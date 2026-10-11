// @find: tests for browser screenshot service, save screenshot, no screenshot no row, bad base64 ignored, BrowserShotServiceTest
// @what: Checks screenshots from browser tool results are stored with their page address and title, and that a missing or broken one is skipped without failing.
package os.aiworkforce.orchestrator.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.mcp.builtin.BrowserAdapter;
import os.aiworkforce.mcp.model.ToolResult;

class BrowserShotServiceTest {

    private final BrowserShots shots = mock(BrowserShots.class);
    private final BrowserShotService service = new BrowserShotService(shots);
    private final UUID org = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();

    @Test
    @DisplayName("a screenshot in the result is decoded and stored with its page address and title")
    void stores() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        ToolResult result = result(Map.of(
                BrowserAdapter.SCREENSHOT, Base64.getEncoder().encodeToString(jpeg),
                BrowserAdapter.PAGE_URL, "https://example.com/",
                BrowserAdapter.PAGE_TITLE, "Example Domain"));

        Optional<UUID> id = service.save(org, run, "call_1", result);

        ArgumentCaptor<BrowserShot> saved = ArgumentCaptor.forClass(BrowserShot.class);
        verify(shots).save(saved.capture());
        assertThat(id).contains(saved.getValue().getId());
        assertThat(saved.getValue().getJpeg()).isEqualTo(jpeg);
        assertThat(saved.getValue().getUrl()).isEqualTo("https://example.com/");
        assertThat(saved.getValue().getTitle()).isEqualTo("Example Domain");
        assertThat(saved.getValue().getOrgId()).isEqualTo(org);
        assertThat(saved.getValue().getRunId()).isEqualTo(run);
        assertThat(saved.getValue().getToolCallId()).isEqualTo("call_1");
    }

    @Test
    @DisplayName("no screenshot, or one that is not base64, stores nothing and does not throw")
    void skips() {
        assertThat(service.save(org, run, "call_1", result(Map.of()))).isEmpty();
        assertThat(service.save(org, run, "call_1", result(Map.of(BrowserAdapter.SCREENSHOT, "%%%not base64")))).isEmpty();
        verifyNoInteractions(shots);
    }

    @Test
    @DisplayName("a store that fails is logged and the step goes on without a picture")
    void storeFailure() {
        when(shots.save(any())).thenThrow(new IllegalStateException("db down"));
        ToolResult result = result(Map.of(BrowserAdapter.SCREENSHOT, Base64.getEncoder().encodeToString(new byte[] {1})));
        assertThat(service.save(org, run, "call_1", result)).isEmpty();
    }

    private static ToolResult result(Map<String, String> metadata) {
        return new ToolResult(ToolResult.Status.SUCCEEDED, "{}", "Opened.", Duration.ZERO, metadata);
    }
}
