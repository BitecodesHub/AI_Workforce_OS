-- Analytics: read models built from what the other services did.
--
-- Nothing here is authoritative. Every row is derived from an event, and the service that emitted
-- it remains the source of truth. That is what lets these tables be dropped and rebuilt, which a
-- read model must be able to survive.

-- The audit projection. Each row carries the digest of the previous one, so a deleted or edited
-- row breaks the chain and is detectable rather than merely discouraged.
CREATE TABLE audit_events (
    id            UUID PRIMARY KEY,
    org_id        UUID,
    sequence      BIGSERIAL   NOT NULL,
    actor_id      TEXT        NOT NULL,
    actor_kind    TEXT        NOT NULL,
    -- An agent acting for a person records both. An audit trail that stops at "the orchestrator
    -- did it" cannot answer the only question ever asked of one.
    on_behalf_of  TEXT,
    action        TEXT        NOT NULL,
    resource_type TEXT        NOT NULL,
    resource_id   TEXT,
    outcome       TEXT        NOT NULL,
    detail        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    request_id    TEXT,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    previous_hash TEXT,
    entry_hash    TEXT        NOT NULL,
    CONSTRAINT audit_outcome_valid CHECK (outcome IN ('succeeded', 'failed', 'denied'))
);

CREATE INDEX audit_org_time_idx ON audit_events (org_id, occurred_at DESC);
CREATE INDEX audit_actor_idx ON audit_events (actor_id, occurred_at DESC);
CREATE INDEX audit_action_idx ON audit_events (action, occurred_at DESC);
CREATE UNIQUE INDEX audit_sequence_unique ON audit_events (sequence);

-- Append-only in the strongest sense the database offers. An audit log an application can edit
-- is a log nobody should rely on.
REVOKE UPDATE, DELETE ON audit_events FROM PUBLIC;

-- Daily rollups, so a dashboard reads a handful of rows rather than aggregating millions.
CREATE TABLE daily_activity (
    org_id          UUID        NOT NULL,
    day             DATE        NOT NULL,
    agent_id        UUID        NOT NULL,
    runs_started    INT         NOT NULL DEFAULT 0,
    runs_completed  INT         NOT NULL DEFAULT 0,
    runs_failed     INT         NOT NULL DEFAULT 0,
    approvals_raised INT        NOT NULL DEFAULT 0,
    approvals_approved INT      NOT NULL DEFAULT 0,
    approvals_rejected INT      NOT NULL DEFAULT 0,
    tool_calls      INT         NOT NULL DEFAULT 0,
    tool_calls_blocked INT      NOT NULL DEFAULT 0,
    prompt_tokens   BIGINT      NOT NULL DEFAULT 0,
    completion_tokens BIGINT    NOT NULL DEFAULT 0,
    cost            NUMERIC(14, 8) NOT NULL DEFAULT 0,
    -- Estimated, not measured, and labelled as such wherever it is shown. Presenting an estimate
    -- as a measurement is how a dashboard becomes a claim nobody can defend.
    minutes_saved_estimate INT  NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, day, agent_id)
);

CREATE INDEX daily_activity_org_day_idx ON daily_activity (org_id, day DESC);

CREATE TABLE provider_health_samples (
    id           UUID PRIMARY KEY,
    org_id       UUID,
    provider_id  TEXT        NOT NULL,
    circuit_state TEXT       NOT NULL,
    success_count INT        NOT NULL DEFAULT 0,
    failure_count INT        NOT NULL DEFAULT 0,
    p95_latency_ms INT       NOT NULL DEFAULT 0,
    sampled_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX provider_health_idx ON provider_health_samples (provider_id, sampled_at DESC);

CREATE TABLE processed_events (
    event_id     TEXT PRIMARY KEY,
    topic        TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE runtime_settings (
    key        TEXT        NOT NULL,
    org_id     UUID,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);

CREATE UNIQUE INDEX an_runtime_settings_platform_unique ON runtime_settings (key) WHERE org_id IS NULL;
CREATE UNIQUE INDEX an_runtime_settings_org_unique ON runtime_settings (key, org_id) WHERE org_id IS NOT NULL;
