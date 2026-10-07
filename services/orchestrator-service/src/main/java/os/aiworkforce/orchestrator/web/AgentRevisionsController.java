package os.aiworkforce.orchestrator.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * The history of an agent's configuration, as a console reads it.
 *
 * <p>{@code GET /api/agents/{id}/versions} on {@link AgentController} returns the stored rows as
 * they are. This is the shape a screen should depend on instead: it names what changed from one
 * revision to the next, says whether any run has used the revision, and carries the settings
 * somebody needs to put an old revision back. Putting one back is not an endpoint of its own: the
 * console saves its prompt, goals and limits through {@code PUT /api/agents/{id}/configuration},
 * which makes revision N+1, so the history only ever grows and a trace never loses the revision
 * it ran on.
 */
@RestController
@RequestMapping("/api/agents")
@Tag(name = "Agent revisions")
public class AgentRevisionsController {

    /** The newest revisions returned. Each carries its whole prompt, so the list is bounded. */
    static final int MAX_REVISIONS = 100;

    /** Field names as the console's edit form knows them. */
    static final String SYSTEM_PROMPT = "systemPrompt";

    static final String GOALS = "goals";
    static final String TEMPERATURE = "temperature";
    static final String MAX_OUTPUT_TOKENS = "maxOutputTokens";
    static final String MAX_STEPS = "maxSteps";

    private final Agents agents;
    private final AgentVersions versions;

    public AgentRevisionsController(Agents agents, AgentVersions versions) {
        this.agents = agents;
        this.versions = versions;
    }

    /**
     * One revision of an agent's configuration.
     *
     * @param revision 1 for the first, counting up by one per saved change
     * @param createdBy the id of the person who saved it; null for one the platform wrote
     * @param changedFields what differs from the revision before it, by field name
     *     ({@code systemPrompt}, {@code goals}, {@code temperature}, {@code maxOutputTokens},
     *     {@code maxSteps}); empty for the first revision and for one saved with no change
     * @param initial whether this is the agent's first revision
     * @param current whether the agent works from this revision now
     * @param usedByRuns whether a run has used it, which seals it: its prompt is what those runs'
     *     traces were produced by, and is never edited
     */
    public record RevisionView(
            UUID id,
            int revision,
            Instant createdAt,
            String createdBy,
            List<String> changedFields,
            boolean initial,
            boolean current,
            boolean usedByRuns,
            String systemPrompt,
            String goals,
            BigDecimal temperature,
            Integer maxOutputTokens,
            int maxSteps) {}

    @GetMapping("/{agentId}/revisions")
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "Every revision of an agent's configuration, newest first, with what changed in each")
    public List<RevisionView> revisions(@PathVariable UUID agentId) {
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        Agent agent =
                agents.findByIdAndOrgId(agentId, orgId).orElseThrow(() -> ApiException.notFound("agent", agentId));
        // Newest first. Each row is compared with the one after it, which is the revision before it.
        List<AgentVersion> all = versions.findByAgentIdOrderByRevisionDesc(agentId);
        List<RevisionView> views = new ArrayList<>();
        for (int i = 0; i < all.size() && i < MAX_REVISIONS; i++) {
            AgentVersion version = all.get(i);
            AgentVersion before = i + 1 < all.size() ? all.get(i + 1) : null;
            views.add(toView(version, before, Objects.equals(version.getId(), agent.getCurrentVersionId())));
        }
        return views;
    }

    static RevisionView toView(AgentVersion version, AgentVersion before, boolean current) {
        return new RevisionView(
                version.getId(),
                version.getRevision(),
                version.getCreatedAt(),
                version.getCreatedBy(),
                before == null ? List.of() : changedFields(before, version),
                before == null,
                current,
                // A run seals the version it uses in the transaction that creates the run
                // (AgentRunner.createRun), so sealed and used by a run are the same fact.
                version.isSealed(),
                version.getSystemPrompt(),
                version.getGoals(),
                version.getTemperature(),
                version.getMaxOutputTokens(),
                version.getMaxSteps());
    }

    /** What differs between two revisions, in the order the edit form lists its fields. */
    static List<String> changedFields(AgentVersion older, AgentVersion newer) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(older.getSystemPrompt(), newer.getSystemPrompt())) {
            changed.add(SYSTEM_PROMPT);
        }
        // An unset goals field and an empty one are the same thing: a save writes "" for null.
        if (!Objects.equals(blankToEmpty(older.getGoals()), blankToEmpty(newer.getGoals()))) {
            changed.add(GOALS);
        }
        if (!sameNumber(older.getTemperature(), newer.getTemperature())) {
            changed.add(TEMPERATURE);
        }
        if (!Objects.equals(older.getMaxOutputTokens(), newer.getMaxOutputTokens())) {
            changed.add(MAX_OUTPUT_TOKENS);
        }
        if (older.getMaxSteps() != newer.getMaxSteps()) {
            changed.add(MAX_STEPS);
        }
        return List.copyOf(changed);
    }

    private static String blankToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** 0.7 and 0.70 are one temperature, so compared by value, not by scale. */
    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }
}
