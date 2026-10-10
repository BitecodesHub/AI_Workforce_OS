// @find: webhook, outgoing webhook, http post, send event, list events, custom url, endpoint, SSRF, private network block, loopback, live adapter, automation
// @what: Live webhook connector: delivers webhook__send_event as an HTTP POST to the pasted URL after refusing private or internal addresses, and remembers recent deliveries.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin; invoked through ToolGateway.invoke; base class LiveServerAdapter
package os.aiworkforce.mcp.live;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * Sends events as JSON to a web address an administrator connected.
 *
 * <p>The credential is the address itself. A pasted address is a request to make this server
 * call somewhere, so it is held to rules that keep it from reaching anything private: https only,
 * no credentials before the host, and never an address inside a private network. Plain
 * {@code http://localhost} is allowed only outside deployed environments, for testing.
 *
 * <p>The connection check is a syntactic one and never contacts the address. The address is
 * resolved again before every send, so a host name that later points inside the network is
 * refused at that point. Redirects are not followed.
 */
public final class WebhookAdapter extends LiveServerAdapter {

    private static final int KEPT_DELIVERIES = 50;

    private final boolean allowLoopback;
    /* Recent deliveries per workspace, newest first, so an agent can see what it already sent. */
    private final Map<String, Deque<ObjectNode>> deliveries = new ConcurrentHashMap<>();

    public WebhookAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, boolean allowLoopback) {
        super(sandbox, "The webhook", json, http, null);
        this.allowLoopback = allowLoopback;
        on("send_event", this::sendEvent);
        on("list_events", this::listEvents);
    }

    @Override
    protected void authorize(HttpHeaders headers, String url) {
        // The address is the credential; nothing goes in a header.
    }

    @Override
    protected Mono<String> whoAmI(String url) {
        return Mono.fromCallable(() -> {
            String problem = problem(url, allowLoopback);
            if (problem != null) {
                throw new VendorException(problem);
            }
            return URI.create(url).getHost();
        });
    }

    // @find: Webhook send event, tool webhook__send_event, live Webhook call
    private Mono<ToolResult> sendEvent(ToolInvocation invocation, JsonNode arguments, String url) {
        String event = required(arguments, "event");
        String problem = problem(url, allowLoopback);
        if (problem != null) {
            throw new VendorException(problem);
        }
        URI target = URI.create(url);
        ObjectNode payload = json.createObjectNode();
        payload.put("event", event);
        payload.set("data", arguments.path("data").isObject() ? arguments.get("data") : json.createObjectNode());
        payload.put("sentAt", Instant.now().toString());
        payload.put("source", "ai-workforce-os");

        return Mono.fromCallable(() -> resolvedProblem(target.getHost(), allowLoopback))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(refusal -> refusal.isEmpty()
                        ? deliver(invocation, target, payload)
                        : Mono.error(new VendorException(refusal)));
    }

    // @find: Webhook deliver, tool webhook__deliver, live Webhook call
    private Mono<ToolResult> deliver(ToolInvocation invocation, URI target, ObjectNode payload) {
        WebClient.RequestBodySpec request = http.post()
                .uri(target)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.USER_AGENT, "ai-workforce-os");
        if (invocation.idempotencyKey() != null) {
            // Lets a receiver drop a duplicate if this exact event ever arrives twice.
            request = request.header("Idempotency-Key", invocation.idempotencyKey());
        }
        return request.bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .map(response -> {
                    if (response.getStatusCode().is3xxRedirection()) {
                        // Redirects are not followed, so the event never reached where it was sent on to.
                        return ToolResult.failed("The receiving system answered with a redirect (status "
                                + response.getStatusCode().value() + "), which is not followed, so the event "
                                + "was not delivered. Connect the webhook again with its current address.");
                    }
                    ObjectNode delivery = payload.deepCopy();
                    delivery.put("id", "evt_" + Long.toHexString(System.nanoTime()));
                    delivery.put("status", "sent");
                    delivery.put("httpStatus", response.getStatusCode().value());
                    remember(invocation.orgId(), delivery);
                    return done(delivery, "Sent the " + payload.path("event").asText() + " event to "
                            + target.getHost() + ".");
                })
                // Said here rather than by the shared wording: a receiver is not a vendor with tokens.
                .onErrorResume(WebClientResponseException.class, refused -> Mono.just(ToolResult.failed(
                        "The receiving system answered with status " + refused.getStatusCode().value()
                                + ", so the event was not accepted.")));
    }

    // @find: Webhook list events, tool webhook__list_events, live Webhook call
    private Mono<ToolResult> listEvents(ToolInvocation invocation, JsonNode arguments, String url) {
        int limit = limit(arguments, 20, KEPT_DELIVERIES);
        ArrayNode items = json.createArrayNode();
        Deque<ObjectNode> recent = deliveries.getOrDefault(invocation.orgId(), new ArrayDeque<>());
        synchronized (recent) {
            recent.stream().limit(limit).forEach(delivery -> items.add(delivery.deepCopy()));
        }
        return Mono.just(done(
                listOf(items),
                "Listed " + items.size() + " event(s) sent to the webhook since the platform last started."));
    }

    private void remember(String orgId, ObjectNode delivery) {
        Deque<ObjectNode> recent = deliveries.computeIfAbsent(orgId, id -> new ArrayDeque<>());
        synchronized (recent) {
            recent.addFirst(delivery);
            while (recent.size() > KEPT_DELIVERIES) {
                recent.removeLast();
            }
        }
    }

    // ---- Address rules ------------------------------------------------------------------------

    /**
     * Why an address may not be used, or null when it may. Never contacts the address.
     *
     * @param allowLoopback whether {@code localhost} is acceptable, which it is only for testing
     */
    public static String problem(String url, boolean allowLoopback) {
        if (url == null || url.isBlank()) {
            return "Enter the web address that should receive events.";
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (Exception e) {
            return "That is not a valid web address.";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (!uri.isAbsolute() || host == null || host.isBlank() || (!scheme.equals("https") && !scheme.equals("http"))) {
            return "Enter a full web address that starts with https://.";
        }
        if (uri.getRawUserInfo() != null) {
            return "Remove the user name and password from the address.";
        }
        String bareHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        boolean loopback = isLoopbackName(bareHost);
        InetAddress literal = !loopback && looksNumeric(bareHost) ? literal(bareHost) : null;
        if (literal != null && literal.isLoopbackAddress()) {
            loopback = true;
        } else if (literal != null && isPrivate(literal)) {
            return "That address points inside a private network, which webhooks may not reach.";
        }
        if (loopback) {
            return allowLoopback ? null : "Addresses on this computer (localhost) are only allowed while testing.";
        }
        if (!scheme.equals("https")) {
            return "Use an https:// address. Plain http is only allowed for localhost while testing.";
        }
        return null;
    }

    /* At send time: where the host name points now. Empty when every address is acceptable. */
    static String resolvedProblem(String host, boolean allowLoopback) {
        String bareHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        try {
            for (InetAddress address : InetAddress.getAllByName(bareHost)) {
                if (address.isLoopbackAddress()) {
                    if (!allowLoopback) {
                        return "Addresses on this computer (localhost) are only allowed while testing.";
                    }
                } else if (isPrivate(address)) {
                    return "That address points inside a private network, which webhooks may not reach.";
                }
            }
            return "";
        } catch (UnknownHostException e) {
            return "The webhook address could not be found. Check that it is spelled correctly.";
        }
    }

    static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            return first == 0 // "this network"
                    || (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT
                    || (first == 192 && second == 0 && (raw[2] & 0xff) == 0) // protocol assignments
                    || (first == 198 && (second == 18 || second == 19)); // benchmarking
        }
        if (address instanceof Inet6Address) {
            return (raw[0] & 0xfe) == 0xfc; // unique local fc00::/7
        }
        return false;
    }

    private static boolean isLoopbackName(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("localhost") || lower.endsWith(".localhost");
    }

    /* An address written as numbers - 10.0.0.1, ::1, or tricks such as 2130706433 or 0x7f.1. */
    private static boolean looksNumeric(String host) {
        return host.contains(":") || host.matches("[0-9a-fA-FxX.]+");
    }

    private static InetAddress literal(String host) {
        try {
            // A numeric host is parsed, not looked up.
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
