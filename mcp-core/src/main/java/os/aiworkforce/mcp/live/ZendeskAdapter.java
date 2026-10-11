// @find: zendesk, support, tickets, helpdesk, list tickets, get ticket, update ticket, public reply, private note, subdomain email api token, live adapter, real API, customer support
// @what: Live Zendesk connector: runs zendesk__ tools (list, get, update tickets, reply or add notes) against a Zendesk subdomain using email and API token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin (case "zendesk"); ToolGateway.invoke reaches it through LiveServerAdapter.invoke, and send_reply passes the approval gate first; the base address comes from the stored subdomain through baseFor; each tool makes one or two calls to the Zendesk subdomain; base class LiveServerAdapter
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
 *
 * <p>Used by the tool gateway when a workspace has stored a Zendesk credential. list_tickets and
 * get_ticket are read tools; update_ticket and add_note are write tools (a person is asked first when
 * the workspace policy says so); send_reply is outbound and always asks a person first, because the
 * requester receives it. A reply is not idempotent, so a dropped connection is reported as
 * indeterminate and the reply is never repeated.
 *
 * <p>Failures: a malformed credential, a missing argument or a value outside the allowed statuses and
 * priorities becomes a plain failed result; HTTP errors are described by the base class.
 */
public final class ZendeskAdapter extends LiveServerAdapter {

    /** The address comes from the credential's subdomain. */
    /** Null on purpose: the real address is taken from the subdomain in each stored credential. */
    public static final String BASE_URL = null;

    /** The ticket statuses Zendesk accepts. */
    private static final Set<String> STATUSES = Set.of("new", "open", "pending", "hold", "solved", "closed");
    /** The ticket priorities Zendesk accepts. */
    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    /** The most characters of a ticket description or comment returned; longer text is cut. */
    private static final int BODY_LIMIT = 2000;

    /** True when a base URL was injected (tests), in which case the credential subdomain is not used for routing. */
    private final boolean fixedBase;

    /** Registers the five Zendesk tools; add_note and send_reply share one method and differ only in whether the comment is public. */
    public ZendeskAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Zendesk", json, http, baseUrl);
        this.fixedBase = baseUrl != null;
        on("list_tickets", this::listTickets);
        on("get_ticket", this::getTicket);
        on("update_ticket", this::updateTicket);
        on("add_note", (invocation, arguments, credential) -> comment(arguments, credential, false));
        on("send_reply", (invocation, arguments, credential) -> comment(arguments, credential, true));
    }

    /** The parsed credential: a validated base address, the agent email and the API token. */
    private record Account(String base, String email, String token) {}

    /**
     * Parses the stored JSON credential and builds the address with {@link Hosts#zendeskBase}, so only
     * Zendesk hosts are ever called. Throws a plain refusal for malformed or incomplete input.
     */
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

    /** The Zendesk address of this credential, or null when a fixed base URL is in use. */
    @Override
    protected String baseFor(String credential) {
        Account account = account(credential);
        return fixedBase ? null : account.base();
    }

    /** Sends the agent email followed by /token as the user name, and the API token as the password. */
    @Override
    protected void authorize(HttpHeaders headers, String credential) {
        Account account = account(credential);
        headers.setBasicAuth(account.email() + "/token", account.token());
        headers.set(HttpHeaders.ACCEPT, "application/json");
    }

    /** Connection check: GET the current user; answers the name and the Zendesk host. */
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
    /**
     * Read tool. Without a status, GET the tickets, newest update first. With a status, a ticket search
     * is used instead, so the answer can lag behind recent changes. At most 100 tickets. An unknown
     * status is refused before any call.
     */
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
    /** Read tool. Two calls made together: the ticket and its comments. Long text is cut at the body limit. */
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
    /**
     * Write tool. PUT the status, priority or assignee that were given; the assignee may be an email
     * address or a numeric user id. No comment is added and no message is sent by this tool, although
     * Zendesk triggers configured by the account may still notify people. Refuses before any call when
     * a value is not allowed or nothing is given to change.
     */
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

    /**
     * Shared by add_note (a private note, a write tool) and send_reply (a public reply, an outbound tool
     * that always asks a person first). PUT a comment on the ticket; a public comment is delivered to
     * the requester by Zendesk. Not idempotent: a repeat would add the comment twice.
     */
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

    /** Maps a Zendesk ticket to the plain shape the tools return. */
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

    /** Cuts text at the body limit and marks the cut with an ellipsis. */
    private static String clip(String text) {
        return text.length() <= BODY_LIMIT ? text : text.substring(0, BODY_LIMIT) + "…";
    }
}
