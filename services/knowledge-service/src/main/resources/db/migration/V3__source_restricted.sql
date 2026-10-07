-- Restricted sources: an interim access control until per-role and per-person source access lands.
--
-- Without it every person who may search can read every passage in the workspace, so a manager
-- who uploads salary bands or an HR file hands them to anyone with Chat. A restricted source is
-- searchable, listed and counted only for holders of knowledge:source_manage; to everyone else it
-- does not exist. Existing sources stay workspace-wide, so nothing that works today stops working.

ALTER TABLE sources ADD COLUMN restricted BOOLEAN NOT NULL DEFAULT false;
