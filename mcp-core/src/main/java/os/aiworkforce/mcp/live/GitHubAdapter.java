// @find: github, repositories, repos, issues, pull requests, pulls, branches, comments, create issue, update issue, create repo, create branch, add comment, personal access token, live adapter, real API, git
// @what: Live GitHub connector: runs github__ tools (issues, pull requests, repos, branches, comments, files, commits, trees) against the GitHub REST API with a pasted personal access token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin; invoked through ToolGateway.invoke; base class LiveServerAdapter
package os.aiworkforce.mcp.live;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;

/**
 * GitHub's REST API with a personal access token.
 *
 * <p>Registers 22 tools (issues, pull requests, repositories, branches, comments, files, commits and
 * trees). Every owner, repository, branch and path argument is checked against a strict pattern
 * before it is placed in a request address, so a crafted value cannot reach a different API path.
 * Writes to a repository's default branch always wait for a person
 * ({@link #approvalReason}), and merging a pull request is an outbound action. Deleting a file is
 * destructive. Created for each connection by the SandboxServerRegistry and called through the gateway.
 */
public final class GitHubAdapter extends LiveServerAdapter {

    /** GitHub's API address; tests pass a stand-in. */
    public static final String BASE_URL = "https://api.github.com";

    /** owner/name of a repository. */
    private static final Pattern REPO = Pattern.compile("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$");
    /** A new repository name. */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    /** A GitHub user or organisation name. */
    private static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$");
    /* A branch or a file path: safe characters only, so it can sit in the request path as written. */
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*$");
    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9_. -]+(?:/[A-Za-z0-9_. -]+)*$");
    /* More of a file than this is not handed to a model in one piece. */
    private static final int MAX_FILE_CHARS = 60_000;
    /* A tree longer than this is cut short, and the result says so. */
    private static final int MAX_TREE_ENTRIES = 1000;
    /** Most files one get_files call may read; shared with the sandbox twin. */
    private static final int MAX_PATHS = SandboxServerRegistry.MAX_GITHUB_PATHS;
    /** Most files one commit_files call may change; shared with the sandbox twin. */
    private static final int MAX_CHANGES = SandboxServerRegistry.MAX_GITHUB_CHANGES;
    /* One file written by commit_files, and all of them together. */
    private static final int MAX_COMMIT_FILE_CHARS = 1_000_000;
    private static final int MAX_COMMIT_CHARS = 10_000_000;
    /** Commits listed by compare_branches before the list is cut. */
    private static final int MAX_COMPARE_COMMITS = 50;
    /** Changed files listed by compare_branches before the list is cut. */
    private static final int MAX_COMPARE_FILES = 300;
    /* A regular file: git's mode for a blob that is not executable. */
    private static final String FILE_MODE = "100644";

    /** Registers the 22 GitHub tools; {@code baseUrl} is the GitHub API address. */
    public GitHubAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "GitHub", json, http, baseUrl);
        on("list_repos", this::listRepos);
        on("create_repo", this::createRepo);
        on("list_branches", this::listBranches);
        on("create_branch", this::createBranch);
        on("list_issues", this::listIssues);
        on("create_issue", this::createIssue);
        on("update_issue", this::updateIssue);
        on("list_comments", this::listComments);
        on("add_comment", this::addComment);
        on("get_pulls", this::getPulls);
        on("get_pull", this::getPull);
        on("create_pull", this::createPull);
        on("merge_pull", this::mergePull);
        on("list_tree", this::listTree);
        on("get_file", this::getFile);
        on("get_files", this::getFiles);
        on("save_file", this::saveFile);
        on("delete_file", this::deleteFile);
        on("rename_file", this::renameFile);
        on("commit_files", this::commitFiles);
        on("compare_branches", this::compareBranches);
        on("list_commits", this::listCommits);
    }

    /** Which writes land straight on a default branch, and so wait for a person. */
    private final DefaultBranchWrites defaultBranchWrites = new DefaultBranchWrites();

    // @find: github write to default branch needs approval, per call approval
    /**
     * Asks a person first when a file write (save, delete, rename or commit) would land on the default branch,
     * using {@link DefaultBranchWrites}. Called by the gateway before the call. Other tools, and arguments that
     * cannot be read, give no reason here; the tool itself then validates them.
     */
    @Override
    public java.util.Optional<String> approvalReason(ToolInvocation invocation) {
        if (!DefaultBranchWrites.TOOLS.contains(invocation.tool())) {
            return java.util.Optional.empty();
        }
        try {
            return defaultBranchWrites.reason(invocation.tool(), json.readTree(
                    invocation.argumentsJson() == null ? "{}" : invocation.argumentsJson()));
        } catch (Exception unreadable) {
            return java.util.Optional.empty();
        }
    }

    /** Bearer token plus the headers GitHub asks for: JSON media type, API version and a user agent. */
    @Override
    protected void authorize(HttpHeaders headers, String token) {
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        headers.set(HttpHeaders.USER_AGENT, "ai-workforce-os");
    }

    /** Reads the signed-in user (a read call) and answers the login name. */
    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, "/user").map(user -> user.path("login").asText("GitHub user"));
    }

    // @find: GitHub list issues, tool github__list_issues, live GitHub call
    /** Tool list_issues (read). Lists up to 30 issues by state; pull requests that GitHub mixes into the list are left out. */
    private Mono<ToolResult> listIssues(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String state = text(arguments, "state");
        String wanted = state == null ? "open" : state.toLowerCase(java.util.Locale.ROOT);
        if (!wanted.equals("open") && !wanted.equals("closed") && !wanted.equals("all")) {
            throw new VendorException("The state must be open, closed or all.");
        }
        return get(token, "/repos/{owner}/{repo}/issues?state={state}&per_page=30", repo[0], repo[1], wanted)
                .map(body -> {
                    ArrayNode items = json.createArrayNode();
                    // GitHub lists pull requests as issues too; they have their own tool.
                    body.forEach(issue -> {
                        if (!issue.has("pull_request")) {
                            items.add(issue(issue));
                        }
                    });
                    return done(listOf(items), "Read " + items.size() + " issue(s) from " + name(repo) + " on GitHub.");
                });
    }

    // @find: GitHub get pulls, tool github__get_pulls, live GitHub call
    /** Tool get_pulls (read). Lists up to 30 open pull requests. */
    private Mono<ToolResult> getPulls(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        return get(token, "/repos/{owner}/{repo}/pulls?state=open&per_page=30", repo[0], repo[1])
                .map(body -> {
                    ArrayNode items = json.createArrayNode();
                    body.forEach(pull -> {
                        ObjectNode item = json.createObjectNode();
                        item.put("id", pull.path("number").asText());
                        item.put("number", pull.path("number").asInt());
                        item.put("title", pull.path("title").asText());
                        item.put("author", pull.path("user").path("login").asText());
                        item.put("draft", pull.path("draft").asBoolean());
                        item.put("head", pull.path("head").path("ref").asText());
                        item.put("base", pull.path("base").path("ref").asText());
                        item.put("url", pull.path("html_url").asText());
                        item.put("createdAt", pull.path("created_at").asText());
                        items.add(item);
                    });
                    return done(
                            listOf(items),
                            "Read " + items.size() + " open pull request(s) from " + name(repo) + " on GitHub.");
                });
    }

    // @find: GitHub create issue, tool github__create_issue, live GitHub call
    /** Tool create_issue (write). Opens an issue with a title and optional body. Not idempotent. */
    private Mono<ToolResult> createIssue(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        ObjectNode body = json.createObjectNode();
        body.put("title", required(arguments, "title"));
        String text = text(arguments, "body");
        if (text != null) {
            body.put("body", text);
        }
        return send(HttpMethod.POST, token, body, "/repos/{owner}/{repo}/issues", repo[0], repo[1])
                .map(issue -> done(
                        issue(issue),
                        "Opened issue #" + issue.path("number").asText() + " in " + name(repo) + " on GitHub."));
    }

    // @find: GitHub update issue, tool github__update_issue, live GitHub call
    /** Tool update_issue (write). Changes the state, title or body of an issue; refuses a call that changes nothing. */
    private Mono<ToolResult> updateIssue(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String number = required(arguments, "id").replaceFirst("^#", "");
        if (!number.matches("\\d+")) {
            throw new VendorException("The id must be the issue number, for example 42.");
        }
        ObjectNode body = json.createObjectNode();
        String state = text(arguments, "state");
        if (state != null) {
            String wanted = state.toLowerCase(java.util.Locale.ROOT);
            if (!wanted.equals("open") && !wanted.equals("closed")) {
                throw new VendorException("The state must be open or closed.");
            }
            body.put("state", wanted);
        }
        for (String field : new String[] {"title", "body"}) {
            String value = text(arguments, field);
            if (value != null) {
                body.put(field, value);
            }
        }
        if (body.isEmpty()) {
            throw new VendorException("Say what to change: the state, the title or the body.");
        }
        return send(HttpMethod.PATCH, token, body, "/repos/{owner}/{repo}/issues/{number}", repo[0], repo[1], number)
                .map(issue -> done(issue(issue), "Updated issue #" + number + " in " + name(repo) + " on GitHub."));
    }

    // ---- Repositories and branches -------------------------------------------------------------

    // @find: GitHub list repos, tool github__list_repos, live GitHub call
    /** Tool list_repos (read). Lists the token owner's repositories, or an organisation's when an owner is given. Learns each default branch for later approval decisions. */
    private Mono<ToolResult> listRepos(ToolInvocation invocation, JsonNode arguments, String token) {
        int limit = limit(arguments, 30, 100);
        String owner = text(arguments, "owner");
        Mono<JsonNode> answer = owner == null
                ? get(token, "/user/repos?sort=updated&per_page={limit}", limit)
                : get(token, "/orgs/{owner}/repos?sort=updated&per_page={limit}", owner(owner), limit);
        return answer.map(body -> {
            ArrayNode items = json.createArrayNode();
            body.forEach(repo -> items.add(repository(repo)));
            return done(listOf(items), "Read " + items.size() + " repositories from GitHub.");
        });
    }

    // @find: GitHub create repo, tool github__create_repo, live GitHub call
    /** Tool create_repo (write). Creates a repository, private unless stated otherwise, with a README so a default branch exists. Not idempotent. */
    private Mono<ToolResult> createRepo(ToolInvocation invocation, JsonNode arguments, String token) {
        // "test repo" becomes test-repo, the way GitHub's own form suggests it.
        String name = required(arguments, "name").replaceAll("\\s+", "-");
        if (!NAME.matcher(name).matches() || name.matches("\\.+")) {
            throw new VendorException(
                    "The repository name can use letters, digits, hyphens, dots and underscores, for example team-notes.");
        }
        ObjectNode body = json.createObjectNode();
        body.put("name", name);
        String description = text(arguments, "description");
        if (description != null) {
            body.put("description", description);
        }
        boolean isPrivate = !arguments.path("private").isBoolean() || arguments.path("private").asBoolean();
        body.put("private", isPrivate);
        // Starts with a README, so it has a default branch that branches and pull requests can use.
        body.put("auto_init", true);
        String owner = text(arguments, "owner");
        Mono<JsonNode> created = owner == null
                ? send(HttpMethod.POST, token, body, "/user/repos")
                : send(HttpMethod.POST, token, body, "/orgs/{owner}/repos", owner(owner));
        return created.map(repo -> done(
                repository(repo),
                "Created the " + (repo.path("private").asBoolean(isPrivate) ? "private" : "public") + " repository "
                        + repo.path("full_name").asText(name) + " on GitHub."));
    }

    // @find: GitHub list branches, tool github__list_branches, live GitHub call
    /** Tool list_branches (read). Lists up to 100 branches with their head commit and protection flag. */
    private Mono<ToolResult> listBranches(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        return get(token, "/repos/{owner}/{repo}/branches?per_page=100", repo[0], repo[1]).map(body -> {
            ArrayNode items = json.createArrayNode();
            body.forEach(branch -> {
                ObjectNode item = json.createObjectNode();
                item.put("id", branch.path("name").asText());
                item.put("name", branch.path("name").asText());
                item.put("sha", branch.path("commit").path("sha").asText());
                item.put("protected", branch.path("protected").asBoolean());
                items.add(item);
            });
            return done(listOf(items), "Read " + items.size() + " branch(es) from " + name(repo) + " on GitHub.");
        });
    }

    // @find: GitHub create branch, tool github__create_branch, live GitHub call
    /** Tool create_branch (write). Creates a branch from the given branch, or from the default branch: reads the source head, then creates the reference. Not idempotent. */
    private Mono<ToolResult> createBranch(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String branch = ref(required(arguments, "branch"), "branch");
        String from = text(arguments, "from");
        Mono<String> start = from != null ? Mono.just(ref(from, "from")) : defaultBranch(token, repo);
        return start.flatMap(base -> get(token, "/repos/{owner}/{repo}/git/ref/heads/" + base, repo[0], repo[1])
                .flatMap(head -> {
                    ObjectNode body = json.createObjectNode();
                    body.put("ref", "refs/heads/" + branch);
                    body.put("sha", head.path("object").path("sha").asText());
                    return send(HttpMethod.POST, token, body, "/repos/{owner}/{repo}/git/refs", repo[0], repo[1]);
                })
                .map(created -> {
                    ObjectNode item = json.createObjectNode();
                    item.put("id", branch);
                    item.put("name", branch);
                    item.put("from", base);
                    item.put("sha", created.path("object").path("sha").asText());
                    return done(item, "Created the branch " + branch + " from " + base + " in " + name(repo)
                            + " on GitHub.");
                }));
    }

    // ---- Comments -------------------------------------------------------------------------------

    // @find: GitHub list comments, tool github__list_comments, live GitHub call
    /** Tool list_comments (read). Lists up to 50 comments on an issue or pull request. */
    private Mono<ToolResult> listComments(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String number = number(arguments, "issue");
        return get(token, "/repos/{owner}/{repo}/issues/{number}/comments?per_page=50", repo[0], repo[1], number)
                .map(body -> {
                    ArrayNode items = json.createArrayNode();
                    body.forEach(comment -> items.add(comment(comment)));
                    return done(
                            listOf(items),
                            "Read " + items.size() + " comment(s) on #" + number + " in " + name(repo) + " on GitHub.");
                });
    }

    // @find: GitHub add comment, tool github__add_comment, live GitHub call
    /** Tool add_comment (write). Adds a comment to an issue or pull request. Not idempotent. */
    private Mono<ToolResult> addComment(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String number = number(arguments, "issue");
        ObjectNode body = json.createObjectNode();
        body.put("body", required(arguments, "body"));
        return send(HttpMethod.POST, token, body, "/repos/{owner}/{repo}/issues/{number}/comments", repo[0], repo[1], number)
                .map(comment -> done(comment(comment), "Commented on #" + number + " in " + name(repo) + " on GitHub."));
    }

    // ---- Pull requests --------------------------------------------------------------------------

    // @find: GitHub get pull, tool github__get_pull, live GitHub call
    /** Tool get_pull (read). Reads one pull request, including whether it can be merged when GitHub knows. */
    private Mono<ToolResult> getPull(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String number = number(arguments, "id");
        return get(token, "/repos/{owner}/{repo}/pulls/{number}", repo[0], repo[1], number)
                .map(pull -> done(pull(pull), "Read pull request #" + number + " in " + name(repo) + " on GitHub."));
    }

    // @find: GitHub create pull, tool github__create_pull, live GitHub call
    /** Tool create_pull (write). Opens a pull request from a head branch into the given base, or the default branch. Changes reach the default branch only when the pull request is merged, which is a separate tool. */
    private Mono<ToolResult> createPull(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String title = required(arguments, "title");
        String head = ref(required(arguments, "head"), "head");
        String base = text(arguments, "base");
        Mono<String> into = base != null ? Mono.just(ref(base, "base")) : defaultBranch(token, repo);
        return into.flatMap(target -> {
                    ObjectNode body = json.createObjectNode();
                    body.put("title", title);
                    body.put("head", head);
                    body.put("base", target);
                    String text = text(arguments, "body");
                    if (text != null) {
                        body.put("body", text);
                    }
                    if (arguments.path("draft").asBoolean(false)) {
                        body.put("draft", true);
                    }
                    return send(HttpMethod.POST, token, body, "/repos/{owner}/{repo}/pulls", repo[0], repo[1]);
                })
                .map(pull -> done(
                        pull(pull),
                        "Opened pull request #" + pull.path("number").asText() + " in " + name(repo) + " on GitHub."));
    }

    // @find: GitHub merge pull, tool github__merge_pull, live GitHub call
    /** Tool merge_pull (outbound; changes the default branch and so waits for a person). Merges with the chosen method: merge, squash or rebase. */
    private Mono<ToolResult> mergePull(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String number = number(arguments, "id");
        String method = text(arguments, "method");
        String wanted = method == null ? "merge" : method.toLowerCase(Locale.ROOT);
        if (!wanted.equals("merge") && !wanted.equals("squash") && !wanted.equals("rebase")) {
            throw new VendorException("The method must be merge, squash or rebase.");
        }
        ObjectNode body = json.createObjectNode();
        body.put("merge_method", wanted);
        return send(HttpMethod.PUT, token, body, "/repos/{owner}/{repo}/pulls/{number}/merge", repo[0], repo[1], number)
                .map(merged -> {
                    ObjectNode item = json.createObjectNode();
                    item.put("id", number);
                    item.put("merged", merged.path("merged").asBoolean());
                    item.put("sha", merged.path("sha").asText());
                    return done(item, "Merged pull request #" + number + " in " + name(repo) + " on GitHub.");
                });
    }

    // ---- Files ----------------------------------------------------------------------------------

    // @find: GitHub get file, tool github__get_file, live GitHub call
    /** Tool get_file (read). Reads a file, decoded and cut at a size limit, or lists a folder. */
    private Mono<ToolResult> getFile(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String path = path(arguments);
        String ref = text(arguments, "ref");
        Mono<JsonNode> answer = ref == null
                ? get(token, "/repos/{owner}/{repo}/contents/" + path, repo[0], repo[1])
                : get(token, "/repos/{owner}/{repo}/contents/" + path + "?ref={ref}", repo[0], repo[1], ref(ref, "ref"));
        return answer.map(body -> {
            ArrayNode items = json.createArrayNode();
            if (body.isArray()) {
                body.forEach(entry -> items.add(file(entry, null)));
                return done(listOf(items), "Listed " + items.size() + " item(s) in " + path + " in " + name(repo)
                        + " on GitHub.");
            }
            items.add(file(body, decoded(body)));
            return done(listOf(items), "Read " + path + " from " + name(repo) + " on GitHub.");
        });
    }

    // @find: GitHub save file, tool github__save_file, live GitHub call
    /** Tool save_file (write; asks first on the default branch). Creates or replaces one file. Reads the current version first because GitHub needs it to replace a file, and treats a missing file as new. */
    private Mono<ToolResult> saveFile(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String path = path(arguments);
        String content = required(arguments, "content");
        String branch = text(arguments, "branch");
        String checkedBranch = branch == null ? null : ref(branch, "branch");
        // The file's current version, which GitHub needs to replace it; none when it is new.
        Mono<JsonNode> existing = (checkedBranch == null
                        ? get(token, "/repos/{owner}/{repo}/contents/" + path, repo[0], repo[1])
                        : get(token, "/repos/{owner}/{repo}/contents/" + path + "?ref={ref}", repo[0], repo[1], checkedBranch))
                .onErrorResume(
                        org.springframework.web.reactive.function.client.WebClientResponseException.NotFound.class,
                        missing -> Mono.just(json.createObjectNode()));
        return existing.flatMap(current -> {
                    if (current.isArray()) {
                        return Mono.error(new VendorException(path + " is a folder in " + name(repo) + ", not a file."));
                    }
                    String sha = current.path("sha").asText("");
                    ObjectNode body = json.createObjectNode();
                    String message = text(arguments, "message");
                    body.put("message", message != null ? message : (sha.isEmpty() ? "Add " : "Update ") + path);
                    body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
                    if (checkedBranch != null) {
                        body.put("branch", checkedBranch);
                    }
                    if (!sha.isEmpty()) {
                        body.put("sha", sha);
                    }
                    return send(HttpMethod.PUT, token, body, "/repos/{owner}/{repo}/contents/" + path, repo[0], repo[1])
                            .map(saved -> {
                                ObjectNode item = file(saved.path("content"), null);
                                item.put("commit", saved.path("commit").path("sha").asText());
                                return done(item, (sha.isEmpty() ? "Added " : "Updated ") + path + " in " + name(repo)
                                        + (checkedBranch == null ? "" : " on the branch " + checkedBranch) + " on GitHub.");
                            });
                });
    }

    // @find: GitHub list tree, tool github__list_tree, repository files and folders, live GitHub call
    /** Tool list_tree (read). Lists every file and folder in a branch through the recursive tree call, optionally below a folder, cut at a limit that the result reports. */
    private Mono<ToolResult> listTree(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String ref = text(arguments, "ref");
        String inside = text(arguments, "path") == null ? null : path(text(arguments, "path"), "path");
        Mono<String> at = ref != null ? Mono.just(ref(ref, "ref")) : defaultBranch(token, repo);
        return at.flatMap(target -> get(token, "/repos/{owner}/{repo}/git/trees/" + target + "?recursive=1", repo[0], repo[1])
                .map(body -> {
                    ArrayNode items = json.createArrayNode();
                    boolean cut = body.path("truncated").asBoolean(false);
                    for (JsonNode entry : body.path("tree")) {
                        String path = entry.path("path").asText();
                        if (inside != null && !path.startsWith(inside + "/")) {
                            continue;
                        }
                        if (items.size() >= MAX_TREE_ENTRIES) {
                            cut = true;
                            break;
                        }
                        ObjectNode item = json.createObjectNode();
                        item.put("path", path);
                        String type = entry.path("type").asText();
                        item.put("type", "tree".equals(type) ? "dir" : "commit".equals(type) ? "submodule" : "file");
                        if (entry.has("size")) {
                            item.put("size", entry.path("size").asInt());
                        }
                        item.put("sha", entry.path("sha").asText());
                        items.add(item);
                    }
                    ObjectNode result = listOf(items);
                    result.put("repo", name(repo));
                    result.put("ref", target);
                    result.put("truncated", cut);
                    return done(result, "Listed " + items.size() + " file(s) and folder(s)"
                            + (inside == null ? "" : " in " + inside) + " in " + name(repo) + " at " + target
                            + " on GitHub."
                            + (cut ? " The list was cut short; narrow it with a path." : ""));
                }));
    }

    // @find: GitHub get files, tool github__get_files, read several files at once, live GitHub call
    /** Tool get_files (read). Reads several files one after another; a missing file is reported inside the result, and the call fails only when none exist. */
    private Mono<ToolResult> getFiles(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String ref = text(arguments, "ref");
        String checkedRef = ref == null ? null : ref(ref, "ref");
        JsonNode given = arguments.path("paths");
        Set<String> paths = new LinkedHashSet<>();
        if (given.isArray()) {
            given.forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    paths.add(path(value.asText(), "paths"));
                }
            });
        } else if (given.isTextual() && !given.asText().isBlank()) {
            paths.add(path(given.asText(), "paths"));
        }
        if (paths.isEmpty()) {
            throw new VendorException("The paths are needed for this action: a list of files, for example README.md.");
        }
        if (paths.size() > MAX_PATHS) {
            throw new VendorException("Ask for at most " + MAX_PATHS + " files at a time.");
        }
        return Flux.fromIterable(paths)
                .concatMap(path -> (checkedRef == null
                                ? get(token, "/repos/{owner}/{repo}/contents/" + path, repo[0], repo[1])
                                : get(token, "/repos/{owner}/{repo}/contents/" + path + "?ref={ref}", repo[0], repo[1],
                                        checkedRef))
                        .map(body -> {
                            if (body.isArray()) {
                                ObjectNode folder = json.createObjectNode();
                                folder.put("id", path);
                                folder.put("path", path);
                                folder.put("type", "dir");
                                ArrayNode entries = folder.putArray("entries");
                                body.forEach(entry -> entries.add(entry.path("path").asText()));
                                return folder;
                            }
                            return file(body, decoded(body));
                        })
                        .onErrorResume(WebClientResponseException.NotFound.class, missing -> {
                            ObjectNode item = json.createObjectNode();
                            item.put("id", path);
                            item.put("path", path);
                            item.put("missing", true);
                            return Mono.just(item);
                        }))
                .collectList()
                .map(found -> {
                    ArrayNode items = json.createArrayNode();
                    List<String> missing = new ArrayList<>();
                    found.forEach(item -> {
                        items.add(item);
                        if (item.path("missing").asBoolean(false)) {
                            missing.add(item.path("path").asText());
                        }
                    });
                    if (missing.size() == found.size()) {
                        throw new VendorException("GitHub could not find any of those files in " + name(repo) + ".");
                    }
                    return done(listOf(items), "Read " + (found.size() - missing.size()) + " of " + found.size()
                            + " file(s) from " + name(repo) + " on GitHub."
                            + (missing.isEmpty() ? "" : " Not found: " + String.join(", ", missing) + "."));
                });
    }

    // @find: GitHub delete file, tool github__delete_file, live GitHub call
    /** Tool delete_file (destructive; asks first on the default branch). Reads the file's current version, then deletes it with a commit. The file stays in history. */
    private Mono<ToolResult> deleteFile(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String path = path(arguments);
        String branch = text(arguments, "branch");
        String checkedBranch = branch == null ? null : ref(branch, "branch");
        Mono<JsonNode> existing = checkedBranch == null
                ? get(token, "/repos/{owner}/{repo}/contents/" + path, repo[0], repo[1])
                : get(token, "/repos/{owner}/{repo}/contents/" + path + "?ref={ref}", repo[0], repo[1], checkedBranch);
        return existing.flatMap(current -> {
            if (current.isArray()) {
                return Mono.error(new VendorException(
                        path + " is a folder in " + name(repo) + ". Delete the files in it one at a time."));
            }
            ObjectNode body = json.createObjectNode();
            String message = text(arguments, "message");
            body.put("message", message != null ? message : "Delete " + path);
            body.put("sha", current.path("sha").asText());
            if (checkedBranch != null) {
                body.put("branch", checkedBranch);
            }
            return send(HttpMethod.DELETE, token, body, "/repos/{owner}/{repo}/contents/" + path, repo[0], repo[1])
                    .map(deleted -> {
                        ObjectNode item = json.createObjectNode();
                        item.put("id", path);
                        item.put("path", path);
                        item.put("deleted", true);
                        item.put("commit", deleted.path("commit").path("sha").asText());
                        item.put("url", deleted.path("commit").path("html_url").asText());
                        if (checkedBranch != null) {
                            item.put("branch", checkedBranch);
                        }
                        return done(item, "Deleted " + path + " from " + name(repo)
                                + (checkedBranch == null ? "" : " on the branch " + checkedBranch) + " on GitHub.");
                    });
        });
    }

    // @find: GitHub rename file, move file, tool github__rename_file, git data API, live GitHub call
    /** Tool rename_file (write; asks first on the default branch). Moves a file in one commit through the git data calls: reads the head and tree, checks the target is free, builds a tree that adds and removes the path, creates the commit and moves the branch forward. */
    private Mono<ToolResult> renameFile(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String from = path(required(arguments, "from"), "from");
        String to = path(required(arguments, "to"), "to");
        if (from.equals(to)) {
            throw new VendorException("The new path is the same as the old one.");
        }
        String branch = text(arguments, "branch");
        String message = text(arguments, "message");
        Mono<String> on = branch != null ? Mono.just(ref(branch, "branch")) : defaultBranch(token, repo);
        return on.flatMap(target -> head(token, repo, target)
                .flatMap(parent -> treeOf(token, repo, parent)
                        .flatMap(tree -> get(token, "/repos/{owner}/{repo}/contents/" + from + "?ref={ref}",
                                        repo[0], repo[1], target)
                                .flatMap(source -> {
                                    if (source.isArray() || !"file".equals(source.path("type").asText("file"))) {
                                        return Mono.<JsonNode>error(new VendorException(from + " is not a file in "
                                                + name(repo) + ". Rename the files in a folder one at a time."));
                                    }
                                    return absent(token, repo, to, target).thenReturn(source);
                                })
                                .flatMap(source -> {
                                    ArrayNode entries = json.createArrayNode();
                                    entries.add(entry(to, source.path("sha").asText()));
                                    entries.add(entry(from, null));
                                    return newCommit(token, repo, tree, parent, entries,
                                            message != null ? message : "Rename " + from + " to " + to);
                                })
                                .flatMap(commit -> move(token, repo, target, commit, false).thenReturn(commit))
                                .map(commit -> {
                                    ObjectNode item = json.createObjectNode();
                                    item.put("id", to);
                                    item.put("from", from);
                                    item.put("to", to);
                                    item.put("branch", target);
                                    item.put("commit", commit.path("sha").asText());
                                    item.put("url", commit.path("html_url").asText());
                                    return done(item, "Renamed " + from + " to " + to + " in " + name(repo)
                                            + " on the branch " + target + " on GitHub.");
                                }))));
    }

    // @find: GitHub commit files, many files one commit, tool github__commit_files, git data API, live GitHub call
    /**
     * Tool commit_files (write; asks first on the default branch). Writes and deletes many files in one commit.
     * Arguments are checked first. The repository is then read to learn the default branch; if the target turns out to be
     * the default and the gateway did not hold the call, it is refused so that the next call asks for approval. Blobs are
     * created, then a tree and a commit, and the branch is created or moved forward without force. Starts the branch from
     * the given source or the default branch when it does not exist yet.
     */
    private Mono<ToolResult> commitFiles(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String branch = ref(required(arguments, "branch"), "branch");
        String message = required(arguments, "message");
        String from = text(arguments, "from") == null ? null : ref(text(arguments, "from"), "from");
        Map<String, String> writes = writes(arguments.path("files"));
        Set<String> deletions = deletions(arguments.path("deletions"), writes);
        if (writes.isEmpty() && deletions.isEmpty()) {
            throw new VendorException("Say what to change: files with a path and content, or deletions.");
        }
        if (writes.size() + deletions.size() > MAX_CHANGES) {
            throw new VendorException("One commit can change at most " + MAX_CHANGES + " files. Split the work.");
        }
        // Whether the gateway already held this call for a person, decided before anything is learned below.
        boolean approvedAsDefault = defaultBranchWrites.reason("commit_files", arguments).isPresent();
        return get(token, "/repos/{owner}/{repo}", repo[0], repo[1]).flatMap(info -> {
            String main = ref(info.path("default_branch").asText("main"), "default branch");
            defaultBranchWrites.learn(name(repo), main);
            if (branch.equals(main) && !approvedAsDefault) {
                // Only now known to be the default: refused, so the next call is held for approval.
                return Mono.error(new VendorException(DefaultBranchWrites.askFirst(branch, name(repo))));
            }
            String start = from != null ? from : main;
            // The branch's head, or, when it does not exist yet, the head of the branch it starts from.
            Mono<String[]> parent = head(token, repo, branch)
                    .map(sha -> new String[] {sha, "existing"})
                    .onErrorResume(
                            WebClientResponseException.NotFound.class,
                            missing -> head(token, repo, start).map(sha -> new String[] {sha, "new"}));
            return parent.flatMap(at -> treeOf(token, repo, at[0])
                    .flatMap(tree -> Flux.fromIterable(writes.entrySet())
                            .concatMap(file -> {
                                ObjectNode blob = json.createObjectNode();
                                blob.put("content", file.getValue());
                                blob.put("encoding", "utf-8");
                                return send(HttpMethod.POST, token, blob, "/repos/{owner}/{repo}/git/blobs", repo[0], repo[1])
                                        .map(created -> entry(file.getKey(), created.path("sha").asText()));
                            })
                            .collectList()
                            .flatMap(entries -> {
                                ArrayNode all = json.createArrayNode();
                                all.addAll(entries);
                                deletions.forEach(path -> all.add(entry(path, null)));
                                return newCommit(token, repo, tree, at[0], all, message);
                            }))
                    .flatMap(commit -> move(token, repo, branch, commit, "new".equals(at[1])).thenReturn(commit))
                    .map(commit -> {
                        boolean created = "new".equals(at[1]);
                        ObjectNode item = json.createObjectNode();
                        item.put("id", commit.path("sha").asText());
                        item.put("repo", name(repo));
                        item.put("branch", branch);
                        item.put("created", created);
                        if (created) {
                            item.put("from", start);
                        }
                        item.put("commit", commit.path("sha").asText());
                        item.put("changed", writes.size());
                        item.put("deleted", deletions.size());
                        item.put("url", commit.path("html_url").asText());
                        return done(item, "Committed " + (writes.size() + deletions.size()) + " file change(s) to the"
                                + " branch " + branch + " in " + name(repo) + " on GitHub."
                                + (created ? " The branch was created from " + start + "." : ""));
                    }));
        });
    }

    // @find: GitHub compare branches, ahead behind, tool github__compare_branches, live GitHub call
    /** Tool compare_branches (read). Reports how far one branch is ahead of or behind another, with the commits and changed files (each list cut at a limit). */
    private Mono<ToolResult> compareBranches(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String base = ref(required(arguments, "base"), "base");
        String head = ref(required(arguments, "head"), "head");
        return get(token, "/repos/{owner}/{repo}/compare/" + base + "..." + head, repo[0], repo[1]).map(body -> {
            ObjectNode result = json.createObjectNode();
            result.put("base", base);
            result.put("head", head);
            result.put("status", body.path("status").asText());
            result.put("ahead", body.path("ahead_by").asInt());
            result.put("behind", body.path("behind_by").asInt());
            ArrayNode commits = result.putArray("commits");
            for (JsonNode commit : body.path("commits")) {
                if (commits.size() >= MAX_COMPARE_COMMITS) {
                    break;
                }
                commits.add(commit(commit));
            }
            ArrayNode files = result.putArray("files");
            for (JsonNode file : body.path("files")) {
                if (files.size() >= MAX_COMPARE_FILES) {
                    break;
                }
                ObjectNode item = json.createObjectNode();
                item.put("path", file.path("filename").asText());
                item.put("status", file.path("status").asText());
                item.put("additions", file.path("additions").asInt());
                item.put("deletions", file.path("deletions").asInt());
                if (file.hasNonNull("previous_filename")) {
                    item.put("previousPath", file.path("previous_filename").asText());
                }
                files.add(item);
            }
            result.put("url", body.path("html_url").asText());
            return done(result, "Compared " + head + " with " + base + " in " + name(repo) + " on GitHub: "
                    + body.path("ahead_by").asInt() + " commit(s) ahead, " + body.path("behind_by").asInt()
                    + " behind, " + body.path("files").size() + " file(s) changed.");
        });
    }

    // @find: GitHub list commits, history, tool github__list_commits, live GitHub call
    /** Tool list_commits (read). Lists recent commits, optionally for a branch or a path. */
    private Mono<ToolResult> listCommits(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        int limit = limit(arguments, 20, 100);
        String ref = text(arguments, "ref");
        String path = text(arguments, "path");
        StringBuilder uri = new StringBuilder("/repos/{owner}/{repo}/commits?per_page={limit}");
        List<Object> variables = new ArrayList<>(List.of(repo[0], repo[1], limit));
        if (ref != null) {
            uri.append("&sha={sha}");
            variables.add(ref(ref, "ref"));
        }
        if (path != null) {
            uri.append("&path={path}");
            variables.add(path(path, "path"));
        }
        return get(token, uri.toString(), variables.toArray()).map(body -> {
            ArrayNode items = json.createArrayNode();
            body.forEach(commit -> items.add(commit(commit)));
            return done(listOf(items), "Read " + items.size() + " commit(s) from " + name(repo) + " on GitHub.");
        });
    }

    // ---- Git data helpers -----------------------------------------------------------------------

    /* The commit a branch points at. */
    private Mono<String> head(String token, String[] repo, String branch) {
        return get(token, "/repos/{owner}/{repo}/git/ref/heads/" + branch, repo[0], repo[1])
                .map(ref -> ref.path("object").path("sha").asText());
    }

    /* The tree a commit points at. */
    private Mono<String> treeOf(String token, String[] repo, String commit) {
        return get(token, "/repos/{owner}/{repo}/git/commits/{sha}", repo[0], repo[1], commit)
                .map(body -> body.path("tree").path("sha").asText());
    }

    /* Fails when a file already exists at the path; GitHub answering 404 is the good answer. */
    private Mono<Void> absent(String token, String[] repo, String path, String branch) {
        return get(token, "/repos/{owner}/{repo}/contents/" + path + "?ref={ref}", repo[0], repo[1], branch)
                .flatMap(existing -> Mono.<Void>error(new VendorException("There is already a file at " + path
                        + " on the branch " + branch + " in " + name(repo) + ". Pick another path.")))
                .onErrorResume(WebClientResponseException.NotFound.class, missing -> Mono.empty());
    }

    /* A new tree on top of the old one, and a commit of it with one parent. */
    private Mono<JsonNode> newCommit(
            String token, String[] repo, String baseTree, String parent, ArrayNode entries, String message) {
        ObjectNode tree = json.createObjectNode();
        tree.put("base_tree", baseTree);
        tree.set("tree", entries);
        return send(HttpMethod.POST, token, tree, "/repos/{owner}/{repo}/git/trees", repo[0], repo[1])
                .flatMap(created -> {
                    ObjectNode commit = json.createObjectNode();
                    commit.put("message", message);
                    commit.put("tree", created.path("sha").asText());
                    commit.putArray("parents").add(parent);
                    return send(HttpMethod.POST, token, commit, "/repos/{owner}/{repo}/git/commits", repo[0], repo[1]);
                });
    }

    /* Points the branch at the new commit: a new ref for a new branch, otherwise a fast-forward. */
    private Mono<JsonNode> move(String token, String[] repo, String branch, JsonNode commit, boolean create) {
        ObjectNode body = json.createObjectNode();
        if (create) {
            body.put("ref", "refs/heads/" + branch);
            body.put("sha", commit.path("sha").asText());
            return send(HttpMethod.POST, token, body, "/repos/{owner}/{repo}/git/refs", repo[0], repo[1]);
        }
        body.put("sha", commit.path("sha").asText());
        body.put("force", false);
        return send(HttpMethod.PATCH, token, body, "/repos/{owner}/{repo}/git/refs/heads/" + branch, repo[0], repo[1]);
    }

    /* One tree entry: a file at a path, or its removal when the sha is null. */
    private ObjectNode entry(String path, String sha) {
        ObjectNode entry = json.createObjectNode();
        entry.put("path", path);
        entry.put("mode", FILE_MODE);
        entry.put("type", "blob");
        if (sha == null) {
            entry.putNull("sha");
        } else {
            entry.put("sha", sha);
        }
        return entry;
    }

    /** The commit fields shared with agents, preferring the GitHub login over the git author name. */
    private ObjectNode commit(JsonNode commit) {
        ObjectNode item = json.createObjectNode();
        item.put("id", commit.path("sha").asText());
        item.put("sha", commit.path("sha").asText());
        item.put("message", commit.path("commit").path("message").asText());
        String login = commit.path("author").path("login").asText("");
        item.put("author", login.isEmpty() ? commit.path("commit").path("author").path("name").asText() : login);
        item.put("date", commit.path("commit").path("author").path("date").asText());
        item.put("url", commit.path("html_url").asText());
        return item;
    }

    /* The files commit_files writes, by path, each checked. */
    private static Map<String, String> writes(JsonNode files) {
        Map<String, String> writes = new LinkedHashMap<>();
        if (files.isMissingNode() || files.isNull()) {
            return writes;
        }
        if (!files.isArray()) {
            throw new VendorException("The files must be a list of objects, each with a path and content.");
        }
        long total = 0;
        for (JsonNode file : files) {
            if (!file.isObject() || !file.path("path").isTextual() || !file.path("content").isTextual()) {
                throw new VendorException("Each of the files needs a path and its whole new content as text.");
            }
            String path = path(file.path("path").asText(), "path of each file");
            String content = file.path("content").asText();
            if (content.length() > MAX_COMMIT_FILE_CHARS) {
                throw new VendorException(path + " is too large to commit this way; keep each file under "
                        + MAX_COMMIT_FILE_CHARS + " characters.");
            }
            total += content.length();
            if (writes.put(path, content) != null) {
                throw new VendorException(path + " appears twice in files. List each file once.");
            }
        }
        if (total > MAX_COMMIT_CHARS) {
            throw new VendorException("The files are too large for one commit. Split the work.");
        }
        return writes;
    }

    /* The paths commit_files deletes, each checked, none also written. */
    private static Set<String> deletions(JsonNode deletions, Map<String, String> writes) {
        Set<String> paths = new LinkedHashSet<>();
        if (deletions.isMissingNode() || deletions.isNull()) {
            return paths;
        }
        if (!deletions.isArray()) {
            throw new VendorException("The deletions must be a list of file paths.");
        }
        for (JsonNode value : deletions) {
            if (!value.isTextual()) {
                throw new VendorException("The deletions must be a list of file paths.");
            }
            String path = path(value.asText(), "path of each deletion");
            if (writes.containsKey(path)) {
                throw new VendorException(path + " is in both files and deletions. Choose one.");
            }
            paths.add(path);
        }
        return paths;
    }


    // ---- Shapes ---------------------------------------------------------------------------------

    /** The repository's default branch, read from GitHub and remembered for approval decisions. */
    private Mono<String> defaultBranch(String token, String[] repo) {
        return get(token, "/repos/{owner}/{repo}", repo[0], repo[1])
                .map(body -> ref(body.path("default_branch").asText("main"), "default branch"))
                .doOnNext(main -> defaultBranchWrites.learn(name(repo), main));
    }

    /** The repository fields shared with agents; also records the default branch. */
    private ObjectNode repository(JsonNode repo) {
        ObjectNode item = json.createObjectNode();
        item.put("id", repo.path("full_name").asText());
        item.put("name", repo.path("name").asText());
        item.put("owner", repo.path("owner").path("login").asText());
        item.put("private", repo.path("private").asBoolean());
        item.put("description", repo.path("description").asText(""));
        item.put("defaultBranch", repo.path("default_branch").asText());
        defaultBranchWrites.learn(repo.path("full_name").asText(), repo.path("default_branch").asText());
        item.put("url", repo.path("html_url").asText());
        item.put("updatedAt", repo.path("updated_at").asText());
        return item;
    }

    /** The comment fields shared with agents. */
    private ObjectNode comment(JsonNode comment) {
        ObjectNode item = json.createObjectNode();
        item.put("id", comment.path("id").asText());
        item.put("author", comment.path("user").path("login").asText());
        item.put("body", comment.path("body").asText());
        item.put("url", comment.path("html_url").asText());
        item.put("createdAt", comment.path("created_at").asText());
        return item;
    }

    /** The pull request fields shared with agents; a merged pull request reports the state merged. */
    private ObjectNode pull(JsonNode pull) {
        ObjectNode item = json.createObjectNode();
        item.put("id", pull.path("number").asText());
        item.put("number", pull.path("number").asInt());
        item.put("title", pull.path("title").asText());
        item.put("state", pull.path("merged").asBoolean() ? "merged" : pull.path("state").asText());
        item.put("author", pull.path("user").path("login").asText());
        item.put("draft", pull.path("draft").asBoolean());
        item.put("head", pull.path("head").path("ref").asText());
        item.put("base", pull.path("base").path("ref").asText());
        item.put("body", pull.path("body").asText(""));
        if (pull.path("mergeable").isBoolean()) {
            item.put("mergeable", pull.path("mergeable").asBoolean());
        }
        item.put("url", pull.path("html_url").asText());
        item.put("createdAt", pull.path("created_at").asText());
        return item;
    }

    /** A file or folder entry; content, when given, is cut at a size limit and flagged as truncated. */
    private ObjectNode file(JsonNode entry, String content) {
        ObjectNode item = json.createObjectNode();
        item.put("id", entry.path("path").asText());
        item.put("path", entry.path("path").asText());
        item.put("type", entry.path("type").asText("file"));
        item.put("size", entry.path("size").asInt());
        item.put("sha", entry.path("sha").asText());
        if (content != null) {
            boolean cut = content.length() > MAX_FILE_CHARS;
            item.put("content", cut ? content.substring(0, MAX_FILE_CHARS) : content);
            if (cut) {
                item.put("truncated", true);
            }
        }
        item.put("url", entry.path("html_url").asText());
        return item;
    }

    /* A file's text: GitHub sends it base64-encoded in lines. */
    private static String decoded(JsonNode body) {
        if (!"base64".equals(body.path("encoding").asText())) {
            return body.path("content").asText("");
        }
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(body.path("content").asText(""));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            throw new VendorException("GitHub sent the file in a form that could not be read.");
        }
    }

    /** Checks an owner name against the allowed pattern. */
    private static String owner(String owner) {
        if (!OWNER.matcher(owner).matches()) {
            throw new VendorException("The owner must be a GitHub account or organisation name, for example acme.");
        }
        return owner;
    }

    /** Checks a branch, tag or default-branch name so it is safe in an address; refuses spaces, double dots and names ending in .lock. */
    private static String ref(String value, String field) {
        String ref = value.strip();
        if (!REF.matcher(ref).matches() || ref.contains("..") || ref.endsWith(".lock")) {
            throw new VendorException(
                    "The " + field + " must be a branch name such as main or feature/login, without spaces.");
        }
        return ref;
    }

    /** The checked {@code path} argument. */
    private static String path(JsonNode arguments) {
        return path(required(arguments, "path"), "path");
    }

    /* A file or folder path, checked so it can sit in the request path as written. */
    private static String path(String value, String field) {
        String path = value.strip().replaceFirst("^/+", "").replaceFirst("/+$", "");
        if (!PATH.matcher(path).matches() || java.util.Arrays.asList(path.split("/")).contains("..")
                || java.util.Arrays.stream(path.split("/")).anyMatch(part -> part.isBlank() || part.equals("."))) {
            throw new VendorException(
                    "The " + field + " must be a file or folder in the repository, for example docs/readme.md.");
        }
        return path;
    }

    /** An issue or pull request number, with an optional leading #. */
    private static String number(JsonNode arguments, String field) {
        String number = required(arguments, field).replaceFirst("^#", "");
        if (!number.matches("\\d{1,10}")) {
            throw new VendorException("The " + field + " must be the number, for example 42.");
        }
        return number;
    }

    /** The issue fields shared with agents. */
    private ObjectNode issue(JsonNode issue) {
        ObjectNode item = json.createObjectNode();
        item.put("id", issue.path("number").asText());
        item.put("number", issue.path("number").asInt());
        item.put("title", issue.path("title").asText());
        item.put("state", issue.path("state").asText());
        item.put("author", issue.path("user").path("login").asText());
        ArrayNode labels = item.putArray("labels");
        issue.path("labels").forEach(label -> labels.add(label.path("name").asText()));
        item.put("comments", issue.path("comments").asInt());
        item.put("url", issue.path("html_url").asText());
        item.put("createdAt", issue.path("created_at").asText());
        item.put("updatedAt", issue.path("updated_at").asText());
        return item;
    }

    /* owner/name, checked so a crafted value cannot reach a different API path. */
    private static String[] repo(JsonNode arguments) {
        Matcher match = REPO.matcher(required(arguments, "repo"));
        if (!match.matches() || match.group(1).matches("\\.+") || match.group(2).matches("\\.+")) {
            throw new VendorException("The repo must be written as owner/name, for example acme/website.");
        }
        return new String[] {match.group(1), match.group(2)};
    }

    /** owner/name text for summaries. */
    private static String name(String[] repo) {
        return repo[0] + "/" + repo[1];
    }
}
