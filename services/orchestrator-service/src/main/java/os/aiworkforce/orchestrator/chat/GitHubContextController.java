// @find: attach a repository, repo picker, list GitHub repositories for chat, list branches, list tree, composer plus menu GitHub, /api/conversations/context/github
// @what: Read-only GitHub lookups behind the composer's "Attach a repository" picker: repositories, branches and folders of the workspace's live GitHub connection.
// @flow: Called by the Chat composer's repo picker; resolves the credential with ToolCredentialResolver and runs github__ read tools through ToolGateway.
package os.aiworkforce.orchestrator.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.policy.ApprovalDecision;
import os.aiworkforce.mcp.policy.ToolGateway;
import os.aiworkforce.orchestrator.service.ToolCredentialResolver;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * What a person picks from when attaching a repository to a message.
 *
 * <p>Only reads, and only against a live connection: the GitHub connector also has a practice
 * twin, and a picker that offered practice repositories as if they were the workspace's own would
 * attach something that does not exist. With no live connection the answer says so
 * ({@code connected: false}) and the composer points the person at Connectors.
 *
 * <p>Folders are read one at a time by {@link GitHubFolderReader}, so a large folder lists in full.
 */
@RestController
@RequestMapping("/api/conversations/context/github")
@Tag(name = "Chat")
public class GitHubContextController {

    private static final Logger log = LoggerFactory.getLogger(GitHubContextController.class);

    /** The connector server id whose credential and read tools are used. */
    static final String SERVER = "github";
    /** How long a connector read may take before the call is reported as unavailable. */
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** Accepted shape of a repository name, {@code owner/name}. */
    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9_.-]{1,100}$");
    /** Accepted shape of a branch name. */
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*$");
    /** Accepted shape of a folder path inside a repository. */
    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9_. -]+(?:/[A-Za-z0-9_. -]+)*$");

    /** Finds the workspace's live GitHub credential. */
    private final ToolCredentialResolver credentials;
    /** Runs the read-only GitHub tools. */
    private final ToolGateway tools;
    /** Writes tool arguments and reads tool results. */
    private final ObjectMapper json;
    /** Lists one folder completely. */
    private final GitHubFolderReader folders;

    /** Creates the controller over the credential resolver, the tool gateway, JSON and the folder reader. */
    public GitHubContextController(
            ToolCredentialResolver credentials, ToolGateway tools, ObjectMapper json, GitHubFolderReader folders) {
        this.credentials = credentials;
        this.tools = tools;
        this.json = json;
        this.folders = folders;
    }

    /**
     * The repositories response of {@code /repos}, with a flag and message for the not connected case.
     *
     * @param connected whether the workspace has a live GitHub connection
     * @param message why not, in plain words, when it has none
     */
    public record Repos(boolean connected, String message, List<Map<String, Object>> repos) {}

    /** The branch names response of {@code /branches}. */
    public record Branches(List<String> branches) {}

    /**
     * The folder listing response of {@code /tree}.
     *
     * @param available whether the connector can list folders; false means a path is typed instead
     * @param entries files and folders at {@code path}, each with {@code path} and {@code type}
     */
    public record Tree(boolean available, List<Map<String, Object>> entries) {}

    /**
     * Lists the repositories of the workspace's live GitHub connection.
     *
     * <p>Route: {@code GET /api/conversations/context/github/repos}. Permission: {@code CHAT_USE}. With
     * no live connection the answer is {@code connected: false} with a plain message and no repositories,
     * not an error. Otherwise it runs the {@code list_repos} tool, up to 100 items, with the stored token
     * and returns each repository's full name, owner, name, privacy, description and default branch. Read
     * only; no state is changed. A GitHub failure is reported as {@code UPSTREAM_UNAVAILABLE} or as a
     * validation error carrying GitHub's summary.
     */
    // @find: list repositories for the repo picker, GET /api/conversations/context/github/repos
    @GetMapping("/repos")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Repositories of the workspace's live GitHub connection, for attaching one to a message")
    public Repos repos() {
        UUID orgId = orgId();
        ToolCredentialResolver.Lookup lookup = credentials.resolve(orgId, SERVER);
        if (!(lookup instanceof ToolCredentialResolver.Connected connected)) {
            return new Repos(false, notConnectedMessage(lookup), List.of());
        }
        JsonNode result = call(orgId, "list_repos", Map.of("limit", 100), connected.token());
        List<Map<String, Object>> repos = new ArrayList<>();
        result.path("items").forEach(item -> {
            Map<String, Object> repo = new LinkedHashMap<>();
            repo.put("fullName", item.path("id").asText());
            repo.put("owner", item.path("owner").asText());
            repo.put("name", item.path("name").asText());
            repo.put("private", item.path("private").asBoolean());
            repo.put("description", item.path("description").asText(""));
            repo.put("defaultBranch", item.path("defaultBranch").asText(""));
            repos.add(repo);
        });
        return new Repos(true, null, repos);
    }

    /**
     * Lists the branch names of one repository.
     *
     * <p>Route: {@code GET /api/conversations/context/github/branches?repo=owner/name}. Permission:
     * {@code CHAT_USE}. The repository name is validated, then the {@code list_branches} tool is run with
     * the workspace's token. A workspace with no live connection receives a {@code CONFLICT} error whose
     * reason is {@code github_not_connected}. Read only.
     */
    // @find: list branches for the repo picker, GET /api/conversations/context/github/branches
    @GetMapping("/branches")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Branches of one repository, for attaching it to a message")
    public Branches branches(@RequestParam String repo) {
        UUID orgId = orgId();
        String token = requireConnected(orgId);
        JsonNode result = call(orgId, "list_branches", Map.of("repo", checkRepo(repo)), token);
        List<String> names = new ArrayList<>();
        result.path("items").forEach(item -> names.add(item.path("name").asText()));
        return new Branches(names);
    }

    /**
     * Lists the files and folders directly inside one folder of a repository.
     *
     * <p>Route: {@code GET /api/conversations/context/github/tree?repo=&ref=&path=}. Permission: {@code
     * CHAT_USE}. The branch and path are validated, and a path containing {@code ..} is refused. Delegates
     * to {@link GitHubFolderReader#list}. An unknown folder is a validation error; any other GitHub
     * failure is {@code UPSTREAM_UNAVAILABLE}; no live connection is a {@code CONFLICT}. Read only.
     */
    // @find: list folders for the repo picker, GET /api/conversations/context/github/tree
    @GetMapping("/tree")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Files and folders of one repository, for attaching part of it to a message")
    public Tree tree(
            @RequestParam String repo,
            @RequestParam(required = false) String ref,
            @RequestParam(required = false) String path) {
        UUID orgId = orgId();
        String token = requireConnected(orgId);
        String trimmedRef = ref == null || ref.isBlank() ? null : ref.trim();
        if (trimmedRef != null && !REF.matcher(trimmedRef).matches()) {
            throw ApiException.validation("ref", "The branch name has characters that cannot be used.");
        }
        String folder = null;
        if (path != null && !path.isBlank()) {
            String trimmed = path.replaceAll("^/+|/+$", "");
            if (!trimmed.isEmpty()) {
                if (!PATH.matcher(trimmed).matches() || trimmed.contains("..")) {
                    throw ApiException.validation("path", "The path has characters that cannot be used.");
                }
                folder = trimmed;
            }
        }
        try {
            GitHubFolderReader.Folder listed = folders.list(token, checkRepo(repo), trimmedRef, folder);
            return new Tree(true, listed.entries());
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException.NotFound missing) {
            throw ApiException.validation("path", "That folder is not in the repository on this branch.");
        } catch (ApiException refused) {
            throw refused;
        } catch (RuntimeException e) {
            log.warn("Reading a GitHub folder for the repo picker failed in workspace {}: {}", orgId, e.toString());
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "GitHub did not answer. Try again in a moment.");
        }
    }

    /** The live GitHub token of the workspace, or a {@code CONFLICT} error that says why there is none. */
    private String requireConnected(UUID orgId) {
        ToolCredentialResolver.Lookup lookup = credentials.resolve(orgId, SERVER);
        if (lookup instanceof ToolCredentialResolver.Connected connected) {
            return connected.token();
        }
        throw new ApiException(ErrorCode.CONFLICT, notConnectedMessage(lookup)).with("reason", "github_not_connected");
    }

    /** The plain-words reason shown when no usable GitHub credential exists. */
    private static String notConnectedMessage(ToolCredentialResolver.Lookup lookup) {
        if (lookup instanceof ToolCredentialResolver.ReconnectRequired reconnect) {
            return "GitHub needs reconnecting in Connectors: " + reconnect.message();
        }
        if (lookup instanceof ToolCredentialResolver.Unavailable) {
            return "The GitHub connection could not be checked right now. Try again in a moment.";
        }
        return "GitHub is not connected. Connect it in Connectors to attach a repository.";
    }

    /**
     * Runs one read-only GitHub tool and returns its JSON result.
     *
     * <p>Invokes the tool gateway as a pre-approved read tagged {@code chat-context}, waiting at most
     * {@link #TIMEOUT}. No timeout, no answer or an unreadable answer becomes {@code UPSTREAM_UNAVAILABLE}; a
     * result that did not succeed becomes a validation error carrying GitHub's summary.
     */
    private JsonNode call(UUID orgId, String tool, Map<String, Object> arguments, String token) {
        String argumentsJson;
        try {
            argumentsJson = json.writeValueAsString(arguments);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        ToolInvocation invocation = new ToolInvocation(
                orgId.toString(),
                null,
                null,
                SERVER,
                tool,
                argumentsJson,
                "chat-context:" + UUID.randomUUID(),
                Map.of("purpose", "chat-context"));
        ToolResult result;
        try {
            result = tools.invoke(invocation, ApprovalDecision.PROCEED, token).block(TIMEOUT);
        } catch (RuntimeException e) {
            log.warn("GitHub {} for the repo picker failed in workspace {}: {}", tool, orgId, e.toString());
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "GitHub did not answer. Try again in a moment.");
        }
        if (result == null || result.status() != ToolResult.Status.SUCCEEDED) {
            String summary = result == null ? "GitHub did not answer." : result.summary();
            throw ApiException.validation("repo", summary == null || summary.isBlank() ? "GitHub refused the request." : summary);
        }
        try {
            return json.readTree(result.contentJson() == null ? "{}" : result.contentJson());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "GitHub's answer could not be read.");
        }
    }

    /** The trimmed repository name, or a validation error when it is not written as {@code owner/name}. */
    private static String checkRepo(String repo) {
        String trimmed = repo == null ? "" : repo.trim();
        if (!REPO.matcher(trimmed).matches()) {
            throw ApiException.validation("repo", "Write the repository as owner/name, for example acme/website.");
        }
        return trimmed;
    }

    /** The workspace of the current request, taken from the request context. */
    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
