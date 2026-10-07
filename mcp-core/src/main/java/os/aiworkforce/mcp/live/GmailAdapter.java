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

/** The Gmail API with an OAuth access token. */
public final class GmailAdapter extends OAuthAdapter {

    public static final String BASE_URL = "https://gmail.googleapis.com";

    private static final String USER = "/gmail/v1/users/me";
    private static final int FETCH_CONCURRENCY = 5;
    private static final int BODY_LIMIT = 50_000;

    public GmailAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Gmail", json, http, baseUrl);
        on("list_messages", this::listMessages);
        on("get_message", this::getMessage);
        on("draft_message", this::draftMessage);
        on("send_message", this::sendMessage);
    }

    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, USER + "/profile").map(profile -> {
            String email = profile.path("emailAddress").asText("");
            return email.isEmpty() ? "Gmail account" : email;
        });
    }

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

    private static String header(JsonNode message, String name) {
        for (JsonNode header : message.path("payload").path("headers")) {
            if (name.equalsIgnoreCase(header.path("name").asText())) {
                return header.path("value").asText("");
            }
        }
        return "";
    }

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
