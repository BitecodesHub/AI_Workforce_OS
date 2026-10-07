-- Columns for the approval audit fields added to Approval (action method, requester and reason).
--
-- The entity gained these fields without a migration, so schema validation refused to start the
-- service. All three are nullable: approvals raised by a run do not set them.

ALTER TABLE approvals ADD COLUMN IF NOT EXISTS action_method text;
ALTER TABLE approvals ADD COLUMN IF NOT EXISTS requested_by uuid;
ALTER TABLE approvals ADD COLUMN IF NOT EXISTS reason text;
