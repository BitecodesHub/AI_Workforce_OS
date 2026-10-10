// @find: tests for AWS default credential chain, IMDSv2, instance role, container credentials, refresh before expiry
// @what: Checks the default chain against a stub metadata service: IMDSv2 token first, role lookup, caching, refresh and fallbacks.
package os.aiworkforce.llm.bedrock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AwsDefaultCredentialsTest {

    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    private WireMockServer imds;
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @BeforeEach
    void setUp() {
        imds = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        imds.start();
    }

    @AfterEach
    void tearDown() {
        imds.stop();
    }

    private Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("AWS_EC2_METADATA_SERVICE_ENDPOINT", imds.baseUrl());
        env.put("AIWOS_BEDROCK_REGION", "ap-southeast-2");
        return env;
    }

    private void stubRole(String accessKeyId, Instant expiry) {
        imds.stubFor(put(urlEqualTo("/latest/api/token"))
                .withHeader("X-aws-ec2-metadata-token-ttl-seconds", equalTo("21600"))
                .willReturn(aResponse().withStatus(200).withBody("imds-session-token")));
        imds.stubFor(get(urlEqualTo("/latest/meta-data/iam/security-credentials/"))
                .withHeader("X-aws-ec2-metadata-token", equalTo("imds-session-token"))
                .willReturn(aResponse().withStatus(200).withBody("aiwos-instance-role\n")));
        imds.stubFor(get(urlEqualTo("/latest/meta-data/iam/security-credentials/aiwos-instance-role"))
                .withHeader("X-aws-ec2-metadata-token", equalTo("imds-session-token"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"Code":"Success","Type":"AWS-HMAC","AccessKeyId":"%s",
                         "SecretAccessKey":"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                         "Token":"session-token-value","Expiration":"%s"}
                        """.formatted(accessKeyId, expiry))));
    }

    @Test
    @DisplayName("reads the instance role over IMDSv2, with the session token, in the configured region")
    void readsInstanceRole() {
        stubRole("ASIAEXAMPLEROLEKEY01", NOW.plusSeconds(3600));
        AwsDefaultCredentials chain = AwsDefaultCredentials.fromEnvironment(env(), clock);

        BedrockCredentials credentials = chain.resolve();

        assertThat(credentials.accessKeyId()).isEqualTo("ASIAEXAMPLEROLEKEY01");
        assertThat(credentials.sessionToken()).isEqualTo("session-token-value");
        assertThat(credentials.region()).isEqualTo("ap-southeast-2");
        assertThat(credentials.usesApiKey()).isFalse();
        // Never a secret in the text form that reaches logs.
        assertThat(credentials.toString()).doesNotContain("wJalr").doesNotContain("session-token-value");
        imds.verify(1, putRequestedFor(urlEqualTo("/latest/api/token")));
    }

    @Test
    @DisplayName("caches the credentials and fetches new ones five minutes before they expire")
    void refreshesBeforeExpiry() {
        stubRole("ASIAEXAMPLEROLEKEY01", NOW.plusSeconds(3600));
        AwsDefaultCredentials chain = AwsDefaultCredentials.fromEnvironment(env(), clock);
        chain.resolve();

        now.set(NOW.plusSeconds(1800));
        assertThat(chain.resolve().accessKeyId()).isEqualTo("ASIAEXAMPLEROLEKEY01");
        imds.verify(1, getRequestedFor(urlEqualTo("/latest/meta-data/iam/security-credentials/aiwos-instance-role")));

        // Inside the five-minute window: replaced, before AWS would refuse them.
        stubRole("ASIAEXAMPLEROLEKEY02", NOW.plusSeconds(7200));
        now.set(NOW.plusSeconds(3600 - 240));
        assertThat(chain.resolve().accessKeyId()).isEqualTo("ASIAEXAMPLEROLEKEY02");
        assertThat(chain.expiry()).isEqualTo(NOW.plusSeconds(7200));
    }

    @Test
    @DisplayName("keeps using still-valid credentials when a refresh fails, and fails once they have expired")
    void fallsBackToCachedWhileValid() {
        stubRole("ASIAEXAMPLEROLEKEY01", NOW.plusSeconds(600));
        AwsDefaultCredentials chain = AwsDefaultCredentials.fromEnvironment(env(), clock);
        chain.resolve();

        imds.resetAll();
        imds.stubFor(put(urlEqualTo("/latest/api/token")).willReturn(aResponse().withStatus(503)));
        now.set(NOW.plusSeconds(400));
        assertThat(chain.resolve().accessKeyId()).isEqualTo("ASIAEXAMPLEROLEKEY01");

        now.set(NOW.plusSeconds(700));
        assertThatThrownBy(chain::resolve)
                .isInstanceOf(AwsDefaultCredentials.Unavailable.class)
                .satisfies(e -> assertThat(((AwsDefaultCredentials.Unavailable) e).isTransient()).isTrue());
    }

    @Test
    @DisplayName("says plainly when the server has no role attached")
    void noRoleAttached() {
        imds.stubFor(put(urlEqualTo("/latest/api/token")).willReturn(aResponse().withStatus(200).withBody("t")));
        imds.stubFor(get(urlEqualTo("/latest/meta-data/iam/security-credentials/"))
                .willReturn(aResponse().withStatus(404)));
        AwsDefaultCredentials chain = AwsDefaultCredentials.fromEnvironment(env(), clock);

        assertThatThrownBy(chain::resolve)
                .isInstanceOf(AwsDefaultCredentials.Unavailable.class)
                .hasMessageContaining("no IAM role")
                .satisfies(e -> assertThat(((AwsDefaultCredentials.Unavailable) e).isTransient()).isFalse());
    }

    @Test
    @DisplayName("reports an unreachable metadata service as transient")
    void unreachable() {
        Map<String, String> env = env();
        env.put("AWS_EC2_METADATA_SERVICE_ENDPOINT", "http://127.0.0.1:9");
        AwsDefaultCredentials chain = AwsDefaultCredentials.fromEnvironment(env, clock);

        assertThatThrownBy(chain::resolve)
                .isInstanceOf(AwsDefaultCredentials.Unavailable.class)
                .satisfies(e -> assertThat(((AwsDefaultCredentials.Unavailable) e).isTransient()).isTrue());
    }

    @Test
    @DisplayName("prefers keys in the environment, then the container endpoint, over the instance role")
    void chainOrder() {
        Map<String, String> env = env();
        env.put("AWS_ACCESS_KEY_ID", "AKIAENVIRONMENTKEY01");
        env.put("AWS_SECRET_ACCESS_KEY", "environment-secret-value-123");
        assertThat(AwsDefaultCredentials.fromEnvironment(env, clock).resolve().accessKeyId())
                .isEqualTo("AKIAENVIRONMENTKEY01");
        imds.verify(0, putRequestedFor(urlEqualTo("/latest/api/token")));

        Map<String, String> container = env();
        container.put("AWS_CONTAINER_CREDENTIALS_FULL_URI", imds.baseUrl() + "/v2/credentials/task");
        container.put("AWS_CONTAINER_AUTHORIZATION_TOKEN", "container-auth");
        imds.stubFor(get(urlEqualTo("/v2/credentials/task"))
                .withHeader("Authorization", equalTo("container-auth"))
                .willReturn(aResponse().withStatus(200).withBody("""
                        {"AccessKeyId":"ASIACONTAINERKEY001","SecretAccessKey":"container-secret-value-1",
                         "Token":"container-token","Expiration":"2026-10-11T06:00:00Z"}
                        """)));
        assertThat(AwsDefaultCredentials.fromEnvironment(container, clock).resolve().accessKeyId())
                .isEqualTo("ASIACONTAINERKEY001");
    }

    @Test
    @DisplayName("is on only when the switch says so, and takes its region from AIWOS_BEDROCK_REGION first")
    void switchAndRegion() {
        assertThat(AwsDefaultCredentials.enabled(Map.of())).isFalse();
        assertThat(AwsDefaultCredentials.enabled(Map.of("AIWOS_BEDROCK_USE_DEFAULT_CREDENTIALS", "true"))).isTrue();
        assertThat(AwsDefaultCredentials.enabled(Map.of("AIWOS_BEDROCK_USE_DEFAULT_CREDENTIALS", "false"))).isFalse();
        assertThat(AwsDefaultCredentials.region(Map.of("AWS_REGION", "us-east-1", "AIWOS_BEDROCK_REGION", "ap-southeast-2")))
                .isEqualTo("ap-southeast-2");
        assertThat(AwsDefaultCredentials.region(Map.of("AWS_DEFAULT_REGION", "eu-west-1"))).isEqualTo("eu-west-1");
        assertThat(AwsDefaultCredentials.region(Map.of())).isNull();
    }
}
