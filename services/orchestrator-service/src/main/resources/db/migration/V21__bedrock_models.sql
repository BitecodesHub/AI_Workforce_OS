-- Amazon Bedrock, finished.
--
-- Bedrock's live model list (ListFoundationModels and ListInferenceProfiles for the region the
-- workspace chose) now fills the picker. These rows are what is shown when that list cannot be
-- read, and what the key check and "Test now" spend their one token on: the Nova models are
-- offered on demand in us-east-1 and us-west-2, and the newer Claude models only through an
-- inference profile, so the US profile ids are seeded for them.
--
-- Prices are AWS's on-demand prices for us-east-1, in US dollars per million tokens. Bedrock
-- streaming is not used (the adapter answers in one piece), so supports_streaming is FALSE.

INSERT INTO llm_models (provider_id, model_id, display_name, context_window, max_output_tokens,
                        supports_tools, supports_json_mode, supports_streaming, supports_vision,
                        input_cost_per_million, output_cost_per_million, enabled)
VALUES
    ('bedrock', 'amazon.nova-micro-v1:0', 'Nova Micro', 128000, 5000, TRUE, TRUE, FALSE, FALSE, 0.035, 0.14, TRUE),
    ('bedrock', 'amazon.nova-lite-v1:0', 'Nova Lite', 300000, 5000, TRUE, TRUE, FALSE, TRUE, 0.06, 0.24, TRUE),
    ('bedrock', 'amazon.nova-pro-v1:0', 'Nova Pro', 300000, 5000, TRUE, TRUE, FALSE, TRUE, 0.80, 3.20, TRUE),
    ('bedrock', 'us.anthropic.claude-haiku-4-5-20251001-v1:0', 'Claude Haiku 4.5 (US cross-region)',
        200000, 64000, TRUE, TRUE, FALSE, TRUE, 1.00, 5.00, TRUE),
    ('bedrock', 'us.anthropic.claude-sonnet-4-20250514-v1:0', 'Claude Sonnet 4 (US cross-region)',
        200000, 64000, TRUE, TRUE, FALSE, TRUE, 3.00, 15.00, TRUE)
ON CONFLICT (provider_id, model_id) DO NOTHING;

-- Embeddings for "Search by meaning", reached with InvokeModel. Stored like every embedding row:
-- no tools, one output token, so they stay out of chat routing.
INSERT INTO llm_models (provider_id, model_id, display_name, context_window, max_output_tokens,
                        supports_tools, supports_json_mode, supports_streaming, supports_vision,
                        input_cost_per_million, output_cost_per_million, enabled)
VALUES
    ('bedrock', 'amazon.titan-embed-text-v2:0', 'Titan Text Embeddings V2', 8192, 1, FALSE, FALSE, FALSE, FALSE, 0.02, 0, TRUE),
    ('bedrock', 'cohere.embed-multilingual-v3', 'Cohere Embed Multilingual', 512, 1, FALSE, FALSE, FALSE, FALSE, 0.10, 0, TRUE),
    ('bedrock', 'cohere.embed-english-v3', 'Cohere Embed English', 512, 1, FALSE, FALSE, FALSE, FALSE, 0.10, 0, TRUE)
ON CONFLICT (provider_id, model_id) DO NOTHING;

-- The two models seeded first are reached only through an inference profile in every region, so
-- their names say which id works.
UPDATE llm_models SET display_name = 'Claude Sonnet 4 (needs the us. profile id)'
WHERE provider_id = 'bedrock' AND model_id = 'anthropic.claude-sonnet-4-20250514-v1:0' AND source = 'seed';
UPDATE llm_models SET display_name = 'Llama 3.3 70B (needs the us. profile id)'
WHERE provider_id = 'bedrock' AND model_id = 'meta.llama3-3-70b-instruct-v1:0' AND source = 'seed';

COMMENT ON COLUMN llm_providers.regions IS
    'Ordered regions to try, for a regional provider. Bedrock uses the region stored with the workspace''s credentials; this list is only for an older credential that names none.';
