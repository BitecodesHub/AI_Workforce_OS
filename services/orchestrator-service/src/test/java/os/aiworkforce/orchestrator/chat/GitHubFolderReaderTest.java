// @find: tests for GitHubFolderReader, repo picker large folders, non-recursive tree, contents pagination, default branch
// @what: Tests that the repository picker lists a folder in full, however large, with full paths and folders first.
// @flow: Exercises GitHubFolderReader against a stubbed GitHub API
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GitHubFolderReaderTest {

    private final List<String> asked = new ArrayList<>();

    private GitHubFolderReader reader(Function<String, String> answers) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction((ClientRequest request) -> {
            String url = request.url().getRawPath() + (request.url().getRawQuery() == null ? "" : "?" + request.url().getRawQuery());
            asked.add(url);
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer t");
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(answers.apply(url))
                    .build());
        });
        return new GitHubFolderReader(builder, "https://api.github.test");
    }

    @Test
    @DisplayName("one folder's own tree is read, so every direct child comes back with its full path, folders first")
    void folderTree() {
        GitHubFolderReader reader = reader(url -> """
                {"truncated":false,"tree":[
                  {"path":"package.json","type":"blob"},
                  {"path":"src","type":"tree"},
                  {"path":"README.md","type":"blob"},
                  {"path":"public","type":"tree"}]}
                """);

        GitHubFolderReader.Folder folder = reader.list("t", "acme/site", "main", "web");

        assertThat(asked).containsExactly("/repos/acme/site/git/trees/main:web");
        assertThat(folder.complete()).isTrue();
        assertThat(folder.entries()).extracting(e -> e.get("path") + ":" + e.get("type"))
                .containsExactly("web/public:dir", "web/src:dir", "web/package.json:file", "web/README.md:file");
    }

    @Test
    @DisplayName("without a branch the default one is used; a truncated tree is read page by page from the contents API")
    void truncatedFolderIsPaged() {
        String firstPage = IntStream.range(0, 100)
                .mapToObj(i -> "{\"path\":\"web/f" + i + ".ts\",\"type\":\"file\"}")
                .collect(Collectors.joining(",", "[", "]"));
        GitHubFolderReader reader = reader(url -> {
            if (url.equals("/repos/acme/site")) return "{\"default_branch\":\"trunk\"}";
            if (url.contains("/git/trees/")) return "{\"truncated\":true,\"tree\":[]}";
            if (url.endsWith("&page=1")) return firstPage;
            if (url.endsWith("&page=2")) return "[{\"path\":\"web/src\",\"type\":\"dir\"},{\"path\":\"web/z.ts\",\"type\":\"file\"}]";
            return "[]";
        });

        GitHubFolderReader.Folder folder = reader.list("t", "acme/site", null, "web");

        assertThat(asked).containsExactly(
                "/repos/acme/site",
                "/repos/acme/site/git/trees/trunk:web",
                "/repos/acme/site/contents/web?ref=trunk&per_page=100&page=1",
                "/repos/acme/site/contents/web?ref=trunk&per_page=100&page=2");
        assertThat(folder.entries()).hasSize(102);
        assertThat(folder.entries().getFirst()).containsEntry("path", "web/src").containsEntry("type", "dir");
        assertThat(folder.complete()).isTrue();
    }

    @Test
    @DisplayName("the root of a repository is the tree of 'branch:'")
    void root() {
        GitHubFolderReader reader = reader(url -> "{\"truncated\":false,\"tree\":[{\"path\":\"web\",\"type\":\"tree\"}]}");

        assertThat(reader.list("t", "acme/site", "dev", null).entries())
                .extracting(e -> e.get("path"))
                .containsExactly("web");
        assertThat(asked).containsExactly("/repos/acme/site/git/trees/dev:");
    }
}
