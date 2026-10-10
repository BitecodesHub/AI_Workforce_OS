// @find: tests for provider key classify, provider key classify, API key test result classification, bad key, rate limited, /api/providers/{id}/test
// @what: Unit and integration tests (10 cases) for provider key classify, for example: valid only when the key was accepted; gemini bad key answers four hundred; xai bad key answers four hundred; other wordings of arefused key.
package os.aiworkforce.orchestrator.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.llm.model.ProviderException;
import os.aiworkforce.llm.model.ProviderFailure;
import os.aiworkforce.orchestrator.web.ProviderController.KeyCheck;

/**
 * What a pasted key's check comes to, given the status and the words of the provider's answer.
 *
 * <p>Providers disagree about how to refuse a key. Most answer 401; Google and xAI answer 400.
 * The adapters file a 400 as a malformed request, so a classification that goes by the failure
 * alone called a refused key valid and stored it. These hold the status and the body to account.
 */
class ProviderKeyClassifyTest {

    private static ProviderException answer(ProviderFailure failure, int status, String body) {
        return new ProviderException(failure, "provider", "model", "Provider responded", status, null, body, null);
    }

    @Test
    @DisplayName("a 2xx answer is valid, and so is a failure that only follows a key being accepted")
    void validOnlyWhenTheKeyWasAccepted() {
        assertThat(ProviderController.classifyAnswer(null)).isEqualTo(KeyCheck.VALID);
        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.MODEL_NOT_FOUND, 404, "no such model")))
                .isEqualTo(KeyCheck.VALID);
        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.CONTEXT_LENGTH_EXCEEDED, 413, "too long")))
                .isEqualTo(KeyCheck.VALID);
        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.CONTENT_FILTERED, 400, "content_filter")))
                .isEqualTo(KeyCheck.VALID);
    }

    @Test
    @DisplayName("Google's 400 for a made-up key is rejected, not valid")
    void geminiBadKeyAnswersFourHundred() {
        String body = "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\","
                + "\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}";

        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.INVALID_REQUEST, 400, body)))
                .isEqualTo(KeyCheck.REJECTED);
    }

    @Test
    @DisplayName("xAI's 400 for an incorrect key is rejected")
    void xaiBadKeyAnswersFourHundred() {
        String body = "{\"code\":\"Client specified an invalid argument\","
                + "\"error\":\"Incorrect API key provided: xai-****. You can obtain an API key from https://console.x.ai.\"}";

        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.INVALID_REQUEST, 400, body)))
                .isEqualTo(KeyCheck.REJECTED);
    }

    @Test
    @DisplayName("a 400 that names a missing or invalid credential in other words is rejected too")
    void otherWordingsOfARefusedKey() {
        for (String body : new String[] {
            "Missing Authorization header",
            "{\"error\":\"invalid x-api-key\"}",
            "No auth credentials found",
            "{\"error\":{\"type\":\"invalid_api_key\"}}",
            "Request is unauthorized",
            "The API key provided has expired",
            "{\"message\":\"Authentication failed\"}",
            "{\n  \"error\": {\n    \"message\": \"Incorrect\\n API key provided\"\n  }\n}",
        }) {
            assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.INVALID_REQUEST, 400, body)))
                    .as(body)
                    .isEqualTo(KeyCheck.REJECTED);
        }
    }

    @Test
    @DisplayName("an adapter's guess at a different failure cannot hide a refused key")
    void aKeyComplaintBeatsTheAdaptersGuess() {
        // The OpenAI-compatible adapter files "blocked" under a safety refusal and "model ... not found" under a missing model.
        assertThat(ProviderController.classifyAnswer(
                        answer(ProviderFailure.CONTENT_FILTERED, 400, "This API key is blocked. Invalid API key.")))
                .isEqualTo(KeyCheck.REJECTED);
        assertThat(ProviderController.classifyAnswer(
                        answer(ProviderFailure.MODEL_NOT_FOUND, 400, "Incorrect API key; model not found for this key")))
                .isEqualTo(KeyCheck.REJECTED);
    }

    @Test
    @DisplayName("a 401 or 403 is rejected whatever the adapter made of it, unless the account is out of credit")
    void unauthorisedStatusesAreRefusals() {
        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.INVALID_REQUEST, 401, "")))
                .isEqualTo(KeyCheck.REJECTED);
        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.MODEL_NOT_FOUND, 403, "denied")))
                .isEqualTo(KeyCheck.REJECTED);
        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.AUTHORISATION_FAILED, 403, "")))
                .isEqualTo(KeyCheck.REJECTED);
        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.QUOTA_EXHAUSTED, 403, "billing disabled")))
                .isEqualTo(KeyCheck.NO_CREDIT);
        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.INSUFFICIENT_CREDIT, 402, "")))
                .isEqualTo(KeyCheck.NO_CREDIT);
    }

    @Test
    @DisplayName("a 400 that objects to a parameter of the one-token call means the key was taken")
    void aComplaintAboutTheCallIsAKnownBenignShape() {
        String body = "{\"error\":{\"message\":\"Unsupported parameter: 'max_tokens' is not supported with this model."
                + " Use 'max_completion_tokens' instead.\"}}";

        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.INVALID_REQUEST, 400, body)))
                .isEqualTo(KeyCheck.VALID);
    }

    @Test
    @DisplayName("a 400 that says nothing either way could not be settled, and nothing is saved on it")
    void anUnreadableFourHundredIsUnsettled() {
        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.INVALID_REQUEST, 400, "{\"error\":\"bad request\"}")))
                .isEqualTo(KeyCheck.NETWORK_ERROR);
        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.INVALID_REQUEST, 400, null)))
                .isEqualTo(KeyCheck.NETWORK_ERROR);
        assertThat(ProviderController.classify(ProviderFailure.INVALID_REQUEST)).isEqualTo(KeyCheck.NETWORK_ERROR);
    }

    @Test
    @DisplayName("trouble that is the provider's or the network's is never read from the body")
    void serverTroubleStaysUnsettled() {
        // A throttle whose body talks about keys is still a throttle.
        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.RATE_LIMITED, 429, "Invalid API key rate")))
                .isEqualTo(KeyCheck.NETWORK_ERROR);
        assertThat(ProviderController.classifyAnswer(answer(ProviderFailure.SERVER_ERROR, 500, "unauthorized")))
                .isEqualTo(KeyCheck.NETWORK_ERROR);
        assertThat(ProviderController.classifyAnswer(ProviderException.of(ProviderFailure.UNKNOWN, "p", "m", "No answer")))
                .isEqualTo(KeyCheck.NETWORK_ERROR);
    }

    @Test
    @DisplayName("an unsettled 400 is worded as not knowing, not as the provider being out of reach")
    void theUnsettledMessageIsHonest() {
        String message = ProviderController.messageFor(KeyCheck.NETWORK_ERROR, ProviderFailure.INVALID_REQUEST, "Acme");

        assertThat(message).contains("Acme answered").contains("nothing was saved").doesNotContain("could not reach");
        assertThat(ProviderController.messageFor(KeyCheck.NETWORK_ERROR, ProviderFailure.TIMEOUT, "Acme"))
                .contains("could not reach");
    }
}
