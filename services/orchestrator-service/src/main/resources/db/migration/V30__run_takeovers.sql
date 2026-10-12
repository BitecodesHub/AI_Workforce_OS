-- Take over: a person taking a run's web browser from its agent, from the Agent desk on the Agents
-- page. One row per run, reused when the run is taken over again. A request starts as 'starting'
-- while the agent finishes the step it is on; once the agent has stopped, the row is 'active' and
-- the agent waits. Control comes back when the person hands it back ('released'), automatically
-- after 30 minutes ('expired'), or because the run ended or stopped to wait for a person ('ended').
CREATE TABLE run_takeovers (
    run_id           UUID        PRIMARY KEY REFERENCES runs (id) ON DELETE CASCADE,
    org_id           UUID        NOT NULL,
    -- The person holding control (their user id), and when they asked for it or, once active,
    -- when they got it.
    taken_by         TEXT        NOT NULL,
    taken_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at      TIMESTAMPTZ,
    released_by      TEXT,
    -- What the person did, as counts by action type, the pages they visited and the labels of the
    -- fields they typed into. Never the typed text.
    actions          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- The person's optional words for the agent, kept only until the agent has been told.
    note             TEXT,
    status           TEXT        NOT NULL DEFAULT 'starting',
    -- When the agent was told what the person did; null until then.
    delivered_at     TIMESTAMPTZ,
    -- Steps given back to the run because a takeover made a turn pointless, kept here so a run
    -- that parks and resumes keeps them.
    steps_given_back INTEGER     NOT NULL DEFAULT 0,
    version          BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT run_takeovers_status_valid CHECK (status IN ('starting', 'active', 'released', 'expired', 'ended')),
    CONSTRAINT run_takeovers_steps_given_back_valid CHECK (steps_given_back >= 0)
);
