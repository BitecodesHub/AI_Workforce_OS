# AI Workforce OS

An enterprise multi-agent platform for business operations. A company configures a team of
role-specialised AI agents, Chat answers from the company's own documents with citations, and the
agents act on business tools through Model Context Protocol servers: GitHub, Slack, Notion,
Linear, HubSpot and webhooks can be connected live with a token, and every other connector works
with practice data. Every action is permission-checked, sensitive actions wait for a person's
approval, and approval decisions and run outcomes are written to a hash-chained audit log.

Course project for Web Services & Service-Oriented Architecture (IT644), Autumn 2026.

## Running it

Nothing needs configuring. The platform starts with an offline sandbox model and sandbox tool
drivers, so it is fully demonstrable with no API key anywhere. Five demo accounts, one per role
(`owner`/`admin`/`manager`/`employee`/`viewer`, all `@demo.aiworkforce.os`), are seeded on startup
and listed on the sign-in screen with their shared password, unless demo data is switched off
with `AIWOS_DEMO_ENABLED=false`.

**One-click launcher** — for a demo machine with only Docker Desktop: double-click
`Start AI Workforce OS.command` (macOS) or `Start AI Workforce OS.bat` (Windows), and the matching
Stop file to stop it. The app opens on http://localhost:4173, reachable from this computer only.

- The first start writes per-install secrets to `infra/launcher/.env` (gitignored): the master key
  that encrypts stored provider and connector keys, the internal service secret and the database
  password. They are never regenerated; keep that file. An install created by an earlier launcher
  keeps its old, published key so stored keys stay readable, and says how to replace it
  (`--new-keys`).
- Demo data is on by default, for sales demos; the first start asks, and `--no-demo` turns it off.
- `AIWOS_EXPOSE_LAN=1` shares the app on the network, and is refused while demo sign-ins exist.
- Give Docker at least 6 GB of memory (Docker Desktop, Settings, Resources); the launcher warns
  when it has less.
- Events are switched off, so the launcher no longer runs Redpanda. An older install's
  `aiwos-app_redpanda-data` volume is left in place, and the launcher says how to remove it.

Two ways to run it for development, both verified:

**Docker Compose** — the whole stack, as originally designed:

```bash
make up          # the whole stack, built and started
make web-install && make web-dev   # the web client on :5173
```

**Locally, without Docker for the services** (what this build was actually developed and
verified against — a machine without a Docker daemon running for the application containers,
though Redis and Qdrant below do need one):

```bash
make build                 # mvn install -DskipTests; the format check is `make lint`, on JDK 21
make dev-backend            # the seven business services against PostgreSQL on 55432
make dev-backend ARGS=--with-gateway   # the same, plus the gateway when Redis answers
make dev-status             # which services are ready; non-zero if any is not
cd web && pnpm install && pnpm dev
```

`dev-backend` runs each service from a copy of its jar in `~/.aiwos-dev/run`, so a rebuild cannot
pull a jar out from under a running service, and writes logs to `~/.aiwos-dev/logs` (the previous
run's as `<name>.log.1`). It waits for every service's readiness check and exits non-zero, naming
each service that did not become ready, with the end of its log.

The gateway needs Redis for rate limiting, and vector search needs Qdrant; `dev-backend` starts
neither. On a machine with Docker installed but not otherwise used for the stack:

```bash
brew install redis && brew services start redis        # or: docker run -d -p 6379:6379 redis:7.4.11-alpine
docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant:v1.19.1   # vector store, for meaning-based search
```

Both are optional in the sense that the platform degrades correctly without them — rate limiting
and vector search are simply unavailable, and every screen that depends on either says so — but
both are real and both have been run this way.

| Surface | Address |
|---|---|
| Gateway and merged API documentation | http://localhost:8080/swagger-ui.html |
| Web client | http://localhost:5173 |
| Grafana | http://localhost:3001 |

`make help` lists every target.

## Architecture

Eight independently deployable Spring Boot applications. Each owns its database schema and its
migration history and never reads another service's tables.

| Service | Port | Responsibility |
|---|---|---|
| `gateway` | 8080 | Routing, edge authentication, rate limits, merged OpenAPI |
| `identity-service` | 8081 | Users, sessions, roles, permissions, token signing, JWKS |
| `org-service` | 8082 | Workspaces, membership, policies, encrypted credentials |
| `orchestrator-service` | 8083 | Agents, goals, runs, approvals, model routing |
| `memory-service` | 8084 | Working, episodic and semantic shared memory |
| `knowledge-service` | 8085 | Ingestion, embedding, cited retrieval |
| `integrations-service` | 8086 | MCP servers, scopes, sandbox drivers |
| `analytics-service` | 8087 | Read models, dashboards, audit projection |

Shared libraries: `platform-core` (context, errors, configuration, cryptography, resilience),
`platform-web` (the servlet half, which the reactive gateway does not use), `platform-contracts`,
`llm-core` and `mcp-core`.

Stack: Java 21 · Spring Boot 3.5 · Spring Cloud 2025.0 · PostgreSQL 17 · Redis · Qdrant ·
Redpanda (wired, not yet used) · React 19 + Vite + TypeScript.

## Two decisions worth knowing

**Language models are configuration, not code.** `llm-core` holds one provider interface and
adapters for OpenRouter, NVIDIA NIM, Groq, OpenAI, Anthropic, Google Gemini and AWS Bedrock, plus
a deterministic sandbox provider. Providers and models are database rows, so adding one is a row
and a credential. The router tries an ordered chain, disqualifies a candidate before spending a
request when it is disabled, uncredentialled, lacking a capability, too small for the
conversation or over budget, and records every attempt and every skip with its reason. A safety
refusal deliberately stops the chain rather than shopping the same prompt to another vendor.

**Authorisation is data, with one deliberate exception.** Roles, their composition and their
assignment are rows a workspace edits in the console. Permission *codes* are fixed by the build,
because a code no endpoint checks grants nothing. Every service re-checks authorisation itself;
the gateway's check is a first pass, not a control.

## State of the work

Everything described below is built and compiles, and the rows say how far each part has been
exercised: most live end to end, with every service running together against a real PostgreSQL
instance, a real Redis and a real Qdrant, and some only partly, as stated.

| Area | State |
|---|---|
| Build, shared libraries, configuration, cryptography, resilience, observability | Built and verified |
| Identity, sessions, RBAC, token signing and JWKS | Built and verified |
| Organisations, envelope-encrypted credentials, working hours, invitations | Built and verified |
| `llm-core`: seven providers, sandbox, router, usage accounting | Built, tested, and live-verified against OpenRouter |
| Budgets: a guard checked before every model call | Partial: the guard is enforced and unit-tested, but no screen or endpoint sets a cap, so no workspace has one |
| `mcp-core`: tool gateway, argument validation, a catalogue of 19 connectors plus voice notes; live adapters for GitHub, Slack, Notion, Linear, HubSpot and webhooks, practice data for the rest | Built and unit-tested |
| Orchestrator: agents, versions, task graph, runs, approvals, model routing policy, reaper | Built and verified |
| Memory: working and episodic storage, compaction | Partial: storage only, not yet used by agents. No run reads or writes memory, compaction and retention never run, and there is no screen to inspect or forget a memory |
| Knowledge: chunking, keyword retrieval with citations, a Qdrant path for meaning-based search | Built; keyword search live-verified. Meaning-based search needs an embedding model to be configured: by default every source uses the sandbox embedder, whose vectors are random, so retrieval is in effect keyword search |
| Integrations: connections, scopes, tool invocation records | Built and verified |
| Analytics: hash-chained audit log of approval decisions and run outcomes, dashboards | Built. Nothing re-walks the chain yet (no endpoint or job verifies it), and sign-ins, member and role changes are not recorded |
| Web client: 22 screens, design system, design-system tests, a usability pass for first-time evaluators, a UI/UX polish pass against the UI UX Pro Max guidelines, an axe-core WCAG 2.2 AA audit of every page at laptop and mobile widths | Built, tested, and checked live at 375px, 1366px and 1440px |
| Workforce Chat: one conversation with every agent; @mention, model-planned or keyword routing, each reply saying who took the work and why; multi-agent chains with handoffs; inline approvals; document questions answered from Knowledge; a General Employee fallback so no request ever dead-ends; agents can ask a clarifying question mid-run, answerable as a question card, by typing in the composer, or from the Orchestrator; a collapsible sidebar, grouped conversations and a Work panel on wide screens | Built and live-verified against OpenRouter and the sandbox provider |
| Orchestrator: live flow map of the coordinator and agents, per-agent swimlanes, a board of queued, working, waiting and finished work with who asked for it, a "Needs you" inbox of open questions and approvals ordered by urgency, pause per agent, stop everything | Built and live-verified |
| Schedules: plain-English timetables ("every weekday at 9am", "tomorrow at 3pm") echoed back with the next five runs in the workspace timezone; pause, resume, run now; auto-pause after three failures | Built and live-verified |
| Voice (ElevenLabs): speak to Chat and hear replies, a voice per agent, and a voice-note tool for agents; the browser's own speech is used until a key is stored | Built; verified live with the browser fallback, ElevenLabs path unit-tested against a mocked API (no key available) |
| Public home page (`/home`), written for business buyers: plain-language offer, how it works in three steps, the AI team, two demos (approval, cited answers) behind tabs, safety promises with a who-can-do-what table, questions and answers | Built, tested, axe-clean at 375px and 1366px |
| Page for IT and security teams (`/trust`): all four technical demos (approval gate, provider failover, audit chain, cited retrieval), the full permission explorer, what runs, and the limits | Built, tested, axe-clean at 375px and 1366px |
| Sign in, create a workspace, accept an invitation: one shared layout, a one-row demo-role picker, show-password control, a stepper for sign-up | Built, tested, live-verified |
| Gateway: routing, JWT verification, Redis-backed rate limiting that lets requests through when Redis is down | Built; its route table is checked against the dev proxy and the launcher by `GatewayRoutesTest`, and it was live-verified with every service (401 at the edge without a token). The dev proxy and the launcher's nginx call the services directly, so everyday use does not pass through it |
| Containers, compose stack, Kubernetes manifests, CI | Built |

Verified running, not merely compiled. All eight services, including the gateway, start together
and stay healthy; sign-in issues an ES256 token, and every service verifies that token
independently through the published key set. Role-based access is enforced inside each service:
an employee is refused `/api/roles` and `/api/providers` with 403, an unauthenticated request gets
401 at the gateway itself, and the seeded providers and tool servers are served from the
database. A stored OpenRouter credential was routed to live, for both a plain completion
and a tool-calling run that parked for a real approval and resumed afterward. The two project PDFs
are indexed as 60 passages, and keyword search returns genuinely ranked, cited passages from them -
a query for "approval queue human review sensitive actions" among them, not a fabricated sample.
The vector half runs end to end against a local Qdrant, but with the default sandbox embedder its
vectors are random, so it adds nothing until an embedding model is configured.

Not yet done, and worth stating plainly:

- **Most connectors work with practice data only.** GitHub, Slack, Notion, Linear, HubSpot and
  webhooks connect live with a token or address an administrator adds. Gmail, Google Calendar,
  Drive, Sheets, Outlook, Teams, Zoom and Salesforce need an OAuth application per provider, which
  is a business decision, not something a code change can supply on its own; Jira, Confluence,
  Asana, Zendesk and Stripe have no live adapter yet. No real mailbox or calendar is connected.
- **Agents do not use memory yet.** The memory service stores episodes, but no run reads or
  writes them.
- **A revoked permission lingers until the next refresh.** Nothing compares a token with the
  role's current permissions yet, so a removed member, a narrowed role or a sign-out everywhere is
  felt when the access token is next refreshed. Access tokens live five minutes to bound that.
- **Kafka is wired but unused.** The envelope, topics and idempotency table exist; the orchestrator
  drives tasks synchronously rather than over the bus. Moving execution onto the bus is an
  architectural change this project does not yet need, not a defect. The one-click launcher runs
  no broker at all.
- **Only upload ingestion works.** A document can be uploaded, extracted, chunked, indexed and
  cited. The Drive, Notion, Confluence and GitHub wiki connectors are not written, so nothing
  crawls a source automatically yet.

A subsequent usability pass reviewed the signed-in console for a first-time evaluator, then a daily
operator: 201 candidate findings, verified down to 67, closed raw UUIDs and status codes, durations
like "84817s", literal markup in the Model Routing cost cells, and static banners claiming no model
was configured when a live OpenRouter key was working — plus added a `/runs` list page, search and
filtering on every long list, and a getting-started guide scoped to what each role can do. Verifying
it live, rather than assuming the sandbox-tested path generalised, surfaced a real, reproduced
infinite approval loop: a resumed run rebuilt its conversation without the tool call it had just
been approved for, so the model repeated the same request indefinitely. Fixed by persisting each
tool call's id and arguments and reconstructing the exchange correctly on resume — confirmed live,
where the HR agent's send-email flow had looped three times before and completed in one afterward.

The workforce Chat, Orchestrator and Schedules work first had to fix the engine underneath them. A
run used to execute inside one database transaction, so nobody else could see it or cancel it
until it ended; an approved action was never actually carried out after approval (the model was
told it had been); task dependencies were declared but ignored, and no result passed from one
agent to the next. Each is fixed and tested: steps commit as they happen, an approved call runs
exactly once when its run resumes, tasks run as a dependency graph with the earlier agents' results
handed over, and work started from Chat or a schedule runs in the background. Verifying it live
against OpenRouter also found that tool names with a dot were rejected by some models, and that
an out-of-credit reply (HTTP 402) was misfiled as an unknown error; both are fixed.

On 29 September 2026, Chat and the Orchestrator were extended again: a run can now pause
(`waiting_input`) when an agent asks a clarifying question through an internal tool, and resumes
exactly once with the person's answer, never past a newer open question or approval. A new
General Employee fallback agent, found by a flag rather than by name, takes any request no
specialist matches, so chat never dead-ends. Both pages were rebuilt for space and interaction —
a collapsible sidebar and grouped conversation list in Chat, a "Needs you" inbox and a goal sheet
in the Orchestrator. See [`docs/2026-09-25_Test-and-Fix-Plan_v1.md`](docs/2026-09-25_Test-and-Fix-Plan_v1.md)'s
"Clarifying questions, General Employee and the Chat/Orchestrator redesign" section for full detail
and live-verification notes.

See [`docs/2026-09-25_Test-and-Fix-Plan_v1.md`](docs/2026-09-25_Test-and-Fix-Plan_v1.md) for the
full defect register (86 entries, all fixed) and the journeys walked to close it, including a
full role-by-role walkthrough (owner, admin, manager, employee, viewer, each signed in for real)
that found and fixed six more defects: two permission-gate bugs that hid data a role legitimately
had access to, one backend endpoint gated too strictly for a page every role should be able to
open, and a systemic pattern of mutating buttons rendered with no permission check at all -
including an Approve/Reject pair that used a mutation function's truthiness (always true) as its
permission check.

A final production sweep (28 September 2026) closed the register: speech without a stored
ElevenLabs key now correctly returns 409 rather than 422; a chat reply's screen-reader
announcement no longer freezes on the generic "an agent" wording when the agent-name lookup is
still loading; the console and public pages are code-split by route, cutting the main JS bundle
from 657 kB to 459 kB; and a sweep for secrets, debug statements and TODOs came back clean.

## Retrieval, and why the query is written the way it is

Keyword search combines terms with OR and applies a relevance floor, rather than using
PostgreSQL's `plainto_tsquery`. That function requires every word to appear in one passage, so a
question phrased naturally — "approval queue human review sensitive actions" — matched nothing at
all even though the corpus discusses exactly that, and the failure looked like an empty index
rather than a wrong query.

OR alone over-matches: a question about zebra migration matches a passage about *database*
migration, because one common term is enough. Measured against this corpus, passages that
genuinely answer a question rank between 0.037 and 0.056 while an incidental single-term match
ranks 0.015, so the floor sits at 0.03. It is a parameter rather than a literal, because the right
value depends on the corpus.

Without that floor, every query matches something, and "no document supports an answer" — the
honest response, and the one that stops an agent inventing one — becomes unreachable.

## Signing algorithm

Tokens are signed ES256, not EdDSA as first built. Nimbus documents
`OctetKeyPair.toPublicKey()` as unsupported, so an Ed25519 key published through a JWKS endpoint
cannot be turned into a verifier by the standard JWT processor — it fails with "no matching
key(s) found", which points at the key set rather than at the algorithm. ES256 is supported
natively by the JDK, needs no third-party crypto library, and is equally sound.

## Font

The whole web client uses one typeface, **Inter** (Rasmus Andersson, SIL Open Font License 1.1),
at weights 400, 500 and 600. It is self-hosted from the `@fontsource-variable/inter` package,
imported once in `web/src/main.tsx`, so no font CDN is involved; `web/src/styles/fonts.css`
describes the metric-matched fallback used while it loads.

## Tests

```bash
make verify      # formatting (JDK 21), unit and slice tests
make test-it     # integration tests, needs Docker
make design-check
```

The format check is not part of the default Maven build, so `make build` and `make test` work on a
newer JDK too. CI should run it with `mvn verify -Pformat-check` on JDK 21; the workflow in
`.github/workflows/ci.yml` does not do so yet. `.java-version` names the JDK the build targets.

# AI Workforce OS

## Team & Module Ownership

| Module | Owner | GitHub |
|--------|-------|--------|
| Platform Core, Contracts | aum2606 | @aum2606 |
| Gateway, Auth, Organisations | Param2725 | @Param2725 |
| AI Employee Agents & Runtime | PanthilShah | @PanthilShah |
| Frontend / Web Client | tempyash007 | @tempyash007 |
| Agent Orchestration & Memory | LoveShah21 | @LoveShah21 |
| Knowledge Base & RAG Pipeline | fahim0-3 | @fahim0-3 |
| DevOps, Testing, Documentation | Afif-Momin | @Afif-Momin |
| MCP Core | BitecodesHub | @BitecodesHub |

## Services

- **gateway** - API gateway (port 8080)
- **identity-service** - Authentication & authorization (port 8081)
- **org-service** - Workspace & organisation management (port 8082)
- **orchestrator-service** - Agent orchestration & chat (port 8083)
- **memory-service** - Episodic & semantic memory (port 8084)
- **knowledge-service** - Document ingestion & retrieval (port 8085)
- **integrations-service** - External tool integrations (port 8086)
- **analytics-service** - Audit logs & analytics (port 8087)

