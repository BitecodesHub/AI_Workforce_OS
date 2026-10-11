// @find: google drive, drive, files, documents, list files, get file, create file, save document, OAuth, live adapter, real API, Drive API
// @what: Live Google Drive connector: runs drive__ tools (list, read, create files) against the Google Drive API with an OAuth access token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin (case "drive"); ToolGateway.invoke reaches it through LiveServerAdapter.invoke; OAuthAdapter supplies the bearer token and the one renew-and-retry after a 401; each tool makes one or two calls to www.googleapis.com; base class OAuthAdapter
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

/**
 * The Google Drive v3 API with an OAuth access token.
 *
 * <p>Used by the tool gateway when a workspace has connected a Google account. list_files and get_file
 * are read tools; create_file is a write tool, so a person is asked first when the workspace policy
 * says so. There is no destructive or outbound tool: files are never deleted, shared or moved.
 *
 * <p>Failures: a missing argument, a Google item that cannot be read as text or a binary file
 * becomes a plain failed result; a 401 triggers one token renewal in the base class.
 */
public final class DriveAdapter extends OAuthAdapter {

    /** The production Google API host; tests pass another base URL to the constructor. */
    public static final String BASE_URL = "https://www.googleapis.com";

    /** The Drive "about" request used for the connection check; also used by SheetsAdapter. */
    static final String ABOUT = "/drive/v3/about?fields=user(displayName,emailAddress)";
    /** The most characters of file text returned by get_file; longer text is cut and flagged as truncated. */
    private static final int CONTENT_LIMIT = 100_000;
    /** Media types outside text/ that are still readable as text. */
    private static final Set<String> TEXT_TYPES = Set.of(
            "application/json",
            "application/xml",
            "application/x-yaml",
            "application/yaml",
            "application/javascript",
            "application/csv",
            "application/x-sh",
            "application/sql");

    /** Registers the three Drive tools. The sandbox supplies the tool definitions. */
    public DriveAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, "Google Drive", json, http, baseUrl);
        on("list_files", this::listFiles);
        on("get_file", this::getFile);
        on("create_file", this::createFile);
    }

    /** Connection check: GET the Drive account details; answers the email address of the signed-in account. */
    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, ABOUT).map(DriveAdapter::account);
    }

    /** The label of the account in a Drive "about" answer: email, else display name, else a generic label. */
    static String account(JsonNode about) {
        JsonNode user = about.path("user");
        String email = user.path("emailAddress").asText("");
        if (email.isEmpty()) {
            email = user.path("displayName").asText("");
        }
        return email.isEmpty() ? "Google account" : email;
    }

    /**
     * Escapes a value placed inside a single-quoted Drive query string.
     *
     * Wraps a value in single quotes for a Drive query, escaping backslashes and quotes so it cannot alter the query.
     */
    static String quoted(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    // @find: Google Drive list files, tool drive__list_files, live Google Drive call
    /**
     * Read tool. GET the files list, optionally limited to one folder; trashed files are always left out.
     * Answers id, name, type, modification time and first parent folder, at most 100 items.
     */
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

    // @find: Google Drive get file, tool drive__get_file, live Google Drive call
    /**
     * Read tool. Two calls: GET the file metadata, then its text. Google Sheets are exported as CSV,
     * Docs and Slides as plain text, and text-like files are downloaded as they are. Other Google items
     * and binary files fail with a plain message. Text is cut at the content limit.
     */
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

    // @find: Google Drive create file, tool drive__create_file, live Google Drive call
    /**
     * Write tool. One multipart upload that creates a plain text file with the given name and content,
     * optionally in a folder. Not idempotent: a dropped connection is reported as indeterminate and is
     * not repeated, so no duplicate file is created.
     */
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

    /** True for media types whose body can be returned as text. */
    private static boolean textLike(String type) {
        return type.startsWith("text/") || TEXT_TYPES.contains(type) || type.endsWith("+json") || type.endsWith("+xml");
    }
}
