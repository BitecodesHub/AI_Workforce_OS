package os.aiworkforce.mcp.spi;

import reactor.core.publisher.Mono;

/**
 * How an OAuth connector asks the connection store for a new access token after the provider
 * rejected the one it was given.
 *
 * <p>The store (the integrations service) owns the refresh token, so an adapter never sees it. It
 * answers with the credential the adapter should use now, or empty when the sign-in can no longer
 * be renewed, and it is told when a connection can only be fixed by connecting the account again.
 */
public interface TokenRefresher {

    /**
     * Gets a new credential for the connection, given the one the provider just rejected.
     *
     * <p>Empty means the store could not renew the sign-in. When another caller has already
     * renewed it, the store answers with that newer credential instead of renewing twice.
     */
    Mono<String> refresh(String orgId, String server, String rejectedCredential);

    /** Marks the connection as needing an administrator to connect the account again. */
    Mono<Void> markReconnectRequired(String orgId, String server, String reason);
}
