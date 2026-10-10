// @find: elevenlabs client, text to speech, speech to text, transcribe audio, list voices, subscription, voice api, ElevenLabsClient, read aloud, dictation
// @what: HTTP client for the ElevenLabs speech, transcription, voices and subscription APIs.
// @flow: Called by VoiceService
package os.aiworkforce.orchestrator.voice;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * The ElevenLabs API, over its REST surface.
 *
 * <p>One synchronous client, deliberately: every caller of this class already blocks (a request
 * thread producing a response, or {@code VoiceClipService} inside a tool call an agent is waiting
 * on), so a reactive pipeline here would only be a chain of {@code .block()} calls dressed up.
 *
 * <p>The API key never becomes a field. It arrives as a parameter on the one call that needs it
 * and is never attached to a log line or an exception - which is also why every failure path below
 * reports a stable sentence rather than the provider's own response body.
 */
@Component
public class ElevenLabsClient {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsClient.class);
    private static final String KEY_HEADER = "xi-api-key";

    private final RestClient client;
    private final VoiceProperties properties;

    public ElevenLabsClient(RestClient elevenLabsRestClient, VoiceProperties properties) {
        this.client = elevenLabsRestClient;
        this.properties = properties;
    }

    /** Synthesises {@code text} in one voice, returning the audio bytes in the configured format. */
    // @find: text to speech call, generate audio
    public byte[] speech(String apiKey, String voiceId, String text) {
        try {
            return client.post()
                    .uri(uri -> uri.path("/v1/text-to-speech/{voiceId}")
                            .queryParam("output_format", properties.outputFormat())
                            .build(voiceId))
                    .header(KEY_HEADER, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SpeechRequest(text, properties.ttsModel(), VoiceSettings.DEFAULT))
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            throw mapped(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    /** Transcribes one recording, returning the text ElevenLabs heard. */
    // @find: transcribe audio, speech to text, dictation
    public String transcribe(String apiKey, byte[] audio, String filename, String contentType) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("model_id", properties.sttModel());
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        ByteArrayResource file = new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename == null ? "clip.audio" : filename;
            }
        };
        form.add("file", new HttpEntity<>(file, fileHeaders));

        try {
            TranscriptionResponse response = client.post()
                    .uri("/v1/speech-to-text")
                    .header(KEY_HEADER, apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(TranscriptionResponse.class);
            return response == null || response.text() == null ? "" : response.text();
        } catch (RestClientResponseException e) {
            throw mapped(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    /** The voices this key can use. Empty rather than thrown when the response holds none. */
    // @find: list voices
    public List<VoiceDto> voices(String apiKey) {
        try {
            VoicesResponse response = client.get()
                    .uri(uri ->
                            uri.path("/v2/voices").queryParam("page_size", 100).build())
                    .header(KEY_HEADER, apiKey)
                    .retrieve()
                    .body(VoicesResponse.class);
            return response == null || response.voices() == null ? List.of() : response.voices();
        } catch (RestClientResponseException e) {
            throw mapped(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    // @find: check ElevenLabs key or subscription
    public Subscription subscription(String apiKey) {
        try {
            Subscription response = client.get()
                    .uri("/v1/user/subscription")
                    .header(KEY_HEADER, apiKey)
                    .retrieve()
                    .body(Subscription.class);
            return response == null ? new Subscription(null, null, null) : response;
        } catch (RestClientResponseException e) {
            throw mapped(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    /**
     * Turns a rejected request into the sentence a person acts on. 401 is a bad or revoked key,
     * 429 a rate limit or an exhausted quota; anything else is reported plainly as an upstream
     * fault, with the body kept in the log rather than the response.
     */
    private ApiException mapped(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        if (status.value() == 401) {
            return new ApiException(ErrorCode.PROVIDER_CREDENTIAL_INVALID, "ElevenLabs refused the stored key.", e);
        }
        if (status.value() == 429) {
            return new ApiException(ErrorCode.RATE_LIMITED, "ElevenLabs is rate limiting or the quota is used up.", e);
        }
        log.warn("ElevenLabs returned {}: {}", status, e.getResponseBodyAsString());
        return new ApiException(ErrorCode.UPSTREAM_ERROR, "ElevenLabs returned an unexpected response.", e);
    }

    private ApiException unavailable(RestClientException e) {
        return new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "ElevenLabs is unavailable.", e);
    }

    private record SpeechRequest(
            String text,
            @JsonProperty("model_id") String modelId,
            @JsonProperty("voice_settings") VoiceSettings voiceSettings) {}

    private record VoiceSettings(double stability, @JsonProperty("similarity_boost") double similarityBoost) {
        static final VoiceSettings DEFAULT = new VoiceSettings(0.5, 0.75);
    }

    public record VoiceDto(
            @JsonProperty("voice_id") String voiceId,
            String name,
            String category,
            String description,
            @JsonProperty("preview_url") String previewUrl) {}

    private record VoicesResponse(List<VoiceDto> voices) {}

    private record TranscriptionResponse(String text) {}

    public record Subscription(
            String tier,
            @JsonProperty("character_count") Integer characterCount,
            @JsonProperty("character_limit") Integer characterLimit) {}
}
