-- Shared memory: what agents remember, and for how long.
--
-- Three tiers, because one store cannot serve three needs. Working memory is small, hot and
-- expendable, and lives in Redis rather than here. Episodic memory is the permanent record of
-- what happened, and is append-only. Semantic memory is a pointer into the knowledge base, so a
-- remembered fact stays attached to the document that supports it.

CREATE TABLE episodes (
    id          UUID PRIMARY KEY,
    org_id      UUID        NOT NULL,
    agent_id    UUID,
    run_id      UUID,
    task_id     UUID,
    kind        TEXT        NOT NULL,
    -- The searchable summary. Full detail lives in `detail`, so a list query does not drag
    -- megabytes of transcript through the database.
    summary     TEXT        NOT NULL,
    detail      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- Higher means keep longer under compaction. An approval decision is worth more than an
    -- intermediate tool result a month later.
    importance  INT         NOT NULL DEFAULT 5,
    -- Set when compaction folds several episodes into one, so the summary is traceable to the
    -- episodes it replaced rather than appearing from nowhere.
    supersedes  UUID[]      NOT NULL DEFAULT ARRAY[]::UUID[],
    compacted   BOOLEAN     NOT NULL DEFAULT FALSE,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT,
    CONSTRAINT episodes_kind_valid CHECK (kind IN
        ('observation', 'decision', 'handoff', 'outcome', 'preference', 'summary')),
    CONSTRAINT episodes_importance_range CHECK (importance BETWEEN 1 AND 10)
);

CREATE INDEX episodes_org_time_idx ON episodes (org_id, occurred_at DESC);
CREATE INDEX episodes_agent_idx ON episodes (agent_id, occurred_at DESC) WHERE agent_id IS NOT NULL;
CREATE INDEX episodes_run_idx ON episodes (run_id) WHERE run_id IS NOT NULL;
-- Full-text search over the summary, so an agent can ask "what do we know about this client"
-- without an embedding round trip for something this simple.
CREATE INDEX episodes_summary_search_idx ON episodes USING gin (to_tsvector('english', summary));
-- Used by the retention sweep.
CREATE INDEX episodes_expiry_idx ON episodes (expires_at) WHERE expires_at IS NOT NULL;

-- Never updated or deleted by the application: an episode is a record of something that happened,
-- and editing it would make the memory a record of something that did not.
REVOKE UPDATE ON episodes FROM PUBLIC;

CREATE TABLE semantic_pointers (
    id           UUID PRIMARY KEY,
    org_id       UUID        NOT NULL,
    agent_id     UUID,
    fact         TEXT        NOT NULL,
    -- What supports the fact. A remembered claim with no source is a rumour, and an agent that
    -- repeats one confidently is worse than one that says it does not know.
    source_type  TEXT        NOT NULL,
    source_id    TEXT        NOT NULL,
    chunk_id     TEXT,
    confidence   NUMERIC(3, 2) NOT NULL DEFAULT 0.50,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   TEXT,
    updated_by   TEXT,
    CONSTRAINT semantic_confidence_range CHECK (confidence BETWEEN 0 AND 1)
);

CREATE INDEX semantic_org_idx ON semantic_pointers (org_id);
CREATE UNIQUE INDEX semantic_fact_unique ON semantic_pointers (org_id, md5(fact), source_id);

CREATE TABLE runtime_settings (
    key        TEXT        NOT NULL,
    org_id     UUID,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);

CREATE UNIQUE INDEX mem_runtime_settings_platform_unique ON runtime_settings (key) WHERE org_id IS NULL;
CREATE UNIQUE INDEX mem_runtime_settings_org_unique ON runtime_settings (key, org_id) WHERE org_id IS NOT NULL;
