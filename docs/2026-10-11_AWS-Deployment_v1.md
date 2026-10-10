# AI Workforce OS on AWS: one-time setup

## Executive summary

- One CloudFormation stack in **ap-southeast-2 (Sydney)** creates one EC2 instance
  (**m7i-flex.large**, 2 vCPU, 8 GiB, Amazon Linux 2023, 30 GB gp3, 4 GB swap), an Elastic IP, a
  small VPC with ports 80/443 only, and an instance role for SSM (no SSH) and Bedrock. A budget
  alarm and ECR repositories are optional.
- **Pull deployment, no credentials anywhere.** The server follows `main` of the public repository
  itself: every 2 minutes it fetches, and on a new commit it builds the images on the server,
  starts them, checks health and rolls back automatically if the new commit is not healthy within
  10 minutes. GitHub holds no AWS keys and has no AWS role.
- GitHub shows whether a push went live: the **Deploy** workflow runs CI, then watches
  `https://<host>/deploy-status.json` until the server reports that commit deployed or failed.
- The live address has a real HTTPS certificate with no domain purchase:
  `https://<elastic-ip-with-dashes>.sslip.io` (for example `https://3-104-12-5.sslip.io`).
- Models: **Amazon Nova Lite** through the `apac.` inference profile first (instance role, no key
  to paste), then **Ollama `qwen2.5:1.5b-instruct`** on the server's CPU as the last fallback.
- The Free plan pays with credits: roughly **US$90-95 of credits a month**. Read "Cost on the
  Free plan" before creating the stack.

## Constraints found in account 288272421287

These were hit when the first version of the stack (GitHub OIDC push deployment) was created:

| Finding | Effect on the design |
| --- | --- |
| An AWS Organizations service control policy explicitly denies `iam:CreateOpenIDConnectProvider` | No GitHub OIDC provider and no GitHub deploy role. The server pulls from the public repository instead, so nothing outside AWS needs AWS access. |
| The account cannot use **ap-south-1 (Mumbai)** at all | The stack is for ap-southeast-2 only. |
| Other IAM, Budgets or ECR actions may also be denied | Everything that is not needed to serve the app is behind a parameter (below). If a create fails with "explicit deny", note the resource, delete the failed stack and create it again with that parameter off. |

What the stack needs, at minimum: EC2 (VPC, subnet, internet gateway, route table, security group,
Elastic IP, instance) and, unless **CreateInstanceRole = false**, `iam:CreateRole`,
`iam:PutRolePolicy`, `iam:AttachRolePolicy`, `iam:CreateInstanceProfile`,
`iam:AddRoleToInstanceProfile` and `iam:PassRole`. No launch template is used.

| Parameter | Default | Turn off when |
| --- | --- | --- |
| **CreateBudget** | `true` | `budgets:*` is denied (also skipped while **BudgetEmail** is empty) |
| **CreateEcrRepositories** | `false` | Leave off: the server builds its own images. `true` only adds an off-instance copy of each deployed image. |
| **CreateInstanceRole** | `true` | `iam:CreateRole` or `iam:PassRole` is denied. The app then runs on the local model only (no Bedrock) and there is no Session Manager shell; deploys and `/deploy-status.json` still work. |

## What you do, once

### 1. Push the deployment files first

The server builds itself from `main` of the public repository, so `infra/aws/` must be on `main`
before the stack exists. Commit and push as usual.

### 2. Create the stack (about 3 minutes, then about 20-25 minutes of first deploy)

1. Sign in to the AWS console and switch the region (top right) to **Asia Pacific (Sydney)
   ap-southeast-2**.
2. Open **CloudFormation > Stacks > Create stack > With new resources (standard)**.
3. Choose **Upload a template file** and select `infra/aws/cloudformation.yaml`.
4. Stack name: `aiwos`. Parameters:
   - **BudgetEmail**: your address, to get the budget alarm (confirm the email AWS Budgets sends).
   - **DemoData**: leave `false` for a private install; see "Signing in" before choosing `true`.
   - Leave the rest at their defaults (m7i-flex.large, local model on, Nova Lite, branch `main`,
     CreateInstanceRole `true`, CreateBudget `true`, CreateEcrRepositories `false`).
5. Tick **I acknowledge that AWS CloudFormation might create IAM resources** and create the stack.
6. When the status is **CREATE_COMPLETE**, open the **Outputs** tab and copy `PublicUrl`,
   `PublicHost` and `DeployStatusUrl`.
7. The first deploy runs in the background (Docker install, image builds, start, certificate).
   After about 5 minutes `DeployStatusUrl` answers (Caddy is started alone before the first build;
   expect a certificate warning for a minute or two until Let's Encrypt has issued one). When it
   shows `"result": "deployed"`, open `PublicUrl`. The site itself shows an error page until then.

If the create fails:

- **"explicit deny" on a resource**: see the parameter table above; delete the failed stack and
  create it again with that parameter off.
- **The instance type is not offered in that Availability Zone**: set **AvailabilityZoneIndex** to
  1 or 2.

### 3. Turn on Amazon Nova Lite in Bedrock (Sydney)

Open **Amazon Bedrock** in ap-southeast-2 and find **Amazon Nova Lite** in the model catalog. If the
console still shows a **Model access** page or a **Request access** button, enable Nova Lite (and
Nova Micro and Pro if wanted). On accounts where serverless models are enabled automatically there
is nothing to do. Amazon models need no AWS Marketplace subscription, which a Free plan account
without a payment method could not complete; that is why Nova, not Claude, is the default.

Check it from the app: **Model routing > AWS Bedrock > Test now**. If Bedrock is not available to
the account (or is denied by a policy), nothing breaks: runs fall back to the local model.

### 4. Let GitHub report deploys (optional)

In GitHub, open **BitecodesHub/AI_Workforce_OS > Settings > Secrets and variables > Actions >
Variables** (the **Variables** tab, not Secrets) and add `AIWOS_PUBLIC_HOST` = the `PublicHost`
output (for example `3-104-12-5.sslip.io`). It is not a secret and grants nothing. Without it the
**Deploy** workflow runs CI and skips the live check with a notice; the server deploys either way.

## What happens on a push to main

1. **GitHub**: the **Deploy** workflow runs CI (`ci.yml`), then polls
   `https://<host>/deploy-status.json` every 30 seconds for up to 25 minutes.
2. **Server, within 2 minutes**: `aiwos-update.timer` runs `aiwos update`, which takes a lock
   (`flock`, so runs never overlap) and runs `git fetch origin main`. If the SHA is the one already
   live, it stops there.
3. **Build**: it checks out the new SHA and builds the images one service at a time (BuildKit
   cache; Maven heap capped at 1 GB, Node at 1.5 GB; the backend compiles once and the other images
   reuse it). The local model is paused during the build to free memory. Images are tagged
   `aiwos/<image>:<sha>`. A build failure leaves the live version running untouched.
4. **Start and check**: `docker compose up -d` with the new tag, then it waits until every service
   reports ready and the site answers through Caddy over HTTPS (the web page and identity's
   `/.well-known/jwks.json`).
5. **Healthy within 10 minutes**: the SHA becomes `current_sha`; the images of the current and
   previous SHA are kept, older ones removed. **Not healthy**: it starts the previous SHA's images
   again (automatic rollback), saves container state and logs to `/var/log/aiwos-deploy.log`, and
   does not retry that SHA until a new commit is pushed (or `sudo aiwos update --force`).
6. **GitHub**: the job passes when the status shows the pushed SHA (or a later commit containing
   it) live, and fails when the server reports `build_failed`, `rolled_back`, `rollback_failed` or
   `failed` for it, or after 25 minutes.

The server does **not** wait for CI: a commit that compiles but fails tests is still deployed if it
starts healthy. The Deploy job then shows CI red and the deploy result separately. Each new deploy
restarts all services at once, so the site is unavailable for a few minutes while the JVMs start.

## Seeing deploy status

- **In a browser or with curl**: `https://<host>/deploy-status.json` (the `DeployStatusUrl`
  output). Fields: `current_sha`, `previous_sha`, `phase` (`idle`, `building`, `starting`,
  `rolling_back`), `last_attempt` (`sha`, `result`, `message`, `started_at`, `finished_at`),
  `last_success_at`, `last_check_at`, `skipped_sha` and `tls_certificate_valid`. It contains no
  secrets.
- **In GitHub**: **Actions > Deploy**, job **Wait until live on the server**, and its summary.
- **On the server** (Session Manager, below): `sudo aiwos status`, `sudo journalctl -u
  aiwos-update -f`, `sudo tail -f /var/log/aiwos-deploy.log` (build output is here; rotated
  weekly or at 20 MB, four kept).

## Rolling back

- **Automatic**: a deploy that is not healthy within 10 minutes is rolled back to the previous SHA
  on its own (see above).
- **By hand** (a deploy that is healthy but wrong): open a Session Manager shell and run
  `sudo aiwos rollback` (the previous deployed SHA) or `sudo aiwos rollback <sha>`. Images of the
  last two SHAs are on the server, so this takes a few minutes; an older SHA is rebuilt first. The
  server then holds at that SHA and does not redeploy the rolled-back-from commit; the next push to
  `main` deploys normally.
- **Without a shell**: push a revert commit to `main` (`git revert <sha>`); it deploys like any
  other commit.
- **Deploy a specific commit**: `sudo aiwos deploy <sha>`; retry a skipped commit:
  `sudo aiwos update --force`.

Database migrations only add things, and a service ignores migrations newer than itself, so rolling
back the images is safe. Data is kept in Docker volumes and survives deploys and rollbacks.

## The live address

`https://<a>-<b>-<c>-<d>.sslip.io`, where `a.b.c.d` is the Elastic IP (`PublicUrl` output).
sslip.io is a public DNS service that answers that name with that address, so Caddy on the server
obtains a Let's Encrypt certificate over ports 80 and 443. The address never changes while the stack
exists. Typing the bare IP redirects to the name. If certificate issuance is ever rate-limited, Caddy
retries and falls back to ZeroSSL on its own; a missing certificate never causes a rollback
(`tls_certificate_valid` shows it). `nip.io` is an equivalent name service if needed (edit
`AIWOS_PUBLIC_HOST` and `AIWOS_PUBLIC_BASE_URL` in `/opt/aiwos/.env` and
`/opt/aiwos/config/nginx/hosts.map`, then run `sudo aiwos deploy <current_sha>`, which reuses
the built images and restarts the containers with the new name).

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
`/opt/aiwos/bootstrap.env`, and run `sudo aiwos init` (it restarts the running stack with the new setting). Turning demo data off afterwards does not
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
| ECR storage (only with CreateEcrRepositories = true, two images each) | under US$0.50 |
| Bedrock Nova Lite | cents for light use (US$0.06 per million input tokens) |
| CloudFormation, SSM Session Manager, one budget | no charge |

Check current prices in the AWS pricing pages; these are estimates. At this rate, US$200 of credits
lasts a little over two months. To stretch them, stop the instance when nobody needs the demo
(**EC2 > Instances > Stop**); the volume and Elastic IP still cost about US$7 a month while stopped,
and starting it again brings everything back at the same address.

When **CreateBudget = true** and **BudgetEmail** is set, the budget alarm emails at **US$5** and **US$20** of actual monthly spend. With the default
**BudgetCountsCredits = false**, it counts usage before credits are applied, so the alerts show how
fast credits are being used (expect the US$20 alert in the first week). Set it to `true` to be
alerted only about spend the credits do not cover.

## Shell access and logs (SSM Session Manager, no SSH)

Open the `SessionManagerUrl` output, or **EC2 > Instances > aiwos > Connect > Session Manager >
Connect**. Then:

```
sudo aiwos status                 # deploy status, containers, timer, memory and disk
sudo aiwos logs orchestrator      # follow one service (identity, organisation, web, caddy, ollama, ...)
sudo journalctl -u aiwos-update   # every deploy check, build, health wait and rollback
sudo tail -f /var/log/aiwos-deploy.log      # the same, plus build output
sudo tail -f /var/log/aiwos-bootstrap.log   # the first boot
sudo systemctl stop aiwos-update.timer      # pause automatic deploys (start it to resume)
```

`/opt/aiwos/.env` holds the generated secrets; back it up if the data matters, because stored
provider keys cannot be decrypted without it.

## Why not Mumbai (ap-south-1)

This account cannot use ap-south-1 at all (the Free plan offered it only as an upgrade to a paid
plan). Sydney is the nearest allowed region (roughly 150-200 ms from India, fine for a web
console). The template itself is not tied to Sydney: the `apac.` inference profiles exist in
ap-south-1 too, so after an upgrade the same template can be created there unchanged.

## Files

- `infra/aws/cloudformation.yaml`: the stack.
- `infra/aws/docker-compose.prod.yml`, `infra/aws/Caddyfile`: what runs on the server (Caddy also
  serves `/deploy-status.json`).
- `infra/aws/aiwos.sh`: first boot, the update loop (build, health check, rollback), manual deploy
  and rollback, status and logs.
- `infra/launcher/services.Dockerfile`, `infra/launcher/web.Dockerfile`: shared with the launcher;
  they accept `MAVEN_OPTS` / `NODE_OPTIONS` build arguments (empty by default) for the heap caps.
- `.github/workflows/deploy.yml`: CI, then waits for the server to report the push live.
