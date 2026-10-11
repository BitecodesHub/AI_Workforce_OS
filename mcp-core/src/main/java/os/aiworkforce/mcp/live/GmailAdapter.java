// @find: gmail, email, inbox, mail, list messages, get message, draft message, send message, draft email, send email, OAuth, MIME, live adapter, real API, Gmail API
// @what: Live Gmail connector: runs gmail__ tools (list, read, draft, send messages) against the Gmail API with an OAuth access token; sending always needs approval.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin (case "gmail"); ToolGateway.invoke reaches it through LiveServerAdapter.invoke, and send_message passes the approval gate first; OAuthAdapter supplies the bearer token and the one renew-and-retry after a 401; calls go to gmail.googleapis.com; base class OAuthAdapter
package os.aiworkforce.mcp.live;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * The Gmail API with an OAuth access token.
 *
 * <p>Used by the tool gateway when a workspace has connected a Google account. list_messages and
 * get_message are read tools; draft_message is a write tool (a person is asked first when the
 * workspace policy says so); send_message is outbound and always asks a person first. A send is not
 * idempotent, so a dropped connection is reported as indeterminate and the send is never repeated.
 *
 * <p>Failures: missing or malformed arguments (line breaks in an address or subject) become a plain
 * failed result; a 401 triggers one token renewal in the base class.
 */
public final class GmailAdapter extends OAuthAdapter {

    /** The production Gmail API host; tests pass another base URL to the constructor. */
    public static final String BASE_URL = "https://gmail.googleapis.com";

    /** The path prefix of the signed-in mailbox. */
    private static final String USER = "/gmail/v1/users/me";
    /** How many message details are fetched at the same time when listing. */
    private static final int FETCH_CONCURRENCY = 5;
    /** The most characters of message text returned by get_message. */
    private static final int BODY_LIMIT = 50_000;

    /** Registers the four Gmail tools. The sandbox supplies the tool definitions. */
    public GmailAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Gmail", json, http, baseUrl);
        on("list_messages", this::listMessages);
        on("get_message", this::getMessage);
        on("draft_message", this::draftMessage);
        on("send_message", this::sendMessage);
    }

    /** Connection check: GET the mailbox profile; answers the email address. */
    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, USER + "/profile").map(profile -> {
            String email = profile.path("emailAddress").asText("");
            return email.isEmpty() ? "Gmail account" : email;
        });
    }

    // @find: Gmail list messages, tool gmail__list_messages, live Gmail call
    /**
     * Read tool. One call lists message ids (optionally with a Gmail search query), then the sender,
     * subject and date of each are fetched, a few at a time and in the original order. A message deleted
     * in between is skipped. Costs up to one call per message against the Gmail quota.
     */
    private Mono<ToolResult> listMessages(ToolInvocation invocation, JsonNode arguments, String token) {
        String query = text(arguments, "query");
        int max = limit(arguments, 10, 50);
        Mono<JsonNode> ids = query == null
                ? get(token, USER + "/messages?maxResults={max}", max)
                : get(token, USER + "/messages?maxResults={max}&q={q}", max, query);
        return ids.flatMap(answer -> {
            java.util.List<String> list = new java.util.ArrayList<>();
            answer.path("messages").forEach(message -> list.add(message.path("id").asText()));
            return Flux.fromIterable(list)
                    .flatMapSequential(
                            id -> get(
                                    token,
                                    USER + "/messages/{id}?format=metadata&metadataHeaders=From"
                                            + "&metadataHeaders=Subject&metadataHeaders=Date",
                                    id)
                                    // A message deleted between the list and the fetch is skipped,
                                    // rather than failing the whole listing.
                                    .onErrorResume(WebClientResponseException.NotFound.class, gone -> Mono.empty()),
                            FETCH_CONCURRENCY)
                    .collectList()
                    .map(messages -> {
                        ArrayNode items = json.createArrayNode();
                        messages.forEach(message -> {
                            ObjectNode item = items.addObject();
                            item.put("id", message.path("id").asText());
                            item.put("from", header(message, "From"));
                            item.put("subject", header(message, "Subject"));
                            item.put("snippet", message.path("snippet").asText(""));
                            item.put("receivedAt", receivedAt(message));
                            item.set("labels", message.path("labelIds").isArray()
                                    ? message.path("labelIds")
                                    : json.createArrayNode());
                        });
                        return done(listOf(items), "Read " + items.size() + " message(s) from Gmail.");
                    });
        });
    }

    // @find: Gmail get message, tool gmail__get_message, live Gmail call
    /**
     * Read tool. GET one message in full and answers headers plus the text body: the first plain part,
     * else the first HTML part reduced to text, cut at the body limit.
     */
    private Mono<ToolResult> getMessage(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = required(arguments, "id");
        return get(token, USER + "/messages/{id}?format=full", id).map(message -> {
            ObjectNode item = json.createObjectNode();
            item.put("id", message.path("id").asText());
            item.put("from", header(message, "From"));
            item.put("to", header(message, "To"));
            item.put("subject", header(message, "Subject"));
            item.put("receivedAt", receivedAt(message));
            item.set("labels", message.path("labelIds").isArray() ? message.path("labelIds") : json.createArrayNode());
            String[] bodies = new String[2];
            collect(message.path("payload"), bodies);
            String body = bodies[0] != null ? bodies[0] : bodies[1] != null ? plain(bodies[1]) : "";
            item.put("body", cap(body, BODY_LIMIT));
            return done(item, "Read the Gmail message \"" + header(message, "Subject") + "\".");
        });
    }

    // @find: Gmail draft message, tool gmail__draft_message, live Gmail call
    /**
     * Write tool. POST a draft built from to, subject and body; nothing is sent. The draft appears in
     * the Drafts folder of the mailbox.
     */
    private Mono<ToolResult> draftMessage(ToolInvocation invocation, JsonNode arguments, String token) {
        String raw = raw(arguments);
        ObjectNode body = json.createObjectNode();
        body.putObject("message").put("raw", raw);
        return send(HttpMethod.POST, token, body, USER + "/drafts").map(draft -> {
            ObjectNode item = json.createObjectNode();
            item.put("id", draft.path("id").asText());
            item.put("to", text(arguments, "to"));
            item.put("subject", text(arguments, "subject"));
            return done(item, "Saved a draft to " + text(arguments, "to") + " in Gmail.");
        });
    }

    // @find: Gmail send message, tool gmail__send_message, live Gmail call
    /**
     * Outbound tool; a person always approves it first. POST the message to Gmail for delivery from the
     * connected mailbox. Fails before any call when an argument is missing or contains a line break.
     */
    private Mono<ToolResult> sendMessage(ToolInvocation invocation, JsonNode arguments, String token) {
        ObjectNode body = json.createObjectNode();
        body.put("raw", raw(arguments));
        return send(HttpMethod.POST, token, body, USER + "/messages/send").map(sent -> {
            ObjectNode item = json.createObjectNode();
            item.put("id", sent.path("id").asText());
            item.put("threadId", sent.path("threadId").asText(null));
            item.put("to", text(arguments, "to"));
            return done(item, "Sent an email to " + text(arguments, "to") + " from Gmail.");
        });
    }

    // ---- Message format -----------------------------------------------------------------------

    /**
     * Builds the RFC 822 message from the arguments and encodes it as unpadded URL-safe base64, which
     * Gmail expects. Line breaks are refused in the address and subject so no header can be injected.
     */
    private static String raw(JsonNode arguments) {
        String to = noLineBreaks("to address", required(arguments, "to"));
        String subject = noLineBreaks("subject", required(arguments, "subject"));
        String body = required(arguments, "body");
        String message = "To: " + to + "\r\n"
                + "Subject: " + encodeSubject(subject) + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n"
                + "\r\n"
                + body.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(message.getBytes(StandardCharsets.UTF_8));
    }

    /** ASCII passes through; anything else becomes RFC 2047 encoded words of at most 75 characters. */
    static String encodeSubject(String subject) {
        boolean ascii = subject.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
        if (ascii) {
            return subject;
        }
        StringBuilder out = new StringBuilder();
        java.io.ByteArrayOutputStream chunk = new java.io.ByteArrayOutputStream();
        int[] points = subject.codePoints().toArray();
        for (int point : points) {
            byte[] bytes = new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8);
            if (chunk.size() + bytes.length > 45) {
                flush(out, chunk);
            }
            chunk.writeBytes(bytes);
        }
        flush(out, chunk);
        return out.toString();
    }

    /** Appends the buffered bytes as one encoded word, folded onto a new line when needed, and empties the buffer. */
    private static void flush(StringBuilder out, java.io.ByteArrayOutputStream chunk) {
        if (chunk.size() == 0) {
            return;
        }
        if (out.length() > 0) {
            out.append("\r\n ");
        }
        out.append("=?UTF-8?B?").append(Base64.getEncoder().encodeToString(chunk.toByteArray())).append("?=");
        chunk.reset();
    }

    /** The value of a named header of a message, ignoring case; empty when absent. */
    private static String header(JsonNode message, String name) {
        for (JsonNode header : message.path("payload").path("headers")) {
            if (name.equalsIgnoreCase(header.path("name").asText())) {
                return header.path("value").asText("");
            }
        }
        return "";
    }

    /** The arrival time as an ISO instant from the internal date, falling back to the Date header. */
    private static String receivedAt(JsonNode message) {
        String millis = message.path("internalDate").asText("");
        if (!millis.isEmpty()) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(millis)).toString();
            } catch (NumberFormatException ignored) {
                // fall back to the Date header
            }
        }
        return header(message, "Date");
    }

    /* bodies[0] is the first text/plain part, bodies[1] the first text/html part. */
    /** Walks the message parts recursively and keeps the first plain and the first HTML body. Undecodable parts are skipped. */
    private static void collect(JsonNode part, String[] bodies) {
        String type = part.path("mimeType").asText("");
        String data = part.path("body").path("data").asText("");
        if (!data.isEmpty()) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
                if (type.equals("text/plain") && bodies[0] == null) {
                    bodies[0] = decoded;
                } else if (type.equals("text/html") && bodies[1] == null) {
                    bodies[1] = decoded;
                }
            } catch (IllegalArgumentException ignored) {
                // an undecodable part is skipped
            }
        }
        part.path("parts").forEach(child -> collect(child, bodies));
    }
}
