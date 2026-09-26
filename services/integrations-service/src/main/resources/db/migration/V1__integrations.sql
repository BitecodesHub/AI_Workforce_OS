-- Integrations: which tool servers a workspace has connected, and on what terms.

CREATE TABLE connections (
    id            UUID PRIMARY KEY,
    org_id        UUID        NOT NULL,
    server        TEXT        NOT NULL,
    display_name  TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'disconnected',
    -- The scopes actually granted, which is not the same as the scopes requested: a person can
    -- decline one at the provider's consent screen and the connection still succeeds.
    granted_scopes TEXT[]     NOT NULL DEFAULT ARRAY[]::TEXT[],
    requested_scopes TEXT[]   NOT NULL DEFAULT ARRAY[]::TEXT[],
    credential_ref TEXT,
    account_label TEXT,
    connected_by  UUID,
    connected_at  TIMESTAMPTZ,
    -- Refreshed ahead of this, not after: a token that expires between the check and the call
    -- fails a person's action rather than a background job.
    token_expires_at TIMESTAMPTZ,
    last_refreshed_at TIMESTAMPTZ,
    last_error    TEXT,
    -- True when a refresh failed and the person must consent again. The interface needs to
    -- distinguish this from "never connected", because the remedy is different.
    reconnect_required BOOLEAN NOT NULL DEFAULT FALSE,
    sandbox       BOOLEAN     NOT NULL DEFAULT TRUE,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    TEXT,
    updated_by    TEXT,
    CONSTRAINT connections_status_valid CHECK (status IN
        ('disconnected', 'connected', 'sandbox', 'error', 'revoked'))
);

CREATE UNIQUE INDEX connections_org_server_unique ON connections (org_id, server);
CREATE INDEX connections_refresh_idx ON connections (token_expires_at) WHERE status = 'connected';

-- Every tool call, whether it ran or was refused.
--
-- The refusals are the point. "The agent tried to send this and was blocked" is exactly what
-- somebody needs to show afterwards, and it exists nowhere else.
CREATE TABLE tool_invocations (
    id           UUID PRIMARY KEY,
    org_id       UUID        NOT NULL,
    agent_id     UUID,
    run_id       UUID,
    server       TEXT        NOT NULL,
    tool         TEXT        NOT NULL,
    side_effect  TEXT        NOT NULL,
    status       TEXT        NOT NULL,
    -- Arguments are stored redacted. They routinely contain the body of an email or the contents
    -- of a document, and a log that holds those is a data store nobody has secured.
    arguments_redacted JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_summary TEXT,
    -- Set when a call timed out on a tool that cannot be safely repeated. The action may or may
    -- not have happened, and the platform must never quietly decide which.
    indeterminate BOOLEAN    NOT NULL DEFAULT FALSE,
    approval_id  UUID,
    duration_ms  BIGINT      NOT NULL DEFAULT 0,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tool_invocations_status_valid CHECK (status IN
        ('SUCCEEDED', 'FAILED', 'INDETERMINATE', 'BLOCKED'))
);

CREATE INDEX tool_invocations_org_time_idx ON tool_invocations (org_id, occurred_at DESC);
CREATE INDEX tool_invocations_agent_idx ON tool_invocations (agent_id, occurred_at DESC);
CREATE INDEX tool_invocations_indeterminate_idx ON tool_invocations (org_id) WHERE indeterminate;

CREATE TABLE runtime_settings (
    key        TEXT        NOT NULL,
    org_id     UUID,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);

CREATE UNIQUE INDEX int_runtime_settings_platform_unique ON runtime_settings (key) WHERE org_id IS NULL;
CREATE UNIQUE INDEX int_runtime_settings_org_unique ON runtime_settings (key, org_id) WHERE org_id IS NOT NULL;
