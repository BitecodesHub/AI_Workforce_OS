-- Schedules: recurring and one-off work a person sets up ahead of time.
--
-- A schedule is not a goal. It is the standing instruction that creates one, over and over for a
-- recurring schedule or once for a one-off, and it carries its own health: how many times in a
-- row its last few firings failed, and whether it has paused itself over that. next_run_at is a
-- plain column rather than something computed on read because the sweep needs to find due
-- schedules with one indexed comparison across every workspace, not a cron expression evaluated
-- row by row.

CREATE TABLE schedules (
    id                   UUID        PRIMARY KEY,
    org_id               UUID        NOT NULL,
    name                 TEXT        NOT NULL,
    agent_id             UUID        NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    instruction          TEXT        NOT NULL,
    kind                 TEXT        NOT NULL,
    cron                 TEXT,
    run_at               TIMESTAMPTZ,
    timezone             TEXT        NOT NULL,
    description          TEXT        NOT NULL,
    enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    overlap_policy       TEXT        NOT NULL DEFAULT 'skip',
    next_run_at          TIMESTAMPTZ,
    last_run_at          TIMESTAMPTZ,
    last_goal_id         UUID,
    -- A goal's own status string (planning, running, waiting, completed, failed, cancelled), not
    -- a status this table invents. No CHECK here on purpose: constraining it to today's goal
    -- statuses would couple this migration to goals_status_valid, so the day that CHECK grows a
    -- value this column would start rejecting writes rather than simply showing the new word.
    last_status          TEXT,
    consecutive_failures INT         NOT NULL DEFAULT 0,
    paused_reason        TEXT,
    -- Who this schedule fires as. A dedicated column, distinct from the audit created_by below:
    -- that one is never populated (no AuditorAware is registered in this service, the same as
    -- every other table here), while this is read back to attribute every goal the schedule
    -- creates and to decide who a resumed sweep should act as.
    requested_by         UUID,
    version              BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           TEXT,
    updated_by           TEXT,
    CONSTRAINT schedules_kind_valid CHECK (kind IN ('recurring', 'once')),
    CONSTRAINT schedules_overlap_valid CHECK (overlap_policy IN ('skip', 'queue'))
);

-- The sweep's one query: every workspace's due, enabled schedules, oldest-due first.
CREATE INDEX schedules_due_idx ON schedules (next_run_at) WHERE enabled;

CREATE INDEX schedules_org_idx ON schedules (org_id, name);

-- A schedule's own run history: the goals it created, newest first.
CREATE INDEX schedules_goal_lookup_idx ON goals (schedule_id, created_at DESC) WHERE schedule_id IS NOT NULL;
