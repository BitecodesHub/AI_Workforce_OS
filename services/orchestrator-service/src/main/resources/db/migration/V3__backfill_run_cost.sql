-- Run cost, recorded honestly.
--
-- Until this release every run stored a total cost of zero, although the router has always
-- written the real price of each attempt to llm_usage with the run it belonged to. The runner now
-- keeps runs.total_cost equal to that sum as it goes; this brings the runs recorded before the
-- change into line, so a historical run does not claim to have been free.
--
-- Failed attempts count, as they do in the runner: a provider bills for a call that timed out
-- after generating most of an answer. Usage rows whose run was never saved (the run's own
-- transaction rolled back) match nothing here and are left as they are.

UPDATE runs r
SET total_cost = u.cost
FROM (
    SELECT run_id, SUM(cost) AS cost
    FROM llm_usage
    WHERE run_id IS NOT NULL
    GROUP BY run_id
) u
WHERE u.run_id = r.id
  AND r.total_cost <> u.cost;

-- The runner sums a run's usage after every model call, so that lookup must not scan the whole
-- table. Rows without a run are never looked up by it, so they are left out of the index.
CREATE INDEX llm_usage_run_idx ON llm_usage (run_id) WHERE run_id IS NOT NULL;

-- The Runs page filters by status across the whole workspace, newest first.
CREATE INDEX runs_org_status_started_idx ON runs (org_id, status, started_at DESC);

-- Goal cancellation, the task views and the goal sweep all look up a task's latest run, and
-- nothing indexed runs by task. Runs started directly rather than for a task are never looked up
-- that way, so they are left out.
CREATE INDEX runs_task_started_idx ON runs (task_id, started_at DESC) WHERE task_id IS NOT NULL;

-- A run a person stopped used to record its reason as "Cancelled by <account id>", and the trace
-- shows that reason as written. It now reads as a sentence; runs recorded before the change are
-- brought into line so no trace shows a bare identifier. Who stopped the run stays on the row
-- (updated_by).
UPDATE runs
SET failure_reason = 'A person stopped this run before it finished.'
WHERE status = 'cancelled'
  AND failure_reason LIKE 'Cancelled by %';
