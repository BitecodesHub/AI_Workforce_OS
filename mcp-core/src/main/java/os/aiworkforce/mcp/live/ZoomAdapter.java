// @find: zoom, meetings, video calls, list meetings, get meeting, schedule meeting, cancel meeting, list recordings, server-to-server OAuth, account id client id secret, live adapter, real API
// @what: Live Zoom connector: runs zoom__ tools (meetings and recordings) against the Zoom API using server-to-server OAuth credentials exchanged for a short-lived token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin; invoked through ToolGateway.invoke; base class LiveServerAdapter
package os.aiworkforce.mcp.live;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * Zoom with a Server-to-Server OAuth app.
 *
 * <p>The stored credential is JSON: {@code {"accountId":"...","clientId":"...","clientSecret":"..."}}.
 * It is exchanged for a short-lived access token, which is kept (under a hash of the credential,
 * never the credential itself) until a minute before it expires.
 */
public final class ZoomAdapter extends LiveServerAdapter {

    public static final String BASE_URL = "https://api.zoom.us/v2";
    public static final String TOKEN_URL = "https://zoom.us/oauth/token";

    private static final Duration SAFETY_MARGIN = Duration.ofSeconds(60);
    private static final String DATE = "\\d{4}-\\d{2}-\\d{2}";

    private record CachedToken(String token, Instant expiresAt) {}

    private final String tokenUrl;
    private final Clock clock;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    public ZoomAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        this(sandbox, json, http, baseUrl, TOKEN_URL);
    }

    public ZoomAdapter(
            SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl, String tokenUrl) {
        this(sandbox, json, http, baseUrl, tokenUrl, Clock.systemUTC());
    }

    ZoomAdapter(
            SandboxServerAdapter sandbox,
            ObjectMapper json,
            WebClient.Builder http,
            String baseUrl,
            String tokenUrl,
            Clock clock) {
        super(sandbox, "Zoom", json, http, baseUrl);
        this.tokenUrl = tokenUrl;
        this.clock = clock;
        on("list_meetings", this::listMeetings);
        on("get_meeting", this::getMeeting);
        on("schedule_meeting", this::scheduleMeeting);
        on("cancel_meeting", this::cancelMeeting);
        on("list_recordings", this::listRecordings);
    }

    @Override
    protected void authorize(HttpHeaders headers, String accessToken) {
        headers.setBearerAuth(accessToken);
    }

    @Override
    protected Mono<String> prepare(String credential) {
        String cacheKey = sha256(credential);
        CachedToken cached = tokens.get(cacheKey);
        if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
            return Mono.just(cached.token());
        }
        JsonNode node;
        try {
            node = json.readTree(credential);
        } catch (Exception e) {
            node = null;
        }
        String accountId = node == null ? null : text(node, "accountId");
        String clientId = node == null ? null : text(node, "clientId");
        String clientSecret = node == null ? null : text(node, "clientSecret");
        if (accountId == null || clientId == null || clientSecret == null) {
            return Mono.error(new VendorException(
                    "The Zoom credential must be JSON with the accountId, clientId and clientSecret."));
        }
        return http.post()
                .uri(tokenUrl + "?grant_type=account_credentials&account_id={account}", accountId)
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorMap(WebClientResponseException.class, error -> {
                    int status = error.getStatusCode().value();
                    return status == 400 || status == 401
                            ? new VendorException("Zoom did not accept the account id, client id or client secret. "
                                    + "Check that the Server-to-Server app is activated, then connect Zoom again with all three copied in full.")
                            : error;
                })
                .map(answer -> {
                    String token = answer.path("access_token").asText("");
                    if (token.isEmpty()) {
                        throw new VendorException("Zoom did not return an access token for these credentials.");
                    }
                    long seconds = answer.path("expires_in").asLong(3600);
                    tokens.put(cacheKey, new CachedToken(token, clock.instant().plusSeconds(seconds).minus(SAFETY_MARGIN)));
                    return token;
                });
    }

    /**
     * A cached token Zoom no longer accepts (the app was deactivated and reactivated, or its
     * secret rotated) is dropped and a new one fetched, so a stale token cannot fail every call
     * until it would have expired.
     */
    @Override
    protected Mono<String> renew(String credential) {
        tokens.remove(sha256(credential));
        return prepare(credential);
    }

    @Override
    protected Mono<String> whoAmI(String accessToken) {
        return get(accessToken, "/users/me").map(user -> {
            String name = user.path("display_name").asText(
                    (user.path("first_name").asText("") + " " + user.path("last_name").asText("")).strip());
            String email = user.path("email").asText("");
            String label = name.isEmpty() ? email : email.isEmpty() ? name : name + " (" + email + ")";
            return label.isEmpty() ? "Zoom account" : label;
        });
    }

    // @find: Zoom list meetings, tool zoom__list_meetings, live Zoom call
    private Mono<ToolResult> listMeetings(ToolInvocation invocation, JsonNode arguments, String token) {
        StringBuilder uri = new StringBuilder("/users/me/meetings?type=upcoming&page_size={n}");
        java.util.List<Object> variables = new java.util.ArrayList<>();
        variables.add(limit(arguments, 25, 300));
        dates(arguments, uri, variables);
        return get(token, uri.toString(), variables.toArray()).map(answer -> {
            ArrayNode items = json.createArrayNode();
            answer.path("meetings").forEach(meeting -> items.add(meeting(meeting)));
            return done(listOf(items), "Read " + items.size() + " Zoom meeting(s).");
        });
    }

    // @find: Zoom get meeting, tool zoom__get_meeting, live Zoom call
    private Mono<ToolResult> getMeeting(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = required(arguments, "id");
        return get(token, "/meetings/{id}", id).map(meeting -> {
            ObjectNode item = meeting(meeting);
            item.put("agenda", meeting.path("agenda").asText(""));
            item.put("status", meeting.path("status").asText(null));
            return done(item, "Read the Zoom meeting \"" + item.path("title").asText() + "\".");
        });
    }

    // @find: Zoom schedule meeting, tool zoom__schedule_meeting, live Zoom call
    private Mono<ToolResult> scheduleMeeting(ToolInvocation invocation, JsonNode arguments, String token) {
        String topic = required(arguments, "topic");
        String start = required(arguments, "start");
        ObjectNode body = json.createObjectNode();
        body.put("topic", topic);
        body.put("type", 2);
        body.put("start_time", start);
        body.put("duration", Math.max(1, arguments.path("durationMinutes").asInt(30)));
        String agenda = text(arguments, "agenda");
        if (agenda != null) {
            body.put("agenda", agenda);
        }
        return send(HttpMethod.POST, token, body, "/users/me/meetings").map(meeting -> {
            ObjectNode item = meeting(meeting);
            if (item.path("title").asText().isEmpty()) {
                item.put("title", topic);
            }
            return done(item, "Scheduled the Zoom meeting \"" + topic + "\". Nobody is invited until the link is shared.");
        });
    }

    // @find: Zoom cancel meeting, tool zoom__cancel_meeting, live Zoom call
    private Mono<ToolResult> cancelMeeting(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = required(arguments, "id");
        return call(HttpMethod.DELETE, token, "/meetings/{id}", id).map(ignored -> {
            ObjectNode item = json.createObjectNode();
            item.put("id", id);
            item.put("cancelled", true);
            return done(item, "Cancelled Zoom meeting " + id + ".");
        });
    }

    // @find: Zoom list recordings, tool zoom__list_recordings, live Zoom call
    private Mono<ToolResult> listRecordings(ToolInvocation invocation, JsonNode arguments, String token) {
        StringBuilder uri = new StringBuilder("/users/me/recordings?page_size={n}");
        java.util.List<Object> variables = new java.util.ArrayList<>();
        variables.add(limit(arguments, 25, 300));
        dates(arguments, uri, variables);
        return get(token, uri.toString(), variables.toArray()).map(answer -> {
            ArrayNode items = json.createArrayNode();
            answer.path("meetings").forEach(recording -> {
                ObjectNode item = items.addObject();
                item.put("id", recording.path("id").asText());
                item.put("title", recording.path("topic").asText());
                item.put("startTime", recording.path("start_time").asText());
                item.put("duration", recording.path("duration").asInt());
                item.put("files", recording.path("recording_files").size());
                item.put("url", recording.path("share_url").asText(null));
            });
            return done(listOf(items), "Read " + items.size() + " Zoom recording(s).");
        });
    }

    private static void dates(JsonNode arguments, StringBuilder uri, java.util.List<Object> variables) {
        for (String field : new String[] {"from", "to"}) {
            String value = text(arguments, field);
            if (value != null) {
                if (!value.matches(DATE)) {
                    throw new VendorException("The " + field + " date must look like 2026-10-31.");
                }
                uri.append('&').append(field).append("={").append(field).append('}');
                variables.add(value);
            }
        }
    }

    /* Never includes start_url: it carries the host's sign-in. */
    private ObjectNode meeting(JsonNode meeting) {
        ObjectNode item = json.createObjectNode();
        item.put("id", meeting.path("id").asText());
        item.put("title", meeting.path("topic").asText());
        item.put("startTime", meeting.path("start_time").asText(null));
        item.put("duration", meeting.path("duration").asInt());
        item.put("timezone", meeting.path("timezone").asText(null));
        item.put("join_url", meeting.path("join_url").asText(null));
        return item;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
