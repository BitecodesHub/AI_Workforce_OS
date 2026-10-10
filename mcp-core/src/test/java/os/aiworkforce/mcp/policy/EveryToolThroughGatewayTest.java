// @find: tests for every tool through gateway, all connectors, practice data, list get create update delete, approval for send and delete, schema built arguments, sandbox, gmail, slack, github, jira, stripe, all vendors
// @what: Calls every tool of every connector through the real gateway on practice data and checks each verb behaves and send or delete parks for approval.
package os.aiworkforce.mcp.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;
import os.aiworkforce.mcp.spi.McpServerAdapter;
import os.aiworkforce.platform.resilience.ResiliencePresets;

/**
 * Every tool of every connector, called through the real gateway with a grant, against practice
 * data - the way an agent with no live credentials calls it.
 *
 * <p>Arguments are built from each tool's own JSON schema, with ids and names taken from the
 * seeded records, so a tool added to the registry is exercised here without a new test. Each
 * tool then has its verb checked: a list returns seeded records, a get reads the record it was
 * asked for and fails plainly for an id that does not exist, a create can be read back, an update
 * changes the record, a delete removes it, and an archive, cancel or refund marks it and cannot be
 * repeated. Sending and deleting park for approval first, whatever the grant says.
 */
class EveryToolThroughGatewayTest {

    private static final String AGENT = "agent-every-tool";
    private static final Set<String> CREATES = Set.of("create", "draft", "send", "post", "add", "append", "schedule", "save");
    private static final Set<String> MARKS = Set.of("archive", "cancel", "refund", "merge");
    private static final Map<String, String> MARKED =
            Map.of("archive", "archived", "cancel", "cancelled", "refund", "refunded", "merge", "merged");

    private final ObjectMapper json = new ObjectMapper();
    private final List<McpServerAdapter> adapters = SandboxServerRegistry.servers(json, WebClient.builder(), true);
    private final Map<String, McpServerAdapter> servers =
            adapters.stream().collect(Collectors.toMap(McpServerAdapter::server, adapter -> adapter));
    private final ToolGateway gateway = new ToolGateway(
            adapters, new ArgumentValidator(json), new ResiliencePresets(ToolGatewayTest.properties()));
    private final JsonNode seeds = readSeeds();

    @TestFactory
    @DisplayName("every tool runs through the gateway and does what its verb says")
    Stream<DynamicTest> everyTool() {
        return SandboxServerRegistry.definitions().values().stream()
                .flatMap(List::stream)
                .map(tool -> DynamicTest.dynamicTest(tool.qualifiedName(), () -> exercise(tool)));
    }

    @Test
    @DisplayName("the registry offers 20 connectors and 89 tools, all of them exercised above")
    void inventory() {
        assertThat(servers).hasSize(20);
        assertThat(adapters.stream().mapToInt(adapter -> adapter.tools().size()).sum()).isEqualTo(89);
    }

    // ---- The verbs -----------------------------------------------------------------------------

    private void exercise(ToolDefinition tool) {
        String org = "org-" + UUID.randomUUID();
        String verb = verbOf(tool);
        ObjectNode args = validArgs(tool);

        ToolResult result = approvedCall(org, tool, args);
        assertThat(result.status()).as(tool.qualifiedName() + ": " + result.summary()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertPlain(tool, result.summary());
        assertThat(result.summary()).as("a verb the sandbox does not understand").doesNotContain("acknowledged");
        JsonNode content = read(result.contentJson());

        boolean readsAList = Set.of("list", "search").contains(verb) || ("get".equals(verb) && !requires(tool, "id"));
        if (readsAList) {
            assertThat(content.path("count").asInt()).as(tool.qualifiedName() + " returns seeded records").isPositive();
            assertThat(content.path("items").size()).isEqualTo(content.path("count").asInt());
        } else if ("get".equals(verb)) {
            assertThat(content.path("id").asText()).isEqualTo(args.path("id").asText());
            assertUnknownIdFailsPlainly(org, tool, args);
        } else if (CREATES.contains(verb)) {
            String id = content.path("id").asText();
            assertThat(id).isNotBlank();
            assertThat(content.path("sandbox").asBoolean()).isTrue();
            assertReadableBack(org, tool, args, id);
        } else if ("update".equals(verb)) {
            String field = changedField(tool, args);
            assertThat(textOf(content.path(field))).as(tool.qualifiedName() + " changes " + field)
                    .contains(textOf(args.path(field)));
            JsonNode after = readBack(org, tool, args.path("id").asText(), args)
                    .orElseThrow(() -> new AssertionError("updated record not found by " + tool.qualifiedName()));
            assertThat(textOf(after.path(field))).contains(textOf(args.path(field)));
            assertUnknownIdFailsPlainly(org, tool, args);
        } else if ("delete".equals(verb)) {
            assertThat(content.path("deleted").asBoolean()).isTrue();
            assertThat(readBack(org, tool, args.path("id").asText(), args)).as("deleted record is gone").isEmpty();
            assertUnknownIdFailsPlainly(org, tool, args);
        } else if (MARKS.contains(verb)) {
            assertThat(content.path("status").asText()).isEqualTo(MARKED.get(verb));
            readBack(org, tool, args.path("id").asText(), args).ifPresent(after ->
                    assertThat(after.path("status").asText()).isEqualTo(MARKED.get(verb)));
            ToolResult again = approvedCall(org, tool, args);
            assertThat(again.status()).as("a second " + verb + " is refused").isEqualTo(ToolResult.Status.FAILED);
            assertThat(again.summary()).contains("already");
            assertPlain(tool, again.summary());
            assertUnknownIdFailsPlainly(org, tool, args);
        } else {
            throw new AssertionError("No check for the verb " + verb + " of " + tool.qualifiedName());
        }

        assertValidationIsPlain(org, tool, args);
    }

    /*
     * Evaluates, checks the gate the tool's side effect calls for, and then makes the call the
     * way an approved one is made.
     */
    private ToolResult approvedCall(String org, ToolDefinition tool, ObjectNode args) {
        ToolInvocation invocation = invocation(org, tool, args.toString());
        ApprovalDecision decision = gateway.evaluate(invocation, grants(tool.server()), false);
        if (tool.alwaysRequiresApproval()) {
            assertThat(decision).as(tool.qualifiedName() + " waits for a person").isInstanceOf(ApprovalDecision.AwaitApproval.class);
            assertThat(((ApprovalDecision.AwaitApproval) decision).reason()).contains(tool.qualifiedName());
            ToolResult parked = gateway.invoke(invocation, decision, null).block();
            assertThat(parked.status()).isEqualTo(ToolResult.Status.BLOCKED);
            assertThat(parked.summary()).startsWith("Waiting for approval");
        } else {
            assertThat(decision).as(tool.qualifiedName() + " proceeds").isInstanceOf(ApprovalDecision.Proceed.class);
        }
        return gateway.invoke(invocation, ApprovalDecision.PROCEED, null).block();
    }

    private void assertUnknownIdFailsPlainly(String org, ToolDefinition tool, ObjectNode args) {
        ObjectNode unknown = args.deepCopy();
        unknown.put("id", "does-not-exist");
        ToolResult missing = approvedCall(org, tool, unknown);
        assertThat(missing.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(missing.summary()).matches("No [a-z_]+ record with id does-not-exist\\.");
    }

    /* A missing required field and a field of the wrong type are refused by name, before the gate. */
    private void assertValidationIsPlain(String org, ToolDefinition tool, ObjectNode args) {
        List<String> required = required(tool);
        if (!required.isEmpty()) {
            String field = required.get(0);
            ObjectNode without = args.deepCopy();
            without.remove(field);
            assertInvalid(org, tool, without, field);
        }
        properties(tool).fields().forEachRemaining(property -> {
            String field = property.getKey();
            if (args.has(field)) {
                ObjectNode wrong = args.deepCopy();
                String type = property.getValue().path("type").asText();
                if (Set.of("string", "array").contains(type)) {
                    // An object, which no near-miss conversion turns into text or a list.
                    wrong.putObject(field).put("unexpected", true);
                } else {
                    wrong.put(field, "not-a-" + type);
                }
                assertInvalid(org, tool, wrong, field);
            }
        });
        ToolInvocation garbage = invocation(org, tool, "this is not json");
        assertThat(gateway.evaluate(garbage, grants(tool.server()), false)).isInstanceOf(ApprovalDecision.Invalid.class);
    }

    private void assertInvalid(String org, ToolDefinition tool, ObjectNode args, String field) {
        ToolInvocation invocation = invocation(org, tool, args.toString());
        ApprovalDecision decision = gateway.evaluate(invocation, grants(tool.server()), false);
        assertThat(decision).as(tool.qualifiedName() + " with a bad " + field + " is invalid, not parked")
                .isInstanceOf(ApprovalDecision.Invalid.class);
        ToolResult result = gateway.invoke(invocation, decision, null).block();
        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(result.summary()).contains(field);
        assertPlain(tool, result.summary());
    }

    private void assertReadableBack(String org, ToolDefinition tool, ObjectNode args, String id) {
        String collection = collectionOf(tool);
        if (args.has("ticketId")) {
            // A note or a reply on a ticket shows in that ticket's conversation.
            JsonNode ticket = read(call(org, servers.get(tool.server()).tool("get_ticket").orElseThrow(),
                            json.createObjectNode().put("id", args.path("ticketId").asText()))
                    .contentJson());
            assertThat(ticket.path("conversation").toString()).contains(args.path("body").asText());
            return;
        }
        if (readers(tool).isEmpty()) {
            // A voice note is a script for the audio, and Salesforce offers no tool to read leads.
            assertThat(collection).as("only these have no tool to read them back").isIn("voice_note", "lead");
            return;
        }
        assertThat(readBack(org, tool, id, args)).as(tool.qualifiedName() + " can be read back").isPresent();
    }

    /* The record with that id, found by the server's get tool for the collection, or its list. */
    private Optional<JsonNode> readBack(String org, ToolDefinition tool, String id, ObjectNode written) {
        for (ToolDefinition reader : readers(tool)) {
            if (requires(reader, "id")) {
                // The other fields a get needs (a pull request's repository) are the ones written.
                ObjectNode args = json.createObjectNode().put("id", id);
                for (String field : required(reader)) {
                    if (!"id".equals(field)) {
                        args.set(field, written.has(field) ? written.get(field) : seededValue(reader, field));
                    }
                }
                ToolResult result = call(org, reader, args);
                return result.isSuccess() ? Optional.of(read(result.contentJson())) : Optional.empty();
            }
        }
        for (ToolDefinition reader : readers(tool)) {
            ObjectNode args = json.createObjectNode();
            for (String field : required(reader)) {
                args.set(field, written.has(field) ? written.get(field) : seededValue(reader, field));
            }
            args.put("limit", 100);
            if (!properties(reader).has("limit")) {
                args.remove("limit");
            }
            for (JsonNode item : read(call(org, reader, args).contentJson()).path("items")) {
                if (id.equals(item.path("id").asText())) {
                    return Optional.of(item);
                }
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private List<ToolDefinition> readers(ToolDefinition tool) {
        return servers.get(tool.server()).tools().stream()
                .filter(other -> Set.of("list", "search", "get").contains(verbOf(other)))
                .filter(other -> collectionOf(other).equals(collectionOf(tool)))
                .toList();
    }

    private ToolResult call(String org, ToolDefinition tool, ObjectNode args) {
        ToolInvocation invocation = invocation(org, tool, args.toString());
        ApprovalDecision decision = gateway.evaluate(invocation, grants(tool.server()), false);
        assertThat(decision).as(tool.qualifiedName() + " " + args).isInstanceOf(ApprovalDecision.Proceed.class);
        return gateway.invoke(invocation, decision, null).block();
    }

    // ---- Arguments from the schema -------------------------------------------------------------

    private ObjectNode validArgs(ToolDefinition tool) {
        String verb = verbOf(tool);
        boolean onlyRequired = Set.of("list", "search", "get").contains(verb);
        ObjectNode args = json.createObjectNode();
        Set<String> required = Set.copyOf(required(tool));
        properties(tool).fields().forEachRemaining(property -> {
            String field = property.getKey();
            if (onlyRequired && !required.contains(field)) {
                return;
            }
            if ("refund".equals(verb) && "amount".equals(field)) {
                args.put(field, 10);
                return;
            }
            args.set(field, valueFor(tool, field, property.getValue()));
        });
        return args;
    }

    private JsonNode valueFor(ToolDefinition tool, String field, JsonNode schema) {
        String type = schema.path("type").asText();
        String description = schema.path("description").asText("");
        if ("id".equals(field)) {
            return json.getNodeFactory().textNode(seed(tool.server(), collectionOf(tool)).path("id").asText("unknown"));
        }
        JsonNode reference = referenceValue(tool, field);
        if (reference != null) {
            return reference;
        }
        if (Set.of("list", "search", "get").contains(verbOf(tool))) {
            JsonNode seeded = seededValue(tool, field);
            if (seeded != null) {
                return seeded;
            }
        }
        return switch (type) {
            case "integer" -> json.getNodeFactory().numberNode(schema.path("minimum").asInt(2));
            case "number" -> json.getNodeFactory().numberNode(1234.5);
            case "boolean" -> json.getNodeFactory().booleanNode(true);
            case "array" -> json.createArrayNode().add("attendees".equals(field) ? "sam@example.com" : "Sample value");
            case "object" -> json.createObjectNode().put("order", "SO-2001");
            default -> json.getNodeFactory().textNode(sampleText(field, description));
        };
    }

    /* Names of other records are taken from the seeds, by id, so the sandbox must resolve them. */
    private JsonNode referenceValue(ToolDefinition tool, String field) {
        String collection = switch (field) {
            case "ticketId" -> "ticket";
            case "spreadsheetId" -> "spreadsheet";
            case "channel" -> "channel";
            case "customer" -> "customer";
            case "parentId" -> collectionOf(tool);
            default -> null;
        };
        if (collection == null) {
            return null;
        }
        JsonNode record = seed(tool.server(), collection);
        return record.isMissingNode() ? null : record.get("id");
    }

    private JsonNode seededValue(ToolDefinition tool, String field) {
        JsonNode record = seed(tool.server(), collectionOf(tool));
        if (record.path(field).isValueNode()) {
            return record.get(field);
        }
        if (Set.of("query", "q").contains(field)) {
            // A word from the first seeded record, so a search finds it.
            Iterator<Map.Entry<String, JsonNode>> values = record.fields();
            while (values.hasNext()) {
                Map.Entry<String, JsonNode> entry = values.next();
                if (!"id".equals(entry.getKey()) && entry.getValue().isTextual()) {
                    for (String word : entry.getValue().asText().split("[^\\p{L}\\p{N}]+")) {
                        if (word.length() >= 4) {
                            return json.getNodeFactory().textNode(word);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String sampleText(String field, String description) {
        boolean date = description.contains("YYYY-MM-DD");
        return switch (field) {
            case "to" -> date ? "2026-10-31" : "jordan.lee@example.com";
            case "from" -> date ? "2026-10-01" : "sam@example.com";
            case "email" -> "new.person@example.com";
            case "start" -> "2026-10-20T09:00:00+10:00";
            case "end" -> "2026-10-20T10:00:00+10:00";
            case "dueOn", "closeDate" -> "2026-11-15";
            case "state" -> "closed";
            case "status" -> "pending";
            case "reason" -> "requested_by_customer";
            default -> date ? "2026-10-15" : "Sample " + field + " text";
        };
    }

    /* A field an update tool was asked to change, which is not the id or a reference. */
    private String changedField(ToolDefinition tool, ObjectNode args) {
        List<String> fields = new ArrayList<>();
        args.fieldNames().forEachRemaining(fields::add);
        return fields.stream()
                .filter(field -> !"id".equals(field) && referenceValue(tool, field) == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(tool.qualifiedName() + " has nothing to change"));
    }

    // ---- Small helpers -------------------------------------------------------------------------

    private static String textOf(JsonNode node) {
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private JsonNode seed(String server, String collection) {
        JsonNode collections = seeds.path(server);
        Iterator<Map.Entry<String, JsonNode>> entries = collections.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (singular(entry.getKey()).equals(collection) && entry.getValue().size() > 0) {
                return entry.getValue().get(0);
            }
        }
        return json.missingNode();
    }

    private List<ToolGrant> grants(String server) {
        return List.of(new ToolGrant(AGENT, server, List.of(), servers.get(server).allScopes(), false, null, true));
    }

    private ToolInvocation invocation(String org, ToolDefinition tool, String arguments) {
        return new ToolInvocation(org, AGENT, "run-" + UUID.randomUUID(), tool.server(), tool.name(), arguments,
                "key-" + UUID.randomUUID(), Map.of());
    }

    private JsonNode properties(ToolDefinition tool) {
        return read(tool.parametersJson()).path("properties");
    }

    private List<String> required(ToolDefinition tool) {
        List<String> required = new ArrayList<>();
        read(tool.parametersJson()).path("required").forEach(field -> required.add(field.asText()));
        return required;
    }

    private boolean requires(ToolDefinition tool, String field) {
        return required(tool).contains(field);
    }

    private static String verbOf(ToolDefinition tool) {
        int underscore = tool.name().indexOf('_');
        return underscore < 0 ? tool.name() : tool.name().substring(0, underscore);
    }

    private static String collectionOf(ToolDefinition tool) {
        int underscore = tool.name().indexOf('_');
        return singular(underscore < 0 ? tool.name() : tool.name().substring(underscore + 1));
    }

    private static String singular(String noun) {
        String lower = noun.toLowerCase(java.util.Locale.ROOT);
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

    private static void assertPlain(ToolDefinition tool, String summary) {
        assertThat(summary).as(tool.qualifiedName() + " summary").isNotBlank()
                .doesNotContain("Exception")
                .doesNotContain("\tat ")
                .doesNotContain("$.")
                .doesNotContain("null");
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + text, e);
        }
    }

    private JsonNode readSeeds() {
        try (InputStream in = SandboxServerRegistry.class.getResourceAsStream("/mcp/sandbox-seeds.json")) {
            return json.readTree(in);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ---- Regressions found while writing the above ---------------------------------------------

    @Test
    @DisplayName("a Slack channel named by the id list_channels returned reads that channel's messages")
    void slackChannelById() {
        String org = "org-slack";
        ToolDefinition getMessages = servers.get("slack").tool("get_messages").orElseThrow();
        JsonNode byId = read(call(org, getMessages, json.createObjectNode().put("channel", "C02SUPPORT")).contentJson());
        assertThat(byId.path("count").asInt()).isEqualTo(1);

        ToolDefinition post = servers.get("slack").tool("post_message").orElseThrow();
        ToolResult posted = gateway.invoke(
                        invocation(org, post, "{\"channel\":\"C02SUPPORT\",\"text\":\"On it.\"}"), ApprovalDecision.PROCEED, null)
                .block();
        assertThat(read(posted.contentJson()).path("channel").asText()).isEqualTo("support");
        assertThat(read(call(org, getMessages, json.createObjectNode().put("channel", "#support")).contentJson())
                        .path("count").asInt())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("posting to, replying on or adding a row to something that does not exist fails plainly")
    void unknownReferencesFail() {
        String org = "org-refs";
        ToolResult post = approved(org, "slack", "post_message", "{\"channel\":\"#nowhere\",\"text\":\"Hi\"}");
        assertThat(post.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(post.summary()).isEqualTo(
                "No channel matches channel \"#nowhere\". Use slack.list_channels to see the ones that exist.");

        ToolResult reply = approved(org, "zendesk", "send_reply", "{\"ticketId\":\"404\",\"body\":\"Hello\"}");
        assertThat(reply.summary()).isEqualTo(
                "No ticket matches ticketId \"404\". Use zendesk.list_tickets to see the ones that exist.");

        ToolResult row = approved(org, "sheets", "append_row", "{\"spreadsheetId\":\"nope\",\"values\":[\"a\"]}");
        assertThat(row.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(row.summary()).contains("sheets.list_spreadsheets");

        ToolResult page = approved(org, "notion", "create_page", "{\"parentId\":\"page_0\",\"title\":\"T\"}");
        assertThat(page.status()).isEqualTo(ToolResult.Status.FAILED);
    }

    @Test
    @DisplayName("a payment cannot be refunded twice, for more than was paid, or for nothing")
    void refundsAreChecked() {
        String org = "org-refund";
        assertThat(approved(org, "stripe", "refund_payment", "{\"id\":\"pi_1001\",\"amount\":600}").summary())
                .isEqualTo("The refund amount 600 is more than the payment of 500.0.");
        assertThat(approved(org, "stripe", "refund_payment", "{\"id\":\"pi_1001\",\"amount\":0}").summary())
                .isEqualTo("The refund amount must be more than 0.");
        assertThat(approved(org, "stripe", "refund_payment", "{\"id\":\"pi_1001\"}").isSuccess()).isTrue();
        assertThat(approved(org, "stripe", "refund_payment", "{\"id\":\"pi_1001\"}").summary())
                .isEqualTo("Payment pi_1001 has already been refunded.");
    }

    @Test
    @DisplayName("notion.update_page adds to the end of the page, as its description says")
    void notionUpdateAppends() {
        String org = "org-notion";
        ToolResult updated = approved(org, "notion", "update_page", "{\"id\":\"page_1401\",\"content\":\"Added line.\"}");
        String content = read(updated.contentJson()).path("content").asText();
        assertThat(content).endsWith("\n\nAdded line.");
        assertThat(content.length()).isGreaterThan("Added line.".length() + 2);
    }

    @Test
    @DisplayName("an outbound call with bad arguments is not put in front of an approver")
    void invalidOutboundIsNotParked() {
        ToolDefinition send = servers.get("gmail").tool("send_message").orElseThrow();
        ToolInvocation invocation = invocation("org-invalid", send, "{\"to\":\"a@example.com\",\"subject\":\"Hi\"}");

        ApprovalDecision decision = gateway.evaluate(invocation, grants("gmail"), false);

        assertThat(decision).isInstanceOf(ApprovalDecision.Invalid.class);
        ToolResult result = gateway.invoke(invocation, decision, null).block();
        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(result.summary()).contains("body");
    }

    @Test
    @DisplayName("unknown servers and tools are refused in plain words")
    void unknownNames() {
        ToolInvocation noServer = new ToolInvocation("org", AGENT, "run", "fax", "send", "{}", "k", Map.of());
        ToolInvocation noTool = new ToolInvocation("org", AGENT, "run", "gmail", "teleport", "{}", "k", Map.of());
        assertThat(((ApprovalDecision.Refuse) gateway.evaluate(noServer, grants("gmail"), false)).reason())
                .isEqualTo("No server named fax is connected.");
        assertThat(((ApprovalDecision.Refuse) gateway.evaluate(noTool, grants("gmail"), false)).reason())
                .isEqualTo("The connected server does not offer a tool named teleport.");
    }

    @Test
    @DisplayName("an adapter that throws is reported without the exception's name")
    void unexpectedErrorsArePlain() {
        ToolDefinition list = new ToolDefinition("flaky", "list_things", "List.", "{\"type\":\"object\"}",
                ToolSpec.SideEffect.READ, List.of(), true, java.time.Duration.ofSeconds(5), 60);
        ToolDefinition create = new ToolDefinition("flaky", "create_thing", "Create.", "{\"type\":\"object\"}",
                ToolSpec.SideEffect.WRITE, List.of(), false, java.time.Duration.ofSeconds(5), 60);
        McpServerAdapter flaky = new McpServerAdapter() {
            public String server() {
                return "flaky";
            }

            public List<ToolDefinition> tools() {
                return List.of(list, create);
            }

            public Mono<ToolResult> invoke(ToolInvocation invocation, String credential) {
                return Mono.error(new IllegalStateException("connection reset by peer at 10.0.0.1"));
            }

            public Mono<Boolean> healthCheck(String credential) {
                return Mono.just(true);
            }
        };
        ToolGateway withFlaky = new ToolGateway(
                List.of(flaky), new ArgumentValidator(json), new ResiliencePresets(ToolGatewayTest.properties()));

        ToolResult read = withFlaky.invoke(invocation("org", list, "{}"), ApprovalDecision.PROCEED, null).block();
        ToolResult write = withFlaky.invoke(invocation("org", create, "{}"), ApprovalDecision.PROCEED, null).block();

        assertThat(read.summary()).isEqualTo("flaky did not answer as expected. Try again shortly.");
        assertThat(write.summary())
                .isEqualTo("flaky did not answer as expected. Check flaky before trying again, in case it went through.");
    }

    @Test
    @DisplayName("near misses a live model sent in real runs are put right before the call, not refused")
    void nearMissesArePutRight() {
        String org = "org-near-miss";
        // Each of these was refused in a real run, and two of the runs ended because of it.
        ToolResult search = approved(org, "jira", "search_issues", "{\"jql\":\"project = OPS\",\"limit\":\"10\"}");
        ToolResult limited = approved(org, "salesforce", "list_opportunities", "{\"limit\":\"2\"}");
        ToolResult event = approved(org, "calendar", "create_event",
                "{\"title\":\"Review\",\"start\":\"2026-10-20T10:00:00+10:00\",\"end\":\"2026-10-20T11:00:00+10:00\","
                        + "\"attendees\":\"a@example.com, b@example.com\"}");
        ToolResult issue = approved(org, "github", "update_issue", "{\"repo\":\"acme/website\",\"id\":41,\"state\":\"closed\"}");
        ToolResult optional = approved(org, "hubspot", "search_contacts", "{\"query\":\"Riverside\",\"limit\":null}");

        assertThat(List.of(search, limited, event, issue, optional)).allMatch(ToolResult::isSuccess);
        assertThat(read(limited.contentJson()).path("count").asInt()).isEqualTo(2);
        assertThat(read(event.contentJson()).path("attendees").toString()).isEqualTo("[\"a@example.com\",\"b@example.com\"]");
        assertThat(read(issue.contentJson()).path("state").asText()).isEqualTo("closed");

        // What does not convert cleanly is still refused, by name.
        ToolResult wrong = approved(org, "jira", "search_issues", "{\"limit\":\"ten\"}");
        assertThat(wrong.summary()).isEqualTo(
                "The arguments do not match the tool's schema: limit must be a whole number, not text");
    }

    private ToolResult approved(String org, String server, String tool, String arguments) {
        ToolDefinition definition = servers.get(server).tool(tool).orElseThrow();
        return gateway.invoke(invocation(org, definition, arguments), ApprovalDecision.PROCEED, null).block();
    }
}
