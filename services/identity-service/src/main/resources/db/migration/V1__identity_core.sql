-- Identity: people, how they prove who they are, and what they are allowed to do.
--
-- This schema is owned by identity-service alone. No other service reads these tables; they
-- receive what they need in a signed token, or by asking this service. That boundary is what
-- makes the service independently deployable rather than merely separately started.

-- ---------------------------------------------------------------------------------------------
-- People and credentials
-- ---------------------------------------------------------------------------------------------

CREATE TABLE users (
    id                  UUID PRIMARY KEY,
    email               TEXT        NOT NULL,
    -- Addresses are compared case-insensitively but displayed as typed, so both are stored.
    -- A generated column keeps them from drifting apart, which a trigger eventually would.
    email_normalised    TEXT        NOT NULL GENERATED ALWAYS AS (lower(email)) STORED,
    email_verified_at   TIMESTAMPTZ,
    display_name        TEXT        NOT NULL,
    -- Argon2id, encoded with its own parameters, so raising the cost later does not invalidate
    -- existing hashes: each one is verified with the parameters it was created under.
    password_hash       TEXT,
    password_changed_at TIMESTAMPTZ,
    mfa_secret          TEXT,
    mfa_enabled         BOOLEAN     NOT NULL DEFAULT FALSE,
    status              TEXT        NOT NULL DEFAULT 'active',
    failed_login_count  INT         NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    avatar_url          TEXT,
    locale              TEXT        NOT NULL DEFAULT 'en-AU',
    timezone            TEXT        NOT NULL DEFAULT 'UTC',
    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          TEXT,
    updated_by          TEXT,
    CONSTRAINT users_status_valid CHECK (status IN ('active', 'invited', 'suspended', 'deactivated')),
    -- A password is optional: an account created through Google or GitHub has none, and
    -- requiring one would force a meaningless password onto every federated user.
    CONSTRAINT users_email_shape CHECK (position('@' IN email) > 1)
);

CREATE UNIQUE INDEX users_email_unique ON users (email_normalised);
CREATE INDEX users_status_idx ON users (status) WHERE status <> 'active';

-- ---------------------------------------------------------------------------------------------
-- Sessions
-- ---------------------------------------------------------------------------------------------

-- Refresh tokens rotate: each use issues a new one and retires the old. A retired token being
-- presented again means it was captured, so the whole family is revoked rather than just that
-- token. Storing only the hash means a database disclosure does not hand over live sessions.
CREATE TABLE sessions (
    id                UUID PRIMARY KEY,
    user_id           UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id         UUID        NOT NULL,
    refresh_token_hash TEXT       NOT NULL,
    previous_id       UUID REFERENCES sessions (id) ON DELETE SET NULL,
    issued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,
    used_at           TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,
    revoked_reason    TEXT,
    ip_address        INET,
    user_agent        TEXT,
    org_id            UUID,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        TEXT,
    updated_by        TEXT
);

CREATE UNIQUE INDEX sessions_token_unique ON sessions (refresh_token_hash);
CREATE INDEX sessions_family_idx ON sessions (family_id);
CREATE INDEX sessions_user_active_idx ON sessions (user_id) WHERE revoked_at IS NULL;
CREATE INDEX sessions_expiry_idx ON sessions (expires_at) WHERE revoked_at IS NULL;

CREATE TABLE oauth_accounts (
    id               UUID PRIMARY KEY,
    user_id          UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider         TEXT        NOT NULL,
    provider_user_id TEXT        NOT NULL,
    email            TEXT,
    linked_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       TEXT,
    updated_by       TEXT,
    CONSTRAINT oauth_provider_valid CHECK (provider IN ('google', 'github', 'microsoft'))
);

-- One identity at a provider maps to exactly one account here. Without this, two people could
-- link the same Google account and each would be able to sign in as the other.
CREATE UNIQUE INDEX oauth_provider_subject_unique ON oauth_accounts (provider, provider_user_id);

-- ---------------------------------------------------------------------------------------------
-- Authorisation
-- ---------------------------------------------------------------------------------------------

-- Permission codes are seeded from the build's registry, because a code only means something if
-- some endpoint checks for it. Roles and their composition, below, are entirely data.
CREATE TABLE permissions (
    code           TEXT PRIMARY KEY,
    resource       TEXT        NOT NULL,
    action         TEXT        NOT NULL,
    description    TEXT        NOT NULL,
    administrative BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX permissions_resource_idx ON permissions (resource);

CREATE TABLE roles (
    id          UUID PRIMARY KEY,
    -- NULL means a system role shared by every workspace; a value scopes the role to one.
    org_id      UUID,
    name        TEXT        NOT NULL,
    description TEXT        NOT NULL DEFAULT '',
    is_system   BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Incremented whenever the role's permissions change. Access tokens carry the version they
    -- were minted under, so a narrowed role takes effect at once instead of when tokens expire.
    permission_version BIGINT NOT NULL DEFAULT 1,
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT,
    updated_by  TEXT
);

-- Two partial indexes rather than one: a NULL org_id would make a plain unique index permit any
-- number of duplicate system roles, because NULL is not equal to NULL.
CREATE UNIQUE INDEX roles_system_name_unique ON roles (name) WHERE org_id IS NULL;
CREATE UNIQUE INDEX roles_org_name_unique ON roles (org_id, name) WHERE org_id IS NOT NULL;

CREATE TABLE role_permissions (
    role_id         UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_code TEXT NOT NULL REFERENCES permissions (code) ON DELETE CASCADE,
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by      TEXT,
    PRIMARY KEY (role_id, permission_code)
);

CREATE INDEX role_permissions_permission_idx ON role_permissions (permission_code);

CREATE TABLE memberships (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    org_id     UUID        NOT NULL,
    role_id    UUID        NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    status     TEXT        NOT NULL DEFAULT 'active',
    invited_by UUID REFERENCES users (id) ON DELETE SET NULL,
    invited_at TIMESTAMPTZ,
    joined_at  TIMESTAMPTZ,
    version    BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by TEXT,
    updated_by TEXT,
    CONSTRAINT memberships_status_valid CHECK (status IN ('active', 'invited', 'suspended'))
);

-- One membership per person per workspace. Two would make "which role does she hold here?"
-- ambiguous, and the answer decides what she can do.
CREATE UNIQUE INDEX memberships_user_org_unique ON memberships (user_id, org_id);
CREATE INDEX memberships_org_idx ON memberships (org_id) WHERE status = 'active';
CREATE INDEX memberships_role_idx ON memberships (role_id);

CREATE TABLE api_keys (
    id            UUID PRIMARY KEY,
    org_id        UUID        NOT NULL,
    role_id       UUID        NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    name          TEXT        NOT NULL,
    -- The key is shown once at creation and only its hash is kept. A key that can be read back
    -- from the database is a key that leaves in a backup.
    key_hash      TEXT        NOT NULL,
    key_prefix    TEXT        NOT NULL,
    created_by    UUID REFERENCES users (id) ON DELETE SET NULL,
    expires_at    TIMESTAMPTZ,
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    TEXT
);

CREATE UNIQUE INDEX api_keys_hash_unique ON api_keys (key_hash);
CREATE INDEX api_keys_org_idx ON api_keys (org_id) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------------------------
-- Signing keys
-- ---------------------------------------------------------------------------------------------

-- Keys live in the database so every replica of this service signs with the same one and every
-- other service can verify through the published JWKS. Rotation adds a row: the new key signs,
-- the previous one keeps verifying until the last token minted under it has expired.
CREATE TABLE signing_keys (
    kid         TEXT PRIMARY KEY,
    algorithm   TEXT        NOT NULL DEFAULT 'EdDSA',
    public_jwk  TEXT        NOT NULL,
    private_key_encrypted TEXT NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'active',
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    retired_at  TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT signing_keys_status_valid CHECK (status IN ('active', 'retiring', 'retired'))
);

-- Exactly one key may be active at a time; two would make the published key set ambiguous.
CREATE UNIQUE INDEX signing_keys_single_active ON signing_keys ((status)) WHERE status = 'active';

-- ---------------------------------------------------------------------------------------------
-- Runtime settings, shared shape across services
-- ---------------------------------------------------------------------------------------------

CREATE TABLE runtime_settings (
    key        TEXT        NOT NULL,
    -- NULL scopes the value to the whole platform; a value scopes it to one workspace.
    org_id     UUID,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);

CREATE UNIQUE INDEX runtime_settings_platform_unique ON runtime_settings (key) WHERE org_id IS NULL;
CREATE UNIQUE INDEX runtime_settings_org_unique ON runtime_settings (key, org_id) WHERE org_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- Audit
-- ---------------------------------------------------------------------------------------------

-- Append-only, with each row carrying the digest of the one before it. A deleted or edited row
-- breaks the chain, so tampering is detectable rather than merely discouraged.
CREATE TABLE audit_events (
    id            UUID PRIMARY KEY,
    org_id        UUID,
    actor_id      TEXT        NOT NULL,
    actor_kind    TEXT        NOT NULL,
    on_behalf_of  TEXT,
    action        TEXT        NOT NULL,
    resource_type TEXT        NOT NULL,
    resource_id   TEXT,
    outcome       TEXT        NOT NULL,
    detail        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    request_id    TEXT,
    ip_address    INET,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    previous_hash TEXT,
    entry_hash    TEXT        NOT NULL,
    CONSTRAINT audit_outcome_valid CHECK (outcome IN ('succeeded', 'failed', 'denied'))
);

CREATE INDEX audit_org_time_idx ON audit_events (org_id, occurred_at DESC);
CREATE INDEX audit_actor_idx ON audit_events (actor_id, occurred_at DESC);
CREATE INDEX audit_resource_idx ON audit_events (resource_type, resource_id);

REVOKE UPDATE, DELETE ON audit_events FROM PUBLIC;
