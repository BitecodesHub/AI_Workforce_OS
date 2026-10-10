// @find: notion, pages, workspace, search pages, get page, create page, update page, archive page, blocks, integration token, live adapter, real API, docs, wiki
// @what: Live Notion connector: runs notion__ tools (search, get, create, update, archive pages) against the Notion API with an integration token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin; invoked through ToolGateway.invoke; base class LiveServerAdapter
package os.aiworkforce.mcp.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * Notion's API with an internal integration secret.
 *
 * <p>An integration only sees the pages that were shared with it, so "not found" usually means
 * the page was never connected to the integration rather than that it does not exist.
 */
public final class NotionAdapter extends LiveServerAdapter {

    public static final String BASE_URL = "https://api.notion.com/v1";
    private static final String VERSION = "2022-06-28";

    /* A page id with or without dashes, also when it is the tail of a page's web address. */
    private static final Pattern PAGE_ID = Pattern.compile(
            "([0-9a-fA-F]{8})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{4})-?([0-9a-fA-F]{12})");
    private static final Set<String> TEXT_BLOCKS = Set.of(
            "paragraph",
            "heading_1",
            "heading_2",
            "heading_3",
            "bulleted_list_item",
            "numbered_list_item",
            "to_do",
            "quote",
            "callout",
            "toggle");
    private static final int BLOCK_TEXT_LIMIT = 2000;

    public NotionAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Notion", json, http, baseUrl);
        on("search_pages", this::searchPages);
        on("get_page", this::getPage);
        on("create_page", this::createPage);
        on("update_page", this::updatePage);
        on("archive_page", this::archivePage);
    }

    @Override
    protected void authorize(HttpHeaders headers, String token) {
        headers.setBearerAuth(token);
        headers.set("Notion-Version", VERSION);
    }

    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, "/users/me").map(user -> {
            String workspace = user.path("bot").path("workspace_name").asText("");
            return workspace.isEmpty() ? user.path("name").asText("Notion integration") : workspace;
        });
    }

    // @find: Notion search pages, tool notion__search_pages, live Notion call
    private Mono<ToolResult> searchPages(ToolInvocation invocation, JsonNode arguments, String token) {
        ObjectNode body = json.createObjectNode();
        String query = text(arguments, "query");
        if (query != null) {
            body.put("query", query);
        }
        body.putObject("filter").put("property", "object").put("value", "page");
        body.put("page_size", limit(arguments, 10, 50));
        return send(HttpMethod.POST, token, body, "/search").map(answer -> {
            ArrayNode items = json.createArrayNode();
            answer.path("results").forEach(page -> items.add(page(page)));
            return done(listOf(items), "Found " + items.size() + " page(s) in Notion.");
        });
    }

    // @find: Notion get page, tool notion__get_page, live Notion call
    private Mono<ToolResult> getPage(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = pageId(required(arguments, "id"));
        return get(token, "/pages/{id}", id)
                .zipWith(get(token, "/blocks/{id}/children?page_size=100", id))
                .map(both -> {
                    ObjectNode page = page(both.getT1());
                    page.put("content", text(both.getT2().path("results")));
                    return done(page, "Read the Notion page \"" + page.path("title").asText() + "\".");
                });
    }

    // @find: Notion create page, tool notion__create_page, live Notion call
    private Mono<ToolResult> createPage(ToolInvocation invocation, JsonNode arguments, String token) {
        String parent = pageId(required(arguments, "parentId"));
        String title = required(arguments, "title");
        ObjectNode body = json.createObjectNode();
        body.putObject("parent").put("page_id", parent);
        body.putObject("properties").putObject("title").set("title", richText(title));
        String content = text(arguments, "content");
        if (content != null) {
            body.set("children", paragraphs(content));
        }
        return send(HttpMethod.POST, token, body, "/pages")
                .map(page -> done(page(page), "Created the Notion page \"" + title + "\"."));
    }

    // @find: Notion update page, tool notion__update_page, live Notion call
    private Mono<ToolResult> updatePage(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = pageId(required(arguments, "id"));
        ObjectNode body = json.createObjectNode();
        body.set("children", paragraphs(required(arguments, "content")));
        return send(HttpMethod.PATCH, token, body, "/blocks/{id}/children", id).map(answer -> {
            ObjectNode result = json.createObjectNode();
            result.put("id", id);
            result.put("blocksAdded", answer.path("results").size());
            return done(result, "Added text to the end of a Notion page.");
        });
    }

    // @find: Notion archive page, tool notion__archive_page, live Notion call
    private Mono<ToolResult> archivePage(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = pageId(required(arguments, "id"));
        ObjectNode body = json.createObjectNode();
        body.put("archived", true);
        return send(HttpMethod.PATCH, token, body, "/pages/{id}", id).map(page -> {
            ObjectNode result = page(page);
            result.put("archived", true);
            return done(result, "Moved the Notion page \"" + result.path("title").asText() + "\" to the trash.");
        });
    }

    private ObjectNode page(JsonNode page) {
        ObjectNode item = json.createObjectNode();
        item.put("id", page.path("id").asText());
        item.put("title", title(page));
        item.put("url", page.path("url").asText());
        item.put("lastEditedAt", page.path("last_edited_time").asText());
        return item;
    }

    /* The title lives in whichever property has type "title"; its name varies by database. */
    private static String title(JsonNode page) {
        StringBuilder title = new StringBuilder();
        page.path("properties").forEach(property -> {
            if ("title".equals(property.path("type").asText())) {
                property.path("title").forEach(part -> title.append(part.path("plain_text").asText()));
            }
        });
        return title.isEmpty() ? "Untitled" : title.toString();
    }

    private static String text(JsonNode blocks) {
        List<String> lines = new ArrayList<>();
        blocks.forEach(block -> {
            String type = block.path("type").asText();
            if (!TEXT_BLOCKS.contains(type)) {
                return;
            }
            StringBuilder line = new StringBuilder();
            block.path(type).path("rich_text").forEach(part -> line.append(part.path("plain_text").asText()));
            String prefix = type.endsWith("list_item") || type.equals("to_do") ? "- " : "";
            lines.add(prefix + line);
        });
        return String.join("\n", lines);
    }

    private ArrayNode paragraphs(String content) {
        ArrayNode children = json.createArrayNode();
        for (String paragraph : content.split("\\n\\s*\\n")) {
            String rest = paragraph.strip();
            while (!rest.isEmpty() && children.size() < 100) {
                String chunk = rest.length() <= BLOCK_TEXT_LIMIT ? rest : rest.substring(0, BLOCK_TEXT_LIMIT);
                rest = rest.substring(chunk.length());
                ObjectNode block = children.addObject();
                block.put("object", "block");
                block.put("type", "paragraph");
                block.putObject("paragraph").set("rich_text", richText(chunk));
            }
        }
        return children;
    }

    private ArrayNode richText(String content) {
        ArrayNode parts = json.createArrayNode();
        parts.addObject().put("type", "text").putObject("text").put("content", content);
        return parts;
    }

    private static String pageId(String raw) {
        Matcher match = PAGE_ID.matcher(raw);
        String found = null;
        while (match.find()) {
            found = String.join("-", match.group(1), match.group(2), match.group(3), match.group(4), match.group(5));
        }
        if (found == null) {
            throw new VendorException("That does not look like a Notion page id. Search for the page first.");
        }
        return found.toLowerCase(java.util.Locale.ROOT);
    }
}
