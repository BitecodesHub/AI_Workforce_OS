// @find: live test for phone calls connector, real ElevenLabs account, no phone number message, optional live check, ELEVENLABS_LIVE_KEY
// @what: Optional check against the real ElevenLabs API: with an account that has no phone number, place_call answers the plain no-number message and creates nothing. Runs only when ELEVENLABS_LIVE_KEY is set; reads only, never dials or creates anything.
package os.aiworkforce.mcp.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;

@EnabledIfEnvironmentVariable(named = "ELEVENLABS_LIVE_KEY", matches = ".+")
class PhoneAdapterLiveTest {

    @Test
    @DisplayName("against the real account: no phone number gives the plain message, and nothing is dialled")
    void noNumberOnTheRealAccount() {
        String key = System.getenv("ELEVENLABS_LIVE_KEY");
        PhoneAdapter adapter = new PhoneAdapter(
                SandboxServerRegistry.definitions().get("phone"),
                new ObjectMapper(),
                WebClient.builder(),
                PhoneAdapter.BASE_URL,
                () -> org -> Optional.of(key));
        ToolResult numbers = adapter.invoke(
                        new ToolInvocation("org-live", "agent-live", "run-live", "phone", "list_phone_numbers", "{}",
                                "run-live:0", Map.of()),
                        null)
                .block(Duration.ofSeconds(20));
        assertThat(numbers.status()).as(numbers.summary()).isEqualTo(ToolResult.Status.SUCCEEDED);
        boolean hasNumber = !numbers.summary().startsWith("No phone numbers");
        // With a number on the account this test would place a real call, so it stops here instead.
        assumeTrue(!hasNumber, "The account has a phone number; not placing a real call");

        ToolResult result = adapter.invoke(
                        new ToolInvocation("org-live", "agent-live", "run-live", "phone", "place_call",
                                "{\"to_number\":\"+61299990000\",\"purpose\":\"Live check, never dialled\"}",
                                "run-live:1", Map.of()),
                        null)
                .block(Duration.ofSeconds(30));

        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(result.summary()).isEqualTo(PhoneAdapter.NO_NUMBER);
    }
}
