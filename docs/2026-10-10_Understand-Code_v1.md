# Understand the code

A guide to the AI Workforce OS repository for developers, managers and teachers. All paths are relative to the repository root. Last checked against the source on 10 October 2026.

Contents

1. What the product is and how the pieces talk
2. Folder-by-folder map
3. How do I find... (index of 60 questions)
4. Search tips: the `@find:`, `@what:` and `@flow:` tags
5. Glossary

---

## 1. What the product is and how the pieces talk

**The product in five sentences.**

1. AI Workforce OS lets a company run a team of role-specialised AI agents (for example HR, Support, Research) that do real business work.
2. People talk to the team in one Chat, give goals to agents, or put recurring work on a Schedule.
3. Agents answer from the company's own documents with citations (the Knowledge base) and act on business tools such as Slack, GitHub and Gmail through connectors.
4. Every action is permission-checked, risky actions (sending, posting, deleting) wait for a person to approve them, and decisions and outcomes are written to a hash-chained audit log.
5. Language models are configuration, not code: a workspace connects any supported provider, sets an ordered list of models, and the router falls over to the next model when one fails; with no key at all, an offline sandbox model and practice data keep every screen working.

**How the pieces talk.**

```
 Browser (React app, web/)
    |
    |  /api/...   (Bearer access token; refresh token is an HttpOnly cookie)
    v
 Development:  Vite dev server proxy (web/vite.config.ts) sends each /api prefix straight to the owning service
 Compose/K8s:  gateway :8080 (services/gateway)  -- checks the JWT, rate-limits via Redis, routes
 Launcher:     nginx (infra/launcher/nginx.conf) in the web container, then the services
    |
    v
 +-------------+ +-------------+ +-------------------+ +-------------+
 | identity    | | org         | | orchestrator      | | memory      |
 | :8081       | | :8082       | | :8083             | | :8084       |
 +-------------+ +-------------+ +-------------------+ +-------------+
 +-------------+ +-------------+ +-------------+
 | knowledge   | | integrations| | analytics   |
 | :8085       | | :8086       | | :8087       |
 +-------------+ +-------------+ +-------------+
    |        \                        |
    |         \  each service owns ONE Postgres schema + its own Flyway migrations
    v          v                      v
 PostgreSQL 17     Redis (gateway rate limit, caches)    Qdrant (vector search, knowledge-service)
                   Kafka/Redpanda (optional; events are off in dev and in the launcher)

 orchestrator-service  --(llm-core ModelRouter)-->  model providers: OpenRouter, NVIDIA NIM, Groq, OpenAI,
                                                    Anthropic, Gemini, AWS Bedrock, or the offline Sandbox
 orchestrator-service  --(mcp-core ToolGateway)-->  tool connectors: live adapter (token or OAuth) or practice-data adapter
 services call each other with short-lived internal tokens that carry the original person (see InternalTokenProvider in each client service)
```

Key facts to remember.

- Eight Spring Boot applications (Java 21, Spring Boot 3.5): the gateway plus seven business services. A service never reads another service's tables; it calls the other service over HTTP.
- Five shared Maven libraries: `platform-contracts`, `platform-core`, `platform-web`, `llm-core`, `mcp-core`.
- The web client is React 19, Vite, TypeScript and TanStack Query, with an in-house router (`web/src/lib/router.tsx`).
- Every service re-checks authorisation itself. The gateway check is only a first pass.

**Notes on accuracy.** `README.md` was last revised on 4 October. In two places the code has moved on: agents now do use memory (`services/orchestrator-service/src/main/java/os/aiworkforce/orchestrator/service/AgentMemoryTool.java` offers the remember and recall tools), and the audit chain is now re-walked nightly and on demand (`services/analytics-service/src/main/java/os/aiworkforce/analytics/service/AuditChainJob.java`, `GET /api/audit/verify`). Trust the code over the README where they differ. The folder `platform-contracts` currently holds package folders only and no Java source files.

---

## 2. Folder-by-folder map

### 2.1 Repository root

| Item | What it is |
|---|---|
| `pom.xml` | Parent Maven build. Lists the 13 modules: the five libraries, then the eight services. |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | Maven wrapper, so nobody needs Maven installed. |
| `.java-version` | Java 21 pin. |
| `Makefile` | Every common command: `build`, `test`, `test-it`, `lint`, `up`, `down`, `dev-backend`, `dev-status`, `dev-stop`, `web-install`, `web-dev`, `web-build`, `web-test`, `design-check`. Run `make help`. |
| `README.md` | Overview, how to run, state of the work (see accuracy note above). |
| `Start AI Workforce OS.command` / `.bat`, `Stop AI Workforce OS.command` / `.bat` | One-click launchers for macOS and Windows. They need only Docker Desktop. See `infra/launcher/`. |
| `Project_Proposal.pdf`, `Project_Plan.pdf` | Course documents. The knowledge base was indexed from them as a real example. |
| `.github/workflows/ci.yml` | CI: backend build and test, web typecheck/build/test, Kubernetes manifest build. |
| `.claude/` | Local Claude Code settings (launch configuration, worktrees). Not part of the product. |
| `.gitignore`, `.gitattributes` | Git settings. |

### 2.2 Shared libraries

#### `platform-core` (context, errors, crypto, permissions; no web dependency)

Package root: `platform-core/src/main/java/os/aiworkforce/platform/`

| Package | Purpose and key files | Backs |
|---|---|---|
| `config/` | `PlatformProperties.java` (typed settings), `PlatformStartupValidator.java` (refuses unsafe settings such as a development master key in a deployed environment). Defaults in `platform-core/src/main/resources/platform-defaults.yml` (token lifetimes, events, resilience). | Every service start |
| `context/` | `Actor.java` (who is acting: person, API key, service), `RequestContext.java` (current actor and workspace for this request). | Every request |
| `crypto/` | `EnvelopeEncryptionService.java` (encrypt/decrypt secrets with a per-value data key wrapped by the master key; workspace id bound in), `EncryptedValue.java`. | Stored API keys and connector tokens |
| `error/` | `ApiException.java`, `ErrorCode.java`, `ProblemResponse.java` (the standard error body). | Every error message the UI shows |
| `event/` | `EventEnvelope.java`, `EventTopics.java` (optional Kafka bus). | Events (off by default) |
| `observability/` | `Redactor.java` (keeps secrets out of logs). | Logging |
| `rbac/` | `Permission.java` (the fixed list of permission codes such as `agent:read`), `RequiresPermission.java` (annotation placed on endpoints). | Roles and permissions |
| `resilience/` | `ResiliencePresets.java` (retry, circuit breaker, timeout presets). | Calls between services and to vendors |
| `runtimeconfig/` | `ConfigKey.java`, `RuntimeConfigService.java`, `RuntimeConfigStore.java` (settings changed at run time and stored in the service's own table). | Settings page, embedding model choice |
| `security/`, `web/`, `persistence/`, `util/` | Empty folders today (the working versions live in `platform-web`). | |

#### `platform-web` (servlet half; the reactive gateway does not use it)

Package root: `platform-web/src/main/java/os/aiworkforce/platform/web/`

| Package | Purpose and key files |
|---|---|
| `audit/` | The audit outbox: `AuditClient.java` (record an event), `JdbcAuditOutbox.java` (store it in the same database), `AuditOutboxRelay.java` (deliver it later), `HttpAuditSender.java` (POST to analytics-service), `AuditTokenSource.java`, `AuditProperties.java`, `AuditClientConfiguration.java`. |
| `config/` | `WebMvcConfig.java` (registers the permission interceptor). |
| `error/` | `GlobalExceptionHandler.java` (turns exceptions into the standard problem body). |
| `filter/` | `ActorContextFilter.java` (builds the `Actor` for each request). |
| `persistence/` | `BaseEntity.java`, `OrgScopedEntity.java` (every row carries its workspace), `UuidV7.java`, `JpaAuditingConfig.java`. |
| `security/` | `ResourceServerConfig.java` (verifies the JWT against identity's published keys), `JwtActorConverter.java`, `PermissionInterceptor.java` (enforces `@RequiresPermission`). |
| `openapi/`, `event/` | Empty folders today, reserved for API documentation settings and event helpers. |

#### `platform-contracts`

`platform-contracts/src/main/java/os/aiworkforce/contracts/{analytics,common,event,identity,integrations,knowledge,memory,orchestrator,org}/` are reserved package folders for shared request and response types. They are empty today; services define their own types.

#### `llm-core` (one interface to every language model)

Package root: `llm-core/src/main/java/os/aiworkforce/llm/`

| Package | Purpose and key files |
|---|---|
| `spi/` | The contracts: `ChatProvider.java` (one provider), `ProviderRegistry.java` (where providers and models come from), `CredentialResolver.java` (where keys come from), `ChatChunk.java`. |
| `provider/` | The adapters: `OpenAiCompatibleProvider.java` (OpenRouter, NVIDIA NIM, Groq, OpenAI), `AnthropicProvider.java`, `GeminiProvider.java`, `BedrockProvider.java`, `SandboxProvider.java` (offline, deterministic, needs no key). |
| `router/` | `ModelRouter.java` (disqualify, attempt, fall over; the only place that chooses a model), `RoutingPolicy.java` (ordered candidate list), `TranscriptCompactor.java` (shrinks a long conversation to fit a small model). |
| `budget/` | `BudgetGuard.java` (interface checked before every model call). |
| `usage/` | `UsageRecorder.java` (interface that writes one usage row per attempt). |
| `bedrock/` | `BedrockCredentials.java` (parses an AWS key pair or Bedrock API key plus region), `AwsSigV4.java` (request signing). |
| `model/` | Plain types: `ChatRequest`, `ChatResponse`, `ChatMessage`, `ToolSpec` (with the side-effect class), `ToolCall`, `ToolNames`, `TokenUsage`, `AttemptRecord`, `ProviderFailure`, `ModelSpec`, `ProviderDescriptor`, `EmbeddingPurpose`, `FinishReason`, `ImagePart`, `TokenEstimate`. |
| `config/` | Empty today; `LlmCore.java` in the package root marks the package for scanning. |

Backs: Model routing page, every agent run, every Chat answer, embeddings.

#### `mcp-core` (the tool gateway and every connector)

Package root: `mcp-core/src/main/java/os/aiworkforce/mcp/`

| Package | Purpose and key files |
|---|---|
| `policy/` | `ToolGateway.java` (the single path every tool call takes: grant, scope, arguments, approval, rate limit, circuit breaker), `ApprovalDecision.java`, `ArgumentValidator.java`, `ToolGrant.java`. |
| `spi/` | `McpServerAdapter.java` (interface every connector implements), `TokenRefresher.java`. |
| `catalog/` | `ConnectorCatalog.java` (the list shown on the Connectors page: name, category, how to connect, setup steps), `ConnectorInfo.java`, `CredentialField.java`, `OAuthSetup.java`, `ToolLabels.java` (plain names for tools). |
| `sandbox/` | `SandboxServerRegistry.java` (defines each connector's tools as data and builds the adapters), `SandboxServerAdapter.java` (practice-data stand-in). Seed data: `mcp-core/src/main/resources/mcp/sandbox-seeds.json`. |
| `live/` | One adapter per real vendor: GitHub, Slack, Notion, Linear, HubSpot, Webhook, Jira, Confluence, Asana, Zendesk, Stripe (token or key); Gmail, Calendar, Drive, Sheets, Outlook, Teams, Salesforce, Zoom (OAuth or app credentials). Bases: `LiveServerAdapter.java`, `OAuthAdapter.java`; `Hosts.java` limits calls to the vendor's own domain. |
| `oauth/` | `OAuthProvider.java`, `OAuthProviders.java` (Google, Microsoft, Salesforce and the permissions each connector asks for). |
| `model/` | `ToolDefinition.java`, `ToolInvocation.java`, `ToolResult.java`, `ConnectionCheck.java`. |
| `protocol/` | `McpProtocol.java` (Model Context Protocol message records). |
| `server/` | Empty today, reserved for a server-side MCP endpoint. |

Backs: Connectors page, agent tool grants, every tool an agent uses.

### 2.3 Backend services

All services live under `services/`. Each has `src/main/java/os/aiworkforce/<name>/{domain,repository,service,web}` (a few extra folders, such as `config/` in identity and `connector/` in knowledge, are empty today), `src/main/resources/application.yml`, `src/main/resources/db/migration/V<n>__*.sql`, and `src/test/java`. The `domain` package holds database entities, `repository` holds Spring Data repositories, `service` holds business logic and background jobs, `web` holds REST controllers.

#### `services/gateway` (port 8080; reactive; no business logic, no database)

| File | Purpose |
|---|---|
| `src/main/java/os/aiworkforce/gateway/GatewayApplication.java` | Boot class. |
| `src/main/java/os/aiworkforce/gateway/security/GatewaySecurityConfig.java` | Public paths, JWT check against identity's JWKS, CORS. |
| `src/main/java/os/aiworkforce/gateway/security/KeyResolvers.java` | Who a rate limit is charged to (address before sign-in, token subject after). |
| `src/main/resources/application.yml` | The route table to the seven services, rate limits, timeouts, merged Swagger list. |
| `src/test/java/os/aiworkforce/gateway/GatewayRoutesTest.java` | Fails if the route table drifts from `web/vite.config.ts` or `infra/launcher/nginx.conf`. |

#### `services/identity-service` (8081; schema `identity`) backs Sign in, Members and roles, Profile

| Package | Key files |
|---|---|
| `service/` | `AuthService.java` (register, sign-in, refresh, sign-out, sessions, change password), `TokenService.java` (ES256 access tokens, key rotation, internal service tokens), `SigningKeyStore.java`, `PasswordService.java`, `PasswordResetService.java`, `PermissionSeeder.java` (permissions and the five system roles), `GrantGuard.java` (who may hand out which role), `DemoDataSeeder.java` (the five demo accounts), `SessionSweep.java` (nightly cleanup), `OrchestratorClient.java`, `AuditWiring.java`. |
| `web/` | `AuthController.java` (`/api/auth/*`), `MemberController.java` (`/api/users`), `RoleController.java` (`/api/roles`), `JwksController.java` (`/.well-known`), `PasswordController.java`, `DemoController.java`, `InternalTokenController.java` (`/internal/tokens`), `InternalMembershipController.java`. |
| `domain/`, `repository/` | `User`, `Membership`, `Role`, `PermissionRecord`, `Session`, `SigningKey`, `PasswordResetToken` and their repositories. |
| migrations | `V1__identity_core.sql` to `V4__audit_outbox.sql`. |

#### `services/org-service` (8082; schema `organisation`) backs Create workspace, Settings, Members (invitations), model-key storage

| Package | Key files |
|---|---|
| `service/` | `CredentialService.java` (store, list, reveal, delete encrypted secrets), `InvitationService.java`, `DemoDataSeeder.java` (Demo Workspace), `InternalTokenProvider.java`, `AuditWiring.java`. |
| `web/` | `WorkspaceController.java` (`/api/workspaces`), `CredentialController.java` (`/api/credentials/{ref}`), `InvitationController.java`, `InternalWorkspaceController.java`. |
| `domain/` | `Organisation`, `Credential`, `Invitation`. |
| migrations | `V1__organisation.sql`, `V2__audit_outbox.sql`. |

#### `services/orchestrator-service` (8083; schema `orchestrator`; the largest service) backs Chat, Agents, Tasks, Schedules, Runs, Approvals, Orchestrator board, Model routing, Analytics inputs

Package root: `services/orchestrator-service/src/main/java/os/aiworkforce/orchestrator/`

| Package | Purpose and key files |
|---|---|
| `service/` | The engine. `AgentRunner.java` (the agent loop), `GoalService.java` (goal into task graph), `RunExecutor.java` (background execution on virtual threads), `ApprovalService.java`, `QuestionService.java`, `TaskProgress.java`, `MaintenanceScheduler.java` (timed sweeps), `RoutingPolicyResolver.java`, `JpaBudgetGuard.java`, `JpaUsageRecorder.java`, `JpaProviderRegistry.java`, `OrgCredentialResolver.java` (fetch a provider key from org-service), `ToolCredentialResolver.java` (fetch a connector token from integrations-service), `KnowledgeSearchTool.java`, `AgentMemoryTool.java`, `MemoryClient.java`, `AskPersonTool.java`, `AgentTemplates.java`, `DemoAgentSeeder.java`, `GeneralEmployee.java` (the fallback agent), `RetentionService.java`, `NotificationService.java`, `AuditClient.java`, `IntegrationsTokenRefresher.java`. |
| `chat/` | Workforce Chat. `CoordinatorService.java` (reads a message and decides what happens), `MentionParser.java`, `IntentDetector.java`, `ModelRouterPlanner.java`, `RuleRouter.java`, `ChatController.java`, `ChatAppender.java`, `ChatQueue.java`, `ChatQueueRunner.java`, `ChatGoalListener.java`, `ConversationAccess.java`, `ConversationAdmin.java`, `ConversationQueries.java`, `ThreadContext.java`, `DocumentsPrompt.java`, `PassageRelevance.java`, `KnowledgeClient.java`, `Attachment*.java` (upload, read, prompt, types), `FeedbackController.java`. |
| `schedule/` | Schedules. `ScheduleService.java`, `ScheduleSweep.java` (every 30 seconds), `ScheduleParser.java` (plain English to a timetable), `ScheduleController.java`, `ScheduleGoalListener.java`, `Schedule.java`. |
| `board/` | Orchestrator page and Analytics figures. `BoardService.java`, `OrchestratorController.java`, `InsightsService.java`, `InsightsController.java`, `GoalViews.java`. |
| `catalog/` | Model discovery. `ModelCatalogService.java` (list what a provider offers), `ModelListParsers.java`, `BedrockRegionFinder.java`, `ModelCatalogStore.java`. |
| `voice/` | ElevenLabs voice. `VoiceController.java`, `VoiceService.java`, `ElevenLabsClient.java`, `VoiceClipService.java`. |
| `web/` | REST: `AgentController`, `AgentGrantController`, `AgentTemplateController`, `AgentRevisionsController`, `AgentVoiceController`, `GoalController`, `RunController`, `RunRetryController`, `ApprovalController`, `QuestionController`, `ProviderController`, `ProviderModelCatalogController`, `BedrockRegionController`, `ModelPolicyController`, `BudgetController`, `UsageController`, `RetentionController`, `ValueSettingsController`, `NotificationSettingsController`, `InternalEmbeddingController`. |
| `domain/` | Entities: `Agent`, `AgentVersion`, `AgentToolGrant`, `Goal`, `Task`, `Run`, `RunStep`, `Approval`, `RunQuestion`, `Budget`, `Conversation`, `ChatMessage`, `ChatQueuedMessage`, `LlmProviderEntity`, `LlmModelEntity`, `ModelPolicyEntity`, `ModelPolicyCandidate`, `LlmUsageRecord`, and others. |
| `repository/` | One Spring Data repository per entity. |
| `config/` | Empty today. |
| migrations | `V1__orchestrator.sql` (agents, goals, tasks, runs, approvals, routing, usage, budgets) to `V21__bedrock_models.sql`. |

#### `services/memory-service` (8084; schema `memory`) backs the Memory card on an agent page

| File | Purpose |
|---|---|
| `service/AgentMemoryService.java` | An agent's short notes: write, correct, pin, forget; refuses secrets; caps the count. |
| `service/SecretGuard.java` | Rejects a note that looks like a password, key or card number. |
| `service/EpisodicMemory.java`, `WorkingMemory.java`, `EpisodePurgeJob.java` | Run history episodes and their cleanup. |
| `web/AgentMemoryController.java` | `/api/memory/agents/{agentId}/memories` (people editing notes). |
| `web/InternalAgentMemoryController.java` | `/internal/memory/agents/{agentId}/remember` and `/recall` (used by the orchestrator). |
| `web/MemoryController.java`, `InternalMemoryController.java` | Episodes. |
| migrations | `V1__memory.sql`, `V2__agent_memories.sql`, `V3__agent_memory_pin.sql`. |

#### `services/knowledge-service` (8085; schema `knowledge`; talks to Qdrant) backs Knowledge page, source detail, Chat grounding

| File | Purpose |
|---|---|
| `service/IngestionService.java` | Create source, upload (extract, chunk, embed, store), replace, reindex, rename, delete. |
| `service/TextExtractor.java`, `Chunker.java` | File to text; text to overlapping heading-aware passages. |
| `service/EmbeddingService.java`, `EmbeddingSettings.java`, `EmbeddingModelChange.java` | Turn text into vectors; remember the workspace's embedding model; rebuild every source when it changes. |
| `service/QdrantClient.java` | Vector store REST client. |
| `service/RetrievalService.java` | Hybrid search: keyword plus meaning, fused by rank, filtered by what the caller may read. |
| `web/KnowledgeController.java` | `/api/sources...`, `/api/knowledge/search`. |
| `web/AgentKnowledgeController.java` | An agent's own private documents and notes. |
| `web/EmbeddingSettingsController.java` | `/api/knowledge/embedding`. |
| `web/InternalSearchController.java`, `InternalExtractController.java` | Used by agents and chat attachments. |
| `domain/` | `Source`, `Document`, `Chunk`. |
| migrations | `V1__knowledge.sql` to `V6__clear_sandbox_vector_errors.sql`. |

#### `services/integrations-service` (8086; schema `integrations`) backs Connectors page

| File | Purpose |
|---|---|
| `service/ConnectorService.java` | Connect (encrypt and store a token), test, disconnect, hand the credential to the orchestrator, refresh, flag reconnect. |
| `oauth/OAuthService.java`, `OAuthStateCodec.java`, `OAuthProperties.java` | OAuth sign-in for Google, Microsoft, Salesforce connectors. |
| `web/IntegrationController.java` | `/api/integrations...` and `/internal/connections/...`. |
| `web/OAuthController.java` | The OAuth callback address. |
| `domain/` | `Connection`, `OAuthApp`, `OAuthState`. |
| migrations | `V1__integrations.sql`, `V2__connection_checks.sql`, `V3__oauth.sql`. |

#### `services/analytics-service` (8087; schema `analytics`) backs Audit log and Analytics

| File | Purpose |
|---|---|
| `service/AuditAppender.java` | The only writer: appends an entry and computes its hash. |
| `service/AuditChain.java`, `CanonicalJson.java` | Hash formulas and chain walking. |
| `service/AuditVerification.java`, `AuditChainJob.java` | Verify now, and every night at 02:15. |
| `service/AuditSearch.java`, `AuditFilter.java`, `AuditExport.java` | Search, filter, CSV or JSON export. |
| `web/AuditController.java` | `/api/audit`, `/api/audit/export`, `/api/audit/verify`. |
| `web/InternalAuditController.java` | `/internal/audit-events` (where other services deliver events). |
| `web/AnalyticsController.java` | `/api/analytics`. |
| migrations | `V1__analytics.sql`, `V2__audit_chain_integrity.sql`. |

### 2.4 Web client: `web/`

| Item | Purpose |
|---|---|
| `package.json`, `pnpm-lock.yaml` | Scripts: `dev`, `build`, `lint`, `test`, `design-check`. |
| `vite.config.ts` | Dev server on 5173 and the `/api` proxy table, one prefix per owning service. |
| `vitest.config.ts`, `src/test-setup.ts` | Test setup. |
| `eslint.config.js`, `tsconfig.json` | Lint and TypeScript. |
| `index.html`, `public/` | Page shell, icons, `og-image.png`, self-hosted Inter fonts in `public/fonts/`. |
| `src/main.tsx` | Entry point: styles, query client, `<App/>`. |
| `src/App.tsx` | Route table (public and private, each with its required permission), session restore, page shell, tab titles. |

#### `web/src/routes/` (one file per page)

| File | Page and address |
|---|---|
| `Landing.tsx` | Public home, `/home`, written for company buyers. |
| `Trust.tsx` | Public technical page, `/trust`. |
| `SignIn.tsx`, `CreateWorkspace.tsx`, `AcceptInvite.tsx` | `/sign-in`, `/create-workspace`, `/accept-invite`. |
| `CommandMap.tsx` | Signed-in home, `/`. |
| `Chat.tsx` | `/chat`. |
| `Orchestrator.tsx` | `/orchestrator`. |
| `Agents.tsx`, `AgentDetail.tsx` | `/agents`, `/agents/:id`. |
| `Tasks.tsx`, `Schedules.tsx` | `/tasks`, `/schedules`. |
| `Runs.tsx`, `RunDetail.tsx` | `/runs`, `/runs/:id`. |
| `Approvals.tsx` | `/approvals`. |
| `Knowledge.tsx`, `SourceDetail.tsx` | `/knowledge`, `/knowledge/:id`. |
| `Connectors.tsx` | `/connectors`. |
| `ModelRouting.tsx` | `/routing`. |
| `Members.tsx` | `/members`. |
| `AuditLog.tsx`, `Analytics.tsx` | `/audit`, `/analytics`. |
| `Settings.tsx`, `Setup.tsx`, `Profile.tsx`, `NotFound.tsx` | `/settings`, `/setup`, `/profile`, any unknown address. |

Files named `*.test.tsx` beside each page are its tests.

#### `web/src/components/<area>/` (pieces used by the pages)

| Folder | Contents | Backs |
|---|---|---|
| `agents/` | `AgentDocumentsCard`, `AgentMemoryCard`, `AgentStatusButton`, `RetireAgentCard` | Agent detail page |
| `analytics/` | `ValueTiles`, `SpendBreakdown`, `BudgetCard`, `DailyTrend`, `AgentsTable`, `ActivityLog`, `OutcomesCard`, `RunRatings`, `AnswerRating`, `ValueInputs`, `figures.ts` | Analytics page |
| `approvals/` | `ApprovalCard`, `BulkDecision`, `PayloadPreview` | Approvals page |
| `auth/` | `AuthShell`, `AuthShowcase`, `DemoRolePicker`, `ResetPasswordForm`, `Stepper`, `WorkspacePicker` | Sign in, Create workspace, Accept invite |
| `chat/` | `Composer`, `MessageList`, `MessageItem`, `UserBubble`, `AnswerBubble`, `RoutingCard`, `ProgressCard`, `QuestionMessage`, `ScheduleCard`, `DocumentsCard`, `PassageList`, `ChatSidebar`, `ConversationRow`, `ThreadHeader`, `WorkPanel`, `WorkStrip`, `WelcomeScreen`, `QueuedBubble`, `AddPeopleDialog`, attachment pieces (`AttachButton`, `AttachmentCards`, `AttachmentChips`, `useAttachments.ts`, `useDropAndPaste.ts`), and hooks (`useStickToBottom`, `useChatShortcuts`, `useRovingList`) | Chat page |
| `command-map/` | `GettingStarted.tsx` | Command Map |
| `connectors/` | `ConnectDialog`, `GrantDialog`, `ConnectorToolbar`, `CapabilityList` | Connectors page, agent grants |
| `knowledge/` | `KnowledgeSearch`, `DocumentPassagesSheet`, `EmbeddingSettingsCard` | Knowledge pages |
| `landing/` | Public pages: `hero/`, `agent-run/` (approval demo), `answer/` (cited answer demo), `failover/` (provider failover demo), `roles/`, `sections/` (how it works, safety, audit chain demo, FAQ, limits), `shared/` | `/home`, `/trust` |
| `layout/` | `Navbar.tsx` (the navigation list and permission gating), `Brand.tsx` | Every signed-in page |
| `onboarding/` | `ConnectModelDialog`, `BedrockCredentialFields`, `RegionCombobox`, `TimeZoneField` | Connect your AI, Create workspace |
| `orchestrator/` | `Board`, `BoardList`, `BoardToolbar`, `FlowMap`, `Swimlanes`, `NeedsYouInbox`, `GoalSheet`, `RunSheet`, `StepList`, `StopEverythingDialog`, `SummaryStrip`, `AgentsStrip` | Orchestrator page |
| `routing/` | `PolicyEditor`, `ModelCombobox`, `CandidateModelField`, `TestNow` | Model routing page |
| `run/` | `AnswerCard`, `TraceStep`, `RunTraceCompact`, `RunStats`, `InlineApproval`, `QuestionCard`, `WaitingForApproval`, `WaitingForAnswer`, `ClipAudio`, `traceModel.ts` | Run detail, Chat |
| `schedules/` | `ScheduleDialog`, `ScheduleHistoryDialog`, `scheduleModel.ts` | Schedules page |
| `settings/` | `BrowserNotificationsCard.tsx` | Settings page |
| `setup/` | `SetupBanner`, `ModelOrderPicker`, `ToolsShortlist`, `QuickUpload`, `InviteInline` | Setup page |
| `ui/` | Shared building blocks: `Sheet`, `Menu`, `Markdown`, `QueryState`, `FilterBar`, `Collapsible`, `CopyButton`, `ErrorBoundary`, `ShortcutsDialog`, `TaskDialog`, `AgentAvatar`, `index.tsx` | Everywhere |

#### `web/src/lib/` (data and rules; no screens)

| File | Purpose |
|---|---|
| `api.ts` | The single HTTP client: bearer token, refresh on 401, timeouts, readable errors. |
| `session.ts` | Access token in `sessionStorage`, profile, permissions, `can(...)`, cross-tab sign-out. |
| `queries.ts` | The main TanStack Query hooks and mutations (agents, runs, approvals, providers, connectors, credentials, and more). |
| `*Queries.ts` | Feature hooks: `accountQueries`, `agentQueries`, `agentMemoryQueries`, `approvalQueries`, `chatQueries`, `chatQueueQueries`, `embeddingQueries`, `insightsQueries`, `knowledgeQueries`, `memberQueries`, `scheduleQueries`, `settingsQueries`, `setupQueries`, `templateQueries`. |
| `router.tsx` | The in-house router. |
| `labels.ts`, `format.ts` | Plain words for every status code; dates, money and counts in one style. |
| `connectors.ts`, `bedrock.ts`, `keyFormats.ts`, `routing.ts`, `routingActions.ts`, `modelCatalogue.ts` | Connector, key and model rules. |
| `approvals.ts`, `questions.ts`, `goals.ts`, `schedules.ts`, `mentions.ts`, `attachments.ts`, `attention.ts`, `voice.ts`, `markdown.ts`, `templates.ts`, `setup.ts`, `onboarding.ts`, `persist.ts`, `toast.tsx`, `useListFilter.ts`, `useNow.ts`, `clipboard.ts`, `hotkeys.ts`, `demo.ts`, `redirect.ts`, `agentDescription.ts` | Small rule sets and helpers, each tested by a `.test.ts` beside it. |

#### `web/src/hooks/`

Animation and visibility hooks used by the public pages: `useCountUp`, `useInView`, `useMediaQuery`, `usePointerSpot`, `useReducedMotion`, `useReveal`, `useSequence`.

#### `web/src/styles/`

| File or folder | Purpose |
|---|---|
| `tokens.css` | Design tokens: colours, spacing, type scale. |
| `fonts.css` | Self-hosted Inter. |
| `components.css` | Base component styles. |
| `polish/` | Per-area styles: `shell`, `chat`, `orchestrator`, `connectors`, `knowledge`, `auth`, `admin`, `setup`, `pickers`, `work`; `index.css` imports them. |
| `landing/` | Public-page styles: `base`, `hero`, `agent-run`, `failover-answer`, `roles-sections`, `business`; `index.css` imports them. |
| `design-system.test.ts` | The design rules checked on every screen (`make design-check`). |

### 2.5 Infrastructure: `infra/`

| Path | Purpose |
|---|---|
| `infra/Dockerfile` | One Dockerfile that builds any one service, chosen by the `SERVICE` build argument. |
| `infra/compose/docker-compose.yml` | Full developer stack: Postgres, Redis, Qdrant, Redpanda, Prometheus, Grafana, all eight services (`make up`). |
| `infra/launcher/` | One-click install: `docker-compose.yml`, `nginx.conf` (serves the web app and proxies `/api`), `web.Dockerfile`, `services.Dockerfile`, `prepare-env.sh` and `prepare-env.ps1` (write private secrets to `infra/launcher/.env`). |
| `infra/k8s/base/` | One manifest per service plus `config.yaml`, `ingress.yaml`, `network-policy.yaml`, `namespace.yaml`, `kustomization.yaml`. |
| `infra/k8s/overlays/staging/kustomization.yaml` | Staging overrides. |
| `infra/prometheus/prometheus.yml`, `infra/prometheus/rules/aiwos-alerts.yml` | Metrics collection and alert rules. |
| `infra/grafana/` | `dashboards/aiwos-overview.json` and provisioning files. |

### 2.6 Scripts, launchers, docs

| Path | Purpose |
|---|---|
| `scripts/dev-backend.sh` | Starts the seven services (optionally the gateway) from copies of the built jars, without Docker. Commands: start, `--with-gateway`, `stop`, `status`. Logs in `~/.aiwos-dev/logs`. |
| `Start AI Workforce OS.command` and `.bat` | Build and start everything with Docker, then open http://localhost:4173. |
| `Stop AI Workforce OS.command` and `.bat` | Stop it. |
| `docs/adr/` | Architecture decision records, one per week. |
| `docs/wp-specs/` | Work-package specification files `WP18.json` to `WP24.json`. |
| `docs/2026-10-01_AI-Workforce-OS_System-Design-Document_v2.docx` | The system design document. |
| `docs/2026-10-01_Team-Contribution-Plan_v1.docx` | Who built what. |
| `docs/2026-09-25_Test-and-Fix-Plan_v1.md`, `2026-10-03_Product-Readiness-Loop_v1.md`, `2026-10-06_Paused-State_v1.md` | Test plan, readiness loop and current status notes. |
| `docs/2026-09-26_HomePageRedesign*.md/.json` | Home page redesign plan and contracts. |
| `docs/2026-10-10_Understand-Code_v1.md` | This guide. |

---

## 3. How do I find... (60 questions)

Paths inside answers abbreviate three long prefixes: **ORCH** = `services/orchestrator-service/src/main/java/os/aiworkforce/orchestrator`, **KNOW** = `services/knowledge-service/src/main/java/os/aiworkforce/knowledge`, **ANALYTICS** = `services/analytics-service/src/main/java/os/aiworkforce/analytics`. Other prefixes are written in full.

### A. Knowledge base

1. **Where is a knowledge base (a source) created?**
   `POST /api/sources` in `KNOW/web/KnowledgeController.java` calls `IngestionService.createSource(...)` in `KNOW/service/IngestionService.java`.
   The Knowledge page's "Add source" button uses `web/src/lib/knowledgeQueries.ts`; the table is `knowledge.sources` (`V1__knowledge.sql`).

2. **How is a document added?**
   `POST /api/sources/{sourceId}/documents` (multipart) in `KnowledgeController` calls `IngestionService.ingest(...)`: extract (`TextExtractor`), chunk (`Chunker`), embed (`EmbeddingService`), store rows plus Qdrant points.
   Parsing happens outside any database transaction so a large file does not hold a connection.

3. **How is a document updated?**
   Upload the same file name again. The default mode is `UploadMode.REPLACE`, so the old version stops being citable; `KEEP_BOTH` stores "Name (2).pdf" instead.
   Code: `IngestionService.ingest(orgId, sourceId, filename, content, UploadMode)`. An unchanged file is skipped by its content hash.

4. **How is a source reindexed?**
   `POST /api/sources/{sourceId}/reindex` calls `IngestionService.reindex(...)`, which re-chunks and re-embeds every document in that source.
   Use it after a failed vector write; the document notice says when this is needed.

5. **How does a change of embedding model work?**
   The Knowledge page's embedding card calls `PUT /api/knowledge/embedding` in `KNOW/web/EmbeddingSettingsController.java`, then `EmbeddingModelChange.change(...)` rebuilds every source into a new Qdrant collection and deletes the old one.
   Progress is read from `GET /api/knowledge/embedding`; an interrupted job restarts with `POST /api/knowledge/embedding/reindex` (`EmbeddingModelChange.resume`). The choice is stored by `EmbeddingSettings.java`.

6. **How is a document deleted?**
   `DELETE /api/sources/{sourceId}/documents/{documentId}` calls `IngestionService.deleteDocument(...)`, which removes its passages and row, then its vectors from Qdrant (`QdrantClient.deleteByDocument`).
   `IngestionService.deleteSource(...)` does the same for a whole source. Both write an audit event.

7. **How does search work?**
   `RetrievalService.retrieve(...)` in `KNOW/service/RetrievalService.java` runs a keyword search and a meaning (vector) search, fuses the two rankings by rank (not by score), and returns only passages the caller may read.
   If Qdrant or the embedding model is unavailable it returns keyword results and sets `degraded`. The Knowledge page search box calls `POST /api/knowledge/search`.

8. **How does grounding work (answers from documents with citations)?**
   In Chat, `CoordinatorService.answerFromDocuments(...)` fetches passages through `ORCH/chat/KnowledgeClient.java`, filters them with `PassageRelevance.java`, and `DocumentsPrompt.java` tells the agent to answer only from them.
   Inside an agent run the `KnowledgeSearchTool` (`ORCH/service/KnowledgeSearchTool.java`) offers a search tool and adds a numbered reference block; citations are shaped by `KnowledgeSearchTool.citations(...)`.

9. **Who can read which source?**
   `KNOW/web/InternalSearchController.java` and `RetrievalService` honour the source's `restricted` flag (only people who manage knowledge) and an owning agent (an agent's private documents).
   The flag is set with `PATCH /api/sources/{sourceId}`; see migrations `V3__source_restricted.sql` and `V5__source_agent_owner.sql`.

### B. Chat and agents

10. **How does Chat pick an agent?**
    `CoordinatorService.send(...)` (in `ORCH/chat/CoordinatorService.java`) tries, in order: an `@mention` (`MentionParser.parse`), an intent such as a schedule or a document question (`IntentDetector.detect`), a configured live model that plans the work (`ModelRouterPlanner.plan`), then keyword scoring (`RuleRouter.route`).
    If nothing fits, the workspace's fallback agent (`GeneralEmployee`) takes it, or Chat asks the person to choose. Each reply states who took the work and why (`RoutingCard.tsx`).

11. **How does one Chat message become work?**
    `ChatController.send` (`POST /api/conversations/{id}/messages`) calls `CoordinatorService.send`, which creates a goal with tasks through `GoalService.createGoal(...)`, then `RunExecutor` starts them in the background.
    Results come back as messages through `ChatGoalListener.java`. The browser polls (`web/src/lib/chatQueries.ts`).

12. **What happens when I send a message while the agent is busy?**
    The message waits in a queue: `ChatQueue.java` stores it (`chat_queued_messages`, migration `V19__chat_queue.sql`) and `ChatQueueRunner.java` starts the next one when the work ends.
    You can edit, cancel or "start now" through `ChatController` (`/queued/{queuedId}`); UI in `QueuedBubble.tsx`.

13. **How does an agent run work, step by step?**
    1) `GoalService` claims a ready task and calls `AgentRunner.prepare/start`. 2) `AgentRunner.drive(...)` builds the prompt (instructions, history, memory, document passages). 3) It calls `ModelRouter.route(...)`. 4) If the model asks for a tool, `runTool(...)` sends it through `ToolGateway`. 5) A risky call parks (`parkForApproval`), a question parks (`parkForInput`), otherwise the result goes back to the model. 6) Every step is saved as a `RunStep`; the loop ends with an answer, a failure, or the step limit.
    Resume after a decision is `AgentRunner.resume(...)`, which executes the approved call exactly once. File: `ORCH/service/AgentRunner.java`.

14. **How do goals, tasks and runs relate?**
    A goal holds a dependency graph of tasks; each task is attempted by a run; each run has ordered steps. `GoalService` hands the earlier agents' results to the next agent.
    Entities: `Goal`, `Task`, `Run`, `RunStep` in `ORCH/domain/`; UI pages Tasks, Runs, Run detail.

15. **How do I stop or retry work?**
    Stop: `POST /api/goals/{goalId}/cancel` or `POST /api/runs/{runId}/cancel` (`GoalService.cancel`, `stopRun`). Retry: `POST /api/goals/{goalId}/retry`, `POST /api/runs/{runId}/retry` (`RunRetryController`).
    "Stop everything" is `BoardService` behind the Orchestrator page; rule on who may stop is `web/src/lib/goals.ts`.

16. **How does an agent ask a person a question?**
    The agent calls the ask tool defined in `AskPersonTool.java`; `AgentRunner.parkForInput` stores a `RunQuestion` through `QuestionService.raise`. The person answers with `POST /api/orchestrator/questions/{id}/answer`.
    UI: `components/run/QuestionCard.tsx` and the "Needs you" inbox (`components/orchestrator/NeedsYouInbox.tsx`). Overdue questions expire in `MaintenanceScheduler.expireQuestions`.

17. **How does agent memory work?**
    Agents get two tools, remember and recall (`ORCH/service/AgentMemoryTool.java`), which call `MemoryClient` and then the memory service (`AgentMemoryService.java`). Notes that look like secrets are refused (`SecretGuard.java`).
    People inspect and edit notes on the agent page (`components/agents/AgentMemoryCard.tsx`, `/api/memory/agents/{agentId}/memories`).

18. **How do attachments work?**
    `AttachmentController` (`ORCH/chat/`) accepts uploads; `AttachmentService.upload` stores them, `checkForSend` and `bind` tie them to the message, and `AttachmentReader` asks the knowledge service (`/internal/knowledge/extract`) for the text without indexing it.
    `AttachmentPrompt` treats file contents as material, not instructions. "Save to knowledge" is `AttachmentService.saveToKnowledge`. Unsent files are swept hourly. Web side: `web/src/lib/attachments.ts`.

19. **How do I add a new agent template?**
    Add a `Template` entry to the list in `ORCH/service/AgentTemplates.java` (key, name, category, instructions, suggested connectors, demo grants), then mirror the key, name and category in `web/src/lib/templates.ts`.
    `templates.test.ts` reads the Java file and fails if the two drift. Templates are served by `AgentTemplateController` (`GET /api/agent-templates`, `POST /api/agents/from-template/{key}`); current keys: `hr`, `engineering-manager`, `research`, `support`.

20. **How is an agent created or changed?**
    `AgentController` (`POST /api/agents`, `PUT /api/agents/{agentId}/configuration`) saves a new `AgentVersion` each time, so history and restore work (`AgentRevisionsController`).
    UI: Agents page and Agent detail (`web/src/routes/Agents.tsx`, `AgentDetail.tsx`); query hooks in `web/src/lib/agentQueries.ts`.

### C. Tools, connectors and approvals

21. **How are tools called?**
    `AgentRunner.runTool` builds a `ToolInvocation` and asks `ToolGateway.evaluate(...)` (`mcp-core/src/main/java/os/aiworkforce/mcp/policy/ToolGateway.java`), then `ToolGateway.invoke(...)` runs the adapter.
    The gateway checks grant, scope, arguments, approval, per-run rate limit and circuit breaker, in that order, and every outcome is audited.

22. **How are tool calls approved?**
    `ToolGateway.evaluate` returns `AwaitApproval` when the tool is outbound or destructive (always), the grant says so, or the workspace policy says so. `AgentRunner.parkForApproval` then calls `ApprovalService.raise(...)`.
    A person decides with `POST /api/approvals/{approvalId}/decision`; `ApprovalService.decide(...)` records it and `RunExecutor.submitResume` continues the run. Pending approvals expire in `MaintenanceScheduler.expireApprovals`.

23. **Can the person who asked also approve (four eyes)?**
    That is the workspace "requester rule": off, destructive only, or all. It is read and set by `ApprovalService.requesterRuleName` and `setRequesterRule`, and checked in `ApprovalService.canDecide`.
    UI: Settings page, approval rules (`web/src/lib/settingsQueries.ts`, `PUT /api/approvals/settings`).

24. **Which tool calls need approval?**
    Each tool has a side-effect class: READ, WRITE, OUTBOUND, DESTRUCTIVE (`llm-core/src/main/java/os/aiworkforce/llm/model/ToolSpec.java`). OUTBOUND and DESTRUCTIVE always need approval, for example Gmail send, Slack post, Stripe refund.
    Tools are defined as data in `mcp-core/src/main/java/os/aiworkforce/mcp/sandbox/SandboxServerRegistry.java`.

25. **How is a connector connected with a token?**
    The Connect dialog (`web/src/components/connectors/ConnectDialog.tsx`) calls `PUT /api/integrations/{server}/connection`; `ConnectorService.connect(...)` encrypts the token with `EnvelopeEncryptionService` and stores it in `integrations.connections`.
    `POST /api/integrations/{server}/test` checks it with a who-am-I request (`ConnectorService.test`). Fields per connector come from `ConnectorCatalog.java`.

26. **How is a connector connected with OAuth?**
    An administrator first registers the provider's app (`PUT /api/integrations/{server}/oauth/app`, `OAuthApp` entity), then "Connect" opens `GET /api/integrations/{server}/oauth/start`; the provider returns to `OAuthController` and `OAuthService` stores the tokens.
    Expired access tokens are renewed by `ConnectorService.refreshRejected`, called by the orchestrator through `IntegrationsTokenRefresher`. Providers and scopes: `mcp-core/.../oauth/OAuthProviders.java`.

27. **How are Bedrock keys connected?**
    The "Connect your AI" dialog (`web/src/components/onboarding/ConnectModelDialog.tsx`, `BedrockCredentialFields.tsx`, `RegionCombobox.tsx`) stores either an AWS access key pair or a Bedrock API key plus a region as one encrypted JSON credential.
    The shape is parsed by `llm-core/.../bedrock/BedrockCredentials.java`; calls are signed by `AwsSigV4.java`; `ORCH/catalog/BedrockRegionFinder.java` finds a working region.

28. **How does an agent get permission to use a connector?**
    A grant ties one agent to one connector server, with allowed tools and scopes: `PUT /api/agents/{agentId}/grants/{server}` in `ORCH/web/AgentGrantController.java` (entity `AgentToolGrant`).
    UI: `web/src/components/connectors/GrantDialog.tsx`. Without a grant, `ToolGateway.evaluate` refuses the call and names what is missing.

29. **What is the difference between practice data and live?**
    Every connector has a practice-data adapter (`SandboxServerAdapter`). A live adapter (`mcp-core/.../live/`) wraps it and takes over once a credential is stored; with no credential the call goes to the sandbox.
    Because the live adapter reuses the sandbox's tool definitions, approval rules do not change when a real account is connected.

30. **How do I add a new connector?**
    1) Define its tools in `SandboxServerRegistry.java` and, if wanted, seed practice data in `mcp-core/src/main/resources/mcp/sandbox-seeds.json`. 2) Add its card in `ConnectorCatalog.java`. 3) For live use, write an adapter in `mcp-core/.../live/` extending `LiveServerAdapter` (or `OAuthAdapter`) and add a `case` in the registry's switch. 4) For OAuth, add scopes in `OAuthProviders.java`.
    Add plain labels in `ToolLabels.java` and a test in `mcp-core/src/test/java`.

### D. Models, routing, budgets

31. **How does model routing and fallback work?**
    `ModelRouter.route(...)` (`llm-core/.../router/ModelRouter.java`) disqualifies candidates that cannot work (disabled, no key, missing capability, too small, over budget), calls the first survivor with retries for temporary failures, and moves to the next on failures another model might not share.
    A safety refusal or a spending cap deliberately stops the chain. Every attempt and skip is recorded with its reason (`JpaUsageRecorder`) and shown in the run trace.

32. **Where is the order of models set?**
    `RoutingPolicyResolver.resolve(orgId, agentId)` uses the agent's own policy first, then the workspace default. They are edited with `PUT /api/model-policy` and `PUT /api/agents/{agentId}/model-policy` (`ORCH/web/ModelPolicyController.java`).
    UI: Model routing page, `components/routing/PolicyEditor.tsx`. Tables: `ModelPolicyEntity`, `ModelPolicyCandidate`.

33. **Where are providers and models defined?**
    As database rows: `V2__seed_providers.sql` seeds providers, `V21__bedrock_models.sql` adds Bedrock models; `ModelCatalogService` discovers what a connected provider offers and `ModelCatalogStore` saves it to `llm_models`.
    Per-workspace switches are in `WorkspaceProviderSetting`. Adding a provider type needs a `ChatProvider` class in `llm-core/.../provider/`.

34. **Where are API keys stored and how are they encrypted?**
    Model-provider keys go to `PUT /api/credentials/{ref}` (`organisation/web/CredentialController.java`), then `CredentialService.store`, which encrypts with `EnvelopeEncryptionService.encrypt(orgId, plaintext)` (a fresh data key per value, wrapped by the master key, workspace id bound in).
    The orchestrator never holds the key: `OrgCredentialResolver` asks org-service to reveal it at call time. Connector tokens are encrypted by the integrations service itself.

35. **Where is the master encryption key?**
    In the environment (platform properties); `PlatformStartupValidator` refuses a development key outside local and test. The launcher generates a private one into `infra/launcher/.env` (`infra/launcher/prepare-env.sh`).
    Rotation re-wraps data keys (`EnvelopeEncryptionService.needsRewrap`, `CredentialService.rewrapOutdated`).

36. **How are budgets enforced?**
    `JpaBudgetGuard.check(...)` (`ORCH/service/JpaBudgetGuard.java`) runs before every model attempt against monthly, per-run and per-agent daily caps, and `record(...)` stores actual cost afterwards. The router treats a refusal as a stop.
    Caps are read and set with `GET/PUT /api/orchestrator/budget` (`BudgetController`); UI: `components/analytics/BudgetCard.tsx`. A null cap means no cap; zero refuses everything.

37. **What is the offline sandbox model?**
    `SandboxProvider.java` answers deterministically with no key, so the whole product can be demonstrated offline.
    The Model routing page says when runs reach only the sandbox (`web/src/lib/routing.ts`).

### E. Sign-in, tokens, permissions

38. **How does sign-in work?**
    `POST /api/auth/sign-in` (`identity/web/AuthController.java`) calls `AuthService.signIn(...)`, which checks the password and creates a session. The access token is returned in the body; the refresh token is set as an HttpOnly cookie named `aiwos_refresh`.
    The browser side is `web/src/lib/session.ts` and `api.ts` (the token lives in `sessionStorage`).

39. **How do tokens work?**
    `TokenService.java` signs ES256 access tokens (five minutes) with keys kept in `SigningKeyStore`; the public keys are served at `/.well-known` (`JwksController`). Every service verifies tokens itself (`platform-web/.../security/ResourceServerConfig.java`).
    Refresh tokens last 30 days and rotate; reusing a spent one revokes the whole family (`AuthService.refresh`). Lifetimes are in `platform-core/src/main/resources/platform-defaults.yml`.

40. **How do services call each other?**
    With short-lived internal tokens from `POST /internal/tokens` (`InternalTokenController`), obtained by each service's `InternalTokenProvider` and carrying the original person.
    Examples: `KnowledgeClient` and `MemoryClient` in the orchestrator, `OrchestratorClient` in identity.

41. **How are permissions and roles checked?**
    Endpoints carry `@RequiresPermission("agent:read")` (`platform-core/.../rbac/RequiresPermission.java`); `PermissionInterceptor.preHandle` in `platform-web` enforces it from the permissions in the token. Codes are fixed in `Permission.java`.
    Roles and their permissions are database rows edited on the Members page (`RoleController`); the five system roles are seeded by `PermissionSeeder`. In the browser, `session.can(...)` and the route table in `web/src/App.tsx` hide what a person cannot use.

42. **Who may give out which role?**
    `GrantGuard.java` in identity: a person can only grant a role whose permissions they hold themselves, and only the owner can add or remove owners.
    The web mirror is `web/src/lib/memberQueries.ts`.

43. **What are the demo accounts?**
    Five accounts, one per role (owner, admin, manager, employee, viewer, all `@demo.aiworkforce.os`), created by `DemoDataSeeder.java` in identity and org services; `DemoAgentSeeder` seeds four agents.
    They are listed on the sign-in screen unless `AIWOS_DEMO_ENABLED=false` (`web/src/lib/demo.ts`).

### F. Schedules, audit, data

44. **How do schedules fire?**
    `ScheduleSweep.sweep()` runs every 30 seconds and calls `ScheduleService.sweepDue(...)`, which turns each due schedule into a goal (`GoalService`), exactly like a Chat request, and computes the next run.
    `ScheduleParser.parse` reads plain English ("every weekday at 9am") in the workspace time zone; `ScheduleGoalListener` records the result and auto-pauses after three failures.

45. **How do I create or pause a schedule?**
    `POST /api/schedules`, `POST /api/schedules/{id}/pause`, `/resume`, `/run-now` in `ORCH/schedule/ScheduleController.java`; a preview of the next five runs is `POST /api/schedules/preview`.
    UI: `web/src/routes/Schedules.tsx`, `components/schedules/ScheduleDialog.tsx`. A removed member's schedules are paused by `InternalScheduleController`.

46. **How is the audit log written?**
    A service records an event through `AuditClient` (`platform-web/.../audit/`), which stores it in a local outbox table; `AuditOutboxRelay` delivers it with `HttpAuditSender` to `POST /internal/audit-events`, where `AuditAppender.append` adds it to the workspace's hash chain.
    The orchestrator and integrations services send directly with their own `AuditClient`. Table: `analytics.audit_events` (`V1__analytics.sql`).

47. **How is the audit log verified?**
    Each entry's hash covers its own fields and the previous hash (`AuditChain.hashV2`). `GET /api/audit/verify` re-walks a chain (`AuditVerification.verifyWorkspace`) and `AuditChainJob.nightly` does so at 02:15.
    The Audit log page shows the result. It is tamper-evident, not tamper-proof; the class comment in `AuditChain.java` explains why.

48. **How do I export the audit log or usage?**
    `GET /api/audit/export` (CSV or JSON lines, `AuditExport.java`) and `GET /api/orchestrator/usage.csv` (`UsageController.java`).
    Buttons are on the Audit log and Analytics pages.

49. **Where do migrations live?**
    In each service at `services/<service>/src/main/resources/db/migration/V<n>__<name>.sql`; Flyway runs them at start-up and each service owns one schema (`orchestrator`, `identity`, and so on).
    Never edit an applied migration; add the next number (the orchestrator is at `V21`). Hibernate does not alter tables.

50. **How is old data removed?**
    `RetentionService` purges run detail nightly (`MaintenanceScheduler.purgeOldRunDetail`, 03:15) using the days a workspace chose (`/api/orchestrator/retention-settings`); expired sessions are removed by `SessionSweep`.
    UI: Settings page, data retention.

51. **How do notifications work?**
    `NotificationService.java` posts a signed webhook when an approval waits or expires or a schedule pauses; settings are `/api/orchestrator/notification-settings`.
    In the browser, `web/src/lib/attention.ts` polls for items waiting on you, sets the badge count and optional browser notifications.

52. **How does voice work?**
    `VoiceController` and `VoiceService` use the workspace's ElevenLabs key for speech and transcription; `VoiceClipService` stores the clip when an agent calls the voice-note tool. Without a key the browser's own speech is used.
    Web: `web/src/lib/voice.ts`, `components/run/ClipAudio.tsx`.

### G. The web client

53. **How do I add a new page?**
    Create `web/src/routes/MyPage.tsx`; add a row to the `PRIVATE` table in `web/src/App.tsx` with its address and required permission; add the tab title in the same file; add a link in `web/src/components/layout/Navbar.tsx` (`NAV_ITEMS`).
    If the page calls a new API prefix, also add it to `web/vite.config.ts`, the gateway `application.yml` and `infra/launcher/nginx.conf`; `GatewayRoutesTest` keeps the three in step.

54. **Where do page data and mutations live?**
    In TanStack Query hooks: shared ones in `web/src/lib/queries.ts`, feature ones in `web/src/lib/*Queries.ts`, all through `web/src/lib/api.ts`.
    Add hooks there rather than calling `fetch` in a component.

55. **Where do the words on screen come from?**
    Status and code names come from `web/src/lib/labels.ts`; dates and money from `web/src/lib/format.ts`; connector wording from `ConnectorCatalog.java` and `ToolLabels.java`.
    Change the word in one place and every screen follows.

56. **Where is the design system?**
    Tokens in `web/src/styles/tokens.css`, base styles in `components.css`, per-area styles under `styles/polish/`, shared parts in `web/src/components/ui/`.
    `web/src/styles/design-system.test.ts` fails when a screen breaks the rules (`make design-check`).

### H. Running and testing

57. **How do I run the project for development?**
    Start PostgreSQL on port 55432, run `make build`, then `make dev-backend` (add `ARGS=--with-gateway` if Redis is running), then `cd web && pnpm install && pnpm dev` and open http://localhost:5173. `make dev-status` shows readiness; `make dev-stop` stops all.
    `scripts/dev-backend.sh` runs jar copies from `~/.aiwos-dev/run` with logs in `~/.aiwos-dev/logs`. Redis (rate limits) and Qdrant (vector search) are optional; screens that need them say so.

58. **How do I run it with Docker or the launcher?**
    `make up` starts the whole stack from `infra/compose/docker-compose.yml`. For a demo machine, double-click `Start AI Workforce OS.command` (macOS) or `.bat` (Windows); the app opens on http://localhost:4173.
    The launcher writes secrets once to `infra/launcher/.env`; keep that file. Allow Docker at least 6 GB of memory.

59. **How do I run the tests?**
    Backend: `make test` (unit and slice tests via `./mvnw test`), `make test-it` (integration tests, needs Docker), `make lint` (format check, needs JDK 21). CI skips the format check with `-Dspotless.check.skip=true`.
    Web: `cd web && pnpm test` (Vitest), `pnpm lint`, `pnpm build` (includes the type check), `make design-check`. Backend tests sit in each module's `src/test/java`; web tests are the `*.test.ts(x)` files beside the code.

60. **Which port is which, and where is the API documentation?**
    Gateway 8080, identity 8081, org 8082, orchestrator 8083, memory 8084, knowledge 8085, integrations 8086, analytics 8087, web dev 5173, launcher web 4173, Grafana 3001, Postgres (dev script) 55432.
    The merged API documentation is at http://localhost:8080/swagger-ui.html when the gateway is running.

---

## 4. Search tips: the `@find:`, `@what:` and `@flow:` tags

Every source file in the repository is being given a short comment header so that a plain question lands on the right file. The rules are in `/Users/mac/.aiwos-dev/find-tag-spec.md` (outside the repository). In summary:

**File header (every file that can hold comments).** Three comment lines at the very top (Java: above the `package` line):

```
// @find: <8-20 comma-separated plain keywords and phrases people would search for>
// @what: <one sentence saying what this file is responsible for>
// @flow: <optional: who calls it and what it calls next>
```

- `@find:` holds synonyms and verbs (create, update, delete, approve, run, schedule, connect), class names, HTTP routes (for example `POST /api/sources`) and the page or button names a person sees (for example "Knowledge page").
- `@what:` is one plain sentence.
- `@flow:` names the caller and the next step.
- Test files start with `@find: tests for <feature>`.
- Comment style varies by type: `//` for Java, TypeScript, TSX and JavaScript; `--` for SQL; `#` for YAML, shell, properties, Dockerfile and nginx; `/* ... */` for CSS; `<!-- ... -->` after the XML declaration in `pom.xml`. JSON and lock files cannot hold comments and carry no tag.

**Method tags.** One line `// @find: <keywords>` directly above each important entry point: every REST endpoint, every service method that changes data, every scheduled job, every exported React component and hook, every Flyway table definition.

**Rules.** Tags are comments only; no code changes. Existing comments are kept. A tag line stays under about 200 characters; a long list wraps onto a second `@find:` line. The tags are being added now, so a few files may not carry one yet, and some files may show two `@find:` lines.

**Example commands** (run from the repository root).

```bash
# Where is the knowledge base created, updated, searched?
grep -rn "@find:.*knowledge base" --include=*.java --include=*.ts --include=*.tsx .

# Which files deal with approvals? (list file names only)
grep -rln "@find:.*approval" --include=*.java --include=*.ts --include=*.tsx .

# What does each file do? (one sentence per file for one service)
grep -rn "@what:" services/knowledge-service/src/main

# Who calls ModelRouter?
grep -rn "@flow:.*ModelRouter" --include=*.java .

# Find a REST route
grep -rn "@find:.*POST /api/sources" --include=*.java .

# Find the page for a button or screen name
grep -rn "@find:.*Add connector" --include=*.tsx --include=*.ts web/src

# Migrations that touch a table
grep -rn "@find:.*audit" --include=*.sql services

# Skip dependencies and build output
grep -rn "@find:.*schedule" --include=*.java --include=*.ts --include=*.tsx --exclude-dir=node_modules --exclude-dir=target .
```

On macOS, if a pattern includes an asterisk and the shell reports "no matches found", quote the include patterns (`--include="*.java"`).

**In VS Code.**

1. Press Cmd+Shift+F (Ctrl+Shift+F on Windows and Linux) to open Search across files.
2. Type `@find:.*approval`, then turn on the regular-expression button (`.*`, or press Alt+Cmd+R).
3. Open "files to include" and enter `*.java, *.ts, *.tsx, *.sql` to narrow the search; put `node_modules, target` in "files to exclude".
4. To see what every file does, search `@what:` and read the one-line results.
5. To follow a flow, search `@flow:.*YourClassName`.
6. For a quick jump to a class by name, press Cmd+P and type the file name; to jump to a method, press Cmd+Shift+O in the open file.

Tip: search with two or three words from the question ("delete document", "reindex", "approve") and add synonyms if the first search finds nothing; the tags were written with synonyms for exactly this reason.

---

## 5. Glossary

| Term | Meaning in this product |
|---|---|
| **Workspace** (also organisation, org) | One company's private space. Every row in every table carries its workspace id. Entity `Organisation`. |
| **Member** | A person who belongs to a workspace (`Membership`). They can be invited, have a role, and be removed. |
| **Role** | A named set of permissions (owner, admin, manager, employee, viewer, or a custom one). Roles are rows a workspace can edit. |
| **Permission** | A fixed code such as `agent:read` or `approval:decide`, defined in `Permission.java`. Endpoints and pages require one. |
| **Agent** (AI employee, assistant) | A role-specialised AI worker with instructions, a model order, connector grants and optional memory. Each edit saves a new version. |
| **Template** | A ready-made agent (HR, Engineering Manager, Research, Support) a workspace can add in one click. |
| **Goal** | A piece of work given to the workforce. It breaks into tasks. |
| **Task** | One step of a goal, assigned to one agent; tasks can depend on earlier tasks. |
| **Run** | One agent's attempt at one task, made of ordered steps (model call, tool call, result). |
| **Step** | One saved event inside a run; steps are never edited. |
| **Approval** | A request, raised by a run, for a person to allow or refuse a risky action (send, post, delete). The run waits until it is decided. |
| **Four-eyes rule** | The setting that stops the person who asked for the work from approving it. |
| **Question** | A pause where an agent asks a person up to four multiple-choice questions before it continues. |
| **Chat** | The single conversation with the whole workforce; messages are routed to agents. |
| **Schedule** | Recurring or one-off work described in plain English that starts goals automatically. |
| **Connector** (tool server, MCP server) | A business tool agents can use (Gmail, Slack, GitHub and others), reached through the Model Context Protocol. |
| **Grant** | Permission for one agent to use one connector, with allowed tools and scopes. |
| **Sandbox / practice data** | The built-in stand-in for a connector, with sample data and no real account. Also the offline sandbox model that answers without a key. |
| **Live** | Connected to the real vendor with a stored token or OAuth sign-in. |
| **Provider** | A company that supplies language models (OpenRouter, Anthropic, Bedrock and others). |
| **Routing policy** | The ordered list of models tried for an agent, with the workspace default behind it. |
| **Candidate** | One provider-and-model entry in a routing policy. |
| **Fallback** (failover) | Moving to the next candidate when one fails. |
| **Budget** | Spending caps (monthly, per run, per agent per day) checked before each model call. |
| **Knowledge base** | The workspace's documents, organised in sources. |
| **Source** | A named folder of documents inside the knowledge base; it can be restricted or owned by one agent. |
| **Document** | One uploaded file inside a source. |
| **Chunk** (passage) | A short, heading-aware piece of a document; the unit that is searched and cited. |
| **Embedding** | A list of numbers that represents the meaning of a passage or question, used for meaning-based search. |
| **Grounding** | Making an answer rest on retrieved passages, with citations, instead of the model's own memory. |
| **Memory** | Short notes an agent keeps between runs, which people can read, correct, pin and forget. |
| **Audit log** | The append-only record of actions and decisions, linked by hashes so an edit can be detected. |
| **Outbox** | A local table where an event waits until it is delivered to the audit service, so nothing is lost if that service is down. |
| **Gateway** | The single entry point that checks the token, limits request rates and routes to services. |
| **Flyway migration** | A numbered SQL file that changes a service's database schema. |
