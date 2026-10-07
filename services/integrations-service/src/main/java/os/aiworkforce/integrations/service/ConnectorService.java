package os.aiworkforce.integrations.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import os.aiworkforce.integrations.domain.Connection;
import os.aiworkforce.integrations.oauth.OAuthService;
import os.aiworkforce.integrations.repository.Connections;
import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.mcp.catalog.ConnectorInfo;
import os.aiworkforce.mcp.catalog.CredentialField;
import os.aiworkforce.mcp.model.ConnectionCheck;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.mcp.spi.McpServerAdapter;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.crypto.EnvelopeEncryptionService;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Connecting, checking and disconnecting a workspace's live connectors.
 *
 * <p>A token is checked with the provider before it is stored, so a typo is caught while the
 * administrator is still looking at the form rather than by an agent's first failed call. It is
 * stored encrypted for the workspace and decrypted only for the internal credential endpoint.
 * Neither the token nor its ciphertext is ever logged, audited or returned.
 */
@Service
public class ConnectorService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorService.class);
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(15);

    private final Connections connections;
    private final ToolGateway gateway;
    private final ConnectorCatalog catalog;
    private final EnvelopeEncryptionService encryption;
    private final AuditClient audit;
    private final OAuthService oauth;
    private final ObjectMapper json = new ObjectMapper();

    public ConnectorService(
            Connections connections,
            ToolGateway gateway,
            ConnectorCatalog catalog,
            EnvelopeEncryptionService encryption,
            AuditClient audit,
            OAuthService oauth) {
        this.connections = connections;
        this.gateway = gateway;
        this.catalog = catalog;
        this.encryption = encryption;
        this.audit = audit;
        this.oauth = oauth;
    }

    /** The result of a check, as the console shows it. */
    public record CheckOutcome(boolean ok, String message, Instant checkedAt) {}

    /**
     * Verifies a token with the provider and stores it.
     *
     * @throws ApiException {@code connector_not_live} when the connector has no live adapter,
     *     {@code connector_check_failed} when the provider did not accept the token
     */
    public Connection connect(UUID orgId, String server, String token, String accountLabel) {
        return connect(orgId, server, token, Map.of(), accountLabel);
    }

    /**
     * As {@link #connect(UUID, String, String, String)}, for a connector whose credential has several
     * parts (a site, an email and a token). The parts are stored together as one encrypted JSON value.
     */
    public Connection connect(UUID orgId, String server, String singleToken, Map<String, String> fields, String accountLabel) {
        McpServerAdapter adapter = adapter(server);
        ConnectorInfo info = info(server);
        if (!info.liveAvailable() || adapter.isSandbox()) {
            throw new ApiException(
                            ErrorCode.CONNECTOR_NOT_LIVE,
                            "There is no live connection for " + info.displayName()
                                    + " yet, so agents use practice data for it.")
                    .with("server", server);
        }
        if ("oauth".equals(info.authType())) {
            throw new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            info.displayName() + " is connected by signing in. Choose Connect and approve the request"
                                    + " instead of pasting a token.")
                    .with("server", server);
        }
        String token = credential(info, singleToken, fields);

        ConnectionCheck check = check(adapter, token);
        if (!check.ok()) {
            throw new ApiException(ErrorCode.CONNECTOR_CHECK_FAILED, check.message()).with("server", server);
        }

        String label = accountLabel == null || accountLabel.isBlank() ? check.accountLabel() : accountLabel.strip();
        String encrypted = encryption.encrypt(orgId.toString(), token).serialise();
        UUID by = actorUuid();
        // Two connects at once - a double click, two administrators - both store a checked token,
        // and the later one wins, rather than one of them being told someone else saved first.
        Connection saved = withRetry(() -> {
            Connection connection =
                    connections.findByOrgIdAndServer(orgId, server).orElseGet(() -> fresh(orgId, server));
            connection.setDisplayName(info.displayName());
            connection.connectWithToken(encrypted, label, by);
            return connections.save(connection);
        });

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("server", server);
        if (label != null) {
            detail.put("accountLabel", label);
        }
        audit.record(orgId, actor(), "integration.connect", "integration", server, detail);
        return saved;
    }

    /** Checks the stored token again and records the result. With no token, nothing is checked. */
    public CheckOutcome test(UUID orgId, String server) {
        McpServerAdapter adapter = adapter(server);
        ConnectorInfo info = info(server);
        Optional<Connection> stored = connections.findByOrgIdAndServer(orgId, server).filter(c -> !c.isSandbox());
        if (stored.isEmpty() || stored.get().getCredentialRef() == null) {
            return new CheckOutcome(
                    true,
                    "No token is stored, so agents use practice data for " + info.displayName()
                            + ". Nothing leaves the workspace.",
                    Instant.now());
        }

        Connection connection = stored.get();
        ConnectionCheck check;
        try {
            String credential = oauth.isOAuth(server)
                    ? oauth.credentialFor(orgId, connection).orElseThrow()
                    : encryption.decrypt(orgId.toString(), connection.getCredentialRef());
            // A refresh may have saved the connection since it was read; work from the saved one.
            connection = connections.findByOrgIdAndServer(orgId, server).orElse(connection);
            check = check(adapter, credential);
            if (connection.isReconnectRequired() && !check.ok()) {
                // Keep the plain reason the refresh left behind rather than a generic rejection.
                check = ConnectionCheck.failed(connection.getLastError() == null ? check.message() : connection.getLastError());
            }
        } catch (RuntimeException e) {
            log.warn("Stored credential for {} in workspace {} could not be decrypted", server, orgId);
            check = ConnectionCheck.failed("The stored token could not be read. Connect " + info.displayName() + " again.");
        }
        if (connection.isReconnectRequired()) {
            // A refresh the provider refused is only cleared by a new consent, never by a check.
            connection.setLastCheckedAtNow();
        } else {
            connection.recordCheck(check.ok(), check.ok() ? null : check.message());
        }
        Connection saved;
        try {
            saved = connections.save(connection);
        } catch (OptimisticLockingFailureException e) {
            // The connection changed while the provider was being asked: disconnected, connected
            // again or checked by someone else. What is stored now is what the console shows.
            Optional<Connection> now = connections.findByOrgIdAndServer(orgId, server);
            if (now.isEmpty() || now.get().isSandbox() || now.get().getCredentialRef() == null) {
                return new CheckOutcome(
                        true,
                        info.displayName() + " was disconnected while it was being checked, so agents use practice"
                                + " data. Nothing leaves the workspace.",
                        Instant.now());
            }
            return new CheckOutcome(check.ok(), check.message(), Instant.now());
        }
        log.info("Connection check for {} in workspace {}: {}", server, orgId, check.ok() ? "passed" : "failed");
        return new CheckOutcome(check.ok(), check.message(), saved.getLastCheckedAt());
    }

    /** Forgets the stored token. Disconnecting a connector that was never connected is not an error. */
    public void disconnect(UUID orgId, String server) {
        adapter(server);
        // Retried like connect, so a check saving its result at the same moment cannot turn a
        // disconnect into "someone else saved first".
        boolean wasLive = Boolean.TRUE.equals(withRetry(() -> connections.findByOrgIdAndServer(orgId, server)
                .map(connection -> {
                    boolean live = !connection.isSandbox();
                    connection.disconnect();
                    connections.save(connection);
                    return live;
                })
                .orElse(false)));
        if (wasLive) {
            audit.record(orgId, actor(), "integration.disconnect", "integration", server, Map.of("server", server));
        }
    }

    /**
     * The credential for an internal caller, or empty when agents should use the sandbox.
     *
     * <p>For a sign-in connector this is a fresh access token, renewed first when it is about to
     * expire. A connection that needs a new consent still answers with its stale token, so the call
     * fails with the provider's own refusal instead of silently returning practice data.
     */
    public Optional<String> credential(UUID orgId, String server) {
        Optional<Connection> stored = connections.findByOrgIdAndServer(orgId, server);
        if (oauth.isOAuth(server)) {
            return stored.filter(connection -> !connection.isSandbox() && connection.getCredentialRef() != null)
                    .flatMap(connection -> oauth.credentialFor(orgId, connection));
        }
        return stored.filter(Connection::hasLiveCredential).flatMap(connection -> {
            try {
                return Optional.of(encryption.decrypt(orgId.toString(), connection.getCredentialRef()));
            } catch (RuntimeException e) {
                // Logged without the value: a row that cannot be read is a gap, not a secret.
                log.warn("Stored credential for {} in workspace {} could not be decrypted", server, orgId);
                return Optional.empty();
            }
        });
    }

    /** What an internal caller is told about a connection: its state, and the token when it has one. */
    public record CredentialLookup(String state, String value, String message) {

        public static final String CONNECTED = "connected";
        public static final String NONE = "none";
        public static final String RECONNECT_REQUIRED = "reconnect_required";
        public static final String UNREADABLE = "unreadable";
    }

    /**
     * The credential for an internal caller together with the connection's state.
     *
     * <p>Unlike {@link #credential}, a connection that needs a new consent is reported as such and
     * hands over no token: the caller must fail the call plainly instead of sending a token the
     * provider has already refused. {@code none} means nothing live is stored (never connected, or
     * disconnected); the caller decides from its own record whether that is a change.
     */
    public CredentialLookup lookup(UUID orgId, String server) {
        Optional<Connection> stored = connections.findByOrgIdAndServer(orgId, server);
        if (stored.isEmpty() || stored.get().isSandbox() || stored.get().getCredentialRef() == null) {
            return new CredentialLookup(CredentialLookup.NONE, null, null);
        }
        Connection connection = stored.get();
        if (connection.isReconnectRequired()) {
            return new CredentialLookup(CredentialLookup.RECONNECT_REQUIRED, null, connection.getLastError());
        }
        try {
            Optional<String> value = credential(orgId, server);
            // A refresh inside credential() can mark the connection; read it again.
            Optional<Connection> now = connections.findByOrgIdAndServer(orgId, server);
            if (now.isPresent() && now.get().isReconnectRequired()) {
                return new CredentialLookup(CredentialLookup.RECONNECT_REQUIRED, null, now.get().getLastError());
            }
            return value.map(v -> new CredentialLookup(CredentialLookup.CONNECTED, v, null))
                    .orElseGet(() -> new CredentialLookup(CredentialLookup.UNREADABLE, null, null));
        } catch (RuntimeException e) {
            return new CredentialLookup(CredentialLookup.UNREADABLE, null, null);
        }
    }

    /**
     * A new credential for a sign-in connector after the provider rejected {@code rejected} with
     * 401; empty when the sign-in cannot be renewed, in which case the connection is marked as
     * needing a reconnect. Audited, because it changes what an administrator sees.
     */
    public Optional<String> refreshRejected(UUID orgId, String server, String rejected) {
        if (!oauth.isOAuth(server)) {
            return Optional.empty();
        }
        Optional<Connection> stored = connections.findByOrgIdAndServer(orgId, server);
        if (stored.isEmpty() || stored.get().isSandbox() || stored.get().getCredentialRef() == null) {
            return Optional.empty();
        }
        Optional<String> renewed = oauth.refreshRejected(orgId, stored.get(), rejected);
        if (renewed.isEmpty()) {
            audit.record(orgId, actor(), "integration.reconnect_required", "integration", server,
                    Map.of("server", server, "reason", "refresh failed after the provider rejected the token"));
        }
        return renewed;
    }

    /** Marks a sign-in connector as needing an administrator to connect it again. */
    public void requireReconnect(UUID orgId, String server, String reason) {
        if (!oauth.isOAuth(server)) {
            return;
        }
        connections.findByOrgIdAndServer(orgId, server)
                .filter(connection -> !connection.isSandbox() && !connection.isReconnectRequired())
                .ifPresent(connection -> {
                    oauth.requireReconnect(orgId, connection, reason);
                    audit.record(orgId, actor(), "integration.reconnect_required", "integration", server,
                            Map.of("server", server, "reason", reason == null ? "" : reason));
                });
    }

    /** The one string stored and checked: the token itself, or the parts as JSON in the catalog's order. */
    private String credential(ConnectorInfo info, String singleToken, Map<String, String> fields) {
        List<CredentialField> expected = info.credentialFields();
        if (expected.size() < 2) {
            if (singleToken == null || singleToken.isBlank()) {
                throw ApiException.validation("token", "must not be empty");
            }
            return singleToken.strip();
        }
        ObjectNode parts = json.createObjectNode();
        for (CredentialField field : expected) {
            String value = fields == null ? null : fields.get(field.key());
            if (value == null || value.isBlank()) {
                throw ApiException.validation(field.key(), "must not be empty");
            }
            if (value.length() > 4096) {
                throw ApiException.validation(field.key(), "is too long");
            }
            checkAddress(field.key(), value);
            parts.put(field.key(), value.strip());
        }
        try {
            return json.writeValueAsString(parts);
        } catch (JsonProcessingException e) {
            throw ApiException.internal("The credential could not be prepared", e);
        }
    }

    /*
     * Runs a read-change-save, again from a fresh read when another request saved the same row
     * first or created it at the same moment. A short random pause between tries spreads out
     * requests that arrived together, so they stop colliding with each other.
     */
    private static <T> T withRetry(java.util.function.Supplier<T> change) {
        for (int attempt = 1; ; attempt++) {
            try {
                return change.get();
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
                if (attempt >= 5) {
                    throw e;
                }
                try {
                    Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(10, 60L * attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /*
     * A site or subdomain that cannot be an address of the provider is refused under its own
     * field, before anything is sent, so the form can show the problem next to the box it is in.
     */
    private static void checkAddress(String key, String value) {
        try {
            switch (key) {
                case "site" -> os.aiworkforce.mcp.live.Hosts.atlassianBase(value);
                case "subdomain" -> os.aiworkforce.mcp.live.Hosts.zendeskBase(value);
                default -> {}
            }
        } catch (IllegalArgumentException e) {
            // The sentence is the detail too, so it reads well wherever it is shown.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage())
                    .with("field", key)
                    .with("problem", e.getMessage());
        }
    }

    private ConnectionCheck check(McpServerAdapter adapter, String token) {
        try {
            ConnectionCheck check = adapter.check(token).block(CHECK_TIMEOUT);
            return check == null ? ConnectionCheck.failed("The check did not return a result.") : check;
        } catch (RuntimeException e) {
            return ConnectionCheck.failed("The check could not be completed. Try again shortly.");
        }
    }

    private McpServerAdapter adapter(String server) {
        return gateway.adapter(server).orElseThrow(() -> ApiException.notFound("connector", server));
    }

    private ConnectorInfo info(String server) {
        return catalog.find(server).orElseThrow(() -> ApiException.notFound("connector", server));
    }

    private static Connection fresh(UUID orgId, String server) {
        Connection connection = new Connection();
        connection.setId(UuidV7.generate());
        connection.setOrgId(orgId);
        connection.setServer(server);
        connection.setDisplayName(server);
        return connection;
    }

    private static Actor actor() {
        return RequestContext.actor().orElse(Actor.SYSTEM);
    }

    private static UUID actorUuid() {
        return RequestContext.actor()
                .map(Actor::humanId)
                .flatMap(id -> {
                    try {
                        return Optional.of(UUID.fromString(id));
                    } catch (IllegalArgumentException e) {
                        return Optional.empty();
                    }
                })
                .orElse(null);
    }
}
