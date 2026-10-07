-- What each AI employee remembers, as short notes people can read and curate.
--
-- Episodes (V1) are the record of what happened and are never edited. These are different: a
-- note an agent chose to keep ("the Brisbane clinic closes at 4pm on Fridays"), or one a person
-- wrote for it, that is recalled at the start of that agent's next runs and that people can
-- correct or delete. It belongs to one agent and is never read by another.

CREATE TABLE agent_memories (
    id               UUID PRIMARY KEY,
    org_id           UUID        NOT NULL,
    agent_id         UUID        NOT NULL,
    kind             TEXT        NOT NULL DEFAULT 'fact',
    content          TEXT        NOT NULL,
    -- Who wrote it: the agent, while working, or a person from the agent's page.
    source           TEXT        NOT NULL DEFAULT 'agent',
    run_id           UUID,
    recall_count     INT         NOT NULL DEFAULT 0,
    last_recalled_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       TEXT,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       TEXT,
    CONSTRAINT agent_memories_kind_valid CHECK (kind IN ('fact', 'preference', 'instruction', 'note')),
    CONSTRAINT agent_memories_source_valid CHECK (source IN ('agent', 'person')),
    CONSTRAINT agent_memories_content_length CHECK (char_length(content) BETWEEN 1 AND 1000)
);

CREATE INDEX agent_memories_agent_idx ON agent_memories (org_id, agent_id, updated_at DESC);
CREATE INDEX agent_memories_search_idx ON agent_memories USING gin (to_tsvector('english', content));
