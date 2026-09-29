package os.aiworkforce.orchestrator.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.service.OrgCredentialResolver;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/** Behaviour that does not need a real ElevenLabs call: the without-key path and voice choice. */
class VoiceServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final VoiceProperties PROPERTIES = new VoiceProperties(
            "https://api.elevenlabs.io",
            "eleven_flash_v2_5",
            "scribe_v2",
            "mp3_44100_128",
            2_500,
            Duration.ofSeconds(5),
            "elevenlabs");

    private ElevenLabsClient client;
    private OrgCredentialResolver credentials;
    private VoiceService service;

    @BeforeEach
    void setUp() {
        client = mock(ElevenLabsClient.class);
        credentials = mock(OrgCredentialResolver.class);
        service = new VoiceService(client, credentials, PROPERTIES);
    }

    @Test
    @DisplayName("without a stored key, status reports the browser fallback")
    void statusWithoutKeyIsBrowserFallback() {
        when(credentials.resolve(ORG.toString(), "elevenlabs")).thenReturn(Optional.empty());

        VoiceService.StatusView status = service.status(ORG);

        assertThat(status.provider()).isEqualTo("browser");
        assertThat(status.keyStored()).isFalse();
        assertThat(status.tier()).isNull();
    }

    @Test
    @DisplayName("without a stored key, the voice list is empty rather than an error")
    void voicesWithoutKeyIsEmpty() {
        when(credentials.resolve(ORG.toString(), "elevenlabs")).thenReturn(Optional.empty());

        assertThat(service.voices(ORG)).isEmpty();
        verify(client, never()).voices(any());
    }

    @Test
    @DisplayName(
            "speech without a stored key is refused with the voice-not-configured code, even with an agent voice chosen")
    void speechWithoutKeyIs409() {
        when(credentials.resolve(ORG.toString(), "elevenlabs")).thenReturn(Optional.empty());
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setVoiceId("v1");

        assertThatThrownBy(() -> service.speech(ORG, "Hello there.", agent))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.VOICE_NOT_CONFIGURED);
        assertThatThrownBy(() -> service.speech(ORG, "Hello there.", agent))
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("speech without a stored key is refused the same way with no agent at all - the common Chat case")
    void speechWithoutKeyAndNoAgentIs409() {
        // Regression test: resolveVoiceId(ORG, null) also returns null here, because voices(ORG)
        // is empty with no key stored. Before this was checked first, that null was reported as
        // "no voice is available" (422), the same message a stored key with zero voices gets,
        // rather than the 409 the API contract promises for no key at all.
        when(credentials.resolve(ORG.toString(), "elevenlabs")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.speech(ORG, "Hello there.", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.VOICE_NOT_CONFIGURED);
        assertThatThrownBy(() -> service.speech(ORG, "Hello there.", null))
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("transcribing without a stored key is refused the same way")
    void transcribeWithoutKeyIs409() {
        when(credentials.resolve(ORG.toString(), "elevenlabs")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transcribe(ORG, new byte[] {1}, "clip.webm", "audio/webm"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.VOICE_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("an agent's own voice is used whenever it has one, without consulting the voice list")
    void agentsOwnVoiceWins() {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setVoiceId("agent-choice");

        assertThat(service.resolveVoiceId(ORG, agent)).isEqualTo("agent-choice");
        verify(credentials, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("without an agent voice, the same agent always lands on the same voice from the list")
    void defaultVoiceChoiceIsStableForOneAgent() {
        when(credentials.resolve(ORG.toString(), "elevenlabs")).thenReturn(Optional.of("secret-key"));
        when(client.voices("secret-key"))
                .thenReturn(List.of(
                        new ElevenLabsClient.VoiceDto("v1", "Rachel", "premade", null, null),
                        new ElevenLabsClient.VoiceDto("v2", "Adam", "premade", null, null),
                        new ElevenLabsClient.VoiceDto("v3", "Domi", "premade", null, null)));
        Agent agent = new Agent();
        agent.setId(UUID.fromString("00000000-0000-0000-0000-000000000042"));

        String first = service.resolveVoiceId(ORG, agent);
        String second = service.resolveVoiceId(ORG, agent);

        assertThat(first).isEqualTo(second);
        assertThat(List.of("v1", "v2", "v3")).contains(first);
    }

    @Test
    @DisplayName("two different agents can land on different voices from the same list")
    void differentAgentsCanGetDifferentVoices() {
        when(credentials.resolve(ORG.toString(), "elevenlabs")).thenReturn(Optional.of("secret-key"));
        when(client.voices("secret-key"))
                .thenReturn(List.of(
                        new ElevenLabsClient.VoiceDto("v1", "Rachel", "premade", null, null),
                        new ElevenLabsClient.VoiceDto("v2", "Adam", "premade", null, null)));
        Agent hr = new Agent();
        hr.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        Agent support = new Agent();
        support.setId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        // Not every pair of ids is guaranteed to differ, but this pair is fixed and known to.
        assertThat(service.resolveVoiceId(ORG, hr)).isNotEqualTo(service.resolveVoiceId(ORG, support));
    }
}
