// @find: tool credential, connector token, integrations service, OAuth token for tool server, is live, credential lookup, MCP server credential
// @what: Fetches the credential for one tool server from the integrations service.
// @flow: Called by AgentRunner when a tool call executes
package os.aiworkforce.orchestrator.service;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.platform.config.PlatformProperties;

/**
 * Fetches the credential for one tool server from the integrations service.
 *
 * <p>Separate from the model-provider resolver because the two have different owners and
 * different lifetimes: a provider key is set once by an administrator, while a tool credential is
 * an OAuth token that refreshes on its own schedule and can be revoked by the person who granted
 * it.
 *
 * <p>The answer has three states, not two, because "nothing is stored" and "the store could not be
 * asked" call for opposite behaviour. A live connector with no token answers from practice data,
 * which is what a workspace that never connected it expects. A live connector whose token could not
 * be read must not do the same: a workspace with GitHub connected would get seeded sandbox issues
 * back as if they were real, and an approved send would "succeed" without leaving the machine.
 */
@Service
public class ToolCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(ToolCredentialResolver.class);

    /** What the model and the trace are told when the store could not be asked. */
    public static final String UNAVAILABLE_MESSAGE =
            "Could not reach the connection store; nothing was read or sent. Try again shortly.";

    /** What a lookup found. Sealed, so callers (AgentRunner, GitHubContextController) must handle every state. */
    public sealed interface Lookup permits Connected, NotConnected, ReconnectRequired, Unavailable {}

    /** A live token is stored for the server. */
    public record Connected(String token) implements Lookup {

        /** Never the token itself, so a lookup that reaches a log line cannot leak it. */
        @Override
        public String toString() {
            return "Connected[token=<redacted>]";
        }
    }

    /**
     * Nothing is stored, or the server only ever answers from practice data. The call goes ahead
     * against the sandbox.
     */
    public record NotConnected() implements Lookup {}

    /**
     * A connection is stored but the provider no longer accepts it, so only an administrator
     * connecting the account again can fix it. The call must not go ahead, and must not fall back
     * to practice data.
     */
    public record ReconnectRequired(String message) implements Lookup {
        public ReconnectRequired {
            message = message == null || message.isBlank() ? null : message;
        }
    }

    /** The connection store could not be asked. The call must not go ahead. */
    public record Unavailable(String reason) implements Lookup {}

    // Shared instance: the record has no state.
    static final NotConnected NOT_CONNECTED = new NotConnected();

    private final WebClient client;
    private final InternalTokenProvider tokens;
    private final ToolGateway gateway;
    /** How long to wait before the one retry; short, to ride out a restart of the store. */
    private Duration retryDelay = Duration.ofMillis(300);

    /**
     * Builds a client for the integrations service.
     *
     * @param builder Spring's shared WebClient builder
     * @param properties platform configuration: integrations service URL
     * @param tokens mints the service token for each lookup
     * @param gateway tells whether a server is a sandbox or built-in adapter
     */
    public ToolCredentialResolver(
            WebClient.Builder builder,
            PlatformProperties properties,
            InternalTokenProvider tokens,
            ToolGateway gateway) {
        this.client = builder.baseUrl(properties.services().integrations()).build();
        this.tokens = tokens;
        this.gateway = gateway;
    }

    /** For tests, which should not wait for the retry. */
    void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    // @find: resolve tool credential
    /**
     * The credential for {@code server} in workspace {@code orgId}.
     *
     * <p>A server whose adapter only ever answers from practice data is not looked up at all: it
     * would ignore a token anyway, and an outage of the store must not break every demo tool. A
     * 2xx answer with no value is {@link NotConnected}. Anything else - a timeout, any 4xx or 5xx,
     * a token that could not be minted - is tried once more after a short pause, then reported
     * as {@link Unavailable}. Blocks the calling thread for up to two 5 second requests plus the pause.
     * Performs one HTTP read and no writes. Called by AgentRunner before a tool call executes and by
     * the chat GitHub context controller.
     *
     * @param orgId the workspace whose connection is wanted
     * @param server the tool server name, for example {@code "github"}
     * @return never null; one of the four {@link Lookup} states
     */
    public Lookup resolve(UUID orgId, String server) {
        if (!liveCapable(server)) {
            return NOT_CONNECTED;
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0 && !pause()) {
                break;
            }
            try {
                CredentialResponse answer = fetch(orgId, server);
                if (answer != null && "reconnect_required".equals(answer.state())) {
                    return new ReconnectRequired(answer.message());
                }
                if (answer != null && "unreadable".equals(answer.state())) {
                    throw new IllegalStateException("the stored credential could not be read");
                }
                String value = answer == null ? null : answer.value();
                return value == null || value.isBlank() ? NOT_CONNECTED : new Connected(value);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        String reason = describe(last);
        // The message and the exception's own text name the server and the URL, never the token.
        log.warn("Could not read the credential for {} in workspace {}, after one retry: {}", server, orgId, reason);
        return new Unavailable(reason);
    }

    // @find: is tool server live
    /** Whether this server has a real connector behind it, so a missing token is a change and not the default. */
    public boolean isLive(String server) {
        return liveCapable(server);
    }

    // A server is live-capable only when its adapter is registered and is neither a sandbox nor built-in; an unknown server is treated as not live.
    private boolean liveCapable(String server) {
        // A built-in server (the web browser, phone calls) has nothing stored for it to look up.
        return gateway.adapter(server).map(adapter -> !adapter.isSandbox() && !adapter.isBuiltIn()).orElse(false);
    }

    // One blocking GET of the stored credential, with the workspace header and a service token, 5 second timeout. Throws on any HTTP or transport failure; the caller retries.
    private CredentialResponse fetch(UUID orgId, String server) {
        return client.get()
                .uri("/internal/connections/{server}/credential", server)
                .header("X-Workspace-Id", orgId.toString())
                .header("Authorization", "Bearer " + tokens.forService("integrations"))
                .retrieve()
                .bodyToMono(CredentialResponse.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    /** False when the thread was interrupted while waiting, so the caller stops retrying. */
    private boolean pause() {
        try {
            Thread.sleep(retryDelay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // Short reason text for logs and the Unavailable result. Never includes the token.
    private static String describe(RuntimeException e) {
        if (e == null) {
            return "the lookup was interrupted";
        }
        return e.getMessage() == null
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    // The integrations service's reply: value is the token; state is empty, reconnect_required or unreadable; message explains reconnect_required.
    private record CredentialResponse(String value, String state, String message) {}
}
