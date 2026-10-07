-- Whether a connector was live (a real account connected) when a run started or an approval was
-- raised, so a connection that is disconnected or needs reconnecting by the time a call executes
-- fails plainly instead of quietly answering from practice data.
--
-- runs.live_servers is a comma-separated list of the servers that were live at the start of the
-- run; null means the snapshot has not been taken yet (runs started before this migration never
-- get one, which keeps the old behaviour for them). approvals.mode is where the approved call was
-- going when it was raised: live or sandbox. approvals.outcome is what happened when it was
-- carried out, in a sentence, so the approval itself shows the result.

ALTER TABLE runs ADD COLUMN live_servers TEXT;
ALTER TABLE approvals ADD COLUMN mode TEXT;
ALTER TABLE approvals ADD COLUMN outcome TEXT;
