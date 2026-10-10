// @find: tests for AWS SigV4 signing, Bedrock request signing, AWS test suite
// @what: Checks SigV4 against AWS's published test suite and the AWS SDK signer.
package os.aiworkforce.llm.bedrock;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;

/**
 * SigV4 against the published AWS Signature Version 4 test suite (credentials AKIDEXAMPLE, 30
 * August 2015 12:36:00 UTC, us-east-1, service "service"), against the worked IAM example in AWS's
 * signing documentation, and against the AWS SDK's own signer for Bedrock-shaped requests.
 */
class AwsSigV4Test {

    private static final String ACCESS_KEY = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final Instant SUITE_TIME = Instant.parse("2015-08-30T12:36:00Z");

    @Test
    @DisplayName("get-vanilla from the AWS test suite")
    void getVanilla() {
        Map<String, String> headers = AwsSigV4.sign(
                "GET", URI.create("https://example.amazonaws.com/"), Map.of(), new byte[0],
                ACCESS_KEY, SECRET, null, "us-east-1", "service", SUITE_TIME, true);

        assertThat(headers.get("X-Amz-Date")).isEqualTo("20150830T123600Z");
        assertThat(headers.get("Authorization"))
                .isEqualTo("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request,"
                        + " SignedHeaders=host;x-amz-date,"
                        + " Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
    }

    @Test
    @DisplayName("post-vanilla from the AWS test suite")
    void postVanilla() {
        Map<String, String> headers = AwsSigV4.sign(
                "POST", URI.create("https://example.amazonaws.com/"), Map.of(), new byte[0],
                ACCESS_KEY, SECRET, null, "us-east-1", "service", SUITE_TIME, true);

        assertThat(headers.get("Authorization"))
                .endsWith("Signature=5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b");
    }

    @Test
    @DisplayName("the derived signing key and signature of the IAM ListUsers example in AWS's documentation")
    void documentedIamExample() {
        byte[] key = AwsSigV4.signingKey(SECRET, "20150830", "us-east-1", "iam");
        assertThat(AwsSigV4.hex(key)).isEqualTo("c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9");

        Map<String, String> headers = AwsSigV4.sign(
                "GET",
                URI.create("https://iam.amazonaws.com/?Action=ListUsers&Version=2010-05-08"),
                Map.of("Content-Type", "application/x-www-form-urlencoded; charset=utf-8"),
                new byte[0],
                ACCESS_KEY, SECRET, null, "us-east-1", "iam", SUITE_TIME, true);

        assertThat(headers.get("Authorization"))
                .isEqualTo("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request,"
                        + " SignedHeaders=content-type;host;x-amz-date,"
                        + " Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7");
    }

    @Test
    @DisplayName("query parameters are sorted and strictly encoded in the canonical form")
    void canonicalQuery() {
        assertThat(AwsSigV4.canonicalQuery(URI.create("https://h/?b=2&a=1&a=0&c=x%20y&d=a+b")))
                .isEqualTo("a=0&a=1&b=2&c=x%20y&d=a%2Bb");
        assertThat(AwsSigV4.canonicalPath(URI.create("https://h/model/anthropic.claude-v2%3A1/converse"), true))
                .isEqualTo("/model/anthropic.claude-v2%253A1/converse");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "POST, https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-3-haiku-20240307-v1%3A0/converse, us-east-1, '{\"messages\":[]}', ''",
        "POST, https://bedrock-runtime.eu-west-1.amazonaws.com/model/eu.amazon.nova-lite-v1%3A0/converse, eu-west-1, '{\"a\":1}', FwoGZXIvYXdzEXAMPLETOKEN",
        "GET, https://bedrock.ap-southeast-2.amazonaws.com/inference-profiles?maxResults=1000&nextToken=abc%2Bdef%3D, ap-southeast-2, '', ''",
        "GET, https://bedrock.us-west-2.amazonaws.com/foundation-models?byOutputModality=TEXT, us-west-2, '', ''",
        "POST, http://localhost:8089/model/arn%3Aaws%3Abedrock%3Aus-east-1%3A123%3Ainference-profile%2Fus.x/invoke, us-east-1, '{}', ''",
    })
    @DisplayName("matches the AWS SDK's signer for Bedrock requests")
    void matchesTheSdk(String method, String url, String region, String body, String token) {
        URI uri = URI.create(url);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.parse("2026-10-08T03:04:05Z");
        String sessionToken = token == null || token.isBlank() ? null : token;

        SdkHttpRequest.Builder request = SdkHttpRequest.builder().uri(uri).method(SdkHttpMethod.fromValue(method));
        if (!body.isEmpty()) {
            request.putHeader("Content-Type", "application/json");
        }
        AwsCredentialsIdentity identity = sessionToken == null
                ? AwsCredentialsIdentity.create(ACCESS_KEY, SECRET)
                : AwsSessionCredentialsIdentity.create(ACCESS_KEY, SECRET, sessionToken);
        SignedRequest signed = AwsV4HttpSigner.create().sign(r -> r.identity(identity)
                .request(request.build())
                .payload(ContentStreamProvider.fromByteArray(bytes))
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "bedrock")
                .putProperty(HttpSigner.SIGNING_CLOCK, Clock.fixed(now, ZoneOffset.UTC)));
        String expected = signed.request().firstMatchingHeader("Authorization").orElseThrow();

        // Whatever else the SDK chose to sign (such as x-amz-content-sha256) is signed here too.
        Set<String> added = Set.of("authorization", "host", "x-amz-date", "x-amz-security-token");
        Map<String, String> headers = new LinkedHashMap<>();
        signed.request().forEachHeader((name, values) -> {
            if (!added.contains(name.toLowerCase())) {
                headers.put(name, String.join(",", values));
            }
        });
        Map<String, String> ours = AwsSigV4.sign(
                method, uri, headers, bytes, ACCESS_KEY, SECRET, sessionToken, region, "bedrock", now, true);

        assertThat(ours.get("Authorization")).isEqualTo(expected);
        if (sessionToken != null) {
            assertThat(ours.get("X-Amz-Security-Token")).isEqualTo(sessionToken);
        }
    }

    @Test
    @DisplayName("an API key is a bearer token, and a key pair signs; neither puts the secret in a header")
    void authHeaders() {
        URI uri = URI.create("https://bedrock-runtime.us-east-1.amazonaws.com/model/x/converse");
        Map<String, String> bearer = new BedrockCredentials(null, null, null, "ABSKexampleexampleexample", "us-east-1")
                .authHeaders("POST", uri, new byte[0], "application/json", SUITE_TIME);
        assertThat(bearer).containsEntry("Authorization", "Bearer ABSKexampleexampleexample");

        Map<String, String> sigv4 = new BedrockCredentials("AKIAIOSFODNN7EXAMPLE", SECRET, null, null, "us-east-1")
                .authHeaders("POST", uri, new byte[0], "application/json", SUITE_TIME);
        assertThat(sigv4.get("Authorization"))
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20150830/us-east-1/bedrock/aws4_request")
                .contains("SignedHeaders=content-type;host;x-amz-date");
        assertThat(sigv4.values()).noneMatch(value -> value.contains(SECRET));
    }
}
