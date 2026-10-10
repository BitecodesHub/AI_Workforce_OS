// @find: tests for token adapters, jira, confluence, zendesk, zoom, atlassian site, email api token, server-to-server oauth, wiremock stand-in provider, token connectors
// @what: Checks the token-based live adapters (Jira, Confluence, Zendesk, Zoom and similar) against a stand-in provider.
package os.aiworkforce.mcp.live;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;

/** The token-based live adapters against a stand-in provider. */
class TokenAdaptersTest {

    private static final String SECRET = "tok-secret-not-real";
    private static final String SITE = "https://acme.atlassian.net";
    private static final String ATLASSIAN = "{\"site\":\"" + SITE + "\",\"email\":\"a@b.c\",\"token\":\"" + SECRET + "\"}";
    private static final String ZENDESK = "{\"subdomain\":\"acme\",\"email\":\"a@b.c\",\"token\":\"" + SECRET + "\"}";
    private static final String ZOOM = "{\"accountId\":\"acc1\",\"clientId\":\"cid\",\"clientSecret\":\"" + SECRET + "\"}";

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

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
    }

    private JiraAdapter jira() {
        return new JiraAdapter(sandbox("jira"), json, WebClient.builder(), provider.baseUrl());
    }

    private ConfluenceAdapter confluence() {
        return new ConfluenceAdapter(sandbox("confluence"), json, WebClient.builder(), provider.baseUrl());
    }

    private ZendeskAdapter zendesk() {
        return new ZendeskAdapter(sandbox("zendesk"), json, WebClient.builder(), provider.baseUrl());
    }

    private AsanaAdapter asana() {
        return new AsanaAdapter(sandbox("asana"), json, WebClient.builder(), provider.baseUrl());
    }

    private StripeAdapter stripe() {
        return new StripeAdapter(sandbox("stripe"), json, WebClient.builder(), provider.baseUrl());
    }

    private ZoomAdapter zoom(Clock clock) {
        return new ZoomAdapter(
                sandbox("zoom"), json, WebClient.builder(), provider.baseUrl() + "/v2", provider.baseUrl() + "/oauth/token", clock);
    }

    @Test
    @DisplayName("every live adapter implements exactly the tools its sandbox declares")
    void toolsMatchSandbox() {
        assertThat(jira().liveTools()).isEqualTo(names("jira"));
        assertThat(confluence().liveTools()).isEqualTo(names("confluence"));
        assertThat(asana().liveTools()).isEqualTo(names("asana"));
        assertThat(zendesk().liveTools()).isEqualTo(names("zendesk"));
        assertThat(stripe().liveTools()).isEqualTo(names("stripe"));
        assertThat(zoom(Clock.systemUTC()).liveTools()).isEqualTo(names("zoom"));
    }

    // ---- Jira --------------------------------------------------------------------------------

    @Test
    @DisplayName("Jira: the check names the account with Basic auth, and search sends the JQL as a query")
    void jiraCheckAndSearch() throws Exception {
        provider.stubFor(get("/rest/api/3/myself")
                .withHeader("Authorization", equalTo(basic("a@b.c", SECRET)))
                .willReturn(okJson("{\"displayName\":\"Ada Admin\",\"emailAddress\":\"a@b.c\"}")));
        provider.stubFor(get(urlPathEqualTo("/rest/api/3/search/jql"))
                .willReturn(okJson("{\"issues\":[{\"key\":\"ACME-1\",\"fields\":{\"summary\":\"Broken\","
                        + "\"status\":{\"name\":\"To Do\"},\"issuetype\":{\"name\":\"Bug\"}}}]}")));

        ConnectionCheck check = jira().check(ATLASSIAN).block();
        ToolResult found = jira().invoke(
                        invocation("jira", "search_issues", "{\"jql\":\"project = ACME AND text ~ \\\"x\\\"\",\"limit\":5}"),
                        ATLASSIAN)
                .block();

        assertThat(check.ok()).as(check.message()).isTrue();
        assertThat(check.accountLabel()).isEqualTo("Ada Admin on acme.atlassian.net");
        provider.verify(getRequestedFor(urlPathEqualTo("/rest/api/3/search/jql"))
                .withQueryParam("jql", equalTo("project = ACME AND text ~ \"x\""))
                .withQueryParam("maxResults", equalTo("5")));
        JsonNode content = json.readTree(found.contentJson());
        assertThat(content.path("items").path(0).path("id").asText()).isEqualTo("ACME-1");
        assertThat(content.path("items").path(0).path("status").asText()).isEqualTo("To Do");
        assertThat(content.path("items").path(0).path("url").asText()).isEqualTo(SITE + "/browse/ACME-1");
    }

    @Test
    @DisplayName("Jira: text that is not a query is searched for, with its quotes escaped")
    void jiraTextSearch() {
        provider.stubFor(get(urlPathEqualTo("/rest/api/3/search/jql")).willReturn(okJson("{\"issues\":[]}")));

        jira().invoke(invocation("jira", "search_issues", "{\"jql\":\"login \\\"bug\\\"\"}"), ATLASSIAN).block();

        provider.verify(getRequestedFor(urlPathEqualTo("/rest/api/3/search/jql"))
                .withQueryParam("jql", equalTo("text ~ \"login \\\"bug\\\"\" ORDER BY updated DESC")));
    }

    @Test
    @DisplayName("Jira: an issue is created with an Atlassian Document Format description")
    void jiraCreate() {
        provider.stubFor(post("/rest/api/3/issue").willReturn(okJson("{\"id\":\"10\",\"key\":\"ACME-9\"}")));

        ToolResult created = jira().invoke(
                        invocation("jira", "create_issue",
                                "{\"project\":\"ACME\",\"summary\":\"Fix it\",\"description\":\"Details\",\"type\":\"Bug\"}"),
                        ATLASSIAN)
                .block();

        assertThat(created.isSuccess()).as(created.summary()).isTrue();
        provider.verify(postRequestedFor(urlPathEqualTo("/rest/api/3/issue"))
                .withHeader("Authorization", equalTo(basic("a@b.c", SECRET)))
                .withRequestBody(matchingJsonPath("$.fields.project.key", equalTo("ACME")))
                .withRequestBody(matchingJsonPath("$.fields.issuetype.name", equalTo("Bug")))
                .withRequestBody(matchingJsonPath("$.fields.description.type", equalTo("doc")))
                .withRequestBody(matchingJsonPath("$.fields.description.content[0].content[0].text", equalTo("Details"))));
    }

    @Test
    @DisplayName("Jira: a status change finds the transition by name, or lists the statuses that are open")
    void jiraTransition() {
        provider.stubFor(get("/rest/api/3/issue/ACME-1/transitions")
                .willReturn(okJson("{\"transitions\":[{\"id\":\"31\",\"name\":\"Finish\",\"to\":{\"name\":\"Done\"}},"
                        + "{\"id\":\"21\",\"name\":\"Start\",\"to\":{\"name\":\"In Progress\"}}]}")));
        provider.stubFor(post("/rest/api/3/issue/ACME-1/transitions").willReturn(aResponse().withStatus(204)));

        ToolResult moved = jira().invoke(
                        invocation("jira", "update_issue", "{\"id\":\"ACME-1\",\"status\":\"in progress\"}"), ATLASSIAN)
                .block();
        ToolResult refused = jira().invoke(
                        invocation("jira", "update_issue", "{\"id\":\"ACME-1\",\"status\":\"Archived\"}"), ATLASSIAN)
                .block();

        assertThat(moved.isSuccess()).as(moved.summary()).isTrue();
        provider.verify(1, postRequestedFor(urlPathEqualTo("/rest/api/3/issue/ACME-1/transitions"))
                .withRequestBody(equalToJson("{\"transition\":{\"id\":\"21\"}}")));
        assertThat(refused.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(refused.summary()).contains("Done").contains("In Progress");
    }

    @Test
    @DisplayName("Jira: a rejected token is a plain failure, a missing issue and a busy API are explained")
    void jiraErrors() {
        provider.stubFor(get("/rest/api/3/myself").willReturn(aResponse().withStatus(401).withBody("{}")));
        provider.stubFor(get("/rest/api/3/issue/NOPE-1/transitions").willReturn(aResponse().withStatus(404)));
        provider.stubFor(get(urlPathEqualTo("/rest/api/3/search/jql")).willReturn(aResponse().withStatus(429)));

        ConnectionCheck check = jira().check(ATLASSIAN).block();
        ToolResult missing = jira().invoke(
                        invocation("jira", "update_issue", "{\"id\":\"NOPE-1\",\"status\":\"Done\"}"), ATLASSIAN)
                .block();
        ToolResult busy = jira().invoke(invocation("jira", "search_issues", "{}"), ATLASSIAN).block();

        assertThat(check.ok()).isFalse();
        assertThat(check.message()).doesNotContain(SECRET);
        assertThat(missing.summary()).contains("could not find").doesNotContain(SECRET);
        assertThat(busy.summary()).contains("limiting requests");
    }

    // ---- Confluence --------------------------------------------------------------------------

    @Test
    @DisplayName("Confluence: the check names the account, and search sends a CQL query with escaped quotes")
    void confluenceCheckAndSearch() throws Exception {
        provider.stubFor(get("/wiki/rest/api/user/current")
                .withHeader("Authorization", equalTo(basic("a@b.c", SECRET)))
                .willReturn(okJson("{\"displayName\":\"Ada Admin\"}")));
        provider.stubFor(get(urlPathEqualTo("/wiki/rest/api/search"))
                .willReturn(okJson("{\"results\":[{\"title\":\"Onboarding\",\"excerpt\":\"how to\","
                        + "\"lastModified\":\"2026-01-01\",\"content\":{\"id\":\"123\",\"_links\":{\"webui\":\"/spaces/ENG/pages/123\"}}}]}")));

        ConnectionCheck check = confluence().check(ATLASSIAN).block();
        ToolResult found = confluence().invoke(
                        invocation("confluence", "search_pages", "{\"query\":\"say \\\"hi\\\"\",\"space\":\"ENG\",\"limit\":3}"),
                        ATLASSIAN)
                .block();

        assertThat(check.accountLabel()).isEqualTo("Ada Admin on acme.atlassian.net");
        provider.verify(getRequestedFor(urlPathEqualTo("/wiki/rest/api/search"))
                .withQueryParam("cql", equalTo("type = page and text ~ \"say \\\"hi\\\"\" and space = \"ENG\""))
                .withQueryParam("limit", equalTo("3")));
        JsonNode item = json.readTree(found.contentJson()).path("items").path(0);
        assertThat(item.path("id").asText()).isEqualTo("123");
        assertThat(item.path("url").asText()).isEqualTo(SITE + "/wiki/spaces/ENG/pages/123");
    }

    @Test
    @DisplayName("Confluence: a page is created in the space whose key is resolved to an id")
    void confluenceCreate() {
        provider.stubFor(get(urlPathEqualTo("/wiki/api/v2/spaces")).willReturn(okJson("{\"results\":[{\"id\":\"98\"}]}")));
        provider.stubFor(post("/wiki/api/v2/pages")
                .willReturn(okJson("{\"id\":\"500\",\"title\":\"Plan\",\"status\":\"current\",\"_links\":{\"webui\":\"/p\"}}")));

        ToolResult created = confluence().invoke(
                        invocation("confluence", "create_page", "{\"space\":\"ENG\",\"title\":\"Plan\",\"content\":\"a < b\"}"),
                        ATLASSIAN)
                .block();

        assertThat(created.isSuccess()).as(created.summary()).isTrue();
        provider.verify(getRequestedFor(urlPathEqualTo("/wiki/api/v2/spaces")).withQueryParam("keys", equalTo("ENG")));
        provider.verify(postRequestedFor(urlPathEqualTo("/wiki/api/v2/pages"))
                .withRequestBody(matchingJsonPath("$.spaceId", equalTo("98")))
                .withRequestBody(matchingJsonPath("$.status", equalTo("current")))
                .withRequestBody(matchingJsonPath("$.body.representation", equalTo("storage")))
                .withRequestBody(matchingJsonPath("$.body.value", equalTo("<p>a &lt; b</p>"))));
    }

    @Test
    @DisplayName("Confluence: an update reads the version and writes the next one")
    void confluenceUpdate() {
        provider.stubFor(get(urlPathEqualTo("/wiki/api/v2/pages/77"))
                .willReturn(okJson("{\"id\":\"77\",\"title\":\"Old\",\"version\":{\"number\":4},"
                        + "\"body\":{\"storage\":{\"value\":\"<p>kept</p>\"}}}")));
        provider.stubFor(put("/wiki/api/v2/pages/77")
                .willReturn(okJson("{\"id\":\"77\",\"title\":\"New\",\"version\":{\"number\":5}}")));

        ToolResult updated = confluence().invoke(
                        invocation("confluence", "update_page", "{\"id\":\"77\",\"title\":\"New\"}"), ATLASSIAN)
                .block();

        assertThat(updated.isSuccess()).as(updated.summary()).isTrue();
        provider.verify(getRequestedFor(urlPathEqualTo("/wiki/api/v2/pages/77"))
                .withQueryParam("body-format", equalTo("storage")));
        provider.verify(putRequestedFor(urlPathEqualTo("/wiki/api/v2/pages/77"))
                .withRequestBody(matchingJsonPath("$.version.number", equalTo("5")))
                .withRequestBody(matchingJsonPath("$.title", equalTo("New")))
                .withRequestBody(matchingJsonPath("$.body.value", equalTo("<p>kept</p>"))));
    }

    @Test
    @DisplayName("Confluence: a page is read as plain text")
    void confluenceGet() throws Exception {
        provider.stubFor(get(urlPathEqualTo("/wiki/api/v2/pages/77"))
                .willReturn(okJson("{\"id\":\"77\",\"title\":\"Doc\",\"status\":\"current\",\"version\":{\"number\":1},"
                        + "\"body\":{\"storage\":{\"value\":\"<h1>Hi</h1><p>One &amp; two</p>\"}}}")));

        ToolResult page = confluence().invoke(invocation("confluence", "get_page", "{\"id\":\"77\"}"), ATLASSIAN).block();

        assertThat(json.readTree(page.contentJson()).path("content").asText()).isEqualTo("Hi\nOne & two");
    }

    // ---- Asana -------------------------------------------------------------------------------

    @Test
    @DisplayName("Asana: the check names the user with Bearer auth, and tasks of a project are listed")
    void asanaCheckAndList() throws Exception {
        provider.stubFor(get("/users/me")
                .withHeader("Authorization", equalTo("Bearer " + SECRET))
                .willReturn(okJson("{\"data\":{\"gid\":\"1\",\"name\":\"Ada\",\"email\":\"a@b.c\"}}")));
        provider.stubFor(get(urlPathEqualTo("/tasks"))
                .willReturn(okJson("{\"data\":[{\"gid\":\"11\",\"name\":\"Ship\",\"completed\":false,\"due_on\":\"2026-11-01\"},"
                        + "{\"gid\":\"12\",\"name\":\"Done\",\"completed\":true}]}")));

        ConnectionCheck check = asana().check(SECRET).block();
        ToolResult open = asana().invoke(
                        invocation("asana", "list_tasks", "{\"project\":\"900\",\"completed\":false,\"limit\":10}"), SECRET)
                .block();

        assertThat(check.accountLabel()).isEqualTo("Ada (a@b.c)");
        provider.verify(getRequestedFor(urlPathEqualTo("/tasks"))
                .withQueryParam("project", equalTo("900"))
                .withQueryParam("limit", equalTo("10")));
        JsonNode content = json.readTree(open.contentJson());
        assertThat(content.path("count").asInt()).isEqualTo(1);
        assertThat(content.path("items").path(0).path("id").asText()).isEqualTo("11");
    }

    @Test
    @DisplayName("Asana: a task is created, updated and deleted with the data wrapper")
    void asanaWrites() {
        provider.stubFor(post(urlPathEqualTo("/tasks")).willReturn(okJson("{\"data\":{\"gid\":\"21\",\"name\":\"New\"}}")));
        provider.stubFor(put(urlPathEqualTo("/tasks/21")).willReturn(okJson("{\"data\":{\"gid\":\"21\",\"completed\":true}}")));
        provider.stubFor(delete("/tasks/21").willReturn(okJson("{\"data\":{}}")));

        asana().invoke(invocation("asana", "create_task",
                "{\"name\":\"New\",\"project\":\"900\",\"notes\":\"n\",\"dueOn\":\"2026-11-01\"}"), SECRET).block();
        asana().invoke(invocation("asana", "update_task", "{\"id\":\"21\",\"completed\":true}"), SECRET).block();
        ToolResult deleted = asana().invoke(invocation("asana", "delete_task", "{\"id\":\"21\"}"), SECRET).block();

        provider.verify(postRequestedFor(urlPathEqualTo("/tasks"))
                .withRequestBody(equalToJson("{\"data\":{\"name\":\"New\",\"notes\":\"n\",\"due_on\":\"2026-11-01\","
                        + "\"projects\":[\"900\"]}}")));
        provider.verify(putRequestedFor(urlPathEqualTo("/tasks/21"))
                .withRequestBody(equalToJson("{\"data\":{\"completed\":true}}")));
        provider.verify(deleteRequestedFor(urlPathEqualTo("/tasks/21")));
        assertThat(deleted.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Asana: a missing task and a failing API are explained without the token")
    void asanaErrors() {
        provider.stubFor(get(urlPathEqualTo("/tasks/404")).willReturn(aResponse().withStatus(404)));
        provider.stubFor(get(urlPathEqualTo("/tasks/500")).willReturn(aResponse().withStatus(503)));

        ToolResult missing = asana().invoke(invocation("asana", "get_task", "{\"id\":\"404\"}"), SECRET).block();
        ToolResult broken = asana().invoke(invocation("asana", "get_task", "{\"id\":\"500\"}"), SECRET).block();

        assertThat(missing.summary()).contains("could not find that item").doesNotContain(SECRET);
        assertThat(broken.summary()).contains("had a problem answering").doesNotContain(SECRET);
    }

    // ---- Zendesk -----------------------------------------------------------------------------

    @Test
    @DisplayName("Zendesk: the check sends email/token Basic auth, and a ticket is read with its conversation")
    void zendeskCheckAndGet() throws Exception {
        provider.stubFor(get("/api/v2/users/me")
                .withHeader("Authorization", equalTo(basic("a@b.c/token", SECRET)))
                .willReturn(okJson("{\"user\":{\"name\":\"Ada Agent\"}}")));
        provider.stubFor(get("/api/v2/tickets/5")
                .willReturn(okJson("{\"ticket\":{\"id\":5,\"subject\":\"Help\",\"status\":\"open\",\"description\":\"d\"}}")));
        provider.stubFor(get("/api/v2/tickets/5/comments")
                .willReturn(okJson("{\"comments\":[{\"author_id\":9,\"public\":true,\"body\":\"hello\"}]}")));

        ConnectionCheck check = zendesk().check(ZENDESK).block();
        ToolResult ticket = zendesk().invoke(invocation("zendesk", "get_ticket", "{\"id\":\"5\"}"), ZENDESK).block();

        assertThat(check.accountLabel()).isEqualTo("Ada Agent on acme.zendesk.com");
        JsonNode content = json.readTree(ticket.contentJson());
        assertThat(content.path("title").asText()).isEqualTo("Help");
        assertThat(content.path("comments").path(0).path("body").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("Zendesk: a status filter searches, and a note is private while a reply is public")
    void zendeskWrites() {
        provider.stubFor(get(urlPathEqualTo("/api/v2/search")).willReturn(okJson("{\"results\":[{\"id\":1,\"status\":\"open\"}]}")));
        provider.stubFor(put("/api/v2/tickets/5").willReturn(okJson("{\"ticket\":{\"id\":5,\"status\":\"open\"}}")));

        ToolResult listed = zendesk().invoke(invocation("zendesk", "list_tickets", "{\"status\":\"open\"}"), ZENDESK).block();
        zendesk().invoke(invocation("zendesk", "add_note", "{\"ticketId\":\"5\",\"body\":\"internal\"}"), ZENDESK).block();
        zendesk().invoke(invocation("zendesk", "send_reply", "{\"ticketId\":\"5\",\"body\":\"hi\"}"), ZENDESK).block();

        assertThat(listed.isSuccess()).isTrue();
        provider.verify(getRequestedFor(urlPathEqualTo("/api/v2/search"))
                .withQueryParam("query", equalTo("type:ticket status:open")));
        provider.verify(putRequestedFor(urlPathEqualTo("/api/v2/tickets/5"))
                .withRequestBody(equalToJson("{\"ticket\":{\"comment\":{\"body\":\"internal\",\"public\":false}}}")));
        provider.verify(putRequestedFor(urlPathEqualTo("/api/v2/tickets/5"))
                .withRequestBody(equalToJson("{\"ticket\":{\"comment\":{\"body\":\"hi\",\"public\":true}}}")));
    }

    @Test
    @DisplayName("Zendesk: a rejected token is a plain failure that never repeats the token")
    void zendeskRejected() {
        provider.stubFor(get("/api/v2/users/me").willReturn(aResponse().withStatus(401)));
        provider.stubFor(get(urlPathEqualTo("/api/v2/tickets")).willReturn(aResponse().withStatus(401)));

        ConnectionCheck check = zendesk().check(ZENDESK).block();
        ToolResult listed = zendesk().invoke(invocation("zendesk", "list_tickets", "{}"), ZENDESK).block();

        assertThat(check.ok()).isFalse();
        assertThat(check.message()).startsWith("Zendesk did not accept this token").doesNotContain(SECRET);
        assertThat(listed.summary()).contains("connect Zendesk again").doesNotContain(SECRET);
    }

    // ---- Site and subdomain checks -----------------------------------------------------------

    @Test
    @DisplayName("Jira, Confluence and Zendesk: a site that is not theirs is refused before any call")
    void hostsRefused() {
        for (String site : List.of("https://evil.example.com", "acme.atlassian.net.evil.com", "http://169.254.169.254")) {
            String credential = "{\"site\":\"" + site + "\",\"email\":\"a@b.c\",\"token\":\"" + SECRET + "\"}";
            assertThat(jira().check(credential).block().ok()).as(site).isFalse();
            ToolResult searched = confluence().invoke(invocation("confluence", "search_pages", "{}"), credential).block();
            assertThat(searched.status()).as(site).isEqualTo(ToolResult.Status.FAILED);
            assertThat(searched.summary()).doesNotContain(SECRET);
        }
        for (String subdomain : List.of("evil.example.com", "acme.zendesk.com.evil.com", "http://169.254.169.254")) {
            String credential = "{\"subdomain\":\"" + subdomain + "\",\"email\":\"a@b.c\",\"token\":\"" + SECRET + "\"}";
            ConnectionCheck check = zendesk().check(credential).block();
            assertThat(check.ok()).as(subdomain).isFalse();
            assertThat(check.message()).doesNotContain(SECRET);
        }
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("a bare subdomain or site name is accepted")
    void bareNamesWork() {
        provider.stubFor(get("/rest/api/3/myself").willReturn(okJson("{\"displayName\":\"Ada\"}")));
        provider.stubFor(get("/api/v2/users/me").willReturn(okJson("{\"user\":{\"name\":\"Ada\"}}")));

        ConnectionCheck jiraCheck =
                jira().check("{\"site\":\"acme\",\"email\":\"a@b.c\",\"token\":\"" + SECRET + "\"}").block();
        ConnectionCheck zendeskCheck = zendesk().check(ZENDESK).block();

        assertThat(jiraCheck.ok()).as(jiraCheck.message()).isTrue();
        assertThat(jiraCheck.accountLabel()).endsWith("acme.atlassian.net");
        assertThat(zendeskCheck.ok()).isTrue();
    }

    // ---- Stripe ------------------------------------------------------------------------------

    @Test
    @DisplayName("Stripe: the check reports live or test mode with Bearer auth")
    void stripeCheck() {
        provider.stubFor(get("/v1/balance")
                .withHeader("Authorization", equalTo("Bearer " + SECRET))
                .willReturn(okJson("{\"livemode\":false,\"available\":[]}")));

        ConnectionCheck check = stripe().check(SECRET).block();

        assertThat(check.accountLabel()).isEqualTo("Stripe account (test mode)");
    }

    @Test
    @DisplayName("Stripe: customers are searched with an escaped query, and payments are read in whole currency units")
    void stripeReads() throws Exception {
        provider.stubFor(get(urlPathEqualTo("/v1/customers/search"))
                .willReturn(okJson("{\"data\":[{\"id\":\"cus_1\",\"name\":\"Acme\",\"email\":\"a@b.c\",\"created\":1700000000}]}")));
        provider.stubFor(get(urlPathEqualTo("/v1/payment_intents"))
                .willReturn(okJson("{\"data\":[{\"id\":\"pi_1\",\"amount\":2550,\"currency\":\"aud\",\"status\":\"succeeded\"},"
                        + "{\"id\":\"pi_2\",\"amount\":100,\"currency\":\"aud\",\"status\":\"canceled\"}]}")));

        ToolResult customers = stripe().invoke(
                        invocation("stripe", "search_customers", "{\"query\":\"o\\\"reilly\"}"), SECRET)
                .block();
        ToolResult payments = stripe().invoke(
                        invocation("stripe", "list_payments", "{\"customer\":\"cus_1\",\"status\":\"succeeded\"}"), SECRET)
                .block();

        provider.verify(getRequestedFor(urlPathEqualTo("/v1/customers/search"))
                .withQueryParam("query", equalTo("email:\"o\\\"reilly\" OR name~\"o\\\"reilly\"")));
        provider.verify(getRequestedFor(urlPathEqualTo("/v1/payment_intents")).withQueryParam("customer", equalTo("cus_1")));
        assertThat(json.readTree(customers.contentJson()).path("items").path(0).path("id").asText()).isEqualTo("cus_1");
        JsonNode items = json.readTree(payments.contentJson()).path("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.path(0).path("amount").decimalValue()).isEqualByComparingTo("25.50");
    }

    @Test
    @DisplayName("Stripe: a refund is a form body in minor units with an idempotency key")
    void stripeRefund() {
        provider.stubFor(get("/v1/payment_intents/pi_1").willReturn(okJson("{\"id\":\"pi_1\",\"currency\":\"aud\"}")));
        provider.stubFor(post("/v1/refunds")
                .willReturn(okJson("{\"id\":\"re_1\",\"status\":\"succeeded\",\"amount\":1999,\"currency\":\"aud\","
                        + "\"payment_intent\":\"pi_1\"}")));

        ToolResult refunded = stripe().invoke(
                        invocation("stripe", "refund_payment",
                                "{\"id\":\"pi_1\",\"amount\":19.99,\"reason\":\"duplicate\"}"),
                        SECRET)
                .block();

        assertThat(refunded.isSuccess()).as(refunded.summary()).isTrue();
        provider.verify(postRequestedFor(urlPathEqualTo("/v1/refunds"))
                .withHeader("Authorization", equalTo("Bearer " + SECRET))
                .withHeader("Idempotency-Key", equalTo("run-1:call-1"))
                .withHeader("Content-Type", com.github.tomakehurst.wiremock.client.WireMock.containing("application/x-www-form-urlencoded"))
                .withRequestBody(equalTo("payment_intent=pi_1&amount=1999&reason=duplicate")));
    }

    @Test
    @DisplayName("Stripe: a full refund sends no amount, and a Stripe refusal and a 429 are explained")
    void stripeFullRefundAndErrors() {
        provider.stubFor(post("/v1/refunds")
                .willReturn(okJson("{\"id\":\"re_2\",\"status\":\"succeeded\",\"amount\":500,\"currency\":\"aud\"}")));
        provider.stubFor(get("/v1/payment_intents/pi_gone").willReturn(aResponse().withStatus(404)));
        provider.stubFor(get(urlPathEqualTo("/v1/invoices")).willReturn(aResponse().withStatus(429)));

        stripe().invoke(invocation("stripe", "refund_payment", "{\"id\":\"pi_2\"}"), SECRET).block();
        ToolResult missing = stripe().invoke(invocation("stripe", "get_payment", "{\"id\":\"pi_gone\"}"), SECRET).block();
        ToolResult busy = stripe().invoke(invocation("stripe", "list_invoices", "{}"), SECRET).block();

        provider.verify(postRequestedFor(urlPathEqualTo("/v1/refunds"))
                .withRequestBody(equalTo("payment_intent=pi_2")));
        assertThat(missing.summary()).contains("could not find that item").doesNotContain(SECRET);
        assertThat(busy.summary()).contains("limiting requests");
    }

    // ---- Zoom --------------------------------------------------------------------------------

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private void stubZoomToken() {
        provider.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(okJson("{\"access_token\":\"zoom-access\",\"expires_in\":3600}")));
    }

    @Test
    @DisplayName("Zoom: the token is fetched once with Basic client auth, cached, and fetched again after it expires")
    void zoomTokenCache() {
        stubZoomToken();
        provider.stubFor(get("/v2/users/me")
                .withHeader("Authorization", equalTo("Bearer zoom-access"))
                .willReturn(okJson("{\"display_name\":\"Ada Host\",\"email\":\"a@b.c\"}")));
        TestClock clock = new TestClock();
        ZoomAdapter zoom = zoom(clock);

        ConnectionCheck first = zoom.check(ZOOM).block();
        ConnectionCheck second = zoom.check(ZOOM).block();

        assertThat(first.accountLabel()).isEqualTo("Ada Host (a@b.c)");
        assertThat(second.ok()).isTrue();
        provider.verify(1, postRequestedFor(urlPathEqualTo("/oauth/token"))
                .withQueryParam("grant_type", equalTo("account_credentials"))
                .withQueryParam("account_id", equalTo("acc1"))
                .withHeader("Authorization", equalTo(basic("cid", SECRET))));

        clock.advance(Duration.ofSeconds(3500));
        assertThat(zoom.check(ZOOM).block().ok()).isTrue();
        provider.verify(1, postRequestedFor(urlPathEqualTo("/oauth/token")));

        clock.advance(Duration.ofSeconds(100));
        assertThat(zoom.check(ZOOM).block().ok()).isTrue();
        provider.verify(2, postRequestedFor(urlPathEqualTo("/oauth/token")));
    }

    @Test
    @DisplayName("Zoom: bad client credentials are a plain failure that never repeats the secret")
    void zoomBadClient() {
        provider.stubFor(post(urlPathEqualTo("/oauth/token"))
                .willReturn(aResponse().withStatus(400).withBody("{\"reason\":\"Invalid client_id or client_secret\",\"error\":\"invalid_client\"}")));
        ZoomAdapter zoom = zoom(Clock.systemUTC());

        ConnectionCheck check = zoom.check(ZOOM).block();
        ToolResult listed = zoom.invoke(invocation("zoom", "list_meetings", "{}"), ZOOM).block();

        assertThat(check.ok()).isFalse();
        assertThat(check.message()).startsWith("Zoom did not accept the account id, client id or client secret")
                .doesNotContain(SECRET);
        assertThat(listed.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(listed.summary()).startsWith("Zoom did not accept the account id").doesNotContain(SECRET);
    }

    @Test
    @DisplayName("Zoom: meetings are listed, scheduled with a join link, and cancelled (204)")
    void zoomMeetings() throws Exception {
        stubZoomToken();
        provider.stubFor(get(urlPathEqualTo("/v2/users/me/meetings"))
                .willReturn(okJson("{\"meetings\":[{\"id\":123456789,\"topic\":\"Standup\",\"start_time\":\"2026-10-10T01:00:00Z\","
                        + "\"duration\":15,\"join_url\":\"https://zoom.us/j/123456789\"}]}")));
        provider.stubFor(post("/v2/users/me/meetings")
                .willReturn(okJson("{\"id\":987,\"topic\":\"Review\",\"start_time\":\"2026-10-12T02:00:00Z\","
                        + "\"duration\":45,\"join_url\":\"https://zoom.us/j/987\",\"start_url\":\"https://zoom.us/s/secret\"}")));
        provider.stubFor(delete("/v2/meetings/987").willReturn(aResponse().withStatus(204)));
        ZoomAdapter zoom = zoom(Clock.systemUTC());

        ToolResult listed = zoom.invoke(invocation("zoom", "list_meetings", "{\"limit\":5}"), ZOOM).block();
        ToolResult scheduled = zoom.invoke(
                        invocation("zoom", "schedule_meeting",
                                "{\"topic\":\"Review\",\"start\":\"2026-10-12T02:00:00Z\",\"durationMinutes\":45,\"agenda\":\"Q3\"}"),
                        ZOOM)
                .block();
        ToolResult cancelled = zoom.invoke(invocation("zoom", "cancel_meeting", "{\"id\":\"987\"}"), ZOOM).block();

        provider.verify(getRequestedFor(urlPathEqualTo("/v2/users/me/meetings"))
                .withQueryParam("type", equalTo("upcoming"))
                .withQueryParam("page_size", equalTo("5"))
                .withHeader("Authorization", equalTo("Bearer zoom-access")));
        assertThat(json.readTree(listed.contentJson()).path("items").path(0).path("id").asText()).isEqualTo("123456789");
        provider.verify(postRequestedFor(urlPathEqualTo("/v2/users/me/meetings"))
                .withRequestBody(equalToJson("{\"topic\":\"Review\",\"type\":2,\"start_time\":\"2026-10-12T02:00:00Z\","
                        + "\"duration\":45,\"agenda\":\"Q3\"}")));
        JsonNode content = json.readTree(scheduled.contentJson());
        assertThat(content.path("join_url").asText()).isEqualTo("https://zoom.us/j/987");
        assertThat(scheduled.contentJson()).doesNotContain("secret");
        assertThat(cancelled.isSuccess()).as(cancelled.summary()).isTrue();
        provider.verify(deleteRequestedFor(urlPathEqualTo("/v2/meetings/987")));
    }

    @Test
    @DisplayName("Zoom: recordings are listed for a date range, and a missing meeting is explained")
    void zoomRecordingsAndMissing() {
        stubZoomToken();
        provider.stubFor(get(urlPathEqualTo("/v2/users/me/recordings")).willReturn(okJson("{\"meetings\":[]}")));
        provider.stubFor(get("/v2/meetings/1").willReturn(aResponse().withStatus(404)));
        ZoomAdapter zoom = zoom(Clock.systemUTC());

        ToolResult recordings = zoom.invoke(
                        invocation("zoom", "list_recordings", "{\"from\":\"2026-10-01\",\"to\":\"2026-10-31\"}"), ZOOM)
                .block();
        ToolResult missing = zoom.invoke(invocation("zoom", "get_meeting", "{\"id\":\"1\"}"), ZOOM).block();

        assertThat(recordings.isSuccess()).isTrue();
        provider.verify(getRequestedFor(urlPathEqualTo("/v2/users/me/recordings"))
                .withQueryParam("from", equalTo("2026-10-01"))
                .withQueryParam("to", equalTo("2026-10-31")));
        assertThat(missing.summary()).contains("could not find that item");
    }

    // ---- Sandbox fallback --------------------------------------------------------------------

    @Test
    @DisplayName("with no credential stored, Stripe and Zoom answer from the sandbox")
    void fallsBackToSandbox() {
        ToolResult payments = stripe().invoke(invocation("stripe", "list_payments", "{}"), null).block();
        ToolResult meetings = zoom(Clock.systemUTC()).invoke(invocation("zoom", "list_meetings", "{}"), "").block();

        assertThat(payments.isSuccess()).isTrue();
        assertThat(payments.summary()).contains("sandbox");
        assertThat(meetings.isSuccess()).isTrue();
        assertThat(meetings.summary()).contains("sandbox");
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private static Set<String> names(String server) {
        return SandboxServerRegistry.definitions().get(server).stream()
                .map(ToolDefinition::name)
                .collect(Collectors.toSet());
    }

    private static SandboxServerAdapter sandbox(String server) {
        return new SandboxServerAdapter(server, SandboxServerRegistry.definitions().get(server), new ObjectMapper());
    }

    private static ToolInvocation invocation(String server, String tool, String arguments) {
        return new ToolInvocation("org-1", "agent-1", "run-1", server, tool, arguments, "run-1:call-1", Map.of());
    }
}
