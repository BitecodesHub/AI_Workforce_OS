-- Indexes for the orchestrator's hot lookups that nothing indexed.
--
-- Flyway runs each migration inside a transaction, so none of these can be built CONCURRENTLY; each
-- is guarded with IF NOT EXISTS instead. runs (org_id, started_at DESC) already exists as
-- runs_org_started_idx (V1) and is not repeated here.

-- Runs parked for an approval, for the board and the resume sweep. Mirrors runs_waiting_input_idx
-- (V8): a run leaves waiting_approval when it resumes, so the status itself says it is parked.
CREATE INDEX IF NOT EXISTS runs_waiting_approval_idx ON runs (org_id) WHERE status = 'waiting_approval';

-- Withdrawing a run's pending approvals (cancel, stop all) and the newest approval of a run. The
-- ON DELETE CASCADE from runs also scans approvals by run_id, so any later purge needs it too.
CREATE INDEX IF NOT EXISTS approvals_run_requested_idx ON approvals (run_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS approvals_task_idx ON approvals (task_id) WHERE task_id IS NOT NULL;

-- Questions are looked up by the task or goal they belong to when a goal is cancelled or shown.
CREATE INDEX IF NOT EXISTS run_questions_task_idx ON run_questions (task_id) WHERE task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS run_questions_goal_idx ON run_questions (goal_id) WHERE goal_id IS NOT NULL;

-- tasks.agent_id is ON DELETE SET NULL, so removing an agent scans tasks; the agent views filter
-- on it too.
CREATE INDEX IF NOT EXISTS tasks_agent_idx ON tasks (agent_id) WHERE agent_id IS NOT NULL;

-- Spend per agent over a window.
CREATE INDEX IF NOT EXISTS llm_usage_org_agent_time_idx ON llm_usage (org_id, agent_id, occurred_at);

-- A conversation's goals: the incremental conversation read asks for the ones that changed since a
-- moment, and Goals.detachConversation and the active-goal lookup filter on the conversation too.
-- Most goals belong to no conversation, so they are left out of the index.
CREATE INDEX IF NOT EXISTS goals_conversation_idx ON goals (conversation_id, updated_at DESC)
    WHERE conversation_id IS NOT NULL;
