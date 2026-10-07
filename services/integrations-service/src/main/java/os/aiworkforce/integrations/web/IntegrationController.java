package os.aiworkforce.integrations.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.integrations.domain.Connection;
import os.aiworkforce.integrations.repository.Connections;
import os.aiworkforce.integrations.service.ConnectorService;
import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.integrations.oauth.OAuthProperties;
import os.aiworkforce.integrations.oauth.OAuthService;
import os.aiworkforce.mcp.catalog.ConnectorInfo;
import os.aiworkforce.mcp.catalog.CredentialField;
import os.aiworkforce.mcp.catalog.OAuthSetup;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Connectors: what each one offers, whether it is connected, and connecting or disconnecting it.
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
    private final ConnectorCatalog catalog;
    private final ConnectorService connectors;
    private final OAuthService oauth;

    public IntegrationController(
            Connections connections,
            ToolGateway gateway,
            ConnectorCatalog catalog,
            ConnectorService connectors,
            OAuthService oauth) {
        this.connections = connections;
        this.gateway = gateway;
        this.catalog = catalog;
        this.connectors = connectors;
        this.oauth = oauth;
    }

    public record ToolView(
            String name,
            String qualifiedName,
            String description,
            String sideEffect,
            List<String> requiredScopes,
            boolean alwaysRequiresApproval) {}

    /**
     * One connector as the console shows it.
     *
     * <p>{@code status} is {@code sandbox} (practice data), {@code connected} (a live token is
     * stored and passed its last check) or {@code error} (the stored token failed its last check,
     * shown as "Needs attention" with {@code lastError}).
     */
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
            List<ToolView> tools,
            String category,
            String description,
            String authType,
            boolean liveAvailable,
            String tokenLabel,
            List<String> setupSteps,
            String docsUrl,
            String lastError,
            Instant lastCheckedAt,
            List<CredentialField> credentialFields,
            OAuthView oauth,
            boolean oauthAppConfigured) {}

    /** What an administrator needs to register an app with the provider; the redirect address included. */
    public record OAuthView(
            String provider,
            String providerLabel,
            List<CredentialField> appFields,
            List<String> scopes,
            List<String> appSteps,
            String appDocsUrl,
            String redirectUri) {}

    /** A token, or the parts of a multi-part credential under their field keys. */
    public record ConnectRequest(
            @Size(max = 4096) String token,
            @Size(max = 16) Map<String, @Size(max = 4096) String> fields,
            @Size(max = 120) String accountLabel) {}

    public record OAuthAppRequest(
            @NotBlank @Size(max = 512) String clientId,
            @Size(max = 2048) String clientSecret,
            @Size(max = 16) Map<String, @Size(max = 512) String> settings) {}

    public record OAuthStartView(String authorizeUrl) {}

    public record InternalCredential(String value, String state, String message) {

        public InternalCredential(String value) {
            this(value, value == null ? "none" : "connected", null);
        }
    }

    /** The credential the provider just rejected, so a concurrent refresh is not repeated. */
    public record RefreshRequest(String rejected) {}

    /** Why a connection needs a reconnect, in a sentence an administrator can read. */
    public record ReconnectRequest(String reason) {}

    @GetMapping("/api/integrations")
    @RequiresPermission(Permission.Codes.INTEGRATION_READ)
    @Operation(summary = "Every connector, its status, and what each one offers")
    public List<ConnectionView> list() {
        UUID orgId = orgId();
        Map<String, Connection> stored = connections.findByOrgId(orgId).stream()
                .collect(java.util.stream.Collectors.toMap(Connection::getServer, connection -> connection));
        java.util.Set<String> apps = oauth.configuredProviders(orgId);

        return gateway.connectedServers().keySet().stream()
                .sorted()
                .map(server -> view(server, stored.get(server), apps))
                .toList();
    }

    @PutMapping("/api/integrations/{server}/connection")
    @RequiresPermission(Permission.Codes.INTEGRATION_CONNECT)
    @Operation(summary = "Check a token with the provider and store it, encrypted")
    public ConnectionView connect(@PathVariable String server, @Valid @RequestBody ConnectRequest request) {
        Connection connection =
                connectors.connect(orgId(), server, request.token(), request.fields(), request.accountLabel());
        return view(server, connection, oauth.configuredProviders(orgId()));
    }

    @GetMapping("/api/integrations/{server}/oauth/app")
    @RequiresPermission(Permission.Codes.INTEGRATION_CONNECT)
    @Operation(summary = "The OAuth app saved for this connector's provider, without its secret")
    public OAuthService.AppView oauthApp(@PathVariable String server) {
        return oauth.app(orgId(), server);
    }

    @PutMapping("/api/integrations/{server}/oauth/app")
    @RequiresPermission(Permission.Codes.INTEGRATION_CONNECT)
    @Operation(summary = "Save the OAuth app (client id, secret and provider settings) for this connector's provider")
    public OAuthService.AppView saveOauthApp(@PathVariable String server, @Valid @RequestBody OAuthAppRequest request) {
        return oauth.saveApp(
                orgId(),
                RequestContext.requireActor(),
                server,
                request.clientId(),
                request.clientSecret(),
                request.settings());
    }

    /**
     * Opens a consent screen: answers the provider's address, and sets a short-lived cookie that
     * ties the sign-in to this browser. The browser is sent to the address by the console.
     */
    @GetMapping("/api/integrations/{server}/oauth/start")
    @RequiresPermission(Permission.Codes.INTEGRATION_CONNECT)
    @Operation(summary = "Start signing in to the provider; answers the consent address")
    public OAuthStartView oauthStart(@PathVariable String server, HttpServletResponse response) {
        OAuthService.Start start = oauth.start(orgId(), humanId(), server);
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(OAuthController.COOKIE, start.browserNonce())
                        .httpOnly(true)
                        .secure(oauth.properties().baseUrl().startsWith("https://"))
                        .sameSite("Lax")
                        .path(OAuthProperties.CALLBACK_PATH)
                        .maxAge(start.validFor())
                        .build()
                        .toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return new OAuthStartView(start.authorizeUrl());
    }

    @PostMapping("/api/integrations/{server}/test")
    @RequiresPermission(Permission.Codes.INTEGRATION_READ)
    @Operation(summary = "Check the stored token with the provider again")
    public ConnectorService.CheckOutcome test(@PathVariable String server) {
        return connectors.test(orgId(), server);
    }

    @DeleteMapping("/api/integrations/{server}/connection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.INTEGRATION_DISCONNECT)
    @Operation(summary = "Forget the stored token and return the connector to practice data")
    public void disconnect(@PathVariable String server) {
        connectors.disconnect(orgId(), server);
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
        // No live token means null, which is the correct answer rather than an error: the
        // adapter then answers from the sandbox.
        ConnectorService.CredentialLookup found = connectors.lookup(workspaceId, server);
        return new InternalCredential(found.value(), found.state(), found.message());
    }

    /**
     * A new access token after the provider rejected the one handed out. Empty (a null value) when
     * the sign-in cannot be renewed; the connection is then marked as needing a reconnect.
     */
    @PostMapping("/internal/connections/{server}/refresh")
    @Operation(summary = "Internal: renew a rejected sign-in token for a sibling service")
    public InternalCredential refresh(
            @PathVariable String server,
            @RequestHeader("X-Workspace-Id") UUID workspaceId,
            @RequestBody RefreshRequest request) {
        requireInternal();
        return connectors.refreshRejected(workspaceId, server, request.rejected())
                .map(token -> new InternalCredential(token, "connected", null))
                .orElseGet(() -> new InternalCredential(null, "reconnect_required", null));
    }

    @PostMapping("/internal/connections/{server}/reconnect-required")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Internal: mark a connection as needing to be connected again")
    public void reconnectRequired(
            @PathVariable String server,
            @RequestHeader("X-Workspace-Id") UUID workspaceId,
            @RequestBody ReconnectRequest request) {
        requireInternal();
        connectors.requireReconnect(workspaceId, server, request.reason());
    }

    private static void requireInternal() {
        if (RequestContext.requireActor().kind() == Actor.Kind.USER) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
    }

    private ConnectionView view(String server, Connection connection, java.util.Set<String> apps) {
        List<ToolView> tools = gateway.adapter(server)
                .map(adapter -> adapter.tools().stream()
                        .map(IntegrationController::toView)
                        .toList())
                .orElse(List.of());
        ConnectorInfo info = catalog.find(server)
                .orElseGet(() -> new ConnectorInfo(server, server, "automation", "", "none", false, null, List.of(), null));
        OAuthView oauthView = oauthView(info);
        boolean appConfigured = info.oauth() != null && apps.contains(info.oauth().provider());
        if (connection == null) {
            // Never connected: shown in sandbox state so the tools are still visible.
            return new ConnectionView(
                    server, info.displayName(), "sandbox", true, false, null, List.of(), List.of(), null, null, tools,
                    info.category(), info.description(), info.authType(), info.liveAvailable(), info.tokenLabel(),
                    info.setupSteps(), info.docsUrl(), null, null, info.credentialFields(), oauthView, appConfigured);
        }
        return new ConnectionView(
                connection.getServer(),
                info.displayName(),
                connection.getStatus(),
                connection.isSandbox(),
                connection.isReconnectRequired(),
                connection.getAccountLabel(),
                connection.getGrantedScopes(),
                connection.missingScopes(),
                connection.getConnectedAt(),
                connection.getTokenExpiresAt(),
                tools,
                info.category(),
                info.description(),
                info.authType(),
                info.liveAvailable(),
                info.tokenLabel(),
                info.setupSteps(),
                info.docsUrl(),
                connection.getLastError(),
                connection.getLastCheckedAt(),
                info.credentialFields(),
                oauthView,
                appConfigured);
    }

    private OAuthView oauthView(ConnectorInfo info) {
        OAuthSetup setup = info.oauth();
        if (setup == null) {
            return null;
        }
        return new OAuthView(
                setup.provider(),
                setup.providerLabel(),
                setup.appFields(),
                setup.scopes(),
                setup.appSteps(),
                setup.appDocsUrl(),
                oauth.properties().redirectUri());
    }

    private static UUID humanId() {
        return RequestContext.actor()
                .map(Actor::humanId)
                .flatMap(id -> {
                    try {
                        return java.util.Optional.of(UUID.fromString(id));
                    } catch (IllegalArgumentException | NullPointerException e) {
                        return java.util.Optional.<UUID>empty();
                    }
                })
                .orElse(null);
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
