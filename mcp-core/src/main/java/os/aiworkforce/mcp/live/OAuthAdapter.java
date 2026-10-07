package os.aiworkforce.mcp.live;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

import os.aiworkforce.mcp.model.ToolInvocation;
import os.aiworkforce.mcp.sandbox.SandboxServerAdapter;
import os.aiworkforce.mcp.spi.TokenRefresher;

/**
 * Shared behaviour of the connectors whose credential is an OAuth access token.
 *
 * <p>The integrations service owns consent, code exchange and refresh; an adapter only ever
 * receives a fresh access token and sends it as a bearer token. A 401 therefore means the
 * connection was revoked or expired beyond refresh, and the fix is to connect the account again.
 *
 * <p>When a {@link TokenRefresher} is available, a 401 from the provider triggers one refresh and
 * one retry of the call. A call is repeated even if it is not idempotent (a send, a create)
 * because a 401 proves the provider rejected the request before processing it, so nothing can have
 * happened twice. If the refresh fails, or the retry is rejected again, the connection is marked
 * as needing to be reconnected and the call fails with a plain message. No other failure (a
 * timeout, a dropped connection, a 5xx) is ever retried here.
 */
public abstract class OAuthAdapter extends LiveServerAdapter {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern HAS_ZONE = Pattern.compile(".*(Z|[+-]\\d{2}:?\\d{2})$");

    protected OAuthAdapter(
            SandboxServerAdapter sandbox, String vendor, ObjectMapper json, WebClient.Builder http, String baseUrl) {
        super(sandbox, vendor, json, http, baseUrl);
    }

    private volatile Supplier<TokenRefresher> refresher = () -> null;

    /** Wires the connection store in; looked up on first use so start-up order does not matter. */
    public void useRefresher(Supplier<TokenRefresher> refresher) {
        this.refresher = refresher == null ? () -> null : refresher;
    }

    /** The sentence a person reads when this connector can only be fixed by connecting it again. */
    public String reconnectMessage() {
        return vendor() + " needs to be reconnected by an administrator.";
    }

    @Override
    protected Mono<String> renewFor(ToolInvocation invocation, String credential) {
        TokenRefresher store = refresher.get();
        if (store == null || invocation == null) {
            return Mono.empty();
        }
        return store.refresh(invocation.orgId(), invocation.server(), credential)
                .onErrorResume(error -> Mono.empty())
                .switchIfEmpty(Mono.defer(() -> reconnect(store, invocation)
                        .then(Mono.<String>error(new VendorException(reconnectMessage())))));
    }

    @Override
    protected Mono<Void> rejectedAfterRenewal(ToolInvocation invocation, String credential) {
        TokenRefresher store = refresher.get();
        if (store == null || invocation == null) {
            return Mono.empty();
        }
        return reconnect(store, invocation).then(Mono.error(new VendorException(reconnectMessage())));
    }

    private Mono<Void> reconnect(TokenRefresher store, ToolInvocation invocation) {
        return store.markReconnectRequired(invocation.orgId(), invocation.server(), reconnectMessage())
                .onErrorResume(error -> Mono.empty());
    }

    @Override
    protected void authorize(HttpHeaders headers, String token) {
        headers.setBearerAuth(token);
    }

    @Override
    protected String describe(WebClientResponseException response) {
        if (response.getStatusCode().value() == 401) {
            return vendor() + " no longer accepts the connection. It needs to be connected again.";
        }
        return super.describe(response);
    }

    // ---- Arguments ----------------------------------------------------------------------------

    /** Refuses a value that could add a header: a line break in an address or a subject. */
    protected static String noLineBreaks(String field, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                throw new VendorException("The " + field + " cannot contain line breaks.");
            }
        }
        return value;
    }

    /** The addresses in a comma or semicolon separated list. */
    protected static List<String> recipients(String to) {
        List<String> addresses = new ArrayList<>();
        for (String part : noLineBreaks("recipient", to).split("[,;]")) {
            String address = part.strip();
            if (!address.isEmpty()) {
                addresses.add(address);
            }
        }
        if (addresses.isEmpty()) {
            throw new VendorException("The to is needed for this action.");
        }
        return addresses;
    }

    protected static boolean isDate(String value) {
        return DATE.matcher(value).matches();
    }

    /** A date becomes the start of that day in UTC; a date-time without an offset is taken as UTC. */
    protected static String startOf(String value) {
        return isDate(value) ? value + "T00:00:00Z" : withZone(value);
    }

    protected static String endOf(String value) {
        return isDate(value) ? value + "T23:59:59Z" : withZone(value);
    }

    protected static String withZone(String value) {
        return HAS_ZONE.matcher(value).matches() ? value : value + "Z";
    }

    protected static String today() {
        return LocalDate.now(java.time.ZoneOffset.UTC).toString();
    }

    /** The values of a "values" argument as text cells. */
    protected List<String> cells(JsonNode arguments, String field) {
        JsonNode values = arguments.get(field);
        if (values == null || !values.isArray() || values.isEmpty()) {
            throw new VendorException("The " + field + " are needed for this action, one per column.");
        }
        List<String> cells = new ArrayList<>();
        values.forEach(value -> cells.add(value.isValueNode() && !value.isNull() ? value.asText() : value.toString()));
        return cells;
    }

    // ---- Text ---------------------------------------------------------------------------------

    /** Readable text from an HTML body. */
    protected static String plain(String html) {
        if (html == null) {
            return "";
        }
        String text = html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>|</h[1-6]>", "\n")
                .replaceAll("(?s)<[^>]*>", "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    protected static String cap(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
