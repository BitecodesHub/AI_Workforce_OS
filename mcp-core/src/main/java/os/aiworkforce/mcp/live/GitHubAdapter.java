package os.aiworkforce.mcp.live;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

/** GitHub's REST API with a personal access token. */
public final class GitHubAdapter extends LiveServerAdapter {

    public static final String BASE_URL = "https://api.github.com";

    private static final Pattern REPO = Pattern.compile("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    private static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$");
    /* A branch or a file path: safe characters only, so it can sit in the request path as written. */
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*$");
    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9_. -]+(?:/[A-Za-z0-9_. -]+)*$");
    /* More of a file than this is not handed to a model in one piece. */
    private static final int MAX_FILE_CHARS = 60_000;

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
        on("get_file", this::getFile);
        on("save_file", this::saveFile);
    }

    @Override
    protected void authorize(HttpHeaders headers, String token) {
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        headers.set(HttpHeaders.USER_AGENT, "ai-workforce-os");
    }

    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, "/user").map(user -> user.path("login").asText("GitHub user"));
    }

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

    private Mono<ToolResult> addComment(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String number = number(arguments, "issue");
        ObjectNode body = json.createObjectNode();
        body.put("body", required(arguments, "body"));
        return send(HttpMethod.POST, token, body, "/repos/{owner}/{repo}/issues/{number}/comments", repo[0], repo[1], number)
                .map(comment -> done(comment(comment), "Commented on #" + number + " in " + name(repo) + " on GitHub."));
    }

    // ---- Pull requests --------------------------------------------------------------------------

    private Mono<ToolResult> getPull(ToolInvocation invocation, JsonNode arguments, String token) {
        String[] repo = repo(arguments);
        String number = number(arguments, "id");
        return get(token, "/repos/{owner}/{repo}/pulls/{number}", repo[0], repo[1], number)
                .map(pull -> done(pull(pull), "Read pull request #" + number + " in " + name(repo) + " on GitHub."));
    }

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

    // ---- Shapes ---------------------------------------------------------------------------------

    private Mono<String> defaultBranch(String token, String[] repo) {
        return get(token, "/repos/{owner}/{repo}", repo[0], repo[1])
                .map(body -> ref(body.path("default_branch").asText("main"), "default branch"));
    }

    private ObjectNode repository(JsonNode repo) {
        ObjectNode item = json.createObjectNode();
        item.put("id", repo.path("full_name").asText());
        item.put("name", repo.path("name").asText());
        item.put("owner", repo.path("owner").path("login").asText());
        item.put("private", repo.path("private").asBoolean());
        item.put("description", repo.path("description").asText(""));
        item.put("defaultBranch", repo.path("default_branch").asText());
        item.put("url", repo.path("html_url").asText());
        item.put("updatedAt", repo.path("updated_at").asText());
        return item;
    }

    private ObjectNode comment(JsonNode comment) {
        ObjectNode item = json.createObjectNode();
        item.put("id", comment.path("id").asText());
        item.put("author", comment.path("user").path("login").asText());
        item.put("body", comment.path("body").asText());
        item.put("url", comment.path("html_url").asText());
        item.put("createdAt", comment.path("created_at").asText());
        return item;
    }

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

    private static String owner(String owner) {
        if (!OWNER.matcher(owner).matches()) {
            throw new VendorException("The owner must be a GitHub account or organisation name, for example acme.");
        }
        return owner;
    }

    private static String ref(String value, String field) {
        String ref = value.strip();
        if (!REF.matcher(ref).matches() || ref.contains("..") || ref.endsWith(".lock")) {
            throw new VendorException(
                    "The " + field + " must be a branch name such as main or feature/login, without spaces.");
        }
        return ref;
    }

    private static String path(JsonNode arguments) {
        String path = required(arguments, "path").replaceFirst("^/+", "").replaceFirst("/+$", "");
        if (!PATH.matcher(path).matches() || java.util.Arrays.asList(path.split("/")).contains("..")
                || java.util.Arrays.stream(path.split("/")).anyMatch(part -> part.isBlank() || part.equals("."))) {
            throw new VendorException("The path must be a file or folder in the repository, for example docs/readme.md.");
        }
        return path;
    }

    private static String number(JsonNode arguments, String field) {
        String number = required(arguments, field).replaceFirst("^#", "");
        if (!number.matches("\\d{1,10}")) {
            throw new VendorException("The " + field + " must be the number, for example 42.");
        }
        return number;
    }

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

    private static String name(String[] repo) {
        return repo[0] + "/" + repo[1];
    }
}
