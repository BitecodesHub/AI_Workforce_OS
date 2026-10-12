-- Team room: indexes for the hand-off conversation.
--
-- The team room's conversation is polled every three seconds by each person who has it open. It is
-- a newest-first page of events taken from timestamps on five tables (a goal asked for, a task
-- started and finished, a run started straight on an agent and finished, an approval asked for and
-- decided, a question asked and answered). Each is read as "this workspace's rows, newest first,
-- before or after a cursor, the first fifty", which only an index on (org_id, that timestamp)
-- answers without reading every row the workspace has ever written.
--
-- Already covered by earlier migrations, so not repeated here: tasks (org_id, completed_at) and
-- approvals (org_id, requested_at) and run_questions (org_id, created_at) from V12, and the active
-- runs the presence reads from runs_org_status_started_idx (V3).
--
-- Not yet covered, and added here:
--   - goals by (org_id, created_at): goals_org_status_idx leads with status;
--   - tasks by (org_id, started_at): no index on tasks covers started_at;
--   - approvals by (org_id, decided_at) and run_questions by (org_id, answered_at): the existing
--     indexes on those tables hold pending rows only, or order by when the row was created;
--   - runs started straight on an agent (task_id null) by (org_id, started_at) and by
--     (org_id, completed_at): runs_org_started_idx holds every run, nearly all of them for tasks,
--     and nothing indexes completed_at.
--
-- Flyway runs each migration inside a transaction, so none of these can be built CONCURRENTLY;
-- each is guarded with IF NOT EXISTS instead, as in V11.

CREATE INDEX IF NOT EXISTS goals_org_created_idx ON goals (org_id, created_at DESC);

CREATE INDEX IF NOT EXISTS tasks_org_started_idx ON tasks (org_id, started_at DESC)
    WHERE started_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS approvals_org_decided_idx ON approvals (org_id, decided_at DESC)
    WHERE decided_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS run_questions_org_answered_idx ON run_questions (org_id, answered_at DESC)
    WHERE answered_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS runs_direct_started_idx ON runs (org_id, started_at DESC)
    WHERE task_id IS NULL;

CREATE INDEX IF NOT EXISTS runs_direct_completed_idx ON runs (org_id, completed_at DESC)
    WHERE task_id IS NULL AND completed_at IS NOT NULL;
