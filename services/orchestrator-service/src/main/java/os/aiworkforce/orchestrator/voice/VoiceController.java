// @find: voice api, /api/voice, speech, text to speech, transcriptions, dictation, microphone, voices list, voice status, check key, clip audio, VoiceController, Voice settings page, read aloud button
// @what: REST endpoints for voice: status, voices, speech, transcription and clip playback.
// @flow: Calls VoiceService and VoiceClips
package os.aiworkforce.orchestrator.voice;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/** Text-to-speech, transcription, and the clips a run's own voice note produced. */
@RestController
@RequestMapping("/api/voice")
@Tag(name = "Voice")
public class VoiceController {

    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    private static final List<String> ACCEPTED_AUDIO_TYPES =
            List.of("audio/webm", "audio/ogg", "audio/mpeg", "audio/wav", "audio/mp4");

    private final VoiceService voice;
    private final VoiceClips clips;
    private final Agents agents;

    public VoiceController(VoiceService voice, VoiceClips clips, Agents agents) {
        this.voice = voice;
        this.clips = clips;
        this.agents = agents;
    }

    public record VoiceStatusView(
            String provider, boolean keyStored, String tier, Integer charactersUsed, Integer characterLimit) {}

    public record VoiceView(String voiceId, String name, String category, String description, String previewUrl) {}

    public record SpeechRequest(@NotBlank @Size(max = 2_500) String text, UUID agentId) {}

    public record TranscriptionView(String text) {}

    public record KeyCheckRequest(@NotBlank @Size(max = 512) String key) {}

    public record KeyCheckView(boolean verified, String message) {}

    /**
     * Asks ElevenLabs whether a key works before the console stores it, so a mistyped key is
     * refused under the field instead of showing ElevenLabs as connected while every clip fails.
     */
    // @find: check ElevenLabs key, POST /api/voice/key/check
    @PostMapping("/key/check")
    @RequiresPermission(Permission.Codes.PROVIDER_MANAGE)
    @Operation(summary = "Check an ElevenLabs key with ElevenLabs, without storing it")
    public KeyCheckView checkKey(@Valid @RequestBody KeyCheckRequest request) {
        VoiceService.KeyCheck check = voice.checkKey(request.key().strip());
        return new KeyCheckView(check.verified(), check.message());
    }

    // @find: voice status, is voice enabled, GET /api/voice/status
    @GetMapping("/status")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Whether a workspace ElevenLabs key is stored, and its quota")
    public VoiceStatusView status() {
        VoiceService.StatusView status = voice.status(orgId());
        return new VoiceStatusView(
                status.provider(), status.keyStored(), status.tier(), status.charactersUsed(), status.characterLimit());
    }

    // @find: list voices, GET /api/voice/voices
    @GetMapping("/voices")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "The voices the stored key offers; empty without one")
    public List<VoiceView> voices() {
        return voice.voices(orgId()).stream()
                .map(v -> new VoiceView(v.voiceId(), v.name(), v.category(), v.description(), v.previewUrl()))
                .toList();
    }

    // @find: text to speech, read aloud, POST /api/voice/speech
    @PostMapping(value = "/speech", produces = "audio/mpeg")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Read a piece of text aloud, in an agent's voice when one is given")
    public ResponseEntity<byte[]> speech(@Valid @RequestBody SpeechRequest request) {
        UUID orgId = orgId();
        Agent agent = request.agentId() == null
                ? null
                : agents.findByIdAndOrgId(request.agentId(), orgId).orElse(null);
        byte[] audio = voice.speech(orgId, request.text(), agent);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.valueOf("audio/mpeg"))
                .body(audio);
    }

    // @find: transcribe recording, dictate message, POST /api/voice/transcriptions
    @PostMapping("/transcriptions")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Turn a short recording into text")
    public TranscriptionView transcribe(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiException.validation("file", "No audio was uploaded.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The recording is larger than 10 MB.");
        }
        String contentType = file.getContentType();
        if (contentType == null || ACCEPTED_AUDIO_TYPES.stream().noneMatch(contentType::startsWith)) {
            throw ApiException.validation("file", "That recording type is not accepted here.");
        }
        try {
            String text = voice.transcribe(orgId(), file.getBytes(), file.getOriginalFilename(), contentType);
            return new TranscriptionView(text);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "The recording could not be read.", e);
        }
    }

    // @find: play voice clip, GET /api/voice/clips/{id}
    @GetMapping("/clips/{clipId}")
    @RequiresPermission(Permission.Codes.RUN_READ)
    @Operation(summary = "The audio for one clip a run produced")
    public ResponseEntity<byte[]> clip(@PathVariable UUID clipId) {
        VoiceClip clip =
                clips.findByIdAndOrgId(clipId, orgId()).orElseThrow(() -> ApiException.notFound("voice clip", clipId));
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf(clip.getContentType()))
                .body(clip.getAudio());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
