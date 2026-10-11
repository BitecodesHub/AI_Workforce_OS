// @find: browser address guard, web browser SSRF, private network block, loopback, metadata address, link-local, open page url check, http https only, BrowserAddresses
// @what: Decides whether the web browser may open an address: http or https only, and never a private, loopback, link-local or cloud metadata address, checked after resolving the name.
// @flow: Called by BrowserAdapter before open_page reaches the browser runner, which repeats the same checks for every request the page makes
package os.aiworkforce.mcp.builtin;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

import os.aiworkforce.mcp.live.WebhookAdapter;

/**
 * The rules an address must pass before the browser opens it.
 *
 * <p>The browser runs inside the platform's own network, so an address is a request to make that
 * network fetch something. The same private ranges the webhook refuses are refused here, the host
 * name is resolved first so a public-looking name that points inside is caught, and there is no
 * exception for {@code localhost}, not even while testing. The runner applies the same rules to
 * every request a page makes, redirects included; this check only stops an obviously bad address
 * before anything is started.
 */
public final class BrowserAddresses {

    /** Looks a host name up; replaced in tests so they need no network. */
    @FunctionalInterface
    public interface Resolver {
        /** Every address the host name resolves to; throws when the name cannot be found. */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** The real resolver, backed by the operating system lookup. */
    public static final Resolver SYSTEM = InetAddress::getAllByName;

    /** Message used for every refusal on network grounds. It does not say which rule matched. */
    static final String PRIVATE =
            "That address points inside a private network or at this server, which the browser may not open.";

    /** Not instantiable. */
    private BrowserAddresses() {}

    // @find: may the browser open this address, refuse private address
    /**
     * Why the browser may not open {@code url}, or null when it may.
     *
     * <p>Rules in order: an address is present and parses; the scheme is http or https; a host is
     * present; no user name or password is embedded; the name is not {@code localhost} or an
     * internal suffix; every address the name resolves to is public. Side effect: one DNS lookup
     * through {@code resolver}. A name that does not resolve is refused as not found. A time of check
     * gap exists between this lookup and the runner's own fetch, which is why the runner repeats the
     * checks for every request.
     */
    public static String problem(String url, Resolver resolver) {
        if (url == null || url.isBlank()) {
            return "Give the full web address of the page to open, starting with https://.";
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (Exception e) {
            return "That is not a valid web address.";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return "Only web pages (http or https addresses) can be opened.";
        }
        if (host == null || host.isBlank()) {
            return "Give the full web address of the page to open, starting with https://.";
        }
        if (uri.getRawUserInfo() != null) {
            return "Addresses with a user name or password in them are not opened.";
        }
        String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        String lower = bare.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".internal")
                || lower.endsWith(".local")) {
            return PRIVATE;
        }
        try {
            InetAddress[] addresses = resolver.resolve(bare);
            if (addresses == null || addresses.length == 0) {
                return "The address could not be found. Check that it is spelled correctly.";
            }
            for (InetAddress address : addresses) {
                if (refused(address)) {
                    return PRIVATE;
                }
            }
            return null;
        } catch (UnknownHostException e) {
            return "The address could not be found. Check that it is spelled correctly.";
        }
    }

    /**
     * Loopback, private, link-local (the cloud metadata address among them) or otherwise not public.
     * Shared with the runner-side checks so both apply one definition; the private ranges come from
     * the webhook adapter.
     */
    public static boolean refused(InetAddress address) {
        return address.isLoopbackAddress() || WebhookAdapter.isPrivate(address);
    }
}
