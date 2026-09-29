package os.aiworkforce.orchestrator.voice;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where ElevenLabs lives and the defaults every call to it uses.
 *
 * <p>{@code credentialRef} is the key under which a workspace's own API key is stored in the
 * organisation service's credential store - the same store Model Routing's "Store key" writes
 * to - so nothing here ever holds the key itself; {@code OrgCredentialResolver} looks it up
 * immediately before the one request that needs it.
 */
@Validated
@ConfigurationProperties(prefix = "aiwos.voice.elevenlabs")
public record VoiceProperties(
        @NotBlank @DefaultValue("https://api.elevenlabs.io") String baseUrl,
        @NotBlank @DefaultValue("eleven_flash_v2_5") String ttsModel,
        @NotBlank @DefaultValue("scribe_v2") String sttModel,
        @NotBlank @DefaultValue("mp3_44100_128") String outputFormat,
        @Positive @DefaultValue("2500") int maxCharacters,
        @DefaultValue("PT30S") Duration timeout,
        @NotBlank @DefaultValue("elevenlabs") String credentialRef) {}
