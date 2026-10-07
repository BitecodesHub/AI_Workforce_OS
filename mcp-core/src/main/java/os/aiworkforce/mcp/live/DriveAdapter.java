package os.aiworkforce.mcp.live;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/** The Google Drive v3 API with an OAuth access token. */
public final class DriveAdapter extends OAuthAdapter {

    public static final String BASE_URL = "https://www.googleapis.com";

    static final String ABOUT = "/drive/v3/about?fields=user(displayName,emailAddress)";
    private static final int CONTENT_LIMIT = 100_000;
    private static final Set<String> TEXT_TYPES = Set.of(
            "application/json",
            "application/xml",
            "application/x-yaml",
            "application/yaml",
            "application/javascript",
            "application/csv",
            "application/x-sh",
            "application/sql");

    public DriveAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Google Drive", json, http, baseUrl);
        on("list_files", this::listFiles);
        on("get_file", this::getFile);
        on("create_file", this::createFile);
    }

    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, ABOUT).map(DriveAdapter::account);
    }

    static String account(JsonNode about) {
        JsonNode user = about.path("user");
        String email = user.path("emailAddress").asText("");
        if (email.isEmpty()) {
            email = user.path("displayName").asText("");
        }
        return email.isEmpty() ? "Google account" : email;
    }

    /** Escapes a value placed inside a single-quoted Drive query string. */
    static String quoted(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private Mono<ToolResult> listFiles(ToolInvocation invocation, JsonNode arguments, String token) {
        String folder = text(arguments, "folderId");
        String query = folder == null ? "trashed=false" : quoted(folder) + " in parents and trashed=false";
        return get(
                        token,
                        "/drive/v3/files?q={q}&fields={fields}&pageSize={size}",
                        query,
                        "files(id,name,mimeType,modifiedTime,parents)",
                        limit(arguments, 20, 100))
                .map(answer -> {
                    ArrayNode items = json.createArrayNode();
                    answer.path("files").forEach(file -> {
                        ObjectNode item = items.addObject();
                        item.put("id", file.path("id").asText());
                        item.put("name", file.path("name").asText());
                        item.put("mimeType", file.path("mimeType").asText());
                        item.put("modifiedAt", file.path("modifiedTime").asText(null));
                        item.put("folderId", file.path("parents").path(0).asText(null));
                    });
                    return done(listOf(items), "Read " + items.size() + " file(s) from Google Drive.");
                });
    }

    private Mono<ToolResult> getFile(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = required(arguments, "id");
        return get(token, "/drive/v3/files/{id}?fields={fields}", id, "id,name,mimeType").flatMap(meta -> {
            String type = meta.path("mimeType").asText("");
            String name = meta.path("name").asText("");
            Mono<String> content;
            if (type.equals("application/vnd.google-apps.spreadsheet")) {
                content = getText(token, "/drive/v3/files/{id}/export?mimeType={type}", id, "text/csv");
            } else if (type.equals("application/vnd.google-apps.document")
                    || type.equals("application/vnd.google-apps.presentation")) {
                content = getText(token, "/drive/v3/files/{id}/export?mimeType={type}", id, "text/plain");
            } else if (type.startsWith("application/vnd.google-apps.")) {
                return Mono.just(ToolResult.failed("\"" + name + "\" is a Google item that cannot be read as text."));
            } else if (textLike(type)) {
                content = getText(token, "/drive/v3/files/{id}?alt=media", id);
            } else {
                return Mono.just(ToolResult.failed(
                        "\"" + name + "\" is a binary file (" + type + "), so its contents cannot be read as text."));
            }
            return content.map(body -> {
                ObjectNode item = json.createObjectNode();
                item.put("id", meta.path("id").asText());
                item.put("name", name);
                item.put("mimeType", type);
                item.put("content", cap(body, CONTENT_LIMIT));
                item.put("truncated", body.length() > CONTENT_LIMIT);
                return done(item, "Read \"" + name + "\" from Google Drive.");
            });
        });
    }

    private Mono<ToolResult> createFile(ToolInvocation invocation, JsonNode arguments, String token) {
        String name = required(arguments, "name");
        String content = required(arguments, "content");
        String folder = text(arguments, "folderId");
        ObjectNode metadata = json.createObjectNode();
        metadata.put("name", name);
        if (folder != null) {
            metadata.putArray("parents").add(folder);
        }
        String boundary = "aiwos-" + UUID.randomUUID();
        String body = "--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + metadata + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                + content + "\r\n"
                + "--" + boundary + "--";
        return http.post()
                .uri("/upload/drive/v3/files?uploadType=multipart&fields={fields}", "id,name,mimeType")
                .headers(headers -> authorize(headers, token))
                .contentType(MediaType.parseMediaType("multipart/related; boundary=" + boundary))
                .bodyValue(body.getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(file -> {
                    ObjectNode item = json.createObjectNode();
                    item.put("id", file.path("id").asText());
                    item.put("name", file.path("name").asText(name));
                    item.put("mimeType", file.path("mimeType").asText("text/plain"));
                    return done(item, "Created \"" + name + "\" in Google Drive.");
                });
    }

    private static boolean textLike(String type) {
        return type.startsWith("text/") || TEXT_TYPES.contains(type) || type.endsWith("+json") || type.endsWith("+xml");
    }
}
