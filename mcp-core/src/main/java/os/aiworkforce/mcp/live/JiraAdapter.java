package os.aiworkforce.mcp.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * Jira Cloud's REST API v3 with an Atlassian account email and API token.
 *
 * <p>The stored credential is JSON: {@code {"site":"https://acme.atlassian.net","email":"...","token":"..."}}.
 * The site is rebuilt through {@link Hosts} on every call, so it can only ever name an Atlassian host.
 */
public final class JiraAdapter extends LiveServerAdapter {

    /** The address comes from the credential's site. */
    public static final String BASE_URL = null;

    private static final String FIELDS = "summary,status,issuetype,assignee,priority,updated";

    private final boolean fixedBase;

    public JiraAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Jira", json, http, baseUrl);
        this.fixedBase = baseUrl != null;
        on("search_issues", this::searchIssues);
        on("create_issue", this::createIssue);
        on("update_issue", this::updateIssue);
    }

    private record Account(String base, String email, String token) {}

    private Account account(String credential) {
        JsonNode node;
        try {
            node = json.readTree(credential);
        } catch (Exception e) {
            node = null;
        }
        if (node == null || !node.isObject()) {
            throw new VendorException("The Jira credential must be JSON with the site, email and token.");
        }
        String site = text(node, "site");
        String email = text(node, "email");
        String token = text(node, "token");
        if (site == null || email == null || token == null) {
            throw new VendorException("The Jira credential needs a site, an email and an API token.");
        }
        try {
            return new Account(Hosts.atlassianBase(site), email, token);
        } catch (IllegalArgumentException e) {
            throw new VendorException(e.getMessage());
        }
    }

    @Override
    protected String baseFor(String credential) {
        Account account = account(credential);
        return fixedBase ? null : account.base();
    }

    @Override
    protected void authorize(HttpHeaders headers, String credential) {
        Account account = account(credential);
        headers.setBasicAuth(account.email(), account.token());
        headers.set(HttpHeaders.ACCEPT, "application/json");
    }

    @Override
    protected Mono<String> whoAmI(String credential) {
        Account account = account(credential);
        return get(credential, "/rest/api/3/myself").map(me -> {
            String name = me.path("displayName").asText(me.path("emailAddress").asText("Jira user"));
            return name + " on " + account.base().substring("https://".length());
        });
    }

    private Mono<ToolResult> searchIssues(ToolInvocation invocation, JsonNode arguments, String credential) {
        String jql = jql(text(arguments, "jql"));
        int limit = limit(arguments, 20, 50);
        Account account = account(credential);
        return get(credential, "/rest/api/3/search/jql?jql={jql}&maxResults={max}&fields={fields}", jql, limit, FIELDS)
                .map(answer -> {
                    ArrayNode items = json.createArrayNode();
                    answer.path("issues").forEach(issue -> items.add(issue(issue, account.base())));
                    return done(listOf(items), "Found " + items.size() + " issue(s) in Jira.");
                });
    }

    /* Text that is not already a query is searched for; a query is passed on as written. */
    private static String jql(String input) {
        if (input == null) {
            return "updated >= -30d ORDER BY updated DESC";
        }
        String lower = input.toLowerCase(Locale.ROOT);
        boolean query = input.matches(".*[=~<>!].*")
                || lower.contains(" in (")
                || lower.contains(" is ")
                || lower.contains("order by");
        return query ? input : "text ~ \"" + escape(input) + "\" ORDER BY updated DESC";
    }

    private Mono<ToolResult> createIssue(ToolInvocation invocation, JsonNode arguments, String credential) {
        String project = required(arguments, "project");
        String summary = required(arguments, "summary");
        String type = text(arguments, "type");
        String description = text(arguments, "description");
        ObjectNode body = json.createObjectNode();
        ObjectNode fields = body.putObject("fields");
        fields.putObject("project").put("key", project);
        fields.put("summary", summary);
        fields.putObject("issuetype").put("name", type == null ? "Task" : type);
        if (description != null) {
            fields.set("description", document(description));
        }
        Account account = account(credential);
        return send(HttpMethod.POST, credential, body, "/rest/api/3/issue").map(created -> {
            ObjectNode item = json.createObjectNode();
            String key = created.path("key").asText();
            item.put("id", key);
            item.put("title", summary);
            item.put("url", account.base() + "/browse/" + key);
            return done(item, "Created " + key + " in Jira.");
        });
    }

    private Mono<ToolResult> updateIssue(ToolInvocation invocation, JsonNode arguments, String credential) {
        String id = required(arguments, "id");
        String status = text(arguments, "status");
        if (status == null) {
            throw new VendorException("Say what to change: the status.");
        }
        return get(credential, "/rest/api/3/issue/{id}/transitions", id).flatMap(answer -> {
            List<String> names = new ArrayList<>();
            String transitionId = null;
            String reached = status;
            for (JsonNode transition : answer.path("transitions")) {
                String target = transition.path("to").path("name").asText(transition.path("name").asText());
                names.add(target);
                if (transitionId == null
                        && (status.equalsIgnoreCase(transition.path("name").asText())
                                || status.equalsIgnoreCase(target))) {
                    transitionId = transition.path("id").asText();
                    reached = target;
                }
            }
            if (transitionId == null) {
                String available = names.isEmpty() ? "none right now" : String.join(", ", names);
                return Mono.just(ToolResult.failed("Jira cannot move " + id + " to " + status
                        + " from its current status. Available statuses: " + available + "."));
            }
            ObjectNode body = json.createObjectNode();
            body.putObject("transition").put("id", transitionId);
            String finalStatus = reached;
            return send(HttpMethod.POST, credential, body, "/rest/api/3/issue/{id}/transitions", id).map(ignored -> {
                ObjectNode item = json.createObjectNode();
                item.put("id", id);
                item.put("status", finalStatus);
                return done(item, "Moved " + id + " to " + finalStatus + " in Jira.");
            });
        });
    }

    private ObjectNode issue(JsonNode issue, String base) {
        JsonNode fields = issue.path("fields");
        ObjectNode item = json.createObjectNode();
        String key = issue.path("key").asText();
        item.put("id", key);
        item.put("title", fields.path("summary").asText());
        item.put("status", fields.path("status").path("name").asText());
        item.put("type", fields.path("issuetype").path("name").asText());
        item.put("priority", fields.path("priority").path("name").asText(null));
        item.put("assignee", fields.path("assignee").path("displayName").asText(null));
        item.put("updatedAt", fields.path("updated").asText());
        item.put("url", base + "/browse/" + key);
        return item;
    }

    /* Atlassian Document Format: one paragraph per blank-line-separated block. */
    private ObjectNode document(String content) {
        ObjectNode doc = json.createObjectNode();
        doc.put("type", "doc");
        doc.put("version", 1);
        ArrayNode blocks = doc.putArray("content");
        for (String paragraph : content.split("\\n\\s*\\n")) {
            String line = paragraph.strip();
            if (line.isEmpty()) {
                continue;
            }
            ObjectNode block = blocks.addObject();
            block.put("type", "paragraph");
            block.putArray("content").addObject().put("type", "text").put("text", line);
        }
        return doc;
    }

    private static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
