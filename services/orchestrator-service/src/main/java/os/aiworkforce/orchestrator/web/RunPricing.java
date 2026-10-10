// @find: run pricing, run cost label, free run, cost display, price of run, calls included
// @what: Turns a run's cost into the label shown for it.
// @flow: Used by RunController views
package os.aiworkforce.orchestrator.web;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What a run's cost figure means, so every page says the same thing about it.
 *
 * <ul>
 *   <li>{@code priced}: it has a cost from catalogue prices.
 *   <li>{@code free}: it cost nothing because every model it used is free in the catalogue.
 *   <li>{@code sandbox}: the offline sandbox answered every call; it costs nothing.
 *   <li>{@code unpriced}: a real model answered and the catalogue has no price for it, so the cost
 *       is not known - never shown as zero.
 *   <li>{@code none}: no model has answered yet.
 * </ul>
 */
@Component
public class RunPricing {

    public static final String PRICED = "priced";
    public static final String FREE = "free";
    public static final String SANDBOX = "sandbox";
    public static final String UNPRICED = "unpriced";
    public static final String NONE = "none";

    private static final String CALLS =
            """
            select u.run_id,
                   bool_and(u.provider_id = 'sandbox') as all_sandbox,
                   bool_and(u.provider_id = 'sandbox' or coalesce(m.free, false)) as all_free
            from llm_usage u
            left join llm_models m on m.provider_id = u.provider_id and m.model_id = u.model_id
            where u.org_id = :orgId and u.run_id in (:runIds) and u.outcome = 'SUCCEEDED'
            group by u.run_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RunPricing(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The successful calls of each run, as {all sandbox, all free}; a run with none is absent. */
    public Map<UUID, boolean[]> calls(UUID orgId, Collection<UUID> runIds) {
        Map<UUID, boolean[]> out = new HashMap<>();
        if (runIds.isEmpty()) {
            return out;
        }
        jdbc.query(CALLS, Map.of("orgId", orgId, "runIds", runIds), rs -> {
            out.put(
                    rs.getObject("run_id", UUID.class),
                    new boolean[] {rs.getBoolean("all_sandbox"), rs.getBoolean("all_free")});
        });
        return out;
    }

    // @find: run price label
    /** The pricing word for a run of this cost whose calls were {@code calls} (null for none). */
    public static String of(BigDecimal cost, boolean[] calls) {
        if (cost != null && cost.signum() > 0) {
            return PRICED;
        }
        if (calls == null) {
            return NONE;
        }
        if (calls[0]) {
            return SANDBOX;
        }
        return calls[1] ? FREE : UNPRICED;
    }
}
