-- Organisations: the workspace, who governs it, and the secrets it holds.

CREATE TABLE organisations (
    id            UUID PRIMARY KEY,
    name          TEXT        NOT NULL,
    slug          TEXT        NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'active',
    timezone      TEXT        NOT NULL DEFAULT 'Australia/Melbourne',
    -- Used by agents to decide whether an action should wait until the morning. Stored as
    -- minutes from midnight so the comparison needs no date arithmetic.
    working_hours_start_minute INT NOT NULL DEFAULT 540,
    working_hours_end_minute   INT NOT NULL DEFAULT 1050,
    working_days  TEXT        NOT NULL DEFAULT 'MON,TUE,WED,THU,FRI',
    owner_id      UUID,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    TEXT,
    updated_by    TEXT,
    CONSTRAINT organisations_status_valid CHECK (status IN ('active', 'suspended', 'closed'))
);

CREATE UNIQUE INDEX organisations_slug_unique ON organisations (lower(slug));

CREATE TABLE invitations (
    id          UUID PRIMARY KEY,
    org_id      UUID        NOT NULL REFERENCES organisations (id) ON DELETE CASCADE,
    email       TEXT        NOT NULL,
    role_name   TEXT        NOT NULL,
    -- Only the hash. An invitation link in a database dump is an unaccepted account waiting to
    -- be taken by whoever reads the dump.
    token_hash  TEXT        NOT NULL,
    invited_by  UUID,
    status      TEXT        NOT NULL DEFAULT 'pending',
    expires_at  TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT,
    updated_by  TEXT,
    CONSTRAINT invitations_status_valid CHECK (status IN ('pending', 'accepted', 'revoked', 'expired'))
);

CREATE UNIQUE INDEX invitations_token_unique ON invitations (token_hash);
CREATE INDEX invitations_pending_idx ON invitations (org_id) WHERE status = 'pending';
-- One open invitation per address per workspace, so re-inviting replaces rather than accumulates.
CREATE UNIQUE INDEX invitations_open_unique ON invitations (org_id, lower(email)) WHERE status = 'pending';

-- ---------------------------------------------------------------------------------------------
-- Credentials
-- ---------------------------------------------------------------------------------------------

-- Every secret the platform holds on a workspace's behalf: model provider keys, OAuth tokens for
-- tool servers, webhook signing secrets.
--
-- The value column holds ciphertext in a self-describing envelope - version, key id, wrapped data
-- key, nonce, payload - so a value stays readable after a master key rotation until a background
-- job re-wraps it. The fingerprint is a truncated digest, which lets the console prove a
-- credential is present and tell two apart without ever returning one.
CREATE TABLE credentials (
    id           UUID PRIMARY KEY,
    org_id       UUID        NOT NULL REFERENCES organisations (id) ON DELETE CASCADE,
    ref          TEXT        NOT NULL,
    kind         TEXT        NOT NULL DEFAULT 'api_key',
    encrypted_value TEXT     NOT NULL,
    fingerprint  TEXT        NOT NULL,
    key_id       TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    rotated_at   TIMESTAMPTZ,
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   TEXT,
    updated_by   TEXT,
    CONSTRAINT credentials_kind_valid CHECK (kind IN ('api_key', 'oauth_token', 'aws_keypair', 'webhook_secret'))
);

CREATE UNIQUE INDEX credentials_org_ref_unique ON credentials (org_id, ref);
-- No index on encrypted_value, deliberately: nothing should ever search by a secret's ciphertext.

CREATE TABLE runtime_settings (
    key        TEXT        NOT NULL,
    org_id     UUID,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);

CREATE UNIQUE INDEX org_runtime_settings_platform_unique ON runtime_settings (key) WHERE org_id IS NULL;
CREATE UNIQUE INDEX org_runtime_settings_org_unique ON runtime_settings (key, org_id) WHERE org_id IS NOT NULL;
