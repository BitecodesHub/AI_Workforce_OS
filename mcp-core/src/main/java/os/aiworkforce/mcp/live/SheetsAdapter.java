// @find: google sheets, sheets, spreadsheets, rows, list spreadsheets, list rows, append row, update row, OAuth, live adapter, real API, Sheets API
// @what: Live Google Sheets connector: runs sheets__ tools (list spreadsheets and rows, append and update rows) against the Google Sheets API with an OAuth access token.
// @flow: Registered by SandboxServerRegistry beside the sandbox twin (case "sheets"); ToolGateway.invoke reaches it through LiveServerAdapter.invoke; OAuthAdapter supplies the bearer token and the one renew-and-retry after a 401; spreadsheet data is read and written on sheets.googleapis.com, listing and the connection check use www.googleapis.com (Drive); base class OAuthAdapter
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
 *
 * <p>Used by the tool gateway when a workspace has connected a Google account. list_spreadsheets and
 * list_rows are read tools; append_row and update_row are write tools, so a person is asked first when
 * the workspace policy says so. There is no destructive or outbound tool; no row is ever deleted.
 *
 * <p>Failures: a missing argument, a spreadsheet with no sheets or a malformed row id becomes a plain
 * failed result; a 401 triggers one token renewal in the base class.
 */
public final class SheetsAdapter extends OAuthAdapter {

    /** The production Google Sheets API host; tests pass another base URL to the constructor. */
    public static final String BASE_URL = "https://sheets.googleapis.com";

    /** The production Drive host used for listing spreadsheets and for the connection check. */
    private static final String DRIVE_URL = "https://www.googleapis.com";
    /** The Drive media type that identifies a Google spreadsheet. */
    private static final String SHEET_MIME = "application/vnd.google-apps.spreadsheet";
    /** The last column this adapter addresses (ZZ). */
    private static final int MAX_COLUMNS = 702; // ZZ

    /** The Drive host in use; separate from the Sheets host so tests can point each at a local server. */
    private final String driveBaseUrl;

    /** Production constructor: Sheets at the given base URL and Drive at its production address. */
    public SheetsAdapter(SandboxServerAdapter sandbox, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        this(sandbox, json, http, baseUrl, DRIVE_URL);
    }

    /** Full constructor with a separate Drive base URL, so tests can point both hosts at local servers. Registers the four Sheets tools; the sandbox supplies the tool definitions. */
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

    /** Connection check: asks Drive for the account details (the Sheets API has no such call) and answers the email address. */
    @Override
    protected Mono<String> whoAmI(String token) {
        return get(token, driveBaseUrl + DriveAdapter.ABOUT).map(DriveAdapter::account);
    }

    // @find: Google Sheets list spreadsheets, tool sheets__list_spreadsheets, live Google Sheets call
    /**
     * Read tool. Lists non-trashed Google spreadsheets through the Drive API, at most 100, with id, name
     * and modification time. Calls the Drive host, not the Sheets host.
     */
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

    // @find: Google Sheets list rows, tool sheets__list_rows, live Google Sheets call
    /**
     * Read tool. GET the values of columns A to ZZ for the first rows of one sheet, at most 500 rows.
     * The first sheet is used when none is named, at the cost of one extra call. Empty rows are skipped,
     * but the row numbers stay true. Each answer carries an id that update_row accepts.
     */
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

    // @find: Google Sheets append row, tool sheets__append_row, live Google Sheets call
    /**
     * Write tool. POST one new row below the existing data of a sheet (the first sheet when none is named,
     * at the cost of one extra call). Values are entered as a person would type them, so text that looks
     * like a formula or a date is interpreted. Not idempotent: a dropped connection is reported as
     * indeterminate and is not repeated, so no row is added twice.
     */
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

    // @find: Google Sheets update row, tool sheets__update_row, live Google Sheets call
    /**
     * Write tool. PUT new values over the cells of the range named by a row id from list_rows, in the
     * form spreadsheetId:range. Existing values in those cells are replaced. Refuses a malformed id
     * before any call is made.
     */
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

    /** The requested sheet name, or the title of the first sheet from one metadata call; fails when the spreadsheet has none. */
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

    /** Builds a one-row value body; a range is included for an update and left out for an append. */
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

    /**
     * A sheet name as it is written in A1 notation.
     *
     * Quotes the name for use in a range, doubling embedded quotes.
     */
    static String quote(String sheet) {
        return "'" + sheet.replace("'", "''") + "'";
    }

    /** The column letters for a one-based number (1 is A, 27 is AA), limited to ZZ. */
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
