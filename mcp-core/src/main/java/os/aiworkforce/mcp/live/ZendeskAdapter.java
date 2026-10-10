// @find: zendesk, support, tickets, helpdesk, list tickets, get ticket, update ticket, public reply, private note, subdomain email api token, live adapter, real API, customer support
// @what: Live Zendesk connector: runs zendesk__ tools (list, get, update tickets, reply or add notes) against a Zendesk subdomain using email and API token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin; invoked through ToolGateway.invoke; base class LiveServerAdapter
package os.aiworkforce.mcp.live;

import java.util.Set;

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
 * Zendesk Support with an agent email and API token.
 *
 * <p>The stored credential is JSON: {@code {"subdomain":"acme","email":"...","token":"..."}}. Zendesk
 * authenticates a token as the user name {@code email/token} with the token as the password.
 */
public final class ZendeskAdapter extends LiveServerAdapter {

    /** The address comes from the credential's subdomain. */
    public static final String BASE_URL = null;

    private static final Set<String> STATUSES = Set.of("new", "open", "pending", "hold", "solved", "closed");
    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    private static final int BODY_LIMIT = 2000;

    private final boolean fixedBase;

    public ZendeskAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Zendesk", json, http, baseUrl);
        this.fixedBase = baseUrl != null;
        on("list_tickets", this::listTickets);
        on("get_ticket", this::getTicket);
        on("update_ticket", this::updateTicket);
        on("add_note", (invocation, arguments, credential) -> comment(arguments, credential, false));
        on("send_reply", (invocation, arguments, credential) -> comment(arguments, credential, true));
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
            throw new VendorException("The Zendesk credential must be JSON with the subdomain, email and token.");
        }
        String subdomain = text(node, "subdomain");
        String email = text(node, "email");
        String token = text(node, "token");
        if (subdomain == null || email == null || token == null) {
            throw new VendorException("The Zendesk credential needs a subdomain, an email and an API token.");
        }
        try {
            return new Account(Hosts.zendeskBase(subdomain), email, token);
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
        headers.setBasicAuth(account.email() + "/token", account.token());
        headers.set(HttpHeaders.ACCEPT, "application/json");
    }

    @Override
    protected Mono<String> whoAmI(String credential) {
        Account account = account(credential);
        return get(credential, "/api/v2/users/me").map(answer -> {
            JsonNode user = answer.path("user");
            String name = user.path("name").asText(user.path("email").asText("Zendesk user"));
            return name + " on " + account.base().substring("https://".length());
        });
    }

    // @find: Zendesk list tickets, tool zendesk__list_tickets, live Zendesk call
    private Mono<ToolResult> listTickets(ToolInvocation invocation, JsonNode arguments, String credential) {
        String status = text(arguments, "status");
        int limit = limit(arguments, 25, 100);
        Mono<JsonNode> answer;
        if (status == null) {
            answer = get(credential, "/api/v2/tickets?per_page={n}&sort_by=updated_at&sort_order=desc", limit);
        } else {
            String wanted = status.toLowerCase();
            if (!STATUSES.contains(wanted)) {
                throw new VendorException("The status must be one of new, open, pending, hold, solved or closed.");
            }
            answer = get(credential, "/api/v2/search?query={query}&per_page={n}&sort_by=updated_at&sort_order=desc",
                    "type:ticket status:" + wanted, limit);
        }
        return answer.map(body -> {
            ArrayNode items = json.createArrayNode();
            JsonNode tickets = body.has("tickets") ? body.path("tickets") : body.path("results");
            tickets.forEach(ticket -> items.add(ticket(ticket)));
            return done(listOf(items), "Read " + items.size() + " ticket(s) from Zendesk.");
        });
    }

    // @find: Zendesk get ticket, tool zendesk__get_ticket, live Zendesk call
    private Mono<ToolResult> getTicket(ToolInvocation invocation, JsonNode arguments, String credential) {
        String id = required(arguments, "id");
        return get(credential, "/api/v2/tickets/{id}", id)
                .zipWith(get(credential, "/api/v2/tickets/{id}/comments", id))
                .map(both -> {
                    ObjectNode item = ticket(both.getT1().path("ticket"));
                    item.put("description", clip(both.getT1().path("ticket").path("description").asText("")));
                    ArrayNode comments = item.putArray("comments");
                    both.getT2().path("comments").forEach(comment -> {
                        ObjectNode entry = comments.addObject();
                        entry.put("author", comment.path("author_id").asText());
                        entry.put("public", comment.path("public").asBoolean(true));
                        entry.put("createdAt", comment.path("created_at").asText());
                        entry.put("body", clip(comment.path("plain_body").asText(comment.path("body").asText(""))));
                    });
                    return done(item, "Read ticket " + id + " from Zendesk.");
                });
    }

    // @find: Zendesk update ticket, tool zendesk__update_ticket, live Zendesk call
    private Mono<ToolResult> updateTicket(ToolInvocation invocation, JsonNode arguments, String credential) {
        String id = required(arguments, "id");
        ObjectNode ticket = json.createObjectNode();
        String status = text(arguments, "status");
        if (status != null) {
            if (!STATUSES.contains(status.toLowerCase())) {
                throw new VendorException("The status must be one of new, open, pending, hold, solved or closed.");
            }
            ticket.put("status", status.toLowerCase());
        }
        String priority = text(arguments, "priority");
        if (priority != null) {
            if (!PRIORITIES.contains(priority.toLowerCase())) {
                throw new VendorException("The priority must be one of low, normal, high or urgent.");
            }
            ticket.put("priority", priority.toLowerCase());
        }
        String assignee = text(arguments, "assignee");
        if (assignee != null) {
            if (assignee.contains("@")) {
                ticket.put("assignee_email", assignee);
            } else if (assignee.matches("\\d{1,18}")) {
                ticket.put("assignee_id", Long.parseLong(assignee));
            } else {
                throw new VendorException("The assignee must be an email address or a Zendesk user id.");
            }
        }
        if (ticket.isEmpty()) {
            throw new VendorException("Say what to change: the status, priority or assignee.");
        }
        ObjectNode body = json.createObjectNode();
        body.set("ticket", ticket);
        return send(HttpMethod.PUT, credential, body, "/api/v2/tickets/{id}", id)
                .map(answer -> done(ticket(answer.path("ticket")), "Updated ticket " + id + " in Zendesk."));
    }

    private Mono<ToolResult> comment(JsonNode arguments, String credential, boolean isPublic) {
        String id = required(arguments, "ticketId");
        String text = required(arguments, "body");
        ObjectNode ticket = json.createObjectNode();
        ObjectNode comment = ticket.putObject("comment");
        comment.put("body", text);
        comment.put("public", isPublic);
        ObjectNode body = json.createObjectNode();
        body.set("ticket", ticket);
        return send(HttpMethod.PUT, credential, body, "/api/v2/tickets/{id}", id).map(answer -> {
            ObjectNode item = ticket(answer.path("ticket"));
            item.put("id", id);
            item.put("public", isPublic);
            return done(
                    item,
                    isPublic
                            ? "Sent a public reply on ticket " + id + " in Zendesk."
                            : "Added a private note to ticket " + id + " in Zendesk.");
        });
    }

    private ObjectNode ticket(JsonNode ticket) {
        ObjectNode item = json.createObjectNode();
        item.put("id", ticket.path("id").asText());
        item.put("title", ticket.path("subject").asText(""));
        item.put("status", ticket.path("status").asText());
        item.put("priority", ticket.path("priority").asText(null));
        item.put("requester", ticket.path("requester_id").asText(null));
        item.put("assignee", ticket.path("assignee_id").asText(null));
        item.put("updatedAt", ticket.path("updated_at").asText());
        return item;
    }

    private static String clip(String text) {
        return text.length() <= BODY_LIMIT ? text : text.substring(0, BODY_LIMIT) + "…";
    }
}
