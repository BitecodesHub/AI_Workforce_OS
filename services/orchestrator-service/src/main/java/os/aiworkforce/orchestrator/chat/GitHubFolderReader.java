// @find: github folder reader, repo picker folders, list folder contents, git trees non-recursive, contents api fallback, large folder listing, truncated tree
// @what: Reads the files and folders directly inside one folder of a GitHub repository, completely, for the composer's repository picker.
// @flow: Called by GitHubContextController.tree with the workspace's live GitHub token; read-only GitHub REST calls.
package os.aiworkforce.orchestrator.chat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One folder of a repository, listed in full.
 *
 * <p>A recursive tree of a large repository is cut short by GitHub (and by the connector's own
 * limit), so folders such as {@code web/} went missing from the picker. This asks for one folder
 * at a time instead: the non-recursive tree of {@code ref:path}, which lists every direct child.
 * When GitHub still reports that tree as truncated, the contents API is read page by page instead.
 * Only reads; nothing here can change a repository.
 */
@Component
public class GitHubFolderReader {

    static final String BASE_URL = "https://api.github.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 30;

    private final WebClient client;
    private final String baseUrl;

    @Autowired
    public GitHubFolderReader(WebClient.Builder builder) {
        this(builder, BASE_URL);
    }

    GitHubFolderReader(WebClient.Builder builder, String baseUrl) {
        this.client = builder.clone().build();
        this.baseUrl = baseUrl;
    }

    /**
     * @param entries every file and folder directly inside the folder, each {@code path} (full)
     *     and {@code type} ({@code dir} or {@code file}), folders first then by name
     * @param complete false only when even the page-by-page read hit its limit
     */
    public record Folder(List<Map<String, Object>> entries, boolean complete) {}

    // @find: list one github folder completely
    /**
     * @param repo "owner/name", already checked
     * @param ref a branch, or null for the default branch
     * @param folder a folder inside it, or null for the root
     */
    public Folder list(String token, String repo, String ref, String folder) {
        String branch = ref != null ? ref : defaultBranch(token, repo);
        String prefix = folder == null ? "" : folder + "/";
        JsonNode tree = get(token, "/repos/" + repo + "/git/trees/" + encode(branch + ":" + (folder == null ? "" : folder)));
        if (!tree.path("truncated").asBoolean(false)) {
            List<Map<String, Object>> entries = new ArrayList<>();
            tree.path("tree").forEach(item -> entries.add(entry(
                    prefix + item.path("path").asText(), "tree".equals(item.path("type").asText()))));
            return new Folder(sorted(entries), true);
        }
        // A folder too large for one tree: the contents API, a page at a time.
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode items = get(token, "/repos/" + repo + "/contents/" + (folder == null ? "" : folder) + "?ref="
                    + encode(branch) + "&per_page=" + PAGE_SIZE + "&page=" + page);
            if (!items.isArray() || items.isEmpty()) {
                return new Folder(sorted(entries), true);
            }
            items.forEach(item -> entries.add(entry(item.path("path").asText(), "dir".equals(item.path("type").asText()))));
            if (items.size() < PAGE_SIZE) {
                return new Folder(sorted(entries), true);
            }
        }
        return new Folder(sorted(entries), false);
    }

    private String defaultBranch(String token, String repo) {
        return get(token, "/repos/" + repo).path("default_branch").asText("main");
    }

    private JsonNode get(String token, String path) {
        JsonNode body = client.get()
                // Already encoded: a URI, so the client does not encode it a second time.
                .uri(java.net.URI.create(baseUrl + path))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header(HttpHeaders.USER_AGENT, "ai-workforce-os")
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(TIMEOUT)
                .block();
        return body == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : body;
    }

    private static Map<String, Object> entry(String path, boolean dir) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", path);
        entry.put("type", dir ? "dir" : "file");
        return entry;
    }

    private static List<Map<String, Object>> sorted(List<Map<String, Object>> entries) {
        return entries.stream()
                .sorted(Comparator.comparing((Map<String, Object> e) -> !"dir".equals(e.get("type")))
                        .thenComparing(e -> e.get("path").toString().toLowerCase(java.util.Locale.ROOT)))
                .toList();
    }

    /** Encodes a path segment, keeping '/' and ':' which GitHub reads as written. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/")
                .replace("%3A", ":");
    }
}
