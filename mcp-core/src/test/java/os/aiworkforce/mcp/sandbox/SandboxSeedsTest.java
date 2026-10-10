// @find: tests for sandbox seeds, practice data, seeded records, first list returns data, sandbox verbs, create read back, update delete, every connector, demo data
// @what: Checks the practice data each workspace starts with and the verbs that change it.
package os.aiworkforce.mcp.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.spi.McpServerAdapter;

/** The practice data a workspace starts with, and the verbs that change it. */
class SandboxSeedsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, McpServerAdapter> servers = SandboxServerRegistry.servers(json, WebClient.builder(), true)
            .stream()
            .collect(java.util.stream.Collectors.toMap(McpServerAdapter::server, server -> server));

    @Test
    @DisplayName("every list or search tool returns seeded records on a first call")
    void firstListReturnsData() {
        servers.values().forEach(server -> server.tools().stream()
                .filter(tool -> tool.name().startsWith("list_") || tool.name().startsWith("search_"))
                .forEach(tool -> {
                    JsonNode result = content(call(server.server(), tool.name(), "{}", "org-seeds"));
                    assertThat(result.path("count").asInt()).as(tool.qualifiedName()).isPositive();
                }));
    }

    @Test
    @DisplayName("what one call creates, the next call lists, and each workspace has its own copy")
    void createThenList() {
        int before = content(call("hubspot", "list_contacts", "{}", "org-a")).path("count").asInt();

        call("hubspot", "create_contact", "{\"email\":\"new.person@example.com\",\"company\":\"Acme\"}", "org-a");

        assertThat(content(call("hubspot", "list_contacts", "{}", "org-a")).path("count").asInt())
                .isEqualTo(before + 1);
        assertThat(content(call("hubspot", "list_contacts", "{}", "org-b")).path("count").asInt())
                .isEqualTo(before);
    }

    @Test
    @DisplayName("a get without an id reads the list, the way get_pulls and get_messages are meant")
    void getWithoutIdLists() {
        JsonNode pulls = content(call("github", "get_pulls", "{\"repo\":\"acme/website\"}", "org-c"));
        JsonNode messages = content(call("slack", "get_messages", "{\"channel\":\"#support\"}", "org-c"));

        assertThat(pulls.path("count").asInt()).isEqualTo(2);
        assertThat(messages.path("count").asInt()).isEqualTo(1);
        assertThat(messages.path("items").path(0).path("channel").asText()).isEqualTo("support");
    }

    @Test
    @DisplayName("lists narrow by state, by free-text query and by limit")
    void listsFilter() {
        assertThat(content(call("github", "list_issues", "{\"repo\":\"acme/website\",\"state\":\"open\"}", "org-d"))
                        .path("count")
                        .asInt())
                .isEqualTo(2);
        JsonNode found = content(call("hubspot", "search_contacts", "{\"query\":\"Riverside\"}", "org-d"));
        assertThat(found.path("count").asInt()).isEqualTo(1);
        assertThat(found.path("items").path(0).path("lastName").asText()).isEqualTo("Ng");
        assertThat(content(call("asana", "list_tasks", "{\"limit\":2}", "org-d")).path("count").asInt())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("update changes the record with that id, and an unknown id is a plain failure")
    void updateById() {
        ToolResult updated = call("zendesk", "update_ticket", "{\"id\":\"9002\",\"status\":\"pending\"}", "org-e");
        ToolResult missing = call("zendesk", "update_ticket", "{\"id\":\"nope\",\"status\":\"pending\"}", "org-e");

        assertThat(content(updated).path("status").asText()).isEqualTo("pending");
        assertThat(content(call("zendesk", "get_ticket", "{\"id\":\"9002\"}", "org-e")).path("status").asText())
                .isEqualTo("pending");
        assertThat(missing.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(missing.summary()).isEqualTo("No ticket record with id nope.");
    }

    @Test
    @DisplayName("delete removes the record, and refund marks a payment refunded without moving money")
    void destructiveVerbs() {
        int tasks = content(call("asana", "list_tasks", "{}", "org-f")).path("count").asInt();
        call("asana", "delete_task", "{\"id\":\"1209001\"}", "org-f");
        assertThat(content(call("asana", "list_tasks", "{}", "org-f")).path("count").asInt())
                .isEqualTo(tasks - 1);

        ToolResult refund = call("stripe", "refund_payment", "{\"id\":\"pi_1002\"}", "org-f");
        assertThat(refund.summary()).contains("No money moved");
        assertThat(content(refund).path("status").asText()).isEqualTo("refunded");
        assertThat(content(refund).path("refundedAmount").asDouble()).isEqualTo(500.0);
    }

    @Test
    @DisplayName("collection names are singular whichever form the tool uses")
    void singularCollections() {
        assertThat(List.of("issues", "issue", "opportunities", "pulls", "invoices", "messages", "voice_note"))
                .extracting(SandboxServerAdapter::singular)
                .containsExactly("issue", "issue", "opportunity", "pull", "invoice", "message", "voice_note");
    }

    private ToolResult call(String server, String tool, String arguments, String org) {
        return servers.get(server)
                .invoke(new ToolInvocation(org, "agent-1", "run-1", server, tool, arguments, "key", Map.of()), null)
                .block();
    }

    private JsonNode content(ToolResult result) {
        assertThat(result.isSuccess()).as(result.summary()).isTrue();
        try {
            return json.readTree(result.contentJson());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
