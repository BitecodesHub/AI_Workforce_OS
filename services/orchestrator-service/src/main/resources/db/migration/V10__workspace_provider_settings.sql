-- Provider and model state that belongs to one workspace, not to the platform.
--
-- llm_providers and llm_models are the platform catalogue: every workspace reads the same rows.
-- Until now a workspace's toggle, a refused key and an out-of-credit account were all written back
-- to those shared rows, so one workspace could switch a provider off for every other workspace or
-- show them a "key refused" for a key they never stored. These two tables hold that state per
-- workspace instead, and no workspace action writes the shared rows any more.
--
-- No row here means "no opinion": the workspace gets the platform's default for that provider.

-- ---------------------------------------------------------------------------------------------
-- What the shared provider row now means
-- ---------------------------------------------------------------------------------------------
--
-- llm_providers.enabled used to be one switch that every workspace both read and wrote. It is
-- split in two, so that what a workspace sees before it has chosen anything stays exactly what it
-- saw before this migration:
--
--   enabled                    on a platform-wide row (org_id NULL): whether the provider is
--                              offered to workspaces at all. Every seeded row is offered; switching
--                              one off withdraws it from every workspace, which is an operator's
--                              job done in the database, never through a workspace endpoint.
--                              On a workspace's own row: whether that workspace has it on.
--   workspace_default_enabled  whether a workspace that has made no choice has it on. Seeded from
--                              the old switch, so a fresh install still starts with the sandbox
--                              alone, and a workspace turns a real provider on by storing its key
--                              and pressing "Turn on", exactly as before.
--
-- Offering every seeded provider spends nothing: the router only tries providers in the
-- workspace's own routing policy, and skips any for which the workspace has stored no key.

ALTER TABLE llm_providers ADD COLUMN workspace_default_enabled BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE llm_providers SET workspace_default_enabled = enabled;
-- The sandbox is the built-in fallback every new workspace's FAIL_CLOSED policy names, so its
-- seeded default is restored even if a tenant switched it off for everyone before this fix.
UPDATE llm_providers SET workspace_default_enabled = TRUE WHERE id = 'sandbox' AND org_id IS NULL;
UPDATE llm_providers SET enabled = TRUE WHERE org_id IS NULL;

COMMENT ON COLUMN llm_providers.enabled IS
    'Platform-wide row: offered to workspaces at all. A workspace''s own row: on for that workspace.';
COMMENT ON COLUMN llm_providers.workspace_default_enabled IS
    'Platform-wide row: on for a workspace that has made no choice in workspace_provider_settings.';

-- ---------------------------------------------------------------------------------------------
-- One workspace's choice and key state, per provider
-- ---------------------------------------------------------------------------------------------

CREATE TABLE workspace_provider_settings (
    org_id                UUID        NOT NULL,
    provider_id           TEXT        NOT NULL REFERENCES llm_providers (id) ON DELETE CASCADE,
    -- NULL takes the provider's workspace_default_enabled. Effective state is "offered" AND this,
    -- so a workspace can switch on or off any provider the platform offers, but cannot switch on
    -- one the platform has withdrawn.
    enabled               BOOLEAN,
    -- What the last call made with this workspace's key learned about it. Keys are stored per
    -- workspace (org-service), so whether one works is a fact about that workspace alone.
    credential_status     TEXT,
    credential_checked_at TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Who last turned the provider on or off for the workspace. A credential write leaves it be.
    updated_by            TEXT,
    PRIMARY KEY (org_id, provider_id),
    CONSTRAINT workspace_credential_status_valid CHECK (credential_status IN ('unknown', 'valid', 'rejected'))
);

-- ---------------------------------------------------------------------------------------------
-- Models one workspace cannot use for a while
-- ---------------------------------------------------------------------------------------------
--
-- Its account is out of credit or quota, or the provider told this workspace the model does not
-- exist. A 404 is how a retirement arrives, but also how "your account cannot use this model"
-- arrives, so one workspace's 404 is noted here first; only when a second workspace reports the
-- same model inside the cool-down is it copied to llm_models.unavailable_until for everybody.

CREATE TABLE workspace_model_availability (
    org_id            UUID        NOT NULL,
    provider_id       TEXT        NOT NULL,
    model_id          TEXT        NOT NULL,
    unavailable_until TIMESTAMPTZ NOT NULL,
    -- The ProviderFailure that set it aside: MODEL_NOT_FOUND, INSUFFICIENT_CREDIT or
    -- QUOTA_EXHAUSTED. What lets the platform tell two workspaces' 404s from anything else.
    cause             TEXT        NOT NULL,
    reason            TEXT,
    PRIMARY KEY (org_id, provider_id, model_id),
    FOREIGN KEY (provider_id, model_id) REFERENCES llm_models (provider_id, model_id) ON DELETE CASCADE
);

-- "Has another workspace reported this model missing just now?" is asked on every 404.
CREATE INDEX workspace_model_availability_model_idx
    ON workspace_model_availability (provider_id, model_id, cause, unavailable_until);

-- The shared credential columns stay for the seed and for history, but nothing reads or writes
-- them for a workspace any more.
COMMENT ON COLUMN llm_providers.credential_status IS
    'Not used. Credential status is per workspace: workspace_provider_settings.credential_status.';
COMMENT ON COLUMN llm_providers.credential_checked_at IS
    'Not used. See workspace_provider_settings.credential_checked_at.';
