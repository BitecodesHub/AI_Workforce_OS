// @find: tests for live adapter defects, regression tests, zoom stale token, vendor bugs found while covering adapters, live connectors regression
// @what: One regression test per defect found while covering the live adapters.
package os.aiworkforce.mcp.live;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static os.aiworkforce.mcp.live.ContractTable.ATLASSIAN;
import static os.aiworkforce.mcp.live.ContractTable.TOKEN;
import static os.aiworkforce.mcp.live.ContractTable.ZOOM;
import static os.aiworkforce.mcp.live.ContractTable.ZOOM_ACCESS;
import static os.aiworkforce.mcp.live.ContractTable.invocation;
import static os.aiworkforce.mcp.live.ContractTable.sandbox;
import static os.aiworkforce.mcp.live.LiveToolContractTest.plain;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.mcp.model.ConnectionCheck;
import os.aiworkforce.mcp.model.ToolResult;

/** One regression test per defect found while covering the live adapters; each is named after it. */
class LiveAdapterDefectsTest {

    private static final String OLD_ZOOM_TOKEN = "zoom-stale-0123456789";

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

    // D1: an answer that could not be decoded reached the gateway, which names the exception class.

    @Test
    @DisplayName("D1 unreadable answer: an HTML or broken body is a plain failure, not an exception name")
    void d1UnreadableAnswerIsPlain() {
        provider.stubFor(get(urlPathEqualTo("/repos/acme/web/issues"))
                .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody("<html>proxy</html>")));
        provider.stubFor(get(urlPathEqualTo("/repos/acme/web/pulls"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[{\"broken")));
        GitHubAdapter github = new GitHubAdapter(sandbox("github"), json, WebClient.builder(), provider.baseUrl());

        ToolResult html = github.invoke(invocation("github", "list_issues", "{\"repo\":\"acme/web\"}"), TOKEN).block();
        ToolResult broken = github.invoke(invocation("github", "get_pulls", "{\"repo\":\"acme/web\"}"), TOKEN).block();

        for (ToolResult result : new ToolResult[] {html, broken}) {
            assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
            assertThat(result.summary()).isEqualTo("GitHub sent an answer that could not be read.");
        }
    }

    @Test
    @DisplayName("D1 unreadable answer to a send: the email may have gone, so it is indeterminate")
    void d1UnreadableAnswerToASendIsIndeterminate() {
        provider.stubFor(post("/gmail/v1/users/me/messages/send")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"id\":")));
        GmailAdapter gmail = new GmailAdapter(sandbox("gmail"), json, WebClient.builder(), provider.baseUrl());

        ToolResult sent = gmail.invoke(
                        invocation("gmail", "send_message", "{\"to\":\"a@b.test\",\"subject\":\"S\",\"body\":\"B\"}"), TOKEN)
                .block();

        assertThat(sent.status()).isEqualTo(ToolResult.Status.INDETERMINATE);
        plain(sent.summary());
        assertThat(sent.summary()).contains("could not be read").contains("not repeated");
    }

    // D2: Spring's 256 KB buffer limit failed every answer above it (a long email, an export).

    @Test
    @DisplayName("D2 answer over 256 KB: a long email is read rather than failing on the buffer limit")
    void d2LargeAnswerIsRead() throws Exception {
        String text = "x".repeat(400_000);
        String data = Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        provider.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages/big"))
                .willReturn(okJson("{\"id\":\"big\",\"payload\":{\"mimeType\":\"text/plain\",\"headers\":"
                        + "[{\"name\":\"Subject\",\"value\":\"Long\"}],\"body\":{\"data\":\"" + data + "\"}}}")));
        GmailAdapter gmail = new GmailAdapter(sandbox("gmail"), json, WebClient.builder(), provider.baseUrl());

        ToolResult read = gmail.invoke(invocation("gmail", "get_message", "{\"id\":\"big\"}"), TOKEN).block();

        assertThat(read.status()).as(read.summary()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(json.readTree(read.contentJson()).path("body").asText()).hasSize(50_000);
    }

    // D3: a vendor that quoted the token in its error text had it repeated in the result.

    @Test
    @DisplayName("D3 token echoed by the vendor: it is hidden in the result and in the check")
    void d3EchoedTokenIsHidden() {
        provider.stubFor(post("/rest/api/3/issue").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"errorMessages\":[\"API token " + TOKEN + " lacks the Create permission\"]}")));
        provider.stubFor(post("/graphql").willReturn(okJson(
                "{\"errors\":[{\"message\":\"Key " + TOKEN + " is not valid for this workspace\"}]}")));
        JiraAdapter jira = new JiraAdapter(sandbox("jira"), json, WebClient.builder(), provider.baseUrl());
        LinearAdapter linear = new LinearAdapter(sandbox("linear"), json, WebClient.builder(), provider.baseUrl());

        ToolResult created = jira.invoke(
                        invocation("jira", "create_issue", "{\"project\":\"ENG\",\"summary\":\"Bug\"}"), ATLASSIAN)
                .block();
        ConnectionCheck check = linear.check(TOKEN).block();

        assertThat(created.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(created.summary()).isEqualTo("Jira did not accept the request: API token [hidden] lacks the Create permission");
        assertThat(check.ok()).isFalse();
        assertThat(check.message()).contains("[hidden]").doesNotContain(TOKEN);
    }

    // D4: an empty 200 answer made invoke complete empty, so the caller received no result at all.

    @Test
    @DisplayName("D4 empty answer: a result is always returned, indeterminate for a post")
    void d4EmptyAnswerStillGivesAResult() {
        provider.stubFor(post("/chat.postMessage").willReturn(aResponse().withStatus(200)));
        provider.stubFor(get(urlPathEqualTo("/conversations.list")).willReturn(aResponse().withStatus(200)));
        SlackAdapter slack = new SlackAdapter(sandbox("slack"), json, WebClient.builder(), provider.baseUrl());

        ToolResult posted = slack.invoke(
                        invocation("slack", "post_message", "{\"channel\":\"#general\",\"text\":\"Hi\"}"), TOKEN)
                .block();
        ToolResult listed = slack.invoke(invocation("slack", "list_channels", "{}"), TOKEN).block();

        assertThat(posted).isNotNull();
        assertThat(posted.status()).isEqualTo(ToolResult.Status.INDETERMINATE);
        assertThat(posted.summary()).startsWith("Slack sent an empty answer.");
        assertThat(listed).isNotNull();
        assertThat(listed.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(listed.summary()).isEqualTo("Slack sent an empty answer.");
    }

    // D5: the webhook's name was put mid-sentence as "The webhook".

    @Test
    @DisplayName("D5 webhook wording: an unreachable receiver reads as a normal sentence")
    void d5WebhookUnreachableReadsNaturally() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        WebhookAdapter webhook = new WebhookAdapter(sandbox("webhook"), json, WebClient.builder(), true);

        ToolResult sent = webhook.invoke(
                        invocation("webhook", "send_event", "{\"event\":\"x\"}"), "http://localhost:" + closed + "/in")
                .block();

        assertThat(sent.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(sent.summary()).isEqualTo("Could not reach the webhook. Try again shortly.");
    }

    // D6: Zoom kept a token it had cached until its expiry even after Zoom stopped accepting it.

    @Test
    @DisplayName("D6 Zoom stale token: a rejected cached token is renewed and the call made once more")
    void d6ZoomStaleTokenIsRenewedAndRetriedOnce() throws Exception {
        stubZoomTokens(OLD_ZOOM_TOKEN, okJson("{\"access_token\":\"" + ZOOM_ACCESS + "\",\"expires_in\":3600}"));
        provider.stubFor(get("/meetings/81")
                .withHeader("Authorization", equalTo("Bearer " + OLD_ZOOM_TOKEN))
                .willReturn(aResponse().withStatus(401).withBody("{\"code\":124,\"message\":\"Invalid access token.\"}")));
        provider.stubFor(get("/meetings/81")
                .withHeader("Authorization", equalTo("Bearer " + ZOOM_ACCESS))
                .willReturn(okJson("{\"id\":81,\"topic\":\"Standup\"}")));
        ZoomAdapter zoom = zoom();

        ToolResult first = zoom.invoke(invocation("zoom", "get_meeting", "{\"id\":\"81\"}"), ZOOM).block();
        ToolResult second = zoom.invoke(invocation("zoom", "get_meeting", "{\"id\":\"81\"}"), ZOOM).block();

        assertThat(first.status()).as(first.summary()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(second.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
        provider.verify(2, postRequestedFor(urlPathEqualTo("/oauth/token")));
        provider.verify(3, getRequestedFor(urlPathEqualTo("/meetings/81")));
    }

    @Test
    @DisplayName("D6 Zoom stale token: a token Zoom keeps rejecting is retried once only, then explained")
    void d6ZoomRejectedTwiceIsNotRetriedAgain() {
        provider.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(okJson("{\"access_token\":\"" + ZOOM_ACCESS + "\",\"expires_in\":3600}")));
        provider.stubFor(post("/users/me/meetings").willReturn(aResponse().withStatus(401)));

        ToolResult scheduled = zoom().invoke(
                        invocation("zoom", "schedule_meeting", "{\"topic\":\"Sync\",\"start\":\"2026-10-10T09:00:00Z\"}"),
                        ZOOM)
                .block();

        assertThat(scheduled.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(scheduled.summary()).contains("connect Zoom again");
        provider.verify(2, postRequestedFor(urlPathEqualTo("/users/me/meetings")));
    }

    @Test
    @DisplayName("D6 Zoom stale token: when renewing is refused the message asks to connect again, without the secret")
    void d6ZoomRenewalRefusedAsksToReconnect() {
        stubZoomTokens(OLD_ZOOM_TOKEN, aResponse().withStatus(401).withBody("{\"reason\":\"Invalid client_id or client_secret\"}"));
        provider.stubFor(get("/meetings/81").willReturn(aResponse().withStatus(401)));

        ToolResult read = zoom().invoke(invocation("zoom", "get_meeting", "{\"id\":\"81\"}"), ZOOM).block();

        assertThat(read.status()).isEqualTo(ToolResult.Status.FAILED);
        plain(read.summary());
        assertThat(read.summary()).contains("connect Zoom again");
        provider.verify(1, getRequestedFor(urlPathEqualTo("/meetings/81")));
    }

    // D7: Linear reports a rejected key as GraphQL errors with HTTP 400, which read as a bad request.

    @Test
    @DisplayName("D7 Linear rejected key sent with 400: the call and the check ask to connect again")
    void d7LinearAuthenticationErrorWith400() {
        provider.stubFor(post("/graphql").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"errors\":[{\"message\":\"Authentication required, not authenticated\","
                        + "\"extensions\":{\"type\":\"authentication error\",\"code\":\"AUTHENTICATION_ERROR\"}}]}")));
        LinearAdapter linear = new LinearAdapter(sandbox("linear"), json, WebClient.builder(), provider.baseUrl());

        ToolResult listed = linear.invoke(invocation("linear", "list_issues", "{}"), TOKEN).block();
        ConnectionCheck check = linear.check(TOKEN).block();

        assertThat(listed.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(listed.summary()).isEqualTo("Linear rejected the stored key. An administrator needs to connect Linear again.");
        assertThat(check.ok()).isFalse();
        assertThat(check.message()).isEqualTo(listed.summary());
    }

    @Test
    @DisplayName("D7 Linear GraphQL error sent with 400: the reason is quoted, not the status")
    void d7LinearValidationErrorWith400() {
        provider.stubFor(post("/graphql").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"errors\":[{\"message\":\"Argument Validation Error\",\"extensions\":{\"code\":\"INVALID_INPUT\"}}]}")));
        LinearAdapter linear = new LinearAdapter(sandbox("linear"), json, WebClient.builder(), provider.baseUrl());

        ToolResult read = linear.invoke(invocation("linear", "get_issue", "{\"id\":\"ENG-1\"}"), TOKEN).block();

        assertThat(read.summary()).isEqualTo("Linear did not accept the request: Argument Validation Error");
    }

    // D8: a Linear mutation answering success false was reported as done.

    @Test
    @DisplayName("D8 Linear success false: the issue is reported as not created")
    void d8LinearSuccessFalseIsFailure() {
        provider.stubFor(post("/graphql").withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("teams(first"))
                .willReturn(okJson("{\"data\":{\"teams\":{\"nodes\":[{\"id\":\"team-1\",\"key\":\"ENG\"}]}}}")));
        provider.stubFor(post("/graphql").withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("issueCreate"))
                .willReturn(okJson("{\"data\":{\"issueCreate\":{\"success\":false,\"issue\":null}}}")));
        LinearAdapter linear = new LinearAdapter(sandbox("linear"), json, WebClient.builder(), provider.baseUrl());

        ToolResult created = linear.invoke(invocation("linear", "create_issue", "{\"title\":\"Crash\"}"), TOKEN).block();

        assertThat(created.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(created.summary()).isEqualTo("Linear did not confirm that the issue was created.");
    }

    // D9: Google Calendar without a start date returned the hundred oldest events ever.

    @Test
    @DisplayName("D9 Calendar without a range: events are listed from today, not from the first event ever")
    void d9CalendarWithoutRangeStartsToday() {
        provider.stubFor(get(urlPathEqualTo("/calendar/v3/calendars/primary/events")).willReturn(okJson("{\"items\":[]}")));
        CalendarAdapter calendar =
                new CalendarAdapter(sandbox("calendar"), json, WebClient.builder(), provider.baseUrl() + "/calendar/v3");

        ToolResult listed = calendar.invoke(invocation("calendar", "list_events", "{}"), TOKEN).block();

        assertThat(listed.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
        provider.verify(getRequestedFor(urlPathEqualTo("/calendar/v3/calendars/primary/events"))
                .withQueryParam("timeMin", equalTo(LocalDate.now(ZoneOffset.UTC) + "T00:00:00Z")));
    }

    // D10: one message deleted between Gmail's list and its fetch failed the whole listing.

    @Test
    @DisplayName("D10 Gmail message deleted meanwhile: it is skipped and the rest are listed")
    void d10GmailListSkipsDeletedMessage() throws Exception {
        provider.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages"))
                .willReturn(okJson("{\"messages\":[{\"id\":\"gone\"},{\"id\":\"m2\"}]}")));
        provider.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages/gone")).willReturn(aResponse().withStatus(404)));
        provider.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages/m2"))
                .willReturn(okJson("{\"id\":\"m2\",\"payload\":{\"headers\":[{\"name\":\"Subject\",\"value\":\"Kept\"}]}}")));
        GmailAdapter gmail = new GmailAdapter(sandbox("gmail"), json, WebClient.builder(), provider.baseUrl());

        ToolResult listed = gmail.invoke(invocation("gmail", "list_messages", "{}"), TOKEN).block();

        assertThat(listed.status()).as(listed.summary()).isEqualTo(ToolResult.Status.SUCCEEDED);
        JsonNode content = json.readTree(listed.contentJson());
        assertThat(content.path("count").asInt()).isEqualTo(1);
        assertThat(content.path("items").path(0).path("subject").asText()).isEqualTo("Kept");
    }

    // D11: Confluence's version conflict (409) was explained as "already has that item".

    @Test
    @DisplayName("D11 Confluence version conflict: explained as a page changed at the same time")
    void d11ConfluenceVersionConflict() {
        provider.stubFor(get(urlPathEqualTo("/wiki/api/v2/pages/11"))
                .willReturn(okJson("{\"id\":\"11\",\"title\":\"Plan\",\"version\":{\"number\":3}}")));
        provider.stubFor(put(urlPathEqualTo("/wiki/api/v2/pages/11")).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"errors\":[{\"status\":409,\"title\":\"Version must be incremented on update.\"}]}")));
        ConfluenceAdapter confluence =
                new ConfluenceAdapter(sandbox("confluence"), json, WebClient.builder(), provider.baseUrl());

        ToolResult updated = confluence.invoke(
                        invocation("confluence", "update_page", "{\"id\":\"11\",\"content\":\"New\"}"), ATLASSIAN)
                .block();

        assertThat(updated.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(updated.summary()).contains("changed at the same time").doesNotContain("already has");
    }

    // D12: a webhook receiver answering with a redirect was reported as having received the event.

    @Test
    @DisplayName("D12 webhook redirect: the event is not reported as sent, and not listed as sent")
    void d12WebhookRedirectIsNotSent() throws Exception {
        provider.stubFor(post("/hooks/in").willReturn(aResponse().withStatus(301).withHeader("Location", "https://elsewhere.test/")));
        WebhookAdapter webhook = new WebhookAdapter(sandbox("webhook"), json, WebClient.builder(), true);
        String address = "http://localhost:" + provider.port() + "/hooks/in";

        ToolResult sent = webhook.invoke(invocation("webhook", "send_event", "{\"event\":\"x\"}"), address).block();
        ToolResult listed = webhook.invoke(invocation("webhook", "list_events", "{}"), address).block();

        assertThat(sent.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(sent.summary()).contains("redirect (status 301)").contains("not delivered");
        assertThat(json.readTree(listed.contentJson()).path("count").asInt()).isZero();
        assertThat(provider.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private ZoomAdapter zoom() {
        return new ZoomAdapter(sandbox("zoom"), json, WebClient.builder(), provider.baseUrl(),
                provider.baseUrl() + "/oauth/token");
    }

    /* The first token request answers with the stale token; the next with the given answer. */
    private void stubZoomTokens(String first, com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder then) {
        provider.stubFor(post(urlPathEqualTo("/oauth/token"))
                .inScenario("zoom")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"access_token\":\"" + first + "\",\"expires_in\":3600}"))
                .willSetStateTo("renewed"));
        provider.stubFor(post(urlPathEqualTo("/oauth/token"))
                .inScenario("zoom")
                .whenScenarioStateIs("renewed")
                .willReturn(then));
    }
}
