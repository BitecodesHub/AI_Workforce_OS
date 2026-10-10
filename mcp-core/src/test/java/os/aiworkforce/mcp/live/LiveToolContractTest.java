// @find: tests for live tool contract, every live tool, request and result, refusal kinds, provider timeout, connection check, sandbox fallback when not connected, all vendors
// @what: Runs every live tool through the contract table against a stand-in provider, including failures and the connection check.
// @flow: Uses ContractTable
package os.aiworkforce.mcp.live;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static os.aiworkforce.mcp.live.ContractTable.TOKEN;
import static os.aiworkforce.mcp.live.ContractTable.ZOOM_ACCESS;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.MatchResult;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import os.aiworkforce.mcp.live.ContractTable.Call;
import os.aiworkforce.mcp.live.ContractTable.Case;
import os.aiworkforce.mcp.live.ContractTable.Vendor;
import os.aiworkforce.mcp.model.ConnectionCheck;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;

/**
 * Every tool of every live adapter against a stand-in provider: the exact request, the result of
 * a good answer, each kind of refusal, a provider that stops answering, the connection check, and
 * the sandbox answering when nothing is connected.
 */
class LiveToolContractTest {

    private static final int[] REFUSALS = {401, 403, 404, 422, 429, 500, 503};
    private static final Duration CLIENT_TIMEOUT = Duration.ofMillis(250);
    private static final int SLOW_ANSWER_MS = 1200;

    private static WireMockServer provider;
    /* Answers late; kept apart so its delayed requests never land in another test's log. */
    private static WireMockServer slow;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void start() {
        provider = new WireMockServer(WireMockConfiguration.options().dynamicPort().containerThreads(60));
        provider.start();
        slow = new WireMockServer(WireMockConfiguration.options().dynamicPort().containerThreads(60));
        slow.start();
        slow.stubFor(any(anyUrl()).atPriority(10).willReturn(okJson("{}").withFixedDelay(SLOW_ANSWER_MS)));
        slow.stubFor(post(urlPathEqualTo("/oauth/token"))
                .atPriority(1)
                .willReturn(okJson("{\"access_token\":\"" + ZOOM_ACCESS + "\",\"expires_in\":3600}")));
    }

    @AfterAll
    static void stop() {
        provider.stop();
        slow.stop();
    }

    @BeforeEach
    void reset() {
        provider.resetAll();
    }

    static Stream<Case> cases() {
        return ContractTable.cases();
    }

    static Stream<Case> online() {
        return ContractTable.cases().filter(c -> !c.offline());
    }

    static Stream<Arguments> refusals() {
        return online().flatMap(c -> java.util.Arrays.stream(REFUSALS).mapToObj(status -> Arguments.of(c, status)));
    }

    static Stream<Vendor> vendors() {
        return ContractTable.VENDORS.values().stream();
    }

    static Stream<Vendor> calling() {
        return vendors().filter(vendor -> !vendor.whoAmI().isEmpty());
    }

    // ---- Coverage -----------------------------------------------------------------------------

    @Test
    @DisplayName("the table has a row for every tool of every live adapter, and nothing else")
    void everyToolHasARow() {
        List<LiveServerAdapter> live = SandboxServerRegistry.servers(json, WebClient.builder(), true).stream()
                .filter(LiveServerAdapter.class::isInstance)
                .map(LiveServerAdapter.class::cast)
                .toList();

        assertThat(live).extracting(LiveServerAdapter::server)
                .containsExactlyInAnyOrderElementsOf(ContractTable.VENDORS.keySet());
        for (LiveServerAdapter adapter : live) {
            Set<String> rows = ContractTable.cases()
                    .filter(c -> c.server.equals(adapter.server()))
                    .map(c -> c.tool)
                    .collect(Collectors.toSet());
            Set<String> declared =
                    adapter.tools().stream().map(ToolDefinition::name).collect(Collectors.toSet());
            assertThat(rows).as(adapter.server()).isEqualTo(declared).isEqualTo(adapter.liveTools());
        }
    }

    // ---- A good answer ------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("sends the documented request and turns the answer into a plain result")
    void succeeds(Case row) throws Exception {
        Vendor vendor = ContractTable.vendor(row.server);
        row.calls.forEach(this::stub);
        stubZoomToken(vendor);

        ToolResult result = adapter(vendor, WebClient.builder()).invoke(row.invocation(), credential(vendor)).block();

        assertThat(result).isNotNull();
        assertThat(result.status()).as(result.summary()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(result.summary()).contains(row.summary);
        plain(result.summary());
        JsonNode content = json.readTree(result.contentJson());
        row.expect.forEach((pointer, value) ->
                assertThat(content.at(pointer).asText()).as(pointer + " in " + content).isEqualTo(value));
        assertThat(result.contentJson()).doesNotContain(TOKEN).doesNotContain("start_url").doesNotContain("host-only");
        assertThat(provider.findAllUnmatchedRequests()).as("requests no row expected").isEmpty();
        for (Call call : row.calls) {
            verify(vendor, call);
        }
    }

    // ---- Refusals -----------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} answered {1}")
    @MethodSource("refusals")
    @DisplayName("a refusal is one plain sentence that never repeats the token")
    void refusalIsPlain(Case row, int status) {
        Vendor vendor = ContractTable.vendor(row.server);
        stubZoomToken(vendor);
        provider.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\":\"Request with " + TOKEN + (vendor.zoom() ? " / " + ZOOM_ACCESS : "")
                        + " was refused\","
                        + "\"errors\":[{\"message\":\"nested\"}]}")));

        ToolResult result = adapter(vendor, WebClient.builder()).invoke(row.invocation(), credential(vendor)).block();

        assertThat(result).isNotNull();
        assertThat(result.status()).as(result.summary()).isEqualTo(ToolResult.Status.FAILED);
        plain(result.summary());
        String summary = result.summary();
        if (row.server.equals("webhook")) {
            assertThat(summary).contains("status " + status).contains("not accepted");
            return;
        }
        switch (status) {
            case 401 -> assertThat(summary).contains("connect").contains("again");
            case 403 -> assertThat(summary).contains("refused this action");
            case 404 -> assertThat(summary).contains("could not find");
            case 422 -> assertThat(summary).contains("did not accept the request");
            case 429 -> assertThat(summary).contains("Try again in a minute");
            default -> assertThat(summary).contains("status " + status).contains("Try again shortly");
        }
    }

    // ---- A provider that stops answering ------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("online")
    @DisplayName("no answer in time: a read fails plainly, anything else is indeterminate and not repeated")
    void slowProvider(Case row) {
        Vendor vendor = ContractTable.vendor(row.server);
        WebClient.Builder impatient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(CLIENT_TIMEOUT)));

        ToolResult result = vendor.adapter(impatient, slow.baseUrl())
                .invoke(row.invocation(), vendor.credential(slow.port()))
                .block();

        assertThat(result).isNotNull();
        plain(result.summary());
        if (row.idempotent()) {
            assertThat(result.status()).as(result.summary()).isEqualTo(ToolResult.Status.FAILED);
            assertThat(result.summary()).contains("did not answer in time");
        } else {
            assertThat(result.status()).as(result.summary()).isEqualTo(ToolResult.Status.INDETERMINATE);
            assertThat(result.summary()).contains("may or may not have happened").contains("not repeated");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("online")
    @DisplayName("the gateway's own timeout reaches the gateway, so it can classify it by the tool")
    void gatewayTimeoutIsNotSwallowed(Case row) {
        Vendor vendor = ContractTable.vendor(row.server);

        Throwable error = vendor.adapter(WebClient.builder(), slow.baseUrl())
                .invoke(row.invocation(), vendor.credential(slow.port()))
                .timeout(Duration.ofMillis(200))
                .map(result -> (Throwable) null)
                .onErrorResume(Mono::just)
                .block();

        assertThat(error).isInstanceOf(TimeoutException.class);
    }

    // ---- The connection check -----------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("vendors")
    @DisplayName("the check names the account with the right credential")
    void checkNamesTheAccount(Vendor vendor) {
        vendor.whoAmI().forEach(this::stub);
        stubZoomToken(vendor);
        LiveServerAdapter adapter = adapter(vendor, WebClient.builder());

        ConnectionCheck check = adapter.check(credential(vendor)).block();

        assertThat(check.ok()).as(check.message()).isTrue();
        assertThat(check.accountLabel()).isEqualTo(vendor.label());
        assertThat(adapter.healthCheck(credential(vendor)).block()).isTrue();
        assertThat(provider.findAllUnmatchedRequests()).isEmpty();
        for (Call call : vendor.whoAmI()) {
            verify(vendor, call);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("calling")
    @DisplayName("a rejected credential fails the check plainly, without the token")
    void checkRejected(Vendor vendor) {
        stubZoomToken(vendor);
        provider.stubFor(any(anyUrl()).atPriority(10).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\":\"bad token " + TOKEN + "\"}")));
        LiveServerAdapter adapter = adapter(vendor, WebClient.builder());

        ConnectionCheck check = adapter.check(credential(vendor)).block();

        assertThat(check.ok()).isFalse();
        plain(check.message());
        assertThat(check.message()).contains("did not accept this token");
        assertThat(adapter.healthCheck(credential(vendor)).block()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vendors")
    @DisplayName("with no credential every tool answers from the sandbox and the provider hears nothing")
    void sandboxWithoutCredential(Vendor vendor) {
        LiveServerAdapter adapter = adapter(vendor, WebClient.builder());

        for (Case row : ContractTable.cases().filter(c -> c.server.equals(vendor.server())).toList()) {
            for (String none : new String[] {null, "", "   "}) {
                ToolResult result = adapter.invoke(row.invocation(), none).block();
                assertThat(result).as(row.toString()).isNotNull();
                assertThat(result.status()).as(row + ": " + result.summary()).isNotEqualTo(ToolResult.Status.INDETERMINATE);
            }
        }
        assertThat(adapter.check(null).block().ok()).isFalse();
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private LiveServerAdapter adapter(Vendor vendor, WebClient.Builder http) {
        return vendor.adapter(http, provider.baseUrl());
    }

    private String credential(Vendor vendor) {
        return vendor.credential(provider.port());
    }

    private void stub(Call call) {
        ResponseDefinitionBuilder answer = aResponse().withStatus(call.status);
        if (call.answer != null) {
            answer = answer.withHeader("Content-Type", call.contentType).withBody(call.answer);
        }
        provider.stubFor(request(call.method, anyUrl())
                .andMatching(request -> MatchResult.of(call.matches(request)))
                .atPriority(1)
                .willReturn(answer));
    }

    private void stubZoomToken(Vendor vendor) {
        if (vendor.zoom()) {
            provider.stubFor(post(urlPathEqualTo("/oauth/token"))
                    .atPriority(1)
                    .willReturn(okJson("{\"access_token\":\"" + ZOOM_ACCESS + "\",\"expires_in\":3600}")));
        }
    }

    private void verify(Vendor vendor, Call call) {
        List<LoggedRequest> hits = provider.findAll(anyRequestedFor(anyUrl())).stream()
                .filter(call::matches)
                .toList();
        assertThat(hits).as("a request " + call).isNotEmpty();
        for (LoggedRequest hit : hits) {
            if (vendor.authorization() == null) {
                assertThat(hit.containsHeader("Authorization")).as(call + " carries no credential").isFalse();
            } else {
                assertThat(hit.getHeader("Authorization")).as(call + " authorization").isEqualTo(vendor.authorization());
            }
            call.headers.forEach((name, value) -> assertThat(hit.getHeader(name)).as(call + " " + name).isEqualTo(value));
            String body = hit.getBodyAsString();
            if (call.json != null) {
                assertThat(equalToJson(call.json, true, true).match(body).isExactMatch())
                        .as(call + " body " + body)
                        .isTrue();
            }
            call.contains.forEach(text -> assertThat(body).as(call + " body").contains(text));
            call.jsonPaths.forEach(path -> assertThat(matchingJsonPath(path).match(body).isExactMatch())
                    .as(call + " body has " + path)
                    .isTrue());
        }
    }

    /** One plain sentence or two: no token, no exception, no stack, no JSON. */
    static void plain(String message) {
        assertThat(message)
                .isNotBlank()
                .doesNotContain(TOKEN)
                .doesNotContain(ZOOM_ACCESS)
                .doesNotContain("Exception")
                .doesNotContain("\tat ")
                .doesNotContain("os.aiworkforce")
                .doesNotContain("{")
                .doesNotContain("}")
                .doesNotContain("null");
        assertThat(message.length()).isLessThan(400);
    }
}
