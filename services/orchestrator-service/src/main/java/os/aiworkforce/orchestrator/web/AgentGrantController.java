// @find: agent grants, tool access, connector grant, allow agent to use tool, revoke grant, approval mode per tool, PUT /api/agents/{agentId}/grants/{server}, DELETE grant, permissions tab
// @what: REST endpoints that grant an agent a connector with an approval mode or revoke it.
// @flow: Called by the Agent detail Tools and permissions UI
package os.aiworkforce.orchestrator.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.mcp.spi.McpServerAdapter;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Which connectors an agent may use, and which of their capabilities.
 *
 * <p>Server and tool names are checked against the tool gateway, so a grant can only name things
 * that exist. Scopes are never taken from the request: they are filled from the chosen tools' own
 * required scopes, so a grant cannot be saved that the gateway would then refuse for a missing
 * scope. Asking first is added by {@code requireApproval}; sending and deleting ask first anyway.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/grants")
@Tag(name = "Agents")
public class AgentGrantController {

    static final int MAX_CALLS_PER_RUN = 1000;

    private final Agents agents;
    private final ToolGrants grants;
    private final ToolGateway gateway;
    private final AgentController details;
    private final AuditClient audit;

    public AgentGrantController(
            Agents agents, ToolGrants grants, ToolGateway gateway, AgentController details, AuditClient audit) {
        this.agents = agents;
        this.grants = grants;
        this.gateway = gateway;
        this.details = details;
        this.audit = audit;
    }

    /**
     * @param tools tool names on the server; empty or missing means every tool it offers
     * @param requireApproval ask a person before every action, not only before sending or deleting
     * @param maxCallsPerRun ceiling per tool per run; null means no ceiling
     */
    public record GrantRequest(
            @Size(max = 100) List<String> tools,
            Boolean requireApproval,
            @Min(1) @Max(MAX_CALLS_PER_RUN) Integer maxCallsPerRun) {}

    // @find: grant tool access to agent, PUT /api/agents/{agentId}/grants/{server}, require approval, allowed tools
    @PutMapping("/{server}")
    @RequiresPermission(Permission.Codes.AGENT_GRANT_TOOLS)
    @Transactional
    @Operation(summary = "Let an agent use a connector, with the chosen capabilities")
    public AgentController.AgentDetail grant(
            @PathVariable UUID agentId, @PathVariable String server, @Valid @RequestBody GrantRequest request) {
        UUID orgId = orgId();
        agents.findByIdAndOrgId(agentId, orgId).orElseThrow(() -> ApiException.notFound("agent", agentId));
        McpServerAdapter adapter = gateway.adapter(server)
                .orElseThrow(() -> invalid("server", server, "There is no connector named " + server + "."));

        List<String> tools = request.tools() == null
                ? List.of()
                : request.tools().stream()
                        .map(tool -> tool == null ? "" : tool.strip())
                        .distinct()
                        .toList();
        List<ToolDefinition> chosen = tools.isEmpty()
                ? adapter.tools()
                : tools.stream()
                        .map(tool -> adapter.tool(tool)
                                .orElseThrow(() -> invalid(
                                        "tools", tool, "The " + server + " connector has no capability named " + tool + ".")))
                        .toList();
        List<String> scopes = chosen.stream()
                .flatMap(tool -> tool.requiredScopes().stream())
                .distinct()
                .toList();

        AgentToolGrant grant = grants.findByAgentIdAndServer(agentId, server).orElseGet(() -> {
            AgentToolGrant fresh = new AgentToolGrant();
            fresh.setId(UuidV7.generate());
            fresh.setOrgId(orgId);
            fresh.setAgentId(agentId);
            fresh.setServer(server);
            return fresh;
        });
        boolean created = grant.isNew();
        grant.setAllowedTools(tools);
        grant.setScopes(scopes);
        grant.setRequireApproval(Boolean.TRUE.equals(request.requireApproval()));
        grant.setMaxCallsPerRun(request.maxCallsPerRun());
        grant.setEnabled(true);
        grants.save(grant);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("server", server);
        detail.put("tools", tools.isEmpty() ? "all" : tools);
        detail.put("requireApproval", grant.isRequireApproval());
        if (grant.getMaxCallsPerRun() != null) {
            detail.put("maxCallsPerRun", grant.getMaxCallsPerRun());
        }
        record(orgId, created ? "agent.grant.add" : "agent.grant.update", agentId, detail);
        return details.get(agentId);
    }

    // @find: revoke tool access, DELETE /api/agents/{agentId}/grants/{server}
    @DeleteMapping("/{server}")
    @RequiresPermission(Permission.Codes.AGENT_GRANT_TOOLS)
    @Transactional
    @Operation(summary = "Stop an agent using a connector")
    public AgentController.AgentDetail revoke(@PathVariable UUID agentId, @PathVariable String server) {
        UUID orgId = orgId();
        agents.findByIdAndOrgId(agentId, orgId).orElseThrow(() -> ApiException.notFound("agent", agentId));
        // Not validated against the gateway: a grant for a connector that has since been removed
        // must still be removable.
        grants.findByAgentIdAndServer(agentId, server).ifPresent(grant -> {
            grants.delete(grant);
            record(orgId, "agent.grant.remove", agentId, Map.of("server", server));
        });
        return details.get(agentId);
    }

    private void record(UUID orgId, String action, UUID agentId, Map<String, Object> detail) {
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        LifecycleAnnouncer.afterCommit(
                () -> audit.record(orgId, actor, action, "agent", agentId.toString(), "succeeded", detail));
    }

    private static ApiException invalid(String field, String value, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message)
                .with("field", field)
                .with("value", value);
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
