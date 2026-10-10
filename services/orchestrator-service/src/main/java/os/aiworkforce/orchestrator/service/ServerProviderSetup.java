// @find: server provider setup, startup, Ollama base url, AIWOS_OLLAMA_BASE_URL, enable Bedrock instance role, AIWOS_BEDROCK_USE_DEFAULT_CREDENTIALS, local fallback model row, ServerProviderSetup
// @what: At start-up, points the Ollama row at AIWOS_OLLAMA_BASE_URL and turns on, for workspaces that have made no choice, the providers this deployment reaches without stored keys.
// @flow: Runs once on ApplicationReadyEvent, after Flyway; reads ServerRouting and the environment.
package os.aiworkforce.orchestrator.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import os.aiworkforce.llm.bedrock.AwsDefaultCredentials;
import os.aiworkforce.llm.router.RoutingPolicy;

/**
 * Applies what a server deployment says about its providers in its environment, so a fresh server
 * routes with no clicks in the console:
 *
 * <ul>
 *   <li>the platform's Ollama row gets the base URL from {@code AIWOS_OLLAMA_BASE_URL}, and is on
 *       by default in every workspace when that or {@code AIWOS_LOCAL_FALLBACK} is set;
 *   <li>the model named by {@code AIWOS_OLLAMA_MODEL} gets a catalogue row if it has none, so the
 *       appended fallback candidate validates and routes;
 *   <li>Bedrock is on by default in every workspace when {@code AIWOS_BEDROCK_USE_DEFAULT_CREDENTIALS}
 *       is true, because the server then reaches it with its own instance role.
 * </ul>
 *
 * <p>"On by default" is the provider's {@code workspace_default_enabled}: a workspace that has
 * turned a provider off keeps it off. Only platform-wide rows are touched, and nothing is
 * written when none of the variables is set.
 */
@Component
public class ServerProviderSetup {

    private static final Logger log = LoggerFactory.getLogger(ServerProviderSetup.class);

    private final JdbcTemplate jdbc;
    private final ServerRouting routing;
    private final Map<String, String> environment;

    @org.springframework.beans.factory.annotation.Autowired
    public ServerProviderSetup(JdbcTemplate jdbc, ServerRouting routing) {
        this(jdbc, routing, System.getenv());
    }

    ServerProviderSetup(JdbcTemplate jdbc, ServerRouting routing, Map<String, String> environment) {
        this.jdbc = jdbc;
        this.routing = routing;
        this.environment = environment;
    }

    // @find: apply server provider settings at startup
    @EventListener(ApplicationReadyEvent.class)
    public void apply() {
        try {
            applyNow();
        } catch (RuntimeException e) {
            // A start-up nicety, never a reason to refuse to start: the console can do the same.
            log.warn("Could not apply the server's provider settings: {}", e.getMessage());
        }
    }

    void applyNow() {
        boolean ollama = !routing.ollamaBaseUrl().isEmpty() || routing.localFallback();
        if (!routing.ollamaBaseUrl().isEmpty()) {
            jdbc.update(
                    "UPDATE llm_providers SET base_url = ?, version = version + 1, updated_at = now()"
                            + " WHERE id = ? AND org_id IS NULL AND base_url <> ?",
                    routing.ollamaBaseUrl(),
                    ServerRouting.OLLAMA_PROVIDER,
                    routing.ollamaBaseUrl());
        }
        if (ollama) {
            onByDefault(ServerRouting.OLLAMA_PROVIDER);
            int added = jdbc.update(
                    "INSERT INTO llm_models (provider_id, model_id, display_name, context_window, max_output_tokens,"
                            + " supports_tools, supports_json_mode, supports_streaming, supports_vision,"
                            + " input_cost_per_million, output_cost_per_million, enabled, free)"
                            + " VALUES (?, ?, ?, 8192, 2048, TRUE, TRUE, TRUE, FALSE, 0, 0, TRUE, TRUE)"
                            + " ON CONFLICT (provider_id, model_id) DO NOTHING",
                    ServerRouting.OLLAMA_PROVIDER,
                    routing.ollamaModel(),
                    routing.ollamaModel() + " (local)");
            if (added > 0) {
                log.info("Added the local model {} to the catalogue", routing.ollamaModel());
            }
            log.info(
                    "Local model fallback {}: {} at {}",
                    routing.localFallback() ? "on" : "off",
                    routing.ollamaModel(),
                    routing.ollamaBaseUrl().isEmpty() ? "the seeded address" : routing.ollamaBaseUrl());
        }
        if (AwsDefaultCredentials.enabled(environment)) {
            onByDefault("bedrock");
            log.info(
                    "Bedrock uses this server's AWS role where a workspace stores no key, in region {}",
                    AwsDefaultCredentials.region(environment));
        }
        for (RoutingPolicy.Candidate candidate : routing.defaultChain()) {
            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM llm_models WHERE provider_id = ? AND model_id = ? AND enabled",
                    Integer.class,
                    candidate.providerId(),
                    candidate.modelId());
            if (rows == null || rows == 0) {
                log.warn(
                        "AIWOS_DEFAULT_ROUTING names {}, which is not in the model catalogue; runs will skip it",
                        candidate.key());
            }
        }
    }

    private void onByDefault(String providerId) {
        jdbc.update(
                "UPDATE llm_providers SET workspace_default_enabled = TRUE, enabled = TRUE, version = version + 1,"
                        + " updated_at = now() WHERE id = ? AND org_id IS NULL"
                        + " AND (workspace_default_enabled = FALSE OR enabled = FALSE)",
                providerId);
    }
}
