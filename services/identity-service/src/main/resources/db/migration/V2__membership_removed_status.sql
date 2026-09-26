-- Removing a member sets its membership to 'removed' rather than deleting the row: this is an
-- audit-conscious platform, and hard-deleting a membership loses the historical fact that the
-- person was ever part of the workspace.
ALTER TABLE memberships DROP CONSTRAINT memberships_status_valid;
ALTER TABLE memberships ADD CONSTRAINT memberships_status_valid
    CHECK (status IN ('active', 'invited', 'suspended', 'removed'));
