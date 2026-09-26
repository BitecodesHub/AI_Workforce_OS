# AI Workforce OS

An enterprise multi-agent platform for business operations. A company configures a team of
role-specialised AI agents, the agents share memory and answer from the company's own documents,
and they act on real business tools through Model Context Protocol servers. Every action is
permission-checked and logged, and sensitive actions wait for a person's approval.

Course project for Web Services & Service-Oriented Architecture (IT644), Autumn 2026.

## Running it

Nothing needs configuring. The platform starts with an offline sandbox model and sandbox tool
drivers, so it is fully demonstrable with no API key anywhere.

```bash
make up          # the whole stack, built and started
make web-install && make web-dev   # the web client on :5173
```

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

Everything described above is built and compiles; the backend and web test suites pass, and all
eight migration sets have been applied to a real PostgreSQL instance.

| Area | State |
|---|---|
| Build, shared libraries, configuration, cryptography, resilience, observability | Built |
| Identity, sessions, RBAC, token signing and JWKS | Built |
| Organisations, envelope-encrypted credentials, working hours | Built |
| `llm-core`: seven providers, sandbox, router, budgets, usage accounting | Built and tested |
| `mcp-core`: tool gateway, argument validation, six sandbox servers | Built |
| Orchestrator: agents, versions, task graph, runs, approvals, reaper | Built |
| Memory: working, episodic, compaction | Built |
| Knowledge: chunking, embeddings, Qdrant, hybrid retrieval with citations | Built |
| Integrations: connections, scopes, tool invocation records | Built |
| Analytics: audit hash chain, daily rollups | Built |
| Web client: 16 screens, design system, design-system tests | Built and tested |
| Containers, compose stack, Kubernetes manifests, CI | Built |

Verified running, not merely compiled. All seven business services start against PostgreSQL,
sign-in issues an ES256 token, and every service verifies that token independently through the
published key set. Role-based access is enforced inside each service: an employee is refused
`/api/roles` and `/api/providers` with 403, an unauthenticated request gets 401, and the eight
seeded providers and six sandbox tool servers are served from the database.

Not yet done, and worth stating plainly:

- **No live provider call has been made.** Each adapter is written against its vendor's documented
  API and its failure classification is covered by sixteen tests against recorded response shapes,
  but no request has gone to OpenRouter, Groq, NVIDIA, Gemini, Bedrock, Anthropic or OpenAI with a
  real key.
- **OAuth flows for the tool servers are not implemented.** The sandbox drivers are complete and
  the connection model is in place; the authorisation-code exchange is not written.
- **Kafka is wired but unused.** The envelope, topics and idempotency table exist; the orchestrator
  drives tasks synchronously rather than over the bus.
- **The gateway has not been started.** It needs Redis for rate limiting, which was not available
  on the machine this was built on. The seven services behind it were verified directly.
- **Only upload ingestion works.** A document can be uploaded, extracted, chunked, indexed and
  cited — the two project PDFs are indexed as 60 passages, and a search returns them with page
  numbers. The Drive, Notion, Confluence and GitHub wiki connectors are not written, so nothing
  crawls a source automatically yet.
- **Vector search is untested against a running Qdrant.** Docker was unavailable, so retrieval
  has only been exercised on its keyword half. That half degrades correctly and says so, which is
  the behaviour the design intends, but the dense ranking has not been observed working.

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
