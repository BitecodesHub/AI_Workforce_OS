// @find: knowledge base, knowledge, documents, sources, embedding settings, embedding model choice, which embedding model, search by meaning, keyword only, sandbox embeddings, runtime_settings, meaning floor, dimension, EmbeddingSettings
// @what: Stores and reads the workspace's chosen embedding model (or keyword-only) in runtime settings.
// @flow: Read by IngestionService.createSource and RetrievalService; changed by EmbeddingModelChange.
package os.aiworkforce.knowledge.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import os.aiworkforce.knowledge.domain.Source;

/**
 * Which embedding model a workspace's knowledge base searches by meaning with, and how far a
 * change of it has got.
 *
 * <p>Kept in the service's own {@code runtime_settings} table, one row per workspace and key, as
 * JSON. No row means the workspace never chose: its sources stay on the offline embeddings and are
 * searched by keyword only, which is what every workspace had before this setting existed.
 */
@Component
public class EmbeddingSettings {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingSettings.class);

    static final String MODEL_KEY = "embedding.model";
    static final String PROGRESS_KEY = "embedding.reindex";

    /**
     * A workspace's choice.
     *
     * @param provider the provider id, {@code sandbox} for keyword search only
     * @param model the model id
     * @param dimension how wide its vectors are, measured when it was chosen
     */
    public record Choice(String provider, String model, int dimension) {

        /** No model: the offline embeddings, whose vectors are never written or read. */
        public static final Choice KEYWORD_ONLY = new Choice(Source.SANDBOX_PROVIDER, "sandbox-embed-1", 1536);

        public boolean searchesByMeaning() {
            return !Source.SANDBOX_PROVIDER.equalsIgnoreCase(provider);
        }
    }

    /**
     * The similarity below which a passage found by meaning is noise, for a model that has not
     * been measured. Similarity scales differ widely between models, so a measured model has its own.
     */
    static final double DEFAULT_MEANING_FLOOR = 0.25;

    /**
     * Floors measured on the demo documents with the labelled questions in the orchestrator's
     * {@code relevance/labelled-set.json}. For nvidia/nemotron-3-embed-1b the passages that answer
     * a question, paraphrases included, scored 0.198 to 0.674, and off-topic questions and small
     * talk at most 0.162 (a connector action 0.181, a question about the assistant 0.185, both of
     * which are kept from documents before similarity is looked at).
     */
    private static final java.util.Map<String, Double> MEASURED_FLOORS =
            java.util.Map.of("nvidia/nemotron-3-embed-1b", 0.19);

    /** The floor a passage found by meaning with this model must reach to be returned at all. */
    public static double meaningFloor(String model) {
        return model == null ? DEFAULT_MEANING_FLOOR : MEASURED_FLOORS.getOrDefault(model, DEFAULT_MEANING_FLOOR);
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public EmbeddingSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // @find: current embedding model for workspace, which embedding model is used
    /** The workspace's choice, or keyword only when it never made one. */
    public Choice current(UUID orgId) {
        return read(orgId, MODEL_KEY, Choice.class).orElse(Choice.KEYWORD_ONLY);
    }

    // @find: save embedding model choice, change embedding model
    public void save(UUID orgId, Choice choice, String by) {
        write(orgId, MODEL_KEY, choice, by);
    }

    <T> Optional<T> read(UUID orgId, String key, Class<T> type) {
        List<String> values = jdbc.queryForList(
                "SELECT value FROM runtime_settings WHERE key = ? AND org_id = ?", String.class, key, orgId);
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(json.readValue(values.get(0), type));
        } catch (JsonProcessingException e) {
            log.warn("Unreadable setting {} for workspace {}; ignoring it", key, orgId);
            return Optional.empty();
        }
    }

    void write(UUID orgId, String key, Object value, String by) {
        String text;
        try {
            text = json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write setting " + key, e);
        }
        jdbc.update(
                "INSERT INTO runtime_settings (key, org_id, value, updated_at, updated_by) VALUES (?, ?, ?, now(), ?)"
                        + " ON CONFLICT (key, org_id) WHERE org_id IS NOT NULL"
                        + " DO UPDATE SET value = EXCLUDED.value, updated_at = now(), updated_by = EXCLUDED.updated_by",
                key,
                orgId,
                text,
                by);
    }
}
