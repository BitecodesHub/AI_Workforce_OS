// @find: linear, issues, tickets, teams, list issues, get issue, create issue, update issue, GraphQL, API key, live adapter, real API, engineering
// @what: Live Linear connector: runs linear__ tools (list, get, create, update issues) against the Linear GraphQL API with an API key.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin (case "linear"); ToolGateway.invoke reaches it through LiveServerAdapter.invoke; every tool is one or two POSTs of a GraphQL document to api.linear.app/graphql; base class LiveServerAdapter
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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * Linear's GraphQL API with a personal API key.
 *
 * <p>GraphQL reports most failures as an {@code errors} array beside a 200, so every answer is
 * checked for one before its data is used.
 *
 * <p>Used by the tool gateway when a workspace has stored a Linear API key. list_issues and get_issue
 * are read tools; create_issue and update_issue are write tools, so a person is asked first when the
 * workspace policy says so. There is no destructive or outbound tool: issues are never deleted.
 *
 * <p>Failures: a missing argument, an unknown team, issue or state, or a mutation that Linear does
 * not confirm becomes a plain failed result; a rejected key asks an administrator to connect again.
 * Nothing is retried, so a dropped connection on a mutation is reported as indeterminate.
 */
public final class LinearAdapter extends LiveServerAdapter {

    /** The production Linear API host; tests pass another base URL to the constructor. */
    public static final String BASE_URL = "https://api.linear.app";

    /** The GraphQL selection shared by every issue query and mutation answer. */
    private static final String ISSUE_FIELDS =
            "id identifier title description priority url createdAt updatedAt state { name } team { key } assignee { name }";

    /** Registers the four Linear tools. The sandbox supplies the tool definitions. */
    public LinearAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Linear", json, http, baseUrl);
        on("list_issues", this::listIssues);
        on("get_issue", this::getIssue);
        on("create_issue", this::createIssue);
        on("update_issue", this::updateIssue);
    }

    /** Sends the personal API key as the Authorization header without a Bearer prefix. */
    @Override
    protected void authorize(HttpHeaders headers, String key) {
        // A personal API key goes in the header as it is; an OAuth token would carry "Bearer".
        headers.set(HttpHeaders.AUTHORIZATION, key);
    }

    /** Connection check: GraphQL query for the viewer and organisation; answers the name with the organisation. */
    @Override
    protected Mono<String> whoAmI(String key) {
        return graphql(key, "query { viewer { name } organization { name } }", json.createObjectNode())
                .map(data -> {
                    String name = data.path("viewer").path("name").asText("Linear user");
                    String organisation = data.path("organization").path("name").asText("");
                    return organisation.isEmpty() ? name : name + " (" + organisation + ")";
                });
    }

    // @find: Linear list issues, tool linear__list_issues, live Linear call
    /**
     * Read tool. One GraphQL query, newest update first, at most 100 issues, optionally filtered by
     * title text, state name or team key (all ignoring case).
     */
    private Mono<ToolResult> listIssues(ToolInvocation invocation, JsonNode arguments, String key) {
        ObjectNode variables = json.createObjectNode();
        variables.put("first", limit(arguments, 25, 100));
        ObjectNode filter = variables.putObject("filter");
        String query = text(arguments, "query");
        if (query != null) {
            filter.putObject("title").put("containsIgnoreCase", query);
        }
        String state = text(arguments, "state");
        if (state != null) {
            filter.putObject("state").putObject("name").put("eqIgnoreCase", state);
        }
        String team = text(arguments, "team");
        if (team != null) {
            filter.putObject("team").putObject("key").put("eqIgnoreCase", team);
        }
        String document = "query($first: Int!, $filter: IssueFilter) { issues(first: $first, filter: $filter, "
                + "orderBy: updatedAt) { nodes { " + ISSUE_FIELDS + " } } }";
        return graphql(key, document, variables).map(data -> {
            ArrayNode items = json.createArrayNode();
            data.path("issues").path("nodes").forEach(issue -> items.add(issue(issue, false)));
            return done(listOf(items), "Read " + items.size() + " issue(s) from Linear.");
        });
    }

    // @find: Linear get issue, tool linear__get_issue, live Linear call
    /** Read tool. One GraphQL query for one issue, including its description; fails with a plain message when the key cannot see it. */
    private Mono<ToolResult> getIssue(ToolInvocation invocation, JsonNode arguments, String key) {
        String id = required(arguments, "id");
        return issueById(key, id, ISSUE_FIELDS).map(issue -> done(
                issue(issue, true), "Read " + issue.path("identifier").asText(id) + " from Linear."));
    }

    // @find: Linear create issue, tool linear__create_issue, live Linear call
    /**
     * Write tool. Two calls: a query for the teams, to choose the team, then the issueCreate mutation.
     * A team is chosen automatically only when there is exactly one. Not idempotent: a dropped
     * connection is reported as indeterminate and is not repeated.
     */
    private Mono<ToolResult> createIssue(ToolInvocation invocation, JsonNode arguments, String key) {
        String title = required(arguments, "title");
        return teamId(key, text(arguments, "team")).flatMap(teamId -> {
            ObjectNode input = json.createObjectNode();
            input.put("teamId", teamId);
            input.put("title", title);
            copy(arguments, input);
            ObjectNode variables = json.createObjectNode();
            variables.set("input", input);
            String document = "mutation($input: IssueCreateInput!) { issueCreate(input: $input) { success issue { "
                    + ISSUE_FIELDS + " } } }";
            return graphql(key, document, variables).map(data -> {
                JsonNode issue = confirmed(data.path("issueCreate"), "created");
                return done(issue(issue, true), "Created " + issue.path("identifier").asText() + " in Linear.");
            });
        });
    }

    // @find: Linear update issue, tool linear__update_issue, live Linear call
    /**
     * Write tool. Two calls: a query for the issue and the states of its team, then the issueUpdate
     * mutation with the title, description, priority and state that were given. A state is matched by
     * name, ignoring case. Refuses before the mutation when nothing is given to change.
     */
    private Mono<ToolResult> updateIssue(ToolInvocation invocation, JsonNode arguments, String key) {
        String id = required(arguments, "id");
        String state = text(arguments, "state");
        // Resolved first: the update needs the issue's own id, and a state is named per team.
        return issueById(key, id, "id identifier team { states { nodes { id name } } }").flatMap(found -> {
            ObjectNode input = json.createObjectNode();
            copy(arguments, input);
            String title = text(arguments, "title");
            if (title != null) {
                input.put("title", title);
            }
            if (state != null) {
                input.put("stateId", stateId(found, state));
            }
            if (input.isEmpty()) {
                throw new VendorException("Say what to change: the state, title, description or priority.");
            }
            ObjectNode variables = json.createObjectNode();
            variables.put("id", found.path("id").asText());
            variables.set("input", input);
            String document = "mutation($id: String!, $input: IssueUpdateInput!) { issueUpdate(id: $id, input: $input) "
                    + "{ success issue { " + ISSUE_FIELDS + " } } }";
            return graphql(key, document, variables).map(data -> {
                JsonNode issue = confirmed(data.path("issueUpdate"), "updated");
                return done(issue(issue, true), "Updated " + issue.path("identifier").asText(id) + " in Linear.");
            });
        });
    }

    /** Queries one issue with the given fields; fails with a plain message when it is missing or not visible to the key. */
    private Mono<JsonNode> issueById(String key, String id, String fields) {
        ObjectNode variables = json.createObjectNode();
        variables.put("id", id);
        return graphql(key, "query($id: String!) { issue(id: $id) { " + fields + " } }", variables)
                .map(data -> {
                    JsonNode issue = data.path("issue");
                    if (issue.isMissingNode() || issue.isNull()) {
                        throw new VendorException("Linear has no issue " + id + " that this key can see.");
                    }
                    return issue;
                });
    }

    /* Linear needs a team for every issue. One team needs no choosing; several do. */
    /**
     * Resolves the team of a new issue by key or name from the first fifty teams. Without a name, the
     * only team is used; with several, the person is asked to say which.
     */
    private Mono<String> teamId(String key, String team) {
        return graphql(key, "query { teams(first: 50) { nodes { id key name } } }", json.createObjectNode())
                .map(data -> {
                    List<JsonNode> teams = new ArrayList<>();
                    data.path("teams").path("nodes").forEach(teams::add);
                    if (team != null) {
                        return teams.stream()
                                .filter(candidate -> team.equalsIgnoreCase(candidate.path("key").asText())
                                        || team.equalsIgnoreCase(candidate.path("name").asText()))
                                .map(candidate -> candidate.path("id").asText())
                                .findFirst()
                                .orElseThrow(() -> new VendorException(
                                        "Linear has no team called " + team + ". Teams: " + keys(teams) + "."));
                    }
                    if (teams.size() == 1) {
                        return teams.getFirst().path("id").asText();
                    }
                    throw new VendorException("Say which Linear team the issue belongs to: " + keys(teams) + ".");
                });
    }

    /** Finds the id of a named workflow state among the states of the issue team; lists the valid names when it is unknown. */
    private static String stateId(JsonNode issue, String state) {
        List<String> names = new ArrayList<>();
        for (JsonNode candidate : issue.path("team").path("states").path("nodes")) {
            names.add(candidate.path("name").asText());
            if (state.equalsIgnoreCase(candidate.path("name").asText())) {
                return candidate.path("id").asText();
            }
        }
        throw new VendorException("Linear has no state called " + state + " for this team. States: "
                + String.join(", ", names) + ".");
    }

    /** Copies the optional description and numeric priority of the arguments into a mutation input. */
    private static void copy(JsonNode arguments, ObjectNode input) {
        String description = text(arguments, "description");
        if (description != null) {
            input.put("description", description);
        }
        if (arguments.path("priority").isInt()) {
            input.put("priority", arguments.path("priority").asInt());
        }
    }

    /** The team keys as a comma separated list, for error messages. */
    private static String keys(List<JsonNode> teams) {
        return String.join(", ", teams.stream().map(team -> team.path("key").asText()).toList());
    }

    /** Maps a Linear issue to the plain shape the tools return; the description is added only when asked for. */
    private ObjectNode issue(JsonNode issue, boolean withDescription) {
        ObjectNode item = json.createObjectNode();
        item.put("id", issue.path("identifier").asText(issue.path("id").asText()));
        item.put("title", issue.path("title").asText());
        item.put("state", issue.path("state").path("name").asText());
        item.put("team", issue.path("team").path("key").asText());
        item.put("priority", issue.path("priority").asInt());
        item.put("assignee", issue.path("assignee").path("name").asText(null));
        item.put("url", issue.path("url").asText());
        item.put("updatedAt", issue.path("updatedAt").asText());
        if (withDescription) {
            item.put("description", issue.path("description").asText(""));
        }
        return item;
    }

    /**
     * Posts one GraphQL document and returns its {@code data}. Linear reports failures as an
     * {@code errors} array, with HTTP 200 or 400, so the array is read from either; an authentication
     * error asks for the connection to be made again, any other error is passed on in one sentence.
     */
    private Mono<JsonNode> graphql(String key, String document, ObjectNode variables) {
        ObjectNode body = json.createObjectNode();
        body.put("query", document);
        body.set("variables", variables);
        return send(HttpMethod.POST, key, body, "/graphql")
                // Linear sends GraphQL errors, a rejected key among them, with HTTP 400 as well as 200.
                .onErrorResume(WebClientResponseException.BadRequest.class, refused -> {
                    JsonNode answer = graphqlErrors(refused.getResponseBodyAsString());
                    return answer == null ? Mono.error(refused) : Mono.just(answer);
                })
                .map(answer -> {
                    JsonNode errors = answer.path("errors");
                    if (errors.isArray() && !errors.isEmpty()) {
                        String message = errors.path(0).path("message").asText("unknown error");
                        JsonNode extensions = errors.path(0).path("extensions");
                        String type = (extensions.path("type").asText("") + " " + extensions.path("code").asText(""))
                                .toLowerCase(Locale.ROOT);
                        throw new VendorException(
                                type.contains("authentication")
                                        ? "Linear rejected the stored key. An administrator needs to connect Linear again."
                                        : "Linear did not accept the request: " + message);
                    }
                    return answer.path("data");
                });
    }

    /** Reads the body of a 400 answer and returns it only when it holds a GraphQL errors array; otherwise null. */
    private JsonNode graphqlErrors(String body) {
        try {
            JsonNode answer = json.readTree(body);
            return answer != null && answer.path("errors").isArray() && !answer.path("errors").isEmpty()
                    ? answer
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

    /* A mutation can answer success false with no error; nothing was changed then. */
    /** Returns the issue of a mutation payload, or refuses when Linear did not confirm success. */
    private static JsonNode confirmed(JsonNode payload, String what) {
        if (!payload.path("success").asBoolean(false) || !payload.path("issue").isObject()) {
            throw new VendorException("Linear did not confirm that the issue was " + what + ".");
        }
        return payload.path("issue");
    }
}
