-- The seven providers, plus the offline sandbox.
--
-- Every one is seeded disabled except the sandbox, and none carries a credential. That is what
-- lets a clone of this repository start and run: the router finds the sandbox, uses it, and says
-- so in the interface. Enabling a real provider is toggling a row and storing a key.
--
-- Base URLs are rows because they change: a proxy, a private endpoint, a region-specific host or
-- a self-hosted server that speaks the same protocol all work without a code change.

INSERT INTO llm_providers (id, display_name, kind, base_url, credential_ref, enabled, priority, credential_status)
VALUES
    ('sandbox',    'Offline sandbox', 'SANDBOX',           '',                                          NULL,                 TRUE,  99, 'valid'),
    ('openrouter', 'OpenRouter',      'OPENAI_COMPATIBLE', 'https://openrouter.ai/api/v1',              'provider:openrouter', FALSE, 10, 'missing'),
    ('groq',       'Groq',            'OPENAI_COMPATIBLE', 'https://api.groq.com/openai/v1',            'provider:groq',       FALSE,  5, 'missing'),
    ('nvidia',     'NVIDIA NIM',      'OPENAI_COMPATIBLE', 'https://integrate.api.nvidia.com/v1',       'provider:nvidia',     FALSE, 20, 'missing'),
    ('openai',     'OpenAI',          'OPENAI_COMPATIBLE', 'https://api.openai.com/v1',                 'provider:openai',     FALSE, 30, 'missing'),
    ('anthropic',  'Anthropic',       'ANTHROPIC',         'https://api.anthropic.com',                 'provider:anthropic',  FALSE, 15, 'missing'),
    ('gemini',     'Google Gemini',   'GEMINI',            'https://generativelanguage.googleapis.com', 'provider:gemini',     FALSE, 25, 'missing'),
    ('bedrock',    'AWS Bedrock',     'BEDROCK',           '',                                          'provider:bedrock',    FALSE, 35, 'missing');

-- OpenRouter attributes traffic through these headers; sending them is a courtesy the service
-- asks for and costs nothing.
UPDATE llm_providers
SET default_headers = '{"HTTP-Referer":"https://aiworkforce.os","X-Title":"AI Workforce OS"}'::jsonb
WHERE id = 'openrouter';

-- Bedrock is the one regional provider. The order is the fallback order: a model unavailable in
-- the first region is tried in the next before the router is told the provider failed at all.
UPDATE llm_providers
SET regions = ARRAY['us-east-1', 'us-west-2', 'ap-southeast-2', 'eu-central-1']
WHERE id = 'bedrock';

-- ---------------------------------------------------------------------------------------------
-- Models
-- ---------------------------------------------------------------------------------------------
--
-- Capabilities and window sizes are recorded because the router uses them to disqualify a
-- candidate before spending a request. Prices are per million tokens in USD and are the figures
-- to correct first when a vendor changes them - which is a row, not a release.

INSERT INTO llm_models (provider_id, model_id, display_name, context_window, max_output_tokens,
                        supports_tools, supports_json_mode, supports_streaming, supports_vision,
                        input_cost_per_million, output_cost_per_million, enabled)
VALUES
    -- The offline model. Deliberately generous, so it is never the candidate skipped for being
    -- too small when it is the only one left.
    ('sandbox', 'sandbox-1', 'Offline sandbox', 128000, 4096, TRUE, TRUE, TRUE, FALSE, 0, 0, TRUE),

    ('groq', 'llama-3.3-70b-versatile', 'Llama 3.3 70B', 128000, 32768, TRUE, TRUE, TRUE, FALSE, 0.59, 0.79, TRUE),
    ('groq', 'llama-3.1-8b-instant', 'Llama 3.1 8B', 128000, 8192, TRUE, TRUE, TRUE, FALSE, 0.05, 0.08, TRUE),

    ('openrouter', 'anthropic/claude-sonnet-4', 'Claude Sonnet 4', 200000, 64000, TRUE, TRUE, TRUE, TRUE, 3.00, 15.00, TRUE),
    ('openrouter', 'google/gemini-2.5-flash', 'Gemini 2.5 Flash', 1048576, 65536, TRUE, TRUE, TRUE, TRUE, 0.30, 2.50, TRUE),
    ('openrouter', 'meta-llama/llama-3.3-70b-instruct', 'Llama 3.3 70B', 131072, 16384, TRUE, TRUE, TRUE, FALSE, 0.12, 0.30, TRUE),

    ('nvidia', 'meta/llama-3.3-70b-instruct', 'Llama 3.3 70B', 128000, 4096, TRUE, TRUE, TRUE, FALSE, 0.00, 0.00, TRUE),
    ('nvidia', 'nvidia/llama-3.1-nemotron-70b-instruct', 'Nemotron 70B', 128000, 4096, TRUE, FALSE, TRUE, FALSE, 0.00, 0.00, TRUE),

    ('openai', 'gpt-4.1', 'GPT-4.1', 1047576, 32768, TRUE, TRUE, TRUE, TRUE, 2.00, 8.00, TRUE),
    ('openai', 'gpt-4.1-mini', 'GPT-4.1 mini', 1047576, 32768, TRUE, TRUE, TRUE, TRUE, 0.40, 1.60, TRUE),

    ('anthropic', 'claude-sonnet-4-20250514', 'Claude Sonnet 4', 200000, 64000, TRUE, TRUE, TRUE, TRUE, 3.00, 15.00, TRUE),
    ('anthropic', 'claude-haiku-4-5-20251001', 'Claude Haiku 4.5', 200000, 64000, TRUE, TRUE, TRUE, TRUE, 1.00, 5.00, TRUE),

    ('gemini', 'gemini-2.5-flash', 'Gemini 2.5 Flash', 1048576, 65536, TRUE, TRUE, TRUE, TRUE, 0.30, 2.50, TRUE),
    ('gemini', 'gemini-2.5-pro', 'Gemini 2.5 Pro', 1048576, 65536, TRUE, TRUE, TRUE, TRUE, 1.25, 10.00, TRUE),

    ('bedrock', 'anthropic.claude-sonnet-4-20250514-v1:0', 'Claude Sonnet 4 on Bedrock', 200000, 64000, TRUE, TRUE, FALSE, TRUE, 3.00, 15.00, TRUE),
    ('bedrock', 'meta.llama3-3-70b-instruct-v1:0', 'Llama 3.3 70B on Bedrock', 128000, 8192, TRUE, FALSE, FALSE, FALSE, 0.72, 0.72, TRUE);

-- Embeddings, used by the knowledge base. The sandbox produces deterministic vectors of the same
-- dimension, so the ingestion pipeline is exercised in full without a credential.
INSERT INTO llm_models (provider_id, model_id, display_name, context_window, max_output_tokens,
                        supports_tools, supports_json_mode, supports_streaming, supports_vision,
                        input_cost_per_million, output_cost_per_million, enabled)
VALUES
    ('sandbox', 'sandbox-embed-1', 'Offline embeddings', 8192, 1, FALSE, FALSE, FALSE, FALSE, 0, 0, TRUE),
    ('openai', 'text-embedding-3-small', 'OpenAI embeddings (small)', 8191, 1, FALSE, FALSE, FALSE, FALSE, 0.02, 0, TRUE),
    ('gemini', 'text-embedding-004', 'Gemini embeddings', 2048, 1, FALSE, FALSE, FALSE, FALSE, 0.00, 0, TRUE);

-- Bedrock streaming uses a different response handler and gives the router nothing it relies on,
-- so those models declare themselves non-streaming rather than pretending otherwise. The router
-- respects the declaration and skips them for a streamed request.
