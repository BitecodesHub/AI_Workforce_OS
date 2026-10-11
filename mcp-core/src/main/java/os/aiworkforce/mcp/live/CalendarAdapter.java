// @find: google calendar, calendar, events, list events, create event, delete event, meetings, schedule, OAuth, live adapter, real API, Calendar API
// @what: Live Google Calendar connector: runs calendar__ tools (list, create, delete events) against the Google Calendar API with an OAuth access token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin (case "calendar"); ToolGateway.invoke reaches it through LiveServerAdapter.invoke; OAuthAdapter supplies the bearer token and the one renew-and-retry after a 401; each tool makes one call to www.googleapis.com; base class OAuthAdapter
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

/**
 * The Google Calendar API (the primary calendar) with an OAuth access token.
 *
 * <p>Used by the tool gateway when a workspace has connected a Google account. list_events is a read
 * tool, create_event is a write tool (a person is asked first when the workspace policy says so),
 * and delete_event is destructive and always asks a person first.
 *
 * <p>Failures: missing arguments become a plain failed result; a 401 triggers one token renewal
 * in the base class and, if that fails, a request to reconnect. No other failure is retried.
 */
public final class CalendarAdapter extends OAuthAdapter {

    /** The production Google Calendar API address; tests pass another base URL to the constructor. */
    public static final String BASE_URL = "https://www.googleapis.com/calendar/v3";

    /** The events collection of the primary calendar, the only calendar this connector touches. */
    private static final String EVENTS = "/calendars/primary/events";

    /** Registers the three calendar tools. The sandbox supplies the tool definitions. */
    public CalendarAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Google Calendar", json, http, baseUrl);
        on("list_events", this::listEvents);
        on("create_event", this::createEvent);
        on("delete_event", this::deleteEvent);
    }

    /** Connection check: GET the primary calendar entry; answers its name, or its id, or a generic label. */
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

    // @find: Google Calendar list events, tool calendar__list_events, live Google Calendar call
    /**
     * Read tool. GET the events of the primary calendar, ordered by start time, at most 100.
     * The range starts today (UTC) when no from date is given and has no end when to is omitted.
     * Recurring events are expanded into single occurrences.
     */
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

    // @find: Google Calendar create event, tool calendar__create_event, live Google Calendar call
    /**
     * Write tool. POST a new event to the primary calendar. A plain date makes an all-day event; a
     * date-time without an offset is taken as UTC. Attendee addresses are checked for line breaks.
     * No notification parameter is set, so Google applies its default for invitations. Not idempotent:
     * a dropped connection is reported as indeterminate and is not repeated.
     */
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

    // @find: Google Calendar delete event, tool calendar__delete_event, live Google Calendar call
    /**
     * Destructive tool; a person always approves it first. DELETE one event of the primary calendar by
     * id. The answer only confirms the id.
     */
    private Mono<ToolResult> deleteEvent(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = required(arguments, "id");
        return call(HttpMethod.DELETE, token, EVENTS + "/{id}", id).map(ignored -> {
            ObjectNode item = json.createObjectNode();
            item.put("id", id);
            item.put("deleted", true);
            return done(item, "Deleted the event from Google Calendar.");
        });
    }

    /** Writes an all-day {@code date} or a timed {@code dateTime} into a start or end object. */
    private static void putTime(ObjectNode target, String value) {
        if (isDate(value)) {
            target.put("date", value);
        } else {
            target.put("dateTime", withZone(value));
        }
    }

    /** Maps a Google event to the plain shape the tools return: id, title, start, end and attendee addresses. */
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

    /** The date-time of an event boundary, or its date for an all-day event. */
    private static String time(JsonNode time) {
        return time.hasNonNull("dateTime") ? time.path("dateTime").asText() : time.path("date").asText("");
    }
}
