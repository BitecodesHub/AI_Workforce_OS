package os.aiworkforce.orchestrator.catalog;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the tool-capable models a provider listed into {@code llm_models}, so the routing policy
 * validation and the router know every model a workspace can choose.
 *
 * <p>A seeded row is never changed: its prices and limits are corrected by hand. A discovered row
 * is refreshed with what the provider says now. A model that drops out of a listing is left in
 * place, so a policy that names it keeps validating; the router's "model not found" handling sets
 * it aside if the provider has really retired it.
 */
@Component
public class ModelCatalogStore {

    /** Used when a listing gives no window: large enough for agent work, small enough to be safe. */
    static final int DEFAULT_CONTEXT = 128_000;

    static final int DEFAULT_MAX_OUTPUT = 4_096;

    private static final String UPSERT = "INSERT INTO llm_models (provider_id, model_id, display_name, context_window,"
            + " max_output_tokens, supports_tools, supports_json_mode, supports_streaming, supports_vision,"
            + " input_cost_per_million, output_cost_per_million, enabled, source, free, discovered_at)"
            + " VALUES (?, ?, ?, ?, ?, TRUE, ?, TRUE, ?, ?, ?, TRUE, 'discovered', ?, ?)"
            + " ON CONFLICT (provider_id, model_id) DO UPDATE SET"
            + " display_name = EXCLUDED.display_name, context_window = EXCLUDED.context_window,"
            + " max_output_tokens = EXCLUDED.max_output_tokens, supports_json_mode = EXCLUDED.supports_json_mode,"
            + " supports_vision = EXCLUDED.supports_vision, input_cost_per_million = EXCLUDED.input_cost_per_million,"
            + " output_cost_per_million = EXCLUDED.output_cost_per_million, free = EXCLUDED.free,"
            + " discovered_at = EXCLUDED.discovered_at, updated_at = now()"
            + " WHERE llm_models.source = 'discovered'";

    private final JdbcTemplate jdbc;

    public ModelCatalogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Saves the tool-capable models of one listing. Returns how many were written. */
    @Transactional
    public int saveDiscovered(String providerId, List<CatalogModel> listed, Instant now) {
        List<CatalogModel> capable = listed.stream()
                .filter(CatalogModel::toolCalling)
                .filter(model -> model.id().length() <= 300)
                .toList();
        if (capable.isEmpty()) {
            return 0;
        }
        Timestamp at = Timestamp.from(now);
        jdbc.batchUpdate(UPSERT, capable, 200, (statement, model) -> {
            statement.setString(1, providerId);
            statement.setString(2, model.id());
            statement.setString(3, truncate(model.displayName(), 200));
            int context = model.contextLength() > 0 ? model.contextLength() : DEFAULT_CONTEXT;
            statement.setInt(4, context);
            int maxOut = model.maxOutputTokens() > 1 ? model.maxOutputTokens() : Math.min(DEFAULT_MAX_OUTPUT, context);
            statement.setInt(5, Math.max(2, maxOut));
            statement.setBoolean(6, model.jsonMode());
            statement.setBoolean(7, model.vision());
            statement.setBigDecimal(8, price(model.pricePerMTokIn()));
            statement.setBigDecimal(9, price(model.pricePerMTokOut()));
            statement.setBoolean(10, model.free());
            statement.setTimestamp(11, at);
        });
        return capable.size();
    }

    /** Unknown is stored as zero, the column's own default; the listing view says "price not listed". */
    private static BigDecimal price(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return BigDecimal.ZERO;
        }
        // NUMERIC(14, 8): six digits before the point.
        return value.min(BigDecimal.valueOf(999_999));
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
