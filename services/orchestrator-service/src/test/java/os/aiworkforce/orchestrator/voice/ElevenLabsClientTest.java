package os.aiworkforce.orchestrator.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * The HTTP layer mocked, so these exercise exactly what {@link ElevenLabsClient} sends and how it
 * turns a rejected request into a sentence a person acts on - never the real ElevenLabs service.
 */
class ElevenLabsClientTest {

    private static final VoiceProperties PROPERTIES = new VoiceProperties(
            "https://api.elevenlabs.io", "eleven_flash_v2_5", "scribe_v2", "mp3_44100_128", 2_500,
            Duration.ofSeconds(5), "elevenlabs");

    private MockRestServiceServer server;
    private ElevenLabsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(PROPERTIES.baseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ElevenLabsClient(builder.build(), PROPERTIES);
    }

    @Test
    @DisplayName("speech posts the text and voice settings, and returns the audio bytes")
    void speechReturnsAudioBytes() {
        server.expect(requestTo(containsString("/v1/text-to-speech/voice-1")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("xi-api-key", "secret-key"))
                .andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.valueOf("audio/mpeg")));

        byte[] audio = client.speech("secret-key", "voice-1", "Hello there.");

        assertThat(audio).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("a 401 from ElevenLabs is reported as the stored key being rejected")
    void unauthorizedIsReportedAsCredentialRejected() {
        server.expect(requestTo(containsString("/v1/text-to-speech/voice-1")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.speech("bad-key", "voice-1", "Hello there."))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.PROVIDER_CREDENTIAL_INVALID);
    }

    @Test
    @DisplayName("a 429 from ElevenLabs is reported as throttled")
    void rateLimitIsReportedAsThrottled() {
        server.expect(requestTo(containsString("/v1/text-to-speech/voice-1")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.speech("secret-key", "voice-1", "Hello there."))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("any other failure is reported as a plain provider error")
    void otherFailuresAreReportedAsProviderError() {
        server.expect(requestTo(containsString("/v1/text-to-speech/voice-1")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.speech("secret-key", "voice-1", "Hello there."))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.UPSTREAM_ERROR);
    }

    @Test
    @DisplayName("the voice list is parsed from ElevenLabs' own field names")
    void voicesParsesTheList() {
        server.expect(requestTo(containsString("/v2/voices")))
                .andExpect(header("xi-api-key", "secret-key"))
                .andRespond(withSuccess(
                        "{\"voices\":[{\"voice_id\":\"v1\",\"name\":\"Rachel\",\"category\":\"premade\","
                                + "\"description\":\"Calm\",\"preview_url\":\"https://example.com/v1.mp3\"}]}",
                        MediaType.APPLICATION_JSON));

        List<ElevenLabsClient.VoiceDto> voices = client.voices("secret-key");

        assertThat(voices).hasSize(1);
        assertThat(voices.getFirst().voiceId()).isEqualTo("v1");
        assertThat(voices.getFirst().name()).isEqualTo("Rachel");
        assertThat(voices.getFirst().previewUrl()).isEqualTo("https://example.com/v1.mp3");
    }

    @Test
    @DisplayName("transcribe posts the recording as multipart form data and returns the heard text")
    void transcribeReturnsText() {
        server.expect(requestTo(containsString("/v1/speech-to-text")))
                .andExpect(header("xi-api-key", "secret-key"))
                .andRespond(withSuccess("{\"text\":\"Book a meeting for Tuesday.\"}", MediaType.APPLICATION_JSON));

        String text = client.transcribe("secret-key", new byte[] {9, 9}, "clip.webm", "audio/webm");

        assertThat(text).isEqualTo("Book a meeting for Tuesday.");
    }
}
