package os.aiworkforce.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import os.aiworkforce.llm.model.ToolCall;
import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.mcp.model.ToolDefinition;

/** An action asked for beside a read waits for the read, so it never sends a placeholder. */
class HeldActionsTest {

    private static final List<ToolDefinition> AVAILABLE = List.of(
            tool("github", "list_issues", ToolSpec.SideEffect.READ),
            tool("github", "create_issue", ToolSpec.SideEffect.WRITE),
            tool("slack", "post_message", ToolSpec.SideEffect.OUTBOUND),
            tool("calendar", "delete_event", ToolSpec.SideEffect.DESTRUCTIVE));

    @Test
    void sendBesideAReadIsHeld() {
        List<ToolCall> calls = List.of(
                new ToolCall("a", "github.list_issues", "{}"),
                new ToolCall("b", "slack.post_message", "{\"text\":\"[issues here]\"}"),
                new ToolCall("c", "calendar.delete_event", "{}"));

        assertThat(AgentRunner.heldUntilReadsReturn(calls, AVAILABLE)).containsExactlyInAnyOrder("b", "c");
    }

    @Test
    void writesBesideAReadStillRun() {
        List<ToolCall> calls = List.of(
                new ToolCall("a", "github.list_issues", "{}"), new ToolCall("b", "github.create_issue", "{}"));

        assertThat(AgentRunner.heldUntilReadsReturn(calls, AVAILABLE)).isEmpty();
    }

    @Test
    void aSendOnItsOwnOrBesideOtherActionsRuns() {
        assertThat(AgentRunner.heldUntilReadsReturn(
                        List.of(new ToolCall("b", "slack.post_message", "{}")), AVAILABLE))
                .isEmpty();
        assertThat(AgentRunner.heldUntilReadsReturn(
                        List.of(
                                new ToolCall("b", "slack.post_message", "{}"),
                                new ToolCall("c", "github.create_issue", "{}")),
                        AVAILABLE))
                .isEmpty();
    }

    private static ToolDefinition tool(String server, String name, ToolSpec.SideEffect effect) {
        return new ToolDefinition(
                server, name, name, "{\"type\":\"object\"}", effect, List.of(), false, Duration.ofSeconds(5), 10);
    }
}
