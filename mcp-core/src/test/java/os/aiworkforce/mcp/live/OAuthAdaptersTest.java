package os.aiworkforce.mcp.live;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
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

/** The OAuth-backed live adapters against a stand-in provider: request shapes and plain failures. */
class OAuthAdaptersTest {

    private static final String TOKEN = "oauth-access-token-not-real";
    private static final String BEARER = "Bearer " + TOKEN;
    private static final String SF_TOKEN = "sf-access-token-not-real";
    private static final String SF_CREDENTIAL =
            "{\"accessToken\":\"" + SF_TOKEN + "\",\"instanceUrl\":\"https://acme.my.salesforce.com\"}";

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

    // ---- Gmail --------------------------------------------------------------------------------

    @Test
    @DisplayName("Gmail: the check names the mailbox using the exact bearer header")
    void gmailCheck() {
        provider.stubFor(get("/gmail/v1/users/me/profile")
                .withHeader("Authorization", equalTo(BEARER))
                .willReturn(okJson("{\"emailAddress\":\"ops@example.com\"}")));

        ConnectionCheck check = gmail().check(TOKEN).block();

        assertThat(check.ok()).isTrue();
        assertThat(check.accountLabel()).isEqualTo("ops@example.com");
    }

    @Test
    @DisplayName("Gmail: messages are listed with the query, fetched as metadata, and kept in order")
    void gmailList() throws Exception {
        provider.stubFor(get(urlPathMatching("/gmail/v1/users/me/messages"))
                .willReturn(okJson("{\"messages\":[{\"id\":\"m1\"},{\"id\":\"m2\"}]}")));
        provider.stubFor(get(urlPathMatching("/gmail/v1/users/me/messages/m1"))
                .willReturn(okJson(metadata("m1", "Slow one", "a@example.com")).withFixedDelay(300)));
        provider.stubFor(get(urlPathMatching("/gmail/v1/users/me/messages/m2"))
                .willReturn(okJson(metadata("m2", "Fast one", "b@example.com"))));

        ToolResult result = gmail().invoke(
                        invocation("gmail", "list_messages", "{\"query\":\"from:a is:unread\",\"limit\":2}"), TOKEN)
                .block();

        LoggedRequest list = lastRequestTo("/gmail/v1/users/me/messages");
        assertThat(list.queryParameter("q").firstValue()).isEqualTo("from:a is:unread");
        assertThat(list.queryParameter("maxResults").firstValue()).isEqualTo("2");
        LoggedRequest fetch = provider.findAll(anyRequestedFor(urlPathMatching("/gmail/v1/users/me/messages/m1")))
                .get(0);
        assertThat(fetch.queryParameter("format").firstValue()).isEqualTo("metadata");
        assertThat(fetch.queryParameter("metadataHeaders").values()).containsExactly("From", "Subject", "Date");
        JsonNode content = json.readTree(result.contentJson());
        assertThat(content.path("count").asInt()).isEqualTo(2);
        assertThat(content.path("items").path(0).path("id").asText()).isEqualTo("m1");
        assertThat(content.path("items").path(0).path("from").asText()).isEqualTo("a@example.com");
        assertThat(content.path("items").path(0).path("subject").asText()).isEqualTo("Slow one");
        assertThat(content.path("items").path(0).path("labels").path(0).asText()).isEqualTo("INBOX");
        assertThat(content.path("items").path(1).path("id").asText()).isEqualTo("m2");
    }

    @Test
    @DisplayName("Gmail: a message body is decoded from the plain text part, or the stripped HTML")
    void gmailBody() throws Exception {
        provider.stubFor(get(urlPathMatching("/gmail/v1/users/me/messages/p1"))
                .willReturn(okJson(full("p1", "text/plain", "Hello there"))));
        provider.stubFor(get(urlPathMatching("/gmail/v1/users/me/messages/h1"))
                .willReturn(okJson(full("h1", "text/html", "<p>Hello <b>there</b></p>"))));

        ToolResult plain = gmail().invoke(invocation("gmail", "get_message", "{\"id\":\"p1\"}"), TOKEN).block();
        ToolResult html = gmail().invoke(invocation("gmail", "get_message", "{\"id\":\"h1\"}"), TOKEN).block();

        assertThat(json.readTree(plain.contentJson()).path("body").asText()).isEqualTo("Hello there");
        assertThat(json.readTree(html.contentJson()).path("body").asText()).isEqualTo("Hello there");
        assertThat(provider.findAll(anyRequestedFor(urlPathMatching("/gmail/v1/users/me/messages/p1")))
                        .get(0)
                        .queryParameter("format")
                        .firstValue())
                .isEqualTo("full");
    }

    @Test
    @DisplayName("Gmail: a sent email is a base64url RFC 2822 message with the headers and body")
    void gmailSend() throws Exception {
        provider.stubFor(post("/gmail/v1/users/me/messages/send")
                .willReturn(okJson("{\"id\":\"sent1\",\"threadId\":\"t1\"}")));

        ToolResult result = gmail().invoke(
                        invocation(
                                "gmail",
                                "send_message",
                                "{\"to\":\"jo@example.com\",\"subject\":\"Café menu\",\"body\":\"Line one\\nLine two\"}"),
                        TOKEN)
                .block();

        assertThat(result.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
        LoggedRequest request = provider.findAll(anyRequestedFor(anyUrl())).get(0);
        assertThat(request.getHeader("Authorization")).isEqualTo(BEARER);
        String raw = json.readTree(request.getBodyAsString()).path("raw").asText();
        assertThat(raw).doesNotContain("=", "+", "/");
        String message = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        assertThat(message).contains("To: jo@example.com\r\n");
        assertThat(message).contains("Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString("Café menu".getBytes(StandardCharsets.UTF_8)) + "?=");
        assertThat(message).contains("Content-Type: text/plain; charset=UTF-8");
        assertThat(message).endsWith("\r\n\r\nLine one\r\nLine two");
        assertThat(json.readTree(result.contentJson()).path("id").asText()).isEqualTo("sent1");
    }

    @Test
    @DisplayName("Gmail: a draft goes to the drafts endpoint wrapped in a message")
    void gmailDraft() throws Exception {
        provider.stubFor(post("/gmail/v1/users/me/drafts").willReturn(okJson("{\"id\":\"d1\"}")));

        gmail().invoke(
                        invocation("gmail", "draft_message", "{\"to\":\"jo@example.com\",\"subject\":\"Hi\",\"body\":\"Body\"}"),
                        TOKEN)
                .block();

        LoggedRequest request = provider.findAll(anyRequestedFor(anyUrl())).get(0);
        String raw = json.readTree(request.getBodyAsString()).path("message").path("raw").asText();
        assertThat(new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8))
                .contains("Subject: Hi\r\n")
                .endsWith("Body");
    }

    @Test
    @DisplayName("Gmail: a line break in the address or subject is refused before any request is made")
    void gmailInjection() {
        for (String arguments : new String[] {
            "{\"to\":\"a@example.com\\r\\nBcc: x@evil.test\",\"subject\":\"Hi\",\"body\":\"B\"}",
            "{\"to\":\"a@example.com\",\"subject\":\"Hi\\nBcc: x@evil.test\",\"body\":\"B\"}"
        }) {
            ToolResult result = gmail().invoke(invocation("gmail", "send_message", arguments), TOKEN).block();
            assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
            assertThat(result.summary()).contains("line breaks");
        }
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    // ---- Calendar -----------------------------------------------------------------------------

    @Test
    @DisplayName("Calendar: the check names the calendar; events are listed with a UTC day range")
    void calendarRead() throws Exception {
        provider.stubFor(get("/calendar/v3/users/me/calendarList/primary")
                .withHeader("Authorization", equalTo(BEARER))
                .willReturn(okJson("{\"id\":\"ops@example.com\",\"summary\":\"Operations calendar\"}")));
        provider.stubFor(get(urlPathMatching("/calendar/v3/calendars/primary/events"))
                .willReturn(okJson("{\"items\":[{\"id\":\"e1\",\"summary\":\"Stand-up\","
                        + "\"start\":{\"dateTime\":\"2026-10-05T09:00:00+10:00\"},"
                        + "\"end\":{\"dateTime\":\"2026-10-05T09:30:00+10:00\"},"
                        + "\"attendees\":[{\"email\":\"t@example.com\"}]},"
                        + "{\"id\":\"e2\",\"summary\":\"Holiday\",\"start\":{\"date\":\"2026-10-06\"},"
                        + "\"end\":{\"date\":\"2026-10-07\"}}]}")));
        CalendarAdapter calendar = new CalendarAdapter(sandbox("calendar"), json, WebClient.builder(), provider.baseUrl() + "/calendar/v3");

        ConnectionCheck check = calendar.check(TOKEN).block();
        ToolResult events = calendar.invoke(
                        invocation("calendar", "list_events", "{\"from\":\"2026-10-05\",\"to\":\"2026-10-06\"}"), TOKEN)
                .block();

        assertThat(check.accountLabel()).isEqualTo("Operations calendar");
        LoggedRequest request = lastRequestTo("/calendar/v3/calendars/primary/events");
        assertThat(request.getHeader("Authorization")).isEqualTo(BEARER);
        assertThat(request.queryParameter("timeMin").firstValue()).isEqualTo("2026-10-05T00:00:00Z");
        assertThat(request.queryParameter("timeMax").firstValue()).isEqualTo("2026-10-06T23:59:59Z");
        assertThat(request.queryParameter("singleEvents").firstValue()).isEqualTo("true");
        assertThat(request.queryParameter("orderBy").firstValue()).isEqualTo("startTime");
        JsonNode content = json.readTree(events.contentJson());
        assertThat(content.path("items").path(0).path("title").asText()).isEqualTo("Stand-up");
        assertThat(content.path("items").path(0).path("attendees").path(0).asText()).isEqualTo("t@example.com");
        assertThat(content.path("items").path(1).path("start").asText()).isEqualTo("2026-10-06");
    }

    @Test
    @DisplayName("Calendar: an event is created with attendees, and deletion handles an empty 204")
    void calendarWrite() throws Exception {
        provider.stubFor(post("/calendar/v3/calendars/primary/events")
                .willReturn(okJson("{\"id\":\"new1\",\"summary\":\"Review\",\"start\":{\"dateTime\":\"2026-10-08T10:00:00Z\"},"
                        + "\"end\":{\"dateTime\":\"2026-10-08T11:00:00Z\"}}")));
        provider.stubFor(delete("/calendar/v3/calendars/primary/events/new1").willReturn(aResponse().withStatus(204)));
        CalendarAdapter calendar = new CalendarAdapter(sandbox("calendar"), json, WebClient.builder(), provider.baseUrl() + "/calendar/v3");

        ToolResult created = calendar.invoke(
                        invocation(
                                "calendar",
                                "create_event",
                                "{\"title\":\"Review\",\"start\":\"2026-10-08T10:00:00Z\",\"end\":\"2026-10-08T11:00:00Z\","
                                        + "\"attendees\":[\"a@example.com\"]}"),
                        TOKEN)
                .block();
        ToolResult deleted = calendar.invoke(invocation("calendar", "delete_event", "{\"id\":\"new1\"}"), TOKEN).block();

        JsonNode body = json.readTree(lastRequestTo("/calendar/v3/calendars/primary/events", "POST").getBodyAsString());
        assertThat(body.path("summary").asText()).isEqualTo("Review");
        assertThat(body.path("start").path("dateTime").asText()).isEqualTo("2026-10-08T10:00:00Z");
        assertThat(body.path("attendees").path(0).path("email").asText()).isEqualTo("a@example.com");
        assertThat(created.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(deleted.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(json.readTree(deleted.contentJson()).path("deleted").asBoolean()).isTrue();
    }

    // ---- Drive --------------------------------------------------------------------------------

    @Test
    @DisplayName("Drive: the check names the account; files are listed inside an escaped folder query")
    void driveList() throws Exception {
        provider.stubFor(get(urlPathEqualTo("/drive/v3/about"))
                .withHeader("Authorization", equalTo(BEARER))
                .willReturn(okJson("{\"user\":{\"displayName\":\"Ops\",\"emailAddress\":\"ops@example.com\"}}")));
        provider.stubFor(get(urlPathMatching("/drive/v3/files"))
                .willReturn(okJson("{\"files\":[{\"id\":\"f1\",\"name\":\"Handbook\",\"mimeType\":\"application/pdf\","
                        + "\"modifiedTime\":\"2026-10-01T00:00:00Z\",\"parents\":[\"fold\"]}]}")));

        ConnectionCheck check = drive().check(TOKEN).block();
        ToolResult files = drive().invoke(invocation("drive", "list_files", "{\"folderId\":\"a'b\"}"), TOKEN).block();

        assertThat(check.accountLabel()).isEqualTo("ops@example.com");
        LoggedRequest request = lastRequestTo("/drive/v3/files");
        assertThat(request.queryParameter("q").firstValue()).isEqualTo("'a\\'b' in parents and trashed=false");
        assertThat(request.queryParameter("fields").firstValue()).isEqualTo("files(id,name,mimeType,modifiedTime,parents)");
        JsonNode item = json.readTree(files.contentJson()).path("items").path(0);
        assertThat(item.path("id").asText()).isEqualTo("f1");
        assertThat(item.path("folderId").asText()).isEqualTo("fold");
    }

    @Test
    @DisplayName("Drive: Google documents are exported as text, binary files are refused")
    void driveGet() throws Exception {
        provider.stubFor(get(urlPathMatching("/drive/v3/files/doc1"))
                .willReturn(okJson("{\"id\":\"doc1\",\"name\":\"Policy\",\"mimeType\":\"application/vnd.google-apps.document\"}")));
        provider.stubFor(get(urlPathMatching("/drive/v3/files/doc1/export"))
                .willReturn(aResponse().withHeader("Content-Type", "text/plain").withBody("Refunds within 30 days.")));
        provider.stubFor(get(urlPathMatching("/drive/v3/files/pic1"))
                .willReturn(okJson("{\"id\":\"pic1\",\"name\":\"Logo\",\"mimeType\":\"image/png\"}")));

        ToolResult doc = drive().invoke(invocation("drive", "get_file", "{\"id\":\"doc1\"}"), TOKEN).block();
        ToolResult image = drive().invoke(invocation("drive", "get_file", "{\"id\":\"pic1\"}"), TOKEN).block();

        assertThat(lastRequestTo("/drive/v3/files/doc1/export").queryParameter("mimeType").firstValue())
                .isEqualTo("text/plain");
        assertThat(json.readTree(doc.contentJson()).path("content").asText()).isEqualTo("Refunds within 30 days.");
        assertThat(image.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(image.summary()).contains("binary");
    }

    @Test
    @DisplayName("Drive: a file is created with a multipart upload holding the metadata and the text")
    void driveCreate() throws Exception {
        provider.stubFor(post(urlPathMatching("/upload/drive/v3/files"))
                .willReturn(okJson("{\"id\":\"new1\",\"name\":\"Notes\",\"mimeType\":\"text/plain\"}")));

        ToolResult result = drive().invoke(
                        invocation("drive", "create_file", "{\"name\":\"Notes\",\"content\":\"First line\",\"folderId\":\"fold\"}"),
                        TOKEN)
                .block();

        LoggedRequest request = lastRequestTo("/upload/drive/v3/files", "POST");
        assertThat(request.getHeader("Authorization")).isEqualTo(BEARER);
        assertThat(request.getHeader("Content-Type")).startsWith("multipart/related;").contains("boundary=");
        assertThat(request.queryParameter("uploadType").firstValue()).isEqualTo("multipart");
        String body = request.getBodyAsString();
        assertThat(body).contains("{\"name\":\"Notes\",\"parents\":[\"fold\"]}").contains("First line");
        assertThat(json.readTree(result.contentJson()).path("id").asText()).isEqualTo("new1");
    }

    // ---- Sheets -------------------------------------------------------------------------------

    @Test
    @DisplayName("Sheets: the check names the Google account through the Drive host")
    void sheetsCheck() {
        provider.stubFor(get(urlPathEqualTo("/drive/v3/about"))
                .withHeader("Authorization", equalTo(BEARER))
                .willReturn(okJson("{\"user\":{\"emailAddress\":\"ops@example.com\"}}")));

        ConnectionCheck check = sheets().check(TOKEN).block();

        assertThat(check.ok()).isTrue();
        assertThat(check.accountLabel()).isEqualTo("ops@example.com");
    }

    @Test
    @DisplayName("Sheets: spreadsheets are listed from Drive with a mime type query")
    void sheetsList() throws Exception {
        provider.stubFor(get(urlPathMatching("/drive/v3/files"))
                .willReturn(okJson("{\"files\":[{\"id\":\"s1\",\"name\":\"Pipeline\",\"modifiedTime\":\"2026-10-01T00:00:00Z\"}]}")));

        ToolResult result = sheets().invoke(invocation("sheets", "list_spreadsheets", "{}"), TOKEN).block();

        assertThat(lastRequestTo("/drive/v3/files").queryParameter("q").firstValue())
                .isEqualTo("mimeType='application/vnd.google-apps.spreadsheet' and trashed=false");
        assertThat(json.readTree(result.contentJson()).path("items").path(0).path("name").asText())
                .isEqualTo("Pipeline");
    }

    @Test
    @DisplayName("Sheets: row ids from list_rows go straight back into update_row")
    void sheetsRoundTrip() throws Exception {
        provider.stubFor(get("/v4/spreadsheets/s1?fields=sheets.properties.title")
                .willReturn(okJson("{\"sheets\":[{\"properties\":{\"title\":\"Deals\"}}]}")));
        provider.stubFor(get(urlPathMatching("/v4/spreadsheets/s1/values/.*"))
                .willReturn(okJson("{\"values\":[[\"Name\",\"Stage\",\"Value\"],[\"Riverside\",\"Proposal\",24000]]}")));
        provider.stubFor(put(urlPathMatching("/v4/spreadsheets/s1/values/.*"))
                .willReturn(okJson("{\"updatedRange\":\"Deals!A2:C2\",\"updatedCells\":3}")));
        SheetsAdapter sheets = sheets();

        ToolResult rows = sheets.invoke(invocation("sheets", "list_rows", "{\"spreadsheetId\":\"s1\",\"limit\":10}"), TOKEN)
                .block();
        JsonNode second = json.readTree(rows.contentJson()).path("items").path(1);
        assertThat(second.path("values").path(2).asText()).isEqualTo("24000");
        String id = second.path("id").asText();
        assertThat(id).isEqualTo("s1:'Deals'!A2:C2");
        ToolResult updated = sheets.invoke(
                        invocation(
                                "sheets",
                                "update_row",
                                json.createObjectNode()
                                        .put("id", id)
                                        .set("values", json.createArrayNode().add("Riverside").add("Won").add("24000"))
                                        .toString()),
                        TOKEN)
                .block();

        assertThat(updated.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
        LoggedRequest read = provider.findAll(anyRequestedFor(urlPathMatching("/v4/spreadsheets/s1/values/.*")))
                .stream()
                .filter(r -> r.getMethod().getName().equals("GET"))
                .findFirst()
                .orElseThrow();
        assertThat(decoded(read)).isEqualTo("/v4/spreadsheets/s1/values/'Deals'!A1:ZZ10");
        LoggedRequest put = lastRequestTo("/v4/spreadsheets/s1/values/.*", "PUT");
        assertThat(decoded(put)).isEqualTo("/v4/spreadsheets/s1/values/'Deals'!A2:C2");
        assertThat(put.queryParameter("valueInputOption").firstValue()).isEqualTo("USER_ENTERED");
        JsonNode body = json.readTree(put.getBodyAsString());
        assertThat(body.path("values").path(0).path(1).asText()).isEqualTo("Won");
        assertThat(body.path("range").asText()).isEqualTo("'Deals'!A2:C2");
    }

    @Test
    @DisplayName("Sheets: a row is appended below the data, and a malformed row id is refused")
    void sheetsAppend() throws Exception {
        provider.stubFor(post(urlPathMatching("/v4/spreadsheets/s1/values/.*"))
                .willReturn(okJson("{\"updates\":{\"updatedRange\":\"Deals!A5:C5\",\"updatedRows\":1}}")));
        SheetsAdapter sheets = sheets();

        ToolResult appended = sheets.invoke(
                        invocation(
                                "sheets",
                                "append_row",
                                "{\"spreadsheetId\":\"s1\",\"sheet\":\"My Deals\",\"values\":[\"Acme\",\"New\",100]}"),
                        TOKEN)
                .block();
        ToolResult refused =
                sheets.invoke(invocation("sheets", "update_row", "{\"id\":\"row_1\",\"values\":[\"x\"]}"), TOKEN).block();

        LoggedRequest post = lastRequestTo("/v4/spreadsheets/s1/values/.*", "POST");
        assertThat(decoded(post)).isEqualTo("/v4/spreadsheets/s1/values/'My Deals':append");
        assertThat(post.queryParameter("valueInputOption").firstValue()).isEqualTo("USER_ENTERED");
        assertThat(post.queryParameter("insertDataOption").firstValue()).isEqualTo("INSERT_ROWS");
        assertThat(json.readTree(post.getBodyAsString()).path("values").path(0).path(2).asText()).isEqualTo("100");
        assertThat(json.readTree(appended.contentJson()).path("updatedRange").asText()).isEqualTo("Deals!A5:C5");
        assertThat(refused.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(provider.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
    }

    // ---- Outlook ------------------------------------------------------------------------------

    @Test
    @DisplayName("Outlook: the check names the mailbox; a search drops the ordering Graph would refuse")
    void outlookList() throws Exception {
        provider.stubFor(get("/v1.0/me")
                .withHeader("Authorization", equalTo(BEARER))
                .willReturn(okJson("{\"displayName\":\"Ops\",\"mail\":\"ops@contoso.test\"}")));
        provider.stubFor(get(urlPathMatching("/v1.0/me/messages"))
                .willReturn(okJson("{\"value\":[{\"id\":\"o1\",\"subject\":\"Quote\",\"from\":{\"emailAddress\":{\"address\":\"c@example.com\"}},"
                        + "\"bodyPreview\":\"Please quote\",\"receivedDateTime\":\"2026-10-01T13:12:00Z\",\"isRead\":false}]}")));
        OutlookAdapter outlook = outlook();

        ConnectionCheck check = outlook.check(TOKEN).block();
        ToolResult searched = outlook.invoke(invocation("outlook", "list_messages", "{\"query\":\"quote\",\"limit\":5}"), TOKEN).block();
        LoggedRequest search = lastRequestTo("/v1.0/me/messages");
        outlook.invoke(invocation("outlook", "list_messages", "{}"), TOKEN).block();
        LoggedRequest recent = lastRequestTo("/v1.0/me/messages");

        assertThat(check.accountLabel()).isEqualTo("ops@contoso.test");
        assertThat(search.queryParameter("$search").firstValue()).isEqualTo("\"quote\"");
        assertThat(search.queryParameter("$top").firstValue()).isEqualTo("5");
        assertThat(search.queryParameter("$orderby").isPresent()).isFalse();
        assertThat(recent.queryParameter("$orderby").firstValue()).isEqualTo("receivedDateTime desc");
        assertThat(search.getHeader("Authorization")).isEqualTo(BEARER);
        JsonNode item = json.readTree(searched.contentJson()).path("items").path(0);
        assertThat(item.path("from").asText()).isEqualTo("c@example.com");
        assertThat(item.path("snippet").asText()).isEqualTo("Please quote");
    }

    @Test
    @DisplayName("Outlook: send_message posts to sendMail and accepts the empty 202; a header break is refused")
    void outlookSend() throws Exception {
        provider.stubFor(post("/v1.0/me/sendMail").willReturn(aResponse().withStatus(202)));
        provider.stubFor(delete("/v1.0/me/messages/o1").willReturn(aResponse().withStatus(204)));
        OutlookAdapter outlook = outlook();

        ToolResult sent = outlook.invoke(
                        invocation("outlook", "send_message", "{\"to\":\"a@example.com, b@example.com\",\"subject\":\"Hi\",\"body\":\"Hello\"}"),
                        TOKEN)
                .block();
        ToolResult deleted = outlook.invoke(invocation("outlook", "delete_message", "{\"id\":\"o1\"}"), TOKEN).block();
        ToolResult refused = outlook.invoke(
                        invocation("outlook", "send_message", "{\"to\":\"a@example.com\",\"subject\":\"Hi\\r\\nBcc: x\",\"body\":\"B\"}"),
                        TOKEN)
                .block();

        assertThat(sent.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(deleted.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
        assertThat(refused.status()).isEqualTo(ToolResult.Status.FAILED);
        JsonNode body = json.readTree(lastRequestTo("/v1.0/me/sendMail", "POST").getBodyAsString());
        assertThat(body.path("saveToSentItems").asBoolean()).isTrue();
        assertThat(body.path("message").path("subject").asText()).isEqualTo("Hi");
        assertThat(body.path("message").path("body").path("contentType").asText()).isEqualTo("Text");
        assertThat(body.path("message").path("body").path("content").asText()).isEqualTo("Hello");
        assertThat(body.path("message").path("toRecipients")).hasSize(2);
        assertThat(body.path("message").path("toRecipients").path(1).path("emailAddress").path("address").asText())
                .isEqualTo("b@example.com");
        assertThat(provider.findAll(anyRequestedFor(anyUrl()))).hasSize(2);
    }

    @Test
    @DisplayName("Outlook: calendar events are read from calendarView with a UTC range")
    void outlookEvents() throws Exception {
        provider.stubFor(get(urlPathMatching("/v1.0/me/calendarView"))
                .willReturn(okJson("{\"value\":[{\"id\":\"ev1\",\"subject\":\"Board pack\","
                        + "\"start\":{\"dateTime\":\"2026-10-07T15:00:00.0000000\",\"timeZone\":\"UTC\"},"
                        + "\"end\":{\"dateTime\":\"2026-10-07T16:00:00.0000000\",\"timeZone\":\"UTC\"},"
                        + "\"attendees\":[{\"emailAddress\":{\"address\":\"b@example.com\"}}]}]}")));

        ToolResult result = outlook().invoke(
                        invocation("outlook", "list_events", "{\"from\":\"2026-10-07\",\"to\":\"2026-10-09\"}"), TOKEN)
                .block();

        LoggedRequest request = lastRequestTo("/v1.0/me/calendarView");
        assertThat(request.queryParameter("startDateTime").firstValue()).isEqualTo("2026-10-07T00:00:00Z");
        assertThat(request.queryParameter("endDateTime").firstValue()).isEqualTo("2026-10-09T23:59:59Z");
        JsonNode item = json.readTree(result.contentJson()).path("items").path(0);
        assertThat(item.path("title").asText()).isEqualTo("Board pack");
        assertThat(item.path("start").asText()).isEqualTo("2026-10-07T15:00:00.0000000Z");
        assertThat(item.path("attendees").path(0).asText()).isEqualTo("b@example.com");
    }

    // ---- Teams --------------------------------------------------------------------------------

    @Test
    @DisplayName("Teams: channels are listed across the joined teams, or for one team by name")
    void teamsChannels() throws Exception {
        stubTeams();
        TeamsAdapter teams = teams();

        ConnectionCheck check = teams.check(TOKEN).block();
        ToolResult all = teams.invoke(invocation("teams", "list_channels", "{}"), TOKEN).block();
        ToolResult one = teams.invoke(invocation("teams", "list_channels", "{\"team\":\"operations\"}"), TOKEN).block();
        ToolResult unknown = teams.invoke(invocation("teams", "list_channels", "{\"team\":\"Nope\"}"), TOKEN).block();

        assertThat(check.accountLabel()).isEqualTo("ops@contoso.test");
        JsonNode items = json.readTree(all.contentJson()).path("items");
        assertThat(items).hasSize(3);
        assertThat(items.path(0).path("team").asText()).isEqualTo("Operations");
        assertThat(items.path(0).path("name").asText()).isEqualTo("General");
        assertThat(json.readTree(one.contentJson()).path("count").asInt()).isEqualTo(2);
        assertThat(unknown.status()).isEqualTo(ToolResult.Status.FAILED);
    }

    @Test
    @DisplayName("Teams: messages are read and posted by channel name or by team/channel id; ambiguity is refused")
    void teamsMessages() throws Exception {
        stubTeams();
        provider.stubFor(get(urlPathMatching("/v1.0/teams/t1/channels/.+/messages"))
                .willReturn(okJson("{\"value\":[{\"id\":\"mm1\",\"createdDateTime\":\"2026-10-02T01:00:00Z\","
                        + "\"from\":{\"user\":{\"displayName\":\"Jamie\"}},\"body\":{\"contentType\":\"html\",\"content\":\"<p>Cover <b>Saturday</b>?</p>\"}}]}")));
        provider.stubFor(post(urlPathMatching("/v1.0/teams/t1/channels/.+/messages"))
                .willReturn(okJson("{\"id\":\"posted1\",\"createdDateTime\":\"2026-10-02T02:00:00Z\"}")));
        TeamsAdapter teams = teams();

        ToolResult read = teams.invoke(invocation("teams", "get_messages", "{\"channel\":\"rostering\",\"limit\":5}"), TOKEN).block();
        ToolResult posted = teams.invoke(
                        invocation("teams", "post_message", "{\"channel\":\"t1/19:rost@thread.tacv2\",\"text\":\"On it\"}"), TOKEN)
                .block();
        ToolResult ambiguous = teams.invoke(invocation("teams", "get_messages", "{\"channel\":\"General\"}"), TOKEN).block();

        JsonNode item = json.readTree(read.contentJson()).path("items").path(0);
        assertThat(item.path("from").asText()).isEqualTo("Jamie");
        assertThat(item.path("text").asText()).isEqualTo("Cover Saturday?");
        assertThat(lastRequestTo("/v1.0/teams/t1/channels/.*/messages", "GET").queryParameter("$top").firstValue())
                .isEqualTo("5");
        JsonNode body = json.readTree(lastRequestTo("/v1.0/teams/t1/channels/.*/messages", "POST").getBodyAsString());
        assertThat(body.path("body").path("content").asText()).isEqualTo("On it");
        assertThat(json.readTree(posted.contentJson()).path("id").asText()).isEqualTo("posted1");
        assertThat(ambiguous.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(ambiguous.summary()).contains("More than one channel");
    }

    // ---- Salesforce ---------------------------------------------------------------------------

    @Test
    @DisplayName("Salesforce: the check names the user and organisation using the access token from the credential")
    void salesforceCheck() {
        provider.stubFor(get("/services/oauth2/userinfo")
                .withHeader("Authorization", equalTo("Bearer " + SF_TOKEN))
                .willReturn(okJson("{\"name\":\"Sam Ops\"}")));
        provider.stubFor(get(urlPathMatching("/services/data/v60.0/query"))
                .willReturn(okJson("{\"records\":[{\"Name\":\"Acme Pty Ltd\"}]}")));

        ConnectionCheck check = salesforce().check(SF_CREDENTIAL).block();

        assertThat(check.ok()).isTrue();
        assertThat(check.accountLabel()).isEqualTo("Sam Ops (Acme Pty Ltd)");
    }

    @Test
    @DisplayName("Salesforce: a hostile search term stays inside the SOQL string literal")
    void salesforceSoql() throws Exception {
        provider.stubFor(get(urlPathMatching("/services/data/v60.0/query"))
                .willReturn(okJson("{\"records\":[{\"Id\":\"001A\",\"Name\":\"Riverside Care\",\"Industry\":\"Healthcare\",\"BillingCity\":\"Brisbane\"}]}")));

        ToolResult result = salesforce().invoke(
                        invocation("salesforce", "search_accounts", "{\"query\":\"' OR 1=1 --\",\"limit\":5}"), SF_CREDENTIAL)
                .block();

        LoggedRequest request = lastRequestTo("/services/data/v60.0/query");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + SF_TOKEN);
        assertThat(request.queryParameter("q").firstValue())
                .isEqualTo("SELECT Id,Name,Industry,BillingCity FROM Account WHERE Name LIKE '%\\' OR 1=1 --%' LIMIT 5");
        assertThat(SalesforceAdapter.likeTerm("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
        JsonNode item = json.readTree(result.contentJson()).path("items").path(0);
        assertThat(item.path("name").asText()).isEqualTo("Riverside Care");
        assertThat(item.path("city").asText()).isEqualTo("Brisbane");
    }

    @Test
    @DisplayName("Salesforce: opportunities are filtered by an escaped stage, and a bad record id is refused")
    void salesforceOpportunities() throws Exception {
        provider.stubFor(get(urlPathMatching("/services/data/v60.0/query"))
                .willReturn(okJson("{\"records\":[{\"Id\":\"006\",\"Name\":\"Expansion\",\"Account\":{\"Name\":\"Riverside\"},"
                        + "\"StageName\":\"Proposal\",\"Amount\":24000.0,\"CloseDate\":\"2026-11-15\"}]}")));
        SalesforceAdapter salesforce = salesforce();

        ToolResult listed = salesforce.invoke(
                        invocation("salesforce", "list_opportunities", "{\"stage\":\"Won' OR StageName != '\"}"), SF_CREDENTIAL)
                .block();
        ToolResult refused = salesforce.invoke(invocation("salesforce", "get_opportunity", "{\"id\":\"../x\"}"), SF_CREDENTIAL).block();

        assertThat(lastRequestTo("/services/data/v60.0/query").queryParameter("q").firstValue())
                .isEqualTo("SELECT Id,Name,Account.Name,StageName,Amount,CloseDate FROM Opportunity "
                        + "WHERE StageName = 'Won\\' OR StageName != \\'' ORDER BY CloseDate DESC LIMIT 20");
        assertThat(json.readTree(listed.contentJson()).path("items").path(0).path("account").asText()).isEqualTo("Riverside");
        assertThat(refused.status()).isEqualTo(ToolResult.Status.FAILED);
    }

    @Test
    @DisplayName("Salesforce: a lead is created, and an opportunity is changed with PATCH and an empty 204")
    void salesforceWrite() throws Exception {
        provider.stubFor(post("/services/data/v60.0/sobjects/Lead").willReturn(okJson("{\"id\":\"00Q000000000001AAA\",\"success\":true}")));
        provider.stubFor(patch("/services/data/v60.0/sobjects/Opportunity/006000000000001AAA").willReturn(aResponse().withStatus(204)));
        SalesforceAdapter salesforce = salesforce();

        ToolResult lead = salesforce.invoke(
                        invocation("salesforce", "create_lead", "{\"firstName\":\"Pat\",\"lastName\":\"Lee\",\"company\":\"Acme\",\"email\":\"p@acme.test\"}"),
                        SF_CREDENTIAL)
                .block();
        ToolResult updated = salesforce.invoke(
                        invocation("salesforce", "update_opportunity", "{\"id\":\"006000000000001AAA\",\"stage\":\"Closed Won\",\"amount\":25000,\"closeDate\":\"2026-11-20\"}"),
                        SF_CREDENTIAL)
                .block();

        JsonNode leadBody = json.readTree(lastRequestTo("/services/data/v60.0/sobjects/Lead", "POST").getBodyAsString());
        assertThat(leadBody.path("FirstName").asText()).isEqualTo("Pat");
        assertThat(leadBody.path("LastName").asText()).isEqualTo("Lee");
        assertThat(leadBody.path("Company").asText()).isEqualTo("Acme");
        assertThat(leadBody.path("Email").asText()).isEqualTo("p@acme.test");
        assertThat(json.readTree(lead.contentJson()).path("id").asText()).isEqualTo("00Q000000000001AAA");
        JsonNode patch = json.readTree(lastRequestTo("/services/data/v60.0/sobjects/Opportunity/.*", "PATCH").getBodyAsString());
        assertThat(patch.path("StageName").asText()).isEqualTo("Closed Won");
        assertThat(patch.path("Amount").decimalValue()).isEqualByComparingTo("25000");
        assertThat(patch.path("CloseDate").asText()).isEqualTo("2026-11-20");
        assertThat(updated.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
    }

    @Test
    @DisplayName("Salesforce: an instance address on another host is refused without any request")
    void salesforceForeignHost() {
        String foreign = "{\"accessToken\":\"" + SF_TOKEN + "\",\"instanceUrl\":\"https://evil.example.com\"}";
        SalesforceAdapter salesforce = salesforce();

        ToolResult result = salesforce.invoke(invocation("salesforce", "search_accounts", "{\"query\":\"x\"}"), foreign).block();
        ConnectionCheck check = salesforce.check(foreign).block();
        ToolResult plain = salesforce.invoke(invocation("salesforce", "search_accounts", "{\"query\":\"x\"}"), "just-a-token").block();

        assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(result.summary()).contains("not a Salesforce domain").doesNotContain(SF_TOKEN);
        assertThat(check.ok()).isFalse();
        assertThat(plain.status()).isEqualTo(ToolResult.Status.FAILED);
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    // ---- Failures -----------------------------------------------------------------------------

    @Test
    @DisplayName("a rejected token asks for the account to be connected again and never repeats the token")
    void unauthorised() {
        provider.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(401).withBody("{\"error\":{\"message\":\"Invalid Credentials\"}}")));
        provider.stubFor(any(urlPathMatching("/services/.*"))
                .willReturn(aResponse().withStatus(401).withBody("[{\"message\":\"Session expired or invalid\",\"errorCode\":\"INVALID_SESSION_ID\"}]")));

        List<ToolResult> results = List.of(
                gmail().invoke(invocation("gmail", "get_message", "{\"id\":\"m1\"}"), TOKEN).block(),
                drive().invoke(invocation("drive", "list_files", "{}"), TOKEN).block(),
                sheets().invoke(invocation("sheets", "list_spreadsheets", "{}"), TOKEN).block(),
                outlook().invoke(invocation("outlook", "get_message", "{\"id\":\"o1\"}"), TOKEN).block(),
                teams().invoke(invocation("teams", "list_channels", "{}"), TOKEN).block(),
                salesforce().invoke(invocation("salesforce", "search_accounts", "{\"query\":\"x\"}"), SF_CREDENTIAL).block());

        for (ToolResult result : results) {
            assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
            assertThat(result.summary()).contains("needs to be connected again").doesNotContain(TOKEN, SF_TOKEN);
        }
        ConnectionCheck check = gmail().check(TOKEN).block();
        assertThat(check.ok()).isFalse();
        assertThat(check.message()).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("forbidden, missing, limited and failing answers each become one plain sentence")
    void otherFailures() {
        provider.stubFor(get(urlPathEqualTo("/gmail/v1/users/me/messages/gone")).willReturn(aResponse().withStatus(404).withBody("{}")));
        provider.stubFor(post("/gmail/v1/users/me/drafts")
                .willReturn(aResponse().withStatus(403).withBody("{\"error\":{\"message\":\"Insufficient Permission\"}}")));
        provider.stubFor(get(urlPathEqualTo("/v1.0/me/messages/busy")).willReturn(aResponse().withStatus(429)));
        provider.stubFor(delete("/v1.0/me/messages/boom").willReturn(aResponse().withStatus(503)));
        provider.stubFor(patch(urlPathMatching("/services/data/v60.0/sobjects/Opportunity/.*"))
                .willReturn(aResponse().withStatus(400).withBody("[{\"message\":\"Required fields are missing: [StageName]\",\"errorCode\":\"REQUIRED_FIELD_MISSING\"}]")));

        ToolResult missing = gmail().invoke(invocation("gmail", "get_message", "{\"id\":\"gone\"}"), TOKEN).block();
        ToolResult forbidden = gmail().invoke(
                        invocation("gmail", "draft_message", "{\"to\":\"a@example.com\",\"subject\":\"s\",\"body\":\"b\"}"), TOKEN)
                .block();
        ToolResult limited = outlook().invoke(invocation("outlook", "get_message", "{\"id\":\"busy\"}"), TOKEN).block();
        ToolResult failing = outlook().invoke(invocation("outlook", "delete_message", "{\"id\":\"boom\"}"), TOKEN).block();
        ToolResult rejected = salesforce().invoke(
                        invocation("salesforce", "update_opportunity", "{\"id\":\"006000000000001AAA\",\"stage\":\"X\"}"), SF_CREDENTIAL)
                .block();

        assertThat(missing.summary()).contains("could not find that item");
        assertThat(forbidden.summary()).contains("refused this action").contains("Insufficient Permission");
        assertThat(limited.summary()).contains("limiting requests");
        assertThat(failing.summary()).contains("status 503");
        assertThat(rejected.summary()).contains("Required fields are missing");
        for (ToolResult result : List.of(missing, forbidden, limited, failing, rejected)) {
            assertThat(result.status()).isEqualTo(ToolResult.Status.FAILED);
            assertThat(result.summary()).doesNotContain(TOKEN, SF_TOKEN);
        }
    }

    // ---- Shape --------------------------------------------------------------------------------

    @Test
    @DisplayName("with no credential stored, the OAuth adapters answer from the sandbox")
    void sandboxFallback() {
        for (LiveServerAdapter adapter : List.of(gmail(), outlook(), salesforce())) {
            String tool = adapter.server().equals("salesforce") ? "search_accounts" : "list_messages";
            ToolResult result = adapter.invoke(invocation(adapter.server(), tool, "{}"), null).block();
            assertThat(result.status()).isEqualTo(ToolResult.Status.SUCCEEDED);
            assertThat(result.summary()).contains("sandbox");
        }
        assertThat(provider.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("every adapter implements exactly the tools its sandbox definition declares")
    void liveToolsMatchSandbox() {
        for (LiveServerAdapter adapter : List.of(gmail(), calendar(), drive(), sheets(), outlook(), teams(), salesforce())) {
            Set<String> declared = adapter.tools().stream().map(ToolDefinition::name).collect(Collectors.toSet());
            assertThat(adapter.liveTools()).as(adapter.server()).isEqualTo(declared);
            assertThat(adapter.isSandbox()).isFalse();
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private void stubTeams() {
        provider.stubFor(get("/v1.0/me").willReturn(okJson("{\"mail\":\"ops@contoso.test\"}")));
        provider.stubFor(get("/v1.0/me/joinedTeams")
                .willReturn(okJson("{\"value\":[{\"id\":\"t1\",\"displayName\":\"Operations\"},{\"id\":\"t2\",\"displayName\":\"Sales\"}]}")));
        provider.stubFor(get("/v1.0/teams/t1/channels")
                .willReturn(okJson("{\"value\":[{\"id\":\"19:gen1@thread.tacv2\",\"displayName\":\"General\",\"description\":\"Day to day\"},"
                        + "{\"id\":\"19:rost@thread.tacv2\",\"displayName\":\"Rostering\",\"description\":\"Shifts\"}]}")));
        provider.stubFor(get("/v1.0/teams/t2/channels")
                .willReturn(okJson("{\"value\":[{\"id\":\"19:gen2@thread.tacv2\",\"displayName\":\"General\"}]}")));
    }

    private static String metadata(String id, String subject, String from) {
        return "{\"id\":\"" + id + "\",\"snippet\":\"Snippet " + id + "\",\"internalDate\":\"1790000000000\","
                + "\"labelIds\":[\"INBOX\"],\"payload\":{\"headers\":[{\"name\":\"From\",\"value\":\"" + from + "\"},"
                + "{\"name\":\"Subject\",\"value\":\"" + subject + "\"}]}}";
    }

    private static String full(String id, String mime, String body) {
        String data = Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return "{\"id\":\"" + id + "\",\"payload\":{\"mimeType\":\"multipart/alternative\",\"headers\":"
                + "[{\"name\":\"Subject\",\"value\":\"S\"}],\"parts\":[{\"mimeType\":\"" + mime
                + "\",\"body\":{\"data\":\"" + data + "\"}}]}}";
    }

    private LoggedRequest lastRequestTo(String pathPattern) {
        List<LoggedRequest> requests = provider.findAll(anyRequestedFor(urlPathMatching(pathPattern)));
        return requests.get(requests.size() - 1);
    }

    private LoggedRequest lastRequestTo(String pathPattern, String method) {
        List<LoggedRequest> requests = provider.findAll(anyRequestedFor(urlPathMatching(pathPattern))).stream()
                .filter(request -> request.getMethod().getName().equals(method))
                .toList();
        assertThat(requests).as(method + " " + pathPattern).isNotEmpty();
        return requests.get(requests.size() - 1);
    }

    private static String decoded(LoggedRequest request) {
        String url = request.getUrl();
        int query = url.indexOf('?');
        return URLDecoder.decode((query < 0 ? url : url.substring(0, query)).replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private GmailAdapter gmail() {
        return new GmailAdapter(sandbox("gmail"), json, WebClient.builder(), provider.baseUrl());
    }

    private CalendarAdapter calendar() {
        return new CalendarAdapter(sandbox("calendar"), json, WebClient.builder(), provider.baseUrl() + "/calendar/v3");
    }

    private DriveAdapter drive() {
        return new DriveAdapter(sandbox("drive"), json, WebClient.builder(), provider.baseUrl());
    }

    private SheetsAdapter sheets() {
        return new SheetsAdapter(sandbox("sheets"), json, WebClient.builder(), provider.baseUrl(), provider.baseUrl());
    }

    private OutlookAdapter outlook() {
        return new OutlookAdapter(sandbox("outlook"), json, WebClient.builder(), provider.baseUrl() + "/v1.0");
    }

    private TeamsAdapter teams() {
        return new TeamsAdapter(sandbox("teams"), json, WebClient.builder(), provider.baseUrl() + "/v1.0");
    }

    private SalesforceAdapter salesforce() {
        return new SalesforceAdapter(sandbox("salesforce"), json, WebClient.builder(), provider.baseUrl());
    }

    private static SandboxServerAdapter sandbox(String server) {
        return new SandboxServerAdapter(server, SandboxServerRegistry.definitions().get(server), new ObjectMapper());
    }

    private static ToolInvocation invocation(String server, String tool, String arguments) {
        return new ToolInvocation("org-1", "agent-1", "run-1", server, tool, arguments, "run-1:call-1", Map.of());
    }
}
