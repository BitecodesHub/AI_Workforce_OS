package os.aiworkforce.orchestrator.voice;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.Run;

/**
 * Turns a successful {@code voice.create_voice_note} tool call into an actual clip.
 *
 * <p>Called from {@code AgentRunner} right after that tool call is recorded, and deliberately
 * swallows everything that can go wrong here. An agent that already wrote its script and had the
 * sandbox record it must not have its run fail because narrating the script hit a rate limit;
 * the run keeps its result, and simply gains no audio for it.
 */
@Service
public class VoiceClipService {

    private static final Logger log = LoggerFactory.getLogger(VoiceClipService.class);

    private final VoiceService voice;
    private final VoiceClips clips;
    private final ObjectMapper json;

    public VoiceClipService(VoiceService voice, VoiceClips clips, ObjectMapper json) {
        this.voice = voice;
        this.clips = clips;
        this.json = json;
    }

    /** Whether the workspace has an ElevenLabs key at all, so a caller can explain a missing clip. */
    public boolean keyStored(UUID orgId) {
        return voice.keyStored(orgId);
    }

    /**
     * @param argumentsJson the tool call's own arguments, holding the {@code text} to speak
     * @return the saved clip's id, or empty when nothing was produced - no key stored, no voice
     *     available, arguments that did not carry a script, or the provider itself failing
     */
    public Optional<UUID> afterVoiceNote(Run run, Agent agent, String argumentsJson) {
        if (!voice.keyStored(run.getOrgId())) {
            return Optional.empty();
        }
        String text = textFrom(argumentsJson);
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            String voiceId = voice.resolveVoiceId(run.getOrgId(), agent);
            if (voiceId == null) {
                return Optional.empty();
            }
            byte[] audio = voice.speechWithVoice(run.getOrgId(), text, voiceId);
            VoiceClip clip = VoiceClip.of(
                    run.getOrgId(),
                    run.getId(),
                    agent == null ? null : agent.getId(),
                    text,
                    voiceId,
                    "audio/mpeg",
                    audio);
            clips.save(clip);
            return Optional.of(clip.getId());
        } catch (RuntimeException e) {
            log.warn("Voice note for run {} could not be synthesised: {}", run.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private String textFrom(String argumentsJson) {
        try {
            JsonNode node = json.readTree(argumentsJson == null ? "{}" : argumentsJson);
            return node.path("text").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
