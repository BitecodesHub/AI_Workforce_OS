package os.aiworkforce.orchestrator.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.service.AgentTemplates;
import os.aiworkforce.orchestrator.service.AgentTemplates.Template;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * The ready-made assistants a workspace can start from, and adding one.
 *
 * <p>Adding one is the same act as {@code POST /api/agents}: it calls that endpoint's own method,
 * so the agent is created by the person asking (never by "system"), with revision 1 of the
 * template's instructions. What it deliberately does not do is grant anything. A template names
 * the connectors worth connecting ({@code suggestedConnectors}) and the console prompts for them;
 * which connectors an agent may use stays a separate decision, made through the grant endpoints
 * by someone who holds {@code agent:grant_tools}.
 *
 * <p>Adding the same template twice is allowed and never fails: a key already taken gets
 * {@code -2}, then {@code -3}, and so on, so a double click or a second attempt after a timeout
 * ends with an assistant rather than an error.
 *
 * <p>{@code GET /api/agent-templates} is the address the API names. The same list is also served
 * under {@code /api/agents/templates}, which the gateway, the development proxy and the launcher
 * already route to this service.
 */
@RestController
@Tag(name = "Agent templates")
public class AgentTemplateController {

    /** Far more than anyone adds; a loop that long means something is wrong, not busy. */
    private static final int MAX_KEY_ATTEMPTS = 50;

    private final AgentController agentController;
    private final Agents agents;
    private final AuditClient audit;

    public AgentTemplateController(AgentController agentController, Agents agents, AuditClient audit) {
        this.agentController = agentController;
        this.agents = agents;
        this.audit = audit;
    }

    /**
     * One template as a card shows it. The prompt is not here: the person sees it on the agent's
     * own page once it exists.
     */
    public record TemplateView(
            String key, String name, String category, String description, List<String> suggestedConnectors) {}

    /**
     * @param agent the new agent, as {@code POST /api/agents} returns it
     * @param suggestedConnectors the connectors to connect so it can act, by connector id
     */
    public record FromTemplateResult(AgentController.AgentView agent, List<String> suggestedConnectors) {}

    @GetMapping({"/api/agent-templates", "/api/agents/templates"})
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "The ready-made assistants a workspace can start from")
    public List<TemplateView> list() {
        return AgentTemplates.all().stream().map(AgentTemplateController::toView).toList();
    }

    @PostMapping("/api/agents/from-template/{key}")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.AGENT_CREATE)
    @Operation(summary = "Add a ready-made assistant to this workspace, with no connectors granted")
    public FromTemplateResult create(@PathVariable String key) {
        Template template = AgentTemplates.find(key).orElseThrow(() -> ApiException.notFound("agent template", key));
        UUID orgId = UUID.fromString(RequestContext.requireOrgId());
        Actor actor = RequestContext.requireActor();

        String chosen = freeKey(orgId, template.key(), 1);
        AgentController.AgentView created = null;
        for (int attempt = 1; created == null; attempt++) {
            try {
                created = agentController.create(new AgentController.CreateAgentRequest(
                        chosen, template.name(), template.category(), template.prompt(), null));
            } catch (ApiException taken) {
                // Somebody took the key between looking and creating; try the next one.
                if (taken.code() != ErrorCode.ALREADY_EXISTS || attempt >= MAX_KEY_ATTEMPTS) {
                    throw taken;
                }
                chosen = freeKey(orgId, template.key(), nextSuffix(chosen, template.key()));
            }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("template", template.key());
        detail.put("key", created.key());
        detail.put("name", created.name());
        AgentController.AgentView made = created;
        LifecycleAnnouncer.afterCommit(() -> audit.record(
                orgId, actor, "agent.create_from_template", "agent", made.id().toString(), "succeeded", detail));
        return new FromTemplateResult(created, template.suggestedConnectors());
    }

    /** {@code hr}, then {@code hr-2}, {@code hr-3}, from {@code firstSuffix} (1 means the bare key). */
    private String freeKey(UUID orgId, String base, int firstSuffix) {
        int suffix = firstSuffix;
        String candidate = suffix <= 1 ? base : base + "-" + suffix;
        while (agents.findByOrgIdAndKey(orgId, candidate).isPresent()) {
            suffix = Math.max(suffix, 1) + 1;
            candidate = base + "-" + suffix;
        }
        return candidate;
    }

    /** The suffix after the one {@code taken} already carries: {@code hr} to 2, {@code hr-2} to 3. */
    private static int nextSuffix(String taken, String base) {
        if (taken.length() > base.length() + 1 && taken.startsWith(base + "-")) {
            try {
                return Integer.parseInt(taken.substring(base.length() + 1)) + 1;
            } catch (NumberFormatException notANumber) {
                return 2;
            }
        }
        return 2;
    }

    private static TemplateView toView(Template template) {
        return new TemplateView(
                template.key(),
                template.name(),
                template.category(),
                template.description(),
                template.suggestedConnectors());
    }
}
