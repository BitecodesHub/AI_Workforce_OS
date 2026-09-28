package os.aiworkforce.orchestrator.voice;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the one {@link RestClient} {@link ElevenLabsClient} talks over.
 *
 * <p>Kept out of {@code ElevenLabsClient} itself so a test can hand that class a
 * {@code RestClient} bound to {@code MockRestServiceServer} directly; a client that built its own
 * request factory inside its constructor would overwrite whatever a test had already bound to the
 * builder it was given.
 */
@Configuration
class VoiceConfig {

    @Bean
    RestClient elevenLabsRestClient(RestClient.Builder builder, VoiceProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) properties.timeout().toMillis();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }
}
