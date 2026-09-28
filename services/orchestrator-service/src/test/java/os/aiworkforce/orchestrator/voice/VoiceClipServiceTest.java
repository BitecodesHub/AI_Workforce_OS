package os.aiworkforce.orchestrator.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Run;

class VoiceClipServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    private VoiceService voice;
    private VoiceClips clips;
    private VoiceClipService service;
    private Run run;
    private Agent agent;

    @BeforeEach
    void setUp() {
        voice = mock(VoiceService.class);
        clips = mock(VoiceClips.class);
        service = new VoiceClipService(voice, clips, new ObjectMapper());

        run = new Run();
        run.setId(UUID.randomUUID());
        run.setOrgId(ORG);
        agent = new Agent();
        agent.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("without a stored key, nothing is synthesised and nothing is asked of ElevenLabs")
    void withoutKeyProducesNoClip() {
        when(voice.keyStored(ORG)).thenReturn(false);

        Optional<UUID> clipId = service.afterVoiceNote(run, agent, "{\"text\":\"Hello there.\"}");

        assertThat(clipId).isEmpty();
        verify(voice, never()).resolveVoiceId(any(), any());
        verify(voice, never()).speechWithVoice(any(), any(), any());
        verifyNoInteractions(clips);
    }

    @Test
    @DisplayName("keyStored is a straight read-through to the voice service")
    void keyStoredDelegates() {
        when(voice.keyStored(ORG)).thenReturn(true);

        assertThat(service.keyStored(ORG)).isTrue();
    }

    @Test
    @DisplayName("arguments without a text field produce no clip either")
    void missingTextProducesNoClip() {
        when(voice.keyStored(ORG)).thenReturn(true);

        Optional<UUID> clipId = service.afterVoiceNote(run, agent, "{}");

        assertThat(clipId).isEmpty();
        verify(voice, never()).resolveVoiceId(any(), any());
    }

    @Test
    @DisplayName("a stored key and a voice let a clip be synthesised and saved")
    void withKeyAndVoiceSavesAClip() {
        when(voice.keyStored(ORG)).thenReturn(true);
        when(voice.resolveVoiceId(ORG, agent)).thenReturn("v1");
        when(voice.speechWithVoice(ORG, "Hello there.", "v1")).thenReturn(new byte[] {1, 2, 3});

        Optional<UUID> clipId = service.afterVoiceNote(run, agent, "{\"text\":\"Hello there.\"}");

        assertThat(clipId).isPresent();
        verify(clips).save(any(VoiceClip.class));
    }

    @Test
    @DisplayName("a synthesis failure is swallowed: the run keeps its result, only the clip is missing")
    void synthesisFailureProducesNoClipRatherThanThrowing() {
        when(voice.keyStored(ORG)).thenReturn(true);
        when(voice.resolveVoiceId(ORG, agent)).thenReturn("v1");
        when(voice.speechWithVoice(any(), any(), any())).thenThrow(new RuntimeException("boom"));

        Optional<UUID> clipId = service.afterVoiceNote(run, agent, "{\"text\":\"Hello there.\"}");

        assertThat(clipId).isEmpty();
        verify(clips, never()).save(any());
    }
}
