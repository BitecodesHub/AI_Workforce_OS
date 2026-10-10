// @find: voice service, text to speech, transcribe, voices, resolve voice id, validate voice, key check, voice status, ElevenLabs key, VoiceService, agent voice
// @what: Voice logic: reads the workspace key, lists voices, makes speech and transcribes audio.
// @flow: Called by VoiceController and VoiceClipService; calls ElevenLabsClient
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

    /** @param verified false when ElevenLabs could not say either way; the key is then stored on trust */
    public record KeyCheck(boolean verified, String message) {}

    /**
     * Whether ElevenLabs takes a key. Only a refusal of the key itself stops it being stored: a
     * key limited to speech may not read the subscription, and ElevenLabs being briefly down says
     * nothing about the key, so both are let through with a note.
     */
    // @find: check ElevenLabs key
    public KeyCheck checkKey(String key) {
        try {
            client.subscription(key);
            return new KeyCheck(true, null);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.PROVIDER_CREDENTIAL_INVALID) {
                throw ApiException.validation(
                        "value", "ElevenLabs did not accept this key. Check that it was copied in full and is still active.");
            }
            log.debug("ElevenLabs could not check a key: {}", e.getMessage());
            return new KeyCheck(false, "ElevenLabs could not confirm the key just now, so it was stored as it is.");
        }
    }

    public boolean keyStored(UUID orgId) {
        return credentials.resolve(orgId.toString(), properties.credentialRef()).isPresent();
    }

    // @find: voice status
    public StatusView status(UUID orgId) {
        Optional<String> key = credentials.resolve(orgId.toString(), properties.credentialRef());
        if (key.isEmpty()) {
            return new StatusView("browser", false, null, null, null);
        }
        try {
            ElevenLabsClient.Subscription subscription = client.subscription(key.get());
            return new StatusView(
                    "elevenlabs",
                    true,
                    subscription.tier(),
                    subscription.characterCount(),
                    subscription.characterLimit());
        } catch (ApiException e) {
            // A stored key the provider currently rejects is still a stored key: the person sees
            // that from the failing usage numbers, not from the browser fallback appearing.
            log.debug("ElevenLabs subscription could not be read for org {}: {}", orgId, e.getMessage());
            return new StatusView("elevenlabs", true, null, null, null);
        }
    }

    // @find: list voices
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
    // @find: which voice for an agent
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
    // @find: validate agent voice id
    public void validateVoiceId(UUID orgId, String voiceId) {
        if (!keyStored(orgId)) {
            return;
        }
        boolean known = voices(orgId).stream().anyMatch(v -> v.voiceId().equals(voiceId));
        if (!known) {
            throw ApiException.validation("voiceId", "That voice is not offered by the stored key.");
        }
    }

    /**
     * Speaks {@code text} in the voice this agent (or the workspace default) would use.
     *
     * <p>Checked before {@link #resolveVoiceId}, not after: with no key stored, {@code voices()}
     * is always empty and resolution always returns null, which reads exactly like the rarer case
     * of a stored key with no voices on it. The two are different problems - one is fixed by
     * storing a key, the other by choosing a voice in the ElevenLabs account - so only the second
     * is reported as a voiceId validation failure; the first keeps the contract's own code.
     */
    // @find: text to speech for agent
    public byte[] speech(UUID orgId, String text, Agent agent) {
        if (!keyStored(orgId)) {
            throw new ApiException(ErrorCode.VOICE_NOT_CONFIGURED);
        }
        String voiceId = resolveVoiceId(orgId, agent);
        if (voiceId == null) {
            throw ApiException.validation("voiceId", "No voice is available from the stored ElevenLabs key.");
        }
        return speechWithVoice(orgId, text, voiceId);
    }

    /** Speaks {@code text} in an already-chosen voice. */
    // @find: text to speech with voice
    public byte[] speechWithVoice(UUID orgId, String text, String voiceId) {
        if (text == null || text.isBlank()) {
            throw ApiException.validation("text", "Text must not be blank.");
        }
        if (text.length() > properties.maxCharacters()) {
            throw ApiException.validation("text", "Text is longer than " + properties.maxCharacters() + " characters.");
        }
        return client.speech(requireKey(orgId), voiceId, text);
    }

    // @find: transcribe audio
    public String transcribe(UUID orgId, byte[] audio, String filename, String contentType) {
        return client.transcribe(requireKey(orgId), audio, filename, contentType);
    }

    private String requireKey(UUID orgId) {
        return credentials
                .resolve(orgId.toString(), properties.credentialRef())
                .orElseThrow(() -> new ApiException(ErrorCode.VOICE_NOT_CONFIGURED));
    }
}
