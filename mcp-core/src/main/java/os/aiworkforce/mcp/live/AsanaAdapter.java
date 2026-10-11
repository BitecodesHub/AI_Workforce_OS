// @find: asana, asana tasks, project tasks, task management, list tasks, get task, create task, update task, delete task, personal access token, live adapter, real API, work management
// @what: Live Asana connector: turns the asana__ tools (list, get, create, update, delete task) into real calls to the Asana REST API using a pasted personal access token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin (case "asana"); ToolGateway.invoke reaches it through LiveServerAdapter.invoke, which looks up the tool in the table filled by on(...); each tool makes one or two HTTP calls to app.asana.com; base class LiveServerAdapter
package os.aiworkforce.mcp.live;

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
 * Asana's API 1.0 with a personal access token.
 *
 * <p>Asana wraps every request and answer in a {@code data} object; the wrapping is added and
 * removed here so the tools see plain objects.
 *
 * <p>Used by the tool gateway whenever a workspace has stored an Asana token; without a token the
 * sandbox twin answers instead. Side-effect classes follow the sandbox declaration: list_tasks and
 * get_task are read, create_task and update_task are write (a person is asked first when the
 * workspace policy says so), and delete_task is destructive and always asks a person first.
 *
 * <p>Failures: missing arguments and an Asana workspace that cannot be found become a plain
 * failed result; HTTP errors are described by the base class. No call is retried here.
 */
public final class AsanaAdapter extends LiveServerAdapter {

    /** The production Asana API address; tests pass another base URL to the constructor. */
    public static final String BASE_URL = "https://app.asana.com/api/1.0";

    /** The task fields requested for list and write answers, so the answer stays small. */
    private static final String TASK_FIELDS = "name,completed,due_on,assignee.name,permalink_url";
    /** The task fields for a single task read, which adds the notes and the projects. */
    private static final String TASK_FIELDS_FULL = TASK_FIELDS + ",notes,projects.name";

    /** Registers the five Asana tools. The sandbox supplies the tool definitions; the base URL is injected so tests can point at a local server. */
    public AsanaAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Asana", json, http, baseUrl);
        on("list_tasks", this::listTasks);
        on("get_task", this::getTask);
        on("create_task", this::createTask);
        on("update_task", this::updateTask);
        on("delete_task", this::deleteTask);
    }

    /** Sends the personal access token as a bearer token and asks for a JSON answer. */
    @Override
    protected void authorize(HttpHeaders headers, String token) {
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, "application/json");
    }

    /** Connection check: GET /users/me; answers the user name, with the email when Asana gives one. */
    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, "/users/me").map(answer -> {
            JsonNode user = answer.path("data");
            String name = user.path("name").asText("Asana user");
            String email = user.path("email").asText("");
            return email.isEmpty() ? name : name + " (" + email + ")";
        });
    }

    // @find: Asana list tasks, tool asana__list_tasks, live Asana call
    /**
     * Read tool. Lists tasks of a project, or of an assignee (default: the token owner) within the
     * token's first workspace. Calls GET /tasks, preceded by GET /users/me when no project is named.
     * The optional completed filter is applied here, after the answer, so it can return fewer than the limit.
     */
    private Mono<ToolResult> listTasks(ToolInvocation invocation, JsonNode arguments, String token) {
        String project = text(arguments, "project");
        String assignee = text(arguments, "assignee");
        JsonNode completed = arguments.path("completed");
        int limit = limit(arguments, 25, 100);
        Mono<JsonNode> answer;
        if (project != null) {
            answer = get(token, "/tasks?project={project}&limit={n}&opt_fields={fields}", project, limit, TASK_FIELDS);
        } else {
            // Tasks of a person are listed within a workspace; the token's first one is used.
            String who = assignee == null ? "me" : assignee;
            answer = workspace(token).flatMap(workspace -> get(
                    token,
                    "/tasks?assignee={who}&workspace={workspace}&limit={n}&opt_fields={fields}",
                    who,
                    workspace,
                    limit,
                    TASK_FIELDS));
        }
        return answer.map(body -> {
            ArrayNode items = json.createArrayNode();
            body.path("data").forEach(task -> {
                if (completed.isBoolean() && task.path("completed").asBoolean() != completed.asBoolean()) {
                    return;
                }
                items.add(task(task, false));
            });
            return done(listOf(items), "Read " + items.size() + " task(s) from Asana.");
        });
    }

    // @find: Asana get task, tool asana__get_task, live Asana call
    /** Read tool. GET /tasks/{id} with the full field set; fails with a plain message when the id is missing. */
    private Mono<ToolResult> getTask(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = required(arguments, "id");
        return get(token, "/tasks/{id}?opt_fields={fields}", id, TASK_FIELDS_FULL)
                .map(answer -> done(task(answer.path("data"), true), "Read the Asana task \""
                        + answer.path("data").path("name").asText() + "\"."));
    }

    // @find: Asana create task, tool asana__create_task, live Asana call
    /**
     * Write tool. POST /tasks. A task with a project is attached to it; otherwise the first workspace of
     * the token is looked up first (GET /users/me). Not idempotent: a dropped connection is reported as
     * indeterminate and the call is not repeated, so no duplicate task is created.
     */
    private Mono<ToolResult> createTask(ToolInvocation invocation, JsonNode arguments, String token) {
        String name = required(arguments, "name");
        String project = text(arguments, "project");
        ObjectNode data = json.createObjectNode();
        data.put("name", name);
        put(data, "notes", text(arguments, "notes"));
        put(data, "assignee", text(arguments, "assignee"));
        put(data, "due_on", text(arguments, "dueOn"));
        Mono<ObjectNode> prepared;
        if (project != null) {
            data.putArray("projects").add(project);
            prepared = Mono.just(data);
        } else {
            // A task outside a project must say which workspace it belongs to.
            prepared = workspace(token).map(workspace -> {
                data.put("workspace", workspace);
                return data;
            });
        }
        return prepared.flatMap(filled -> send(HttpMethod.POST, token, wrap(filled), "/tasks?opt_fields={fields}", TASK_FIELDS))
                .map(answer -> done(task(answer.path("data"), false), "Created the Asana task \"" + name + "\"."));
    }

    // @find: Asana update task, tool asana__update_task, live Asana call
    /**
     * Write tool. PUT /tasks/{id} with only the fields given. Refuses with a plain message when no
     * field to change is present, before any HTTP call is made.
     */
    private Mono<ToolResult> updateTask(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = required(arguments, "id");
        ObjectNode data = json.createObjectNode();
        put(data, "name", text(arguments, "name"));
        put(data, "assignee", text(arguments, "assignee"));
        put(data, "due_on", text(arguments, "dueOn"));
        if (arguments.path("completed").isBoolean()) {
            data.put("completed", arguments.path("completed").asBoolean());
        }
        if (data.isEmpty()) {
            throw new VendorException("Say what to change: the name, assignee, due date or whether it is complete.");
        }
        return send(HttpMethod.PUT, token, wrap(data), "/tasks/{id}?opt_fields={fields}", id, TASK_FIELDS)
                .map(answer -> done(task(answer.path("data"), false), "Updated the Asana task \""
                        + answer.path("data").path("name").asText() + "\"."));
    }

    // @find: Asana delete task, tool asana__delete_task, live Asana call
    /**
     * Destructive tool; a person always approves it first. DELETE /tasks/{id}; Asana moves the task to
     * the trash of the owner. The answer only confirms the id.
     */
    private Mono<ToolResult> deleteTask(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = required(arguments, "id");
        return call(HttpMethod.DELETE, token, "/tasks/{id}", id).map(answer -> {
            ObjectNode result = json.createObjectNode();
            result.put("id", id);
            result.put("deleted", true);
            return done(result, "Deleted an Asana task.");
        });
    }

    /** The gid of the first workspace of the token, from GET /users/me; fails when the token shows none. */
    private Mono<String> workspace(String token) {
        return get(token, "/users/me").map(answer -> {
            String gid = answer.path("data").path("workspaces").path(0).path("gid").asText("");
            if (gid.isEmpty()) {
                throw new VendorException("Asana shows no workspace for this token. Name a project instead.");
            }
            return gid;
        });
    }

    /** Wraps a body in the {@code data} object that Asana expects on every request. */
    private ObjectNode wrap(ObjectNode data) {
        ObjectNode body = json.createObjectNode();
        body.set("data", data);
        return body;
    }

    /** Adds a text field only when a value was given, so an update never blanks a field. */
    private static void put(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    /** Maps an Asana task to the plain shape the tools return; the full form adds notes and project names. */
    private ObjectNode task(JsonNode task, boolean full) {
        ObjectNode item = json.createObjectNode();
        item.put("id", task.path("gid").asText());
        item.put("title", task.path("name").asText());
        item.put("status", task.path("completed").asBoolean() ? "complete" : "open");
        item.put("dueOn", task.path("due_on").asText(null));
        item.put("assignee", task.path("assignee").path("name").asText(null));
        item.put("url", task.path("permalink_url").asText(null));
        if (full) {
            item.put("notes", task.path("notes").asText(""));
            ArrayNode projects = item.putArray("projects");
            task.path("projects").forEach(project -> projects.add(project.path("name").asText()));
        }
        return item;
    }
}
