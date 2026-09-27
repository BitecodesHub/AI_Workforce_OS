# AI Workforce OS

An enterprise multi-agent platform for business operations. A company configures a team of
role-specialised AI agents, the agents share memory and answer from the company's own documents,
and they act on real business tools through Model Context Protocol servers. Every action is
permission-checked and logged, and sensitive actions wait for a person's approval.

Course project for Web Services & Service-Oriented Architecture (IT644), Autumn 2026.

## Running it

Nothing needs configuring. The platform starts with an offline sandbox model and sandbox tool
drivers, so it is fully demonstrable with no API key anywhere. Five demo accounts, one per role
(`owner`/`admin`/`manager`/`employee`/`viewer`, all `@demo.aiworkforce.os`), are seeded on startup
and listed on the sign-in screen with their shared password.

Two ways to run it, both verified:

**Docker Compose** — the whole stack, as originally designed:

```bash
make up          # the whole stack, built and started
make web-install && make web-dev   # the web client on :5173
```

**Locally, without Docker for the services** (what this build was actually developed and
verified against — a machine without a Docker daemon running for the application containers,
though Redis and Qdrant below do need one):

```bash
make build                 # or: mvn clean install
make dev-backend            # the seven business services, from their jars, against PostgreSQL on 55432
# separately, the gateway (needs Redis; see below) and the web client:
java -jar services/gateway/target/gateway.jar
cd web && pnpm install && pnpm dev
```

The gateway needs Redis for rate limiting, and vector search needs Qdrant; neither is started by
`dev-backend`. On a machine with Docker installed but not otherwise used for the stack:

```bash
brew install redis && brew services start redis        # or: docker run -d -p 6379:6379 redis
docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant   # vector store, for the dense half of retrieval
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
Redpanda · React 19 + Vite + TypeScript.

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

Everything described below is built, compiles, and has been exercised live end to end — every
service running together against a real PostgreSQL instance, a real Redis, and a real Qdrant, not
merely compiled in isolation.

| Area | State |
|---|---|
| Build, shared libraries, configuration, cryptography, resilience, observability | Built and verified |
| Identity, sessions, RBAC, token signing and JWKS | Built and verified |
| Organisations, envelope-encrypted credentials, working hours, invitations | Built and verified |
| `llm-core`: seven providers, sandbox, router, budgets, usage accounting | Built, tested, and live-verified against OpenRouter |
| `mcp-core`: tool gateway, argument validation, six sandbox servers | Built and verified |
| Orchestrator: agents, versions, task graph, runs, approvals, model routing policy, reaper | Built and verified |
| Memory: working, episodic, compaction | Built |
| Knowledge: chunking, embeddings, Qdrant, hybrid retrieval with citations | Built and live-verified, including the dense half |
| Integrations: connections, scopes, tool invocation records | Built and verified |
| Analytics: audit hash chain, live-emitted events, dashboards | Built and verified |
| Web client: 20 screens, design system, design-system tests, a usability pass for first-time evaluators | Built and tested |
| Public home page: interactive console, approval-gate, provider-failover, audit-chain and cited-answer demos, role explorer | Built, tested, and adversarially reviewed |
| Gateway: routing, JWT verification, Redis-backed rate limiting | Built and live-verified |
| Containers, compose stack, Kubernetes manifests, CI | Built |

Verified running, not merely compiled. All eight services, including the gateway, start together
and stay healthy; sign-in issues an ES256 token, and every service verifies that token
independently through the published key set. Role-based access is enforced inside each service:
an employee is refused `/api/roles` and `/api/providers` with 403, an unauthenticated request gets
401 at the gateway itself, and the eight seeded providers and six sandbox tool servers are served
from the database. A stored OpenRouter credential was routed to live, for both a plain completion
and a tool-calling run that parked for a real approval and resumed afterward. The two project PDFs
are indexed as 60 passages with both halves of retrieval working: keyword search and, now that a
local Qdrant is running, vector search — a query for "approval queue human review sensitive
actions" returns genuinely ranked, cited passages from the source documents, not a fabricated
sample.

Not yet done, and worth stating plainly:

- **OAuth flows for the tool servers are not implemented.** The sandbox drivers are complete and
  the connection model is in place; the authorisation-code exchange is not written, because it
  needs a registered OAuth application per real provider (Google, Slack, GitHub, ...) — a business
  decision, not something a code change can supply on its own.
- **Kafka is wired but unused.** The envelope, topics and idempotency table exist; the orchestrator
  drives tasks synchronously rather than over the bus. Moving execution onto the bus is an
  architectural change this project does not yet need, not a defect.
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

See [`docs/2026-09-25_Test-and-Fix-Plan_v1.md`](docs/2026-09-25_Test-and-Fix-Plan_v1.md) for the
full defect register (76 entries, all fixed) and the journeys walked to close it, including a
full role-by-role walkthrough (owner, admin, manager, employee, viewer, each signed in for real)
that found and fixed six more defects: two permission-gate bugs that hid data a role legitimately
had access to, one backend endpoint gated too strictly for a page every role should be able to
open, and a systemic pattern of mutating buttons rendered with no permission check at all -
including an Approve/Reject pair that used a mutation function's truthiness (always true) as its
permission check.

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

## Tests

```bash
make verify      # formatting, unit and slice tests
make test-it     # integration tests, needs Docker
make design-check
```
