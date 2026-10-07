package os.aiworkforce.mcp.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.mcp.live.AsanaAdapter;
import os.aiworkforce.mcp.live.CalendarAdapter;
import os.aiworkforce.mcp.live.ConfluenceAdapter;
import os.aiworkforce.mcp.live.DriveAdapter;
import os.aiworkforce.mcp.live.GitHubAdapter;
import os.aiworkforce.mcp.live.GmailAdapter;
import os.aiworkforce.mcp.live.HubSpotAdapter;
import os.aiworkforce.mcp.live.JiraAdapter;
import os.aiworkforce.mcp.live.LinearAdapter;
import os.aiworkforce.mcp.live.NotionAdapter;
import os.aiworkforce.mcp.live.OAuthAdapter;
import os.aiworkforce.mcp.live.OutlookAdapter;
import os.aiworkforce.mcp.live.SalesforceAdapter;
import os.aiworkforce.mcp.live.SheetsAdapter;
import os.aiworkforce.mcp.live.SlackAdapter;
import os.aiworkforce.mcp.live.StripeAdapter;
import os.aiworkforce.mcp.live.TeamsAdapter;
import os.aiworkforce.mcp.live.WebhookAdapter;
import os.aiworkforce.mcp.live.ZendeskAdapter;
import os.aiworkforce.mcp.live.ZoomAdapter;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.spi.McpServerAdapter;
import os.aiworkforce.mcp.spi.TokenRefresher;

/**
 * Every tool server, defined as data.
 *
 * <p>These definitions are the contract. A live adapter must offer exactly the tools, scopes and
 * side-effect classes declared here - it is built around the sandbox definition rather than
 * beside it - so swapping the sandbox for the real thing cannot quietly change what an agent is
 * able to do or which of its actions need approval.
 *
 * <p>The side-effect class on each tool is the governance decision, and it is made once, here.
 * {@code send_message} is OUTBOUND, so it is gated whatever any policy says; {@code draft_message}
 * is WRITE, so an agent can prepare an email freely and only the sending waits for a person.
 * That split is what makes the platform useful rather than permanently blocked. Anything that
 * sends, posts or emails outside the workspace is OUTBOUND; anything that deletes, cancels or
 * refunds is DESTRUCTIVE.
 *
 * <p>Tool names are {@code verb_noun}. The sandbox reads the verb to decide what a call does and
 * the noun to pick the collection, so {@code list_issues} and {@code create_issue} share one.
 */
@Configuration
@ConditionalOnProperty(name = "aiwos.mcp.sandbox-enabled", havingValue = "true", matchIfMissing = true)
public class SandboxServerRegistry {

    private static final Logger log = LoggerFactory.getLogger(SandboxServerRegistry.class);
    private static final String SEEDS = "/mcp/sandbox-seeds.json";

    @Bean
    public List<McpServerAdapter> sandboxServers(
            ObjectMapper json,
            ObjectProvider<WebClient.Builder> http,
            ObjectProvider<TokenRefresher> refresher,
            @Value("${aiwos.environment:local}") String environment) {
        // A webhook to http://localhost is how the webhook connector is tested on a laptop. A
        // deployed environment refuses it, so a pasted URL cannot reach the platform's own ports.
        boolean allowLoopback = "local".equalsIgnoreCase(environment) || "test".equalsIgnoreCase(environment);
        List<McpServerAdapter> servers = servers(json, http.getIfAvailable(WebClient::builder), allowLoopback);
        servers.stream()
                .filter(OAuthAdapter.class::isInstance)
                .forEach(server -> ((OAuthAdapter) server).useRefresher(refresher::getIfAvailable));
        log.info(
                "Tool servers registered: {} servers ({} with a live connection available), {} tools",
                servers.size(),
                servers.stream().filter(server -> !server.isSandbox()).count(),
                servers.stream().mapToInt(s -> s.tools().size()).sum());
        return servers;
    }

    /**
     * Builds every server: the sandbox for each, wrapped in its live adapter where one exists.
     *
     * <p>A live adapter answers from the sandbox until a credential is stored, so a demo
     * workspace keeps working and the tool surface is the same either way.
     */
    public static List<McpServerAdapter> servers(ObjectMapper json, WebClient.Builder http, boolean allowLoopback) {
        Map<String, Map<String, List<ObjectNode>>> seeds = seeds(json);
        List<McpServerAdapter> servers = new ArrayList<>();
        definitions().forEach((server, tools) -> {
            SandboxServerAdapter sandbox =
                    new SandboxServerAdapter(server, tools, json, seeds.getOrDefault(server, Map.of()));
            servers.add(
                    switch (server) {
                        case "github" -> new GitHubAdapter(sandbox, json, http, GitHubAdapter.BASE_URL);
                        case "slack" -> new SlackAdapter(sandbox, json, http, SlackAdapter.BASE_URL);
                        case "notion" -> new NotionAdapter(sandbox, json, http, NotionAdapter.BASE_URL);
                        case "linear" -> new LinearAdapter(sandbox, json, http, LinearAdapter.BASE_URL);
                        case "hubspot" -> new HubSpotAdapter(sandbox, json, http, HubSpotAdapter.BASE_URL);
                        case "webhook" -> new WebhookAdapter(sandbox, json, http, allowLoopback);
                        case "jira" -> new JiraAdapter(sandbox, json, http, JiraAdapter.BASE_URL);
                        case "confluence" -> new ConfluenceAdapter(sandbox, json, http, ConfluenceAdapter.BASE_URL);
                        case "asana" -> new AsanaAdapter(sandbox, json, http, AsanaAdapter.BASE_URL);
                        case "zendesk" -> new ZendeskAdapter(sandbox, json, http, ZendeskAdapter.BASE_URL);
                        case "stripe" -> new StripeAdapter(sandbox, json, http, StripeAdapter.BASE_URL);
                        case "zoom" -> new ZoomAdapter(sandbox, json, http, ZoomAdapter.BASE_URL);
                        case "gmail" -> new GmailAdapter(sandbox, json, http, GmailAdapter.BASE_URL);
                        case "calendar" -> new CalendarAdapter(sandbox, json, http, CalendarAdapter.BASE_URL);
                        case "drive" -> new DriveAdapter(sandbox, json, http, DriveAdapter.BASE_URL);
                        case "sheets" -> new SheetsAdapter(sandbox, json, http, SheetsAdapter.BASE_URL);
                        case "outlook" -> new OutlookAdapter(sandbox, json, http, OutlookAdapter.BASE_URL);
                        case "teams" -> new TeamsAdapter(sandbox, json, http, TeamsAdapter.BASE_URL);
                        case "salesforce" -> new SalesforceAdapter(sandbox, json, http, SalesforceAdapter.BASE_URL);
                        default -> sandbox;
                    });
        });
        return List.copyOf(servers);
    }

    /** Every server's tools, in the order the console lists them. */
    public static Map<String, List<ToolDefinition>> definitions() {
        Map<String, List<ToolDefinition>> servers = new LinkedHashMap<>();
        servers.put("gmail", gmailTools());
        servers.put("calendar", calendarTools());
        servers.put("slack", slackTools());
        servers.put("github", githubTools());
        servers.put("jira", jiraTools());
        servers.put("drive", driveTools());
        servers.put("voice", voiceTools());
        servers.put("outlook", outlookTools());
        servers.put("teams", teamsTools());
        servers.put("notion", notionTools());
        servers.put("linear", linearTools());
        servers.put("hubspot", hubspotTools());
        servers.put("salesforce", salesforceTools());
        servers.put("zendesk", zendeskTools());
        servers.put("confluence", confluenceTools());
        servers.put("asana", asanaTools());
        servers.put("sheets", sheetsTools());
        servers.put("stripe", stripeTools());
        servers.put("zoom", zoomTools());
        servers.put("webhook", webhookTools());
        return servers;
    }

    /* Practice records by server and collection, read once from the classpath. */
    private static Map<String, Map<String, List<ObjectNode>>> seeds(ObjectMapper json) {
        Map<String, Map<String, List<ObjectNode>>> seeds = new LinkedHashMap<>();
        try (InputStream in = SandboxServerRegistry.class.getResourceAsStream(SEEDS)) {
            if (in == null) {
                log.warn("No sandbox seed file at {}; sandbox lists start empty", SEEDS);
                return seeds;
            }
            JsonNode root = json.readTree(in);
            root.fields().forEachRemaining(server -> {
                Map<String, List<ObjectNode>> collections = new LinkedHashMap<>();
                server.getValue().fields().forEachRemaining(collection -> {
                    List<ObjectNode> records = new ArrayList<>();
                    collection.getValue().forEach(record -> {
                        if (record instanceof ObjectNode object) {
                            records.add(object);
                        }
                    });
                    collections.put(collection.getKey(), records);
                });
                seeds.put(server.getKey(), collections);
            });
        } catch (IOException e) {
            log.warn("Sandbox seed file {} could not be read; sandbox lists start empty", SEEDS, e);
        }
        return seeds;
    }

    // ---- Definitions ---------------------------------------------------------------------

    private static ToolDefinition read(
            String server, String name, String description, String schema, String... scopes) {
        return new ToolDefinition(
                server,
                name,
                description,
                schema,
                ToolSpec.SideEffect.READ,
                List.of(scopes),
                true,
                Duration.ofSeconds(20),
                60);
    }

    private static ToolDefinition write(
            String server, String name, String description, String schema, String... scopes) {
        return new ToolDefinition(
                server,
                name,
                description,
                schema,
                ToolSpec.SideEffect.WRITE,
                List.of(scopes),
                false,
                Duration.ofSeconds(30),
                30);
    }

    private static ToolDefinition outbound(
            String server, String name, String description, String schema, String... scopes) {
        // Not idempotent: a repeat sends a second message, so a timeout stays indeterminate.
        return new ToolDefinition(
                server,
                name,
                description,
                schema,
                ToolSpec.SideEffect.OUTBOUND,
                List.of(scopes),
                false,
                Duration.ofSeconds(30),
                20);
    }

    private static ToolDefinition destructive(
            String server, String name, String description, String schema, String... scopes) {
        return new ToolDefinition(
                server,
                name,
                description,
                schema,
                ToolSpec.SideEffect.DESTRUCTIVE,
                List.of(scopes),
                false,
                Duration.ofSeconds(30),
                5);
    }

    private static String schema(String properties, String... required) {
        String requiredList = required.length == 0
                ? ""
                : ",\"required\":["
                        + String.join(
                                ",",
                                java.util.Arrays.stream(required)
                                        .map(field -> "\"" + field + "\"")
                                        .toList()) + "]";
        return "{\"type\":\"object\",\"properties\":{" + properties + "}" + requiredList + "}";
    }

    private static List<ToolDefinition> gmailTools() {
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(read(
                "gmail",
                "list_messages",
                "List recent messages in the mailbox.",
                schema("\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}"),
                "gmail.readonly"));
        tools.add(read(
                "gmail",
                "get_message",
                "Read one message in full.",
                schema("\"id\":{\"type\":\"string\"}", "id"),
                "gmail.readonly"));
        tools.add(write(
                "gmail",
                "draft_message",
                "Prepare an email without sending it.",
                schema(
                        "\"to\":{\"type\":\"string\"},\"subject\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}",
                        "to",
                        "subject",
                        "body"),
                "gmail.compose"));
        tools.add(outbound(
                "gmail",
                "send_message",
                "Send an email. This cannot be recalled.",
                schema(
                        "\"to\":{\"type\":\"string\"},\"subject\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}",
                        "to",
                        "subject",
                        "body"),
                "gmail.send"));
        return tools;
    }

    private static List<ToolDefinition> calendarTools() {
        return List.of(
                read(
                        "calendar",
                        "list_events",
                        "List events in a date range.",
                        schema("\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"}"),
                        "calendar.readonly"),
                write(
                        "calendar",
                        "create_event",
                        "Create an event in the calendar.",
                        schema(
                                "\"title\":{\"type\":\"string\"},\"start\":{\"type\":\"string\"},"
                                        + "\"end\":{\"type\":\"string\"},\"attendees\":{\"type\":\"array\","
                                        + "\"items\":{\"type\":\"string\"}}",
                                "title",
                                "start",
                                "end"),
                        "calendar.events"),
                destructive(
                        "calendar",
                        "delete_event",
                        "Remove an event from the calendar.",
                        schema("\"id\":{\"type\":\"string\"}", "id"),
                        "calendar.events"));
    }

    private static List<ToolDefinition> slackTools() {
        return List.of(
                read("slack", "list_channels", "List channels the workspace can see.", schema(""), "channels:read"),
                read(
                        "slack",
                        "get_messages",
                        "Read recent messages in a channel.",
                        schema("\"channel\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}", "channel"),
                        "channels:history"),
                outbound(
                        "slack",
                        "post_message",
                        "Post a message to a channel.",
                        schema("\"channel\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}", "channel", "text"),
                        "chat:write"));
    }

    private static List<ToolDefinition> githubTools() {
        String repo = str("repo", "owner/name, for example acme/website");
        return List.of(
                read(
                        "github",
                        "list_repos",
                        "List the repositories the connected account can see, most recently updated first.",
                        schema(props(str("owner", "an organisation's login; leave out for the account's own"), LIMIT)),
                        "repo:read"),
                write(
                        "github",
                        "create_repo",
                        "Create a new repository in the connected account, or in an organisation. It is private"
                                + " unless private is false.",
                        schema(
                                props(
                                        str("name", "letters, digits, hyphens, dots or underscores"),
                                        str("description"),
                                        "\"private\":{\"type\":\"boolean\",\"description\":\"true when left out\"}",
                                        str("owner", "an organisation's login; leave out for the account's own")),
                                "name"),
                        "repo:write"),
                read(
                        "github",
                        "list_branches",
                        "List the branches of a repository.",
                        schema(props(repo), "repo"),
                        "repo:read"),
                write(
                        "github",
                        "create_branch",
                        "Create a branch in a repository, starting from another branch.",
                        schema(
                                props(
                                        repo,
                                        str("branch", "the new branch's name"),
                                        str("from", "the branch to start from; the default branch when left out")),
                                "repo",
                                "branch"),
                        "repo:write"),
                read(
                        "github",
                        "list_issues",
                        "List issues in a repository.",
                        schema("\"repo\":{\"type\":\"string\"},\"state\":{\"type\":\"string\"}", "repo"),
                        "repo:read"),
                write(
                        "github",
                        "create_issue",
                        "Open an issue in a repository.",
                        schema(
                                "\"repo\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},"
                                        + "\"body\":{\"type\":\"string\"}",
                                "repo",
                                "title"),
                        "repo:write"),
                write(
                        "github",
                        "update_issue",
                        "Change an existing issue: close or reopen it, or edit its title or text.",
                        schema(
                                props(
                                        str("repo", "owner/name, for example acme/website"),
                                        str("id", "the issue number"),
                                        str("state", "open or closed"),
                                        str("title"),
                                        str("body")),
                                "repo",
                                "id"),
                        "repo:write"),
                read(
                        "github",
                        "list_comments",
                        "Read the comments on an issue or pull request.",
                        schema(props(repo, str("issue", "the issue or pull request number")), "repo", "issue"),
                        "repo:read"),
                write(
                        "github",
                        "add_comment",
                        "Comment on an issue or pull request.",
                        schema(
                                props(repo, str("issue", "the issue or pull request number"), str("body")),
                                "repo",
                                "issue",
                                "body"),
                        "repo:write"),
                read(
                        "github",
                        "get_pulls",
                        "List open pull requests.",
                        schema("\"repo\":{\"type\":\"string\"}", "repo"),
                        "repo:read"),
                read(
                        "github",
                        "get_pull",
                        "Read one pull request: its description, branches, and whether it can be merged.",
                        schema(props(repo, str("id", "the pull request number")), "repo", "id"),
                        "repo:read"),
                write(
                        "github",
                        "create_pull",
                        "Open a pull request from one branch into another.",
                        schema(
                                props(
                                        repo,
                                        str("title"),
                                        str("head", "the branch with the changes"),
                                        str("base", "the branch to merge into; the default branch when left out"),
                                        str("body"),
                                        bool("draft")),
                                "repo",
                                "title",
                                "head"),
                        "repo:write"),
                // Merging changes the shared branch everyone builds from, and cannot be undone by
                // the agent, so it waits for a person like anything that leaves the workspace.
                outbound(
                        "github",
                        "merge_pull",
                        "Merge a pull request into its base branch.",
                        schema(
                                props(repo, str("id", "the pull request number"), str("method", "merge, squash or rebase")),
                                "repo",
                                "id"),
                        "repo:write"),
                read(
                        "github",
                        "get_file",
                        "Read a file, or list a folder, in a repository.",
                        schema(
                                props(
                                        repo,
                                        str("path", "for example docs/readme.md"),
                                        str("ref", "a branch, tag or commit; the default branch when left out")),
                                "repo",
                                "path"),
                        "repo:read"),
                write(
                        "github",
                        "save_file",
                        "Create a file, or replace a file's contents, as one commit.",
                        schema(
                                props(
                                        repo,
                                        str("path", "for example docs/readme.md"),
                                        str("content", "the whole new contents of the file"),
                                        str("message", "the commit message"),
                                        str("branch", "the default branch when left out")),
                                "repo",
                                "path",
                                "content"),
                        "repo:write"));
    }

    private static List<ToolDefinition> jiraTools() {
        return List.of(
                read(
                        "jira",
                        "search_issues",
                        "Search issues with a query.",
                        schema("\"jql\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}"),
                        "read:jira-work"),
                write(
                        "jira",
                        "create_issue",
                        "Create a Jira issue.",
                        schema(
                                "\"project\":{\"type\":\"string\"},\"summary\":{\"type\":\"string\"},"
                                        + "\"description\":{\"type\":\"string\"},\"type\":{\"type\":\"string\"}",
                                "project",
                                "summary"),
                        "write:jira-work"),
                write(
                        "jira",
                        "update_issue",
                        "Update a Jira issue.",
                        schema("\"id\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"}", "id"),
                        "write:jira-work"));
    }

    private static List<ToolDefinition> voiceTools() {
        // No scopes: this is a workspace capability, not an external account an agent connects
        // to, so there is no consent screen whose grant could be missing.
        return List.of(write(
                "voice",
                "create_voice_note",
                "Write a short script for a voice note to be read aloud.",
                schema("\"text\":{\"type\":\"string\",\"maxLength\":2500}", "text")));
    }

    private static List<ToolDefinition> driveTools() {
        return List.of(
                read(
                        "drive",
                        "list_files",
                        "List files in a folder.",
                        schema("\"folderId\":{\"type\":\"string\"}"),
                        "drive.readonly"),
                read(
                        "drive",
                        "get_file",
                        "Read a file's contents.",
                        schema("\"id\":{\"type\":\"string\"}", "id"),
                        "drive.readonly"),
                write(
                        "drive",
                        "create_file",
                        "Create a document in Drive.",
                        schema(
                                "\"name\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},"
                                        + "\"folderId\":{\"type\":\"string\"}",
                                "name",
                                "content"),
                        "drive.file"));
    }

    // ---- Schema helpers --------------------------------------------------------------------

    private static String props(String... properties) {
        return String.join(",", properties);
    }

    private static String str(String name) {
        return "\"" + name + "\":{\"type\":\"string\"}";
    }

    private static String str(String name, String description) {
        return "\"" + name + "\":{\"type\":\"string\",\"description\":\"" + description + "\"}";
    }

    private static String integer(String name) {
        return "\"" + name + "\":{\"type\":\"integer\"}";
    }

    private static String number(String name, String description) {
        return "\"" + name + "\":{\"type\":\"number\",\"description\":\"" + description + "\"}";
    }

    private static String bool(String name) {
        return "\"" + name + "\":{\"type\":\"boolean\"}";
    }

    private static String strings(String name, String description) {
        return "\"" + name + "\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\""
                + description + "\"}";
    }

    private static String object(String name, String description) {
        return "\"" + name + "\":{\"type\":\"object\",\"description\":\"" + description + "\"}";
    }

    private static final String LIMIT = "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}";

    // ---- Communication ---------------------------------------------------------------------

    private static List<ToolDefinition> outlookTools() {
        String email = props(str("to"), str("subject"), str("body"));
        return List.of(
                read(
                        "outlook",
                        "list_messages",
                        "List recent emails in the Outlook mailbox, optionally only those matching a search.",
                        schema(props(str("query"), LIMIT)),
                        "Mail.Read"),
                read(
                        "outlook",
                        "get_message",
                        "Read one Outlook email in full.",
                        schema(str("id"), "id"),
                        "Mail.Read"),
                write(
                        "outlook",
                        "draft_message",
                        "Prepare an Outlook email and leave it in Drafts without sending it.",
                        schema(email, "to", "subject", "body"),
                        "Mail.ReadWrite"),
                outbound(
                        "outlook",
                        "send_message",
                        "Send an email from Outlook. It cannot be recalled once sent.",
                        schema(email, "to", "subject", "body"),
                        "Mail.Send"),
                destructive(
                        "outlook",
                        "delete_message",
                        "Delete an email from the Outlook mailbox.",
                        schema(str("id"), "id"),
                        "Mail.ReadWrite"),
                read(
                        "outlook",
                        "list_events",
                        "List Outlook calendar events between two dates.",
                        schema(props(str("from", "start date, YYYY-MM-DD"), str("to", "end date, YYYY-MM-DD"))),
                        "Calendars.Read"));
    }

    private static List<ToolDefinition> teamsTools() {
        return List.of(
                read(
                        "teams",
                        "list_channels",
                        "List the Microsoft Teams channels the workspace can see.",
                        schema(str("team")),
                        "Channel.ReadBasic.All"),
                read(
                        "teams",
                        "get_messages",
                        "Read recent messages in a Teams channel.",
                        schema(props(str("channel"), LIMIT), "channel"),
                        "ChannelMessage.Read.All"),
                outbound(
                        "teams",
                        "post_message",
                        "Post a message to a Teams channel, where everyone in the channel can see it.",
                        schema(props(str("channel"), str("text")), "channel", "text"),
                        "ChannelMessage.Send"));
    }

    private static List<ToolDefinition> zoomTools() {
        return List.of(
                read(
                        "zoom",
                        "list_meetings",
                        "List upcoming Zoom meetings.",
                        schema(props(str("from", "YYYY-MM-DD"), str("to", "YYYY-MM-DD"), LIMIT)),
                        "meeting:read"),
                read(
                        "zoom",
                        "get_meeting",
                        "Read one Zoom meeting, including its join link.",
                        schema(str("id"), "id"),
                        "meeting:read"),
                write(
                        "zoom",
                        "schedule_meeting",
                        "Create a Zoom meeting and return its join link. Nobody is invited until the link is shared.",
                        schema(
                                props(
                                        str("topic"),
                                        str("start", "ISO 8601 date and time"),
                                        integer("durationMinutes"),
                                        str("agenda")),
                                "topic",
                                "start"),
                        "meeting:write"),
                destructive(
                        "zoom",
                        "cancel_meeting",
                        "Cancel a Zoom meeting. Anyone registered for it is told it is cancelled.",
                        schema(str("id"), "id"),
                        "meeting:write"),
                read(
                        "zoom",
                        "list_recordings",
                        "List recordings of past Zoom meetings.",
                        schema(props(str("from", "YYYY-MM-DD"), str("to", "YYYY-MM-DD"), LIMIT)),
                        "recording:read"));
    }

    // ---- Productivity and files -------------------------------------------------------------

    private static List<ToolDefinition> notionTools() {
        return List.of(
                read(
                        "notion",
                        "search_pages",
                        "Find Notion pages whose title or text matches a search.",
                        schema(props(str("query"), LIMIT)),
                        "read_content"),
                read(
                        "notion",
                        "get_page",
                        "Read one Notion page, including its text.",
                        schema(str("id"), "id"),
                        "read_content"),
                write(
                        "notion",
                        "create_page",
                        "Create a new Notion page inside an existing page.",
                        schema(
                                props(
                                        str("parentId", "id of the page to create it under"),
                                        str("title"),
                                        str("content", "plain text; blank lines start new paragraphs")),
                                "parentId",
                                "title"),
                        "insert_content"),
                write(
                        "notion",
                        "update_page",
                        "Add text to the end of an existing Notion page.",
                        schema(props(str("id"), str("content")), "id", "content"),
                        "insert_content"),
                destructive(
                        "notion",
                        "archive_page",
                        "Move a Notion page to the trash.",
                        schema(str("id"), "id"),
                        "update_content"));
    }

    private static List<ToolDefinition> confluenceTools() {
        return List.of(
                read(
                        "confluence",
                        "search_pages",
                        "Find Confluence pages that match a search, optionally in one space.",
                        schema(props(str("query"), str("space"), LIMIT)),
                        "search:confluence"),
                read(
                        "confluence",
                        "get_page",
                        "Read one Confluence page in full.",
                        schema(str("id"), "id"),
                        "read:confluence-content.all"),
                write(
                        "confluence",
                        "create_page",
                        "Create a new Confluence page in a space.",
                        schema(props(str("space"), str("title"), str("content"), str("parentId")), "space", "title"),
                        "write:confluence-content"),
                write(
                        "confluence",
                        "update_page",
                        "Replace the title or text of an existing Confluence page.",
                        schema(props(str("id"), str("title"), str("content")), "id"),
                        "write:confluence-content"));
    }

    private static List<ToolDefinition> asanaTools() {
        return List.of(
                read(
                        "asana",
                        "list_tasks",
                        "List Asana tasks in a project or assigned to someone.",
                        schema(props(str("project"), str("assignee"), bool("completed"), LIMIT)),
                        "tasks:read"),
                read(
                        "asana",
                        "get_task",
                        "Read one Asana task in full.",
                        schema(str("id"), "id"),
                        "tasks:read"),
                write(
                        "asana",
                        "create_task",
                        "Create an Asana task.",
                        schema(
                                props(
                                        str("name"),
                                        str("project"),
                                        str("notes"),
                                        str("assignee"),
                                        str("dueOn", "YYYY-MM-DD")),
                                "name"),
                        "tasks:write"),
                write(
                        "asana",
                        "update_task",
                        "Change a task's name, assignee, due date, or mark it complete.",
                        schema(
                                props(
                                        str("id"),
                                        str("name"),
                                        str("assignee"),
                                        str("dueOn", "YYYY-MM-DD"),
                                        bool("completed")),
                                "id"),
                        "tasks:write"),
                destructive(
                        "asana",
                        "delete_task",
                        "Delete an Asana task.",
                        schema(str("id"), "id"),
                        "tasks:delete"));
    }

    private static List<ToolDefinition> sheetsTools() {
        return List.of(
                read(
                        "sheets",
                        "list_spreadsheets",
                        "List the Google Sheets spreadsheets the workspace can open.",
                        schema(""),
                        "spreadsheets.readonly"),
                read(
                        "sheets",
                        "list_rows",
                        "Read rows from one sheet of a spreadsheet.",
                        schema(props(str("spreadsheetId"), str("sheet"), LIMIT), "spreadsheetId"),
                        "spreadsheets.readonly"),
                write(
                        "sheets",
                        "append_row",
                        "Add a row to the end of a sheet.",
                        schema(
                                props(
                                        str("spreadsheetId"),
                                        str("sheet"),
                                        strings("values", "one value per column, left to right")),
                                "spreadsheetId",
                                "values"),
                        "spreadsheets"),
                write(
                        "sheets",
                        "update_row",
                        "Change the values in one row of a sheet.",
                        schema(props(str("id"), strings("values", "one value per column, left to right")), "id", "values"),
                        "spreadsheets"));
    }

    // ---- Engineering -------------------------------------------------------------------------

    private static List<ToolDefinition> linearTools() {
        return List.of(
                read(
                        "linear",
                        "list_issues",
                        "List Linear issues, optionally only those matching a search, in one state or in one team.",
                        schema(props(str("query"), str("state"), str("team", "team key, for example ENG"), LIMIT)),
                        "read"),
                read(
                        "linear",
                        "get_issue",
                        "Read one Linear issue in full, by its key such as ENG-42.",
                        schema(str("id"), "id"),
                        "read"),
                write(
                        "linear",
                        "create_issue",
                        "Create a Linear issue in a team.",
                        schema(
                                props(
                                        str("team", "team key, for example ENG"),
                                        str("title"),
                                        str("description"),
                                        "\"priority\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":4}"),
                                "title"),
                        "write"),
                write(
                        "linear",
                        "update_issue",
                        "Change a Linear issue's state, title, description or priority.",
                        schema(
                                props(
                                        str("id"),
                                        str("state", "workflow state name, for example In Progress"),
                                        str("title"),
                                        str("description"),
                                        "\"priority\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":4}"),
                                "id"),
                        "write"));
    }

    // ---- Sales, support and finance ------------------------------------------------------------

    private static List<ToolDefinition> hubspotTools() {
        return List.of(
                read(
                        "hubspot",
                        "list_contacts",
                        "List contacts in HubSpot.",
                        schema(LIMIT),
                        "crm.objects.contacts.read"),
                read(
                        "hubspot",
                        "search_contacts",
                        "Find HubSpot contacts by name, email address or company.",
                        schema(props(str("query"), LIMIT), "query"),
                        "crm.objects.contacts.read"),
                write(
                        "hubspot",
                        "create_contact",
                        "Add a contact to HubSpot.",
                        schema(
                                props(str("email"), str("firstName"), str("lastName"), str("company"), str("phone")),
                                "email"),
                        "crm.objects.contacts.write"),
                read(
                        "hubspot",
                        "list_deals",
                        "List deals in the HubSpot pipeline with their stage and amount.",
                        schema(LIMIT),
                        "crm.objects.deals.read"));
    }

    private static List<ToolDefinition> salesforceTools() {
        return List.of(
                read(
                        "salesforce",
                        "search_accounts",
                        "Find Salesforce accounts by name.",
                        schema(props(str("query"), LIMIT)),
                        "api"),
                read(
                        "salesforce",
                        "list_opportunities",
                        "List Salesforce opportunities with their stage, amount and close date.",
                        schema(props(str("stage"), LIMIT)),
                        "api"),
                read(
                        "salesforce",
                        "get_opportunity",
                        "Read one Salesforce opportunity in full.",
                        schema(str("id"), "id"),
                        "api"),
                write(
                        "salesforce",
                        "create_lead",
                        "Add a new lead to Salesforce.",
                        schema(
                                props(str("firstName"), str("lastName"), str("company"), str("email")),
                                "lastName",
                                "company"),
                        "api"),
                write(
                        "salesforce",
                        "update_opportunity",
                        "Change an opportunity's stage, amount or close date.",
                        schema(
                                props(
                                        str("id"),
                                        str("stage"),
                                        number("amount", "in the opportunity's currency"),
                                        str("closeDate", "YYYY-MM-DD")),
                                "id"),
                        "api"));
    }

    private static List<ToolDefinition> zendeskTools() {
        return List.of(
                read(
                        "zendesk",
                        "list_tickets",
                        "List Zendesk support tickets, optionally only those with one status.",
                        schema(props(str("status", "new, open, pending or solved"), LIMIT)),
                        "tickets:read"),
                read(
                        "zendesk",
                        "get_ticket",
                        "Read one Zendesk ticket and its conversation.",
                        schema(str("id"), "id"),
                        "tickets:read"),
                write(
                        "zendesk",
                        "update_ticket",
                        "Change a ticket's status, priority or assignee.",
                        schema(props(str("id"), str("status"), str("priority"), str("assignee")), "id"),
                        "tickets:write"),
                write(
                        "zendesk",
                        "add_note",
                        "Add a private note to a ticket that only your team can see.",
                        schema(props(str("ticketId"), str("body")), "ticketId", "body"),
                        "tickets:write"),
                outbound(
                        "zendesk",
                        "send_reply",
                        "Send a public reply on a ticket. The customer receives it by email.",
                        schema(props(str("ticketId"), str("body")), "ticketId", "body"),
                        "tickets:write"));
    }

    private static List<ToolDefinition> stripeTools() {
        return List.of(
                read(
                        "stripe",
                        "search_customers",
                        "Find Stripe customers by name or email address.",
                        schema(props(str("query"), LIMIT)),
                        "customers:read"),
                read(
                        "stripe",
                        "list_payments",
                        "List recent payments, optionally for one customer or with one status.",
                        schema(props(str("customer", "customer id"), str("status"), LIMIT)),
                        "payments:read"),
                read(
                        "stripe",
                        "get_payment",
                        "Read one payment in full.",
                        schema(str("id"), "id"),
                        "payments:read"),
                read(
                        "stripe",
                        "list_invoices",
                        "List invoices, optionally for one customer or with one status.",
                        schema(props(str("customer", "customer id"), str("status", "draft, open, paid or void"), LIMIT)),
                        "invoices:read"),
                destructive(
                        "stripe",
                        "refund_payment",
                        "Refund a payment in full or in part. The money goes back to the customer and cannot be taken back.",
                        schema(
                                props(
                                        str("id"),
                                        number("amount", "amount to refund; leave out to refund in full"),
                                        str("reason", "duplicate, fraudulent or requested_by_customer")),
                                "id"),
                        "refunds:write"));
    }

    // ---- Automation --------------------------------------------------------------------------

    private static List<ToolDefinition> webhookTools() {
        // No scopes: the credential is a web address, and it either accepts events or it does not.
        return List.of(
                outbound(
                        "webhook",
                        "send_event",
                        "Send an event, as JSON, to the web address this workspace connected.",
                        schema(
                                props(
                                        str("event", "a short name, for example order.shipped"),
                                        object("data", "the details to send with the event")),
                                "event")),
                read(
                        "webhook",
                        "list_events",
                        "List the events recently sent to the connected web address.",
                        schema(LIMIT)));
    }
}
