// @find: github sandbox repositories, practice git repo, in memory git, branches, commits, file tree, commit files, compare branches, merge pull request, list tree, get files, rename file, delete file, sandbox github
// @what: An in-memory model of GitHub repositories (branches, files, commits) so the github file and branch tools work end to end in the sandbox.
// @flow: Used by SandboxServerAdapter for the github server's repository tools; seeded from sandbox-seeds.json (repos, files, branches, commits)
package os.aiworkforce.mcp.sandbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import os.aiworkforce.mcp.model.ToolResult;

/**
 * GitHub repositories as the sandbox keeps them: branches holding files, and the commits that
 * changed them.
 *
 * <p>The generic sandbox stores records, and a git repository is not a list of records: a commit
 * on one branch must leave the default branch unchanged, a comparison must count commits, and a
 * merge must bring a branch's changes across. This class models exactly that, per workspace, so
 * an agent can read a repository, commit a set of changes on a new branch, compare it and have it
 * merged, all with nothing leaving the machine. It answers in the same shapes as the live adapter.
 */
final class GitHubSandboxRepos {

    /** The github tools this model answers; every other github tool stays on the generic path. */
    static final Set<String> TOOLS = Set.of(
            "list_branches",
            "create_branch",
            "list_tree",
            "get_file",
            "get_files",
            "save_file",
            "delete_file",
            "rename_file",
            "commit_files",
            "compare_branches",
            "list_commits");

    /** {@code owner/name}: the only repository spelling accepted. */
    private static final Pattern REPO = Pattern.compile("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$");
    /** A branch name: letters, digits, dot, underscore, dash and slash separators; no spaces. */
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*$");
    /** A file or folder path inside a repository; spaces are allowed, absolute and parent segments are not. */
    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9_. -]+(?:/[A-Za-z0-9_. -]+)*$");
    /** Longest file content returned in one read; longer content is cut and marked truncated. */
    private static final int MAX_FILE_CHARS = 60_000;
    /** Most entries returned by one tree listing. */
    private static final int MAX_TREE_ENTRIES = 1000;
    /** Longest single file accepted by commit_files. */
    private static final int MAX_COMMIT_FILE_CHARS = 1_000_000;
    /** Sentence appended to every write summary, so an agent never reports a real change. */
    private static final String NOTHING_LEFT = " Nothing left this machine.";

    /** A refusal said in one plain sentence; becomes a failed result. Stackless, because it is expected control flow. */
    private static final class Refusal extends RuntimeException {
        Refusal(String message) {
            super(message, null, false, false);
        }
    }

    /** One commit: its changes are a map of path to new content, with null meaning the file was deleted. */
    private static final class Commit {
        final String sha;
        final String message;
        final String author;
        final String date;
        /* Path to new contents; null contents for a deletion. */
        final Map<String, String> changes;

        Commit(String sha, String message, String author, String date, Map<String, String> changes) {
            this.sha = sha;
            this.message = message;
            this.author = author;
            this.date = date;
            this.changes = changes;
        }
    }

    /** A branch: the current files, the commit history oldest first, and whether it is protected. */
    private static final class Branch {
        final TreeMap<String, String> files;
        /* Oldest first. */
        final List<Commit> history;
        final boolean isProtected;

        Branch(TreeMap<String, String> files, List<Commit> history, boolean isProtected) {
            this.files = files;
            this.history = history;
            this.isProtected = isProtected;
        }

        /** A new branch starting from this one, with the same files and history and no protection. */
        Branch copy() {
            return new Branch(new TreeMap<>(files), new ArrayList<>(history), false);
        }

        /** The sha of the newest commit, or an empty string for an empty history. */
        String head() {
            return history.isEmpty() ? "" : history.get(history.size() - 1).sha;
        }
    }

    /** A repository: its default branch and its branches in creation order. */
    private static final class Repo {
        final String name;
        final String defaultBranch;
        final Map<String, Branch> branches = new LinkedHashMap<>();

        Repo(String name, String defaultBranch) {
            this.name = name;
            this.defaultBranch = defaultBranch;
        }
    }

    /** Builds the JSON answers. */
    private final ObjectMapper json;
    /** Seeded practice records from {@code sandbox-seeds.json}, by kind. */
    private final List<ObjectNode> repoSeeds;
    private final List<ObjectNode> fileSeeds;
    private final List<ObjectNode> branchSeeds;
    private final List<ObjectNode> commitSeeds;
    /**
     * Each workspace's own repositories, built from the seeds on first use. Per workspace maps are locked while used.
     */
    private final Map<String, Map<String, Repo>> orgs = new ConcurrentHashMap<>();
    /** Makes commit shas unique. */
    private final AtomicLong counter = new AtomicLong();

    /**
     * Builds an empty model over the seeds. Nothing is created until a workspace first uses it.
     *
     * @param seeds the github server's seeded records by singular collection name
     */
    GitHubSandboxRepos(ObjectMapper json, Map<String, List<ObjectNode>> seeds) {
        this.json = json;
        this.repoSeeds = seeds.getOrDefault("repo", List.of());
        this.fileSeeds = seeds.getOrDefault("file", List.of());
        this.branchSeeds = seeds.getOrDefault("branch", List.of());
        this.commitSeeds = seeds.getOrDefault("commit", List.of());
    }

    // @find: run github sandbox repository tool
    /**
     * Runs one repository tool for a workspace.
     *
     * <p>Flow: take the workspace's repositories (seeded on first use), lock them, dispatch on the tool name
     * and turn the answer into a result. Side effects: changes the in-memory model only; no HTTP call is
     * made. Failure behaviour: a {@code Refusal} (bad argument, missing repository, branch or file) becomes a
     * failed result with one plain sentence; nothing is thrown to the caller.
     *
     * @param created finds a repository the generic sandbox recorded (create_repo), or null
     */
    ToolResult run(String orgId, String agentId, String tool, JsonNode arguments, Function<String, ObjectNode> created) {
        Instant startedAt = Instant.now();
        Map<String, Repo> repos = orgs.computeIfAbsent(orgId, id -> seeded());
        try {
            synchronized (repos) {
                Answer answer = switch (tool) {
                    case "list_branches" -> listBranches(repos, arguments, created);
                    case "create_branch" -> createBranch(repos, arguments, created);
                    case "list_tree" -> listTree(repos, arguments, created);
                    case "get_file" -> getFile(repos, arguments, created);
                    case "get_files" -> getFiles(repos, arguments, created);
                    case "save_file" -> saveFile(repos, agentId, arguments, created);
                    case "delete_file" -> deleteFile(repos, agentId, arguments, created);
                    case "rename_file" -> renameFile(repos, agentId, arguments, created);
                    case "commit_files" -> commitFiles(repos, agentId, arguments, created);
                    case "compare_branches" -> compareBranches(repos, arguments, created);
                    case "list_commits" -> listCommits(repos, arguments, created);
                    default -> throw new Refusal("The sandbox has no GitHub tool named " + tool + ".");
                };
                return ToolResult.succeeded(
                        answer.content.toString(), answer.summary, Duration.between(startedAt, Instant.now()));
            }
        } catch (Refusal refusal) {
            return ToolResult.failed(refusal.getMessage());
        }
    }

    // @find: sandbox merge pull request applies branch changes
    /**
     * Brings a pull request's head branch into its base branch, after the generic sandbox marked
     * it merged. Answers a sentence for the summary, or null when the repository or a branch is not
     * one this model knows (a pull request recorded with made-up branch names). Called by the generic
     * sandbox when a pull request is merged. Appends the head's commits and a merge commit to the base, so
     * the base branch then shows the changes.
     */
    String merge(String orgId, ObjectNode pull, Function<String, ObjectNode> created) {
        Map<String, Repo> repos = orgs.computeIfAbsent(orgId, id -> seeded());
        synchronized (repos) {
            String name = pull.path("repo").asText("");
            Repo repo = repos.get(name);
            if (repo == null && created.apply(name) != null && REPO.matcher(name).matches()) {
                repo = fromRecord(name, created.apply(name));
                repos.put(name, repo);
            }
            if (repo == null) {
                return null;
            }
            Branch head = repo.branches.get(pull.path("head").asText(""));
            String baseName = pull.hasNonNull("base") ? pull.path("base").asText() : repo.defaultBranch;
            Branch base = repo.branches.get(baseName);
            if (head == null || base == null || head == base) {
                return null;
            }
            List<Commit> ahead = notIn(head, base);
            if (ahead.isEmpty()) {
                return "The branch " + pull.path("head").asText() + " had no new commits for " + baseName + ".";
            }
            Set<String> touched = new TreeSet<>();
            for (Commit commit : ahead) {
                apply(base.files, commit.changes);
                touched.addAll(commit.changes.keySet());
                base.history.add(commit);
            }
            base.history.add(new Commit(
                    sha(),
                    "Merge pull request #" + pull.path("id").asText() + " from " + pull.path("head").asText(),
                    "sandbox",
                    Instant.now().toString(),
                    Map.of()));
            return "Its " + touched.size() + " file change(s) are now on " + baseName + ".";
        }
    }

    // ---- Tools ----------------------------------------------------------------------------------

    /** What a tool produced: the JSON content and the one-sentence summary. */
    private record Answer(JsonNode content, String summary) {}

    /** Lists the branches with head sha, protection and default flag (read). */
    private Answer listBranches(Map<String, Repo> repos, JsonNode arguments, Function<String, ObjectNode> created) {
        Repo repo = repo(repos, arguments, created);
        ArrayNode items = json.createArrayNode();
        repo.branches.forEach((name, branch) -> {
            ObjectNode item = json.createObjectNode();
            item.put("id", name);
            item.put("name", name);
            item.put("sha", branch.head());
            item.put("protected", branch.isProtected);
            item.put("default", name.equals(repo.defaultBranch));
            items.add(item);
        });
        return new Answer(listOf(items), "Read " + items.size() + " branch(es) from " + repo.name + " in the sandbox.");
    }

    /**
     * Creates a branch as a copy of another, default {@code from} the default branch (write). Refuses an existing name.
     */
    private Answer createBranch(Map<String, Repo> repos, JsonNode arguments, Function<String, ObjectNode> created) {
        Repo repo = repo(repos, arguments, created);
        String name = ref(required(arguments, "branch"), "branch");
        String from = text(arguments, "from") == null ? repo.defaultBranch : ref(text(arguments, "from"), "from");
        if (repo.branches.containsKey(name)) {
            throw new Refusal("The branch " + name + " already exists in " + repo.name + ".");
        }
        Branch start = branch(repo, from);
        Branch branch = start.copy();
        repo.branches.put(name, branch);
        ObjectNode item = json.createObjectNode();
        item.put("id", name);
        item.put("name", name);
        item.put("from", from);
        item.put("sha", branch.head());
        item.put("sandbox", true);
        return new Answer(item, "Created the branch " + name + " from " + from + " in " + repo.name
                + " in the sandbox." + NOTHING_LEFT);
    }

    /** Lists files and folders on a ref, optionally inside a path, up to 1000 entries (read). */
    private Answer listTree(Map<String, Repo> repos, JsonNode arguments, Function<String, ObjectNode> created) {
        Repo repo = repo(repos, arguments, created);
        String ref = refOrDefault(repo, arguments);
        Branch branch = branch(repo, ref);
        String inside = text(arguments, "path") == null ? null : path(text(arguments, "path"), "path");
        TreeMap<String, ObjectNode> entries = new TreeMap<>();
        branch.files.forEach((path, content) -> {
            if (inside != null && !path.startsWith(inside + "/")) {
                return;
            }
            String[] parts = path.split("/");
            StringBuilder folder = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                folder.append(i == 0 ? "" : "/").append(parts[i]);
                String dir = folder.toString();
                if (inside == null || dir.startsWith(inside + "/")) {
                    entries.computeIfAbsent(dir, key -> {
                        ObjectNode item = json.createObjectNode();
                        item.put("path", key);
                        item.put("type", "dir");
                        return item;
                    });
                }
            }
            ObjectNode item = json.createObjectNode();
            item.put("path", path);
            item.put("type", "file");
            item.put("size", content.getBytes(StandardCharsets.UTF_8).length);
            item.put("sha", blobSha(content));
            entries.put(path, item);
        });
        ArrayNode items = json.createArrayNode();
        boolean cut = false;
        for (ObjectNode item : entries.values()) {
            if (items.size() >= MAX_TREE_ENTRIES) {
                cut = true;
                break;
            }
            items.add(item);
        }
        if (inside != null && items.isEmpty()) {
            throw new Refusal("There is no folder " + inside + " on " + ref + " in " + repo.name + ".");
        }
        ObjectNode result = listOf(items);
        result.put("repo", repo.name);
        result.put("ref", ref);
        result.put("truncated", cut);
        return new Answer(result, "Listed " + items.size() + " file(s) and folder(s)"
                + (inside == null ? "" : " in " + inside) + " in " + repo.name + " at " + ref + " in the sandbox."
                + (cut ? " The list was cut short; narrow it with a path." : ""));
    }

    /** Reads one file with its content, or lists a folder's direct children (read). */
    private Answer getFile(Map<String, Repo> repos, JsonNode arguments, Function<String, ObjectNode> created) {
        Repo repo = repo(repos, arguments, created);
        String ref = refOrDefault(repo, arguments);
        Branch branch = branch(repo, ref);
        String path = path(required(arguments, "path"), "path");
        ArrayNode items = json.createArrayNode();
        if (branch.files.containsKey(path)) {
            items.add(file(path, branch.files.get(path), true));
            return new Answer(listOf(items), "Read " + path + " from " + repo.name + " in the sandbox.");
        }
        Set<String> children = children(branch, path);
        if (children.isEmpty()) {
            throw new Refusal("There is no file or folder " + path + " on " + ref + " in " + repo.name + ".");
        }
        for (String child : children) {
            if (branch.files.containsKey(child)) {
                items.add(file(child, branch.files.get(child), false));
            } else {
                ObjectNode dir = json.createObjectNode();
                dir.put("id", child);
                dir.put("path", child);
                dir.put("type", "dir");
                items.add(dir);
            }
        }
        return new Answer(listOf(items), "Listed " + items.size() + " item(s) in " + path + " in " + repo.name
                + " in the sandbox.");
    }

    /**
     * Reads several files at once, up to the registry limit; reports missing ones and refuses only when none exist (read).
     */
    private Answer getFiles(Map<String, Repo> repos, JsonNode arguments, Function<String, ObjectNode> created) {
        Repo repo = repo(repos, arguments, created);
        String ref = refOrDefault(repo, arguments);
        Branch branch = branch(repo, ref);
        Set<String> paths = new LinkedHashSet<>();
        JsonNode given = arguments.path("paths");
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
            throw new Refusal("The paths are needed for this action: a list of files, for example README.md.");
        }
        if (paths.size() > SandboxServerRegistry.MAX_GITHUB_PATHS) {
            throw new Refusal("Ask for at most " + SandboxServerRegistry.MAX_GITHUB_PATHS + " files at a time.");
        }
        ArrayNode items = json.createArrayNode();
        List<String> missing = new ArrayList<>();
        for (String path : paths) {
            if (branch.files.containsKey(path)) {
                items.add(file(path, branch.files.get(path), true));
            } else {
                ObjectNode item = json.createObjectNode();
                item.put("id", path);
                item.put("path", path);
                item.put("missing", true);
                items.add(item);
                missing.add(path);
            }
        }
        if (missing.size() == paths.size()) {
            throw new Refusal("None of those files exist on " + ref + " in " + repo.name + ".");
        }
        return new Answer(listOf(items), "Read " + (paths.size() - missing.size()) + " of " + paths.size()
                + " file(s) from " + repo.name + " in the sandbox."
                + (missing.isEmpty() ? "" : " Not found: " + String.join(", ", missing) + "."));
    }

    /**
     * Creates or replaces one file as one commit on a branch, default branch when none is named (write). A write
     * to the default branch is held for approval by {@link #approvalReason} before it reaches here.
     */
    private Answer saveFile(
            Map<String, Repo> repos, String agentId, JsonNode arguments, Function<String, ObjectNode> created) {
        Repo repo = repo(repos, arguments, created);
        String path = path(required(arguments, "path"), "path");
        String content = arguments.path("content").asText("");
        String name = text(arguments, "branch") == null ? repo.defaultBranch : ref(text(arguments, "branch"), "branch");
        Branch branch = branch(repo, name);
        if (!children(branch, path).isEmpty()) {
            throw new Refusal(path + " is a folder in " + repo.name + ", not a file.");
        }
        boolean isNew = !branch.files.containsKey(path);
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(path, content);
        Commit commit = commit(branch, changes, message(arguments, (isNew ? "Add " : "Update ") + path), agentId);
        ObjectNode item = file(path, content, false);
        item.put("commit", commit.sha);
        item.put("branch", name);
        item.put("sandbox", true);
        return new Answer(item, (isNew ? "Added " : "Updated ") + path + " in " + repo.name + " on the branch " + name
                + " in the sandbox." + NOTHING_LEFT);
    }

    /** Deletes one file as one commit (destructive, asks a person first). Refuses folders. */
    private Answer deleteFile(
            Map<String, Repo> repos, String agentId, JsonNode arguments, Function<String, ObjectNode> created) {
        Repo repo = repo(repos, arguments, created);
        String path = path(required(arguments, "path"), "path");
        String name = text(arguments, "branch") == null ? repo.defaultBranch : ref(text(arguments, "branch"), "branch");
        Branch branch = branch(repo, name);
        if (!branch.files.containsKey(path)) {
            throw new Refusal(!children(branch, path).isEmpty()
                    ? path + " is a folder in " + repo.name + ". Delete the files in it one at a time."
                    : "There is no file " + path + " on " + name + " in " + repo.name + ".");
        }
        Map<String, String> changes = new HashMap<>();
        changes.put(path, null);
        Commit commit = commit(branch, changes, message(arguments, "Delete " + path), agentId);
        ObjectNode item = json.createObjectNode();
        item.put("id", path);
        item.put("path", path);
        item.put("deleted", true);
        item.put("commit", commit.sha);
        item.put("branch", name);
        item.put("sandbox", true);
        return new Answer(item, "Deleted " + path + " from " + repo.name + " on the branch " + name
                + " in the sandbox." + NOTHING_LEFT);
    }

    /** Moves one file as one commit, keeping its contents (write). Refuses an existing target. */
    private Answer renameFile(
            Map<String, Repo> repos, String agentId, JsonNode arguments, Function<String, ObjectNode> created) {
        Repo repo = repo(repos, arguments, created);
        String from = path(required(arguments, "from"), "from");
        String to = path(required(arguments, "to"), "to");
        if (from.equals(to)) {
            throw new Refusal("The new path is the same as the old one.");
        }
        String name = text(arguments, "branch") == null ? repo.defaultBranch : ref(text(arguments, "branch"), "branch");
        Branch branch = branch(repo, name);
        if (!branch.files.containsKey(from)) {
            throw new Refusal("There is no file " + from + " on " + name + " in " + repo.name + ".");
        }
        if (branch.files.containsKey(to) || !children(branch, to).isEmpty()) {
            throw new Refusal("There is already a file at " + to + " on the branch " + name + " in " + repo.name
                    + ". Pick another path.");
        }
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(to, branch.files.get(from));
        changes.put(from, null);
        Commit commit = commit(branch, changes, message(arguments, "Rename " + from + " to " + to), agentId);
        ObjectNode item = json.createObjectNode();
        item.put("id", to);
        item.put("from", from);
        item.put("to", to);
        item.put("branch", name);
        item.put("commit", commit.sha);
        item.put("sandbox", true);
        return new Answer(item, "Renamed " + from + " to " + to + " in " + repo.name + " on the branch " + name
                + " in the sandbox." + NOTHING_LEFT);
    }

    /**
     * Applies many file writes and deletions as one commit (write). Creates the branch from {@code from} when it
     * does not exist. Validates every path and size, refuses duplicates and a path in both lists, and caps the
     * number of changes.
     */
    private Answer commitFiles(
            Map<String, Repo> repos, String agentId, JsonNode arguments, Function<String, ObjectNode> created) {
        Repo repo = repo(repos, arguments, created);
        String name = ref(required(arguments, "branch"), "branch");
        String message = required(arguments, "message");
        Map<String, String> changes = new LinkedHashMap<>();
        JsonNode files = arguments.path("files");
        if (!files.isMissingNode() && !files.isNull()) {
            if (!files.isArray()) {
                throw new Refusal("The files must be a list of objects, each with a path and content.");
            }
            for (JsonNode file : files) {
                if (!file.isObject() || !file.path("path").isTextual() || !file.path("content").isTextual()) {
                    throw new Refusal("Each of the files needs a path and its whole new content as text.");
                }
                String path = path(file.path("path").asText(), "path of each file");
                if (file.path("content").asText().length() > MAX_COMMIT_FILE_CHARS) {
                    throw new Refusal(path + " is too large to commit this way; keep each file under "
                            + MAX_COMMIT_FILE_CHARS + " characters.");
                }
                if (changes.put(path, file.path("content").asText()) != null) {
                    throw new Refusal(path + " appears twice in files. List each file once.");
                }
            }
        }
        int writes = changes.size();
        List<String> deletions = new ArrayList<>();
        JsonNode deleting = arguments.path("deletions");
        if (!deleting.isMissingNode() && !deleting.isNull()) {
            if (!deleting.isArray()) {
                throw new Refusal("The deletions must be a list of file paths.");
            }
            for (JsonNode value : deleting) {
                if (!value.isTextual()) {
                    throw new Refusal("The deletions must be a list of file paths.");
                }
                String path = path(value.asText(), "path of each deletion");
                if (changes.containsKey(path)) {
                    throw new Refusal(path + " is in both files and deletions. Choose one.");
                }
                deletions.add(path);
            }
        }
        if (changes.isEmpty() && deletions.isEmpty()) {
            throw new Refusal("Say what to change: files with a path and content, or deletions.");
        }
        if (changes.size() + deletions.size() > SandboxServerRegistry.MAX_GITHUB_CHANGES) {
            throw new Refusal("One commit can change at most " + SandboxServerRegistry.MAX_GITHUB_CHANGES
                    + " files. Split the work.");
        }
        String from = text(arguments, "from") == null ? repo.defaultBranch : ref(text(arguments, "from"), "from");
        boolean isNew = !repo.branches.containsKey(name);
        Branch branch = isNew ? branch(repo, from).copy() : repo.branches.get(name);
        for (String path : deletions) {
            if (!branch.files.containsKey(path)) {
                throw new Refusal("There is no file " + path + " to delete on " + (isNew ? from : name) + " in "
                        + repo.name + ".");
            }
            changes.put(path, null);
        }
        if (isNew) {
            repo.branches.put(name, branch);
        }
        Commit commit = commit(branch, changes, message, agentId);
        ObjectNode item = json.createObjectNode();
        item.put("id", commit.sha);
        item.put("repo", repo.name);
        item.put("branch", name);
        item.put("created", isNew);
        if (isNew) {
            item.put("from", from);
        }
        item.put("commit", commit.sha);
        item.put("changed", writes);
        item.put("deleted", deletions.size());
        item.put("sandbox", true);
        return new Answer(item, "Committed " + changes.size() + " file change(s) to the branch " + name + " in "
                + repo.name + " in the sandbox." + (isNew ? " The branch was created from " + from + "." : "")
                + NOTHING_LEFT);
    }

    /**
     * Compares two branches: ahead and behind counts, status (identical, ahead, behind or diverged), the
     * commits ahead and per file additions and deletions (read).
     */
    private Answer compareBranches(Map<String, Repo> repos, JsonNode arguments, Function<String, ObjectNode> created) {
        Repo repo = repo(repos, arguments, created);
        String baseName = ref(required(arguments, "base"), "base");
        String headName = ref(required(arguments, "head"), "head");
        Branch base = branch(repo, baseName);
        Branch head = branch(repo, headName);
        List<Commit> ahead = notIn(head, base);
        List<Commit> behind = notIn(base, head);
        // What head changed since the two parted: its own commits, against the common history.
        Set<String> common = new HashSet<>();
        base.history.forEach(commit -> common.add(commit.sha));
        Map<String, String> before = new TreeMap<>();
        head.history.stream().filter(commit -> common.contains(commit.sha)).forEach(commit -> apply(before, commit.changes));
        Set<String> touched = new TreeSet<>();
        ahead.forEach(commit -> touched.addAll(commit.changes.keySet()));
        ObjectNode result = json.createObjectNode();
        result.put("base", baseName);
        result.put("head", headName);
        result.put("status", ahead.isEmpty() && behind.isEmpty() ? "identical"
                : behind.isEmpty() ? "ahead" : ahead.isEmpty() ? "behind" : "diverged");
        result.put("ahead", ahead.size());
        result.put("behind", behind.size());
        ArrayNode commits = result.putArray("commits");
        ahead.forEach(commit -> commits.add(commit(commit)));
        ArrayNode files = result.putArray("files");
        for (String path : touched) {
            String old = before.get(path);
            String now = head.files.get(path);
            if (java.util.Objects.equals(old, now)) {
                continue;
            }
            ObjectNode file = json.createObjectNode();
            file.put("path", path);
            file.put("status", old == null ? "added" : now == null ? "removed" : "modified");
            file.put("additions", linesOnlyIn(now, old));
            file.put("deletions", linesOnlyIn(old, now));
            files.add(file);
        }
        return new Answer(result, "Compared " + headName + " with " + baseName + " in " + repo.name + " in the sandbox: "
                + ahead.size() + " commit(s) ahead, " + behind.size() + " behind, " + files.size()
                + " file(s) changed.");
    }

    /** Lists commits newest first, optionally only those touching a path, 1 to 100 (read). */
    private Answer listCommits(Map<String, Repo> repos, JsonNode arguments, Function<String, ObjectNode> created) {
        Repo repo = repo(repos, arguments, created);
        String ref = refOrDefault(repo, arguments);
        Branch branch = branch(repo, ref);
        String path = text(arguments, "path") == null ? null : path(text(arguments, "path"), "path");
        int limit = Math.max(1, Math.min(arguments.path("limit").asInt(20), 100));
        ArrayNode items = json.createArrayNode();
        for (int i = branch.history.size() - 1; i >= 0 && items.size() < limit; i--) {
            Commit commit = branch.history.get(i);
            if (path == null || commit.changes.keySet().stream()
                    .anyMatch(changed -> changed.equals(path) || changed.startsWith(path + "/"))) {
                items.add(commit(commit));
            }
        }
        return new Answer(listOf(items), "Read " + items.size() + " commit(s) from " + repo.name + " in the sandbox.");
    }

    // ---- The model ------------------------------------------------------------------------------

    /** Applies the changes to the branch and records them as a new commit. */
    private Commit commit(Branch branch, Map<String, String> changes, String message, String author) {
        apply(branch.files, changes);
        Commit commit = new Commit(sha(), message, author == null ? "agent" : author, Instant.now().toString(),
                new LinkedHashMap<>(changes));
        branch.history.add(commit);
        return commit;
    }

    /** Applies changes to a file map; null content removes the file. */
    private static void apply(Map<String, String> files, Map<String, String> changes) {
        changes.forEach((path, content) -> {
            if (content == null) {
                files.remove(path);
            } else {
                files.put(path, content);
            }
        });
    }

    /** The commits on one branch that the other does not have, oldest first. */
    private static List<Commit> notIn(Branch branch, Branch other) {
        Set<String> known = new HashSet<>();
        other.history.forEach(commit -> known.add(commit.sha));
        return branch.history.stream().filter(commit -> !known.contains(commit.sha)).toList();
    }

    /** The files and folders directly inside a folder; empty when it is not one. */
    private static Set<String> children(Branch branch, String folder) {
        Set<String> children = new TreeSet<>();
        String prefix = folder + "/";
        for (String path : branch.files.subMap(prefix, prefix + Character.MAX_VALUE).keySet()) {
            String rest = path.substring(prefix.length());
            int slash = rest.indexOf('/');
            children.add(slash < 0 ? path : prefix + rest.substring(0, slash));
        }
        return children;
    }

    /**
     * Resolves the repository named in the arguments. Without a name, the first practice repository is used, as a
     * demonstration does. A name the model does not know is looked up among repositories the generic store
     * recorded; otherwise the call is refused.
     */
    private Repo repo(Map<String, Repo> repos, JsonNode arguments, Function<String, ObjectNode> created) {
        String name = text(arguments, "repo");
        if (name == null) {
            // A first look without naming one, as a demo does: the first practice repository.
            if (repos.isEmpty()) {
                throw new Refusal("The repo is needed for this action.");
            }
            return repos.values().iterator().next();
        }
        Matcher match = REPO.matcher(name);
        if (!match.matches() || match.group(1).matches("\\.+") || match.group(2).matches("\\.+")) {
            throw new Refusal("The repo must be written as owner/name, for example acme/website.");
        }
        Repo repo = repos.get(name);
        if (repo == null) {
            ObjectNode record = created.apply(name);
            if (record == null) {
                throw new Refusal("No repository " + name + " in the sandbox. Use github.list_repos to see the ones"
                        + " that exist.");
            }
            repo = fromRecord(name, record);
            repos.put(name, repo);
        }
        return repo;
    }

    /** A repository create_repo recorded: it starts with a README, as the live one does. */
    private Repo fromRecord(String name, ObjectNode record) {
        Repo repo = new Repo(name, record.path("defaultBranch").asText("main"));
        String title = name.substring(name.indexOf('/') + 1);
        Branch main = new Branch(new TreeMap<>(), new ArrayList<>(), false);
        Map<String, String> readme = new LinkedHashMap<>();
        readme.put("README.md", "# " + title + "\n");
        commit(main, readme, "Initial commit", "sandbox");
        repo.branches.put(repo.defaultBranch, main);
        return repo;
    }

    /** The named branch, or a refusal pointing to the listing tool. */
    private static Branch branch(Repo repo, String name) {
        Branch branch = repo.branches.get(name);
        if (branch == null) {
            throw new Refusal("No branch named " + name + " in " + repo.name + ". Use github.list_branches to see the"
                    + " ones that exist.");
        }
        return branch;
    }

    // @find: sandbox github write to default branch needs approval
    /**
     * The same rule the live adapter applies ({@link os.aiworkforce.mcp.live.DefaultBranchWrites}),
     * answered exactly from this workspace's practice repositories: a file write that lands on the
     * repository's default branch waits for a person. Called through {@code SandboxServerAdapter} by the
     * gateway before the call is made. Empty for tools that do not write files or for other branches. Pure.
     */
    java.util.Optional<String> approvalReason(String orgId, String tool, JsonNode arguments) {
        if (!os.aiworkforce.mcp.live.DefaultBranchWrites.TOOLS.contains(tool) || arguments == null) {
            return java.util.Optional.empty();
        }
        String repoName = text(arguments, "repo");
        Map<String, Repo> repos = orgs.computeIfAbsent(orgId, id -> seeded());
        String defaultBranch;
        synchronized (repos) {
            Repo repo = repoName == null ? null : repos.get(repoName);
            defaultBranch = repo == null ? null : repo.defaultBranch;
        }
        String branch = text(arguments, "branch");
        boolean onDefault = branch == null
                || (defaultBranch != null ? branch.equals(defaultBranch) : branch.equals("main") || branch.equals("master"));
        return onDefault
                ? java.util.Optional.of(os.aiworkforce.mcp.live.DefaultBranchWrites.sentence(
                        tool, repoName, branch == null ? defaultBranch : branch))
                : java.util.Optional.empty();
    }

    /** The {@code ref} argument, validated, or the repository's default branch. */
    private static String refOrDefault(Repo repo, JsonNode arguments) {
        String ref = text(arguments, "ref");
        return ref == null ? repo.defaultBranch : ref(ref, "ref");
    }

    // ---- Seeds ----------------------------------------------------------------------------------

    /**
     * A workspace's own copy of the practice repositories. The default branch is built first and every other
     * branch starts as a copy of it, then has its own seeded files and commits laid over it.
     */
    private Map<String, Repo> seeded() {
        Map<String, Repo> repos = new LinkedHashMap<>();
        Map<String, String> defaults = new LinkedHashMap<>();
        repoSeeds.forEach(seed -> defaults.put(seed.path("id").asText(), seed.path("defaultBranch").asText("main")));
        for (List<ObjectNode> seeds : List.of(fileSeeds, branchSeeds, commitSeeds)) {
            seeds.forEach(seed -> defaults.putIfAbsent(seed.path("repo").asText(), "main"));
        }
        defaults.forEach((name, defaultBranch) -> {
            if (!REPO.matcher(name).matches()) {
                return;
            }
            Repo repo = new Repo(name, defaultBranch);
            // The default branch first: every other branch starts from it.
            Branch main = new Branch(new TreeMap<>(), new ArrayList<>(), protectedSeed(name, defaultBranch));
            seedBranch(repo, main, defaultBranch, true);
            repo.branches.put(defaultBranch, main);
            Set<String> others = new LinkedHashSet<>();
            for (List<ObjectNode> seeds : List.of(branchSeeds, fileSeeds, commitSeeds)) {
                seeds.stream()
                        .filter(seed -> name.equals(seed.path("repo").asText()))
                        .map(seed -> seed.path(seeds == branchSeeds ? "name" : "branch").asText(""))
                        .filter(branch -> !branch.isEmpty() && !branch.equals(defaultBranch))
                        .forEach(others::add);
            }
            for (String other : others) {
                Branch branch = new Branch(new TreeMap<>(main.files), new ArrayList<>(main.history),
                        protectedSeed(name, other));
                seedBranch(repo, branch, other, false);
                repo.branches.put(other, branch);
            }
            repos.put(name, repo);
        });
        return repos;
    }

    /** Lays a branch's seeded files and commits over what it started with. */
    private void seedBranch(Repo repo, Branch branch, String name, boolean isDefault) {
        Map<String, String> files = new TreeMap<>();
        fileSeeds.stream()
                .filter(seed -> repo.name.equals(seed.path("repo").asText()))
                .filter(seed -> name.equals(seed.path("branch").asText(repo.defaultBranch)))
                .filter(seed -> PATH.matcher(seed.path("path").asText("")).matches())
                .forEach(seed -> files.put(seed.path("path").asText(), seed.path("content").asText("")));
        List<ObjectNode> commits = commitSeeds.stream()
                .filter(seed -> repo.name.equals(seed.path("repo").asText()))
                .filter(seed -> name.equals(seed.path("branch").asText(repo.defaultBranch)))
                .toList();
        if (commits.isEmpty() && (isDefault || !files.isEmpty())) {
            ObjectNode synthetic = json.createObjectNode();
            synthetic.put("message", isDefault ? "Initial commit" : "Work on " + name);
            commits = List.of(synthetic);
        }
        boolean first = true;
        for (ObjectNode seed : commits) {
            Map<String, String> changes = new LinkedHashMap<>();
            if (seed.path("files").isArray()) {
                seed.path("files").forEach(path -> {
                    if (files.containsKey(path.asText())) {
                        changes.put(path.asText(), files.get(path.asText()));
                    }
                });
            } else if (first) {
                changes.putAll(files);
            }
            first = false;
            apply(branch.files, changes);
            branch.history.add(new Commit(
                    seed.path("sha").asText(sha()),
                    seed.path("message").asText("Update"),
                    seed.path("author").asText("sandbox"),
                    seed.path("date").asText(Instant.now().toString()),
                    changes));
        }
        // Seeded files no commit named are still on the branch.
        files.forEach(branch.files::putIfAbsent);
    }

    /** Whether the seeds mark this branch as protected. */
    private boolean protectedSeed(String repo, String branch) {
        return branchSeeds.stream()
                .anyMatch(seed -> repo.equals(seed.path("repo").asText()) && branch.equals(seed.path("name").asText())
                        && seed.path("protected").asBoolean(false));
    }

    // ---- Shapes and arguments -------------------------------------------------------------------

    /** The JSON for one file, with content only when asked, cut to {@code MAX_FILE_CHARS}. */
    private ObjectNode file(String path, String content, boolean withContent) {
        ObjectNode item = json.createObjectNode();
        item.put("id", path);
        item.put("path", path);
        item.put("type", "file");
        item.put("size", content.getBytes(StandardCharsets.UTF_8).length);
        item.put("sha", blobSha(content));
        if (withContent) {
            boolean cut = content.length() > MAX_FILE_CHARS;
            item.put("content", cut ? content.substring(0, MAX_FILE_CHARS) : content);
            if (cut) {
                item.put("truncated", true);
            }
        }
        return item;
    }

    /** The JSON for one commit. */
    private ObjectNode commit(Commit commit) {
        ObjectNode item = json.createObjectNode();
        item.put("id", commit.sha);
        item.put("sha", commit.sha);
        item.put("message", commit.message);
        item.put("author", commit.author);
        item.put("date", commit.date);
        return item;
    }

    /** Wraps items as {@code {count, items}}. */
    private ObjectNode listOf(ArrayNode items) {
        ObjectNode result = json.createObjectNode();
        result.put("count", items.size());
        result.set("items", items);
        return result;
    }

    /** A unique commit sha for this process. */
    private String sha() {
        return sha1("sandbox-commit-" + counter.incrementAndGet() + "-" + System.nanoTime());
    }

    /** The sha git itself would give the file's contents. */
    private static String blobSha(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return sha1("blob " + bytes.length + "\0" + content);
    }

    /** SHA-1 of the text as lower-case hex. */
    private static String sha1(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lines in one text that the other lacks, counted with repeats: a diff's additions or deletions. */
    private static int linesOnlyIn(String text, String other) {
        if (text == null) {
            return 0;
        }
        Map<String, Integer> remaining = new HashMap<>();
        if (other != null) {
            for (String line : other.split("\n", -1)) {
                remaining.merge(line, 1, Integer::sum);
            }
        }
        int count = 0;
        for (String line : text.split("\n", -1)) {
            Integer left = remaining.get(line);
            if (left == null || left == 0) {
                count++;
            } else {
                remaining.put(line, left - 1);
            }
        }
        return count;
    }

    /** The commit message given, or the fallback. */
    private static String message(JsonNode arguments, String fallback) {
        String message = text(arguments, "message");
        return message == null ? fallback : message;
    }

    /** A trimmed scalar argument, or null when absent, null or not a scalar. */
    private static String text(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }

    /** A required text argument; refuses when missing. */
    private static String required(JsonNode arguments, String field) {
        String value = text(arguments, field);
        if (value == null) {
            throw new Refusal("The " + field + " is needed for this action.");
        }
        return value;
    }

    /** A validated branch name; refuses spaces, double dots and a {@code .lock} suffix. */
    private static String ref(String value, String field) {
        String ref = value.strip();
        if (!REF.matcher(ref).matches() || ref.contains("..") || ref.endsWith(".lock")) {
            throw new Refusal("The " + field + " must be a branch name such as main or feature/login, without spaces.");
        }
        return ref;
    }

    /**
     * A validated repository path with leading and trailing slashes removed; refuses parent or empty segments.
     */
    private static String path(String value, String field) {
        String path = value.strip().replaceFirst("^/+", "").replaceFirst("/+$", "");
        if (!PATH.matcher(path).matches() || List.of(path.split("/")).contains("..")
                || java.util.Arrays.stream(path.split("/")).anyMatch(part -> part.isBlank() || part.equals("."))) {
            throw new Refusal(
                    "The " + field + " must be a file or folder in the repository, for example docs/readme.md.");
        }
        return path;
    }
}
