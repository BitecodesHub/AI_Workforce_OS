// @find: live adapter base, real API, connector base class, tool dispatch, check connection, test connection, who am I, health check, token refresh, retry, timeout, indeterminate, vendor error message, gmail, slack, github, jira, stripe, all vendors
// @what: Base class for every live vendor adapter: routes tool calls to the vendor call, checks credentials with a who-am-I request, and turns vendor errors and timeouts into plain results.
// @flow: Extended by each vendor adapter and by OAuthAdapter; wrapped around a SandboxServerAdapter for tools without a live call
package os.aiworkforce.mcp.live;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.core.codec.CodecException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ConnectionCheck;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;
import os.aiworkforce.mcp.spi.McpServerAdapter;

/**
 * A connector that talks to the real provider once a token is stored.
 *
 * <p>It is built around the sandbox for the same server and takes its tool definitions from it,
 * so the live connector offers exactly the tools, scopes and side-effect classes the sandbox
 * declares, and governance cannot change when a workspace connects a real account. With no
 * credential it hands the call to the sandbox, so every agent keeps working in a demo workspace.
 *
 * <p>Vendor failures become a failed result with one plain sentence; the token never appears in
 * a result, a log line or an exception message. A dropped connection on a tool that cannot be
 * safely repeated is reported as indeterminate, because the action may already have happened.
 */
public abstract class LiveServerAdapter implements McpServerAdapter {

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);
    private static final int VENDOR_MESSAGE_LIMIT = 200;
    /* A full email or an exported document is often larger than Spring's 256 KB default. */
    static final int MAX_ANSWER_BYTES = 16 * 1024 * 1024;
    private static final String HIDDEN = "[hidden]";
    private static final Pattern SECRET_FIELD = Pattern.compile("(?i).*(token|secret|password|key).*");

    /** One tool, run against the provider with a credential that is known to be present. */
    @FunctionalInterface
    protected interface Call {
        Mono<ToolResult> run(ToolInvocation invocation, JsonNode arguments, String credential);
    }

    /** A refusal that can be explained in plain words: bad arguments, or the vendor saying no. */
    protected static final class VendorException extends RuntimeException {

        public VendorException(String message) {
            super(message, null, false, false);
        }
    }

    private final SandboxServerAdapter sandbox;
    private final String vendor;
    private final Map<String, Call> calls = new LinkedHashMap<>();
    protected final ObjectMapper json;
    protected final WebClient http;

    protected LiveServerAdapter(
            SandboxServerAdapter sandbox, String vendor, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        this.sandbox = sandbox;
        this.vendor = vendor;
        this.json = json;
        WebClient.Builder builder =
                http.clone().codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_ANSWER_BYTES));
        this.http = baseUrl == null ? builder.build() : builder.baseUrl(baseUrl).build();
    }

    /** Registers the live implementation of one tool. Called from the subclass constructor. */
    protected final void on(String tool, Call call) {
        calls.put(tool, call);
    }

    /** Sets the provider's authentication headers. Never logs the credential. */
    protected abstract void authorize(HttpHeaders headers, String credential);

    /** A real "who am I" call, answering the label of the account the credential belongs to. */
    protected abstract Mono<String> whoAmI(String credential);

    /**
     * Turns the stored credential into the one {@link #authorize} and the calls use.
     *
     * <p>The default is the credential itself. A connector whose stored secret is only the means
     * of getting a token (Zoom's server-to-server app) exchanges it here, and caches the result.
     */
    protected Mono<String> prepare(String credential) {
        return Mono.just(credential);
    }

    /**
     * The base address for this credential, when it depends on it (a Jira site, a Zendesk
     * subdomain, a Salesforce instance); null when the adapter's own base URL applies. A path
     * that starts with a slash is appended to it. Throw {@link VendorException} for an address
     * that fails the {@link Hosts} checks.
     */
    protected String baseFor(String credential) {
        return null;
    }

    /**
     * A new access token after the provider rejected the current one with 401, or empty when the
     * adapter cannot get one itself (the default: OAuth refresh belongs to the integrations
     * service). When it answers, the rejected call is made once more with the new token, which is
     * safe for any tool because a 401 means the provider did not act.
     */
    protected Mono<String> renew(String credential) {
        return Mono.empty();
    }

    /**
     * As {@link #renew(String)}, with the invocation that was rejected (null for a connection
     * check), for an adapter whose renewal needs to know the workspace. An error from here is
     * the call's answer: it replaces the 401.
     */
    protected Mono<String> renewFor(ToolInvocation invocation, String credential) {
        return renew(credential);
    }

    /**
     * Called when the call made with a renewed credential was rejected with 401 as well. Return
     * an error to replace the 401 as the call's answer; the default keeps it.
     */
    protected Mono<Void> rejectedAfterRenewal(ToolInvocation invocation, String credential) {
        return Mono.empty();
    }

    /** The tools this adapter implements against the provider; must equal {@link #tools()}. */
    public Set<String> liveTools() {
        return Set.copyOf(calls.keySet());
    }

    public String vendor() {
        return vendor;
    }

    @Override
    public String server() {
        return sandbox.server();
    }

    @Override
    public List<ToolDefinition> tools() {
        return sandbox.tools();
    }

    @Override
    public boolean isSandbox() {
        return false;
    }

    // @find: run live tool call, invoke tool, vendor request, timeout becomes indeterminate, 401 renew token
    @Override
    public Mono<ToolResult> invoke(ToolInvocation invocation, String credential) {
        if (credential == null || credential.isBlank()) {
            return sandbox.invoke(invocation, credential);
        }
        Call call = calls.get(invocation.tool());
        if (call == null) {
            return Mono.just(ToolResult.failed(vendor + " has no tool named " + invocation.tool() + "."));
        }
        boolean idempotent = tool(invocation.tool()).map(ToolDefinition::idempotent).orElse(false);
        Instant startedAt = Instant.now();
        JsonNode arguments = parse(invocation.argumentsJson());
        String stored = credential.strip();
        Set<String> secrets = secrets(stored);
        return Mono.defer(() -> withRenewal(
                        invocation, stored, secrets, prepared -> call.run(invocation, arguments, prepared)))
                .onErrorResume(error -> explain(error, idempotent))
                .switchIfEmpty(Mono.fromSupplier(() -> empty(idempotent)))
                .map(result -> redacted(timed(result, startedAt), secrets));
    }

    // @find: test connection, check credential, who am I, connect dialog test, POST /api/integrations/connectors/{server}/test
    @Override
    public Mono<ConnectionCheck> check(String credential) {
        if (credential == null || credential.isBlank()) {
            return Mono.just(ConnectionCheck.failed("Paste the token first."));
        }
        String stored = credential.strip();
        Set<String> secrets = secrets(stored);
        return Mono.defer(() -> withRenewal(null, stored, secrets, this::whoAmI))
                .map(ConnectionCheck::passed)
                .timeout(CHECK_TIMEOUT)
                .switchIfEmpty(Mono.fromSupplier(
                        () -> ConnectionCheck.failed(vendor + " sent an empty answer to the check.")))
                .onErrorResume(error -> Mono.just(ConnectionCheck.failed(redact(checkFailure(error), secrets))));
    }

    /*
     * Prepares the credential and runs the work; after a 401, once more with a renewed token.
     *
     * One renewal and one retry, never a loop. Repeating a call that may have reached the provider
     * is unsafe for a tool that is not idempotent (a second email, a second record), so the retry
     * is made only because a 401 proves the provider rejected the request before it processed
     * anything. A dropped connection or a timeout proves no such thing and is never retried.
     */
    private <T> Mono<T> withRenewal(
            ToolInvocation invocation,
            String stored,
            Set<String> secrets,
            java.util.function.Function<String, Mono<T>> work) {
        return prepare(stored)
                .doOnNext(secrets::add)
                .flatMap(work)
                .onErrorResume(
                        WebClientResponseException.Unauthorized.class,
                        rejected -> renewFor(invocation, stored)
                                .map(java.util.Optional::of)
                                .defaultIfEmpty(java.util.Optional.empty())
                                .flatMap(renewed -> {
                                    if (renewed.isEmpty()) {
                                        return Mono.<T>error(rejected);
                                    }
                                    secrets.add(renewed.get());
                                    return work.apply(renewed.get())
                                            .onErrorResume(
                                                    WebClientResponseException.Unauthorized.class,
                                                    again -> rejectedAfterRenewal(invocation, stored)
                                                            .then(Mono.<T>error(again)));
                                }));
    }

    // @find: health check, connection healthy
    @Override
    public Mono<Boolean> healthCheck(String credential) {
        return check(credential).map(ConnectionCheck::ok);
    }

    // ---- HTTP --------------------------------------------------------------------------------

    protected Mono<JsonNode> get(String credential, String uri, Object... variables) {
        return http.get()
                .uri(address(credential, uri), variables)
                .headers(headers -> authorize(headers, credential))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .defaultIfEmpty(json.createObjectNode());
    }

    /** A GET whose answer is plain text, such as a file's contents. */
    protected Mono<String> getText(String credential, String uri, Object... variables) {
        return http.get()
                .uri(address(credential, uri), variables)
                .headers(headers -> authorize(headers, credential))
                .retrieve()
                .bodyToMono(String.class)
                .defaultIfEmpty("");
    }

    protected Mono<JsonNode> send(HttpMethod method, String credential, Object body, String uri, Object... variables) {
        return http.method(method)
                .uri(address(credential, uri), variables)
                .headers(headers -> authorize(headers, credential))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .defaultIfEmpty(json.createObjectNode());
    }

    /** A request with no body, such as DELETE; an empty answer (204) becomes an empty object. */
    protected Mono<JsonNode> call(HttpMethod method, String credential, String uri, Object... variables) {
        return http.method(method)
                .uri(address(credential, uri), variables)
                .headers(headers -> authorize(headers, credential))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .defaultIfEmpty(json.createObjectNode());
    }

    private String address(String credential, String uri) {
        String base = baseFor(credential);
        return base != null && uri.startsWith("/") ? base + uri : uri;
    }

    // ---- Results and arguments ----------------------------------------------------------------

    protected ToolResult done(JsonNode content, String summary) {
        return ToolResult.succeeded(content.toString(), summary, Duration.ZERO);
    }

    protected ObjectNode listOf(ArrayNode items) {
        ObjectNode result = json.createObjectNode();
        result.put("count", items.size());
        result.set("items", items);
        return result;
    }

    /** A trimmed text argument, or null when it is absent or blank. */
    protected static String text(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.isValueNode() ? value.asText().strip() : value.toString();
        return text.isEmpty() ? null : text;
    }

    protected static String required(JsonNode arguments, String field) {
        String value = text(arguments, field);
        if (value == null) {
            throw new VendorException("The " + field + " is needed for this action.");
        }
        return value;
    }

    protected static int limit(JsonNode arguments, int fallback, int max) {
        int requested = arguments.path("limit").asInt(fallback);
        return Math.max(1, Math.min(requested, max));
    }

    // ---- Failures -----------------------------------------------------------------------------

    private Mono<ToolResult> explain(Throwable error, boolean idempotent) {
        if (error instanceof VendorException refusal) {
            return Mono.just(ToolResult.failed(refusal.getMessage()));
        }
        String name = inSentence();
        // Spring wraps a 2xx answer it could not decode (HTML from a proxy) as a response error.
        boolean answered = error instanceof WebClientResponseException response && !response.getStatusCode().isError();
        if (error instanceof WebClientResponseException response && !answered) {
            return Mono.just(ToolResult.failed(describe(response)));
        }
        if (error instanceof WebClientRequestException request) {
            if (unreachable(request)) {
                return Mono.just(ToolResult.failed("Could not reach " + name + ". Try again shortly."));
            }
            boolean slow = timedOut(request);
            return Mono.just(
                    idempotent
                            ? ToolResult.failed(slow
                                    ? capitalised(name) + " did not answer in time. Try again shortly."
                                    : "The connection to " + name + " dropped before it answered.")
                            : ToolResult.indeterminate(
                                    (slow
                                                    ? capitalised(name) + " did not confirm the result in time. "
                                                    : "The connection to " + name
                                                            + " dropped before it confirmed the result. ")
                                            + "The action may or may not have happened, so it was not repeated.",
                                    Duration.ZERO));
        }
        // The gateway's own timeout is classified there, against the tool's declared timeout.
        if (error instanceof TimeoutException) {
            return Mono.error(error);
        }
        // Anything else would reach the gateway, which can only name the exception's class. A
        // provider answer that could not be read still means the provider answered, so for a tool
        // that cannot be repeated the action may have happened.
        String what = causedBy(error, DataBufferLimitException.class)
                ? capitalised(name) + " sent an answer too large to read."
                : answered || causedBy(error, CodecException.class) || causedBy(error, UnsupportedMediaTypeException.class)
                        ? capitalised(name) + " sent an answer that could not be read."
                        : "The request to " + name + " could not be completed.";
        return Mono.just(
                idempotent
                        ? ToolResult.failed(what)
                        : ToolResult.indeterminate(
                                what + " The action may or may not have happened, so it was not repeated.",
                                Duration.ZERO));
    }

    private ToolResult empty(boolean idempotent) {
        String what = capitalised(inSentence()) + " sent an empty answer.";
        return idempotent
                ? ToolResult.failed(what)
                : ToolResult.indeterminate(
                        what + " The action may or may not have happened, so it was not repeated.", Duration.ZERO);
    }

    /* The vendor's name inside a sentence: "the webhook", never "The webhook". */
    private String inSentence() {
        return vendor.startsWith("The ") ? "the " + vendor.substring(4) : vendor;
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** One sentence for a vendor's HTTP refusal, quoting the vendor's own reason where it gave one. */
    protected String describe(WebClientResponseException response) {
        int status = response.getStatusCode().value();
        String detail = vendorMessage(response.getResponseBodyAsString());
        String because = detail == null ? "." : ": " + detail;
        return switch (status) {
            case 401 -> vendor + " rejected the stored token. An administrator needs to connect " + vendor + " again.";
            case 403 -> vendor + " refused this action. The token may not have permission for it" + because;
            case 404 -> vendor + " could not find that item, or the token cannot see it.";
            case 409 -> vendor + " already has that item" + because;
            case 429 -> vendor + " is limiting requests right now. Try again in a minute.";
            default ->
                status >= 500
                        ? vendor + " had a problem answering (status " + status + "). Try again shortly."
                        : vendor + " did not accept the request" + because;
        };
    }

    private String checkFailure(Throwable error) {
        if (error instanceof VendorException refusal) {
            return refusal.getMessage();
        }
        if (error instanceof WebClientResponseException response && !response.getStatusCode().isError()) {
            return capitalised(inSentence()) + " sent an answer to the check that could not be read.";
        }
        if (error instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 401 || status == 403) {
                return vendor + " did not accept this token. Check that it was copied in full and has not been revoked.";
            }
            if (status == 429) {
                return vendor + " is limiting requests right now. Try again in a minute.";
            }
            if (status == 404) {
                // Most often a mistyped site or account: the address answered, but nothing is there.
                return vendor + " could not find that account or site. Check the address and details, then try again.";
            }
            if (status >= 500) {
                return vendor + " had a problem answering the check. Try again shortly.";
            }
            return vendor + " did not accept the check. Check the details, then try again.";
        }
        if (error instanceof WebClientRequestException) {
            return "Could not reach " + inSentence() + ". Check the network and try again.";
        }
        if (error instanceof TimeoutException) {
            return capitalised(inSentence()) + " did not answer within " + CHECK_TIMEOUT.toSeconds() + " seconds.";
        }
        return "The check with " + inSentence() + " could not be completed.";
    }

    /* The message a vendor put in its error body, if it put one in a field we recognise. */
    private String vendorMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(body);
            String message = firstText(
                    node.path("message"),
                    node.path("error"),
                    node.path("error").path("message"),
                    node.path("error_description"),
                    node.path("errors").path(0).path("message"),
                    node.path("errors").path(0).path("detail"),
                    node.path("errorMessages").path(0),
                    node.path("detail"),
                    node.path("description"),
                    node.path(0).path("message"));
            if (message == null) {
                return null;
            }
            String flat = message.replaceAll("\\s+", " ").strip();
            return flat.length() <= VENDOR_MESSAGE_LIMIT ? flat : flat.substring(0, VENDOR_MESSAGE_LIMIT) + "…";
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstText(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate.isTextual() && !candidate.asText().isBlank()) {
                return candidate.asText();
            }
        }
        return null;
    }

    private static boolean unreachable(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof UnknownHostException || current instanceof ConnectException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /* A read timeout inside the HTTP client: Java's own, or Netty's, which is a different type. */
    private static boolean timedOut(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof TimeoutException || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /* The stored credential, and each secret-looking value inside it when it is JSON. */
    private Set<String> secrets(String credential) {
        Set<String> secrets = ConcurrentHashMap.newKeySet();
        secrets.add(credential);
        try {
            JsonNode node = json.readTree(credential);
            if (node != null && node.isObject()) {
                node.fields().forEachRemaining(field -> {
                    if (SECRET_FIELD.matcher(field.getKey()).matches() && field.getValue().isTextual()) {
                        secrets.add(field.getValue().asText().strip());
                    }
                });
            }
        } catch (Exception notJson) {
            // a plain token
        }
        return secrets;
    }

    /* A vendor may quote the token back in its error text; it never reaches a result. */
    private static String redact(String text, Set<String> secrets) {
        if (text == null) {
            return null;
        }
        String clean = text;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 6) {
                clean = clean.replace(secret, HIDDEN);
            }
        }
        return clean;
    }

    private static ToolResult redacted(ToolResult result, Set<String> secrets) {
        String summary = redact(result.summary(), secrets);
        return summary == null || summary.equals(result.summary())
                ? result
                : new ToolResult(result.status(), result.contentJson(), summary, result.duration(), result.metadata());
    }

    private static ToolResult timed(ToolResult result, Instant startedAt) {
        if (!result.duration().isZero()) {
            return result;
        }
        return new ToolResult(
                result.status(),
                result.contentJson(),
                result.summary(),
                Duration.between(startedAt, Instant.now()),
                result.metadata());
    }

    private JsonNode parse(String argumentsJson) {
        try {
            JsonNode node = json.readTree(argumentsJson == null ? "{}" : argumentsJson);
            return node != null && node.isObject() ? node : json.createObjectNode();
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }
}
