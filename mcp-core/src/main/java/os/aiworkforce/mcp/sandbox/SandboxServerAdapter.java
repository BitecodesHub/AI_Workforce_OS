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
 * <p>It is the same class for all six servers. What each one offers comes from the manifest, so
 * adding a seventh sandbox server is a manifest entry rather than a new class - and the tools it
 * advertises are exactly the ones the live adapter will have to implement.
 *
 * <p>Faults are injectable: an argument containing {@code [[fault:timeout]]} produces the failure
 * the gateway would see from a real provider, which is how the approval and indeterminate paths
 * are exercised without waiting for a vendor to have a bad day.
 */
public class SandboxServerAdapter implements McpServerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SandboxServerAdapter.class);
    private static final String FAULT_MARKER = "[[fault:";

    private final String server;
    private final List<ToolDefinition> tools;
    private final ObjectMapper json;

    /* Per workspace, per collection, an ordered list of records. Deliberately in memory: the
     * sandbox is for demonstration and tests, and persisting it would invite it being trusted. */
    private final Map<String, Map<String, List<ObjectNode>>> store = new ConcurrentHashMap<>();

    public SandboxServerAdapter(String server, List<ToolDefinition> tools, ObjectMapper json) {
        this.server = server;
        this.tools = List.copyOf(tools);
        this.json = json;
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

    @Override
    public Mono<Boolean> healthCheck(String credential) {
        return Mono.just(true);
    }

    @Override
    public Mono<ToolResult> invoke(ToolInvocation invocation, String credential) {
        return Mono.fromCallable(() -> run(invocation))
                // A little latency, so a streaming interface and the approval flow are exercised
                // against something that does not answer instantaneously.
                .delayElement(Duration.ofMillis(80));
    }

    private ToolResult run(ToolInvocation invocation) {
        Instant startedAt = Instant.now();
        JsonNode arguments = parse(invocation.argumentsJson());

        String fault = faultIn(arguments);
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
        String collection = collectionOf(tool);
        List<ObjectNode> records = records(invocation.orgId(), collection);

        ObjectNode result = json.createObjectNode();
        String summary;

        switch (verb) {
            case "list", "search" -> {
                result.put("count", records.size());
                result.set("items", json.valueToTree(records));
                summary = "Returned " + records.size() + " " + collection + " record(s) from the sandbox.";
            }
            case "get" -> {
                String id = arguments.path("id").asText(null);
                ObjectNode match = records.stream()
                        .filter(record -> record.path("id").asText("").equals(id))
                        .findFirst()
                        .orElse(null);
                if (match == null) {
                    return ToolResult.failed("No " + collection + " record with id " + id + ".");
                }
                result.setAll(match);
                summary = "Read one " + collection + " record from the sandbox.";
            }
            case "create", "draft", "send", "post", "update" -> {
                ObjectNode record = json.createObjectNode();
                record.put("id", collection + "_" + Long.toHexString(System.nanoTime()));
                record.put("createdAt", Instant.now().toString());
                record.put("createdBy", invocation.agentId());
                arguments.fields().forEachRemaining(entry -> record.set(entry.getKey(), entry.getValue()));
                records.add(record);
                result.setAll(record);
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
            default -> {
                result.put("tool", invocation.qualifiedName());
                result.put("sandbox", true);
                summary = "The sandbox acknowledged " + invocation.qualifiedName() + ".";
            }
        }

        log.debug("Sandbox {} handled {} for org {}", server, invocation.qualifiedName(), invocation.orgId());
        return ToolResult.succeeded(result.toString(), summary, Duration.between(startedAt, Instant.now()));
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

    private List<ObjectNode> records(String orgId, String collection) {
        return store.computeIfAbsent(orgId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(collection, name -> java.util.Collections.synchronizedList(new ArrayList<>()));
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
