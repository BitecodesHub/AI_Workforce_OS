package os.aiworkforce.mcp.live;

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

/** The Google Calendar API (the primary calendar) with an OAuth access token. */
public final class CalendarAdapter extends OAuthAdapter {

    public static final String BASE_URL = "https://www.googleapis.com/calendar/v3";

    private static final String EVENTS = "/calendars/primary/events";

    public CalendarAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Google Calendar", json, http, baseUrl);
        on("list_events", this::listEvents);
        on("create_event", this::createEvent);
        on("delete_event", this::deleteEvent);
    }

    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, "/users/me/calendarList/primary").map(calendar -> {
            String name = calendar.path("summary").asText("");
            if (name.isEmpty()) {
                name = calendar.path("id").asText("");
            }
            return name.isEmpty() ? "Google Calendar" : name;
        });
    }

    private Mono<ToolResult> listEvents(ToolInvocation invocation, JsonNode arguments, String token) {
        String from = text(arguments, "from");
        String to = text(arguments, "to");
        StringBuilder uri = new StringBuilder(EVENTS + "?singleEvents=true&orderBy=startTime&maxResults=100");
        java.util.List<Object> variables = new java.util.ArrayList<>();
        // Without a start Google lists from the calendar's first event ever, so the oldest
        // hundred events would come back; the range starts today unless one is given.
        uri.append("&timeMin={min}");
        variables.add(from == null ? today() + "T00:00:00Z" : startOf(from));
        if (to != null) {
            uri.append("&timeMax={max}");
            variables.add(endOf(to));
        }
        return get(token, uri.toString(), variables.toArray()).map(answer -> {
            ArrayNode items = json.createArrayNode();
            answer.path("items").forEach(event -> items.add(event(event)));
            return done(listOf(items), "Read " + items.size() + " event(s) from Google Calendar.");
        });
    }

    private Mono<ToolResult> createEvent(ToolInvocation invocation, JsonNode arguments, String token) {
        ObjectNode body = json.createObjectNode();
        body.put("summary", required(arguments, "title"));
        putTime(body.putObject("start"), required(arguments, "start"));
        putTime(body.putObject("end"), required(arguments, "end"));
        JsonNode attendees = arguments.path("attendees");
        if (attendees.isArray() && !attendees.isEmpty()) {
            ArrayNode list = body.putArray("attendees");
            attendees.forEach(attendee -> {
                String email = attendee.asText("").strip();
                if (!email.isEmpty()) {
                    list.addObject().put("email", noLineBreaks("attendee", email));
                }
            });
        }
        return send(HttpMethod.POST, token, body, EVENTS)
                .map(event -> done(event(event), "Created \"" + body.path("summary").asText() + "\" in Google Calendar."));
    }

    private Mono<ToolResult> deleteEvent(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = required(arguments, "id");
        return call(HttpMethod.DELETE, token, EVENTS + "/{id}", id).map(ignored -> {
            ObjectNode item = json.createObjectNode();
            item.put("id", id);
            item.put("deleted", true);
            return done(item, "Deleted the event from Google Calendar.");
        });
    }

    private static void putTime(ObjectNode target, String value) {
        if (isDate(value)) {
            target.put("date", value);
        } else {
            target.put("dateTime", withZone(value));
        }
    }

    private ObjectNode event(JsonNode event) {
        ObjectNode item = json.createObjectNode();
        item.put("id", event.path("id").asText());
        item.put("title", event.path("summary").asText(""));
        item.put("start", time(event.path("start")));
        item.put("end", time(event.path("end")));
        ArrayNode attendees = item.putArray("attendees");
        event.path("attendees").forEach(attendee -> attendees.add(attendee.path("email").asText()));
        return item;
    }

    private static String time(JsonNode time) {
        return time.hasNonNull("dateTime") ? time.path("dateTime").asText() : time.path("date").asText("");
    }
}
