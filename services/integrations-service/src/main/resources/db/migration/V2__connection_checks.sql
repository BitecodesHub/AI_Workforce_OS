-- When a stored token was last checked against its provider.
--
-- A connector whose last check failed shows "Needs attention" in the console; the time of that
-- check tells an administrator whether the failure is fresh or a leftover from last week.
ALTER TABLE connections ADD COLUMN last_checked_at TIMESTAMPTZ;
