// @find: value settings, hours saved, cost per hour, ROI settings, value of work, /api/orchestrator/value-settings, Value settings page
// @what: REST endpoints to read and change the settings used to estimate the value of agent work.
// @flow: Used by the dashboard ROI figures
package os.aiworkforce.orchestrator.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.board.InsightsService;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.runtimeconfig.RuntimeConfigStore;

/**
 * The two inputs behind "hours returned" and "what it was worth": what an hour of a person's time
 * costs the workspace, and how long one of an agent's tasks would take a person.
 *
 * <p>Both are an administrator's own estimates, kept as runtime settings of the workspace
 * ({@value InsightsService#KEY_HOURLY_RATE} and {@value InsightsService#KEY_MINUTES_PREFIX}
 * followed by the agent's id) and read by {@link InsightsService}, which labels every figure it
 * derives from them an estimate. Anyone who can see Analytics may read them, since they are shown
 * beside the figures they produce; changing them needs {@code budget:manage}, the permission that
 * sets what the workforce may cost, because they decide what it is said to have saved.
 *
 * <p>The whole set is replaced on each save, as the budget is: an agent left out, or sent with no
 * minutes, loses its estimate and shows as "not estimated" again. A setting left behind by an
 * agent that has since been deleted is cleared at the same time.
 */
@RestController
@RequestMapping("/api/orchestrator/value-settings")
@Tag(name = "Value settings")
public class ValueSettingsController {

    /** Longer than a working day is not one task; the figure is for routine work. */
    static final int MAX_MINUTES = 2_400;

    /** Far above any loaded hourly cost, and well inside what a decimal can hold. */
    static final BigDecimal MAX_HOURLY_RATE = new BigDecimal("10000");

    /**
     * @param agentId the agent
     * @param name its name, for the form
     * @param minutesPerTask what one of its tasks would take a person; absent when not estimated
     */
    public record AgentMinutes(UUID agentId, String name, Integer minutesPerTask) {}

    /**
     * @param label what every figure derived from these is called, wherever it is shown
     * @param hourlyRate the loaded hourly staff cost in US dollars; absent when not entered
     * @param agents every agent of the workspace, with its estimate or none
     */
    public record ValueSettings(String label, BigDecimal hourlyRate, List<AgentMinutes> agents) {}

    /**
     * @param hourlyRate absent to remove the rate
     * @param minutesPerTask an estimate per agent id; an agent left out, or sent with no value, has
     *     none
     */
    public record ValueSettingsRequest(
            @DecimalMin("0") @DecimalMax("10000") BigDecimal hourlyRate, Map<UUID, Integer> minutesPerTask) {}

    private final RuntimeConfigStore store;
    private final InsightsService insights;
    private final Agents agents;
    private final AuditClient audit;

    public ValueSettingsController(
            RuntimeConfigStore store, InsightsService insights, Agents agents, AuditClient audit) {
        this.store = store;
        this.insights = insights;
        this.agents = agents;
        this.audit = audit;
    }

    // @find: get value settings, GET /api/orchestrator/value-settings
    @GetMapping
    @RequiresPermission(Permission.Codes.ANALYTICS_READ)
    @Transactional(readOnly = true)
    @Operation(summary = "The hourly staff cost and the minutes a person would spend per task, for each agent")
    public ValueSettings get() {
        UUID orgId = orgId();
        return view(orgId, insights.valueInputs(orgId));
    }

    // @find: update value settings, PUT /api/orchestrator/value-settings
    @PutMapping
    @RequiresPermission(Permission.Codes.BUDGET_MANAGE)
    @Transactional
    @Operation(summary = "Set the inputs the estimated value of the work is worked out from")
    public ValueSettings update(@Valid @RequestBody ValueSettingsRequest request) {
        UUID orgId = orgId();
        BigDecimal rate = rate(request.hourlyRate());
        Map<UUID, Integer> wanted = minutes(request.minutesPerTask());

        // Every agent named must be this workspace's: an id from another one would store a setting
        // that names a stranger's agent and tells the caller whether it exists.
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Agent agent : agents.findByOrgIdOrderByName(orgId)) {
            names.put(agent.getId(), agent.getName());
        }
        for (UUID agentId : wanted.keySet()) {
            if (!names.containsKey(agentId)) {
                throw ApiException.notFound("agent", agentId);
            }
        }

        InsightsService.ValueInputs before = insights.valueInputs(orgId);
        String actorId = RequestContext.actor().map(Actor::id).orElse(Actor.SYSTEM.id());
        String org = orgId.toString();

        if (rate == null) {
            store.clear(InsightsService.KEY_HOURLY_RATE, org, actorId);
        } else {
            store.write(InsightsService.KEY_HOURLY_RATE, org, rate.stripTrailingZeros().toPlainString(), actorId);
        }
        // Everything under the prefix that is not wanted goes, including what a deleted agent left.
        for (RuntimeConfigStore.StoredValue stored : store.readAll(org)) {
            String key = stored.key();
            if (key == null || !key.startsWith(InsightsService.KEY_MINUTES_PREFIX)) {
                continue;
            }
            UUID agentId = agentOf(key);
            if (agentId == null || !wanted.containsKey(agentId)) {
                store.clear(key, org, actorId);
            }
        }
        wanted.forEach((agentId, minutes) ->
                store.write(InsightsService.KEY_MINUTES_PREFIX + agentId, org, minutes.toString(), actorId));

        InsightsService.ValueInputs after = new InsightsService.ValueInputs(rate, wanted);
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("old", snapshot(before));
        detail.put("new", snapshot(after));
        LifecycleAnnouncer.afterCommit(() -> audit.record(
                orgId, actor, "value_settings.update", "workspace", org, "succeeded", detail));

        return view(orgId, after);
    }

    private ValueSettings view(UUID orgId, InsightsService.ValueInputs inputs) {
        List<AgentMinutes> rows = new ArrayList<>();
        for (Agent agent : agents.findByOrgIdOrderByName(orgId)) {
            rows.add(new AgentMinutes(agent.getId(), agent.getName(), inputs.minutesPerTask().get(agent.getId())));
        }
        return new ValueSettings(InsightsService.VALUE_LABEL, inputs.hourlyRate(), rows);
    }

    /** The rate to cents, or null for none. Zero is no rate: an hour that costs nothing saves nothing. */
    private static BigDecimal rate(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return null;
        }
        if (value.signum() < 0) {
            throw ApiException.validation("hourlyRate", "The hourly cost cannot be negative.");
        }
        if (value.compareTo(MAX_HOURLY_RATE) > 0) {
            throw ApiException.validation("hourlyRate", "The hourly cost is higher than can be entered.");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Whole minutes, 1 to a working day each; an entry with no value is dropped. */
    private static Map<UUID, Integer> minutes(Map<UUID, Integer> requested) {
        Map<UUID, Integer> kept = new HashMap<>();
        if (requested == null) {
            return kept;
        }
        requested.forEach((agentId, minutes) -> {
            if (agentId == null || minutes == null) {
                return;
            }
            if (minutes < 1 || minutes > MAX_MINUTES) {
                throw ApiException.validation(
                        "minutesPerTask", "Minutes per task must be between 1 and " + MAX_MINUTES + ".");
            }
            kept.put(agentId, minutes);
        });
        return kept;
    }

    private static UUID agentOf(String key) {
        try {
            return UUID.fromString(key.substring(InsightsService.KEY_MINUTES_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The inputs as the audit trail records them, as text so a rate is never rounded in transit. */
    private static Map<String, Object> snapshot(InsightsService.ValueInputs inputs) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(
                "hourlyRate",
                inputs.hourlyRate() == null ? null : inputs.hourlyRate().stripTrailingZeros().toPlainString());
        Map<String, Integer> perAgent = new LinkedHashMap<>();
        inputs.minutesPerTask().forEach((agentId, minutes) -> perAgent.put(agentId.toString(), minutes));
        values.put("minutesPerTask", perAgent);
        return values;
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
