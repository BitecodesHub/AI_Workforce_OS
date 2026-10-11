// @find: tests for ElevenLabs key lookup for phone calls, workspace secrets, store unavailable is not a missing key
// @what: Checks the phone and Twilio connectors get the stored ElevenLabs key, nothing when none is stored, and an error (not "no key") when the store does not answer.
package os.aiworkforce.orchestrator.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.llm.spi.CredentialResolver;
import os.aiworkforce.orchestrator.service.OrgCredentialResolver;

class ElevenLabsWorkspaceSecretsTest {

    private final OrgCredentialResolver credentials = mock(OrgCredentialResolver.class);
    private final ElevenLabsWorkspaceSecrets secrets = new ElevenLabsWorkspaceSecrets(credentials, new VoiceProperties(
            "https://api.elevenlabs.io", "eleven_flash_v2_5", "scribe_v2", "mp3_44100_128", 2_500,
            Duration.ofSeconds(5), "elevenlabs"));

    @Test
    @DisplayName("found, not found and unavailable stay three different answers")
    void threeAnswers() {
        when(credentials.lookup("org-1", "elevenlabs")).thenReturn(new CredentialResolver.Found("xi-key"));
        assertThat(secrets.elevenLabsKey("org-1")).contains("xi-key");

        when(credentials.lookup("org-2", "elevenlabs")).thenReturn(CredentialResolver.NotFound.INSTANCE);
        assertThat(secrets.elevenLabsKey("org-2")).isEmpty();

        when(credentials.lookup("org-3", "elevenlabs")).thenReturn(new CredentialResolver.Unavailable("timeout"));
        assertThatThrownBy(() -> secrets.elevenLabsKey("org-3")).isInstanceOf(IllegalStateException.class);
    }
}
