// @find: push endpoint check, allowed push services, SSRF, push service hosts, fcm.googleapis.com, mozilla autopush, web.push.apple.com, notify.windows.com, validate subscription endpoint, PushEndpoints
// @what: Decides whether a push endpoint a browser handed over is somewhere this service may post to.
// @flow: Used by PushService when a subscription is saved and by PushDelivery before every send
package os.aiworkforce.orchestrator.push;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import os.aiworkforce.orchestrator.service.NotificationService;

/**
 * Where push messages may go.
 *
 * <p>The endpoint arrives from the browser, which means from whoever is holding the session: left
 * unchecked, it would let anyone make this service send requests to an address of their choosing,
 * including ones inside the network it runs in. So an endpoint must be https, carry no user name
 * or password, and belong to one of the push services browsers actually use - Google's (Chrome,
 * Edge on Android, Samsung Internet, Opera), Mozilla's (Firefox), Apple's (Safari) and Microsoft's
 * (Edge on Windows). An operator can widen the list with {@code aiwos.push.allowed-hosts}. In a
 * deployed environment the name must also resolve only to public addresses, checked again at every
 * send, the same rule the workspace webhook follows.
 */
public final class PushEndpoints {

    /** Host names, or the domains they sit under, that browsers' push services use. */
    public static final List<String> DEFAULT_HOSTS = List.of(
            "fcm.googleapis.com",
            "android.googleapis.com",
            "push.services.mozilla.com",
            "push.apple.com",
            "notify.windows.com");

    /** The longest endpoint accepted; real ones are a few hundred characters. */
    public static final int MAX_LENGTH = 2_048;

    /**
     * The endpoint's host name could not be looked up just now. Unlike a refusal, which is final,
     * this says nothing about the subscription: the name server may simply be unreachable, so the
     * caller tries again later and keeps the subscription.
     */
    public static final class Unresolved extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unresolved(String host) {
            super("the host name " + host + " could not be looked up");
        }
    }

    /** Looks a host name up; the JDK's resolver, or a stand-in in tests. */
    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final List<String> allowedHosts;
    private final boolean strict;
    private final Resolver resolver;

    /**
     * @param allowedHosts host names or parent domains an endpoint may use; lower-cased, blanks dropped
     * @param strict whether names must resolve to public addresses only (any deployed environment)
     */
    public PushEndpoints(List<String> allowedHosts, boolean strict) {
        this(allowedHosts, strict, InetAddress::getAllByName);
    }

    /** For tests: the same rules with a resolver of their own. */
    PushEndpoints(List<String> allowedHosts, boolean strict, Resolver resolver) {
        this.resolver = resolver;
        this.allowedHosts = allowedHosts.stream()
                .map(host -> host.strip().toLowerCase(Locale.ROOT))
                .map(host -> host.startsWith(".") ? host.substring(1) : host)
                .filter(host -> !host.isEmpty())
                .distinct()
                .toList();
        this.strict = strict;
    }

    /** The default push services plus any extra hosts from a comma-separated setting. */
    public static List<String> hostsWith(String extra) {
        if (extra == null || extra.isBlank()) {
            return DEFAULT_HOSTS;
        }
        return java.util.stream.Stream.concat(DEFAULT_HOSTS.stream(), Arrays.stream(extra.split(",")))
                .toList();
    }

    // @find: check push endpoint
    /**
     * The endpoint as a URI, when it may be used.
     *
     * @throws IllegalArgumentException with what is wrong, as a phrase that follows "endpoint": a
     *     final refusal (not https, not a push service, a private address)
     * @throws Unresolved when, in strict mode, the host name could not be looked up at all; a
     *     temporary failure, not a refusal
     */
    public URI check(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("is missing");
        }
        if (endpoint.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("is too long");
        }
        URI uri;
        try {
            uri = new URI(endpoint.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("is not a valid web address");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("must start with https://");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new IllegalArgumentException("must include a host name");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("must not contain a user name or password");
        }
        if (!allowed(host)) {
            throw new IllegalArgumentException("is not a browser push service this workspace can send to");
        }
        if (strict) {
            InetAddress[] resolved;
            try {
                resolved = resolver.resolve(host);
            } catch (UnknownHostException e) {
                throw new Unresolved(host);
            }
            for (InetAddress address : resolved) {
                if (NotificationService.isInternal(address)) {
                    throw new IllegalArgumentException("must be a public address, not one inside a private network");
                }
            }
        }
        return uri;
    }

    // The host is one of the allowed names, or sits under one of them.
    private boolean allowed(String host) {
        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }
}
