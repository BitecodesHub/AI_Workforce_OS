// @find: tests for web browser connector, browser adapter, open page, follow link, fill field, click button, submit form, screenshot metadata, browser runner client, SSRF refusals, private address, metadata address, localhost refused
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
                "fill_field", ToolSpec.SideEffect.WRITE,
                "click_button", ToolSpec.SideEffect.OUTBOUND,
                "submit_form", ToolSpec.SideEffect.OUTBOUND));
        assertThat(tools).filteredOn(ToolDefinition::alwaysRequiresApproval)
                .extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("click_button", "submit_form");
        assertThat(tools).allSatisfy(tool -> assertThat(tool.defaultTimeout()).isGreaterThan(Duration.ofSeconds(30)));
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

    private static ToolInvocation invocation(String tool, String arguments) {
        return new ToolInvocation("org-1", "agent-1", RUN, "browser", tool, arguments, RUN + ":c1", Map.of());
    }
}
