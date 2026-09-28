package os.aiworkforce.orchestrator.voice;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.service.OrgCredentialResolver;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * Speech, transcription and the voice list, for whichever workspace is asking.
 *
 * <p>A workspace without a stored ElevenLabs key is not an error state - it is the ordinary
 * starting point, and every read here answers accordingly: {@link #status} reports the browser
 * fallback, {@link #voices} answers an empty list, and only the two calls that actually need
 * ElevenLabs to do something ({@link #speechWithVoice} and {@link #transcribe}) raise
 * {@link ErrorCode#VOICE_NOT_CONFIGURED}.
 */
@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);
    private static final Duration VOICES_CACHE_TTL = Duration.ofMinutes(10);

    private final ElevenLabsClient client;
    private final OrgCredentialResolver credentials;
    private final VoiceProperties properties;

    /* Per workspace: refetching the voice list on every reply would mean an ordinary chat turn
     * pays for a call to ElevenLabs it does not need the result of yet again. */
    private final Map<UUID, CachedVoices> voicesCache = new ConcurrentHashMap<>();

    public VoiceService(ElevenLabsClient client, OrgCredentialResolver credentials, VoiceProperties properties) {
        this.client = client;
        this.credentials = credentials;
        this.properties = properties;
    }

    public record VoiceView(String voiceId, String name, String category, String description, String previewUrl) {}

    public record StatusView(
            String provider, boolean keyStored, String tier, Integer charactersUsed, Integer characterLimit) {}

    private record CachedVoices(List<VoiceView> voices, Instant fetchedAt) {
        boolean isFresh() {
            return Instant.now().isBefore(fetchedAt.plus(VOICES_CACHE_TTL));
        }
    }

    public boolean keyStored(UUID orgId) {
        return credentials.resolve(orgId.toString(), properties.credentialRef()).isPresent();
    }

    public StatusView status(UUID orgId) {
        Optional<String> key = credentials.resolve(orgId.toString(), properties.credentialRef());
        if (key.isEmpty()) {
            return new StatusView("browser", false, null, null, null);
        }
        try {
            ElevenLabsClient.Subscription subscription = client.subscription(key.get());
            return new StatusView(
                    "elevenlabs", true, subscription.tier(), subscription.characterCount(),
                    subscription.characterLimit());
        } catch (ApiException e) {
            // A stored key the provider currently rejects is still a stored key: the person sees
            // that from the failing usage numbers, not from the browser fallback appearing.
            log.debug("ElevenLabs subscription could not be read for org {}: {}", orgId, e.getMessage());
            return new StatusView("elevenlabs", true, null, null, null);
        }
    }

    public List<VoiceView> voices(UUID orgId) {
        Optional<String> key = credentials.resolve(orgId.toString(), properties.credentialRef());
        if (key.isEmpty()) {
            return List.of();
        }
        CachedVoices cached = voicesCache.get(orgId);
        if (cached != null && cached.isFresh()) {
            return cached.voices();
        }
        List<VoiceView> fetched = client.voices(key.get()).stream()
                .map(v -> new VoiceView(v.voiceId(), v.name(), v.category(), v.description(), v.previewUrl()))
                .toList();
        voicesCache.put(orgId, new CachedVoices(fetched, Instant.now()));
        return fetched;
    }

    /**
     * The voice an agent speaks with: its own choice when it has one, otherwise a pick from the
     * workspace's voices that is stable for that agent, so it sounds like the same person on
     * every clip rather than a different one each time.
     *
     * <p>Null when no key is stored, or the key's voice list is empty - callers treat that as
     * "no audio to make", not as a failure.
     */
    public String resolveVoiceId(UUID orgId, Agent agent) {
        if (agent != null && agent.getVoiceId() != null && !agent.getVoiceId().isBlank()) {
            return agent.getVoiceId();
        }
        List<VoiceView> available = voices(orgId);
        if (available.isEmpty()) {
            return null;
        }
        // UUID.hashCode() is derived from the value's own bits, not from object identity, so the
        // same agent lands on the same index across restarts and across instances.
        int seed = agent == null ? orgId.hashCode() : agent.getId().hashCode();
        return available.get(Math.floorMod(seed, available.size())).voiceId();
    }

    /** Refuses a voice id the stored key does not offer. Skipped entirely when no key is stored. */
    public void validateVoiceId(UUID orgId, String voiceId) {
        if (!keyStored(orgId)) {
            return;
        }
        boolean known = voices(orgId).stream().anyMatch(v -> v.voiceId().equals(voiceId));
        if (!known) {
            throw ApiException.validation("voiceId", "That voice is not offered by the stored key.");
        }
    }

    /** Speaks {@code text} in the voice this agent (or the workspace default) would use. */
    public byte[] speech(UUID orgId, String text, Agent agent) {
        String voiceId = resolveVoiceId(orgId, agent);
        if (voiceId == null) {
            throw ApiException.validation("voiceId", "No voice is available from the stored ElevenLabs key.");
        }
        return speechWithVoice(orgId, text, voiceId);
    }

    /** Speaks {@code text} in an already-chosen voice. */
    public byte[] speechWithVoice(UUID orgId, String text, String voiceId) {
        if (text == null || text.isBlank()) {
            throw ApiException.validation("text", "Text must not be blank.");
        }
        if (text.length() > properties.maxCharacters()) {
            throw ApiException.validation(
                    "text", "Text is longer than " + properties.maxCharacters() + " characters.");
        }
        return client.speech(requireKey(orgId), voiceId, text);
    }

    public String transcribe(UUID orgId, byte[] audio, String filename, String contentType) {
        return client.transcribe(requireKey(orgId), audio, filename, contentType);
    }

    private String requireKey(UUID orgId) {
        return credentials.resolve(orgId.toString(), properties.credentialRef())
                .orElseThrow(() -> new ApiException(ErrorCode.VOICE_NOT_CONFIGURED));
    }
}
