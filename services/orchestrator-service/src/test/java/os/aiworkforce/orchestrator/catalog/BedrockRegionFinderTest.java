// @find: tests for bedrock region finder, catalog, chooses aregion where amodel answers, prefers the chosen region when it works, sends the one token call to only afew regions, says so when no region accepts, a gov cloud credential is tried only in gov cloud, list denied but caller known still counts, BedrockRegionFinderTest, BedrockRegionFinder
// @what: Tests for BedrockRegionFinder in the orchestrator catalog package (6 test methods).
// @flow: Exercises BedrockRegionFinder
package os.aiworkforce.orchestrator.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import os.aiworkforce.llm.bedrock.BedrockCredentials;
import os.aiworkforce.orchestrator.catalog.BedrockRegionFinder.Finding;
import os.aiworkforce.orchestrator.catalog.BedrockRegionFinder.RegionResult;
import os.aiworkforce.orchestrator.catalog.BedrockRegionFinder.Status;

/** Finding the regions a Bedrock credential works in, with AWS replaced by canned answers. */
class BedrockRegionFinderTest {

    private static final String API_KEY = "ABSKdo-not-echo-this-key-0123456789";
    private static final String LIST =
            "{\"modelSummaries\":[{\"modelId\":\"amazon.nova-micro-v1:0\",\"inferenceTypesSupported\":[\"ON_DEMAND\"]},"
                    + "{\"modelId\":\"anthropic.claude-x\",\"inferenceTypesSupported\":[\"INFERENCE_PROFILE\"]}]}";
    private static final String REFUSED =
            "{\"__type\":\"UnrecognizedClientException\",\"message\":\"The security token included in the request is invalid.\"}";
    private static final String NO_ACCESS =
            "{\"__type\":\"AccessDeniedException\",\"message\":\"You don't have access to the model with the specified model ID.\"}";

    private final Map<String, ModelListHttp.Response> lists = new ConcurrentHashMap<>();
    private final Map<String, ModelListHttp.Response> calls = new ConcurrentHashMap<>();
    private final List<String> converseRegions = new CopyOnWriteArrayList<>();

    private final BedrockRegionFinder.Transport transport = (method, uri, headers, body, timeout) -> {
        String region = region(uri);
        if (method.equals("POST")) {
            converseRegions.add(region);
            assertThat(uri.getRawPath()).isEqualTo("/model/amazon.nova-micro-v1%3A0/converse");
            ModelListHttp.Response answer = calls.get(region);
            if (answer == null) {
                throw new IOException("timeout");
            }
            return answer;
        }
        ModelListHttp.Response answer = lists.get(region);
        if (answer == null) {
            throw new IOException("timeout");
        }
        return answer;
    };

    private final BedrockRegionFinder finder = new BedrockRegionFinder(
            transport,
            Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC),
            List.of("us-east-1", "us-west-2", "eu-west-1", "ap-southeast-2", "us-gov-west-1"));

    private static String region(URI uri) {
        // bedrock.<region>.amazonaws.com or bedrock-runtime.<region>.amazonaws.com
        return uri.getHost().split("\\.")[1];
    }

    private static BedrockCredentials key(String region) {
        return new BedrockCredentials(null, null, null, API_KEY, region);
    }

    // @find: test chooses aregion where amodel answers, bedrock region finder
    @Test
    void choosesARegionWhereAModelAnswers() throws Exception {
        lists.put("us-east-1", new ModelListHttp.Response(403, REFUSED));
        lists.put("eu-west-1", new ModelListHttp.Response(200, LIST));
        lists.put("ap-southeast-2", new ModelListHttp.Response(200, LIST));
        calls.put("eu-west-1", new ModelListHttp.Response(403, NO_ACCESS));
        calls.put("ap-southeast-2", new ModelListHttp.Response(200, "{\"output\":{}}"));

        Finding finding = finder.find(key("us-east-1"));

        assertThat(finding.best()).isEqualTo("ap-southeast-2");
        assertThat(finding.regions()).extracting(RegionResult::region).first().isEqualTo("ap-southeast-2");
        assertThat(status(finding, "eu-west-1")).isEqualTo(Status.no_model_access);
        assertThat(status(finding, "us-east-1")).isEqualTo(Status.refused);
        assertThat(status(finding, "us-west-2")).isEqualTo(Status.unreachable);
        assertThat(finding.regions()).noneMatch(result -> result.region().equals("us-gov-west-1"));
        assertThat(finding.message()).contains("ap-southeast-2");
        // Nothing the person pasted, and nothing AWS said, comes back.
        String wire = new ObjectMapper().writeValueAsString(finding);
        assertThat(wire).doesNotContain(API_KEY).doesNotContain("security token").doesNotContain("don't have access");
    }

    // @find: test prefers the chosen region when it works, bedrock region finder
    @Test
    void prefersTheChosenRegionWhenItWorks() {
        lists.put("us-east-1", new ModelListHttp.Response(200, LIST));
        lists.put("eu-west-1", new ModelListHttp.Response(200, LIST));
        calls.put("us-east-1", new ModelListHttp.Response(200, "{}"));
        calls.put("eu-west-1", new ModelListHttp.Response(200, "{}"));

        assertThat(finder.find(key("eu-west-1")).best()).isEqualTo("eu-west-1");
    }

    // @find: test sends the one token call to only afew regions, bedrock region finder
    @Test
    void sendsTheOneTokenCallToOnlyAFewRegions() {
        BedrockRegionFinder wide = new BedrockRegionFinder(
                transport,
                Clock.systemUTC(),
                List.of("us-east-1", "us-east-2", "us-west-2", "eu-west-1", "eu-west-2", "ap-south-1"));
        for (String region : List.of("us-east-1", "us-east-2", "us-west-2", "eu-west-1", "eu-west-2", "ap-south-1")) {
            lists.put(region, new ModelListHttp.Response(200, LIST));
        }
        Finding finding = wide.find(key(null));

        assertThat(converseRegions).hasSize(BedrockRegionFinder.CALL_LIMIT);
        assertThat(finding.best()).isEqualTo("us-east-1");
        assertThat(finding.regions()).filteredOn(result -> result.status() == Status.accepted).hasSize(6);
    }

    // @find: test says so when no region accepts, bedrock region finder
    @Test
    void saysSoWhenNoRegionAccepts() {
        lists.put("us-east-1", new ModelListHttp.Response(403, REFUSED));
        lists.put("us-west-2", new ModelListHttp.Response(403, REFUSED));

        Finding finding = finder.find(key("us-east-1"));

        assertThat(finding.best()).isNull();
        assertThat(finding.message()).startsWith("No region accepted");
    }

    // @find: test a gov cloud credential is tried only in gov cloud, bedrock region finder
    @Test
    void aGovCloudCredentialIsTriedOnlyInGovCloud() {
        lists.put("us-gov-west-1", new ModelListHttp.Response(200, LIST));
        calls.put("us-gov-west-1", new ModelListHttp.Response(200, "{}"));

        Finding finding = finder.find(key("us-gov-west-1"));

        assertThat(finding.best()).isEqualTo("us-gov-west-1");
        assertThat(finding.regions()).hasSize(1);
    }

    // @find: test list denied but caller known still counts, bedrock region finder
    @Test
    void listDeniedButCallerKnownStillCounts() {
        lists.put("us-east-1", new ModelListHttp.Response(
                403, "{\"__type\":\"AccessDeniedException\",\"message\":\"User is not authorized to perform: bedrock:ListFoundationModels\"}"));
        calls.put("us-east-1", new ModelListHttp.Response(200, "{}"));

        Finding finding = finder.find(key("us-east-1"));

        assertThat(finding.best()).isEqualTo("us-east-1");
        assertThat(status(finding, "us-east-1")).isEqualTo(Status.ready);
    }

    private static Status status(Finding finding, String region) {
        return finding.regions().stream()
                .filter(result -> result.region().equals(region))
                .findFirst()
                .orElseThrow()
                .status();
    }
}
