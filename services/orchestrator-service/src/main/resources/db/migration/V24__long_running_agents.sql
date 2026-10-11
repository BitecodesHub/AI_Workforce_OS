-- Long-running agents: a per-revision limit on how long one run may keep working, in minutes.
-- Null means the platform default (aiwos.runs.max-duration). The step limit's ceiling also rises
-- from 50 to 200 (enforced by the API), so a long task is bounded by time as well as by steps.
ALTER TABLE agent_versions ADD COLUMN max_run_minutes INT;
ALTER TABLE agent_versions
    ADD CONSTRAINT agent_versions_max_run_minutes_range
    CHECK (max_run_minutes IS NULL OR (max_run_minutes BETWEEN 1 AND 120));
