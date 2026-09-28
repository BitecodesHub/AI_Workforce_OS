package os.aiworkforce.mcp.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;

/** The one tool whose sandbox summary is not the generic "recorded a record" sentence. */
class SandboxServerAdapterTest {

    @Test
    @DisplayName("create_voice_note reports the script length rather than the generic sandbox summary")
    void voiceNoteSummaryNamesTheScriptLength() {
        ObjectMapper json = new ObjectMapper();
        ToolDefinition createVoiceNote = new ToolDefinition(
                "voice", "create_voice_note", "Write a script.",
                "{\"type\":\"object\"}", ToolSpec.SideEffect.WRITE, List.of(), false, Duration.ofSeconds(30), 30);
        SandboxServerAdapter adapter = new SandboxServerAdapter("voice", List.of(createVoiceNote), json);

        ToolResult result = adapter
                .invoke(new ToolInvocation(
                        "org-1", "agent-1", "run-1", "voice", "create_voice_note",
                        "{\"text\":\"Hello there.\"}", "run-1:call-1", java.util.Map.of()),
                        "unused-credential")
                .block();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.summary()).isEqualTo("Saved a voice note script (12 characters).");
    }
}
