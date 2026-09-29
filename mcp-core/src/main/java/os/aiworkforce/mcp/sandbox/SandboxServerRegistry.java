package os.aiworkforce.mcp.sandbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import os.aiworkforce.llm.model.ToolSpec;
import os.aiworkforce.mcp.model.ToolDefinition;
import os.aiworkforce.mcp.spi.McpServerAdapter;

/**
 * The six committed servers, defined as data.
 *
 * <p>These definitions are the contract. A live adapter for Gmail must offer exactly the tools,
 * scopes and side-effect classes declared here, so swapping the sandbox for the real thing cannot
 * quietly change what an agent is able to do or which of its actions need approval.
 *
 * <p>The side-effect class on each tool is the governance decision, and it is made once, here.
 * {@code send_message} is OUTBOUND, so it is gated whatever any policy says; {@code draft_message}
 * is WRITE, so an agent can prepare an email freely and only the sending waits for a person.
 * That split is what makes the platform useful rather than permanently blocked.
 */
@Configuration
@ConditionalOnProperty(name = "aiwos.mcp.sandbox-enabled", havingValue = "true", matchIfMissing = true)
public class SandboxServerRegistry {

    private static final Logger log = LoggerFactory.getLogger(SandboxServerRegistry.class);

    @Bean
    public List<McpServerAdapter> sandboxServers(ObjectMapper json) {
        List<McpServerAdapter> servers = List.of(
                new SandboxServerAdapter("gmail", gmailTools(), json),
                new SandboxServerAdapter("calendar", calendarTools(), json),
                new SandboxServerAdapter("slack", slackTools(), json),
                new SandboxServerAdapter("github", githubTools(), json),
                new SandboxServerAdapter("jira", jiraTools(), json),
                new SandboxServerAdapter("drive", driveTools(), json),
                new SandboxServerAdapter("voice", voiceTools(), json));
        log.info(
                "Sandbox tool servers registered: {} servers, {} tools",
                servers.size(),
                servers.stream().mapToInt(s -> s.tools().size()).sum());
        return servers;
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

    private List<ToolDefinition> gmailTools() {
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

    private List<ToolDefinition> calendarTools() {
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

    private List<ToolDefinition> slackTools() {
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

    private List<ToolDefinition> githubTools() {
        return List.of(
                read(
                        "github",
                        "list_issues",
                        "List issues in a repository.",
                        schema("\"repo\":{\"type\":\"string\"},\"state\":{\"type\":\"string\"}", "repo"),
                        "repo:read"),
                read(
                        "github",
                        "get_pulls",
                        "List open pull requests.",
                        schema("\"repo\":{\"type\":\"string\"}", "repo"),
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
                        "Change an existing issue.",
                        schema(
                                "\"repo\":{\"type\":\"string\"},\"id\":{\"type\":\"string\"},"
                                        + "\"state\":{\"type\":\"string\"}",
                                "repo",
                                "id"),
                        "repo:write"));
    }

    private List<ToolDefinition> jiraTools() {
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

    private List<ToolDefinition> voiceTools() {
        // No scopes: this is a workspace capability, not an external account an agent connects
        // to, so there is no consent screen whose grant could be missing.
        return List.of(write(
                "voice",
                "create_voice_note",
                "Write a short script for a voice note to be read aloud.",
                schema("\"text\":{\"type\":\"string\",\"maxLength\":2500}", "text")));
    }

    private List<ToolDefinition> driveTools() {
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
}
