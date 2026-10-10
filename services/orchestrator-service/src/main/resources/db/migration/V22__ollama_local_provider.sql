-- A local model server, Ollama, as a keyless OpenAI-compatible provider: the fallback a server
-- deployment appends last to every routing chain (AIWOS_LOCAL_FALLBACK=true), so runs still
-- answer when every cloud model fails.
--
-- credential_ref is NULL on purpose: the router and "Test now" call a provider whose row names no
-- credential without asking the key store (ProviderDescriptor.requiresCredential). The base URL
-- seeded here is the Docker Compose service name; ServerProviderSetup replaces it at start-up with
-- AIWOS_OLLAMA_BASE_URL when that is set.
--
-- Offered by the platform (enabled) but off in each workspace until a deployment that runs Ollama
-- says so (ServerProviderSetup turns workspace_default_enabled on when AIWOS_OLLAMA_BASE_URL or
-- AIWOS_LOCAL_FALLBACK is set). A local install without Ollama therefore sees no change in routing.

INSERT INTO llm_providers (id, display_name, kind, base_url, credential_ref, enabled, priority,
                           credential_status, workspace_default_enabled)
VALUES ('ollama', 'Ollama (local)', 'OPENAI_COMPATIBLE', 'http://ollama:11434/v1', NULL, TRUE, 95,
        'valid', FALSE)
ON CONFLICT (id) DO NOTHING;

-- The models the deployment guide offers. qwen2.5:1.5b-instruct is the default: tool-capable and
-- about 1 GB resident, so it fits beside eight JVMs, PostgreSQL, Redis and Qdrant on 8 GB.
-- llama3.2:3b is the larger alternative (about 2.5 GB). Context is the 8192 tokens the compose file
-- starts Ollama with (OLLAMA_CONTEXT_LENGTH). Free: the model runs on the server's own CPU.
INSERT INTO llm_models (provider_id, model_id, display_name, context_window, max_output_tokens,
                        supports_tools, supports_json_mode, supports_streaming, supports_vision,
                        input_cost_per_million, output_cost_per_million, enabled, free)
VALUES
    ('ollama', 'qwen2.5:1.5b-instruct', 'Qwen2.5 1.5B (local)', 8192, 2048, TRUE, TRUE, TRUE, FALSE, 0, 0, TRUE, TRUE),
    ('ollama', 'llama3.2:3b', 'Llama 3.2 3B (local)', 8192, 2048, TRUE, TRUE, TRUE, FALSE, 0, 0, TRUE, TRUE)
ON CONFLICT (provider_id, model_id) DO NOTHING;

-- Amazon Nova through the APAC cross-region inference profiles, which is how an account in
-- ap-southeast-2 (Sydney) reaches them on demand. Nova Lite is the server's default first
-- candidate (AIWOS_DEFAULT_ROUTING): first-party, so no AWS Marketplace subscription is needed,
-- tool-capable through Converse, and cheap. Prices are AWS's on-demand prices in US dollars per
-- million tokens; check the Bedrock pricing page for the current APAC figures.
INSERT INTO llm_models (provider_id, model_id, display_name, context_window, max_output_tokens,
                        supports_tools, supports_json_mode, supports_streaming, supports_vision,
                        input_cost_per_million, output_cost_per_million, enabled)
VALUES
    ('bedrock', 'apac.amazon.nova-micro-v1:0', 'Nova Micro (APAC cross-region)', 128000, 5000, TRUE, TRUE, FALSE, FALSE, 0.037, 0.148, TRUE),
    ('bedrock', 'apac.amazon.nova-lite-v1:0', 'Nova Lite (APAC cross-region)', 300000, 5000, TRUE, TRUE, FALSE, TRUE, 0.063, 0.252, TRUE),
    ('bedrock', 'apac.amazon.nova-pro-v1:0', 'Nova Pro (APAC cross-region)', 300000, 5000, TRUE, TRUE, FALSE, TRUE, 0.84, 3.36, TRUE)
ON CONFLICT (provider_id, model_id) DO NOTHING;
