// @find: confluence, atlassian, wiki, pages, search pages, get page, create page, update page, site email api token, live adapter, real API, documentation
// @what: Live Confluence connector: runs confluence__ tools (search, get, create, update pages) against an Atlassian Cloud site using site, email and API token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin; invoked through ToolGateway.invoke; base class LiveServerAdapter
package os.aiworkforce.mcp.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * Confluence Cloud with an Atlassian account email and API token.
 *
 * <p>The stored credential is JSON: {@code {"site":"https://acme.atlassian.net","email":"...","token":"..."}}.
 * Search uses the v1 search API (CQL); pages are read and written through the v2 API, which
 * needs the current version number to update a page.
 */
public final class ConfluenceAdapter extends LiveServerAdapter {

    /** The address comes from the credential's site. */
    public static final String BASE_URL = null;

    private static final int CONTENT_LIMIT = 20_000;

    private final boolean fixedBase;

    public ConfluenceAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Confluence", json, http, baseUrl);
        this.fixedBase = baseUrl != null;
        on("search_pages", this::searchPages);
        on("get_page", this::getPage);
        on("create_page", this::createPage);
        on("update_page", this::updatePage);
    }

    private record Account(String base, String email, String token) {}

    private Account account(String credential) {
        JsonNode node;
        try {
            node = json.readTree(credential);
        } catch (Exception e) {
            node = null;
        }
        if (node == null || !node.isObject()) {
            throw new VendorException("The Confluence credential must be JSON with the site, email and token.");
        }
        String site = text(node, "site");
        String email = text(node, "email");
        String token = text(node, "token");
        if (site == null || email == null || token == null) {
            throw new VendorException("The Confluence credential needs a site, an email and an API token.");
        }
        try {
            return new Account(Hosts.atlassianBase(site), email, token);
        } catch (IllegalArgumentException e) {
            throw new VendorException(e.getMessage());
        }
    }

    @Override
    protected String baseFor(String credential) {
        Account account = account(credential);
        return fixedBase ? null : account.base();
    }

    @Override
    protected void authorize(HttpHeaders headers, String credential) {
        Account account = account(credential);
        headers.setBasicAuth(account.email(), account.token());
        headers.set(HttpHeaders.ACCEPT, "application/json");
    }

    @Override
    protected Mono<String> whoAmI(String credential) {
        Account account = account(credential);
        return get(credential, "/wiki/rest/api/user/current").map(me -> {
            String name = me.path("displayName").asText(me.path("email").asText("Confluence user"));
            return name + " on " + account.base().substring("https://".length());
        });
    }

    // @find: Confluence search pages, tool confluence__search_pages, live Confluence call
    private Mono<ToolResult> searchPages(ToolInvocation invocation, JsonNode arguments, String credential) {
        StringBuilder cql = new StringBuilder("type = page");
        String query = text(arguments, "query");
        String space = text(arguments, "space");
        if (query != null) {
            cql.append(" and text ~ \"").append(escape(query)).append('"');
        }
        if (space != null) {
            cql.append(" and space = \"").append(escape(space)).append('"');
        }
        if (query == null) {
            cql.append(" order by lastmodified desc");
        }
        Account account = account(credential);
        return get(credential, "/wiki/rest/api/search?cql={cql}&limit={limit}", cql.toString(), limit(arguments, 10, 50))
                .map(answer -> {
                    ArrayNode items = json.createArrayNode();
                    answer.path("results").forEach(result -> {
                        JsonNode content = result.path("content");
                        ObjectNode item = items.addObject();
                        item.put("id", content.path("id").asText(result.path("id").asText()));
                        item.put("title", result.path("title").asText(content.path("title").asText()));
                        item.put("space", result.path("resultGlobalContainer").path("title").asText(null));
                        item.put("excerpt", result.path("excerpt").asText("").replaceAll("\\s+", " ").strip());
                        item.put("updatedAt", result.path("lastModified").asText());
                        String link = content.path("_links").path("webui").asText(result.path("url").asText(""));
                        item.put("url", link.isEmpty() ? null : account.base() + "/wiki" + link);
                    });
                    return done(listOf(items), "Found " + items.size() + " page(s) in Confluence.");
                });
    }

    // @find: Confluence get page, tool confluence__get_page, live Confluence call
    private Mono<ToolResult> getPage(ToolInvocation invocation, JsonNode arguments, String credential) {
        String id = required(arguments, "id");
        Account account = account(credential);
        return page(credential, id).map(page -> {
            ObjectNode item = json.createObjectNode();
            item.put("id", page.path("id").asText(id));
            item.put("title", page.path("title").asText());
            item.put("status", page.path("status").asText());
            item.put("version", page.path("version").path("number").asInt());
            item.put("url", account.base() + "/wiki" + page.path("_links").path("webui").asText(""));
            String plain = page.path("body").path("storage").path("value").asText("")
                    .replaceAll("(?i)</(p|h[1-6]|li|tr|div)>|<br\\s*/?>", "\n")
                    .replaceAll("<[^>]+>", "")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replaceAll("\\n{3,}", "\n\n")
                    .strip();
            item.put("content", plain.length() <= CONTENT_LIMIT ? plain : plain.substring(0, CONTENT_LIMIT));
            return done(item, "Read the Confluence page \"" + item.path("title").asText() + "\".");
        });
    }

    // @find: Confluence create page, tool confluence__create_page, live Confluence call
    private Mono<ToolResult> createPage(ToolInvocation invocation, JsonNode arguments, String credential) {
        String space = required(arguments, "space");
        String title = required(arguments, "title");
        String content = text(arguments, "content");
        String parent = text(arguments, "parentId");
        Account account = account(credential);
        return get(credential, "/wiki/api/v2/spaces?keys={key}", space).flatMap(spaces -> {
            JsonNode found = spaces.path("results").path(0);
            if (found.isMissingNode() || found.path("id").asText("").isEmpty()) {
                throw new VendorException("Confluence has no space with the key " + space + " that this token can see.");
            }
            ObjectNode body = json.createObjectNode();
            body.put("spaceId", found.path("id").asText());
            body.put("status", "current");
            body.put("title", title);
            if (parent != null) {
                body.put("parentId", parent);
            }
            ObjectNode storage = body.putObject("body");
            storage.put("representation", "storage");
            storage.put("value", content == null ? "" : storage(content));
            return send(HttpMethod.POST, credential, body, "/wiki/api/v2/pages").map(created -> {
                ObjectNode item = json.createObjectNode();
                item.put("id", created.path("id").asText());
                item.put("title", created.path("title").asText(title));
                item.put("status", created.path("status").asText("current"));
                item.put("url", account.base() + "/wiki" + created.path("_links").path("webui").asText(""));
                return done(item, "Created the Confluence page \"" + title + "\".");
            });
        });
    }

    // @find: Confluence update page, tool confluence__update_page, live Confluence call
    private Mono<ToolResult> updatePage(ToolInvocation invocation, JsonNode arguments, String credential) {
        String id = required(arguments, "id");
        String title = text(arguments, "title");
        String content = text(arguments, "content");
        if (title == null && content == null) {
            throw new VendorException("Say what to change: the title or the content.");
        }
        return page(credential, id).flatMap(current -> {
            int version = current.path("version").path("number").asInt(0) + 1;
            ObjectNode body = json.createObjectNode();
            body.put("id", id);
            body.put("status", "current");
            body.put("title", title != null ? title : current.path("title").asText());
            ObjectNode storage = body.putObject("body");
            storage.put("representation", "storage");
            storage.put(
                    "value",
                    content != null ? storage(content) : current.path("body").path("storage").path("value").asText(""));
            body.putObject("version").put("number", version);
            return send(HttpMethod.PUT, credential, body, "/wiki/api/v2/pages/{id}", id).map(updated -> {
                ObjectNode item = json.createObjectNode();
                item.put("id", updated.path("id").asText(id));
                item.put("title", updated.path("title").asText(body.path("title").asText()));
                item.put("version", updated.path("version").path("number").asInt(version));
                return done(item, "Updated the Confluence page \"" + item.path("title").asText() + "\".");
            });
        });
    }

    /* Confluence answers 409 when the page changed between reading its version and writing the next. */
    @Override
    protected String describe(WebClientResponseException response) {
        if (response.getStatusCode().value() == 409) {
            return "Confluence did not save the change because the page was changed at the same time. "
                    + "Read the page again and retry.";
        }
        return super.describe(response);
    }

    private Mono<JsonNode> page(String credential, String id) {
        return get(credential, "/wiki/api/v2/pages/{id}?body-format=storage", id);
    }

    /* Plain text becomes storage format: one escaped paragraph per blank-line-separated block. */
    private static String storage(String content) {
        StringBuilder html = new StringBuilder();
        for (String paragraph : content.split("\\n\\s*\\n")) {
            String line = paragraph.strip();
            if (!line.isEmpty()) {
                html.append("<p>")
                        .append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                                .replace("\n", "<br/>"))
                        .append("</p>");
            }
        }
        return html.toString();
    }

    private static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
