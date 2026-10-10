// @find: test data for live adapter contracts, every tool of every live adapter, provider requests and answers, wiremock stand-in, gmail, slack, github, jira, confluence, asana, zendesk, stripe, zoom, hubspot, linear, notion, salesforce, outlook, teams, calendar, drive, sheets, webhook
// @what: Table of one row per live tool: arguments, expected provider request, provider answer and expected result.
// @flow: Used by LiveToolContractTest
package os.aiworkforce.mcp.live;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.http.Request;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;
import os.aiworkforce.mcp.sandbox.SandboxServerRegistry;

/**
 * Every tool of every live adapter as a row: the arguments, the requests the provider must see,
 * the answers it gives, and what the result must say. The contract tests run each row against a
 * stand-in provider, then again with the provider failing in each way it can.
 */
final class ContractTable {

    static final String TOKEN = "tok-contract-0123456789";
    static final String ZOOM_ACCESS = "zoom-access-0123456789";
    static final String EMAIL = "ops@acme.test";
    static final String NOTION_ID = "0123456789abcdef0123456789abcdef";
    static final String NOTION_UUID = "01234567-89ab-cdef-0123-456789abcdef";
    static final String SF_ID = "006000000000001";

    private ContractTable() {}

    // ---- Rows ---------------------------------------------------------------------------------

    /** One request the provider must receive, and the answer it gives. */
    static final class Call {
        final String method;
        final String path;
        final Map<String, String> query = new LinkedHashMap<>();
        final Map<String, String> headers = new LinkedHashMap<>();
        final List<String> contains = new ArrayList<>();
        final List<String> jsonPaths = new ArrayList<>();
        String match;
        String json;
        int status = 200;
        String answer = "{}";
        String contentType = "application/json";

        Call(String method, String path) {
            this.method = method;
            this.path = path;
        }

        Call q(String key, String value) {
            query.put(key, value);
            return this;
        }

        Call header(String key, String value) {
            headers.put(key, value);
            return this;
        }

        /** Text the request body must contain; also used to tell GraphQL calls apart. */
        Call match(String text) {
            match = text;
            return this;
        }

        /** The request body, compared as JSON ignoring extra fields. */
        Call json(String body) {
            json = body;
            return this;
        }

        Call contains(String... texts) {
            contains.addAll(List.of(texts));
            return this;
        }

        Call jsonPath(String path) {
            jsonPaths.add(path);
            return this;
        }

        Call answer(String body) {
            answer = body;
            return this;
        }

        Call answer(int code, String body) {
            status = code;
            answer = body;
            return this;
        }

        Call text(String body) {
            answer = body;
            contentType = "text/plain; charset=UTF-8";
            return this;
        }

        Call empty(int code) {
            status = code;
            answer = null;
            return this;
        }

        /** Whether a request is this one: method, decoded path, the listed query values, the match text. */
        boolean matches(Request request) {
            if (!request.getMethod().getName().equals(method)) {
                return false;
            }
            String url = request.getUrl();
            int split = url.indexOf('?');
            if (!decode(split < 0 ? url : url.substring(0, split)).equals(path)) {
                return false;
            }
            Map<String, String> given = query(url);
            for (Map.Entry<String, String> wanted : query.entrySet()) {
                if (!wanted.getValue().equals(given.get(wanted.getKey()))) {
                    return false;
                }
            }
            return match == null || request.getBodyAsString().contains(match);
        }

        @Override
        public String toString() {
            return method + " " + path + (query.isEmpty() ? "" : " " + query);
        }
    }

    /** One tool, called once with arguments the provider accepts. */
    static final class Case {
        final String server;
        final String tool;
        final String args;
        final String variant;
        final List<Call> calls = new ArrayList<>();
        final Map<String, String> expect = new LinkedHashMap<>();
        String summary;

        Case(String server, String tool, String variant, String args) {
            this.server = server;
            this.tool = tool;
            this.variant = variant;
            this.args = args;
        }

        Case calls(Call... more) {
            calls.addAll(List.of(more));
            return this;
        }

        Case says(String text) {
            summary = text;
            return this;
        }

        /** A JSON Pointer into the result content and the text it must hold. */
        Case has(String pointer, String value) {
            expect.put(pointer, value);
            return this;
        }

        /** A tool that answers without contacting the provider. */
        boolean offline() {
            return calls.isEmpty();
        }

        boolean idempotent() {
            return SandboxServerRegistry.definitions().get(server).stream()
                    .filter(tool -> tool.name().equals(this.tool))
                    .findFirst()
                    .orElseThrow()
                    .idempotent();
        }

        ToolInvocation invocation() {
            return ContractTable.invocation(server, tool, args);
        }

        @Override
        public String toString() {
            return server + "." + tool + (variant == null ? "" : " (" + variant + ")");
        }
    }

    @FunctionalInterface
    interface Factory {
        LiveServerAdapter make(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String base);
    }

    /** One adapter: how it is built, the credential, the header it must send, and its "who am I". */
    record Vendor(
            String server, String credential, String authorization, Factory factory, List<Call> whoAmI, String label) {

        LiveServerAdapter adapter(WebClient.Builder http, String base) {
            return factory.make(sandbox(server), new ObjectMapper(), http, base);
        }

        /** The webhook's credential is the address, which depends on the stand-in's port. */
        String credential(int port) {
            return credential.replace("{port}", Integer.toString(port));
        }

        boolean zoom() {
            return server.equals("zoom");
        }

        @Override
        public String toString() {
            return server;
        }
    }

    static Call GET(String path) {
        return new Call("GET", path);
    }

    static Call POST(String path) {
        return new Call("POST", path);
    }

    static Call PUT(String path) {
        return new Call("PUT", path);
    }

    static Call PATCH(String path) {
        return new Call("PATCH", path);
    }

    static Call DELETE(String path) {
        return new Call("DELETE", path);
    }

    private static Case tool(String server, String tool, String args) {
        return new Case(server, tool, null, args);
    }

    private static Case tool(String server, String tool, String variant, String args) {
        return new Case(server, tool, variant, args);
    }

    // ---- Vendors ------------------------------------------------------------------------------

    static final String ATLASSIAN = "{\"site\":\"acme\",\"email\":\"" + EMAIL + "\",\"token\":\"" + TOKEN + "\"}";
    static final String ZENDESK = "{\"subdomain\":\"acme\",\"email\":\"" + EMAIL + "\",\"token\":\"" + TOKEN + "\"}";
    static final String ZOOM =
            "{\"accountId\":\"acc1\",\"clientId\":\"client1\",\"clientSecret\":\"" + TOKEN + "\"}";
    static final String SALESFORCE =
            "{\"accessToken\":\"" + TOKEN + "\",\"instanceUrl\":\"https://acme.my.salesforce.com\"}";
    static final String BEARER = "Bearer " + TOKEN;

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    static final String GOOGLE_ABOUT = "{\"user\":{\"emailAddress\":\"me@acme.test\"}}";

    static final Map<String, Vendor> VENDORS = vendors();

    private static Map<String, Vendor> vendors() {
        Map<String, Vendor> vendors = new LinkedHashMap<>();
        add(vendors, new Vendor("github", TOKEN, BEARER, GitHubAdapter::new,
                List.of(GET("/user").answer("{\"login\":\"octo\"}")), "octo"));
        add(vendors, new Vendor("slack", TOKEN, BEARER, SlackAdapter::new,
                List.of(GET("/auth.test").answer("{\"ok\":true,\"user\":\"bot\",\"team\":\"Acme\"}")), "bot in Acme"));
        add(vendors, new Vendor("notion", TOKEN, BEARER, NotionAdapter::new,
                List.of(GET("/users/me").answer("{\"bot\":{\"workspace_name\":\"Acme HQ\"}}")), "Acme HQ"));
        add(vendors, new Vendor("linear", TOKEN, TOKEN, LinearAdapter::new,
                List.of(POST("/graphql").match("viewer")
                        .answer("{\"data\":{\"viewer\":{\"name\":\"Robin\"},\"organization\":{\"name\":\"Acme\"}}}")),
                "Robin (Acme)"));
        add(vendors, new Vendor("hubspot", TOKEN, BEARER, HubSpotAdapter::new,
                List.of(GET("/account-info/v3/details").answer("{\"portalId\":123}")), "HubSpot account 123"));
        add(vendors, new Vendor("asana", TOKEN, BEARER, AsanaAdapter::new,
                List.of(GET("/users/me").answer("{\"data\":{\"name\":\"Ana\",\"email\":\"ana@acme.test\"}}")),
                "Ana (ana@acme.test)"));
        add(vendors, new Vendor("stripe", TOKEN, BEARER, StripeAdapter::new,
                List.of(GET("/v1/balance").answer("{\"livemode\":false}")), "Stripe account (test mode)"));
        add(vendors, new Vendor("jira", ATLASSIAN, basic(EMAIL, TOKEN), JiraAdapter::new,
                List.of(GET("/rest/api/3/myself").answer("{\"displayName\":\"Jo\"}")), "Jo on acme.atlassian.net"));
        add(vendors, new Vendor("confluence", ATLASSIAN, basic(EMAIL, TOKEN), ConfluenceAdapter::new,
                List.of(GET("/wiki/rest/api/user/current").answer("{\"displayName\":\"Cy\"}")),
                "Cy on acme.atlassian.net"));
        add(vendors, new Vendor("zendesk", ZENDESK, basic(EMAIL + "/token", TOKEN), ZendeskAdapter::new,
                List.of(GET("/api/v2/users/me").answer("{\"user\":{\"name\":\"Zed\"}}")), "Zed on acme.zendesk.com"));
        add(vendors, new Vendor("zoom", ZOOM, "Bearer " + ZOOM_ACCESS,
                (sandbox, json, http, base) -> new ZoomAdapter(sandbox, json, http, base, base + "/oauth/token"),
                List.of(GET("/users/me").answer("{\"display_name\":\"Zo\",\"email\":\"zo@acme.test\"}")),
                "Zo (zo@acme.test)"));
        add(vendors, new Vendor("webhook", "http://localhost:{port}/hooks/in", null,
                (sandbox, json, http, base) -> new WebhookAdapter(sandbox, json, http, true), List.of(), "localhost"));
        add(vendors, new Vendor("gmail", TOKEN, BEARER, GmailAdapter::new,
                List.of(GET("/gmail/v1/users/me/profile").answer("{\"emailAddress\":\"me@acme.test\"}")),
                "me@acme.test"));
        add(vendors, new Vendor("calendar", TOKEN, BEARER,
                (sandbox, json, http, base) -> new CalendarAdapter(sandbox, json, http, base + "/calendar/v3"),
                List.of(GET("/calendar/v3/users/me/calendarList/primary").answer("{\"summary\":\"me@acme.test\"}")),
                "me@acme.test"));
        add(vendors, new Vendor("drive", TOKEN, BEARER, DriveAdapter::new,
                List.of(GET("/drive/v3/about").answer(GOOGLE_ABOUT)), "me@acme.test"));
        add(vendors, new Vendor("sheets", TOKEN, BEARER,
                (sandbox, json, http, base) -> new SheetsAdapter(sandbox, json, http, base, base),
                List.of(GET("/drive/v3/about").answer(GOOGLE_ABOUT)), "me@acme.test"));
        add(vendors, new Vendor("outlook", TOKEN, BEARER,
                (sandbox, json, http, base) -> new OutlookAdapter(sandbox, json, http, base + "/v1.0"),
                List.of(GET("/v1.0/me").answer("{\"mail\":\"me@contoso.test\"}")), "me@contoso.test"));
        add(vendors, new Vendor("teams", TOKEN, BEARER,
                (sandbox, json, http, base) -> new TeamsAdapter(sandbox, json, http, base + "/v1.0"),
                List.of(GET("/v1.0/me").answer("{\"mail\":\"me@contoso.test\"}")), "me@contoso.test"));
        add(vendors, new Vendor("salesforce", SALESFORCE, BEARER, SalesforceAdapter::new,
                List.of(
                        GET("/services/oauth2/userinfo").answer("{\"name\":\"Sam\"}"),
                        GET("/services/data/v60.0/query").answer("{\"records\":[{\"Name\":\"Acme\"}]}")),
                "Sam (Acme)"));
        return vendors;
    }

    private static void add(Map<String, Vendor> vendors, Vendor vendor) {
        vendors.put(vendor.server(), vendor);
    }

    static Vendor vendor(String server) {
        return VENDORS.get(server);
    }

    // ---- Tools --------------------------------------------------------------------------------

    static final List<Case> CASES = rows();

    static Stream<Case> cases() {
        return CASES.stream();
    }

    private static List<Case> rows() {
        List<Case> all = new ArrayList<>();

        // GitHub
        all.add(tool("github", "list_issues", "{\"repo\":\"acme/web\",\"state\":\"closed\"}")
                .calls(GET("/repos/acme/web/issues").q("state", "closed").q("per_page", "30")
                        .answer("[{\"number\":7,\"title\":\"Bug\",\"state\":\"closed\",\"user\":{\"login\":\"sam\"}},"
                                + "{\"number\":8,\"title\":\"A pull\",\"pull_request\":{}}]"))
                .says("Read 1 issue(s) from acme/web on GitHub.").has("/items/0/id", "7"));
        all.add(tool("github", "get_pulls", "{\"repo\":\"acme/web\"}")
                .calls(GET("/repos/acme/web/pulls").q("state", "open")
                        .answer("[{\"number\":3,\"title\":\"Feature\",\"user\":{\"login\":\"kim\"},"
                                + "\"head\":{\"ref\":\"feat\"},\"base\":{\"ref\":\"main\"}}]"))
                .says("Read 1 open pull request(s) from acme/web on GitHub.").has("/items/0/head", "feat"));
        all.add(tool("github", "create_issue", "{\"repo\":\"acme/web\",\"title\":\"Crash\",\"body\":\"Steps\"}")
                .calls(POST("/repos/acme/web/issues").json("{\"title\":\"Crash\",\"body\":\"Steps\"}")
                        .answer(201, "{\"number\":9,\"title\":\"Crash\",\"state\":\"open\"}"))
                .says("Opened issue #9 in acme/web on GitHub.").has("/id", "9"));
        all.add(tool("github", "update_issue", "{\"repo\":\"acme/web\",\"id\":\"#9\",\"state\":\"CLOSED\"}")
                .calls(PATCH("/repos/acme/web/issues/9").json("{\"state\":\"closed\"}")
                        .answer("{\"number\":9,\"title\":\"Crash\",\"state\":\"closed\"}"))
                .says("Updated issue #9 in acme/web on GitHub.").has("/state", "closed"));
        all.add(tool("github", "list_repos", "{\"limit\":5}")
                .calls(GET("/user/repos").q("sort", "updated").q("per_page", "5")
                        .answer("[{\"full_name\":\"octo/notes\",\"name\":\"notes\",\"owner\":{\"login\":\"octo\"},"
                                + "\"private\":true,\"default_branch\":\"main\"}]"))
                .says("Read 1 repositories from GitHub.").has("/items/0/id", "octo/notes").has("/items/0/private", "true"));
        all.add(tool("github", "list_repos", "an organisation", "{\"owner\":\"acme\"}")
                .calls(GET("/orgs/acme/repos").q("per_page", "30")
                        .answer("[{\"full_name\":\"acme/web\",\"name\":\"web\",\"owner\":{\"login\":\"acme\"}}]"))
                .says("Read 1 repositories from GitHub.").has("/items/0/owner", "acme"));
        all.add(tool("github", "create_repo", "{\"name\":\"test repo\",\"description\":\"Trial\"}")
                .calls(POST("/user/repos")
                        .json("{\"name\":\"test-repo\",\"description\":\"Trial\",\"private\":true,\"auto_init\":true}")
                        .answer(201, "{\"full_name\":\"octo/test-repo\",\"name\":\"test-repo\",\"private\":true,"
                                + "\"owner\":{\"login\":\"octo\"},\"default_branch\":\"main\"}"))
                .says("Created the private repository octo/test-repo on GitHub.").has("/id", "octo/test-repo"));
        all.add(tool("github", "create_repo", "public, in an organisation",
                        "{\"name\":\"site\",\"private\":false,\"owner\":\"acme\"}")
                .calls(POST("/orgs/acme/repos").json("{\"name\":\"site\",\"private\":false,\"auto_init\":true}")
                        .answer(201, "{\"full_name\":\"acme/site\",\"name\":\"site\",\"private\":false}"))
                .says("Created the public repository acme/site on GitHub."));
        all.add(tool("github", "list_branches", "{\"repo\":\"acme/web\"}")
                .calls(GET("/repos/acme/web/branches").q("per_page", "100")
                        .answer("[{\"name\":\"main\",\"commit\":{\"sha\":\"abc\"},\"protected\":true}]"))
                .says("Read 1 branch(es) from acme/web on GitHub.").has("/items/0/name", "main"));
        all.add(tool("github", "create_branch", "{\"repo\":\"acme/web\",\"branch\":\"feature/login\"}")
                .calls(
                        GET("/repos/acme/web").answer("{\"default_branch\":\"main\"}"),
                        GET("/repos/acme/web/git/ref/heads/main").answer("{\"object\":{\"sha\":\"abc123\"}}"),
                        POST("/repos/acme/web/git/refs").json("{\"ref\":\"refs/heads/feature/login\",\"sha\":\"abc123\"}")
                                .answer(201, "{\"ref\":\"refs/heads/feature/login\",\"object\":{\"sha\":\"abc123\"}}"))
                .says("Created the branch feature/login from main in acme/web on GitHub.").has("/sha", "abc123"));
        all.add(tool("github", "list_comments", "{\"repo\":\"acme/web\",\"issue\":\"#9\"}")
                .calls(GET("/repos/acme/web/issues/9/comments").q("per_page", "50")
                        .answer("[{\"id\":77,\"user\":{\"login\":\"kim\"},\"body\":\"Seen it too\"}]"))
                .says("Read 1 comment(s) on #9 in acme/web on GitHub.").has("/items/0/body", "Seen it too"));
        all.add(tool("github", "add_comment", "{\"repo\":\"acme/web\",\"issue\":\"9\",\"body\":\"Fixed in #12\"}")
                .calls(POST("/repos/acme/web/issues/9/comments").json("{\"body\":\"Fixed in #12\"}")
                        .answer(201, "{\"id\":78,\"user\":{\"login\":\"octo\"},\"body\":\"Fixed in #12\"}"))
                .says("Commented on #9 in acme/web on GitHub.").has("/id", "78"));
        all.add(tool("github", "get_pull", "{\"repo\":\"acme/web\",\"id\":\"3\"}")
                .calls(GET("/repos/acme/web/pulls/3")
                        .answer("{\"number\":3,\"title\":\"Feature\",\"state\":\"open\",\"mergeable\":true,"
                                + "\"head\":{\"ref\":\"feat\"},\"base\":{\"ref\":\"main\"}}"))
                .says("Read pull request #3 in acme/web on GitHub.").has("/mergeable", "true").has("/base", "main"));
        all.add(tool("github", "create_pull", "{\"repo\":\"acme/web\",\"title\":\"Login\",\"head\":\"feature/login\"}")
                .calls(
                        GET("/repos/acme/web").answer("{\"default_branch\":\"main\"}"),
                        POST("/repos/acme/web/pulls")
                                .json("{\"title\":\"Login\",\"head\":\"feature/login\",\"base\":\"main\"}")
                                .answer(201, "{\"number\":12,\"title\":\"Login\",\"state\":\"open\","
                                        + "\"head\":{\"ref\":\"feature/login\"},\"base\":{\"ref\":\"main\"}}"))
                .says("Opened pull request #12 in acme/web on GitHub.").has("/id", "12"));
        all.add(tool("github", "merge_pull", "{\"repo\":\"acme/web\",\"id\":\"#12\",\"method\":\"Squash\"}")
                .calls(PUT("/repos/acme/web/pulls/12/merge").json("{\"merge_method\":\"squash\"}")
                        .answer("{\"merged\":true,\"sha\":\"def456\",\"message\":\"Pull Request successfully merged\"}"))
                .says("Merged pull request #12 in acme/web on GitHub.").has("/merged", "true"));
        all.add(tool("github", "get_file", "{\"repo\":\"acme/web\",\"path\":\"/docs/read me.md\",\"ref\":\"dev\"}")
                .calls(GET("/repos/acme/web/contents/docs/read me.md").q("ref", "dev")
                        .answer("{\"type\":\"file\",\"path\":\"docs/read me.md\",\"size\":5,\"encoding\":\"base64\","
                                + "\"content\":\"SGVs\\nbG8=\\n\"}"))
                .says("Read docs/read me.md from acme/web on GitHub.").has("/items/0/content", "Hello"));
        all.add(tool("github", "get_file", "a folder", "{\"repo\":\"acme/web\",\"path\":\"docs\"}")
                .calls(GET("/repos/acme/web/contents/docs")
                        .answer("[{\"type\":\"file\",\"path\":\"docs/a.md\"},{\"type\":\"dir\",\"path\":\"docs/img\"}]"))
                .says("Listed 2 item(s) in docs in acme/web on GitHub.").has("/items/1/type", "dir"));
        all.add(tool("github", "save_file", "a new file",
                        "{\"repo\":\"acme/web\",\"path\":\"notes.md\",\"content\":\"Hi\",\"branch\":\"dev\"}")
                .calls(
                        GET("/repos/acme/web/contents/notes.md").q("ref", "dev").answer(404, "{\"message\":\"Not Found\"}"),
                        PUT("/repos/acme/web/contents/notes.md")
                                .json("{\"message\":\"Add notes.md\",\"content\":\"SGk=\",\"branch\":\"dev\"}")
                                .answer(201, "{\"content\":{\"path\":\"notes.md\",\"sha\":\"n1\"},\"commit\":{\"sha\":\"c1\"}}"))
                .says("Added notes.md in acme/web on the branch dev on GitHub.").has("/commit", "c1"));
        all.add(tool("github", "save_file", "{\"repo\":\"acme/web\",\"path\":\"notes.md\",\"content\":\"Hi\",\"message\":\"Edit\"}")
                .calls(
                        GET("/repos/acme/web/contents/notes.md").answer("{\"type\":\"file\",\"path\":\"notes.md\",\"sha\":\"old1\"}"),
                        PUT("/repos/acme/web/contents/notes.md")
                                .json("{\"message\":\"Edit\",\"content\":\"SGk=\",\"sha\":\"old1\"}")
                                .answer("{\"content\":{\"path\":\"notes.md\",\"sha\":\"n2\"},\"commit\":{\"sha\":\"c2\"}}"))
                .says("Updated notes.md in acme/web on GitHub.").has("/id", "notes.md"));

        // Slack
        String channels = "{\"ok\":true,\"channels\":[{\"id\":\"C0123456789\",\"name\":\"general\",\"num_members\":4,"
                + "\"is_member\":true}]}";
        all.add(tool("slack", "list_channels", "{}")
                .calls(GET("/conversations.list").q("limit", "200").q("exclude_archived", "true")
                        .q("types", "public_channel").answer(channels))
                .says("Read 1 channel(s) from Slack.").has("/items/0/name", "general"));
        all.add(tool("slack", "get_messages", "{\"channel\":\"#general\",\"limit\":5}")
                .calls(
                        GET("/conversations.list").answer(channels),
                        GET("/conversations.history").q("channel", "C0123456789").q("limit", "5")
                                .answer("{\"ok\":true,\"messages\":[{\"ts\":\"1.2\",\"user\":\"U1\",\"text\":\"hi\"}]}"))
                .says("Read 1 message(s) from #general in Slack.").has("/items/0/text", "hi"));
        all.add(tool("slack", "post_message", "{\"channel\":\"#general\",\"text\":\"Hello\"}")
                .calls(POST("/chat.postMessage").json("{\"channel\":\"general\",\"text\":\"Hello\"}")
                        .answer("{\"ok\":true,\"ts\":\"1.3\",\"channel\":\"C0123456789\"}"))
                .says("Posted a message to #general in Slack.").has("/id", "1.3"));

        // Notion
        String page = "{\"id\":\"" + NOTION_UUID + "\",\"url\":\"https://notion.test/p\",\"properties\":"
                + "{\"Name\":{\"type\":\"title\",\"title\":[{\"plain_text\":\"Plan\"}]}}}";
        all.add(tool("notion", "search_pages", "{\"query\":\"Plan\",\"limit\":5}")
                .calls(POST("/search")
                        .json("{\"query\":\"Plan\",\"filter\":{\"property\":\"object\",\"value\":\"page\"},\"page_size\":5}")
                        .answer("{\"results\":[" + page + "]}"))
                .says("Found 1 page(s) in Notion.").has("/items/0/title", "Plan"));
        all.add(tool("notion", "get_page", "{\"id\":\"https://notion.so/Plan-" + NOTION_ID + "\"}")
                .calls(
                        GET("/pages/" + NOTION_UUID).answer(page),
                        GET("/blocks/" + NOTION_UUID + "/children").q("page_size", "100")
                                .answer("{\"results\":[{\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":"
                                        + "[{\"plain_text\":\"Hello\"}]}},{\"type\":\"to_do\",\"to_do\":{\"rich_text\":"
                                        + "[{\"plain_text\":\"Ship\"}]}}]}"))
                .says("Read the Notion page \"Plan\".").has("/content", "Hello\n- Ship"));
        all.add(tool("notion", "create_page",
                        "{\"parentId\":\"" + NOTION_ID + "\",\"title\":\"New\",\"content\":\"Body\"}")
                .calls(POST("/pages")
                        .json("{\"parent\":{\"page_id\":\"" + NOTION_UUID + "\"},\"properties\":{\"title\":{\"title\":"
                                + "[{\"type\":\"text\",\"text\":{\"content\":\"New\"}}]}},\"children\":[{\"object\":\"block\","
                                + "\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":[{\"type\":\"text\",\"text\":"
                                + "{\"content\":\"Body\"}}]}}]}")
                        .answer(page))
                .says("Created the Notion page \"New\".").has("/id", NOTION_UUID));
        all.add(tool("notion", "update_page", "{\"id\":\"" + NOTION_ID + "\",\"content\":\"More\"}")
                .calls(PATCH("/blocks/" + NOTION_UUID + "/children")
                        .json("{\"children\":[{\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":[{\"text\":"
                                + "{\"content\":\"More\"}}]}}]}")
                        .answer("{\"results\":[{\"id\":\"b1\"}]}"))
                .says("Added text to the end of a Notion page.").has("/blocksAdded", "1"));
        all.add(tool("notion", "archive_page", "{\"id\":\"" + NOTION_ID + "\"}")
                .calls(PATCH("/pages/" + NOTION_UUID).json("{\"archived\":true}").answer(page))
                .says("Moved the Notion page \"Plan\" to the trash.").has("/archived", "true"));

        // Linear
        String issue = "{\"id\":\"u1\",\"identifier\":\"ENG-1\",\"title\":\"Crash\",\"state\":{\"name\":\"Todo\"},"
                + "\"team\":{\"key\":\"ENG\"},\"priority\":2}";
        all.add(tool("linear", "list_issues", "{\"query\":\"crash\",\"state\":\"Todo\",\"team\":\"ENG\",\"limit\":5}")
                .calls(POST("/graphql").match("issues(first")
                        .json("{\"variables\":{\"first\":5,\"filter\":{\"title\":{\"containsIgnoreCase\":\"crash\"},"
                                + "\"state\":{\"name\":{\"eqIgnoreCase\":\"Todo\"}},\"team\":{\"key\":{\"eqIgnoreCase\":\"ENG\"}}}}}")
                        .answer("{\"data\":{\"issues\":{\"nodes\":[" + issue + "]}}}"))
                .says("Read 1 issue(s) from Linear.").has("/items/0/id", "ENG-1"));
        all.add(tool("linear", "get_issue", "{\"id\":\"ENG-1\"}")
                .calls(POST("/graphql").match("issue(id").json("{\"variables\":{\"id\":\"ENG-1\"}}")
                        .answer("{\"data\":{\"issue\":" + issue + "}}"))
                .says("Read ENG-1 from Linear.").has("/state", "Todo"));
        all.add(tool("linear", "create_issue", "{\"title\":\"Crash\",\"team\":\"ENG\",\"priority\":2}")
                .calls(
                        POST("/graphql").match("teams(first")
                                .answer("{\"data\":{\"teams\":{\"nodes\":[{\"id\":\"team-1\",\"key\":\"ENG\",\"name\":\"Engineering\"}]}}}"),
                        POST("/graphql").match("issueCreate")
                                .json("{\"variables\":{\"input\":{\"teamId\":\"team-1\",\"title\":\"Crash\",\"priority\":2}}}")
                                .answer("{\"data\":{\"issueCreate\":{\"success\":true,\"issue\":" + issue + "}}}"))
                .says("Created ENG-1 in Linear.").has("/team", "ENG"));
        all.add(tool("linear", "update_issue", "{\"id\":\"ENG-1\",\"state\":\"Done\",\"title\":\"Fixed\"}")
                .calls(
                        POST("/graphql").match("issue(id")
                                .answer("{\"data\":{\"issue\":{\"id\":\"u1\",\"identifier\":\"ENG-1\",\"team\":{\"states\":"
                                        + "{\"nodes\":[{\"id\":\"s-todo\",\"name\":\"Todo\"},{\"id\":\"s-done\",\"name\":\"Done\"}]}}}}}"),
                        POST("/graphql").match("issueUpdate")
                                .json("{\"variables\":{\"id\":\"u1\",\"input\":{\"title\":\"Fixed\",\"stateId\":\"s-done\"}}}")
                                .answer("{\"data\":{\"issueUpdate\":{\"success\":true,\"issue\":" + issue + "}}}"))
                .says("Updated ENG-1 in Linear.").has("/id", "ENG-1"));

        // HubSpot
        String contact = "{\"id\":\"1\",\"properties\":{\"email\":\"a@acme.test\",\"firstname\":\"A\"}}";
        all.add(tool("hubspot", "list_contacts", "{\"limit\":5}")
                .calls(GET("/crm/v3/objects/contacts").q("limit", "5")
                        .q("properties", "firstname,lastname,email,company,phone,lifecyclestage")
                        .answer("{\"results\":[" + contact + "]}"))
                .says("Read 1 contact(s) in HubSpot.").has("/items/0/email", "a@acme.test"));
        all.add(tool("hubspot", "search_contacts", "{\"query\":\"acme\"}")
                .calls(POST("/crm/v3/objects/contacts/search").json("{\"query\":\"acme\",\"limit\":20}")
                        .answer("{\"results\":[" + contact + "]}"))
                .says("Found 1 contact(s) in HubSpot.").has("/items/0/firstName", "A"));
        all.add(tool("hubspot", "create_contact", "{\"email\":\"new@acme.test\",\"firstName\":\"N\",\"company\":\"Acme\"}")
                .calls(POST("/crm/v3/objects/contacts")
                        .json("{\"properties\":{\"email\":\"new@acme.test\",\"firstname\":\"N\",\"company\":\"Acme\"}}")
                        .answer(201, "{\"id\":\"5\",\"properties\":{\"email\":\"new@acme.test\"}}"))
                .says("Added new@acme.test to HubSpot.").has("/id", "5"));
        all.add(tool("hubspot", "list_deals", "{}")
                .calls(GET("/crm/v3/objects/deals").q("limit", "20")
                        .q("properties", "dealname,amount,dealstage,closedate,pipeline")
                        .answer("{\"results\":[{\"id\":\"d1\",\"properties\":{\"dealname\":\"Big\",\"dealstage\":\"won\"}}]}"))
                .says("Read 1 deal(s) from HubSpot.").has("/items/0/name", "Big"));

        // Asana
        String fields = "name,completed,due_on,assignee.name,permalink_url";
        all.add(tool("asana", "list_tasks", "by project", "{\"project\":\"p1\",\"completed\":false}")
                .calls(GET("/tasks").q("project", "p1").q("limit", "25").q("opt_fields", fields)
                        .answer("{\"data\":[{\"gid\":\"t1\",\"name\":\"A\",\"completed\":false},"
                                + "{\"gid\":\"t2\",\"name\":\"B\",\"completed\":true}]}"))
                .says("Read 1 task(s) from Asana.").has("/items/0/id", "t1"));
        all.add(tool("asana", "list_tasks", "mine", "{}")
                .calls(
                        GET("/users/me").answer("{\"data\":{\"workspaces\":[{\"gid\":\"w1\"}]}}"),
                        GET("/tasks").q("assignee", "me").q("workspace", "w1")
                                .answer("{\"data\":[{\"gid\":\"t1\",\"name\":\"A\"}]}"))
                .says("Read 1 task(s) from Asana."));
        all.add(tool("asana", "get_task", "{\"id\":\"t1\"}")
                .calls(GET("/tasks/t1").q("opt_fields", fields + ",notes,projects.name")
                        .answer("{\"data\":{\"gid\":\"t1\",\"name\":\"A\",\"notes\":\"N\",\"projects\":[{\"name\":\"P\"}]}}"))
                .says("Read the Asana task \"A\".").has("/notes", "N").has("/projects/0", "P"));
        all.add(tool("asana", "create_task", "{\"name\":\"A\",\"project\":\"p1\",\"dueOn\":\"2026-10-10\"}")
                .calls(POST("/tasks").json("{\"data\":{\"name\":\"A\",\"projects\":[\"p1\"],\"due_on\":\"2026-10-10\"}}")
                        .answer(201, "{\"data\":{\"gid\":\"t9\",\"name\":\"A\"}}"))
                .says("Created the Asana task \"A\".").has("/id", "t9"));
        all.add(tool("asana", "update_task", "{\"id\":\"t1\",\"completed\":true}")
                .calls(PUT("/tasks/t1").json("{\"data\":{\"completed\":true}}")
                        .answer("{\"data\":{\"gid\":\"t1\",\"name\":\"A\",\"completed\":true}}"))
                .says("Updated the Asana task \"A\".").has("/status", "complete"));
        all.add(tool("asana", "delete_task", "{\"id\":\"t1\"}")
                .calls(DELETE("/tasks/t1").answer("{\"data\":{}}"))
                .says("Deleted an Asana task.").has("/deleted", "true"));

        // Stripe
        all.add(tool("stripe", "search_customers", "{\"query\":\"ann@x.test\"}")
                .calls(GET("/v1/customers/search").q("query", "email:\"ann@x.test\" OR name~\"ann@x.test\"").q("limit", "10")
                        .answer("{\"data\":[{\"id\":\"cus_1\",\"email\":\"ann@x.test\",\"created\":1700000000}]}"))
                .says("Found 1 customer(s) in Stripe.").has("/items/0/createdAt", "2023-11-14T22:13:20Z"));
        all.add(tool("stripe", "list_payments", "{\"status\":\"succeeded\",\"limit\":1}")
                .calls(GET("/v1/payment_intents").q("limit", "100")
                        .answer("{\"data\":[{\"id\":\"pi_0\",\"status\":\"canceled\"},{\"id\":\"pi_1\",\"amount\":1250,"
                                + "\"currency\":\"usd\",\"status\":\"succeeded\"},{\"id\":\"pi_2\",\"status\":\"succeeded\"}]}"))
                .says("Read 1 payment(s) from Stripe.").has("/items/0/amount", "12.5"));
        all.add(tool("stripe", "get_payment", "{\"id\":\"pi_1\"}")
                .calls(GET("/v1/payment_intents/pi_1").answer("{\"id\":\"pi_1\",\"amount\":1250,\"currency\":\"usd\"}"))
                .says("Read payment pi_1 from Stripe.").has("/amount", "12.5"));
        all.add(tool("stripe", "list_invoices", "{\"customer\":\"cus_1\",\"status\":\"OPEN\"}")
                .calls(GET("/v1/invoices").q("limit", "10").q("customer", "cus_1").q("status", "open")
                        .answer("{\"data\":[{\"id\":\"in_1\",\"number\":\"INV-1\",\"total\":5000,\"currency\":\"jpy\","
                                + "\"status\":\"open\"}]}"))
                .says("Read 1 invoice(s) from Stripe.").has("/items/0/amount", "5000"));
        all.add(tool("stripe", "refund_payment", "{\"id\":\"pi_1\",\"amount\":\"5.25\",\"reason\":\"duplicate\"}")
                .calls(
                        GET("/v1/payment_intents/pi_1").answer("{\"id\":\"pi_1\",\"currency\":\"usd\"}"),
                        POST("/v1/refunds").header("Idempotency-Key", "run-1:call-1")
                                .contains("payment_intent=pi_1", "amount=525", "reason=duplicate")
                                .answer("{\"id\":\"re_1\",\"status\":\"succeeded\",\"amount\":525,\"currency\":\"usd\","
                                        + "\"payment_intent\":\"pi_1\"}"))
                .says("Refunded 5.25 USD on payment pi_1 in Stripe.").has("/id", "re_1"));

        // Jira
        all.add(tool("jira", "search_issues", "{\"jql\":\"project = ENG\",\"limit\":5}")
                .calls(GET("/rest/api/3/search/jql").q("jql", "project = ENG").q("maxResults", "5")
                        .q("fields", "summary,status,issuetype,assignee,priority,updated")
                        .answer("{\"issues\":[{\"key\":\"ENG-1\",\"fields\":{\"summary\":\"Bug\",\"status\":{\"name\":\"To Do\"}}}]}"))
                .says("Found 1 issue(s) in Jira.").has("/items/0/url", "https://acme.atlassian.net/browse/ENG-1"));
        all.add(tool("jira", "create_issue", "{\"project\":\"ENG\",\"summary\":\"Bug\",\"description\":\"Steps\"}")
                .calls(POST("/rest/api/3/issue")
                        .json("{\"fields\":{\"project\":{\"key\":\"ENG\"},\"summary\":\"Bug\",\"issuetype\":{\"name\":\"Task\"},"
                                + "\"description\":{\"type\":\"doc\",\"version\":1,\"content\":[{\"type\":\"paragraph\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"Steps\"}]}]}}}")
                        .answer(201, "{\"key\":\"ENG-2\"}"))
                .says("Created ENG-2 in Jira.").has("/url", "https://acme.atlassian.net/browse/ENG-2"));
        all.add(tool("jira", "update_issue", "{\"id\":\"ENG-1\",\"status\":\"Done\"}")
                .calls(
                        GET("/rest/api/3/issue/ENG-1/transitions")
                                .answer("{\"transitions\":[{\"id\":\"21\",\"name\":\"Start\",\"to\":{\"name\":\"In Progress\"}},"
                                        + "{\"id\":\"31\",\"name\":\"Finish\",\"to\":{\"name\":\"Done\"}}]}"),
                        POST("/rest/api/3/issue/ENG-1/transitions").json("{\"transition\":{\"id\":\"31\"}}").empty(204))
                .says("Moved ENG-1 to Done in Jira.").has("/status", "Done"));

        // Confluence
        String current = "{\"id\":\"11\",\"title\":\"Plan\",\"status\":\"current\",\"version\":{\"number\":3},"
                + "\"body\":{\"storage\":{\"value\":\"<p>Hi &amp; bye</p>\"}},\"_links\":{\"webui\":\"/spaces/OPS/pages/11\"}}";
        all.add(tool("confluence", "search_pages", "{\"query\":\"plan\",\"space\":\"OPS\"}")
                .calls(GET("/wiki/rest/api/search").q("cql", "type = page and text ~ \"plan\" and space = \"OPS\"")
                        .q("limit", "10")
                        .answer("{\"results\":[{\"title\":\"Plan\",\"content\":{\"id\":\"11\",\"_links\":"
                                + "{\"webui\":\"/spaces/OPS/pages/11\"}}}]}"))
                .says("Found 1 page(s) in Confluence.")
                .has("/items/0/url", "https://acme.atlassian.net/wiki/spaces/OPS/pages/11"));
        all.add(tool("confluence", "get_page", "{\"id\":\"11\"}")
                .calls(GET("/wiki/api/v2/pages/11").q("body-format", "storage").answer(current))
                .says("Read the Confluence page \"Plan\".").has("/content", "Hi & bye").has("/version", "3"));
        all.add(tool("confluence", "create_page", "{\"space\":\"OPS\",\"title\":\"New\",\"content\":\"a < b\"}")
                .calls(
                        GET("/wiki/api/v2/spaces").q("keys", "OPS").answer("{\"results\":[{\"id\":\"99\"}]}"),
                        POST("/wiki/api/v2/pages")
                                .json("{\"spaceId\":\"99\",\"status\":\"current\",\"title\":\"New\",\"body\":"
                                        + "{\"representation\":\"storage\",\"value\":\"<p>a &lt; b</p>\"}}")
                                .answer("{\"id\":\"12\",\"title\":\"New\"}"))
                .says("Created the Confluence page \"New\".").has("/id", "12"));
        all.add(tool("confluence", "update_page", "{\"id\":\"11\",\"content\":\"New text\"}")
                .calls(
                        GET("/wiki/api/v2/pages/11").answer(current),
                        PUT("/wiki/api/v2/pages/11")
                                .json("{\"id\":\"11\",\"title\":\"Plan\",\"version\":{\"number\":4},"
                                        + "\"body\":{\"value\":\"<p>New text</p>\"}}")
                                .answer("{\"id\":\"11\",\"title\":\"Plan\",\"version\":{\"number\":4}}"))
                .says("Updated the Confluence page \"Plan\".").has("/version", "4"));

        // Zendesk
        String ticket = "{\"id\":1,\"subject\":\"Help\",\"status\":\"open\"}";
        all.add(tool("zendesk", "list_tickets", "by status", "{\"status\":\"Open\",\"limit\":5}")
                .calls(GET("/api/v2/search").q("query", "type:ticket status:open").q("per_page", "5")
                        .answer("{\"results\":[" + ticket + "]}"))
                .says("Read 1 ticket(s) from Zendesk.").has("/items/0/title", "Help"));
        all.add(tool("zendesk", "list_tickets", "recent", "{}")
                .calls(GET("/api/v2/tickets").q("per_page", "25").q("sort_by", "updated_at")
                        .answer("{\"tickets\":[" + ticket + "]}"))
                .says("Read 1 ticket(s) from Zendesk."));
        all.add(tool("zendesk", "get_ticket", "{\"id\":\"1\"}")
                .calls(
                        GET("/api/v2/tickets/1").answer("{\"ticket\":{\"id\":1,\"subject\":\"Help\",\"description\":\"D\"}}"),
                        GET("/api/v2/tickets/1/comments")
                                .answer("{\"comments\":[{\"author_id\":7,\"public\":false,\"plain_body\":\"note\"}]}"))
                .says("Read ticket 1 from Zendesk.").has("/comments/0/body", "note").has("/description", "D"));
        all.add(tool("zendesk", "update_ticket", "{\"id\":\"1\",\"status\":\"Solved\",\"assignee\":\"42\"}")
                .calls(PUT("/api/v2/tickets/1").json("{\"ticket\":{\"status\":\"solved\",\"assignee_id\":42}}")
                        .answer("{\"ticket\":{\"id\":1,\"status\":\"solved\"}}"))
                .says("Updated ticket 1 in Zendesk.").has("/status", "solved"));
        all.add(tool("zendesk", "add_note", "{\"ticketId\":\"1\",\"body\":\"Internal\"}")
                .calls(PUT("/api/v2/tickets/1").json("{\"ticket\":{\"comment\":{\"body\":\"Internal\",\"public\":false}}}")
                        .answer("{\"ticket\":" + ticket + "}"))
                .says("Added a private note to ticket 1 in Zendesk.").has("/public", "false"));
        all.add(tool("zendesk", "send_reply", "{\"ticketId\":\"1\",\"body\":\"Hi\"}")
                .calls(PUT("/api/v2/tickets/1").json("{\"ticket\":{\"comment\":{\"body\":\"Hi\",\"public\":true}}}")
                        .answer("{\"ticket\":" + ticket + "}"))
                .says("Sent a public reply on ticket 1 in Zendesk.").has("/public", "true"));

        // Zoom
        all.add(tool("zoom", "list_meetings", "{\"from\":\"2026-10-01\",\"to\":\"2026-10-31\",\"limit\":5}")
                .calls(GET("/users/me/meetings").q("type", "upcoming").q("page_size", "5").q("from", "2026-10-01")
                        .q("to", "2026-10-31")
                        .answer("{\"meetings\":[{\"id\":81,\"topic\":\"Standup\",\"start_url\":\"https://zoom.test/s/host-only\"}]}"))
                .says("Read 1 Zoom meeting(s).").has("/items/0/title", "Standup"));
        all.add(tool("zoom", "get_meeting", "{\"id\":\"81\"}")
                .calls(GET("/meetings/81").answer("{\"id\":81,\"topic\":\"Standup\",\"agenda\":\"A\"}"))
                .says("Read the Zoom meeting \"Standup\".").has("/agenda", "A"));
        all.add(tool("zoom", "schedule_meeting",
                        "{\"topic\":\"Sync\",\"start\":\"2026-10-10T09:00:00Z\",\"durationMinutes\":45}")
                .calls(POST("/users/me/meetings")
                        .json("{\"topic\":\"Sync\",\"type\":2,\"start_time\":\"2026-10-10T09:00:00Z\",\"duration\":45}")
                        .answer(201, "{\"id\":82,\"topic\":\"Sync\",\"join_url\":\"https://zoom.test/j/82\"}"))
                .says("Scheduled the Zoom meeting \"Sync\".").has("/join_url", "https://zoom.test/j/82"));
        all.add(tool("zoom", "cancel_meeting", "{\"id\":\"82\"}")
                .calls(DELETE("/meetings/82").empty(204))
                .says("Cancelled Zoom meeting 82.").has("/cancelled", "true"));
        all.add(tool("zoom", "list_recordings", "{\"from\":\"2026-10-01\"}")
                .calls(GET("/users/me/recordings").q("page_size", "25").q("from", "2026-10-01")
                        .answer("{\"meetings\":[{\"id\":\"r1\",\"topic\":\"Standup\",\"recording_files\":[{},{}]}]}"))
                .says("Read 1 Zoom recording(s).").has("/items/0/files", "2"));

        // Webhook
        all.add(tool("webhook", "send_event", "{\"event\":\"order.shipped\",\"data\":{\"order\":\"SO-1\"}}")
                .calls(POST("/hooks/in").header("Idempotency-Key", "run-1:call-1")
                        .json("{\"event\":\"order.shipped\",\"data\":{\"order\":\"SO-1\"},\"source\":\"ai-workforce-os\"}")
                        .empty(202))
                .says("Sent the order.shipped event to localhost.").has("/httpStatus", "202"));
        all.add(tool("webhook", "list_events", "{}").says("Listed 0 event(s)").has("/count", "0"));

        // Gmail
        String data = Base64.getUrlEncoder().withoutPadding().encodeToString("Body text".getBytes(StandardCharsets.UTF_8));
        all.add(tool("gmail", "list_messages", "{\"query\":\"from:boss\",\"limit\":2}")
                .calls(
                        GET("/gmail/v1/users/me/messages").q("maxResults", "2").q("q", "from:boss")
                                .answer("{\"messages\":[{\"id\":\"m1\"}]}"),
                        GET("/gmail/v1/users/me/messages/m1").q("format", "metadata")
                                .answer("{\"id\":\"m1\",\"snippet\":\"S\",\"payload\":{\"headers\":"
                                        + "[{\"name\":\"Subject\",\"value\":\"Hi\"}]}}"))
                .says("Read 1 message(s) from Gmail.").has("/items/0/subject", "Hi"));
        all.add(tool("gmail", "get_message", "{\"id\":\"m1\"}")
                .calls(GET("/gmail/v1/users/me/messages/m1").q("format", "full")
                        .answer("{\"id\":\"m1\",\"payload\":{\"mimeType\":\"multipart/alternative\",\"headers\":"
                                + "[{\"name\":\"Subject\",\"value\":\"Hi\"}],\"parts\":[{\"mimeType\":\"text/plain\","
                                + "\"body\":{\"data\":\"" + data + "\"}}]}}"))
                .says("Read the Gmail message \"Hi\".").has("/body", "Body text"));
        all.add(tool("gmail", "draft_message", "{\"to\":\"a@b.test\",\"subject\":\"S\",\"body\":\"B\"}")
                .calls(POST("/gmail/v1/users/me/drafts").jsonPath("$.message.raw").answer("{\"id\":\"d1\"}"))
                .says("Saved a draft to a@b.test in Gmail.").has("/id", "d1"));
        all.add(tool("gmail", "send_message", "{\"to\":\"a@b.test\",\"subject\":\"S\",\"body\":\"B\"}")
                .calls(POST("/gmail/v1/users/me/messages/send").jsonPath("$.raw")
                        .answer("{\"id\":\"s1\",\"threadId\":\"t1\"}"))
                .says("Sent an email to a@b.test from Gmail.").has("/threadId", "t1"));

        // Google Calendar
        all.add(tool("calendar", "list_events", "{\"from\":\"2026-10-01\",\"to\":\"2026-10-02\"}")
                .calls(GET("/calendar/v3/calendars/primary/events").q("timeMin", "2026-10-01T00:00:00Z")
                        .q("timeMax", "2026-10-02T23:59:59Z").q("singleEvents", "true").q("orderBy", "startTime")
                        .answer("{\"items\":[{\"id\":\"e1\",\"summary\":\"Lunch\",\"start\":{\"dateTime\":\"2026-10-01T12:00:00Z\"}}]}"))
                .says("Read 1 event(s) from Google Calendar.").has("/items/0/start", "2026-10-01T12:00:00Z"));
        all.add(tool("calendar", "create_event",
                        "{\"title\":\"Lunch\",\"start\":\"2026-10-01T12:00:00\",\"end\":\"2026-10-01\",\"attendees\":[\"a@b.test\"]}")
                .calls(POST("/calendar/v3/calendars/primary/events")
                        .json("{\"summary\":\"Lunch\",\"start\":{\"dateTime\":\"2026-10-01T12:00:00Z\"},"
                                + "\"end\":{\"date\":\"2026-10-01\"},\"attendees\":[{\"email\":\"a@b.test\"}]}")
                        .answer("{\"id\":\"e2\",\"summary\":\"Lunch\",\"attendees\":[{\"email\":\"a@b.test\"}]}"))
                .says("Created \"Lunch\" in Google Calendar.").has("/attendees/0", "a@b.test"));
        all.add(tool("calendar", "delete_event", "{\"id\":\"e2\"}")
                .calls(DELETE("/calendar/v3/calendars/primary/events/e2").empty(204))
                .says("Deleted the event from Google Calendar.").has("/deleted", "true"));

        // Google Drive
        all.add(tool("drive", "list_files", "{\"folderId\":\"f'1\"}")
                .calls(GET("/drive/v3/files").q("q", "'f\\'1' in parents and trashed=false").q("pageSize", "20")
                        .answer("{\"files\":[{\"id\":\"x\",\"name\":\"Notes\",\"mimeType\":\"text/plain\",\"parents\":[\"f'1\"]}]}"))
                .says("Read 1 file(s) from Google Drive.").has("/items/0/folderId", "f'1"));
        all.add(tool("drive", "get_file", "{\"id\":\"doc1\"}")
                .calls(
                        GET("/drive/v3/files/doc1").q("fields", "id,name,mimeType")
                                .answer("{\"id\":\"doc1\",\"name\":\"Plan\",\"mimeType\":\"application/vnd.google-apps.document\"}"),
                        GET("/drive/v3/files/doc1/export").q("mimeType", "text/plain").text("Hello"))
                .says("Read \"Plan\" from Google Drive.").has("/content", "Hello"));
        all.add(tool("drive", "create_file", "{\"name\":\"n.txt\",\"content\":\"Hi there\"}")
                .calls(POST("/upload/drive/v3/files").q("uploadType", "multipart")
                        .contains("{\"name\":\"n.txt\"}", "Hi there")
                        .answer("{\"id\":\"new\",\"name\":\"n.txt\",\"mimeType\":\"text/plain\"}"))
                .says("Created \"n.txt\" in Google Drive.").has("/id", "new"));

        // Google Sheets
        all.add(tool("sheets", "list_spreadsheets", "{}")
                .calls(GET("/drive/v3/files").q("q", "mimeType='application/vnd.google-apps.spreadsheet' and trashed=false")
                        .answer("{\"files\":[{\"id\":\"s1\",\"name\":\"Deals\"}]}"))
                .says("Found 1 spreadsheet(s) in Google Sheets.").has("/items/0/name", "Deals"));
        all.add(tool("sheets", "list_rows", "{\"spreadsheetId\":\"s1\",\"sheet\":\"Deals\",\"limit\":10}")
                .calls(GET("/v4/spreadsheets/s1/values/'Deals'!A1:ZZ10").answer("{\"values\":[[\"a\",\"b\"]]}"))
                .says("Read 1 row(s) from \"Deals\".").has("/items/0/id", "s1:'Deals'!A1:B1"));
        all.add(tool("sheets", "append_row", "{\"spreadsheetId\":\"s1\",\"values\":[\"x\",1]}")
                .calls(
                        GET("/v4/spreadsheets/s1").q("fields", "sheets.properties.title")
                                .answer("{\"sheets\":[{\"properties\":{\"title\":\"Deals\"}}]}"),
                        POST("/v4/spreadsheets/s1/values/'Deals':append").q("valueInputOption", "USER_ENTERED")
                                .q("insertDataOption", "INSERT_ROWS")
                                .json("{\"majorDimension\":\"ROWS\",\"values\":[[\"x\",\"1\"]]}")
                                .answer("{\"updates\":{\"updatedRange\":\"Deals!A2:B2\",\"updatedRows\":1}}"))
                .says("Added a row to \"Deals\" in Google Sheets.").has("/updatedRange", "Deals!A2:B2"));
        all.add(tool("sheets", "update_row", "{\"id\":\"s1:'Deals'!A2:B2\",\"values\":[\"y\",\"2\"]}")
                .calls(PUT("/v4/spreadsheets/s1/values/'Deals'!A2:B2").q("valueInputOption", "USER_ENTERED")
                        .json("{\"range\":\"'Deals'!A2:B2\",\"values\":[[\"y\",\"2\"]]}")
                        .answer("{\"updatedRange\":\"Deals!A2:B2\",\"updatedCells\":2}"))
                .says("Updated a row in Google Sheets.").has("/updatedCells", "2"));

        // Outlook
        all.add(tool("outlook", "list_messages", "{\"limit\":3}")
                .calls(GET("/v1.0/me/messages").q("$top", "3").q("$orderby", "receivedDateTime desc")
                        .answer("{\"value\":[{\"id\":\"o1\",\"subject\":\"Hi\",\"from\":{\"emailAddress\":"
                                + "{\"address\":\"a@b.test\"}}}]}"))
                .says("Read 1 message(s) from Outlook.").has("/items/0/from", "a@b.test"));
        all.add(tool("outlook", "get_message", "{\"id\":\"o1\"}")
                .calls(GET("/v1.0/me/messages/o1")
                        .answer("{\"id\":\"o1\",\"subject\":\"Hi\",\"body\":{\"contentType\":\"html\",\"content\":\"<p>Hello</p>\"}}"))
                .says("Read the Outlook message \"Hi\".").has("/body", "Hello"));
        all.add(tool("outlook", "draft_message", "{\"to\":\"a@b.test; c@d.test\",\"subject\":\"S\",\"body\":\"B\"}")
                .calls(POST("/v1.0/me/messages")
                        .json("{\"subject\":\"S\",\"body\":{\"contentType\":\"Text\",\"content\":\"B\"},\"toRecipients\":"
                                + "[{\"emailAddress\":{\"address\":\"a@b.test\"}},{\"emailAddress\":{\"address\":\"c@d.test\"}}]}")
                        .answer(201, "{\"id\":\"dr1\"}"))
                .says("Saved a draft to a@b.test; c@d.test in Outlook.").has("/id", "dr1"));
        all.add(tool("outlook", "send_message", "{\"to\":\"a@b.test\",\"subject\":\"S\",\"body\":\"B\"}")
                .calls(POST("/v1.0/me/sendMail")
                        .json("{\"message\":{\"subject\":\"S\",\"toRecipients\":[{\"emailAddress\":{\"address\":\"a@b.test\"}}]},"
                                + "\"saveToSentItems\":true}")
                        .empty(202))
                .says("Sent an email to a@b.test from Outlook.").has("/sent", "true"));
        all.add(tool("outlook", "delete_message", "{\"id\":\"o1\"}")
                .calls(DELETE("/v1.0/me/messages/o1").empty(204))
                .says("Deleted the message from Outlook.").has("/deleted", "true"));
        all.add(tool("outlook", "list_events", "{\"from\":\"2026-10-01\",\"to\":\"2026-10-07\"}")
                .calls(GET("/v1.0/me/calendarView").q("startDateTime", "2026-10-01T00:00:00Z")
                        .q("endDateTime", "2026-10-07T23:59:59Z").q("$top", "50")
                        .answer("{\"value\":[{\"id\":\"ev1\",\"subject\":\"Lunch\",\"start\":{\"dateTime\":"
                                + "\"2026-10-01T12:00:00.0000000\",\"timeZone\":\"UTC\"}}]}"))
                .says("Read 1 event(s) from Outlook.").has("/items/0/start", "2026-10-01T12:00:00.0000000Z"));

        // Microsoft Teams
        String joined = "{\"value\":[{\"id\":\"t1\",\"displayName\":\"Ops\"}]}";
        String teamChannels = "{\"value\":[{\"id\":\"19:c1@thread.tacv2\",\"displayName\":\"General\"}]}";
        all.add(tool("teams", "list_channels", "{}")
                .calls(GET("/v1.0/me/joinedTeams").answer(joined), GET("/v1.0/teams/t1/channels").answer(teamChannels))
                .says("Found 1 channel(s) in Microsoft Teams.").has("/items/0/team", "Ops"));
        all.add(tool("teams", "get_messages", "{\"channel\":\"t1/19:c1@thread.tacv2\",\"limit\":5}")
                .calls(GET("/v1.0/teams/t1/channels/19:c1@thread.tacv2/messages").q("$top", "5")
                        .answer("{\"value\":[{\"id\":\"m1\",\"from\":{\"user\":{\"displayName\":\"Jo\"}},"
                                + "\"body\":{\"contentType\":\"text\",\"content\":\"hi\"}}]}"))
                .says("Read 1 message(s) from Teams.").has("/items/0/from", "Jo"));
        all.add(tool("teams", "post_message", "{\"channel\":\"General\",\"text\":\"On it\"}")
                .calls(
                        GET("/v1.0/me/joinedTeams").answer(joined),
                        GET("/v1.0/teams/t1/channels").answer(teamChannels),
                        POST("/v1.0/teams/t1/channels/19:c1@thread.tacv2/messages").json("{\"body\":{\"content\":\"On it\"}}")
                                .answer(201, "{\"id\":\"p1\"}"))
                .says("Posted a message to the Teams channel.").has("/channel", "General"));

        // Salesforce
        all.add(tool("salesforce", "search_accounts", "{\"query\":\"Acme\"}")
                .calls(GET("/services/data/v60.0/query")
                        .q("q", "SELECT Id,Name,Industry,BillingCity FROM Account WHERE Name LIKE '%Acme%' LIMIT 20")
                        .answer("{\"records\":[{\"Id\":\"001\",\"Name\":\"Acme\"}]}"))
                .says("Found 1 account(s) in Salesforce.").has("/items/0/name", "Acme"));
        all.add(tool("salesforce", "list_opportunities", "{\"stage\":\"Closed Won\"}")
                .calls(GET("/services/data/v60.0/query")
                        .q("q", "SELECT Id,Name,Account.Name,StageName,Amount,CloseDate FROM Opportunity "
                                + "WHERE StageName = 'Closed Won' ORDER BY CloseDate DESC LIMIT 20")
                        .answer("{\"records\":[{\"Id\":\"006A\",\"Name\":\"Deal\",\"Amount\":100}]}"))
                .says("Read 1 opportunity(ies) from Salesforce.").has("/items/0/amount", "100"));
        all.add(tool("salesforce", "get_opportunity", "{\"id\":\"" + SF_ID + "\"}")
                .calls(GET("/services/data/v60.0/sobjects/Opportunity/" + SF_ID)
                        .answer("{\"Id\":\"" + SF_ID + "\",\"Name\":\"Deal\",\"StageName\":\"Prospecting\"}"))
                .says("Read the opportunity \"Deal\" from Salesforce.").has("/stage", "Prospecting"));
        all.add(tool("salesforce", "create_lead", "{\"lastName\":\"Lee\",\"company\":\"Acme\",\"email\":\"lee@acme.test\"}")
                .calls(POST("/services/data/v60.0/sobjects/Lead")
                        .json("{\"LastName\":\"Lee\",\"Company\":\"Acme\",\"Email\":\"lee@acme.test\"}")
                        .answer(201, "{\"id\":\"00Q1\",\"success\":true}"))
                .says("Added Lee as a lead in Salesforce.").has("/id", "00Q1"));
        all.add(tool("salesforce", "update_opportunity",
                        "{\"id\":\"" + SF_ID + "\",\"stage\":\"Closed Won\",\"amount\":\"1200.50\"}")
                .calls(PATCH("/services/data/v60.0/sobjects/Opportunity/" + SF_ID)
                        .json("{\"StageName\":\"Closed Won\",\"Amount\":1200.50}")
                        .empty(204))
                .says("Updated the opportunity in Salesforce.").has("/updated", "true"));

        return List.copyOf(all);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    static SandboxServerAdapter sandbox(String server) {
        return new SandboxServerAdapter(server, SandboxServerRegistry.definitions().get(server), new ObjectMapper());
    }

    static ToolInvocation invocation(String server, String tool, String arguments) {
        return new ToolInvocation("org-1", "agent-1", "run-1", server, tool, arguments, "run-1:call-1", Map.of());
    }

    static String decode(String raw) {
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    static Map<String, String> query(String url) {
        Map<String, String> values = new LinkedHashMap<>();
        int split = url.indexOf('?');
        if (split < 0) {
            return values;
        }
        for (String pair : url.substring(split + 1).split("&")) {
            int equals = pair.indexOf('=');
            String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            values.putIfAbsent(key, value);
        }
        return values;
    }
}
