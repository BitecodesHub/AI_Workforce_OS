# AI Workforce OS on AWS: one-time setup

## Executive summary

- One CloudFormation stack in **ap-southeast-2 (Sydney)** creates everything: one EC2 instance
  (**m7i-flex.large**, 2 vCPU, 8 GiB, Amazon Linux 2023, 30 GB gp3, 4 GB swap), an Elastic IP,
  nine ECR repositories, a GitHub OIDC deploy role, SSM access (no SSH) and a budget alarm.
- The live address has a real HTTPS certificate with no domain purchase:
  `https://<elastic-ip-with-dashes>.sslip.io` (for example `https://3-104-12-5.sslip.io`).
- Every push to `main` runs CI, builds the images, pushes them to ECR with the commit SHA, rolls
  the server forward over SSM and smoke-tests the live address. No AWS keys are stored in GitHub.
- Models: **Amazon Nova Lite** through the `apac.` inference profile first (reached with the
  instance's IAM role, no key to paste), then **Ollama `qwen2.5:1.5b-instruct`** on the server's
  CPU as the last fallback.
- The Free plan pays with credits, not with a permanent free tier: this setup uses roughly
  **US$90-95 of credits a month**. Read "Cost on the Free plan" before creating the stack.

## What you do, once

### 1. Commit and push the deployment files first

The server builds itself from the public repository on its first boot, so `infra/aws/` must be on
`main` before the stack exists. Commit and push as usual. The **Deploy** workflow runs CI; its AWS
jobs are skipped until step 4.

### 2. Create the stack (about 3 minutes, then about 15-20 minutes of first boot)

1. Sign in to the AWS console and switch the region (top right) to **Asia Pacific (Sydney)
   ap-southeast-2**.
2. Open **CloudFormation > Stacks > Create stack > With new resources (standard)**.
3. Choose **Upload a template file** and select `infra/aws/cloudformation.yaml`.
4. Stack name: `aiwos`. Parameters:
   - **BudgetEmail**: your address (required). Confirm the subscription email AWS Budgets sends.
   - **DemoData**: leave `false` for a private install; see "Signing in" before choosing `true`.
   - Leave the rest at their defaults (m7i-flex.large, local model on, Nova Lite).
5. Tick **I acknowledge that AWS CloudFormation might create IAM resources** and create the stack.
6. When the status is **CREATE_COMPLETE**, open the **Outputs** tab and copy `PublicUrl`,
   `InstanceId` and `DeployRoleArn`.
7. Wait about 15-20 minutes: the first boot installs Docker, builds the images on the server (ECR is
   still empty), starts the stack and obtains the certificate. Then open `PublicUrl`.

If the stack fails with "already exists" on `GitHubOidcProvider`, the account already has GitHub's
identity provider: delete the failed stack and create it again with
**CreateGitHubOidcProvider = false**. If it fails because the instance type is not offered in that
Availability Zone, set **AvailabilityZoneIndex** to 1 or 2.

### 3. Turn on Amazon Nova Lite in Bedrock (Sydney)

Open **Amazon Bedrock** in ap-southeast-2 and find **Amazon Nova Lite** in the model catalog. If the
console still shows a **Model access** page or a **Request access** button, enable Nova Lite (and
Nova Micro and Pro if wanted). On accounts where serverless models are enabled automatically there
is nothing to do. Amazon models need no AWS Marketplace subscription, which a Free plan account
without a payment method could not complete; that is why Nova, not Claude, is the default.

Check it from the app: **Model routing > AWS Bedrock > Test now**. If Bedrock is not available to
the account, nothing breaks: runs fall back to the local model.

### 4. Connect GitHub to the stack

In GitHub, open **BitecodesHub/AI_Workforce_OS > Settings > Secrets and variables > Actions >
Variables** (the **Variables** tab, not Secrets) and add:

| Name | Value |
| --- | --- |
| `AWS_DEPLOY_ROLE_ARN` | the `DeployRoleArn` output |
| `AIWOS_INSTANCE_ID` | the `InstanceId` output |

Then deploy: **Actions > Deploy > Run workflow** on `main`, or push any commit to `main`. From now on
every push to `main` deploys. Do not add a GitHub "environment" to the workflow: the deploy role
trusts only `repo:BitecodesHub/AI_Workforce_OS:ref:refs/heads/main`.

## The live address

`https://<a>-<b>-<c>-<d>.sslip.io`, where `a.b.c.d` is the Elastic IP (`PublicUrl` output).
sslip.io is a public DNS service that answers that name with that address, so Caddy on the server
obtains a Let's Encrypt certificate over ports 80 and 443. The address never changes while the stack
exists. Typing the bare IP redirects to the name. If certificate issuance is ever rate-limited, Caddy
retries and falls back to ZeroSSL on its own; `nip.io` is an equivalent name service if needed (edit
`AIWOS_PUBLIC_HOST` in `/opt/aiwos/.env` and the nginx host file, then `sudo aiwos deploy`).

## Signing in

- **DemoData = false (default, recommended).** Open the address and choose **Create a workspace** on the sign-in page;
  the first person to register creates the first workspace and is its owner. Registration is open to
  anyone who finds the address, so register straight away and set a monthly budget for the
  workspace (**Analytics > Budget**).
- **DemoData = true.** The sample workspace and five demo sign-ins (owner, admin, manager, employee,
  viewer) are loaded; the sign-in screen lists them and their shared password, which is also
  published in the source. Anyone who finds the address can sign in and spend Bedrock credits. The
  services refuse demo accounts in production mode, so a demo server runs in `local` mode (still
  HTTPS and private secrets, but the refresh cookie is not marked Secure).

To change it later, open a Session Manager shell (below), edit `DEMO_DATA` in
`/opt/aiwos/bootstrap.env`, and run `sudo aiwos init`. Turning demo data off afterwards does not
delete demo accounts already created; for that, start from empty data.

## Models and routing on the server

- A workspace with no routing of its own uses `bedrock/apac.amazon.nova-lite-v1:0`
  (`AIWOS_DEFAULT_ROUTING`). Bedrock is called with the instance role through IMDSv2 (credentials
  refreshed before they expire); nothing is stored and nothing is logged.
- The local model `ollama/qwen2.5:1.5b-instruct` is appended at run time as the last candidate of
  every chain, including chains a workspace saves itself (`AIWOS_LOCAL_FALLBACK=true`). Saved
  policies are not changed. It answers slowly on two vCPUs (tens of seconds for a long agent
  prompt), which is acceptable for a fallback.
- Why qwen2.5:1.5b-instruct: it calls tools and needs about 1.3 GB with an 8k context. Seven JVMs at
  a 320 MB heap take about 3.8 GB, PostgreSQL, Redis, Qdrant, nginx and Caddy about 1 GB, so the
  1.5B model leaves about 1.5 GB of headroom on 8 GiB. `llama3.2:3b` (about 2.5 GB) fits only with
  swap in use. The **OllamaModel** parameter offers both.
- Smaller instances: **c7i-flex.large** (4 GiB) runs the stack only with **LocalModel = false** and
  swap; **t3.small** (2 GiB) cannot run the full stack and is listed only for a stripped-down test.

## Cost on the Free plan

The AWS Free plan gives credits (US$100 at sign-up, up to US$100 more for completing activities),
for up to six months. Free-plan-eligible resources are paid from those credits; there is no
always-free EC2 allowance. If the credits run out or six months pass, the account must be upgraded
to a paid plan or AWS closes it and later deletes its resources.

| Item | Approximate monthly cost from credits (Sydney) |
| --- | --- |
| m7i-flex.large, running all month | about US$85-90 |
| Elastic IP / public IPv4 | about US$3.60 |
| 30 GB gp3 volume | about US$3 |
| ECR storage (nine repositories, three images each, about 2-3 GB) | under US$0.50 |
| Bedrock Nova Lite | cents for light use (US$0.06 per million input tokens) |
| CloudFormation, SSM Session Manager, two budgets | no charge |

Check current prices in the AWS pricing pages; these are estimates. At this rate, US$200 of credits
lasts a little over two months. To stretch them, stop the instance when nobody needs the demo
(**EC2 > Instances > Stop**); the volume and Elastic IP still cost about US$7 a month while stopped,
and starting it again brings everything back at the same address.

The budget alarm emails at **US$5** and **US$20** of actual monthly spend. With the default
**BudgetCountsCredits = false**, it counts usage before credits are applied, so the alerts show how
fast credits are being used (expect the US$20 alert in the first week). Set it to `true` to be
alerted only about spend the credits do not cover.

## Rolling back

- **From GitHub:** **Actions > Deploy**, open the last good run, **Re-run all jobs**. It redeploys
  that commit's images (ECR keeps the three newest per service).
- **On the server:** open a Session Manager shell and run `sudo aiwos rollback` (the previous
  deployed commit) or `sudo aiwos rollback <commit-sha>`.

Database migrations only add things, and a service ignores migrations newer than itself, so rolling
back the images is safe. Data is kept in Docker volumes and survives deploys and rollbacks.

## Checking logs and status (SSM Session Manager, no SSH)

Open the `SessionManagerUrl` output, or **EC2 > Instances > aiwos > Connect > Session Manager >
Connect**. Then:

```
sudo aiwos status                 # containers, health, deployed commit, memory and disk
sudo aiwos logs orchestrator      # follow one service (identity, organisation, web, caddy, ollama, ...)
sudo aiwos logs                   # follow everything
sudo tail -f /var/log/aiwos-bootstrap.log   # the first boot
```

Each deploy's output is also in **Systems Manager > Run Command > Command history** and in the
GitHub Actions log. `/opt/aiwos/.env` holds the generated secrets; back it up if the data matters,
because stored provider keys cannot be decrypted without it.

## Why not Mumbai (ap-south-1)

This account is on the Free plan, and the plan offered only some regions to it; Mumbai showed as
requiring an upgrade to a paid plan, which needs a payment method the account does not have.
Sydney is the nearest allowed region (roughly 150-200 ms from India, fine for a web console).
After upgrading, the same template works in ap-south-1 unchanged: the `apac.` inference profiles
are offered there too. Pass the region when creating the stack and update `AWS_REGION` in
`.github/workflows/deploy.yml`.

## Files

- `infra/aws/cloudformation.yaml`: the stack.
- `infra/aws/docker-compose.prod.yml`, `infra/aws/Caddyfile`: what runs on the server.
- `infra/aws/aiwos.sh`: first boot, deploy, rollback, status and logs on the server.
- `.github/workflows/deploy.yml`: CI, images, roll-out and smoke test on every push to `main`.
