// @find: jira, atlassian, issues, tickets, search issues, create issue, update issue, JQL, site email api token, live adapter, real API, project management
// @what: Live Jira connector: runs jira__ tools (search, create, update issues) against an Atlassian Cloud site using site, email and API token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin (case "jira"); ToolGateway.invoke reaches it through LiveServerAdapter.invoke; the site address comes from the stored credential through baseFor; each tool makes one or two calls to the Atlassian site; base class LiveServerAdapter
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
 *
 * <p>Used by the tool gateway when a workspace has stored a Jira credential. search_issues is a read
 * tool; create_issue and update_issue are write tools, so a person is asked first when the workspace
 * policy says so. update_issue only moves an issue to another status. There is no destructive or
 * outbound tool.
 *
 * <p>Failures: a malformed credential, a missing argument or a status that is not reachable becomes
 * a plain failed result; HTTP errors are described by the base class. Nothing is retried.
 */
public final class JiraAdapter extends LiveServerAdapter {

    /** The address comes from the credential's site. */
    /** Null on purpose: the real address is taken from the site in each stored credential. */
    public static final String BASE_URL = null;

    /** The issue fields requested in a search. */
    private static final String FIELDS = "summary,status,issuetype,assignee,priority,updated";

    /** True when a base URL was injected (tests), in which case the credential site is not used for routing. */
    private final boolean fixedBase;

    /** Registers the three Jira tools. A non-null base URL fixes the address and bypasses the site in the credential. */
    public JiraAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Jira", json, http, baseUrl);
        this.fixedBase = baseUrl != null;
        on("search_issues", this::searchIssues);
        on("create_issue", this::createIssue);
        on("update_issue", this::updateIssue);
    }

    /** The parsed credential: a validated site address, the account email and the API token. */
    private record Account(String base, String email, String token) {}

    /**
     * Parses the stored JSON credential and checks the site with {@link Hosts#atlassianBase}, so only
     * Atlassian Cloud addresses are ever called. Throws a plain refusal for malformed or incomplete input.
     */
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

    /** The Atlassian site of this credential, or null when a fixed base URL is in use. */
    @Override
    protected String baseFor(String credential) {
        Account account = account(credential);
        return fixedBase ? null : account.base();
    }

    /** Sends the email and API token as HTTP basic authentication. */
    @Override
    protected void authorize(HttpHeaders headers, String credential) {
        Account account = account(credential);
        headers.setBasicAuth(account.email(), account.token());
        headers.set(HttpHeaders.ACCEPT, "application/json");
    }

    /** Connection check: GET the current user; answers the display name and the site host. */
    @Override
    protected Mono<String> whoAmI(String credential) {
        Account account = account(credential);
        return get(credential, "/rest/api/3/myself").map(me -> {
            String name = me.path("displayName").asText(me.path("emailAddress").asText("Jira user"));
            return name + " on " + account.base().substring("https://".length());
        });
    }

    // @find: Jira search issues, tool jira__search_issues, live Jira call
    /**
     * Read tool. One search call with a JQL query, at most 50 issues. With no input the last thirty
     * days of changes are listed. Answers key, title, status, type, priority, assignee, update time and link.
     */
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
    /** Decides whether the input is already JQL (it contains an operator, "in (", "is" or "order by"). If not, it is escaped and searched as text. */
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

    // @find: Jira create issue, tool jira__create_issue, live Jira call
    /**
     * Write tool. POST a new issue in the given project key; the type defaults to Task and the
     * description becomes a document with one paragraph per block. Not idempotent: a dropped connection
     * is reported as indeterminate and is not repeated, so no duplicate issue is created.
     */
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

    // @find: Jira update issue, tool jira__update_issue, live Jira call
    /**
     * Write tool. Two calls: GET the transitions available from the current status, then POST the one
     * whose name or target status matches (ignoring case). When none matches, it fails and lists the
     * statuses that are available. Refuses before any call when no status is given.
     */
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

    /** Maps a Jira issue to the plain shape the tools return, with a browse link built from the site. */
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
    /** Builds the document format Jira expects for a description: one paragraph per blank-line separated block. */
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

    /** Escapes backslashes and quotes so a value stays inside its JQL string. */
    private static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
