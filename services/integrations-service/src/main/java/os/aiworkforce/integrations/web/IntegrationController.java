package os.aiworkforce.integrations.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.integrations.domain.Connection;
import os.aiworkforce.integrations.repository.Connections;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Connected tool servers and the tools they offer.
 *
 * <p>A server with no connection is still listed, in sandbox state. That is deliberate: a person
 * evaluating the platform should see what Gmail would offer before deciding whether to connect an
 * account, and an agent can be configured against it in the meantime.
 */
@RestController
@Tag(name = "Integrations")
@RequestMapping
public class IntegrationController {

    private final Connections connections;
    private final ToolGateway gateway;

    public IntegrationController(Connections connections, ToolGateway gateway) {
        this.connections = connections;
        this.gateway = gateway;
    }

    public record ToolView(
            String name,
            String qualifiedName,
            String description,
            String sideEffect,
            List<String> requiredScopes,
            boolean alwaysRequiresApproval) {}

    public record ConnectionView(
            String server,
            String displayName,
            String status,
            boolean sandbox,
            boolean reconnectRequired,
            String accountLabel,
            List<String> grantedScopes,
            List<String> missingScopes,
            Instant connectedAt,
            Instant tokenExpiresAt,
            List<ToolView> tools) {}

    public record InternalCredential(String value) {}

    @GetMapping("/api/integrations")
    @RequiresPermission(Permission.Codes.INTEGRATION_READ)
    @Operation(summary = "Connected tool servers, and what each one offers")
    public List<ConnectionView> list() {
        UUID orgId = orgId();
        Map<String, Connection> stored = connections.findByOrgId(orgId).stream()
                .collect(java.util.stream.Collectors.toMap(Connection::getServer, connection -> connection));

        return gateway.connectedServers().keySet().stream()
                .sorted()
                .map(server -> {
                    Connection connection = stored.get(server);
                    List<ToolView> tools = gateway.adapter(server)
                            .map(adapter -> adapter.tools().stream()
                                    .map(IntegrationController::toView)
                                    .toList())
                            .orElse(List.of());
                    if (connection == null) {
                        // Never connected: shown in sandbox state so the tools are still visible.
                        return new ConnectionView(
                                server, server, "sandbox", true, false, null, List.of(), List.of(), null, null, tools);
                    }
                    return new ConnectionView(
                            connection.getServer(),
                            connection.getDisplayName(),
                            connection.getStatus(),
                            connection.isSandbox(),
                            connection.isReconnectRequired(),
                            connection.getAccountLabel(),
                            connection.getGrantedScopes(),
                            connection.missingScopes(),
                            connection.getConnectedAt(),
                            connection.getTokenExpiresAt(),
                            tools);
                })
                .toList();
    }

    /**
     * The credential for one server, for the orchestrator.
     *
     * <p>Refused to a person's token, whatever permissions it carries. The only legitimate caller
     * is a service acting on a run, and a human session reaching this endpoint is either a
     * mistake or an attempt to read a workspace's stored tokens.
     */
    @GetMapping("/internal/connections/{server}/credential")
    @Operation(summary = "Internal: resolve a tool credential for a sibling service")
    public InternalCredential credential(
            @PathVariable String server, @RequestHeader("X-Workspace-Id") UUID workspaceId) {
        Actor actor = RequestContext.requireActor();
        if (actor.kind() == Actor.Kind.USER) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }

        return connections
                .findByOrgIdAndServer(workspaceId, server)
                .filter(Connection::isUsable)
                // A sandbox connection needs no credential, and returning null is the correct
                // answer rather than an error: the sandbox adapter ignores it.
                .map(connection -> new InternalCredential(connection.getCredentialRef()))
                .orElse(new InternalCredential(null));
    }

    private static ToolView toView(ToolDefinition tool) {
        return new ToolView(
                tool.name(),
                tool.qualifiedName(),
                tool.description(),
                tool.sideEffect().name(),
                tool.requiredScopes(),
                tool.alwaysRequiresApproval());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
