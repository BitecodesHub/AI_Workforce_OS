// @find: slack, channels, chat, messages, list channels, get messages, post message, bot token, live adapter, real API, communication
// @what: Live Slack connector: runs slack__ tools (list channels, get messages, post message) against the Slack Web API with a bot token; posting always needs approval.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin (case "slack"); ToolGateway.invoke reaches it through LiveServerAdapter.invoke, and post_message passes the approval gate first; each tool makes one or two calls to slack.com/api; base class LiveServerAdapter
package os.aiworkforce.mcp.live;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * Slack's Web API with a bot token.
 *
 * <p>Slack answers most failures with HTTP 200 and {@code "ok": false}, so every response is
 * checked for that before it is trusted.
 *
 * <p>Used by the tool gateway when a workspace has stored a Slack bot token. list_channels and
 * get_messages are read tools; post_message is outbound and always asks a person first, because it
 * publishes text in a channel. A post is not idempotent, so a dropped connection is reported as
 * indeterminate and the post is never repeated.
 *
 * <p>Failures: a missing argument or a Slack error code becomes a plain failed result, with a
 * specific sentence for a rejected token, an unknown channel, a missing permission and a rate limit.
 * Nothing is retried here.
 */
public final class SlackAdapter extends LiveServerAdapter {

    /** The production Slack Web API address; tests pass another base URL to the constructor. */
    public static final String BASE_URL = "https://slack.com/api";

    /** Registers the three Slack tools. The sandbox supplies the tool definitions. */
    public SlackAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Slack", json, http, baseUrl);
        on("list_channels", this::listChannels);
        on("get_messages", this::getMessages);
        on("post_message", this::postMessage);
    }

    /** Sends the bot token as a bearer token. */
    @Override
    protected void authorize(HttpHeaders headers, String token) {
        headers.setBearerAuth(token);
    }

    /** Connection check: calls auth.test and answers the bot user with the workspace name. */
    @Override
    protected Mono<String> whoAmI(String token) {
        return api(token, "auth.test", Map.of()).map(body -> {
            String user = body.path("user").asText("");
            String team = body.path("team").asText("");
            return user.isEmpty() ? team : team.isEmpty() ? user : user + " in " + team;
        });
    }

    // @find: Slack list channels, tool slack__list_channels, live Slack call
    /**
     * Read tool. Calls conversations.list for public, non-archived channels, at most the first 200.
     * Private channels and direct messages are not listed.
     */
    private Mono<ToolResult> listChannels(ToolInvocation invocation, JsonNode arguments, String token) {
        return api(token, "conversations.list", channelQuery()).map(body -> {
            ArrayNode items = json.createArrayNode();
            body.path("channels").forEach(channel -> {
                ObjectNode item = json.createObjectNode();
                item.put("id", channel.path("id").asText());
                item.put("name", channel.path("name").asText());
                item.put("topic", channel.path("topic").path("value").asText());
                item.put("members", channel.path("num_members").asInt());
                item.put("appIsMember", channel.path("is_member").asBoolean());
                items.add(item);
            });
            return done(listOf(items), "Read " + items.size() + " channel(s) from Slack.");
        });
    }

    // @find: Slack get messages, tool slack__get_messages, live Slack call
    /**
     * Read tool. Resolves the channel to an id (one extra list call when a name is given), then calls
     * conversations.history for at most 100 messages. The app must be a member of the channel.
     */
    private Mono<ToolResult> getMessages(ToolInvocation invocation, JsonNode arguments, String token) {
        String channel = required(arguments, "channel");
        int limit = limit(arguments, 20, 100);
        return channelId(token, channel)
                .flatMap(id -> api(token, "conversations.history", Map.of("channel", id, "limit", String.valueOf(limit))))
                .map(body -> {
                    ArrayNode items = json.createArrayNode();
                    body.path("messages").forEach(message -> {
                        ObjectNode item = json.createObjectNode();
                        item.put("id", message.path("ts").asText());
                        item.put("user", message.path("user").asText(message.path("username").asText()));
                        item.put("text", message.path("text").asText());
                        item.put("replies", message.path("reply_count").asInt());
                        items.add(item);
                    });
                    return done(listOf(items), "Read " + items.size() + " message(s) from #" + bare(channel) + " in Slack.");
                });
    }

    // @find: Slack post message, tool slack__post_message, live Slack call
    /**
     * Outbound tool; a person always approves it first. Calls chat.postMessage with the channel and
     * text as given, so the message appears under the name of the bot. A leading # on the channel is
     * removed. Slack answers HTTP 200 with ok false on failure, which is turned into a plain message.
     */
    private Mono<ToolResult> postMessage(ToolInvocation invocation, JsonNode arguments, String token) {
        String channel = bare(required(arguments, "channel"));
        ObjectNode body = json.createObjectNode();
        body.put("channel", channel);
        body.put("text", required(arguments, "text"));
        return http.post()
                .uri("/chat.postMessage")
                .headers(headers -> authorize(headers, token))
                .contentType(new MediaType(MediaType.APPLICATION_JSON, java.nio.charset.StandardCharsets.UTF_8))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(SlackAdapter::requireOk)
                .map(answer -> {
                    ObjectNode result = json.createObjectNode();
                    result.put("id", answer.path("ts").asText());
                    result.put("channel", answer.path("channel").asText());
                    result.put("text", body.path("text").asText());
                    return done(result, "Posted a message to #" + channel + " in Slack.");
                });
    }

    /* conversations.history needs an id; people and models usually say #general. */
    /** Returns the id for a channel reference: an id as given, or the id found by name among the listed public channels. */
    private Mono<String> channelId(String token, String channel) {
        String name = bare(channel);
        if (name.matches("^[CGD][A-Z0-9]{8,}$")) {
            return Mono.just(name);
        }
        return api(token, "conversations.list", channelQuery()).map(body -> StreamSupport.stream(
                        body.path("channels").spliterator(), false)
                .filter(candidate -> name.equalsIgnoreCase(candidate.path("name").asText()))
                .map(candidate -> candidate.path("id").asText())
                .findFirst()
                .orElseThrow(() -> new VendorException("Slack has no channel named #" + name
                        + " that the app can see. Check the name, and invite the app to the channel.")));
    }

    /** GET one Slack Web API method with query parameters, and refuse an answer whose ok field is false. */
    private Mono<JsonNode> api(String token, String method, Map<String, String> query) {
        return http.get()
                .uri(builder -> {
                    builder.path("/" + method);
                    query.forEach(builder::queryParam);
                    return builder.build();
                })
                .headers(headers -> authorize(headers, token))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(SlackAdapter::requireOk);
    }

    /** The query that lists up to 200 public, non-archived channels. */
    private static Map<String, String> channelQuery() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("limit", "200");
        query.put("exclude_archived", "true");
        query.put("types", "public_channel");
        return query;
    }

    /**
     * Passes on an answer with ok true. Otherwise throws a plain sentence chosen from the Slack error
     * code: rejected token, unknown channel, app not in channel, archived channel, missing permission,
     * rate limit, or a generic message with the code.
     */
    private static JsonNode requireOk(JsonNode body) {
        if (body.path("ok").asBoolean(false)) {
            return body;
        }
        String code = body.path("error").asText("unknown_error");
        throw new VendorException(
                switch (code) {
                    case "invalid_auth", "not_authed", "token_revoked", "token_expired", "account_inactive" ->
                        "Slack rejected the stored token. An administrator needs to connect Slack again.";
                    case "channel_not_found" ->
                        "Slack could not find that channel. Check the name, and invite the app to the channel.";
                    case "not_in_channel" ->
                        "The Slack app is not in that channel. Invite it by typing /invite and the app's name there.";
                    case "is_archived" -> "That Slack channel is archived.";
                    case "missing_scope" ->
                        "The Slack app is missing a permission for this action ("
                                + body.path("needed").asText("unknown") + "). Add it and reinstall the app.";
                    case "ratelimited" -> "Slack is limiting requests right now. Try again in a minute.";
                    default -> "Slack did not accept the request (" + code + ").";
                });
    }

    /** Removes a leading # from a channel name. */
    private static String bare(String channel) {
        return channel.startsWith("#") ? channel.substring(1) : channel;
    }
}
