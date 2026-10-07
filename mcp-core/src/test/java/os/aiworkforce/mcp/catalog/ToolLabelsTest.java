package os.aiworkforce.mcp.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;

/** Tool names said in plain words, and never shown to a person in their internal form. */
class ToolLabelsTest {

    @Test
    @DisplayName("a tool's name reads as an action in the service it belongs to")
    void labels() {
        assertThat(ToolLabels.label("github", "create_issue")).isEqualTo("Create an issue in GitHub");
        assertThat(ToolLabels.label("github", "get_pulls")).isEqualTo("Read pull requests in GitHub");
        assertThat(ToolLabels.label("github", "list_repos")).isEqualTo("List repositories in GitHub");
        assertThat(ToolLabels.label("github", "merge_pull")).isEqualTo("Merge a pull request in GitHub");
        assertThat(ToolLabels.label("gmail", "send_message")).isEqualTo("Send a message in Gmail");
        assertThat(ToolLabels.label("person", "ask_question")).isEqualTo("Ask you a question");
    }

    @Test
    @DisplayName("every tool on every connector has a label free of underscores")
    void everyToolHasALabel() {
        SandboxServerRegistry.definitions().values().forEach(tools -> tools.forEach(tool ->
                assertThat(ToolLabels.label(tool.server(), tool.name())).as(tool.qualifiedName())
                        .doesNotContain("_")
                        .doesNotContain(".")));
    }

    @Test
    @DisplayName("a list of tool ids with descriptions keeps only the descriptions")
    void namedLines() {
        String answer = """
                I have these GitHub abilities:
                - github__create_issue: Open an issue in a repository.
                - `github__get_pulls`: List open pull requests.
                - **github.update_issue** - change an existing issue.""";

        String plain = ToolLabels.humanise(answer);

        assertThat(plain).isEqualTo("""
                I have these GitHub abilities:
                - Open an issue in a repository.
                - List open pull requests.
                - Change an existing issue.""");
    }

    @Test
    @DisplayName("a tool id inside a sentence becomes its label")
    void inline() {
        assertThat(ToolLabels.humanise("I used `github__list_issues` to check, then gmail.send_message failed."))
                .isEqualTo("I used list issues in GitHub to check, then send a message in Gmail failed.");
        assertThat(ToolLabels.humanise("github__create_issue opens an issue."))
                .isEqualTo("Create an issue in GitHub opens an issue.");
    }

    @Test
    @DisplayName("text without a tool id is left exactly as it was")
    void untouched() {
        String text = "See example.com and notes.md, or email sam@acme.test. The repo is acme/website.";
        assertThat(ToolLabels.humanise(text)).isSameAs(text);
        assertThat(ToolLabels.humanise("Read docs.readme and app.config_value")).isEqualTo("Read docs.readme and app.config_value");
        assertThat(ToolLabels.humanise(null)).isNull();
    }
}
