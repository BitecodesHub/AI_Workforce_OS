-- Orchestrator: agents, the work they are given, and the record of what they did.
--
-- Two ideas shape this schema. An agent's configuration is versioned and a version becomes
-- immutable the moment a run uses it, so a trace from last week can still be read against the
-- prompt that actually produced it. And a run's state is persisted at every step, so a worker
-- dying mid-task resumes rather than starting again - or worse, repeating a side effect.

-- ---------------------------------------------------------------------------------------------
-- Language model providers and models, as rows
-- ---------------------------------------------------------------------------------------------

CREATE TABLE llm_providers (
    id                 TEXT PRIMARY KEY,
    display_name       TEXT        NOT NULL,
    -- Which adapter speaks to it. Four of the seven share OPENAI_COMPATIBLE because they share
    -- a protocol; the enum is the only thing that decides.
    kind               TEXT        NOT NULL,
    base_url           TEXT        NOT NULL DEFAULT '',
    -- A pointer into the credential store, never the credential. A dump of this table must not
    -- be a dump of the workspace's API keys.
    credential_ref     TEXT,
    enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    default_headers    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- Ordered. Bedrock tries each in turn before the router is told the provider failed at all.
    regions            TEXT[]      NOT NULL DEFAULT ARRAY[]::TEXT[],
    requests_per_minute INT,
    max_concurrent     INT,
    priority           INT         NOT NULL DEFAULT 0,
    credential_status  TEXT        NOT NULL DEFAULT 'unknown',
    credential_checked_at TIMESTAMPTZ,
    org_id             UUID,
    version            BIGINT      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         TEXT,
    updated_by         TEXT,
    CONSTRAINT llm_provider_kind_valid CHECK (kind IN
        ('OPENAI_COMPATIBLE', 'ANTHROPIC', 'GEMINI', 'BEDROCK', 'SANDBOX')),
    CONSTRAINT llm_credential_status_valid CHECK (credential_status IN
        ('unknown', 'valid', 'missing', 'rejected'))
);

CREATE TABLE llm_models (
    provider_id        TEXT        NOT NULL REFERENCES llm_providers (id) ON DELETE CASCADE,
    model_id           TEXT        NOT NULL,
    display_name       TEXT        NOT NULL,
    context_window     INT         NOT NULL,
    max_output_tokens  INT         NOT NULL,
    supports_tools     BOOLEAN     NOT NULL DEFAULT FALSE,
    supports_json_mode BOOLEAN     NOT NULL DEFAULT FALSE,
    supports_streaming BOOLEAN     NOT NULL DEFAULT TRUE,
    supports_vision    BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Priced per million tokens, in the platform's accounting currency, to eight decimal places:
    -- fractions of a cent across millions of calls stop being a rounding curiosity.
    input_cost_per_million  NUMERIC(14, 8) NOT NULL DEFAULT 0,
    cached_cost_per_million NUMERIC(14, 8),
    output_cost_per_million NUMERIC(14, 8) NOT NULL DEFAULT 0,
    enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    -- Set when a provider answers "no such model". Vendors retire models on their own schedule,
    -- and the first we hear of it is a 404 somebody is waiting on.
    unavailable_until  TIMESTAMPTZ,
    unavailable_reason TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_id, model_id),
    CONSTRAINT llm_model_window_positive CHECK (context_window > 0)
);

CREATE INDEX llm_models_enabled_idx ON llm_models (provider_id) WHERE enabled;

-- ---------------------------------------------------------------------------------------------
-- Agents
-- ---------------------------------------------------------------------------------------------

CREATE TABLE agents (
    id           UUID PRIMARY KEY,
    org_id       UUID        NOT NULL,
    key          TEXT        NOT NULL,
    name         TEXT        NOT NULL,
    category     TEXT        NOT NULL DEFAULT 'operations',
    status       TEXT        NOT NULL DEFAULT 'active',
    -- Points at the version currently in force. A run pins its own, so changing this does not
    -- rewrite history.
    current_version_id UUID,
    owner_id     UUID,
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   TEXT,
    updated_by   TEXT,
    CONSTRAINT agents_status_valid CHECK (status IN ('active', 'paused', 'retired')),
    CONSTRAINT agents_category_valid CHECK (category IN ('operations', 'engineering', 'growth', 'support'))
);

CREATE UNIQUE INDEX agents_org_key_unique ON agents (org_id, key);
CREATE INDEX agents_org_status_idx ON agents (org_id) WHERE status = 'active';

CREATE TABLE agent_versions (
    id            UUID PRIMARY KEY,
    agent_id      UUID        NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    org_id        UUID        NOT NULL,
    revision      INT         NOT NULL,
    -- The prompt is a row, never a constant in code. Changing how an agent behaves is an edit in
    -- the console with a diff, not a deployment.
    system_prompt TEXT        NOT NULL,
    goals         TEXT        NOT NULL DEFAULT '',
    temperature   NUMERIC(3, 2),
    max_output_tokens INT,
    max_steps     INT         NOT NULL DEFAULT 12,
    -- Frozen the first time a run uses it. Editing a version a trace refers to would make the
    -- trace a record of something that never happened.
    sealed        BOOLEAN     NOT NULL DEFAULT FALSE,
    sealed_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    TEXT
);

CREATE UNIQUE INDEX agent_versions_revision_unique ON agent_versions (agent_id, revision);

CREATE TABLE model_policies (
    id          UUID PRIMARY KEY,
    org_id      UUID        NOT NULL,
    -- NULL means the workspace default, which every agent without its own policy inherits.
    agent_id    UUID REFERENCES agents (id) ON DELETE CASCADE,
    exhausted_behaviour TEXT NOT NULL DEFAULT 'FAIL_CLOSED',
    max_attempts_per_candidate INT NOT NULL DEFAULT 2,
    overall_deadline_seconds   INT NOT NULL DEFAULT 300,
    compact_on_overflow BOOLEAN NOT NULL DEFAULT TRUE,
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT,
    updated_by  TEXT,
    CONSTRAINT exhausted_behaviour_valid CHECK (exhausted_behaviour IN ('FAIL_CLOSED', 'DEGRADE_TO_SANDBOX'))
);

CREATE UNIQUE INDEX model_policies_agent_unique ON model_policies (agent_id) WHERE agent_id IS NOT NULL;
CREATE UNIQUE INDEX model_policies_default_unique ON model_policies (org_id) WHERE agent_id IS NULL;

CREATE TABLE model_policy_candidates (
    policy_id   UUID        NOT NULL REFERENCES model_policies (id) ON DELETE CASCADE,
    position    INT         NOT NULL,
    provider_id TEXT        NOT NULL,
    model_id    TEXT        NOT NULL,
    temperature NUMERIC(3, 2),
    max_output_tokens INT,
    weight      INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (policy_id, position)
);

CREATE TABLE agent_tool_grants (
    id          UUID PRIMARY KEY,
    org_id      UUID        NOT NULL,
    agent_id    UUID        NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    server      TEXT        NOT NULL,
    -- Empty means every tool the server offers, which is the sensible default for a read-only one.
    allowed_tools TEXT[]    NOT NULL DEFAULT ARRAY[]::TEXT[],
    scopes      TEXT[]      NOT NULL DEFAULT ARRAY[]::TEXT[],
    require_approval BOOLEAN NOT NULL DEFAULT FALSE,
    max_calls_per_run INT,
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT,
    updated_by  TEXT
);

CREATE UNIQUE INDEX agent_tool_grants_unique ON agent_tool_grants (agent_id, server);

-- ---------------------------------------------------------------------------------------------
-- Work
-- ---------------------------------------------------------------------------------------------

CREATE TABLE goals (
    id          UUID PRIMARY KEY,
    org_id      UUID        NOT NULL,
    title       TEXT        NOT NULL,
    description TEXT        NOT NULL DEFAULT '',
    status      TEXT        NOT NULL DEFAULT 'planning',
    requested_by UUID,
    completed_at TIMESTAMPTZ,
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT,
    updated_by  TEXT,
    CONSTRAINT goals_status_valid CHECK (status IN
        ('planning', 'running', 'waiting', 'completed', 'failed', 'cancelled'))
);

CREATE INDEX goals_org_status_idx ON goals (org_id, status, created_at DESC);

CREATE TABLE tasks (
    id          UUID PRIMARY KEY,
    org_id      UUID        NOT NULL,
    goal_id     UUID        NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    agent_id    UUID REFERENCES agents (id) ON DELETE SET NULL,
    title       TEXT        NOT NULL,
    instruction TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'pending',
    position    INT         NOT NULL DEFAULT 0,
    -- The task graph is a DAG. Dependencies are stored as an array rather than a join table
    -- because they are read as a whole, always, and never queried across.
    depends_on  UUID[]      NOT NULL DEFAULT ARRAY[]::UUID[],
    attempt     INT         NOT NULL DEFAULT 0,
    max_attempts INT        NOT NULL DEFAULT 2,
    result      TEXT,
    failure_reason TEXT,
    started_at  TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT,
    updated_by  TEXT,
    CONSTRAINT tasks_status_valid CHECK (status IN
        ('pending', 'ready', 'running', 'waiting_approval', 'completed', 'failed', 'cancelled', 'skipped'))
);

CREATE INDEX tasks_goal_idx ON tasks (goal_id, position);
CREATE INDEX tasks_ready_idx ON tasks (org_id, status) WHERE status IN ('ready', 'pending');

CREATE TABLE runs (
    id            UUID PRIMARY KEY,
    org_id        UUID        NOT NULL,
    task_id       UUID REFERENCES tasks (id) ON DELETE CASCADE,
    agent_id      UUID        NOT NULL,
    -- Pinned, so the trace can always be read against the prompt that produced it.
    agent_version_id UUID     NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'running',
    trigger       TEXT        NOT NULL DEFAULT 'task',
    step_count    INT         NOT NULL DEFAULT 0,
    -- A lease with a heartbeat. A worker that dies stops renewing, and the reaper picks the run
    -- up rather than leaving it running forever in the interface.
    worker_id     TEXT,
    lease_expires_at TIMESTAMPTZ,
    total_prompt_tokens     INT NOT NULL DEFAULT 0,
    total_completion_tokens INT NOT NULL DEFAULT 0,
    total_cost    NUMERIC(14, 8) NOT NULL DEFAULT 0,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    failure_reason TEXT,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    TEXT,
    updated_by    TEXT,
    CONSTRAINT runs_status_valid CHECK (status IN
        ('running', 'waiting_approval', 'completed', 'failed', 'cancelled', 'abandoned'))
);

CREATE INDEX runs_org_started_idx ON runs (org_id, started_at DESC);
CREATE INDEX runs_agent_idx ON runs (agent_id, started_at DESC);
-- Used by the reaper to find runs whose worker stopped renewing the lease.
CREATE INDEX runs_expired_lease_idx ON runs (lease_expires_at) WHERE status = 'running';

CREATE TABLE run_steps (
    id          UUID PRIMARY KEY,
    org_id      UUID        NOT NULL,
    run_id      UUID        NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    position    INT         NOT NULL,
    kind        TEXT        NOT NULL,
    -- The whole trace, including the attempts that failed. A run that answered on the third
    -- provider is only explicable if the first two are recorded.
    detail      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    provider_id TEXT,
    model_id    TEXT,
    prompt_tokens     INT   NOT NULL DEFAULT 0,
    completion_tokens INT   NOT NULL DEFAULT 0,
    cost        NUMERIC(14, 8) NOT NULL DEFAULT 0,
    duration_ms BIGINT      NOT NULL DEFAULT 0,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT run_steps_kind_valid CHECK (kind IN
        ('model_call', 'tool_call', 'approval', 'handoff', 'memory_read', 'memory_write',
         'knowledge_query', 'note', 'error'))
);

CREATE UNIQUE INDEX run_steps_position_unique ON run_steps (run_id, position);
CREATE INDEX run_steps_run_idx ON run_steps (run_id, occurred_at);

-- ---------------------------------------------------------------------------------------------
-- Approvals
-- ---------------------------------------------------------------------------------------------

CREATE TABLE approvals (
    id           UUID PRIMARY KEY,
    org_id       UUID        NOT NULL,
    run_id       UUID        NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    task_id      UUID REFERENCES tasks (id) ON DELETE CASCADE,
    agent_id     UUID        NOT NULL,
    action_class TEXT        NOT NULL,
    tool         TEXT,
    summary      TEXT        NOT NULL,
    -- Kept so the approver sees exactly what will be sent, not a paraphrase of it.
    payload      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status       TEXT        NOT NULL DEFAULT 'pending',
    required_permission TEXT NOT NULL DEFAULT 'approval:decide',
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    decided_at   TIMESTAMPTZ,
    decided_by   UUID,
    decision_note TEXT,
    -- What happens if nobody decides in time. Defaults to rejection: an action nobody approved
    -- must not happen because everybody was busy.
    on_expiry    TEXT        NOT NULL DEFAULT 'reject',
    escalated_to UUID,
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   TEXT,
    updated_by   TEXT,
    CONSTRAINT approvals_status_valid CHECK (status IN ('pending', 'approved', 'rejected', 'expired', 'cancelled')),
    CONSTRAINT approvals_expiry_valid CHECK (on_expiry IN ('reject', 'escalate'))
);

CREATE INDEX approvals_pending_idx ON approvals (org_id, requested_at) WHERE status = 'pending';
CREATE INDEX approvals_expiry_idx ON approvals (expires_at) WHERE status = 'pending';

CREATE TABLE approval_policies (
    id           UUID PRIMARY KEY,
    org_id       UUID        NOT NULL,
    agent_id     UUID REFERENCES agents (id) ON DELETE CASCADE,
    action_class TEXT        NOT NULL,
    require_approval BOOLEAN NOT NULL DEFAULT TRUE,
    approver_role TEXT,
    expiry_minutes INT       NOT NULL DEFAULT 1440,
    on_expiry    TEXT        NOT NULL DEFAULT 'reject',
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   TEXT,
    updated_by   TEXT,
    CONSTRAINT approval_policies_class_valid CHECK (action_class IN ('READ', 'WRITE', 'OUTBOUND', 'DESTRUCTIVE'))
);

CREATE UNIQUE INDEX approval_policies_unique ON approval_policies (org_id, COALESCE(agent_id, '00000000-0000-0000-0000-000000000000'::uuid), action_class);

-- ---------------------------------------------------------------------------------------------
-- Accounting and idempotency
-- ---------------------------------------------------------------------------------------------

CREATE TABLE llm_usage (
    id          UUID PRIMARY KEY,
    org_id      UUID        NOT NULL,
    agent_id    UUID,
    run_id      UUID,
    provider_id TEXT        NOT NULL,
    model_id    TEXT        NOT NULL,
    -- Failed attempts are recorded too. A provider bills for a call that timed out after
    -- generating most of an answer, and a spend report that ignores them drifts from the invoice.
    outcome     TEXT        NOT NULL,
    failure     TEXT,
    skip_reason TEXT,
    prompt_tokens     INT   NOT NULL DEFAULT 0,
    cached_tokens     INT   NOT NULL DEFAULT 0,
    completion_tokens INT   NOT NULL DEFAULT 0,
    cost        NUMERIC(14, 8) NOT NULL DEFAULT 0,
    duration_ms BIGINT      NOT NULL DEFAULT 0,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX llm_usage_org_time_idx ON llm_usage (org_id, occurred_at DESC);
CREATE INDEX llm_usage_provider_idx ON llm_usage (provider_id, occurred_at DESC);

CREATE TABLE budgets (
    -- One row per workspace, keyed by the workspace identifier itself. Named `id` because the
    -- entity extends the shared base type, and a base type whose column names are not honoured
    -- is a base type that fails schema validation at startup.
    id              UUID PRIMARY KEY,
    monthly_cap     NUMERIC(14, 4),
    per_run_cap     NUMERIC(14, 4),
    per_agent_daily_cap NUMERIC(14, 4),
    spent_this_month NUMERIC(14, 8) NOT NULL DEFAULT 0,
    period_started_at TIMESTAMPTZ NOT NULL DEFAULT date_trunc('month', now()),
    -- What to do at the cap. Stopping is the default: silently downgrading to a cheaper model
    -- changes the quality of answers without anybody being told.
    on_exhausted    TEXT    NOT NULL DEFAULT 'stop',
    version         BIGINT  NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      TEXT,
    updated_by      TEXT,
    CONSTRAINT budgets_on_exhausted_valid CHECK (on_exhausted IN ('stop', 'sandbox'))
);

-- Records every event this service has already handled. Kafka delivers at least once, so a
-- consumer that is not idempotent will double-send, double-charge or double-approve.
CREATE TABLE processed_events (
    event_id    TEXT PRIMARY KEY,
    topic       TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX processed_events_cleanup_idx ON processed_events (processed_at);

CREATE TABLE runtime_settings (
    key        TEXT        NOT NULL,
    org_id     UUID,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);

CREATE UNIQUE INDEX orch_runtime_settings_platform_unique ON runtime_settings (key) WHERE org_id IS NULL;
CREATE UNIQUE INDEX orch_runtime_settings_org_unique ON runtime_settings (key, org_id) WHERE org_id IS NOT NULL;
