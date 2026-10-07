package os.aiworkforce.mcp.live;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The only way a typed-in host becomes a URL the platform will call.
 *
 * <p>Atlassian, Zendesk and Salesforce live at an address the administrator supplies, which makes
 * each of them a way to point the server at somewhere it should not go. A value is therefore never
 * used as given: it is reduced to one label (or a full host) and rebuilt on the provider's own
 * domain, so {@code https://internal.example.com} or {@code evil.com/x#.atlassian.net} cannot
 * produce anything but a refusal. Every method either returns a normalised {@code https://} base
 * URL with no path, or throws {@link IllegalArgumentException} with a sentence a person can act on.
 */
public final class Hosts {

    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern SALESFORCE_INSTANCE =
            Pattern.compile("[a-z0-9](?:[a-z0-9.-]{0,120}[a-z0-9])?\\.(?:salesforce\\.com|force\\.com)");
    private static final Pattern SALESFORCE_LOGIN = Pattern.compile(
            "(?:login|test)\\.salesforce\\.com|[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.sandbox)?\\.my\\.salesforce\\.com");
    private static final Pattern MICROSOFT_TENANT =
            Pattern.compile("common|organizations|consumers|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|"
                    + "[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?\\.[a-z]{2,}");

    private Hosts() {}

    /** {@code acme}, {@code acme.atlassian.net} or {@code https://acme.atlassian.net/} to {@code https://acme.atlassian.net}. */
    public static String atlassianBase(String site) {
        return "https://" + subdomain(site, "atlassian.net", "Atlassian site", "https://your-team.atlassian.net")
                + ".atlassian.net";
    }

    /** {@code acme} or {@code acme.zendesk.com} to {@code https://acme.zendesk.com}. */
    public static String zendeskBase(String subdomain) {
        return "https://" + subdomain(subdomain, "zendesk.com", "Zendesk subdomain", "your-team.zendesk.com")
                + ".zendesk.com";
    }

    /** A Salesforce instance address from a token response, or a refusal for any other host. */
    public static String salesforceInstance(String url) {
        String host = hostOf(url);
        if (host == null || !SALESFORCE_INSTANCE.matcher(host).matches()) {
            throw new IllegalArgumentException(
                    "Salesforce gave an address that is not a Salesforce domain, so it was not used.");
        }
        return "https://" + host;
    }

    /** A Salesforce login domain: login.salesforce.com, test.salesforce.com or a My Domain. */
    public static String salesforceLoginDomain(String domain) {
        String host = hostOf(domain);
        if (host == null || !SALESFORCE_LOGIN.matcher(host).matches()) {
            throw new IllegalArgumentException(
                    "The login domain must be login.salesforce.com, test.salesforce.com or your My Domain "
                            + "(for example acme.my.salesforce.com).");
        }
        return host;
    }

    /** A Microsoft tenant: common, organizations, consumers, a tenant id or a verified domain. */
    public static String microsoftTenant(String tenant) {
        String value = tenant == null ? "" : tenant.strip().toLowerCase(Locale.ROOT);
        if (!MICROSOFT_TENANT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "The tenant must be a directory (tenant) id, a domain such as acme.onmicrosoft.com, or common.");
        }
        return value;
    }

    private static String subdomain(String input, String suffix, String what, String example) {
        String host = hostOf(input);
        if (host != null && host.endsWith("." + suffix)) {
            host = host.substring(0, host.length() - suffix.length() - 1);
        }
        if (host == null || !LABEL.matcher(host).matches()) {
            throw new IllegalArgumentException("The " + what + " must look like " + example + ".");
        }
        return host;
    }

    /** The bare lower-case host of a value that may carry a scheme, a path or a port; null if it is not one. */
    private static String hostOf(String input) {
        if (input == null) {
            return null;
        }
        String value = input.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("https://")) {
            value = value.substring("https://".length());
        } else if (value.contains("://")) {
            return null;
        }
        int end = value.length();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String rest = value.substring(end);
        if (!rest.isEmpty() && !rest.matches("/*")) {
            return null; // a path, query or fragment is not a bare host
        }
        String host = value.substring(0, end);
        // Credentials, ports, brackets and anything else that is not a plain host name.
        return host.matches("[a-z0-9.-]+") ? host : null;
    }
}
