-- "Send back with feedback": an approver rejects the action as written but does not end the run;
-- the agent is told why and may try again. Such a rejection is marked so the approvals history,
-- the run's trace and the two-rounds limit can tell it from a rejection that stops the work.

ALTER TABLE approvals ADD COLUMN sent_back BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX approvals_run_sent_back_idx ON approvals (run_id) WHERE sent_back;
