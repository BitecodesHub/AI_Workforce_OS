-- A note somebody pinned is always read back to its agent, ahead of whatever else bears on the
-- request, and is listed first on the agent's page. Pinning is a person's choice; an agent never
-- pins its own notes.
ALTER TABLE agent_memories ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE agent_memories ADD COLUMN pinned_at TIMESTAMPTZ;

CREATE INDEX agent_memories_pinned_idx ON agent_memories (org_id, agent_id) WHERE pinned;
