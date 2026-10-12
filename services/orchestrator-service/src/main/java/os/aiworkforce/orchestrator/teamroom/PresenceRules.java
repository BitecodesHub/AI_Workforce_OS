// @find: team room presence rules, agent presence, idle, thinking, browsing, using connector, waiting for you, paused, private work, in-flight tool, which run to show, PresenceRules, Team room
// @what: Pure rules that turn a run's latest steps into what its agent is doing now, and pick the one state an agent shows.
// @flow: Called by TeamRoomService with the steps TeamRoomStore read; no database or network access.
package os.aiworkforce.orchestrator.teamroom;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What an agent is doing right now, read from what its runs have written so far.
 *
 * <p>A run writes a {@code model_call} step when the model answers, listing the tool calls it asked
 * for ({@code toolCallRecords}), and then one {@code tool_call} step as each of those calls
 * returns. So, for a run that is {@code running}:
 *
 * <ul>
 *   <li>no model answer yet, or every call of the latest answer has returned: the next model call
 *       is under way, and the agent is <em>thinking</em>;
 *   <li>a call of the latest answer has not returned: that call is under way. A call to the
 *       built-in web browser is <em>browsing</em> (with the site, from the call's own address or
 *       the page the run last had open); any other is <em>using</em> that connector.
 * </ul>
 *
 * <p>A run parked for a person is waiting, whatever its steps say. Everything here is pure: the
 * caller reads the steps and decides who may see what.
 */
final class PresenceRules {

    /** Nothing in progress. */
    static final String IDLE = "idle";
    /** A model call is under way. */
    static final String THINKING = "thinking";
    /** A web browser tool call is under way. */
    static final String BROWSING = "browsing";
    /** A call to any other tool is under way. */
    static final String USING_TOOL = "using_tool";
    /** The run is parked until a person decides an approval. */
    static final String WAITING_APPROVAL = "waiting_approval";
    /** The run is parked until a person answers a question. */
    static final String WAITING_INPUT = "waiting_input";
    /** The agent is paused and has nothing in progress. */
    static final String PAUSED = "paused";
    /** The agent is busy with work this viewer may not see (another person's private conversation). */
    static final String PRIVATE = "private";

    /** The built-in web browser's server name. */
    static final String BROWSER = "browser";

    /** Reads tool-call arguments; a stateless, thread-safe parser. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private PresenceRules() {}

    /** One tool call a model answer asked for, as {@code toolCallRecords} stores it. */
    record CallRecord(String id, String name, String argumentsJson) {}

    /**
     * The facts of one step that presence needs, without its (possibly large) detail document.
     *
     * @param calls the tool calls a {@code model_call} step asked for; empty for any other kind
     * @param toolCallId the call a {@code tool_call} step answers; null for any other kind
     * @param tool the qualified tool name a {@code tool_call} step recorded
     * @param url the page a browser {@code tool_call} step ended on, when it recorded one
     */
    record StepFact(
            UUID runId,
            String kind,
            int position,
            Instant occurredAt,
            List<CallRecord> calls,
            String toolCallId,
            String tool,
            String url) {}

    /**
     * What a running run is doing.
     *
     * @param since when this activity began, as near as the steps say
     * @param server the tool server of the call under way (browsing and using_tool only)
     * @param tool the tool of the call under way, without its server (browsing and using_tool only)
     * @param site the host the browser is on or going to, without "www." (browsing only, when known)
     */
    record Activity(String state, Instant since, String server, String tool, String site) {}

    /** A tool name split into its server and tool. */
    record ToolName(String server, String tool) {

        /**
         * Splits {@code browser.open_page} or the provider form {@code browser__open_page}. The dotted
         * form wins when both appear, because a tool's own name may contain two underscores. A name
         * with neither is all server. Pure; a null or blank name gives two empty strings.
         */
        static ToolName of(String qualified) {
            if (qualified == null || qualified.isBlank()) {
                return new ToolName("", "");
            }
            String name = qualified.strip();
            int dot = name.indexOf('.');
            if (dot > 0) {
                return new ToolName(name.substring(0, dot), name.substring(dot + 1));
            }
            int wire = name.indexOf("__");
            if (wire > 0) {
                return new ToolName(name.substring(0, wire), name.substring(wire + 2));
            }
            return new ToolName(name, "");
        }
    }

    /**
     * What a {@code running} run is doing, from its steps since its latest model answer.
     *
     * <p>Pure. {@code steps} may hold earlier steps too (they are ignored) and may be empty (the
     * first model call is under way). The activity's {@code since} is the latest step's moment, or
     * the run's start when it has none.
     *
     * @param lastPageUrl the page the run's browser last had open, used for a browser call that
     *     names no address of its own (a click, a read); may be null
     */
    static Activity working(Instant runStartedAt, List<StepFact> steps, String lastPageUrl) {
        List<StepFact> ordered = steps.stream()
                .sorted(Comparator.comparingInt(StepFact::position))
                .toList();
        Instant since = ordered.isEmpty() ? runStartedAt : ordered.getLast().occurredAt();
        int latestModel = -1;
        for (int index = 0; index < ordered.size(); index++) {
            if ("model_call".equals(ordered.get(index).kind())) {
                latestModel = index;
            }
        }
        if (latestModel < 0) {
            return new Activity(THINKING, since, null, null, null);
        }
        Set<String> returned = new HashSet<>();
        for (StepFact step : ordered.subList(latestModel + 1, ordered.size())) {
            if ("tool_call".equals(step.kind()) && step.toolCallId() != null) {
                returned.add(step.toolCallId());
            }
        }
        for (CallRecord call : ordered.get(latestModel).calls()) {
            if (call.id() != null && returned.contains(call.id())) {
                continue;
            }
            ToolName name = ToolName.of(call.name());
            if (BROWSER.equals(name.server())) {
                String site = siteOf(argument(call.argumentsJson(), "url"));
                return new Activity(BROWSING, since, name.server(), name.tool(), site != null ? site : siteOf(lastPageUrl));
            }
            return new Activity(USING_TOOL, since, name.server(), name.tool(), null);
        }
        return new Activity(THINKING, since, null, null, null);
    }

    /**
     * Whether the run's call under way needs the page it last had open to name its site: a browser
     * call that carries no address of its own. Lets the caller skip that lookup for every other run.
     */
    static boolean needsLastPage(List<StepFact> steps) {
        Activity activity = working(Instant.EPOCH, steps, null);
        return BROWSING.equals(activity.state()) && activity.site() == null;
    }

    /**
     * The host of an address, lower case and without a leading "www.", for "Browsing youtube.com".
     *
     * <p>Pure. An address without a scheme is read as https. Null for a blank value or one that is
     * not an address with a host.
     */
    static String siteOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String text = url.strip();
        if (!text.contains("://")) {
            text = "https://" + text;
        }
        try {
            String host = URI.create(text).getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException notAnAddress) {
            return null;
        }
    }

    /** A string argument of a tool call, or null when the arguments do not parse or lack it. */
    private static String argument(String argumentsJson, String field) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode value = JSON.readTree(argumentsJson).path(field);
            return value.isTextual() ? value.asText() : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * How strongly a state asks for attention, lowest first: a decision waiting on this viewer, one
     * waiting on someone else, then browsing, using a connector, thinking and private work.
     */
    static int rank(String state, boolean waitingForYou) {
        return switch (state) {
            case WAITING_APPROVAL, WAITING_INPUT -> waitingForYou ? 0 : 1;
            case BROWSING -> 2;
            case USING_TOOL -> 3;
            case THINKING -> 4;
            case PRIVATE -> 5;
            default -> 6;
        };
    }

    /** One of an agent's active runs, ready to be shown. */
    record RunState(
            UUID runId,
            String state,
            Instant since,
            boolean waitingForYou,
            boolean hidden,
            UUID goalId,
            String goalTitle,
            UUID taskId,
            String taskTitle,
            Activity activity,
            UUID approvalId,
            UUID questionId,
            String waitingText,
            UUID waitingOn) {}

    /**
     * The run an agent's presence is about, when it has several at once: the one that most needs a
     * person (rank), and among equals the one whose state began most recently. Pure; empty for none.
     */
    static Optional<RunState> headline(List<RunState> runs) {
        return runs.stream()
                .min(Comparator.comparingInt((RunState run) -> rank(run.state(), run.waitingForYou()))
                        .thenComparing(RunState::since, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(run -> run.runId().toString()));
    }

    /**
     * An agent's state with nothing visible in progress: paused when it is paused, otherwise idle.
     * Pure.
     */
    static String restingState(String agentStatus) {
        return "paused".equals(agentStatus) ? PAUSED : IDLE;
    }
}
