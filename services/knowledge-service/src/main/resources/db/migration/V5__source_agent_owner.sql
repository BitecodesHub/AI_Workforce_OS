-- An AI employee's own documents.
--
-- A source with an agent_id belongs to that one agent: only that agent's searches read it, and it
-- is left out of the workspace's source list, its counts and every workspace-wide search. A source
-- with no agent_id is a workspace source, exactly as before.

ALTER TABLE sources ADD COLUMN agent_id UUID;

CREATE INDEX sources_agent_idx ON sources (org_id, agent_id) WHERE agent_id IS NOT NULL;
