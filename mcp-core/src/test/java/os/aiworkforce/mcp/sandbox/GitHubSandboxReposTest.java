// @find: tests for github sandbox repositories, commit files on a branch, default branch unchanged, compare branches ahead, merge pull applies changes, delete file, rename file, list tree, get files, practice git repo
// @what: Checks the sandbox's GitHub repository model behaves like git: branches stay apart, comparisons count commits, and merging brings changes across.
package os.aiworkforce.mcp.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.spi.McpServerAdapter;

/** GitHub's branches, files and commits in the sandbox, used the way an agent fixing docs would. */
class GitHubSandboxReposTest {

    private static final String REPO = "\"repo\":\"acme/website\"";

    private final ObjectMapper json = new ObjectMapper();
    private final McpServerAdapter github = SandboxServerRegistry.servers(json, WebClient.builder(), true).stream()
            .filter(server -> server.server().equals("github"))
            .findFirst()
            .orElseThrow();
    private final String org = "org-" + UUID.randomUUID();

    @Test
    @DisplayName("the practice repository has a tree, readable files with deliberate typos, branches and history")
    void seeded() {
        JsonNode tree = ok("list_tree", "{" + REPO + "}");
        assertThat(paths(tree)).contains("README.md", "docs", "docs/faq.md", "docs/getting-started.md", "package.json",
                "src/index.js");
        assertThat(tree.path("truncated").asBoolean()).isFalse();
        assertThat(paths(ok("list_tree", "{" + REPO + ",\"path\":\"docs\"}")))
                .containsExactly("docs/faq.md", "docs/getting-started.md");

        JsonNode files = ok("get_files", "{" + REPO + ",\"paths\":[\"docs/faq.md\",\"docs/missing.md\"]}");
        assertThat(files.path("items").path(0).path("content").asText()).contains("recieve");
        assertThat(files.path("items").path(1).path("missing").asBoolean()).isTrue();
        ToolResult none = call("get_files", "{" + REPO + ",\"paths\":[\"nope.md\"]}");
        assertThat(none.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(none.summary()).isEqualTo("None of those files exist on main in acme/website.");

        assertThat(ok("list_branches", "{" + REPO + "}").path("count").asInt()).isEqualTo(3);
        assertThat(ok("list_commits", "{" + REPO + "}").path("items").path(0).path("message").asText())
                .isEqualTo("Add the FAQ page");
        assertThat(ok("list_commits", "{" + REPO + ",\"path\":\"src\"}").path("count").asInt()).isEqualTo(1);
        assertThat(ok("compare_branches", "{" + REPO + ",\"base\":\"main\",\"head\":\"fix-checkout-spacing\"}")
                        .path("files").path(0).path("status").asText())
                .isEqualTo("modified");
    }

    @Test
    @DisplayName("commit_files on a new branch changes that branch only, and compare counts the commit")
    void commitOnANewBranch() {
        ToolResult committed = call("commit_files", "{" + REPO + ",\"branch\":\"docs/fix-typos\",\"message\":\"Fix typos\","
                + "\"files\":[{\"path\":\"docs/faq.md\",\"content\":\"You will receive an email.\"},"
                + "{\"path\":\"docs/new.md\",\"content\":\"New page\"}],\"deletions\":[\"docs/getting-started.md\"]}");

        assertThat(committed.status()).as(committed.summary()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(committed.summary()).isEqualTo("Committed 3 file change(s) to the branch docs/fix-typos in"
                + " acme/website in the sandbox. The branch was created from main. Nothing left this machine.");
        JsonNode content = content(committed);
        assertThat(content.path("created").asBoolean()).isTrue();
        assertThat(content.path("commit").asText()).hasSize(40);

        assertThat(paths(ok("list_tree", "{" + REPO + ",\"ref\":\"docs/fix-typos\",\"path\":\"docs\"}")))
                .containsExactly("docs/faq.md", "docs/new.md");
        assertThat(file("docs/fix-typos", "docs/faq.md")).isEqualTo("You will receive an email.");
        // The default branch is untouched.
        assertThat(file("main", "docs/faq.md")).contains("recieve");
        assertThat(paths(ok("list_tree", "{" + REPO + ",\"path\":\"docs\"}")))
                .containsExactly("docs/faq.md", "docs/getting-started.md");

        JsonNode compared = ok("compare_branches", "{" + REPO + ",\"base\":\"main\",\"head\":\"docs/fix-typos\"}");
        assertThat(compared.path("status").asText()).isEqualTo("ahead");
        assertThat(compared.path("ahead").asInt()).isEqualTo(1);
        assertThat(compared.path("behind").asInt()).isZero();
        assertThat(compared.path("commits").path(0).path("message").asText()).isEqualTo("Fix typos");
        assertThat(compared.path("files").findValuesAsText("status")).containsExactlyInAnyOrder("modified", "added",
                "removed");

        // A second commit to the existing branch adds to it.
        JsonNode again = ok("commit_files", "{" + REPO + ",\"branch\":\"docs/fix-typos\",\"message\":\"More\","
                + "\"files\":[{\"path\":\"README.md\",\"content\":\"Hello\"}]}");
        assertThat(again.path("created").asBoolean()).isFalse();
        assertThat(ok("compare_branches", "{" + REPO + ",\"base\":\"main\",\"head\":\"docs/fix-typos\"}")
                        .path("ahead").asInt())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("commit_files refuses bad requests plainly; an approved deletion on the default branch goes ahead")
    void commitRefusals() {
        ToolResult onMain = call("commit_files", "{" + REPO + ",\"branch\":\"main\",\"message\":\"Tidy\","
                + "\"deletions\":[\"docs/faq.md\"]}");
        assertThat(onMain.status()).isEqualTo(ToolResult.Status.SUCCEEDED);

        assertThat(call("commit_files", "{" + REPO + ",\"branch\":\"x\",\"message\":\"Nothing\"}").summary())
                .isEqualTo("Say what to change: files with a path and content, or deletions.");
        assertThat(call("commit_files", "{" + REPO + ",\"branch\":\"x\",\"message\":\"Bad\","
                        + "\"files\":[{\"path\":\"../etc/passwd\",\"content\":\"x\"}]}").summary())
                .contains("must be a file or folder in the repository");
        assertThat(call("commit_files", "{" + REPO + ",\"branch\":\"x\",\"message\":\"Gone\","
                        + "\"deletions\":[\"nope.md\"]}").summary())
                .isEqualTo("There is no file nope.md to delete on main in acme/website.");
        assertThat(call("commit_files", "{\"repo\":\"acme/unknown\",\"branch\":\"x\",\"message\":\"M\","
                        + "\"files\":[{\"path\":\"a\",\"content\":\"b\"}]}").summary())
                .startsWith("No repository acme/unknown in the sandbox.");
        // Once approved, committing straight to the default branch goes ahead.
        assertThat(call("commit_files", "{" + REPO + ",\"branch\":\"main\",\"message\":\"Add\","
                        + "\"files\":[{\"path\":\"CHANGELOG.md\",\"content\":\"# Changes\"}]}").status())
                .isEqualTo(ToolResult.Status.SUCCEEDED);
    }

    // @find: test sandbox default branch writes need approval
    @Test
    @DisplayName("a file write straight to the default branch needs approval in the sandbox; one to another branch does not")
    void defaultBranchWritesNeedApproval() {
        SandboxServerAdapter sandbox = new SandboxServerAdapter(
                "github",
                SandboxServerRegistry.definitions().get("github"),
                json,
                SandboxServerRegistry.seeds(json).get("github"));
        for (String tool : new String[] {"save_file", "delete_file", "rename_file", "commit_files"}) {
            assertThat(sandbox.approvalReason(invocation(tool, "{" + REPO + "}")))
                    .as(tool + " with no branch")
                    .contains("Write straight to the default branch main of acme/website using github." + tool);
            assertThat(sandbox.approvalReason(invocation(tool, "{" + REPO + ",\"branch\":\"main\"}"))).isPresent();
            assertThat(sandbox.approvalReason(invocation(tool, "{" + REPO + ",\"branch\":\"docs/fix\"}"))).isEmpty();
        }
        assertThat(sandbox.approvalReason(invocation("get_file", "{" + REPO + "}"))).isEmpty();
        assertThat(sandbox.approvalReason(invocation("create_branch", "{" + REPO + "}"))).isEmpty();
    }

    private ToolInvocation invocation(String tool, String arguments) {
        return new ToolInvocation(org, "agent-1", "run-1", "github", tool, arguments, "key", Map.of());
    }

    @Test
    @DisplayName("merging a pull request brings the head branch's changes into the base branch")
    void mergeAppliesChanges() {
        ok("commit_files", "{" + REPO + ",\"branch\":\"docs/fix-typos\",\"message\":\"Fix typos\","
                + "\"files\":[{\"path\":\"docs/faq.md\",\"content\":\"Fixed\"}]}");
        JsonNode pull = ok("create_pull", "{" + REPO + ",\"title\":\"Fix typos\",\"head\":\"docs/fix-typos\",\"base\":\"main\"}");

        ToolResult merged = call("merge_pull", "{" + REPO + ",\"id\":\"" + pull.path("id").asText() + "\"}");

        assertThat(merged.status()).as(merged.summary()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(merged.summary()).contains("1 file change(s) are now on main").contains("Nothing left this machine");
        assertThat(file("main", "docs/faq.md")).isEqualTo("Fixed");
        // The branch has nothing main lacks; main is ahead only by the merge commit, as on GitHub.
        JsonNode after = ok("compare_branches", "{" + REPO + ",\"base\":\"main\",\"head\":\"docs/fix-typos\"}");
        assertThat(after.path("ahead").asInt()).isZero();
        assertThat(after.path("status").asText()).isEqualTo("behind");
        assertThat(call("merge_pull", "{" + REPO + ",\"id\":\"" + pull.path("id").asText() + "\"}").status())
                .isEqualTo(ToolResult.Status.FAILED);

        // A seeded pull request merges its seeded branch.
        ok("merge_pull", "{" + REPO + ",\"id\":\"43\"}");
        assertThat(file("main", "src/styles/checkout.css")).contains("max-width: 375px");
    }

    @Test
    @DisplayName("delete_file and rename_file each make one commit on their branch")
    void deleteAndRename() {
        ok("create_branch", "{" + REPO + ",\"branch\":\"tidy\"}");

        JsonNode deleted = ok("delete_file", "{" + REPO + ",\"path\":\"docs/faq.md\",\"branch\":\"tidy\"}");
        assertThat(deleted.path("deleted").asBoolean()).isTrue();
        assertThat(paths(ok("list_tree", "{" + REPO + ",\"ref\":\"tidy\"}"))).doesNotContain("docs/faq.md");
        assertThat(file("main", "docs/faq.md")).isNotEmpty();
        assertThat(call("delete_file", "{" + REPO + ",\"path\":\"docs/faq.md\",\"branch\":\"tidy\"}").summary())
                .isEqualTo("There is no file docs/faq.md on tidy in acme/website.");

        JsonNode renamed = ok("rename_file", "{" + REPO + ",\"from\":\"docs/getting-started.md\","
                + "\"to\":\"docs/start/index.md\",\"branch\":\"tidy\"}");
        assertThat(renamed.path("to").asText()).isEqualTo("docs/start/index.md");
        assertThat(file("tidy", "docs/start/index.md")).contains("to instal the");
        assertThat(paths(ok("list_tree", "{" + REPO + ",\"ref\":\"tidy\"}"))).doesNotContain("docs/getting-started.md");
        assertThat(call("rename_file", "{" + REPO + ",\"from\":\"README.md\",\"to\":\"package.json\",\"branch\":\"tidy\"}")
                        .summary())
                .startsWith("There is already a file at package.json");

        JsonNode history = ok("list_commits", "{" + REPO + ",\"ref\":\"tidy\",\"limit\":2}");
        assertThat(history.path("items").findValuesAsText("message"))
                .containsExactly("Rename docs/getting-started.md to docs/start/index.md", "Delete docs/faq.md");
        assertThat(ok("compare_branches", "{" + REPO + ",\"base\":\"main\",\"head\":\"tidy\"}").path("ahead").asInt())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("each workspace has its own repositories, and a created repository starts with a README")
    void perWorkspace() {
        ok("commit_files", "{" + REPO + ",\"branch\":\"mine\",\"message\":\"M\",\"files\":[{\"path\":\"a.md\",\"content\":\"A\"}]}");
        ToolResult other = github.invoke(new ToolInvocation("org-other-" + UUID.randomUUID(), "agent-1", "run-1",
                        "github", "list_branches", "{" + REPO + "}", "key", Map.of()), null)
                .block();
        assertThat(content(other).path("count").asInt()).isEqualTo(3);

        ok("create_repo", "{\"name\":\"handbook\",\"owner\":\"acme\"}");
        assertThat(file("acme/handbook", "main", "README.md")).isEqualTo("# handbook\n");
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private String file(String ref, String path) {
        return file("acme/website", ref, path);
    }

    private String file(String repo, String ref, String path) {
        return ok("get_file", "{\"repo\":\"" + repo + "\",\"path\":\"" + path + "\",\"ref\":\"" + ref + "\"}")
                .path("items").path(0).path("content").asText();
    }

    private static List<String> paths(JsonNode list) {
        List<String> paths = new ArrayList<>();
        list.path("items").forEach(item -> paths.add(item.path("path").asText()));
        return paths;
    }

    private JsonNode ok(String tool, String arguments) {
        return content(call(tool, arguments));
    }

    private ToolResult call(String tool, String arguments) {
        return github.invoke(new ToolInvocation(org, "agent-1", "run-1", "github", tool, arguments, "key", Map.of()), null)
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
