// @find: sandbox server, practice data, fake connector, demo connector, seeded records, in memory store, no credentials needed, fault injection, timeout test, list create update delete send draft, sandbox twin, gmail, slack, github, jira, confluence, asana, zendesk, stripe, zoom, hubspot, linear, notion, salesforce, outlook, teams, calendar, drive, sheets, webhook
// @what: A working in-memory stand-in for one vendor server, so every connector works with practice data and no credentials.
// @flow: Built per server by SandboxServerRegistry; wrapped by live adapters; called through ToolGateway
package os.aiworkforce.mcp.sandbox;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.spi.McpServerAdapter;

/**
 * A working stand-in for one real Model Context Protocol server.
 *
 * <p>This is the reason the platform is demonstrable with no credentials at all. It is not a
 * mock that returns an empty object: it keeps a small in-memory store per workspace, so a draft
 * created by one call is readable by the next, a ticket that is created can then be updated, and
 * an agent's multi-step task genuinely works end to end.
 *
 * <p>It is the same class for every server. What each one offers comes from the manifest, so
 * adding a sandbox server is a manifest entry rather than a new class - and the tools it
 * advertises are exactly the ones the live adapter will have to implement. Each workspace starts
 * with a few seeded practice records per collection, so a first "list" returns something to work
 * with.
 *
 * <p>Faults are injectable: an argument containing {@code [[fault:timeout]]} produces the failure
 * the gateway would see from a real provider, which is how the approval and indeterminate paths
 * are exercised without waiting for a vendor to have a bad day.
 */
public class SandboxServerAdapter implements McpServerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SandboxServerAdapter.class);
    private static final String FAULT_MARKER = "[[fault:";

    /* The status a record is left in by the verb that touched it. */
    private static final Map<String, String> STATUS_AFTER = Map.of(
            "draft", "draft",
            "send", "sent",
            "post", "posted",
            "schedule", "scheduled",
            "archive", "archived",
            "cancel", "cancelled",
            "refund", "refunded",
            "merge", "merged");

    /*
     * Arguments that name another record, and the collection that record lives in. A reply to a
     * ticket that does not exist, a row for a spreadsheet nobody has, or a post to a channel that
     * is not there fails the way the real provider would, instead of quietly succeeding. A channel
     * may be named by id or by name; it is stored the way the channel's messages name it.
     */
    private static final Map<String, String> REFERENCES = Map.of(
            "ticketId", "ticket",
            "spreadsheetId", "spreadsheet",
            "channel", "channel",
            "customer", "customer");

    /* Tools whose update adds text to the end of the record's content rather than replacing it. */
    private static final java.util.Set<String> APPENDS_CONTENT = java.util.Set.of("notion.update_page");

    /* Arguments that describe the request rather than a field to match on. */
    private static final java.util.Set<String> NOT_FILTERS =
            java.util.Set.of("id", "query", "q", "jql", "limit", "text", "body", "content", "from", "to");

    private final String server;
    private final List<ToolDefinition> tools;
    private final ObjectMapper json;

    /* Practice records each workspace starts with, by collection, so a first list returns data. */
    private final Map<String, List<ObjectNode>> seeds;

    /* Per workspace, per collection, an ordered list of records. Deliberately in memory: the
     * sandbox is for demonstration and tests, and persisting it would invite it being trusted. */
    private final Map<String, Map<String, List<ObjectNode>>> store = new ConcurrentHashMap<>();

    public SandboxServerAdapter(String server, List<ToolDefinition> tools, ObjectMapper json) {
        this(server, tools, json, Map.of());
    }

    /**
     * @param seeds practice records by collection; the key may be singular or plural
     *     ({@code issues} or {@code issue}), and every workspace receives its own copy
     */
    public SandboxServerAdapter(
            String server, List<ToolDefinition> tools, ObjectMapper json, Map<String, List<ObjectNode>> seeds) {
        this.server = server;
        this.tools = List.copyOf(tools);
        this.json = json;
        Map<String, List<ObjectNode>> normalised = new java.util.HashMap<>();
        (seeds == null ? Map.<String, List<ObjectNode>>of() : seeds)
                .forEach((collection, records) -> normalised.put(singular(collection), List.copyOf(records)));
        this.seeds = Map.copyOf(normalised);
    }

    @Override
    public String server() {
        return server;
    }

    @Override
    public List<ToolDefinition> tools() {
        return tools;
    }

    @Override
    public boolean isSandbox() {
        return true;
    }

    // @find: sandbox health check, always healthy
    @Override
    public Mono<Boolean> healthCheck(String credential) {
        return Mono.just(true);
    }

    // @find: run sandbox tool, verb collection routing, practice data store, fault marker
    @Override
    public Mono<ToolResult> invoke(ToolInvocation invocation, String credential) {
        return Mono.fromCallable(() -> run(invocation))
                // A little latency, so a streaming interface and the approval flow are exercised
                // against something that does not answer instantaneously.
                .delayElement(Duration.ofMillis(80));
    }

    private ToolResult run(ToolInvocation invocation) {
        Instant startedAt = Instant.now();
        JsonNode raw = parse(invocation.argumentsJson());

        String fault = faultIn(raw);
        if (fault != null) {
            return switch (fault) {
                case "timeout" -> throw new RuntimeException(new java.util.concurrent.TimeoutException());
                case "failed" -> ToolResult.failed("The sandbox was asked to fail.");
                case "indeterminate" ->
                    ToolResult.indeterminate("The sandbox was asked to return an unknown outcome.", Duration.ZERO);
                default -> ToolResult.failed("Unknown sandbox fault: " + fault);
            };
        }

        String tool = invocation.tool();
        String verb = verbOf(tool);
        // list_issues and create_issue share one collection, so what one call creates the next
        // call lists, and the seeded records answer both.
        String collection = singular(collectionOf(tool));
        List<ObjectNode> records = records(invocation.orgId(), collection);
        String id = raw.hasNonNull("id") ? raw.get("id").asText() : null;

        // The records other arguments name must exist, and are written the way they are stored.
        ObjectNode resolved = raw.isObject() ? ((ObjectNode) raw).deepCopy() : json.createObjectNode();
        ObjectNode parent = null;
        List<ObjectNode> parentRecords = null;
        for (Map.Entry<String, String> reference : references(collection).entrySet()) {
            String field = reference.getKey();
            String value = resolved.path(field).isValueNode() ? resolved.path(field).asText("").trim() : "";
            if (value.isEmpty()) {
                continue;
            }
            String target = reference.getValue();
            ObjectNode found = lookUp(records(invocation.orgId(), target), value);
            if (found == null) {
                String lister = listerOf(target);
                return ToolResult.failed("No " + target.replace('_', ' ') + " matches " + field + " \"" + value + "\"."
                        + (lister == null ? "" : " Use " + lister + " to see the ones that exist."));
            }
            resolved.put(field, "channel".equals(field) && found.hasNonNull("name")
                    ? found.get("name").asText()
                    : found.path("id").asText());
            if (!target.equals(collection)) {
                parent = found;
                parentRecords = records(invocation.orgId(), target);
            }
        }
        final ObjectNode arguments = resolved;

        // get_pulls and get_messages read a list rather than one record by id.
        if ("get".equals(verb) && id == null) {
            verb = "list";
        }

        ObjectNode result = json.createObjectNode();
        String summary;

        switch (verb) {
            case "list", "search" -> {
                List<ObjectNode> matches = filter(snapshot(records), arguments);
                result.put("count", matches.size());
                result.set("items", json.valueToTree(matches));
                summary = "Returned " + matches.size() + " " + collection + " record(s) from the sandbox.";
            }
            case "get" -> {
                ObjectNode match = find(records, id);
                if (match == null) {
                    return ToolResult.failed("No " + collection + " record with id " + id + ".");
                }
                result.setAll(match.deepCopy());
                summary = "Read one " + collection + " record from the sandbox.";
            }
            case "create", "draft", "send", "post", "add", "append", "schedule" -> {
                ObjectNode record = json.createObjectNode();
                record.put("id", collection + "_" + Long.toHexString(System.nanoTime()));
                record.put("createdAt", Instant.now().toString());
                record.put("createdBy", invocation.agentId());
                arguments.fields().forEachRemaining(entry -> record.set(entry.getKey(), entry.getValue()));
                if (!record.has("status") && STATUS_AFTER.containsKey(verb)) {
                    record.put("status", STATUS_AFTER.get(verb));
                }
                records.add(record);
                if (parent != null && parent.path("conversation").isArray()) {
                    // A note or a reply on a ticket is part of that ticket's conversation.
                    String text = arguments.path("body").asText(arguments.path("text").asText(""));
                    synchronized (parentRecords) {
                        ((com.fasterxml.jackson.databind.node.ArrayNode) parent.get("conversation"))
                                .add("note".equals(collection) ? "Private note: " + text : text);
                    }
                }
                result.setAll(record.deepCopy());
                // Said plainly, because an agent reporting "email sent" when nothing left the
                // machine is exactly the confusion the sandbox must not create.
                result.put("sandbox", true);
                // The voice tool writes a script, not an email or a ticket, so it earns its own
                // sentence rather than the generic "record in the sandbox" one.
                summary = "voice_note".equals(collection)
                        ? "Saved a voice note script ("
                                + arguments.path("text").asText("").length() + " characters)."
                        : "Recorded a " + collection + " record in the sandbox. Nothing left this machine.";
            }
            case "update" -> {
                ObjectNode match = find(records, id);
                if (match == null) {
                    return ToolResult.failed("No " + collection + " record with id " + id + ".");
                }
                boolean appends = APPENDS_CONTENT.contains(invocation.qualifiedName());
                synchronized (records) {
                    arguments.fields().forEachRemaining(entry -> {
                        if ("id".equals(entry.getKey())) {
                            return;
                        }
                        if (appends && "content".equals(entry.getKey()) && match.hasNonNull("content")) {
                            match.put("content", match.get("content").asText() + "\n\n" + entry.getValue().asText());
                        } else {
                            match.set(entry.getKey(), entry.getValue());
                        }
                    });
                    match.put("updatedAt", Instant.now().toString());
                }
                result.setAll(match.deepCopy());
                result.put("sandbox", true);
                summary = "Updated one " + collection + " record in the sandbox. Nothing left this machine.";
            }
            case "delete", "remove" -> {
                ObjectNode match = find(records, id);
                if (match == null) {
                    return ToolResult.failed("No " + collection + " record with id " + id + ".");
                }
                records.remove(match);
                result.put("id", id);
                result.put("deleted", true);
                result.put("sandbox", true);
                summary = "Deleted one " + collection + " record from the sandbox. Nothing left this machine.";
            }
            case "save" -> {
                // A file is saved by its path: the first save creates it, a later one replaces it.
                ObjectNode match = sameFile(records, arguments);
                if (match == null) {
                    ObjectNode record = json.createObjectNode();
                    record.put("id", collection + "_" + Long.toHexString(System.nanoTime()));
                    record.put("createdAt", Instant.now().toString());
                    record.put("createdBy", invocation.agentId());
                    arguments.fields().forEachRemaining(entry -> record.set(entry.getKey(), entry.getValue()));
                    records.add(record);
                    result.setAll(record.deepCopy());
                } else {
                    synchronized (records) {
                        arguments.fields().forEachRemaining(entry -> match.set(entry.getKey(), entry.getValue()));
                        match.put("updatedAt", Instant.now().toString());
                    }
                    result.setAll(match.deepCopy());
                }
                result.put("sandbox", true);
                summary = "Saved one " + collection + " record in the sandbox. Nothing left this machine.";
            }
            case "archive", "cancel", "refund", "merge" -> {
                ObjectNode match = find(records, id);
                if (match == null) {
                    return ToolResult.failed("No " + collection + " record with id " + id + ".");
                }
                String problem = refusal(verb, collection, id, match, arguments);
                if (problem != null) {
                    return ToolResult.failed(problem);
                }
                synchronized (records) {
                    match.put("status", STATUS_AFTER.get(verb));
                    if ("refund".equals(verb)) {
                        JsonNode amount = arguments.hasNonNull("amount") ? arguments.get("amount") : match.get("amount");
                        if (amount != null) {
                            match.set("refundedAmount", amount);
                        }
                    }
                    match.put("updatedAt", Instant.now().toString());
                }
                result.setAll(match.deepCopy());
                result.put("sandbox", true);
                summary = "refund".equals(verb)
                        ? "Marked one payment as refunded in the sandbox. No money moved."
                        : "Marked one " + collection + " record as " + STATUS_AFTER.get(verb)
                                + " in the sandbox. Nothing left this machine.";
            }
            default -> {
                result.put("tool", invocation.qualifiedName());
                result.put("sandbox", true);
                summary = "The sandbox acknowledged " + invocation.qualifiedName() + ".";
            }
        }

        log.debug("Sandbox {} handled {} for org {}", server, invocation.qualifiedName(), invocation.orgId());
        return ToolResult.succeeded(result.toString(), summary, Duration.between(startedAt, Instant.now()));
    }

    /*
     * Why an archive, cancel or refund cannot be made, the way the provider would refuse it: a
     * second refund of the same payment, a refund of more than was paid, or cancelling what is
     * already cancelled. Null when it can go ahead.
     */
    private static String refusal(String verb, String collection, String id, ObjectNode match, JsonNode arguments) {
        String after = STATUS_AFTER.get(verb);
        String noun = collection.replace('_', ' ');
        if (after.equals(match.path("status").asText(""))) {
            return "refund".equals(verb)
                    ? "Payment " + id + " has already been refunded."
                    : "That " + noun + " (" + id + ") is already " + after + ".";
        }
        if ("refund".equals(verb) && arguments.hasNonNull("amount")) {
            double amount = arguments.get("amount").asDouble();
            double paid = match.path("amount").asDouble(Double.NaN);
            if (amount <= 0) {
                return "The refund amount must be more than 0.";
            }
            if (!Double.isNaN(paid) && amount > paid) {
                return "The refund amount " + arguments.get("amount").asText() + " is more than the payment of "
                        + match.path("amount").asText() + ".";
            }
        }
        return null;
    }

    /* The record already saved at the same path in the same repository, if there is one. */
    private static ObjectNode sameFile(List<ObjectNode> records, JsonNode arguments) {
        String path = arguments.path("path").asText("");
        String repo = arguments.path("repo").asText("");
        synchronized (records) {
            return records.stream()
                    .filter(record -> !path.isEmpty() && path.equals(record.path("path").asText()))
                    .filter(record -> repo.equals(record.path("repo").asText("")))
                    .findFirst()
                    .orElse(null);
        }
    }

    /* The reference arguments this server knows the target collection of. */
    private Map<String, String> references(String collection) {
        Map<String, String> known = new java.util.LinkedHashMap<>();
        REFERENCES.forEach((field, target) -> {
            if (knows(target)) {
                known.put(field, target);
            }
        });
        // A page is created under a page, a task under a task: the parent is in the same collection.
        if (knows(collection) && !collection.isEmpty()) {
            known.put("parentId", collection);
        }
        return known;
    }

    private boolean knows(String collection) {
        return seeds.containsKey(collection)
                || tools.stream().anyMatch(tool -> singular(collectionOf(tool.name())).equals(collection)
                        && ("list".equals(verbOf(tool.name())) || "search".equals(verbOf(tool.name()))));
    }

    /* The tool that lists a collection, to name in a "not found" message. */
    private String listerOf(String collection) {
        return tools.stream()
                .filter(tool -> singular(collectionOf(tool.name())).equals(collection))
                .filter(tool -> "list".equals(verbOf(tool.name())) || "search".equals(verbOf(tool.name())))
                .map(ToolDefinition::qualifiedName)
                .findFirst()
                .orElse(null);
    }

    /* A record named by its id, or by its name or email address, ignoring case and a leading #. */
    private static ObjectNode lookUp(List<ObjectNode> records, String value) {
        String wanted = normalise(value);
        synchronized (records) {
            return records.stream()
                    .filter(record -> record.path("id").asText("").equals(value)
                            || normalise(record.path("id").asText("")).equals(wanted)
                            || normalise(record.path("name").asText("")).equals(wanted)
                            || normalise(record.path("email").asText("")).equals(wanted))
                    .findFirst()
                    .orElse(null);
        }
    }

    /*
     * Narrows a list the way the real provider would: by a free-text query, by any argument that
     * names a field the records carry (state, status, channel, project...), and by a limit. A
     * record that lacks the field is kept, so something an agent created a moment ago is not
     * hidden because it was created without a status.
     */
    private List<ObjectNode> filter(List<ObjectNode> records, JsonNode arguments) {
        List<ObjectNode> matches = records;
        var fields = arguments.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String wanted = entry.getValue().isValueNode() ? normalise(entry.getValue().asText()) : "";
            if (NOT_FILTERS.contains(entry.getKey()) || wanted.isEmpty() || "all".equals(wanted) || "any".equals(wanted)) {
                continue;
            }
            String field = entry.getKey();
            matches = matches.stream()
                    .filter(record -> !record.has(field)
                            || normalise(record.get(field).asText()).equals(wanted))
                    .toList();
        }

        String query = arguments.path("query").asText(arguments.path("q").asText(""));
        List<String> words = java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}@.]+"))
                .filter(word -> word.length() >= 3)
                .toList();
        if (!words.isEmpty()) {
            matches = matches.stream()
                    .filter(record -> {
                        String text = textOf(record);
                        return words.stream().anyMatch(text::contains);
                    })
                    .toList();
        }

        int limit = arguments.path("limit").asInt(0);
        return limit > 0 && matches.size() > limit ? matches.subList(0, limit) : matches;
    }

    private static String normalise(String value) {
        String trimmed = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
    }

    private static String textOf(JsonNode node) {
        StringBuilder text = new StringBuilder();
        collectText(node, text);
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static void collectText(JsonNode node, StringBuilder text) {
        if (node.isValueNode()) {
            text.append(node.asText()).append(' ');
        } else {
            node.elements().forEachRemaining(child -> collectText(child, text));
        }
    }

    private static ObjectNode find(List<ObjectNode> records, String id) {
        if (id == null) {
            return null;
        }
        synchronized (records) {
            return records.stream()
                    .filter(record -> record.path("id").asText("").equals(id))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static List<ObjectNode> snapshot(List<ObjectNode> records) {
        synchronized (records) {
            return records.stream().map(ObjectNode::deepCopy).toList();
        }
    }

    /* The verb is the part before the first underscore: send_message, list_events, create_issue. */
    private static String verbOf(String tool) {
        int underscore = tool.indexOf('_');
        return underscore < 0 ? tool : tool.substring(0, underscore);
    }

    private static String collectionOf(String tool) {
        int underscore = tool.indexOf('_');
        return underscore < 0 ? tool : tool.substring(underscore + 1);
    }

    /** issues and issue, opportunities and opportunity: one collection, whichever the tool says. */
    static String singular(String noun) {
        String lower = noun.toLowerCase(Locale.ROOT);
        if (lower.endsWith("ies") && lower.length() > 4) {
            return lower.substring(0, lower.length() - 3) + "y";
        }
        // branches and branch, boxes and box, but not messages and messag.
        if (lower.length() > 5 && (lower.endsWith("ches") || lower.endsWith("shes") || lower.endsWith("xes"))) {
            return lower.substring(0, lower.length() - 2);
        }
        if (lower.endsWith("s") && !lower.endsWith("ss") && lower.length() > 3) {
            return lower.substring(0, lower.length() - 1);
        }
        return lower;
    }

    private List<ObjectNode> records(String orgId, String collection) {
        return store.computeIfAbsent(orgId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(collection, name -> {
                    List<ObjectNode> fresh = new ArrayList<>();
                    seeds.getOrDefault(name, List.of()).forEach(record -> fresh.add(record.deepCopy()));
                    return java.util.Collections.synchronizedList(fresh);
                });
    }

    private JsonNode parse(String argumentsJson) {
        try {
            return json.readTree(argumentsJson == null ? "{}" : argumentsJson);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private String faultIn(JsonNode arguments) {
        String text = arguments.toString();
        int start = text.indexOf(FAULT_MARKER);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf("]]", start);
        return end < 0
                ? null
                : text.substring(start + FAULT_MARKER.length(), end).trim().toLowerCase(Locale.ROOT);
    }
}
