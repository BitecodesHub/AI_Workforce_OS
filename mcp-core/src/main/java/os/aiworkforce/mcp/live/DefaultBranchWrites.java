// @find: default branch writes need approval, write straight to main, github save_file delete_file rename_file commit_files on default branch, per call approval, DefaultBranchWrites
// @what: Decides, per call, whether a GitHub file write lands straight on the repository's default branch and so needs a person's approval.
// @flow: Used by GitHubAdapter.approvalReason (gateway evaluation) and its write tools; GitHubSandboxRepos applies the same rule
package os.aiworkforce.mcp.live;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Writes to a repository's default branch change what everyone builds from, so they wait for a
 * person; writes to any other branch go ahead and reach the default branch only through a pull
 * request, whose merge waits for a person too.
 *
 * <p>The gateway decides before any call is made, so this cannot ask GitHub which branch is the
 * default. It answers from what earlier calls learned ({@link #learn}), and when nothing is known
 * yet it treats {@code main} and {@code master} as the default. A write that leaves the branch out
 * always lands on the default branch, so it always asks.
 */
public final class DefaultBranchWrites {

    /** The tools that write files to a branch. */
    public static final Set<String> TOOLS = Set.of("save_file", "delete_file", "rename_file", "commit_files");

    private static final Set<String> USUAL_DEFAULTS = Set.of("main", "master");

    private final Map<String, String> defaults = new ConcurrentHashMap<>();

    /** Records a repository's default branch, read from any answer that carried it. */
    public void learn(String repo, String defaultBranch) {
        if (repo != null && !repo.isBlank() && defaultBranch != null && !defaultBranch.isBlank()) {
            defaults.put(repo.strip().toLowerCase(Locale.ROOT), defaultBranch.strip());
        }
    }

    /** The default branch learned for a repository, if any. */
    public Optional<String> known(String repo) {
        return repo == null ? Optional.empty() : Optional.ofNullable(defaults.get(repo.strip().toLowerCase(Locale.ROOT)));
    }

    // @find: does this github write need approval, default branch rule
    /** Why this call needs approval, or empty when it writes to another branch or is not a write. */
    public Optional<String> reason(String tool, JsonNode arguments) {
        if (!TOOLS.contains(tool) || arguments == null) {
            return Optional.empty();
        }
        String repo = text(arguments, "repo");
        String branch = text(arguments, "branch");
        boolean onDefault = branch == null
                || known(repo).map(branch::equals).orElse(USUAL_DEFAULTS.contains(branch));
        return onDefault ? Optional.of(sentence(tool, repo, branch == null ? known(repo).orElse(null) : branch))
                : Optional.empty();
    }

    /** The approval's summary: what is written, where. */
    public static String sentence(String tool, String repo, String branch) {
        return "Write straight to the default branch" + (branch == null ? "" : " " + branch)
                + (repo == null ? "" : " of " + repo) + " using github." + tool;
    }

    /** The refusal when a branch only turns out to be the default once GitHub is asked. */
    public static String askFirst(String branch, String repo) {
        return branch + " is the default branch of " + repo + ", and writing straight to it needs a person's approval."
                + " Call the tool again to ask for that approval, or commit to a new branch and open a pull request.";
    }

    private static String text(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }
}
