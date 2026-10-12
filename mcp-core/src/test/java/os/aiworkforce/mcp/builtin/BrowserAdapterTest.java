// @find: tests for web browser connector, browser adapter, open page, follow link, fill field, click button, submit form, screenshot metadata, browser runner client, SSRF refusals, private address, metadata address, localhost refused, takeover click, takeover navigate guard, hold page
// @what: Checks the browser adapter calls the runner per run, gives the model text only with the screenshot in metadata, restores a page for a click, and refuses private addresses before the runner is called.
package os.aiworkforce.mcp.builtin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;

class BrowserAdapterTest {

    private static final String RUN = "0199aaaa-bbbb-7ccc-8ddd-eeeeffff0000";
    private static final String PAGE = "{\"url\":\"https://example.com/\",\"title\":\"Example Domain\","
            + "\"text\":\"This domain is for use in examples.\",\"links\":[{\"text\":\"More information\","
            + "\"href\":\"https://www.iana.org/domains/example\"}],\"fields\":[],\"screenshot\":\"/9j/AAAA\"}";

    private final ObjectMapper json = new ObjectMapper();
    private final List<ToolDefinition> tools = SandboxServerRegistry.definitions().get("browser");
    private WireMockServer runner;
    private BrowserAdapter adapter;

    /* example.com resolves to a public address without a network; everything else to what it says. */
    private static final BrowserAddresses.Resolver RESOLVER = host -> switch (host) {
        case "example.com", "www.iana.org" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 215, 14})};
        case "inside.example.com" -> new InetAddress[] {InetAddress.getByAddress(host, new byte[] {10, 0, 0, 5})};
        case "nowhere.example.com" -> throw new UnknownHostException(host);
        default -> InetAddress.getAllByName(host);
    };

    @BeforeEach
    void start() {
        runner = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        runner.start();
        adapter = new BrowserAdapter(tools, json, WebClient.builder(), runner.baseUrl(), "s3cret", RESOLVER);
    }

    @AfterEach
    void stop() {
        runner.stop();
    }

    @Test
    @DisplayName("reading is free, typing is a write, and clicking or submitting always asks a person")
    void sideEffects() {
        Map<String, ToolSpec.SideEffect> effects = new java.util.HashMap<>();
        tools.forEach(tool -> effects.put(tool.name(), tool.sideEffect()));
        assertThat(effects).containsExactlyInAnyOrderEntriesOf(Map.of(
                "open_page", ToolSpec.SideEffect.READ,
                "read_page", ToolSpec.SideEffect.READ,
                "follow_link", ToolSpec.SideEffect.READ,
                "search_site", ToolSpec.SideEffect.READ,
                "fill_field", ToolSpec.SideEffect.WRITE,
                "click_button", ToolSpec.SideEffect.OUTBOUND,
                "submit_form", ToolSpec.SideEffect.OUTBOUND));
        assertThat(tools).filteredOn(ToolDefinition::alwaysRequiresApproval)
                .extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("click_button", "submit_form");
        assertThat(tools).allSatisfy(tool -> assertThat(tool.defaultTimeout()).isGreaterThan(Duration.ofSeconds(70)));
        assertThat(adapter.isBuiltIn()).isTrue();
        assertThat(adapter.isSandbox()).isFalse();
    }

    @Test
    @DisplayName("open_page calls the runner for this run with the secret, and the model gets text without the image")
    void openPage() throws Exception {
        runner.stubFor(post(urlEqualTo("/sessions/" + RUN + "/open")).willReturn(okJson(PAGE)));

        ToolResult result = call("open_page", "{\"url\":\"https://example.com\"}");

        assertThat(result.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(result.summary()).isEqualTo("Opened Example Domain (https://example.com/).");
        JsonNode content = json.readTree(result.contentJson());
        assertThat(content.path("text").asText()).contains("for use in examples");
        assertThat(content.path("links").get(0).path("text").asText()).isEqualTo("More information");
        assertThat(result.contentJson()).doesNotContain("/9j/AAAA").doesNotContain("screenshot");
        assertThat(result.metadata())
                .containsEntry(BrowserAdapter.SCREENSHOT, "/9j/AAAA")
                .containsEntry(BrowserAdapter.PAGE_URL, "https://example.com/")
                .containsEntry(BrowserAdapter.PAGE_TITLE, "Example Domain");
        runner.verify(postRequestedFor(urlEqualTo("/sessions/" + RUN + "/open"))
                .withHeader(BrowserAdapter.SECRET_HEADER, equalTo("s3cret"))
                .withRequestBody(matchingJsonPath("$.url", equalTo("https://example.com"))));
    }

    @Test
    @DisplayName("a click carries the open page and what was typed, so the runner can put it back after a long approval")
    void clickRestoresThePage() {
        runner.stubFor(post(urlPathMatching("/sessions/.*/(open|fill|click)")).willReturn(okJson(PAGE)));

        call("open_page", "{\"url\":\"https://example.com\"}");
        call("fill_field", "{\"field\":\"Search\",\"value\":\"otters\"}");
        ToolResult clicked = call("click_button", "{\"text\":\"Go\"}");

        assertThat(clicked.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
        runner.verify(postRequestedFor(urlEqualTo("/sessions/" + RUN + "/click"))
                .withRequestBody(matchingJsonPath("$.text", equalTo("Go")))
                .withRequestBody(matchingJsonPath("$.restore.url", equalTo("https://example.com/")))
                .withRequestBody(matchingJsonPath("$.restore.fills[0].field", equalTo("Search")))
                .withRequestBody(matchingJsonPath("$.restore.fills[0].value", equalTo("otters"))));
    }

    @Test
    @DisplayName("the runner's plain refusal is the result, and a wrong secret says the browser is off")
    void refusals() {
        runner.stubFor(post(urlEqualTo("/sessions/" + RUN + "/follow")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"No link with that text is on the page.\"}")));
        runner.stubFor(post(urlEqualTo("/sessions/" + RUN + "/read")).willReturn(aResponse().withStatus(401)));

        assertThat(call("follow_link", "{\"linkText\":\"Nope\"}").summary())
                .isEqualTo("No link with that text is on the page.");
        assertThat(call("read_page", "{}").summary()).isEqualTo(BrowserAdapter.UNAVAILABLE);
    }

    @Test
    @DisplayName("private, loopback, link-local and metadata addresses are refused before the runner is called")
    void ssrfRefusals() {
        for (String url : List.of(
                "http://localhost:8083/actuator", "http://127.0.0.1/", "http://[::1]/", "http://10.1.2.3/",
                "http://192.168.0.1/", "http://172.16.5.4/", "http://169.254.169.254/latest/meta-data/",
                "http://metadata.google.internal/", "http://100.64.0.1/", "http://2130706433/",
                "https://inside.example.com/", "http://0.0.0.0/")) {
            ToolResult result = call("open_page", "{\"url\":\"" + url + "\"}");
            assertThat(result.status()).as(url).isEqualTo(ToolResult.Status.FAILED);
            assertThat(result.summary()).as(url).isEqualTo(BrowserAddresses.PRIVATE);
        }
        assertThat(call("open_page", "{\"url\":\"file:///etc/passwd\"}").summary()).contains("Only web pages");
        assertThat(call("open_page", "{\"url\":\"ftp://example.com/\"}").summary()).contains("Only web pages");
        assertThat(call("open_page", "{\"url\":\"https://user:pw@example.com/\"}").summary()).contains("user name");
        assertThat(call("open_page", "{\"url\":\"https://nowhere.example.com/\"}").summary()).contains("could not be found");
        runner.verify(0, postRequestedFor(urlPathMatching("/sessions/.*")));
    }

    @Test
    @DisplayName("with no runner configured every call says the browser is off, and the run's page is closed at the end")
    void offAndRelease() {
        BrowserAdapter off = new BrowserAdapter(tools, json, WebClient.builder(), "", "", RESOLVER);
        assertThat(off.invoke(invocation("read_page", "{}"), null).block().summary())
                .isEqualTo(BrowserAdapter.UNAVAILABLE);

        runner.stubFor(post(urlPathMatching("/sessions/.*/open")).willReturn(okJson(PAGE)));
        runner.stubFor(delete(urlEqualTo("/sessions/" + RUN)).willReturn(aResponse().withStatus(204)));
        call("open_page", "{\"url\":\"https://example.com\"}");
        adapter.releaseRun(RUN);
        long deadline = System.currentTimeMillis() + 3_000;
        while (runner.findAll(deleteRequestedFor(urlEqualTo("/sessions/" + RUN))).isEmpty()
                && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        runner.verify(deleteRequestedFor(urlEqualTo("/sessions/" + RUN)));
    }

    private ToolResult call(String tool, String arguments) {
        return adapter.invoke(invocation(tool, arguments), null).block(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("open_page with search opens and searches in one step, and read_page passes where to read from")
    void openWithSearchAndReadFrom() {
        runner.stubFor(post(urlEqualTo("/sessions/" + RUN + "/open")).willReturn(okJson(PAGE)));
        runner.stubFor(post(urlEqualTo("/sessions/" + RUN + "/read")).willReturn(okJson(PAGE)));

        call("open_page", "{\"url\":\"https://example.com\",\"search\":\"lofi music\"}");
        call("read_page", "{\"from\":3500}");

        runner.verify(postRequestedFor(urlEqualTo("/sessions/" + RUN + "/open"))
                .withRequestBody(matchingJsonPath("$.search", equalTo("lofi music"))));
        runner.verify(postRequestedFor(urlEqualTo("/sessions/" + RUN + "/read"))
                .withRequestBody(matchingJsonPath("$.from", equalTo("3500"))));
    }

    @Test
    @DisplayName("search_site with a url opens that site and searches it in one step; a private address is refused first")
    void searchSiteWithUrl() {
        runner.stubFor(post(urlEqualTo("/sessions/" + RUN + "/search")).willReturn(okJson(PAGE)));

        call("search_site", "{\"query\":\"lofi music\",\"url\":\"https://example.com\"}");

        runner.verify(postRequestedFor(urlEqualTo("/sessions/" + RUN + "/search"))
                .withRequestBody(matchingJsonPath("$.url", equalTo("https://example.com")))
                .withRequestBody(matchingJsonPath("$.query", equalTo("lofi music"))));
        assertThat(call("search_site", "{\"query\":\"x\",\"url\":\"http://127.0.0.1/\"}").status())
                .isEqualTo(ToolResult.Status.FAILED);
    }

    @Test
    @DisplayName("the live view returns the runner's frame, and nothing when the runner has no page")
    void liveView() {
        runner.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/sessions/" + RUN + "/live"))
                .willReturn(okJson("{\"busy\":true,\"action\":\"Opening example.com\",\"url\":\"https://example.com/\",\"frame\":\"abc\"}")));

        JsonNode view = adapter.live(RUN).block();

        assertThat(view).isNotNull();
        assertThat(view.path("action").asText()).isEqualTo("Opening example.com");
        runner.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/sessions/other/live"))
                .willReturn(aResponse().withStatus(404)));
        assertThat(adapter.live("other").block()).isNull();
    }

    @Test
    @DisplayName("search_site sends the words to the runner's search, which needs no approval")
    void searchSite() {
        runner.stubFor(post(urlEqualTo("/sessions/" + RUN + "/search")).willReturn(okJson(PAGE)));

        ToolResult result = call("search_site", "{\"query\":\"lofi music\"}");

        assertThat(result.status()).isNotEqualTo(ToolResult.Status.FAILED);
        assertThat(result.summary()).isEqualTo("Searched the site; the page is now Example Domain (https://example.com/).");
        runner.verify(postRequestedFor(urlEqualTo("/sessions/" + RUN + "/search"))
                .withRequestBody(matchingJsonPath("$.query", equalTo("lofi music"))));
    }

    @Test
    @DisplayName("an approval for a submit or a click names the page and what was typed, since its arguments are empty")
    void approvalDetailNamesPageAndTypedValues() {
        runner.stubFor(post(urlPathMatching("/sessions/.*/(open|fill)")).willReturn(okJson(PAGE)));

        assertThat(adapter.approvalDetail(invocation("submit_form", "{}")))
                .contains("Submits the form on the open page. Nothing was typed into the page.");

        call("open_page", "{\"url\":\"https://example.com\"}");
        call("fill_field", "{\"field\":\"Search\",\"value\":\"Alan Turing\"}");

        assertThat(adapter.approvalDetail(invocation("submit_form", "{}")))
                .contains("Submits the form on https://example.com/, sending what was typed: Search = \"Alan Turing\".");
        assertThat(adapter.approvalDetail(invocation("click_button", "{\"text\":\"Go\"}")))
                .contains("Clicks the button \"Go\" on https://example.com/, sending what was typed:"
                        + " Search = \"Alan Turing\".");
        assertThat(adapter.approvalDetail(invocation("open_page", "{\"url\":\"https://example.com\"}"))).isEmpty();
    }

    @Test
    @DisplayName("only reading and typing are retried after a lost connection; a click or a submit never is")
    void retriesOnlySafeSteps() {
        assertThat(BrowserAdapter.repeatable("open")).isTrue();
        assertThat(BrowserAdapter.repeatable("read")).isTrue();
        assertThat(BrowserAdapter.repeatable("follow")).isTrue();
        assertThat(BrowserAdapter.repeatable("fill")).isTrue();
        assertThat(BrowserAdapter.repeatable("search")).isTrue();
        assertThat(BrowserAdapter.repeatable("click")).isFalse();
        assertThat(BrowserAdapter.repeatable("submit")).isFalse();
    }

    @Test
    @DisplayName("a person's click on a taken-over page goes to the runner once, with the secret, and comes back with a frame")
    void takeoverClick() {
        runner.stubFor(post(urlEqualTo("/sessions/" + RUN + "/takeover/click")).willReturn(okJson(
                "{\"url\":\"https://example.com/next\",\"title\":\"Next\",\"frame\":\"/9j/BBBB\"}")));
        com.fasterxml.jackson.databind.node.ObjectNode body =
                json.createObjectNode().put("x", 640).put("y", 400).put("width", 1280).put("height", 800);

        BrowserAdapter.TakeoverAnswer answer = adapter.takeover(RUN, "click", body).block(Duration.ofSeconds(10));

        assertThat(answer.ok()).isTrue();
        assertThat(answer.url()).isEqualTo("https://example.com/next");
        assertThat(answer.frame()).isEqualTo("/9j/BBBB");
        runner.verify(1, postRequestedFor(urlEqualTo("/sessions/" + RUN + "/takeover/click"))
                .withHeader(BrowserAdapter.SECRET_HEADER, equalTo("s3cret"))
                .withRequestBody(matchingJsonPath("$.x", equalTo("640"))));
    }

    @Test
    @DisplayName("a person opening a private address is refused by the same guard as open_page, before the runner")
    void takeoverNavigateGuard() {
        for (String url : List.of("http://169.254.169.254/latest/meta-data/", "http://localhost:8083/", "https://inside.example.com/")) {
            BrowserAdapter.TakeoverAnswer answer = adapter.takeover(RUN, "navigate", json.createObjectNode().put("url", url))
                    .block(Duration.ofSeconds(10));
            assertThat(answer.ok()).as(url).isFalse();
            assertThat(answer.problem()).as(url).isEqualTo(BrowserAddresses.PRIVATE);
        }
        assertThat(adapter.takeover(RUN, "navigate", json.createObjectNode().put("url", "file:///etc/passwd"))
                        .block(Duration.ofSeconds(10)).problem())
                .contains("Only web pages");
        assertThat(adapter.takeover(RUN, "submit", json.createObjectNode()).block(Duration.ofSeconds(10)).ok()).isFalse();
        runner.verify(0, postRequestedFor(urlPathMatching("/sessions/.*")));
    }

    @Test
    @DisplayName("a runner refusal of a person's action is its plain sentence; holding the page posts on or off")
    void takeoverRefusalAndHold() {
        runner.stubFor(post(urlEqualTo("/sessions/" + RUN + "/takeover/type")).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"Click the field to type into first.\"}")));
        runner.stubFor(post(urlEqualTo("/sessions/" + RUN + "/takeover/hold")).willReturn(okJson("{\"held\":true}")));

        BrowserAdapter.TakeoverAnswer typed =
                adapter.takeover(RUN, "type", json.createObjectNode().put("text", "secret words")).block(Duration.ofSeconds(10));

        assertThat(typed.ok()).isFalse();
        assertThat(typed.problem()).isEqualTo("Click the field to type into first.");
        assertThat(adapter.hold(RUN, true).block(Duration.ofSeconds(10))).isTrue();
        runner.verify(postRequestedFor(urlEqualTo("/sessions/" + RUN + "/takeover/hold"))
                .withRequestBody(matchingJsonPath("$.on", equalTo("true"))));
        BrowserAdapter off = new BrowserAdapter(tools, json, WebClient.builder(), "", "", RESOLVER);
        assertThat(off.hold(RUN, true).block()).isFalse();
        assertThat(off.takeover(RUN, "click", json.createObjectNode()).block().problem()).isEqualTo(BrowserAdapter.UNAVAILABLE);
    }

    @Test
    @DisplayName("after a person typed on a taken-over page, the approval names their field and the agent's earlier typing, never 'nothing was typed'")
    void approvalAfterTakeoverType() {
        runner.stubFor(post(urlPathMatching("/sessions/.*/(open|fill)")).willReturn(okJson(PAGE)));
        runner.stubFor(post(urlEqualTo("/sessions/" + RUN + "/takeover/type")).willReturn(okJson(
                "{\"url\":\"https://example.com/transfer\",\"title\":\"Transfer\",\"frame\":\"\",\"field\":\"Amount\"}")));
        call("open_page", "{\"url\":\"https://example.com\"}");
        call("fill_field", "{\"field\":\"Amount\",\"value\":\"10\"}");

        adapter.takeover(RUN, "type", json.createObjectNode().put("text", "9999")).block(Duration.ofSeconds(10));

        String detail = adapter.approvalDetail(invocation("submit_form", "{}")).orElseThrow();
        assertThat(detail)
                .startsWith("Submits the form on https://example.com/transfer.")
                .contains("A person had control of this page before this step and typed into \"Amount\""
                        + " (what they typed is not shown here)")
                .contains("Before they took control, the agent had typed: Amount = \"10\".")
                .doesNotContain("Nothing was typed")
                .doesNotContain("9999");
    }

    @Test
    @DisplayName("a person's click or scroll alone is still told to the approver, and a restore never types the agent's earlier values over the person's work")
    void approvalAfterTakeoverClickOrScroll() {
        runner.stubFor(post(urlPathMatching("/sessions/.*/(open|fill|click)")).willReturn(okJson(PAGE)));
        runner.stubFor(post(urlPathMatching("/sessions/" + RUN + "/takeover/(click|scroll)")).willReturn(okJson(
                "{\"url\":\"https://example.com/\",\"title\":\"Example Domain\",\"frame\":\"\"}")));
        call("open_page", "{\"url\":\"https://example.com\"}");
        call("fill_field", "{\"field\":\"Search\",\"value\":\"otters\"}");

        adapter.takeover(RUN, "scroll", json.createObjectNode().put("dy", 600)).block(Duration.ofSeconds(10));
        assertThat(adapter.approvalDetail(invocation("click_button", "{\"text\":\"Go\"}")).orElseThrow())
                .contains("A person had control of this page before this step, so the page may hold more than the agent typed.")
                .contains("Before they took control, the agent had typed: Search = \"otters\".")
                .doesNotContain("Nothing was typed");

        adapter.takeover(RUN, "click", json.createObjectNode().put("x", 1).put("y", 1).put("width", 10).put("height", 10))
                .block(Duration.ofSeconds(10));
        call("click_button", "{\"text\":\"Go\"}");
        runner.verify(postRequestedFor(urlEqualTo("/sessions/" + RUN + "/click"))
                .withRequestBody(matchingJsonPath("$.restore.url", equalTo("https://example.com/")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notContaining("otters")));
        // The click moved the page on: the next approval no longer speaks of the person.
        assertThat(adapter.approvalDetail(invocation("submit_form", "{}")).orElseThrow())
                .isEqualTo("Submits the form on https://example.com/. Nothing was typed into the page.");
    }

    @Test
    @DisplayName("the server driving the run learns of a person's typing served by another server through personActed")
    void personActedFromAnotherServer() {
        runner.stubFor(post(urlPathMatching("/sessions/.*/(open|fill)")).willReturn(okJson(PAGE)));
        runner.stubFor(post(urlEqualTo("/sessions/" + RUN + "/takeover/type")).willReturn(okJson(
                "{\"url\":\"https://example.com/pay\",\"title\":\"Pay\",\"frame\":\"\",\"field\":\"Card name\"}")));
        call("open_page", "{\"url\":\"https://example.com\"}");
        call("fill_field", "{\"field\":\"Amount\",\"value\":\"10\"}");
        // Another replica serves the person's request: it never saw the page, so it remembers nothing.
        BrowserAdapter other = new BrowserAdapter(tools, json, WebClient.builder(), runner.baseUrl(), "s3cret", RESOLVER);
        other.takeover(RUN, "type", json.createObjectNode().put("text", "J Smith")).block(Duration.ofSeconds(10));
        assertThat(other.approvalDetail(invocation("submit_form", "{}")).orElseThrow())
                .isEqualTo("Submits the form on the open page. Nothing was typed into the page.");
        assertThat(adapter.approvalDetail(invocation("submit_form", "{}")).orElseThrow())
                .contains("sending what was typed: Amount = \"10\"");

        adapter.personActed(RUN, List.of("Card name"), "https://example.com/pay");

        assertThat(adapter.approvalDetail(invocation("submit_form", "{}")).orElseThrow())
                .startsWith("Submits the form on https://example.com/pay.")
                .contains("typed into \"Card name\"")
                .contains("the agent had typed: Amount = \"10\"")
                .doesNotContain("J Smith");
    }

    private static ToolInvocation invocation(String tool, String arguments) {
        return new ToolInvocation("org-1", "agent-1", RUN, "browser", tool, arguments, RUN + ":c1", Map.of());
    }
}
