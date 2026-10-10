// @find: tests for live adapters, github, slack, hubspot, linear, notion, stripe, asana, request shapes, plain failure messages, wiremock stand-in provider
// @what: Checks live adapters send the real request shapes and report failures in plain words.
package os.aiworkforce.mcp.live;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

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

import os.aiworkforce.mcp.model.ConnectionCheck;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;

/** The live adapters against a stand-in provider: the real request shapes, and plain failures. */
class LiveAdaptersTest {

    private static final String TOKEN = "test-token-not-real";

    private final ObjectMapper json = new ObjectMapper();
    private WireMockServer provider;

    @BeforeEach
    void start() {
        provider = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        provider.start();
    }

    @AfterEach
    void stop() {
        provider.stop();
    }

    @Test
    @DisplayName("GitHub: the check names the account, and issues are read without pull requests")
    void github() throws Exception {
        provider.stubFor(get("/user")
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .willReturn(okJson("{\"login\":\"octo-admin\"}")));
        provider.stubFor(get(urlPathEqualTo("/repos/acme/website/issues"))
                .willReturn(okJson("[{\"number\":7,\"title\":\"Bug\",\"state\":\"open\",\"user\":{\"login\":\"sam\"}},"
                        + "{\"number\":8,\"title\":\"A pull\",\"pull_request\":{}}]")));
        GitHubAdapter github = new GitHubAdapter(sandbox("github"), json, WebClient.builder(), provider.baseUrl());

        ConnectionCheck check = github.check(TOKEN).block();
        ToolResult issues = github.invoke(invocation("github", "list_issues", "{\"repo\":\"acme/website\"}"), TOKEN)
                .block();

        assertThat(check.ok()).isTrue();
        assertThat(check.accountLabel()).isEqualTo("octo-admin");
        JsonNode content = json.readTree(issues.contentJson());
        assertThat(content.path("count").asInt()).isEqualTo(1);
        assertThat(content.path("items").path(0).path("id").asText()).isEqualTo("7");
    }

    @Test
    @DisplayName("GitHub: a rejected token is a plain failure that never repeats the token")
    void githubRejected() {
        provider.stubFor(get("/user").willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Bad credentials\"}")));
        provider.stubFor(post("/repos/acme/website/issues")
                .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Bad credentials\"}")));
        GitHubAdapter github = new GitHubAdapter(sandbox("github"), json, WebClient.builder(), provider.baseUrl());

        ConnectionCheck check = github.check(TOKEN).block();
        ToolResult created = github.invoke(
                        invocation("github", "create_issue", "{\"repo\":\"acme/website\",\"title\":\"x\"}"), TOKEN)
                .block();

        assertThat(check.ok()).isFalse();
        assertThat(check.message()).startsWith("GitHub did not accept this token");
        assertThat(created.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(created.summary()).contains("connect GitHub again").doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("a check that finds nothing at the address says so in plain words, not as a status code")
    void checkNotFoundIsPlain() {
        provider.stubFor(get("/user").willReturn(aResponse().withStatus(404)));
        GitHubAdapter github = new GitHubAdapter(sandbox("github"), json, WebClient.builder(), provider.baseUrl());

        ConnectionCheck check = github.check(TOKEN).block();

        assertThat(check.ok()).isFalse();
        assertThat(check.message())
                .isEqualTo("GitHub could not find that account or site. Check the address and details, then try again.")
                .doesNotContain("404");
    }

    @Test
    @DisplayName("GitHub: a repo that is not owner/name never reaches the provider")
    void githubRepoChecked() {
        GitHubAdapter github = new GitHubAdapter(sandbox("github"), json, WebClient.builder(), provider.baseUrl());

        ToolResult result = github.invoke(invocation("github", "list_issues", "{\"repo\":\"../../user\"}"), TOKEN)
                .block();

        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(result.summary()).contains("owner/name");
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("with no credential stored, a live adapter answers from the sandbox")
    void fallsBackToSandbox() {
        GitHubAdapter github = new GitHubAdapter(sandbox("github"), json, WebClient.builder(), provider.baseUrl());

        ToolResult result = github.invoke(invocation("github", "list_issues", "{\"repo\":\"acme/website\"}"), null)
                .block();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.summary()).contains("sandbox");
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("Slack: ok false with HTTP 200 is still a failure, explained in plain words")
    void slackOkFalse() {
        provider.stubFor(get(urlPathEqualTo("/auth.test"))
                .willReturn(okJson("{\"ok\":true,\"user\":\"workforce\",\"team\":\"Acme\"}")));
        provider.stubFor(post("/chat.postMessage").willReturn(okJson("{\"ok\":false,\"error\":\"not_in_channel\"}")));
        SlackAdapter slack = new SlackAdapter(sandbox("slack"), json, WebClient.builder(), provider.baseUrl());

        ConnectionCheck check = slack.check(TOKEN).block();
        ToolResult posted = slack.invoke(
                        invocation("slack", "post_message", "{\"channel\":\"#general\",\"text\":\"Hello\"}"), TOKEN)
                .block();

        assertThat(check.accountLabel()).isEqualTo("workforce in Acme");
        assertThat(posted.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(posted.summary()).contains("not in that channel");
        provider.verify(postRequestedFor(urlPathEqualTo("/chat.postMessage"))
                .withRequestBody(equalToJson("{\"channel\":\"general\",\"text\":\"Hello\"}")));
    }

    @Test
    @DisplayName("Linear: the key goes in the header as it is, and GraphQL errors are failures")
    void linear() {
        provider.stubFor(post("/graphql")
                .withHeader("Authorization", equalTo(TOKEN))
                .withRequestBody(matchingJsonPath("$.query", com.github.tomakehurst.wiremock.client.WireMock.containing("viewer")))
                .willReturn(okJson("{\"data\":{\"viewer\":{\"name\":\"Robin\"},\"organization\":{\"name\":\"Acme\"}}}")));
        provider.stubFor(post("/graphql")
                .withRequestBody(matchingJsonPath("$.query", com.github.tomakehurst.wiremock.client.WireMock.containing("issue(id")))
                .willReturn(okJson("{\"errors\":[{\"message\":\"Entity not found\"}]}")));
        LinearAdapter linear = new LinearAdapter(sandbox("linear"), json, WebClient.builder(), provider.baseUrl());

        assertThat(linear.check(TOKEN).block().accountLabel()).isEqualTo("Robin (Acme)");
        ToolResult missing = linear.invoke(invocation("linear", "get_issue", "{\"id\":\"ENG-1\"}"), TOKEN).block();
        assertThat(missing.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(missing.summary()).isEqualTo("Linear did not accept the request: Entity not found");
    }

    @Test
    @DisplayName("Webhook: an event is posted as JSON to the connected address")
    void webhookDelivers() throws Exception {
        provider.stubFor(post("/hooks/orders").willReturn(aResponse().withStatus(202)));
        WebhookAdapter webhook = new WebhookAdapter(sandbox("webhook"), json, WebClient.builder(), true);
        String address = "http://localhost:" + provider.port() + "/hooks/orders";

        ToolResult sent = webhook.invoke(
                        invocation("webhook", "send_event", "{\"event\":\"order.shipped\",\"data\":{\"order\":\"SO-1\"}}"),
                        address)
                .block();
        ToolResult listed = webhook.invoke(invocation("webhook", "list_events", "{}"), address).block();

        assertThat(sent.isSuccess()).as(sent.summary()).isTrue();
        provider.verify(postRequestedFor(urlPathEqualTo("/hooks/orders"))
                .withHeader("Idempotency-Key", equalTo("run-1:call-1"))
                .withRequestBody(matchingJsonPath("$.event", equalTo("order.shipped")))
                .withRequestBody(matchingJsonPath("$.data.order", equalTo("SO-1"))));
        assertThat(json.readTree(listed.contentJson()).path("count").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Webhook: localhost is refused outside testing, and the check never contacts the address")
    void webhookLoopbackRefusedWhenDeployed() {
        WebhookAdapter webhook = new WebhookAdapter(sandbox("webhook"), json, WebClient.builder(), false);
        String address = "http://localhost:" + provider.port() + "/hooks/orders";

        ToolResult sent = webhook.invoke(invocation("webhook", "send_event", "{\"event\":\"x\"}"), address)
                .block();
        ConnectionCheck check = webhook.check("https://hooks.example.com/abc").block();

        assertThat(sent.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(sent.summary()).contains("only allowed while testing");
        assertThat(check.ok()).isTrue();
        assertThat(check.accountLabel()).isEqualTo("hooks.example.com");
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("Webhook: addresses that could reach a private network are refused")
    void webhookAddressRules() {
        assertThat(WebhookAdapter.problem("https://hooks.example.com/abc", false)).isNull();
        assertThat(WebhookAdapter.problem("http://localhost:9000/x", true)).isNull();

        assertThat(WebhookAdapter.problem("http://hooks.example.com/abc", false)).contains("https://");
        assertThat(WebhookAdapter.problem("http://hooks.example.com/abc", true)).contains("https://");
        assertThat(WebhookAdapter.problem("ftp://hooks.example.com/abc", true)).contains("https://");
        assertThat(WebhookAdapter.problem("not a url", true)).isNotNull();
        assertThat(WebhookAdapter.problem("", true)).isNotNull();
        assertThat(WebhookAdapter.problem("https://user:secret@hooks.example.com/", true)).contains("user name");
        assertThat(WebhookAdapter.problem("http://localhost:9000/x", false)).contains("only allowed while testing");
        assertThat(WebhookAdapter.problem("https://127.0.0.1/x", false)).contains("only allowed while testing");
        assertThat(WebhookAdapter.problem("https://[::1]/x", false)).contains("only allowed while testing");
        for (String inside : new String[] {
            "https://10.0.0.5/hook",
            "https://192.168.1.10/hook",
            "https://172.16.4.4/hook",
            "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/hook",
            "https://0.0.0.0/hook",
            "https://[fd00::1]/hook",
            "https://[fe80::1]/hook"
        }) {
            assertThat(WebhookAdapter.problem(inside, true)).as(inside).contains("private network");
        }
    }

    private static SandboxServerAdapter sandbox(String server) {
        return new SandboxServerAdapter(server, SandboxServerRegistry.definitions().get(server), new ObjectMapper());
    }

    private static ToolInvocation invocation(String server, String tool, String arguments) {
        return new ToolInvocation("org-1", "agent-1", "run-1", server, tool, arguments, "run-1:call-1", Map.of());
    }
}
