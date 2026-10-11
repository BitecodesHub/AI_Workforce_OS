// @find: tests for GitHubContextController, repo picker folders, direct children of a recursive tree
// @what: Tests that the repo picker shows only the files and folders directly inside the folder being browsed.
// @flow: Exercises GitHubContextController.directChildren
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GitHubContextControllerTest {

    private static JsonNode tree(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    @Test
    @DisplayName("a recursive tree is cut to the root's direct children, folders first, with folders found from deeper paths")
    void rootChildren() throws Exception {
        JsonNode items = tree("""
                [{"path":".github/workflows/ci.yml","type":"blob"},
                 {"path":"README.md","type":"blob"},
                 {"path":"web","type":"tree"},
                 {"path":"web/src/App.tsx","type":"blob"}]
                """);

        List<Map<String, Object>> children = GitHubContextController.directChildren(items, null);

        assertThat(children).extracting(m -> m.get("path") + ":" + m.get("type"))
                .containsExactly(".github:dir", "web:dir", "README.md:file");
    }

    @Test
    @DisplayName("inside a folder only its own entries come back, with full paths")
    void folderChildren() throws Exception {
        JsonNode items = tree("""
                [{"path":"web/src/App.tsx","type":"blob"},
                 {"path":"web/src/components/chat/Composer.tsx","type":"blob"},
                 {"path":"web/package.json","type":"blob"},
                 {"path":"webby/x.txt","type":"blob"}]
                """);

        assertThat(GitHubContextController.directChildren(items, "web/src"))
                .extracting(m -> m.get("path") + ":" + m.get("type"))
                .containsExactly("web/src/components:dir", "web/src/App.tsx:file");
    }
}
