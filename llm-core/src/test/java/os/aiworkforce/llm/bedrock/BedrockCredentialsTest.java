// @find: tests for Bedrock credentials, AWS access key, Bedrock API key, region, plain error sentences
// @what: Checks Bedrock credential parsing and the messages for malformed entries.
package os.aiworkforce.llm.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** What the console stores for Bedrock, and the plain sentences a malformed entry gets back. */
class BedrockCredentialsTest {

    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";

    @Test
    @DisplayName("reads an access key with a session token and region, and stores it back the same")
    void accessKey() {
        BedrockCredentials parsed = BedrockCredentials.parse("{\"type\":\"access_key\",\"accessKeyId\":\"ASIAIOSFODNN7EXAMPLE\","
                + "\"secretAccessKey\":\"" + SECRET + "\",\"sessionToken\":\"FwoGZXIvYXdzEXAMPLE\",\"region\":\"eu-west-1\"}");

        assertThat(parsed.usesApiKey()).isFalse();
        assertThat(parsed.region()).isEqualTo("eu-west-1");
        assertThat(BedrockCredentials.parse(parsed.toJson())).isEqualTo(parsed);
        assertThat(parsed.toString()).doesNotContain(SECRET).doesNotContain("FwoG");
    }

    @Test
    @DisplayName("reads a Bedrock API key, defaulting the region to us-east-1")
    void apiKey() {
        BedrockCredentials parsed = BedrockCredentials.parse("{\"type\":\"api_key\",\"apiKey\":\" ABSKQmVkcm9ja0FQSUtleS1leGFtcGxl \"}");

        assertThat(parsed.usesApiKey()).isTrue();
        assertThat(parsed.apiKey()).isEqualTo("ABSKQmVkcm9ja0FQSUtleS1leGFtcGxl");
        assertThat(parsed.regionOrDefault()).isEqualTo("us-east-1");
        assertThat(parsed.toJson()).contains("\"region\":\"us-east-1\"");
        assertThat(parsed.toString()).doesNotContain("ABSK");
    }

    @Test
    @DisplayName("still reads the older key:secret and bare-key shapes, which name no region")
    void legacy() {
        BedrockCredentials pair = BedrockCredentials.parse("AKIAIOSFODNN7EXAMPLE:" + SECRET);
        assertThat(pair.accessKeyId()).isEqualTo("AKIAIOSFODNN7EXAMPLE");
        assertThat(pair.secretAccessKey()).isEqualTo(SECRET);
        assertThat(pair.region()).isNull();

        assertThat(BedrockCredentials.parse("ABSKQmVkcm9ja0FQSUtleS1leGFtcGxl").usesApiKey()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "{\"type\":\"access_key\",\"secretAccessKey\":\"" + SECRET + "\"}|accessKeyId",
        "{\"type\":\"access_key\",\"accessKeyId\":\"akia lower\",\"secretAccessKey\":\"" + SECRET + "\"}|accessKeyId",
        "{\"type\":\"access_key\",\"accessKeyId\":\"AKIAIOSFODNN7EXAMPLE\"}|secretAccessKey",
        "{\"type\":\"access_key\",\"accessKeyId\":\"ASIAIOSFODNN7EXAMPLE\",\"secretAccessKey\":\"" + SECRET + "\"}|sessionToken",
        "{\"type\":\"api_key\",\"apiKey\":\"short\"}|apiKey",
        "{\"type\":\"api_key\",\"apiKey\":\"ABSKQmVkcm9ja0FQSUtleS1leGFtcGxl\",\"region\":\"Mars-1\"}|region",
        "{not json|value",
    })
    @DisplayName("says which field is wrong, in words, without quoting the value")
    void invalid(String raw, String field) {
        assertThatThrownBy(() -> BedrockCredentials.parse(raw))
                .isInstanceOfSatisfying(BedrockCredentials.Invalid.class, e -> {
                    assertThat(e.field()).isEqualTo(field);
                    assertThat(e.getMessage()).doesNotContain(SECRET).doesNotContain("ABSK");
                });
    }

    @Test
    @DisplayName("falls back to the process environment when nothing is stored")
    void environment() {
        assertThat(BedrockCredentials.fromEnvironment(Map.of())).isNull();
        BedrockCredentials bearer =
                BedrockCredentials.fromEnvironment(Map.of("AWS_BEARER_TOKEN_BEDROCK", "ABSKabc", "AWS_REGION", "us-west-2"));
        assertThat(bearer.usesApiKey()).isTrue();
        assertThat(bearer.region()).isEqualTo("us-west-2");
    }
}
