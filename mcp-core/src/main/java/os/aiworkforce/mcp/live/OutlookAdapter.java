package os.aiworkforce.mcp.live;

import java.time.LocalDate;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/** Outlook mail and calendar through Microsoft Graph with an OAuth access token. */
public final class OutlookAdapter extends OAuthAdapter {

    public static final String BASE_URL = "https://graph.microsoft.com/v1.0";

    private static final String LIST_FIELDS = "id,subject,from,bodyPreview,receivedDateTime,isRead";
    private static final int BODY_LIMIT = 50_000;

    public OutlookAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Outlook", json, http, baseUrl);
        on("list_messages", this::listMessages);
        on("get_message", this::getMessage);
        on("draft_message", this::draftMessage);
        on("send_message", this::sendMessage);
        on("delete_message", this::deleteMessage);
        on("list_events", this::listEvents);
    }

    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, "/me").map(me -> {
            String label = me.path("mail").asText("");
            if (label.isEmpty()) {
                label = me.path("userPrincipalName").asText("");
            }
            return label.isEmpty() ? "Outlook account" : label;
        });
    }

    private Mono<ToolResult> listMessages(ToolInvocation invocation, JsonNode arguments, String token) {
        String query = text(arguments, "query");
        int top = limit(arguments, 10, 50);
        // Graph refuses $search together with $orderby, so a search keeps Graph's own ranking.
        Mono<JsonNode> answer = query == null
                ? get(
                        token,
                        "/me/messages?$top={top}&$select={fields}&$orderby={order}",
                        top,
                        LIST_FIELDS,
                        "receivedDateTime desc")
                : get(
                        token,
                        "/me/messages?$top={top}&$select={fields}&$search={search}",
                        top,
                        LIST_FIELDS,
                        "\"" + query.replace("\"", " ") + "\"");
        return answer.map(result -> {
            ArrayNode items = json.createArrayNode();
            result.path("value").forEach(message -> {
                ObjectNode item = items.addObject();
                item.put("id", message.path("id").asText());
                item.put("from", message.path("from").path("emailAddress").path("address").asText(""));
                item.put("subject", message.path("subject").asText(""));
                item.put("snippet", message.path("bodyPreview").asText(""));
                item.put("receivedAt", message.path("receivedDateTime").asText(""));
                item.put("read", message.path("isRead").asBoolean(false));
            });
            return done(listOf(items), "Read " + items.size() + " message(s) from Outlook.");
        });
    }

    private Mono<ToolResult> getMessage(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = required(arguments, "id");
        return get(
                        token,
                        "/me/messages/{id}?$select={fields}",
                        id,
                        "id,subject,from,toRecipients,receivedDateTime,body,isRead")
                .map(message -> {
                    ObjectNode item = json.createObjectNode();
                    item.put("id", message.path("id").asText());
                    item.put("from", message.path("from").path("emailAddress").path("address").asText(""));
                    ArrayNode to = item.putArray("to");
                    message.path("toRecipients")
                            .forEach(r -> to.add(r.path("emailAddress").path("address").asText()));
                    item.put("subject", message.path("subject").asText(""));
                    item.put("receivedAt", message.path("receivedDateTime").asText(""));
                    JsonNode body = message.path("body");
                    String content = body.path("content").asText("");
                    item.put(
                            "body",
                            cap("html".equalsIgnoreCase(body.path("contentType").asText()) ? plain(content) : content,
                                    BODY_LIMIT));
                    return done(item, "Read the Outlook message \"" + message.path("subject").asText("") + "\".");
                });
    }

    private Mono<ToolResult> draftMessage(ToolInvocation invocation, JsonNode arguments, String token) {
        ObjectNode message = message(arguments);
        return send(HttpMethod.POST, token, message, "/me/messages").map(draft -> {
            ObjectNode item = json.createObjectNode();
            item.put("id", draft.path("id").asText());
            item.put("to", text(arguments, "to"));
            item.put("subject", text(arguments, "subject"));
            return done(item, "Saved a draft to " + text(arguments, "to") + " in Outlook.");
        });
    }

    private Mono<ToolResult> sendMessage(ToolInvocation invocation, JsonNode arguments, String token) {
        ObjectNode body = json.createObjectNode();
        body.set("message", message(arguments));
        body.put("saveToSentItems", true);
        return send(HttpMethod.POST, token, body, "/me/sendMail").map(ignored -> {
            ObjectNode item = json.createObjectNode();
            item.put("sent", true);
            item.put("to", text(arguments, "to"));
            item.put("subject", text(arguments, "subject"));
            return done(item, "Sent an email to " + text(arguments, "to") + " from Outlook.");
        });
    }

    private Mono<ToolResult> deleteMessage(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = required(arguments, "id");
        return call(HttpMethod.DELETE, token, "/me/messages/{id}", id).map(ignored -> {
            ObjectNode item = json.createObjectNode();
            item.put("id", id);
            item.put("deleted", true);
            return done(item, "Deleted the message from Outlook.");
        });
    }

    private Mono<ToolResult> listEvents(ToolInvocation invocation, JsonNode arguments, String token) {
        String from = text(arguments, "from");
        String to = text(arguments, "to");
        String start = from == null ? today() + "T00:00:00Z" : startOf(from);
        String end = to == null
                ? LocalDate.now(ZoneOffset.UTC).plusDays(30) + "T23:59:59Z"
                : endOf(to);
        return get(
                        token,
                        "/me/calendarView?startDateTime={start}&endDateTime={end}&$top={top}"
                                + "&$select={fields}&$orderby={order}",
                        start,
                        end,
                        limit(arguments, 50, 100),
                        "id,subject,start,end,attendees",
                        "start/dateTime")
                .map(answer -> {
                    ArrayNode items = json.createArrayNode();
                    answer.path("value").forEach(event -> {
                        ObjectNode item = items.addObject();
                        item.put("id", event.path("id").asText());
                        item.put("title", event.path("subject").asText(""));
                        item.put("start", time(event.path("start")));
                        item.put("end", time(event.path("end")));
                        ArrayNode attendees = item.putArray("attendees");
                        event.path("attendees")
                                .forEach(a -> attendees.add(a.path("emailAddress").path("address").asText()));
                    });
                    return done(listOf(items), "Read " + items.size() + " event(s) from Outlook.");
                });
    }

    private ObjectNode message(JsonNode arguments) {
        java.util.List<String> to = recipients(required(arguments, "to"));
        ObjectNode message = json.createObjectNode();
        message.put("subject", noLineBreaks("subject", required(arguments, "subject")));
        ObjectNode body = message.putObject("body");
        body.put("contentType", "Text");
        body.put("content", required(arguments, "body"));
        ArrayNode recipients = message.putArray("toRecipients");
        to.forEach(address -> recipients.addObject().putObject("emailAddress").put("address", address));
        return message;
    }

    private static String time(JsonNode time) {
        String value = time.path("dateTime").asText("");
        boolean utc = "UTC".equalsIgnoreCase(time.path("timeZone").asText());
        return utc && !value.isEmpty() ? withZone(value) : value;
    }
}
