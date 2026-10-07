# Product readiness loop — 3 October 2026

Working log and contract for the self-paced improvement loop the user started on 3 Oct 2026.
Goal: connectors in the navbar, connectors attachable to agents with chosen capabilities, more
important connectors, a lighter modern typeface, and a system that is ready to sell. Each loop
iteration updates the "Progress" section at the bottom.

## Decisions

- **Typeface.** The user asked for "a better modern one, not so thick". This replaces the earlier
  Aperçu-only rule (Aperçu's licensed files were never added, so the site rendered in a heavy
  system fallback). New face: **Inter** (SIL OFL), self-hosted from the `@fontsource-variable/inter`
  package, so there is no font CDN. Headings drop to 600 and body copy stays at 400.
- **Connectors stay honest.** A connector is in one of three states: *Sandbox* (seeded practice
  data, nothing leaves the machine), *Connected* (a live token is stored and verified), or
  *Needs attention* (the stored token failed its last check). A connector that has no live
  implementation says "Sandbox only" and never pretends otherwise.
- **Live where a token is enough.** Connectors that authenticate with a token pasted by an admin
  get real API adapters: GitHub, Slack, Notion, Linear, HubSpot and Webhook. OAuth-only services
  (Gmail, Google Calendar, Google Drive, Microsoft 365 Outlook and Teams, Salesforce, Zoom) remain
  sandbox-only until OAuth app registration is built.
- **A live adapter falls back to the sandbox when no token is stored**, so every agent keeps working
  in a demo workspace, and the same tool names, scopes and side-effect classes apply either way.

## API contract

### mcp-core

- `ConnectorCatalog` (data, like `SandboxServerRegistry`): one `ConnectorInfo` per server:
  `server`, `displayName`, `category` (one of `communication`, `productivity`, `engineering`,
  `sales`, `support`, `files`, `finance`, `automation`, `voice`), `description` (one plain sentence),
  `authType` (`token` | `oauth` | `url` | `none`), `liveAvailable` (boolean),
  `tokenLabel` (for example "Personal access token"), `setupSteps` (list of plain sentences
  telling an admin where to get the token), `docsUrl`.
- New sandbox servers (each with read, write, outbound and destructive tools where they make sense,
  plus a few seeded records so a first "list" returns something): `outlook`, `teams`, `notion`,
  `linear`, `hubspot`, `salesforce`, `zendesk`, `confluence`, `asana`, `sheets`, `stripe`
  (read-only plus refund as DESTRUCTIVE), `zoom`, `webhook`.
- Live adapters (token-based, used only when a credential is present; otherwise the sandbox
  answers): `github`, `slack`, `notion`, `linear`, `hubspot`, `webhook`. Each implements
  `healthCheck(credential)` with a real "who am I" call and returns an account label.

### integrations-service

- `GET /api/integrations` — unchanged shape plus catalog fields on each item:
  `category`, `description`, `authType`, `liveAvailable`, `tokenLabel`, `setupSteps`, `docsUrl`,
  `lastError`, `lastCheckedAt`.
- `PUT /api/integrations/{server}/connection` (permission `integration:connect`) body
  `{ "token": "...", "accountLabel": "optional" }`. Verifies the token with the adapter's health
  check before storing; stores it encrypted (`EnvelopeEncryptionService`); sets
  `status=connected`, `sandbox=false`, `connectedAt`, `accountLabel`. 422 `connector_not_live`
  when `liveAvailable` is false; 422 `connector_check_failed` with a plain message when the check
  fails. Returns the updated `ConnectionView`. Audited.
- `POST /api/integrations/{server}/test` (permission `integration:read`) — re-runs the health check
  against the stored token; returns `{ "ok": boolean, "message": "...", "checkedAt": "..." }` and
  records `lastError`.
- `DELETE /api/integrations/{server}/connection` (permission `integration:disconnect`) — clears the
  credential, returns the server to sandbox. 204. Audited.
- `GET /internal/connections/{server}/credential` decrypts before returning.

### orchestrator-service

- `PUT /api/agents/{agentId}/grants/{server}` (permission `agent:update`) body
  `{ "tools": ["list_issues", ...], "requireApproval": false, "maxCallsPerRun": 20 }`.
  Empty `tools` means every tool on the server. Unknown server or tool names → 422 with the bad
  name. Scopes are filled from the tools' required scopes, so a grant can never be created that
  the gateway will then refuse for a missing scope. Upsert; returns the agent's `AgentDetail`.
  Audited.
- `DELETE /api/agents/{agentId}/grants/{server}` (permission `agent:update`) — removes the grant;
  returns the `AgentDetail`. Audited.
- The General Employee and every agent keep working with zero grants.

### web

- Navbar: a top-level **Connectors** item (`/connectors`, needs `integration:read`). `/integrations`
  redirects to `/connectors`. The settings menu entry is renamed to match.
- Connectors page: search, category filter, status filter; one card per connector with its
  status, a plain description, its capabilities grouped as *Reads*, *Creates and edits*,
  *Sends (asks first)* and *Deletes (asks first)*, which agents use it, and Connect / Test /
  Disconnect for admins. The connect dialog shows the setup steps and a token field.
- Agent detail: a **Connectors** section with **Add connector**. The dialog picks a connector,
  then the capabilities (checkboxes, reads ticked by default), "always ask before acting", and a
  per-run call limit. Each granted connector can be edited or removed.
- Plain words everywhere; design-system test rules hold (no hex in .tsx, tokens only, every route
  keeps an `<Eyebrow>`, no "!" or emoji in JSX text).

## Progress

- Iteration 1 (3 Oct): fixed the orchestrator start failure (`V9__approval_action_method.sql`,
  duplicate `created_at` mapping removed) and wrote this contract. Backend and web work started.
- Iteration 1 findings from live testing (before the restart):
  - The running services were started before their jars were rebuilt on 2 Oct, so classes failed
    to load lazily (`ClassNotFoundException ... ThrowableProxy`). A chat run died silently and stayed
    "running"; Analytics hung on "Loading". Root cause is operational, but the engine made it
    invisible: `RunExecutor` caught only `RuntimeException` and `AgentRunner.driveOrFail` only
    `ApiException`. Fixed: unexpected failures now fail the run with a plain message, and `Error`s
    are logged.
  - The model asked to read GitHub issues and post to Slack in one turn, filling the Slack text with
    a placeholder ("[list from github__list_issues call]"). Fixed: sends and deletes requested beside
    a read are held until the reads return (`AgentRunner.heldUntilReadsReturn`, `HeldActionsTest`).
  - Backlog seen so far: agent cards and chat quote the system prompt ("You triage…") instead of a
    third-person description; the Command Map shows model-routing jargon to every role; no
    value/ROI figures (time saved, work completed) anywhere a manager or buyer would look.

## 12-hour programme (started 3 Oct 2026, about 22:20 IST)

The user extended the loop into a 12-hour continuous improvement programme (explore → identify →
prioritize → implement → test → verify → repeat) across UX, connectors, agents, reliability,
security, performance and commercial value. Working method:

1. **Audit** (workflow `product-readiness-audit`): ten read-only lenses (agent engine, chat, work
   operations, security, admin/enterprise, first run, value/ROI, knowledge/memory,
   reliability/performance, frontend quality), each medium+ finding adversarially verified, then
   synthesized into work packages assigned to waves with disjoint file ownership.
2. **Implement in waves**: one engineer agent per package, packages in a wave never share files;
   each wave ends with a full build, the unit and web test suites, a restart of the stack and live
   browser checks of the affected journeys.
3. **Re-audit** the changed areas and repeat until audits come back dry.
- Web connectors work done (tsc, lint, 583 vitest tests and build all pass): Inter at 400/500/600,
  Connectors in the top bar (Runs moved into the menu's "Work" group because the top bar is capped
  at six items), `/integrations` → `/connectors`, new `routes/Connectors.tsx`, `lib/connectors.ts`,
  `components/connectors/{CapabilityList,GrantDialog}.tsx`, grant section on agent detail. "Select
  all" sends the explicit tool list so tools added later are never granted silently. Live endpoints
  still to be exercised once the backend agent restarts the stack.
- Backend connectors done (mcp-core 45, integrations 8, orchestrator 480 tests green): 20 connectors
  with catalog metadata and seeded sandbox data; live adapters for GitHub, Slack, Notion, Linear,
  HubSpot and Webhook; connect/test/disconnect with encrypted tokens and audit entries;
  `AgentGrantController` for grants.
- Coordinator follow-ups: grant changes now need `agent:grant_tools` (admin), not `agent:update`;
  the held-action message tells the model firmly to call the action again with real content;
  gateway `StripPrefix=1` removed (workspaces 404 through the gateway) and
  `validate-group-membership: false` lets the gateway start without the db readiness override;
  connector dialogs say "address" for the webhook instead of "token".
- **Live end-to-end verified in the browser:** connected the Webhook connector to a local receiver,
  granted it to Engineering Manager through the new dialog, asked in Chat, the approval appeared
  inline with the exact JSON, approved, and the receiver got
  `{"event":"deploy_finished","data":{"status":"ok","version":"1.4.2"},...}`.

## Audit result (3 Oct, 23:46)

135 findings across ten lenses; 132 survived adversarial verification (6 critical, 59 high, 61 medium, 6 low). Synthesized into 24 work packages in four waves with disjoint file ownership. Criticals:

- Any self-registered workspace owner can disable LLM providers and models for every tenant on the platform
- Endpoints added in commit 9d4121c bypass tenant isolation: workspace settings authorise everyone and memory trusts a caller-supplied orgId
- Model providers are platform-wide rows, yet every workspace admin can enable or disable them for all tenants
- Token signing key is regenerated in memory on every identity pod; the configured private key is validated but never used
- Any self-registered workspace owner can switch model providers on or off for every tenant, and one tenant's key or credit failures disable models for all
- Orchestrator falls into an infinite render loop whenever Live updates is off, and the setting keeps it broken on every later visit

Work packages:

- Wave 1 · WP01 · Per-workspace model provider state (close cross-tenant provider control) (L)
- Wave 1 · WP02 · Tenant isolation: workspace settings, memory API, credential reveal binding, edge routes and readiness (M)
- Wave 1 · WP03 · Identity: persistent signing keys, password recovery and session hygiene (L)
- Wave 1 · WP05 · Agent run lifecycle: asynchronous starts and approvals, lease heartbeat, reliable stop, correct attribution (L)
- Wave 1 · WP06 · Frontend crash resilience and session continuity (M)
- Wave 1 · WP04 · Roles, invitations, membership lifecycle and offboarding (L)
- Wave 1 · WP07 · Knowledge ingestion correctness and document deletion (L)
- Wave 1 · WP08 · Chat correctness and answer rendering (L)
- Wave 1 · WP09 · Schedules: authorization, audit, owner offboarding and correctness (L)
- Wave 1 · WP10 · Demo and operations readiness: launcher secrets, dev tooling, readiness, honest public claims (L)
- Wave 2 · WP11 · Agent run safety: no duplicate side effects, bounded loops, honest partial results, live versus sandbox truth (L)
- Wave 2 · WP13 · Chat request fidelity, follow-up context and visible sources (L)
- Wave 2 · WP12 · Router resilience and budget enforcement API (L)
- Wave 2 · WP14 · Approval decision experience, history and out-of-app notifications (L)
- Wave 2 · WP17 · Polling and query performance, indexes and web data layer hygiene (L)
- Wave 2 · WP15 · Knowledge retrieval resilience, restricted sources and a search tester (L)
- Wave 2 · WP16 · First-run activation: connect a live model, ready-made assistants, workspace settings and attention (L)
- Wave 3 · WP18 · Grounded and safer agents: document search in every run, current date, untrusted content and secret hygiene (L)
- Wave 3 · WP20 · Agent management controls (M)
- Wave 3 · WP21 · A trustworthy audit log: chain integrity, auditor tools, full coverage and durable delivery (L)
- Wave 3 · WP19 · Manager value, spend and ROI analytics, with answer ratings (L)
- Wave 4 · WP22 · Private conversations (L)
- Wave 4 · WP23 · Human-in-the-loop follow-through: request changes, retry from a run, decisions on the trace, one vocabulary (L)
- Wave 4 · WP24 · Operations: structured logging, tracing, run context, metrics, data retention and capacity (L)

Deferred (with reasons in the audit output): MFA/SSO/SCIM, token streaming, chat attachments, email notifications, workspace export/closure, durable orchestrator audit outbox, per-agent knowledge scoping, real embedding model choice, agent episodic memory wiring, immediate token revocation, and others.

Wave 1 started 3 Oct ~23:55 (workflow implement-wave): WP01–WP10, each implemented, reviewed against its spec with tests, fixed, then integrated with a full reactor build, web checks, a restart and smoke tests.

## Wave 1 result (4 Oct)

All ten packages implemented, reviewed against their specs and integrated. Full reactor build green
(orchestrator 615 tests, identity 89, org 36, knowledge 29, memory 22, llm-core 39, mcp-core 45,
gateway 9, plus opt-in Postgres tests); web tsc, lint, 836 vitest tests and the production build
green; stack restarted healthy with Flyway orchestrator V10, identity V3, knowledge V2.

Highlights: provider and model state is per workspace (no cross-tenant control); workspace settings,
memory and credential endpoints enforce tenant isolation; signing keys persist (ES256, thumbprint
kid) and access tokens last 5 minutes; password reset links, session list and sign-out of other
sessions; role grant guard (no self-promotion, owner-only owner changes); agent runs start
asynchronously with a lease heartbeat and a safe reaper; error boundaries; Orchestrator freeze with
live updates off fixed; chat retry keeps the original request text and Markdown rendering fixes;
schedules need ownership or task:cancel, are audited, and pause when their owner leaves;
re-uploading a document replaces it cleanly; launcher generates its own secrets and drops the Kafka
broker; `dev-backend.sh` runs services from copied jars so rebuilds no longer break them.

Open items carried into wave 2 packages (see each package's carryOver) or deferred: CI spotless
switch (needs a JDK 21 spotless:apply pass before pushing), schedule owner membership check,
identity audit client for password-reset events, docs test-plan updates.

Wave 2 started 4 Oct: WP11 run safety, WP12 router resilience and budgets, WP13 chat fidelity and
sources, WP14 approval experience and notifications, WP15 knowledge retrieval, WP16 first-run
activation, WP17 performance.

## Wave 2 result (4 Oct, Sonnet implementers at the user's request)

All seven packages passed review and integration: orchestrator 962 tests, llm-core 70, knowledge 66,
platform-core 24 and every other module green (plus 26 opt-in Postgres tests); web tsc, lint, 1195
vitest tests and build green; Flyway orchestrator V11 and knowledge V3 applied; stack healthy.

Highlights: a failed task that already changed something is not auto-retried (and a manual retry is
told what was already done); identical calls replay or are refused, loops end as `loop_detected`;
every tool step records its side effect and Live/Practice mode; budgets are enforced with a plain
message and an admin API; follow-up messages carry the prior reply; answers list the Sources given
to the agent; approvals show a readable payload preview, decision history, a four-eyes rule and a
signed workspace webhook; restricted knowledge sources and a search tester; a guided "connect a
model" dialog that tests keys, ready-made agent templates and a workspace Settings page; board and
chat delta polling with new indexes.

Leftovers assigned to the final waves as carry-over: restricted passages visible in chat routing
details (WP18), usage cost queries without an org filter (WP19), bad-key 400 classified as valid
(WP20), concurrent approvers, legacy four-eyes and bulk-approve hiding Bcc/body (WP23), model
over-asking clarifying questions and raw tool-call JSON in answers (WP18). Fixed directly: duplicate
React key for the voice row on Connectors. Docker Desktop memory lowered from 8 GB to 4 GB at the
user's request (launcher still expects 6 GB and warns).

Final waves started 4 Oct: group A WP18, WP19, WP20, WP21, WP24 then group B WP22, WP23, one
integration at the end.

## 6 Oct: live verification and new request

Final waves were cut off by the weekly usage limit on 4 Oct and relaunched on 6 Oct (Sonnet), each
package finishing the partial work left in the tree.

Live verification on the wave-2 build found and fixed:
- **All agent runs failing.** With no ElevenLabs key stored, the credential store answers `{}`;
  `OrgCredentialResolver` mapped that null with `Mono.map`, threw a NullPointerException, and counted
  it as a store outage. The voice-status polls opened the breaker, after which every model call
  failed ("calls to the credential store are paused"). Fixed with `mapNotNull`, plus a test that 20
  lookups of an unset key leave the breaker closed. Hotfixed live from a clean wave-2 copy.
- **401s for ~30 s after a restart.** Services that start before identity fail their first JWKS
  fetch and Nimbus's default rate limit (one fetch per 30 s) then rejects every token. Now 5 s with
  one retry (`ResourceServerConfig`).
- Assigned to WP22 (chat, not started yet): a failed send leaves "Finding the right agent" forever;
  the conversation view does not pick up in-place progress updates until a reload; announcements
  say "The agent"; a clearly HR request routed to the General Employee.
- Seen again live: the Llama 3.3 70B model asks a needless clarifying question (with its answer as
  the options) — already in WP18's carry-over.

New user request: every AI employee (agent) gets its own memory (remember/recall tools, a memory
section people can curate, recalled at run start) and its own knowledge base (agent-owned sources
and notes searched only by that agent). Specified as WP25, to run right after the final waves.

## 6 Oct: final waves finished, WP25 built, one live smoke test

The specs were restored to `docs/wp-specs/` and the tree was treated as the baseline: only what was
broken or missing was changed. Results of the final integration (low-RAM flags, one process at a
time): `./mvnw clean install` green, orchestrator 1,112 tests, llm-core 107, memory 36, knowledge 91,
identity 70, platform-core 33; web `tsc`, lint and build green, vitest 1,306 tests in 110 files.

Done this round:
- **WP18-WP21, WP24**: partial edits reviewed and finished. Fixed a variable clash and a missing
  import in `AgentRunner`, two package-private constants, stale tests (passage tags, the 8-argument
  `Observability` record, a JSON-lines column order), `Observability` binding failures caused by a
  leftover constructor, the audit client configuration being picked up by every service's component
  scan (four services failed to start), org and knowledge audit events (invitations, keys,
  workspace, documents, sources), the audit log page (server filters, export, verify), session sweep,
  episode purge, retention card, Grafana dashboard, Prometheus alerts, per-service connection pools.
- **WP22**: private conversations (V13, `ConversationAccess`, 404 for outsiders in list, search,
  read, send, goals, runs, questions, board), `chat:read_all` (audited, owners only, topped up for
  existing workspaces), share and add-people UI.
- **WP23**: send back with feedback (V14, two-round cap, forced approval afterwards), direct-run
  retry, decisions on the trace, one vocabulary, bulk approve shows the whole request, concurrent
  approvers no longer conflict.
- **WP25**: per-agent memory (memory V2 `agent_memories`, public CRUD, internal remember/recall,
  `memory.remember` and `memory.recall` tools, `memory` server name reserved, recall at run start,
  secrets refused) and per-agent documents (knowledge V5 `sources.agent_id`, notes and files searched
  only by that agent, excluded from workspace lists and search); "What it remembers" and "Its own
  documents" on the agent page.
- **Carry-over**: usage cost queries are org-scoped, HR-type words route to HR, a failed chat send
  stays on screen with Try again.

Live smoke test (Postgres, Redis, eight services): sign-in, agents and integrations lists, a chat
message routed to HR that completed, an agent-owned note found by the agent's search, a memory note
recalled into a run and used in its answer, a secret refused, a private thread invisible to manager
and owner-without-permission then readable after sharing, audit events delivered and the chain verified.
Found and fixed live: the audit configuration scan, a missing identity endpoint for a person's
permissions (agent document search was always "unavailable"), a run-step kind the database rejects,
and owners of existing workspaces missing the new permission.

Not verified live: send back with feedback (the offline model never calls an email tool), the retry
endpoint, in-place progress refresh in the browser, announcements naming the agent. Web tests were run
with one isolated fork at a time, because `singleFork` shares state between test files.
All services, Postgres and Redis were stopped afterwards.
