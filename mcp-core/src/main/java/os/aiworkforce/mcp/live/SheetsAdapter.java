package os.aiworkforce.mcp.live;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.model.ToolResult;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;

/**
 * The Google Sheets v4 API with an OAuth access token. Spreadsheets are listed, and the account is
 * named, through the Drive API, which is why a second host is involved.
 */
public final class SheetsAdapter extends OAuthAdapter {

    public static final String BASE_URL = "https://sheets.googleapis.com";

    private static final String DRIVE_URL = "https://www.googleapis.com";
    private static final String SHEET_MIME = "application/vnd.google-apps.spreadsheet";
    private static final int MAX_COLUMNS = 702; // ZZ

    private final String driveBaseUrl;

    public SheetsAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        this(sandbox, json, http, baseUrl, DRIVE_URL);
    }

    public SheetsAdapter(
            SandboxServerAdapter sandbox,
            ObjectMapper json,
            WebClient.Builder http,
            String baseUrl,
            String driveBaseUrl) {
        super(sandbox, "Google Sheets", json, http, baseUrl);
        this.driveBaseUrl = driveBaseUrl;
        on("list_spreadsheets", this::listSpreadsheets);
        on("list_rows", this::listRows);
        on("append_row", this::appendRow);
        on("update_row", this::updateRow);
    }

    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, driveBaseUrl + DriveAdapter.ABOUT).map(DriveAdapter::account);
    }

    private Mono<ToolResult> listSpreadsheets(ToolInvocation invocation, JsonNode arguments, String token) {
        return get(
                        token,
                        driveBaseUrl + "/drive/v3/files?q={q}&fields={fields}&pageSize={size}",
                        "mimeType=" + DriveAdapter.quoted(SHEET_MIME) + " and trashed=false",
                        "files(id,name,modifiedTime)",
                        limit(arguments, 50, 100))
                .map(answer -> {
                    ArrayNode items = json.createArrayNode();
                    answer.path("files").forEach(file -> {
                        ObjectNode item = items.addObject();
                        item.put("id", file.path("id").asText());
                        item.put("name", file.path("name").asText());
                        item.put("modifiedAt", file.path("modifiedTime").asText(null));
                    });
                    return done(listOf(items), "Found " + items.size() + " spreadsheet(s) in Google Sheets.");
                });
    }

    private Mono<ToolResult> listRows(ToolInvocation invocation, JsonNode arguments, String token) {
        String spreadsheet = required(arguments, "spreadsheetId");
        int max = limit(arguments, 50, 500);
        return sheetName(token, spreadsheet, text(arguments, "sheet")).flatMap(sheet -> get(
                        token,
                        "/v4/spreadsheets/{id}/values/{range}",
                        spreadsheet,
                        quote(sheet) + "!A1:ZZ" + max)
                .map(answer -> {
                    ArrayNode items = json.createArrayNode();
                    int row = 0;
                    for (JsonNode values : answer.path("values")) {
                        row++;
                        if (values.isEmpty()) {
                            continue;
                        }
                        ObjectNode item = items.addObject();
                        item.put("id", spreadsheet + ":" + quote(sheet) + "!A" + row + ":" + column(values.size()) + row);
                        item.put("row", row);
                        item.put("sheet", sheet);
                        ArrayNode cells = item.putArray("values");
                        values.forEach(value -> cells.add(value.asText()));
                    }
                    return done(listOf(items), "Read " + items.size() + " row(s) from \"" + sheet + "\".");
                }));
    }

    private Mono<ToolResult> appendRow(ToolInvocation invocation, JsonNode arguments, String token) {
        String spreadsheet = required(arguments, "spreadsheetId");
        List<String> cells = cells(arguments, "values");
        return sheetName(token, spreadsheet, text(arguments, "sheet")).flatMap(sheet -> send(
                        HttpMethod.POST,
                        token,
                        rows(null, cells),
                        "/v4/spreadsheets/{id}/values/{range}:append?valueInputOption=USER_ENTERED"
                                + "&insertDataOption=INSERT_ROWS",
                        spreadsheet,
                        quote(sheet))
                .map(answer -> {
                    ObjectNode item = json.createObjectNode();
                    item.put("updatedRange", answer.path("updates").path("updatedRange").asText(null));
                    item.put("updatedRows", answer.path("updates").path("updatedRows").asInt(1));
                    return done(item, "Added a row to \"" + sheet + "\" in Google Sheets.");
                }));
    }

    private Mono<ToolResult> updateRow(ToolInvocation invocation, JsonNode arguments, String token) {
        String id = required(arguments, "id");
        int split = id.indexOf(':');
        if (split <= 0 || split == id.length() - 1) {
            throw new VendorException(
                    "The id must be the row id from list_rows, in the form <spreadsheetId>:<range>.");
        }
        String spreadsheet = id.substring(0, split);
        String range = id.substring(split + 1);
        return send(
                        HttpMethod.PUT,
                        token,
                        rows(range, cells(arguments, "values")),
                        "/v4/spreadsheets/{id}/values/{range}?valueInputOption=USER_ENTERED",
                        spreadsheet,
                        range)
                .map(answer -> {
                    ObjectNode item = json.createObjectNode();
                    item.put("updatedRange", answer.path("updatedRange").asText(range));
                    item.put("updatedCells", answer.path("updatedCells").asInt(0));
                    return done(item, "Updated a row in Google Sheets.");
                });
    }

    private Mono<String> sheetName(String token, String spreadsheet, String requested) {
        if (requested != null) {
            return Mono.just(requested);
        }
        return get(token, "/v4/spreadsheets/{id}?fields={fields}", spreadsheet, "sheets.properties.title")
                .map(answer -> {
                    String title = answer.path("sheets").path(0).path("properties").path("title").asText("");
                    if (title.isEmpty()) {
                        throw new VendorException("That spreadsheet has no sheets.");
                    }
                    return title;
                });
    }

    private ObjectNode rows(String range, List<String> cells) {
        ObjectNode body = json.createObjectNode();
        if (range != null) {
            body.put("range", range);
        }
        body.put("majorDimension", "ROWS");
        ArrayNode row = body.putArray("values").addArray();
        cells.forEach(row::add);
        return body;
    }

    /** A sheet name as it is written in A1 notation. */
    static String quote(String sheet) {
        return "'" + sheet.replace("'", "''") + "'";
    }

    static String column(int number) {
        int n = Math.max(1, Math.min(number, MAX_COLUMNS));
        List<Character> letters = new ArrayList<>();
        while (n > 0) {
            n--;
            letters.add(0, (char) ('A' + n % 26));
            n /= 26;
        }
        StringBuilder out = new StringBuilder();
        letters.forEach(out::append);
        return out.toString();
    }
}
