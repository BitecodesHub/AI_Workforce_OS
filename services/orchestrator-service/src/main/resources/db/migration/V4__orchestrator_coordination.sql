-- Coordination groundwork for chat, schedules and voice.
--
-- This migration lands ahead of the services that actually use most of it. Landing it first, on
-- its own, is what lets the chat, schedule and voice engines build against a schema that already
-- has the columns their code refers to, each in its own migration, without waiting on one another
-- or renumbering anything.
--
-- What is here and why:
--   - goals gets where the work came from (a person typing, a chat, a schedule) and what it is
--     linked to, so the board and the chat and schedules pages can each find their own goals.
--   - An index on (org_id, status) for goals and for tasks: the orchestrator board's stats and the
--     goal sweep both filter on exactly that pair, and the existing partial indexes do not cover
--     every status a dashboard needs to count.
--   - approvals gets the tool call id the approval answers, so a resumed run can invoke the exact
--     call that was approved rather than re-deriving it from a summary.
--   - agents gets its voice, ahead of the voice service's own migration (V7): both would otherwise
--     want to add the same column, and only one migration may.

ALTER TABLE goals
    ADD COLUMN source          TEXT NOT NULL DEFAULT 'manual',
    ADD COLUMN conversation_id UUID NULL,
    ADD COLUMN schedule_id     UUID NULL;

ALTER TABLE goals
    ADD CONSTRAINT goals_source_valid CHECK (source IN ('manual', 'chat', 'schedule'));

CREATE INDEX goals_org_status_lookup_idx ON goals (org_id, status);

CREATE INDEX tasks_org_status_idx ON tasks (org_id, status);

ALTER TABLE approvals
    ADD COLUMN tool_call_id TEXT NULL;

-- Added here, ahead of the voice service's own V7, so this table has exactly one column named
-- voice_id rather than two migrations racing to add it.
ALTER TABLE agents
    ADD COLUMN voice_id TEXT NULL;
