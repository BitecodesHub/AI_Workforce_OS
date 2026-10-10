// @find: tests for embedding refused, embedding error messages, provider refuses embedding, plain-words reason, knowledge base embeddings
// @what: Checks the reason a provider refused an embedding is explained in plain words.
package os.aiworkforce.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class EmbeddingRefusalTest {

    @Test
    @DisplayName("the orchestrator's own reason is kept, and the request URL never is")
    void reasonComesFromTheProblemBody() {
        WebClientResponseException refused = WebClientResponseException.create(
                409,
                "Conflict",
                HttpHeaders.EMPTY,
                "{\"code\":\"provider_not_configured\",\"detail\":\"That provider has not been configured for this workspace.\"}"
                        .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThat(EmbeddingService.reasonOf(refused))
                .isEqualTo("That provider has not been configured for this workspace.");
    }

    @Test
    @DisplayName("a body that is not a problem falls back to the status")
    void unreadableBodyFallsBackToStatus() {
        WebClientResponseException refused = WebClientResponseException.create(
                502, "Bad Gateway", HttpHeaders.EMPTY, "<html>".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThat(EmbeddingService.reasonOf(refused)).isEqualTo("the model service answered with status 502.");
    }
}
