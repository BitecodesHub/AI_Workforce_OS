// @find: model policy, model routing, fallback models, workspace default model, agent model override, remove model everywhere, model usage, /api/model-policy, Models settings page
// @what: REST endpoints to read and change the ordered model chain for the workspace and per agent.
// @flow: Called by Model settings UI; data used by RoutingPolicyResolver
package os.aiworkforce.orchestrator.web;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.LlmModelEntity;
import os.aiworkforce.orchestrator.domain.ModelPolicyCandidate;
import os.aiworkforce.orchestrator.domain.ModelPolicyEntity;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ModelPolicies;
import os.aiworkforce.orchestrator.repository.Models;
import os.aiworkforce.orchestrator.repository.WorkspaceProviderSettings;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry;
import os.aiworkforce.orchestrator.service.JpaProviderRegistry.WorkspaceProvider;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Which models a workspace or an agent should try, and in what order.
 *
 * <p>{@link os.aiworkforce.orchestrator.service.RoutingPolicyResolver} already resolves narrowest
 * first - an agent's own policy, then the workspace default, then a built-in sandbox-only fallback
 * - and enabling a provider and storing its credential only makes a candidate <em>usable</em>, not
 * <em>chosen</em>. Without a row here, every run still resolves to the built-in fallback,
 * regardless of what is enabled. This is the endpoint that writes that row.
 *
 * <p>A save takes the workspace's provider-settings lock before it reads anything, the lock that
 * turning a provider off also takes. A policy is refused when every provider it lists is switched
 * off for this workspace and it is set to stop when its models fail, because every run on it would
 * stop. Without the shared lock, that check and a provider being switched off could each pass
 * while the other was in flight, and together leave a policy that no longer works.
 *
 * <p>Every change to a policy - saving the workspace's, saving or clearing an agent's - is audited
 * after it commits, with the chain before and after, so "who changed the models, and to what" has
 * an answer.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Model policy")
public class ModelPolicyController {

    private static final int MAX_CANDIDATES = 10;

    private final ModelPolicies policies;
    private final Models models;
    private final Agents agents;
    private final WorkspaceProviderSettings settings;
    private final JpaProviderRegistry registry;
    private final AuditClient audit;

    public ModelPolicyController(
            ModelPolicies policies,
            Models models,
            Agents agents,
            WorkspaceProviderSettings settings,
            JpaProviderRegistry registry,
            AuditClient audit) {
        this.policies = policies;
        this.models = models;
        this.agents = agents;
        this.settings = settings;
        this.registry = registry;
        this.audit = audit;
    }

    public record CandidateInput(
            @NotBlank String providerId,
            @NotBlank String modelId,
            @DecimalMin("0.0") @DecimalMax("2.0") BigDecimal temperature,
            @Positive Integer maxOutputTokens) {}

    public record ModelPolicyRequest(
            @Valid @Size(max = MAX_CANDIDATES) List<CandidateInput> candidates,
            @Pattern(regexp = "FAIL_CLOSED|DEGRADE_TO_SANDBOX") String exhaustedBehaviour,
            @Min(1) @Max(10) Integer maxAttemptsPerCandidate,
            @Min(30) @Max(1800) Integer overallDeadlineSeconds,
            Boolean compactOnOverflow) {}

    public record CandidateView(
            int position, String providerId, String modelId, BigDecimal temperature, Integer maxOutputTokens) {}

    /** {@code configured} is false when nothing has been set and the resolver will fall through. */
    public record ModelPolicyView(
            boolean configured,
            String exhaustedBehaviour,
            int maxAttemptsPerCandidate,
            int overallDeadlineSeconds,
            boolean compactOnOverflow,
            List<CandidateView> candidates,
            /** A plain sentence about what runs will do with this routing, when that needs saying; else null. */
            String warning) {

        public ModelPolicyView(
                boolean configured,
                String exhaustedBehaviour,
                int maxAttemptsPerCandidate,
                int overallDeadlineSeconds,
                boolean compactOnOverflow,
                List<CandidateView> candidates) {
            this(
                    configured,
                    exhaustedBehaviour,
                    maxAttemptsPerCandidate,
                    overallDeadlineSeconds,
                    compactOnOverflow,
                    candidates,
                    null);
        }

        ModelPolicyView withWarning(String text) {
            return new ModelPolicyView(
                    configured,
                    exhaustedBehaviour,
                    maxAttemptsPerCandidate,
                    overallDeadlineSeconds,
                    compactOnOverflow,
                    candidates,
                    text);
        }
    }

    /** One agent that lists a provider or model. */
    public record AgentRef(UUID id, String name) {}

    /** Who lists a provider or a model: the workspace routing, and which agents' own chains. */
    public record Usage(boolean workspace, List<AgentRef> agents) {}

    public record RemoveRequest(@NotBlank String providerId, String modelId) {}

    /** One agent a removal changed, and whether its own chain is now empty. */
    public record RemovedFrom(UUID id, String name, boolean nowEmpty) {}

    public record RemoveResult(boolean workspace, List<RemovedFrom> agents, String warning) {}

    // @find: get workspace model policy, GET /api/model-policy
    @GetMapping("/model-policy")
    @RequiresPermission(Permission.Codes.PROVIDER_READ)
    @Operation(summary = "The workspace's default routing policy")
    public ModelPolicyView workspaceDefault() {
        UUID orgId = orgId();
        ModelPolicyView view = policies.findWorkspaceDefault(orgId)
                .map(ModelPolicyController::toView)
                .orElseGet(ModelPolicyController::unset);
        return view.withWarning(workspaceWarning(orgId, view));
    }

    // @find: set workspace model policy, PUT /api/model-policy
    @PutMapping("/model-policy")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Transactional
    @Operation(summary = "Set the workspace's default routing policy")
    public ModelPolicyView setWorkspaceDefault(@Valid @RequestBody ModelPolicyRequest request) {
        UUID orgId = orgId();
        settings.holdWorkspaceLock(orgId);
        ModelPolicyEntity policy = policies.findWorkspaceDefault(orgId).orElseGet(() -> {
            ModelPolicyEntity fresh = new ModelPolicyEntity();
            fresh.setOrgId(orgId);
            return fresh;
        });
        Map<String, Object> before = snapshot(policy);
        apply(policy, request);
        policies.save(policy);
        record(orgId, "model_policy.update", "model_policy", orgId, "workspace", null, before, policy);
        ModelPolicyView view = toView(policy);
        return view.withWarning(workspaceWarning(orgId, view));
    }

    // @find: get agent model policy, GET /api/agents/{agentId}/model-policy
    @GetMapping("/agents/{agentId}/model-policy")
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "An agent's own routing policy, if it has set one")
    public ModelPolicyView forAgent(@PathVariable UUID agentId) {
        UUID orgId = orgId();
        requireAgent(orgId, agentId);
        ModelPolicyView view = policies.findByOrgIdAndAgentId(orgId, agentId)
                .map(ModelPolicyController::toView)
                .orElseGet(ModelPolicyController::unset);
        return view.withWarning(agentWarning(orgId, view, false));
    }

    // @find: set agent model policy, PUT /api/agents/{agentId}/model-policy
    @PutMapping("/agents/{agentId}/model-policy")
    @RequiresPermission(Permission.Codes.AGENT_SET_MODEL_POLICY)
    @Transactional
    @Operation(summary = "Set an agent's own routing policy, overriding the workspace default")
    public ModelPolicyView setForAgent(@PathVariable UUID agentId, @Valid @RequestBody ModelPolicyRequest request) {
        UUID orgId = orgId();
        settings.holdWorkspaceLock(orgId);
        Agent agent = requireAgent(orgId, agentId);
        ModelPolicyEntity policy = policies.findByOrgIdAndAgentId(orgId, agentId)
                .orElseGet(() -> {
                    ModelPolicyEntity fresh = new ModelPolicyEntity();
                    fresh.setOrgId(orgId);
                    fresh.setAgentId(agentId);
                    return fresh;
                });
        Map<String, Object> before = snapshot(policy);
        apply(policy, request);
        policies.save(policy);
        record(orgId, "agent.model_policy.set", "agent", agentId, "agent", agent.getName(), before, policy);
        // Runs already in progress keep the chain they started with; new runs read this one.
        ModelPolicyView view = toView(policy);
        return view.withWarning(agentWarning(orgId, view, true));
    }

    // @find: clear agent model policy, DELETE /api/agents/{agentId}/model-policy
    /**
     * Gives an agent back to the workspace's routing: its own chain is deleted, and the router
     * resolves it to the workspace default (or the built-in offline model) from the next call.
     *
     * <p>Idempotent. An agent with no policy of its own is left as it is, answers the same
     * "nothing set" view as a read, and is not audited, because nothing changed.
     */
    @DeleteMapping("/agents/{agentId}/model-policy")
    @RequiresPermission(Permission.Codes.AGENT_SET_MODEL_POLICY)
    @Transactional
    @Operation(summary = "Clear an agent's own routing policy, so it follows the workspace default")
    public ModelPolicyView clearForAgent(@PathVariable UUID agentId) {
        UUID orgId = orgId();
        Agent agent = requireAgent(orgId, agentId);
        policies.findByOrgIdAndAgentId(orgId, agentId).ifPresent(policy -> {
            Map<String, Object> before = snapshot(policy);
            policies.delete(policy);
            record(orgId, "agent.model_policy.clear", "agent", agentId, "agent", agent.getName(), before, null);
        });
        return unset().withWarning(agentWarning(orgId, unset(), true));
    }

    // @find: remove model from agent chain, DELETE candidates
    /**
     * Takes one model out of an agent's own chain, at any time - including while the agent has
     * runs in progress, which finish with the chain they started with. When that empties the
     * chain the agent goes back to the workspace routing. Idempotent.
     */
    @DeleteMapping("/agents/{agentId}/model-policy/candidates")
    @RequiresPermission(Permission.Codes.AGENT_SET_MODEL_POLICY)
    @Transactional
    @Operation(summary = "Remove one model from an agent's own routing")
    public ModelPolicyView removeAgentCandidate(
            @PathVariable UUID agentId, @RequestParam String providerId, @RequestParam String modelId) {
        UUID orgId = orgId();
        settings.holdWorkspaceLock(orgId);
        Agent agent = requireAgent(orgId, agentId);
        ModelPolicyEntity policy = policies.findByOrgIdAndAgentId(orgId, agentId).orElse(null);
        if (policy == null) {
            return unset().withWarning(agentWarning(orgId, unset(), true));
        }
        Map<String, Object> before = snapshot(policy);
        boolean changed = dropFrom(policy, providerId, modelId);
        if (!changed) {
            ModelPolicyView view = toView(policy);
            return view.withWarning(agentWarning(orgId, view, true));
        }
        if (policy.getCandidates().isEmpty()) {
            policies.delete(policy);
            record(orgId, "agent.model_policy.clear", "agent", agentId, "agent", agent.getName(), before, null);
            return unset().withWarning(agentWarning(orgId, unset(), true));
        }
        policies.save(policy);
        record(orgId, "agent.model_policy.set", "agent", agentId, "agent", agent.getName(), before, policy);
        ModelPolicyView view = toView(policy);
        return view.withWarning(agentWarning(orgId, view, true));
    }

    // @find: where is model used, GET /api/model-policy/usage
    @GetMapping("/model-policy/usage")
    @RequiresPermission(Permission.Codes.PROVIDER_READ)
    @Operation(summary = "Which routing lists a provider, or one of its models")
    public Usage usage(@RequestParam String providerId, @RequestParam(required = false) String modelId) {
        UUID orgId = orgId();
        boolean workspace = false;
        List<AgentRef> listed = new ArrayList<>();
        Map<UUID, Agent> byId = agentsById(orgId);
        for (ModelPolicyEntity policy : policies.findByOrgId(orgId)) {
            if (!lists(policy, providerId, modelId)) {
                continue;
            }
            if (policy.getAgentId() == null) {
                workspace = true;
            } else if (byId.containsKey(policy.getAgentId())) {
                Agent agent = byId.get(policy.getAgentId());
                listed.add(new AgentRef(agent.getId(), agent.getName()));
            }
        }
        listed.sort(Comparator.comparing(AgentRef::name, String.CASE_INSENSITIVE_ORDER));
        return new Usage(workspace, listed);
    }

    // @find: remove model everywhere, POST /api/model-policy/remove
    /**
     * Takes a model - or every model of a provider - out of the workspace routing and out of every
     * agent's own chain at once. Never refused: an agent left with an empty chain goes back to the
     * workspace routing, and the answer says plainly what runs will do now.
     */
    @PostMapping("/model-policy/remove")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Transactional
    @Operation(summary = "Remove a model or provider from every routing chain in the workspace")
    public RemoveResult removeEverywhere(@Valid @RequestBody RemoveRequest request) {
        UUID orgId = orgId();
        settings.holdWorkspaceLock(orgId);
        String modelId = request.modelId() == null || request.modelId().isBlank() ? null : request.modelId();
        Map<UUID, Agent> byId = agentsById(orgId);
        boolean workspace = false;
        List<RemovedFrom> changedAgents = new ArrayList<>();
        for (ModelPolicyEntity policy : policies.findByOrgId(orgId)) {
            Map<String, Object> before = snapshot(policy);
            if (!dropFrom(policy, request.providerId(), modelId)) {
                continue;
            }
            if (policy.getAgentId() == null) {
                workspace = true;
                policies.save(policy);
                record(orgId, "model_policy.update", "model_policy", orgId, "workspace", null, before, policy);
                continue;
            }
            Agent agent = byId.get(policy.getAgentId());
            String name = agent == null ? "An agent" : agent.getName();
            boolean empty = policy.getCandidates().isEmpty();
            if (empty) {
                policies.delete(policy);
                record(orgId, "agent.model_policy.clear", "agent", policy.getAgentId(), "agent", name, before, null);
            } else {
                policies.save(policy);
                record(orgId, "agent.model_policy.set", "agent", policy.getAgentId(), "agent", name, before, policy);
            }
            if (agent != null) {
                changedAgents.add(new RemovedFrom(agent.getId(), name, empty));
            }
        }
        changedAgents.sort(Comparator.comparing(RemovedFrom::name, String.CASE_INSENSITIVE_ORDER));
        return new RemoveResult(workspace, changedAgents, removalWarning(orgId, changedAgents));
    }

    // ---- Warnings ------------------------------------------------------------------------------

    /** What an agent's runs will do with this routing, or null when that needs no saying. */
    private String agentWarning(UUID orgId, ModelPolicyView view, boolean sayDefault) {
        if (view.candidates().isEmpty()) {
            List<String> fallback = workspaceChain(orgId);
            if (fallback.isEmpty()) {
                return NO_MODEL_SET;
            }
            return sayDefault ? "This agent will use the workspace default: " + String.join(", ", fallback) + "." : null;
        }
        if (noProviderOn(orgId, view)) {
            return "None of the providers in this agent's routing is turned on, so its runs will fail until you"
                    + " turn one on in Model routing or add another model.";
        }
        return null;
    }

    private String workspaceWarning(UUID orgId, ModelPolicyView view) {
        if (view.candidates().isEmpty()) {
            return "No AI model is set for the workspace \u2014 agents without their own routing will fail until"
                    + " you add one in Model routing.";
        }
        if (noProviderOn(orgId, view)) {
            return "None of the providers in the workspace routing is turned on, so runs that use it will fail"
                    + " until you turn one on or add another model.";
        }
        return null;
    }

    private String removalWarning(UUID orgId, List<RemovedFrom> changed) {
        List<String> emptied =
                changed.stream().filter(RemovedFrom::nowEmpty).map(RemovedFrom::name).toList();
        List<String> fallback = workspaceChain(orgId);
        List<String> parts = new ArrayList<>();
        if (fallback.isEmpty()) {
            parts.add(NO_MODEL_SET);
        }
        if (!emptied.isEmpty()) {
            parts.add((emptied.size() == 1 ? emptied.getFirst() + " now has" : String.join(", ", emptied) + " now have")
                    + " no model of its own and will use the workspace default"
                    + (fallback.isEmpty() ? ", which is empty." : ": " + String.join(", ", fallback) + "."));
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** The plain sentence for routing that lists no model anywhere. */
    static final String NO_MODEL_SET =
            "No AI model is set for this agent or the workspace \u2014 add one in Model routing.";

    /** True when the chain stops on failure and none of the providers it lists is on here. */
    private boolean noProviderOn(UUID orgId, ModelPolicyView view) {
        if (!"FAIL_CLOSED".equals(view.exhaustedBehaviour())) {
            return false;
        }
        Set<String> on = registry.workspaceProviders(orgId).stream()
                .filter(WorkspaceProvider::enabled)
                .map(provider -> provider.entity().getId())
                .collect(Collectors.toSet());
        return view.candidates().stream().map(CandidateView::providerId).noneMatch(on::contains);
    }

    /** The workspace chain as "Provider · Model" labels, in order. */
    private List<String> workspaceChain(UUID orgId) {
        return policies.findWorkspaceDefault(orgId)
                .map(policy -> policy.getCandidates().stream()
                        .sorted(Comparator.comparing(ModelPolicyCandidate::getPosition))
                        .map(candidate -> label(orgId, candidate.getProviderId(), candidate.getModelId()))
                        .toList())
                .orElse(List.of());
    }

    private String label(UUID orgId, String providerId, String modelId) {
        String provider = registry.workspaceProvider(orgId, providerId)
                .map(p -> p.entity().getDisplayName())
                .filter(name -> name != null && !name.isBlank())
                .orElse(providerId);
        String model = models.findById(new LlmModelEntity.Key(providerId, modelId))
                .map(LlmModelEntity::getDisplayName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(modelId);
        return provider + " \u00b7 " + model;
    }

    // ---- Removing ------------------------------------------------------------------------------

    private static boolean lists(ModelPolicyEntity policy, String providerId, String modelId) {
        return policy.getCandidates().stream()
                .anyMatch(c -> c.getProviderId().equals(providerId) && (modelId == null || c.getModelId().equals(modelId)));
    }

    /** Drops the matching candidates and renumbers the rest; false when none matched. */
    private static boolean dropFrom(ModelPolicyEntity policy, String providerId, String modelId) {
        if (!lists(policy, providerId, modelId)) {
            return false;
        }
        List<ModelPolicyCandidate> kept = policy.getCandidates().stream()
                .sorted(Comparator.comparing(ModelPolicyCandidate::getPosition))
                .filter(c -> !(c.getProviderId().equals(providerId) && (modelId == null || c.getModelId().equals(modelId))))
                .toList();
        List<ModelPolicyCandidate> renumbered = new ArrayList<>();
        for (int position = 0; position < kept.size(); position++) {
            ModelPolicyCandidate old = kept.get(position);
            ModelPolicyCandidate fresh =
                    ModelPolicyCandidate.of(policy.getId(), position, old.getProviderId(), old.getModelId());
            fresh.setTemperature(old.getTemperature());
            fresh.setMaxOutputTokens(old.getMaxOutputTokens());
            renumbered.add(fresh);
        }
        policy.setCandidates(renumbered);
        return true;
    }

    private Map<UUID, Agent> agentsById(UUID orgId) {
        Map<UUID, Agent> byId = new LinkedHashMap<>();
        for (Agent agent : agents.findByOrgIdOrderByName(orgId)) {
            byId.put(agent.getId(), agent);
        }
        return byId;
    }

    private Agent requireAgent(UUID orgId, UUID agentId) {
        return agents.findByIdAndOrgId(agentId, orgId).orElseThrow(() -> ApiException.notFound("agent", agentId));
    }

    /**
     * Audits a change after it commits, so a save that rolls back leaves no entry saying it
     * happened. {@code after} is null when the policy was removed.
     */
    private void record(
            UUID orgId,
            String action,
            String resourceType,
            UUID resourceId,
            String scope,
            String agentName,
            Map<String, Object> before,
            ModelPolicyEntity after) {
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scope", scope);
        if (agentName != null) {
            detail.put("agent", agentName);
        }
        detail.put("old", before);
        // A cleared policy has no chain at all: the agent follows the workspace's.
        detail.put("new", after == null ? Map.of("candidates", List.of()) : snapshot(after));
        LifecycleAnnouncer.afterCommit(() ->
                audit.record(orgId, actor, action, resourceType, resourceId.toString(), "succeeded", detail));
    }

    /** A policy as the audit trail records it: the chain as provider/model, in order, and what happens when it runs out. */
    private static Map<String, Object> snapshot(ModelPolicyEntity policy) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(
                "candidates",
                policy.getCandidates().stream()
                        .sorted(Comparator.comparing(ModelPolicyCandidate::getPosition))
                        .map(candidate -> candidate.getProviderId() + "/" + candidate.getModelId())
                        .toList());
        values.put("exhaustedBehaviour", policy.getExhaustedBehaviour());
        return values;
    }

    /**
     * Rejects a candidate that names a model this platform does not know about, rather than
     * saving a policy that would fail silently the first time a run tries to resolve it.
     */
    private void apply(ModelPolicyEntity policy, ModelPolicyRequest request) {
        List<CandidateInput> inputs = request.candidates() == null ? List.of() : request.candidates();
        // A model already in the chain is kept even if the catalogue no longer lists it, so taking
        // another model out is never refused because of one that stays.
        Set<String> already = policy.getCandidates().stream()
                .map(c -> c.getProviderId() + "/" + c.getModelId())
                .collect(Collectors.toSet());
        List<ModelPolicyCandidate> candidates = new ArrayList<>();
        for (int position = 0; position < inputs.size(); position++) {
            CandidateInput input = inputs.get(position);
            if (!already.contains(input.providerId() + "/" + input.modelId())
                    && models.findById(new LlmModelEntity.Key(input.providerId(), input.modelId()))
                    .isEmpty()) {
                throw ApiException.validation(
                        "candidates[" + position + "]",
                        "Unknown model " + input.providerId() + "/" + input.modelId() + ".");
            }
            ModelPolicyCandidate candidate =
                    ModelPolicyCandidate.of(policy.getId(), position, input.providerId(), input.modelId());
            candidate.setTemperature(input.temperature());
            candidate.setMaxOutputTokens(input.maxOutputTokens());
            candidates.add(candidate);
        }
        policy.setCandidates(candidates);

        if (request.exhaustedBehaviour() != null) {
            policy.setExhaustedBehaviour(request.exhaustedBehaviour());
        }
        if (request.maxAttemptsPerCandidate() != null) {
            policy.setMaxAttemptsPerCandidate(request.maxAttemptsPerCandidate());
        }
        if (request.overallDeadlineSeconds() != null) {
            policy.setOverallDeadlineSeconds(request.overallDeadlineSeconds());
        }
        if (request.compactOnOverflow() != null) {
            policy.setCompactOnOverflow(request.compactOnOverflow());
        }
    }

    private static ModelPolicyView toView(ModelPolicyEntity entity) {
        List<CandidateView> candidates = entity.getCandidates().stream()
                .sorted(Comparator.comparing(ModelPolicyCandidate::getPosition))
                .map(c -> new CandidateView(
                        c.getPosition(), c.getProviderId(), c.getModelId(), c.getTemperature(), c.getMaxOutputTokens()))
                .toList();
        return new ModelPolicyView(
                !candidates.isEmpty(),
                entity.getExhaustedBehaviour(),
                entity.getMaxAttemptsPerCandidate(),
                entity.getOverallDeadlineSeconds(),
                entity.isCompactOnOverflow(),
                candidates);
    }

    private static ModelPolicyView unset() {
        return new ModelPolicyView(false, "FAIL_CLOSED", 2, 300, true, List.of());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
