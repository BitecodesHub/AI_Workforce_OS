// @find: integrations controller, integrations page api, GET /api/integrations, PUT /api/integrations/{server}/connection, POST /api/integrations/{server}/test, DELETE /api/integrations/{server}/connection, oauth app, oauth start, internal credential, connectors, integrations, gmail, slack, github, jira, confluence, asana, zendesk, stripe, zoom, hubspot, linear, notion, salesforce, outlook, teams, calendar, drive, sheets, webhook
// @what: REST endpoints behind the Integrations page: list connectors, connect, test, disconnect, OAuth app setup, plus internal credential endpoints for the orchestrator.
// @flow: Calls ConnectorService and OAuthService; internal endpoints are called by the orchestrator and OAuth adapters
package os.aiworkforce.integrations.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
 *
 * <p>Three groups of routes live here: the Integrations page ({@code /api/integrations/...}, gated by
 * permission), the OAuth app and sign-in start routes, and the {@code /internal/connections/...}
 * routes that sibling services call with a service token. Delegates to {@code ConnectorService}
 * and {@code OAuthService}; it holds no state of its own.
 */
@RestController
@Tag(name = "Integrations")
@RequestMapping
public class IntegrationController {

    /** Reads stored connections for the listing. */
    private final Connections connections;
    /** Source of the connector adapters and their tool definitions. */
    private final ToolGateway gateway;
    /** Static catalog of connector names, categories, setup steps and credential fields. */
    private final ConnectorCatalog catalog;
    /** Connect, test, disconnect and internal credential logic. */
    private final ConnectorService connectors;
    /** OAuth app storage and sign-in start. */
    private final OAuthService oauth;

    /** Creates the controller with the collaborators it delegates to. */
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

    /**
     * One tool a connector offers, as the console lists it. {@code alwaysRequiresApproval} is true for
     * a tool that is always approved by a person, whether by its definition or because its adapter asks
     * on every call of it.
     */
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
     *
     * <p>Built by {@code view}; it never contains a token. {@code liveAvailable} says whether a live
     * adapter exists, {@code authType} how the connector is connected, {@code oauth} is null for
     * connectors that are not connected by signing in, and {@code oauthAppConfigured} says whether the
     * workspace has saved the provider's app.
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

    /**
     * Body of {@code PUT /api/integrations/{server}/oauth/app}. A blank {@code clientSecret} keeps the
     * secret already stored; {@code settings} holds the provider's non-secret fields.
     */
    public record OAuthAppRequest(
            @NotBlank
                    @Size(max = 512)
                    @Pattern(regexp = "\\s*\\S+\\s*", message = "A client ID has no spaces. Copy it again from the provider.")
                    String clientId,
            @Size(max = 2048) String clientSecret,
            @Size(max = 16) Map<String, @Size(max = 512) String> settings) {}

    /** The provider's consent address that the console sends the browser to. */
    public record OAuthStartView(String authorizeUrl) {}

    /**
     * The answer of the internal credential endpoints. {@code value} is the token (or null),
     * {@code state} is {@code connected}, {@code none}, {@code reconnect_required} or
     * {@code unreadable}, and {@code message} is a plain reason when there is one.
     */
    public record InternalCredential(String value, String state, String message) {

        /** A token only: the state is {@code connected} when a value is present and {@code none} otherwise. */
        public InternalCredential(String value) {
            this(value, value == null ? "none" : "connected", null);
        }
    }

    /** The credential the provider just rejected, so a concurrent refresh is not repeated. */
    public record RefreshRequest(String rejected) {}

    /** Why a connection needs a reconnect, in a sentence an administrator can read. */
    public record ReconnectRequest(String reason) {}

    // @find: list connectors, Integrations page, GET /api/integrations
    /**
     * {@code GET /api/integrations}. Permission: {@code integration:read}.
     *
     * <p>Loads the workspace's stored connections and its saved OAuth apps, then returns one view for
     * every connector the gateway knows, sorted by key. Connectors never connected appear as practice
     * data. Read only; no tokens are decrypted.
     */
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

    // @find: connect connector, Add connector dialog, PUT /api/integrations/{server}/connection
    /**
     * {@code PUT /api/integrations/{server}/connection}. Permission: {@code integration:connect}.
     *
     * <p>Passes the pasted token or credential parts to {@code ConnectorService.connect}, which checks
     * them with the provider, stores them encrypted and writes an audit entry. Returns the updated view.
     * Fails with a validation or connector error when the token is empty, rejected, or the connector is
     * not live or is connected by signing in.
     */
    @PutMapping("/api/integrations/{server}/connection")
    @RequiresPermission(Permission.Codes.INTEGRATION_CONNECT)
    @Operation(summary = "Check a token with the provider and store it, encrypted")
    public ConnectionView connect(@PathVariable String server, @Valid @RequestBody ConnectRequest request) {
        Connection connection =
                connectors.connect(orgId(), server, request.token(), request.fields(), request.accountLabel());
        return view(server, connection, oauth.configuredProviders(orgId()));
    }

    // @find: get oauth app, GET /api/integrations/{server}/oauth/app
    /**
     * {@code GET /api/integrations/{server}/oauth/app}. Permission: {@code integration:connect}.
     *
     * <p>Returns whether an app is saved for the connector's provider, with its client identifier and
     * settings; the secret is never included. Fails with not found for a connector without sign-in.
     */
    @GetMapping("/api/integrations/{server}/oauth/app")
    @RequiresPermission(Permission.Codes.INTEGRATION_CONNECT)
    @Operation(summary = "The OAuth app saved for this connector's provider, without its secret")
    public OAuthService.AppView oauthApp(@PathVariable String server) {
        return oauth.app(orgId(), server);
    }

    // @find: save oauth app, PUT /api/integrations/{server}/oauth/app
    /**
     * {@code PUT /api/integrations/{server}/oauth/app}. Permission: {@code integration:connect}.
     *
     * <p>Validates and saves the provider app for the workspace (secret encrypted, written to the
     * database) and writes an audit entry as the calling actor. Returns the saved app without its secret.
     */
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

    // @find: start oauth sign in, GET /api/integrations/{server}/oauth/start
    /**
     * Opens a consent screen: answers the provider's address, and sets a short-lived cookie that
     * ties the sign-in to this browser. The browser is sent to the address by the console.
     *
     * <p>Permission: {@code integration:connect}. Calls {@code OAuthService.start}, which writes a
     * pending state row, and sets the {@code aiwos_oauth} cookie (HTTP only, Lax, limited to the callback
     * path, lasting as long as the state). Response: the consent address. Fails when the app has not
     * been saved yet.
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

    // @find: test connection, POST /api/integrations/{server}/test
    /**
     * {@code POST /api/integrations/{server}/test}. Permission: {@code integration:read}.
     *
     * <p>Asks the provider again whether the stored credential works, through
     * {@code ConnectorService.test}, and records the result on the connection. Response: whether the
     * check passed, a message and the time. With no stored token it reports that practice data is used.
     */
    @PostMapping("/api/integrations/{server}/test")
    @RequiresPermission(Permission.Codes.INTEGRATION_READ)
    @Operation(summary = "Check the stored token with the provider again")
    public ConnectorService.CheckOutcome test(@PathVariable String server) {
        return connectors.test(orgId(), server);
    }

    // @find: disconnect connector, DELETE /api/integrations/{server}/connection
    /**
     * {@code DELETE /api/integrations/{server}/connection}. Permission: {@code integration:disconnect}.
     *
     * <p>Forgets the stored token and returns the connector to practice data through
     * {@code ConnectorService.disconnect}; an audit entry is written when a live connection existed.
     * Response: {@code 204}. Disconnecting a connector that was never connected is not an error.
     */
    @DeleteMapping("/api/integrations/{server}/connection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.INTEGRATION_DISCONNECT)
    @Operation(summary = "Forget the stored token and return the connector to practice data")
    public void disconnect(@PathVariable String server) {
        connectors.disconnect(orgId(), server);
    }

    // @find: internal get credential, GET /internal/connections/{server}/credential
    /**
     * The credential for one server, for the orchestrator.
     *
     * <p>Refused to a person's token, whatever permissions it carries. The only legitimate caller
     * is a service acting on a run, and a human session reaching this endpoint is either a
     * mistake or an attempt to read a workspace's stored tokens.
     *
     * <p>Route: {@code GET /internal/connections/{server}/credential} with the workspace in the
     * {@code X-Workspace-Id} header. Response: the credential and its state from
     * {@code ConnectorService.lookup}, which may renew a token that is about to expire. A missing live
     * token is a normal answer (state {@code none}), not an error.
     *
     * @throws ApiException {@code PERMISSION_DENIED} when the caller is a person
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

    // @find: internal refresh credential, POST /internal/connections/{server}/refresh
    /**
     * A new access token after the provider rejected the one handed out. Empty (a null value) when
     * the sign-in cannot be renewed; the connection is then marked as needing a reconnect.
     *
     * <p>Route: {@code POST /internal/connections/{server}/refresh}; internal callers only. Calls
     * {@code ConnectorService.refreshRejected}, which may call the provider's token endpoint, save the
     * new tokens and write an audit entry on failure. Response: the new token with state
     * {@code connected}, or no value with state {@code reconnect_required}.
     *
     * @throws ApiException {@code PERMISSION_DENIED} when the caller is a person
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

    // @find: internal reconnect required, POST /internal/connections/{server}/reconnect-required
    /**
     * {@code POST /internal/connections/{server}/reconnect-required}; internal callers only. Used when
     * a sibling service finds that the provider no longer accepts a connection. Calls
     * {@code ConnectorService.requireReconnect}, which flags the connection and writes an audit entry.
     * Response: {@code 204}.
     *
     * @throws ApiException {@code PERMISSION_DENIED} when the caller is a person
     */
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

    /**
     * Refuses a person's token on the internal routes.
     *
     * @throws ApiException {@code PERMISSION_DENIED} when the caller is a person rather than a service
     */
    private static void requireInternal() {
        if (RequestContext.requireActor().kind() == Actor.Kind.USER) {
            throw new ApiException(ErrorCode.PERMISSION_DENIED, "This endpoint is for internal service calls only.");
        }
    }

    /**
     * Builds the console view of one connector from its adapter's tools, its catalog entry and its
     * stored connection, when there is one. A connection of null yields the sandbox view. No tokens are
     * read. A connector missing from the catalog falls back to a minimal entry named after its key.
     *
     * @param apps the providers the workspace has saved an OAuth app for
     */
    private ConnectionView view(String server, Connection connection, java.util.Set<String> apps) {
        List<ToolView> tools = gateway.adapter(server)
                .map(adapter -> adapter.tools().stream()
                        .map(tool -> toView(tool, adapter.asksEveryCall(tool.name())))
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

    /**
     * The sign-in setup shown to administrators, including the redirect address to register.
     *
     * @return null when the connector is not connected by signing in
     */
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

    /**
     * The calling person's identifier, for recording who started a sign-in.
     *
     * @return null when there is no actor or its human identifier is missing or not a valid UUID
     */
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

    /* "Always asks" covers a send or a delete, and a tool whose adapter asks for every call of it. */
    private static ToolView toView(ToolDefinition tool, boolean asksEveryCall) {
        return new ToolView(
                tool.name(),
                tool.qualifiedName(),
                tool.description(),
                tool.sideEffect().name(),
                tool.requiredScopes(),
                tool.alwaysRequiresApproval() || asksEveryCall);
    }

    /**
     * The workspace of the current request.
     *
     * @throws ApiException when the request carries no workspace
     */
    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
