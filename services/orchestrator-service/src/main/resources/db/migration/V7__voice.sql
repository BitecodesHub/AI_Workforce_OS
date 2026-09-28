-- Voice: ElevenLabs speech, transcription and per-agent voices, plus the clips a run produces.
--
-- agents.voice_id already exists - V4 added it ahead of this migration, precisely so the two
-- would not both try to add the same column. What is added here is the store for a spoken clip
-- and the grant that lets the two demo agents most likely to want one, HR and Customer Support,
-- call the voice tool at all.

CREATE TABLE voice_clips (
    id           UUID PRIMARY KEY,
    org_id       UUID        NOT NULL,
    -- Null only if a clip is ever made outside a run; every clip today comes from one.
    run_id       UUID,
    agent_id     UUID,
    text         TEXT        NOT NULL,
    voice_id     TEXT        NOT NULL,
    content_type TEXT        NOT NULL DEFAULT 'audio/mpeg',
    -- The audio itself, kept beside its own record rather than in an object store: a clip is
    -- small - a script capped at 2500 characters is at most a couple of minutes of speech - and
    -- this is the one place a run's trace needs to reach it from.
    audio        BYTEA       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX voice_clips_org_run_idx ON voice_clips (org_id, run_id);

-- DemoAgentSeeder grants the voice tool to hr and support the moment it creates them, so this
-- insert only matters for a demo workspace that was seeded before this migration existed. It is
-- a no-op everywhere else, including in every deployed workspace, which never runs the seeder.
INSERT INTO agent_tool_grants (
    id, org_id, agent_id, server, allowed_tools, scopes, require_approval, max_calls_per_run, enabled
)
SELECT gen_random_uuid(), a.org_id, a.id, 'voice', ARRAY['create_voice_note']::TEXT[], ARRAY[]::TEXT[],
       FALSE, 10, TRUE
FROM agents a
WHERE a.org_id = '00000000-0000-7000-8000-000000000001'
  AND a.key IN ('hr', 'support')
  AND NOT EXISTS (
      SELECT 1 FROM agent_tool_grants g WHERE g.agent_id = a.id AND g.server = 'voice'
  );
