-- Models discovered from a provider's own model list.
--
-- The seeded rows are a short, hand-checked list. A provider offers far more than that, and the
-- routing policy editor now reads each provider's live list (GET /api/providers/{id}/models). A
-- model a workspace can choose there must also be one the policy validation and the router can
-- find, so every tool-capable model a listing returns is written here, beside the seeded rows.
--
--   source         'seed' for a row this repository's migrations wrote, 'discovered' for one a
--                  listing wrote. A listing only ever updates a discovered row: a seeded row's
--                  prices and limits are corrected by hand, never overwritten by a listing.
--   free           the provider charges nothing for the model (OpenRouter's ":free" variants and
--                  zero-priced models, NVIDIA NIM's free developer access).
--   discovered_at  when a listing last returned the model.
--
-- Only public catalogue models are written. Provider listings that can include an account's own
-- models (OpenAI fine-tunes) are filtered before anything is stored, because this table is shared
-- by every workspace.

ALTER TABLE llm_models ADD COLUMN source TEXT NOT NULL DEFAULT 'seed';
ALTER TABLE llm_models ADD COLUMN free BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE llm_models ADD COLUMN discovered_at TIMESTAMPTZ;

ALTER TABLE llm_models ADD CONSTRAINT llm_model_source_valid CHECK (source IN ('seed', 'discovered'));

-- NVIDIA's hosted NIM endpoints are free for development, within rate limits; the seeded prices
-- already say zero.
UPDATE llm_models SET free = TRUE WHERE provider_id IN ('nvidia', 'sandbox');

CREATE INDEX llm_models_discovered_idx ON llm_models (provider_id) WHERE source = 'discovered';
