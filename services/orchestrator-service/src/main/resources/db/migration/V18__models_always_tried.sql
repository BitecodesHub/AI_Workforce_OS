-- Models are always tried.
--
-- A failure used to set a model aside (a 402 for six hours, a 404 for fifteen minutes) and runs
-- then failed with "no model available" long after the account was topped up or the model
-- enabled. The router no longer writes or reads these notes; any left behind are cleared here so
-- nothing old lingers in the console either.

DELETE FROM workspace_model_availability;

UPDATE llm_models SET unavailable_until = NULL, unavailable_reason = NULL
WHERE unavailable_until IS NOT NULL OR unavailable_reason IS NOT NULL;
