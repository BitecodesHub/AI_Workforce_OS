-- OAuth: the app a workspace registered with a provider, and the sign-ins in flight.
--
-- The client secret and, for a connection, the access and refresh tokens are stored encrypted
-- (envelope encryption, bound to the workspace). Nothing in these tables is readable without the
-- platform's key, and no endpoint returns any of it.

CREATE TABLE oauth_apps (
    id               UUID PRIMARY KEY,
    org_id           UUID        NOT NULL,
    -- google, microsoft or salesforce. One app serves every connector of that provider.
    provider         TEXT        NOT NULL,
    client_id        TEXT        NOT NULL,
    client_secret_ref TEXT       NOT NULL,
    -- Provider-specific settings that are not secret, as JSON: the Microsoft tenant, the
    -- Salesforce login domain.
    settings         TEXT        NOT NULL DEFAULT '{}',
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       TEXT,
    updated_by       TEXT
);

CREATE UNIQUE INDEX oauth_apps_org_provider_unique ON oauth_apps (org_id, provider);

-- One row per consent screen opened. The state sent to the provider names the row; the row holds
-- the PKCE verifier and says whether the state has been used. Single use and short life are what
-- stop a stolen or replayed callback link from connecting anything.
CREATE TABLE oauth_states (
    id            UUID PRIMARY KEY,
    org_id        UUID        NOT NULL,
    user_id       UUID,
    server        TEXT        NOT NULL,
    verifier_ref  TEXT        NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    used_at       TIMESTAMPTZ,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    TEXT,
    updated_by    TEXT
);

CREATE INDEX oauth_states_expiry_idx ON oauth_states (expires_at);
