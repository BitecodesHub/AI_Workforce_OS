-- Web browser: the screenshot the built-in browser took after each step of a run, shown in the
-- run's trace and as Chat's live view. One JPEG of about 1280x800 per browser tool call, kept
-- beside its run (like voice_clips) and removed with the run's detail by the retention purge.
CREATE TABLE browser_shots (
    id         UUID PRIMARY KEY,
    org_id     UUID        NOT NULL,
    run_id     UUID        NOT NULL,
    -- The tool call the screenshot belongs to, as recorded in the run step's detail.
    tool_call_id TEXT,
    url        TEXT        NOT NULL DEFAULT '',
    title      TEXT        NOT NULL DEFAULT '',
    jpeg       BYTEA       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX browser_shots_org_run_idx ON browser_shots (org_id, run_id);
CREATE INDEX browser_shots_created_idx ON browser_shots (org_id, created_at);
