// @find: tests for gateway routes, route table, dev proxy, vite config, nginx launcher, path prefixes, StripPrefix, RewritePath, route drift, Nothing configured, No open invitations, workspaces route, GatewayRoutesTest, served roots, AIWOS_URL
// @what: Checks that gateway routes, the web dev proxy and the launcher nginx agree on every path prefix and that no route rewrites paths.
// @flow: Reads services/gateway application.yml, web/vite.config.ts and the launcher nginx conf without starting the gateway.
package os.aiworkforce.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The gateway routes every prefix the web client's development proxy and the launcher's nginx
 * route, to the same service, and passes each path through unchanged.
 *
 * <p>The three tables are kept by hand, and they drifted twice already: the routing policy and the
 * invitations list worked against the dev proxy and failed behind the gateway, where the screens
 * showed "Nothing configured" and "No open invitations" instead of an error; and a
 * {@code StripPrefix} on the organisation route turned {@code /api/workspaces} into
 * {@code /workspaces}, which no controller serves, so creating a workspace failed only in the
 * deployment that goes through the gateway. This reads all three files as they are, without
 * starting the gateway.
 */
class GatewayRoutesTest {

    private static final Pattern VITE_TARGET = Pattern.compile("const (\\w+) = 'http://localhost:(\\d+)'");
    private static final Pattern VITE_ROUTE = Pattern.compile("'(/[^']+)':\\s*(\\w+)");
    private static final Pattern NGINX_ROUTE =
            Pattern.compile("location\\s+\\^~\\s+(/\\S+)\\s*\\{\\s*proxy_pass\\s+http://([\\w-]+)(?::\\d+)?\\s*;");
    private static final Pattern PORT = Pattern.compile("localhost:(\\d+)");
    private static final Pattern SERVICE = Pattern.compile("AIWOS_URL_(\\w+)");

    /** Every service controller is mapped under these, so any other path was rewritten. */
    private static final List<String> SERVED_ROOTS = List.of("/api/", "/.well-known/");

    /** Filters that change the path on its way to the service. */
    private static final Set<String> PATH_CHANGING_FILTERS =
            Set.of("StripPrefix", "RewritePath", "PrefixPath", "SetPath");

    /** The one route allowed a path-changing filter, should it ever need one. */
    private static final String PATH_FILTER_EXEMPT_ROUTE = "identity-public";

    private record Route(String id, String port, String service, List<String> patterns, List<String> filters) {}

    // @find: test gateway covers every dev proxy prefix, same service
    @Test
    @DisplayName("every prefix the dev proxy routes reaches the same service through the gateway")
    void gatewayCoversDevProxy() throws IOException {
        Path vite = repositoryFile("web/vite.config.ts");
        // The check needs the whole repository. A build of the services alone has nothing to compare.
        assumeTrue(Files.exists(vite), "web/vite.config.ts is not present in this checkout");

        Map<String, String> proxied = viteRoutes(Files.readString(vite));
        List<Route> routes = gatewayRoutes();
        assertThat(proxied).isNotEmpty();
        assertThat(routes).isNotEmpty();

        List<String> problems = new ArrayList<>();
        proxied.forEach((prefix, port) -> {
            Optional<Route> route = routeFor(routes, prefix);
            if (route.isEmpty()) {
                problems.add(prefix + " has no gateway route");
            } else if (!route.get().port().equals(port)) {
                problems.add(prefix + " goes to port " + port + " in development but to route '"
                        + route.get().id() + "' on port " + route.get().port() + " through the gateway");
            }
        });
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("the launcher's nginx, the dev proxy and the gateway send each prefix to the same service")
    // @find: test launcher nginx matches dev proxy and gateway
    void launcherMatchesDevProxyAndGateway() throws IOException {
        Path vite = repositoryFile("web/vite.config.ts");
        Path nginx = repositoryFile("infra/launcher/nginx.conf");
        assumeTrue(Files.exists(vite) && Files.exists(nginx), "the web client or the launcher is not in this checkout");

        Map<String, String> development = viteServices(Files.readString(vite));
        Map<String, String> launcher = nginxRoutes(Files.readString(nginx));
        List<Route> routes = gatewayRoutes();
        assertThat(launcher).isNotEmpty();

        List<String> problems = new ArrayList<>();
        development.forEach((prefix, service) -> {
            String forwarded = launcher.get(prefix);
            if (forwarded == null) {
                problems.add(prefix + " is proxied in development but not by the launcher's nginx");
            } else if (!forwarded.equals(service)) {
                problems.add(prefix + " goes to " + service + " in development but to " + forwarded + " in nginx");
            }
        });
        launcher.forEach((prefix, service) -> {
            if (!development.containsKey(prefix)) {
                problems.add(prefix + " is forwarded by nginx but not by the development proxy");
            }
            Optional<Route> route = routeFor(routes, prefix);
            if (route.isEmpty()) {
                problems.add(prefix + " is forwarded by nginx but has no gateway route");
            } else if (!route.get().service().equals(service)) {
                problems.add(prefix + " goes to " + service + " in nginx but to route '" + route.get().id()
                        + "' (" + route.get().service() + ") through the gateway");
            }
        });
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("no route except identity-public changes the path on its way to the service")
    // @find: test no StripPrefix or RewritePath filters on routes
    void noPathChangingFilters() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Route route : gatewayRoutes()) {
            if (route.id().equals(PATH_FILTER_EXEMPT_ROUTE)) {
                continue;
            }
            route.filters().stream()
                    .filter(PATH_CHANGING_FILTERS::contains)
                    .forEach(filter -> problems.add("route '" + route.id() + "' has a " + filter + " filter"));
        }
        defaultFilters().stream()
                .filter(PATH_CHANGING_FILTERS::contains)
                .forEach(filter ->
                        problems.add("default-filters has a " + filter + " filter, which applies to every route"));
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("every Path predicate is under /api/ or /.well-known/, where the services serve")
    // @find: test route path predicates are under /api/ or /.well-known/
    void pathPredicatesUnderServedRoots() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Route route : gatewayRoutes()) {
            assertThat(route.patterns()).as("route '%s' has a Path predicate", route.id()).isNotEmpty();
            for (String pattern : route.patterns()) {
                if (SERVED_ROOTS.stream().noneMatch(pattern::startsWith)) {
                    problems.add("route '" + route.id() + "' matches " + pattern);
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("the check finds a path-changing filter in either form the gateway accepts")
    // @find: test route filter names are parsed
    void filterNamesRead() {
        assertThat(filterName("StripPrefix=1")).isEqualTo("StripPrefix");
        assertThat(filterName(Map.of("name", "RewritePath", "args", Map.of()))).isEqualTo("RewritePath");
    }

    private static Path repositoryFile(String relative) {
        return Path.of("").toAbsolutePath().resolve("../..").resolve(relative).normalize();
    }

    private static Optional<Route> routeFor(List<Route> routes, String prefix) {
        return routes.stream()
                .filter(candidate -> candidate.patterns().stream().anyMatch(pattern -> covers(pattern, prefix)))
                .findFirst();
    }

    /** A pattern covers a prefix when it is the prefix itself or anything beneath it. */
    private static boolean covers(String pattern, String prefix) {
        return pattern.equals(prefix) || pattern.startsWith(prefix + "/");
    }

    /** Prefix to port, as the dev proxy declares it. */
    private static Map<String, String> viteRoutes(String source) {
        Map<String, String> ports = new LinkedHashMap<>();
        Matcher target = VITE_TARGET.matcher(source);
        while (target.find()) {
            ports.put(target.group(1), target.group(2));
        }
        Map<String, String> routes = new LinkedHashMap<>();
        Matcher route = VITE_ROUTE.matcher(source);
        while (route.find()) {
            String port = ports.get(route.group(2));
            if (port != null) {
                routes.put(route.group(1), port);
            }
        }
        return routes;
    }

    /** Prefix to service name, taken from the dev proxy's target constant (ORGANISATION). */
    private static Map<String, String> viteServices(String source) {
        Map<String, String> targets = new LinkedHashMap<>();
        Matcher target = VITE_TARGET.matcher(source);
        while (target.find()) {
            targets.put(target.group(1), target.group(1).toLowerCase(Locale.ROOT));
        }
        Map<String, String> routes = new LinkedHashMap<>();
        Matcher route = VITE_ROUTE.matcher(source);
        while (route.find()) {
            String service = targets.get(route.group(2));
            if (service != null) {
                routes.put(route.group(1), service);
            }
        }
        return routes;
    }

    /** Prefix to service name, taken from the upstream host nginx forwards to. */
    private static Map<String, String> nginxRoutes(String source) {
        Map<String, String> routes = new LinkedHashMap<>();
        Matcher route = NGINX_ROUTE.matcher(source);
        while (route.find()) {
            routes.put(route.group(1), route.group(2).toLowerCase(Locale.ROOT));
        }
        return routes;
    }

    @SuppressWarnings("unchecked")
    private static List<Route> gatewayRoutes() throws IOException {
        List<Route> routes = new ArrayList<>();
        for (Object document : gatewayDocuments()) {
            Object list = walk(document, "spring", "cloud", "gateway", "server", "webflux", "routes");
            if (!(list instanceof List<?> entries)) {
                continue;
            }
            for (Object entry : entries) {
                Map<String, Object> route = (Map<String, Object>) entry;
                String uri = String.valueOf(route.get("uri"));
                Matcher port = PORT.matcher(uri);
                Matcher service = SERVICE.matcher(uri);
                List<String> patterns = new ArrayList<>();
                for (Object predicate : (List<Object>) route.getOrDefault("predicates", List.of())) {
                    patterns.addAll(pathPatterns(predicate));
                }
                List<String> filters = new ArrayList<>();
                for (Object filter : (List<Object>) route.getOrDefault("filters", List.of())) {
                    filters.add(filterName(filter));
                }
                routes.add(new Route(
                        String.valueOf(route.get("id")),
                        port.find() ? port.group(1) : "",
                        service.find() ? service.group(1).toLowerCase(Locale.ROOT) : "",
                        patterns,
                        filters));
            }
        }
        return routes;
    }

    private static List<String> defaultFilters() throws IOException {
        List<String> filters = new ArrayList<>();
        for (Object document : gatewayDocuments()) {
            Object list = walk(document, "spring", "cloud", "gateway", "server", "webflux", "default-filters");
            if (list instanceof List<?> entries) {
                entries.forEach(filter -> filters.add(filterName(filter)));
            }
        }
        return filters;
    }

    private static List<Object> gatewayDocuments() throws IOException {
        try (InputStream in = GatewayRoutesTest.class.getResourceAsStream("/application.yml")) {
            assertThat(in)
                    .as("the gateway's application.yml on the test classpath")
                    .isNotNull();
            List<Object> documents = new ArrayList<>();
            new Yaml().loadAll(in).forEach(documents::add);
            return documents;
        }
    }

    /** The patterns of a Path predicate, in shortcut form ("Path=/a/**,/b/**") or full form. */
    private static List<String> pathPatterns(Object predicate) {
        if (predicate instanceof Map<?, ?> full) {
            if (!"Path".equals(String.valueOf(full.get("name")))) {
                return List.of();
            }
            List<String> patterns = new ArrayList<>();
            if (full.get("args") instanceof Map<?, ?> args) {
                args.values().forEach(value -> {
                    if (value instanceof List<?> many) {
                        many.forEach(item -> patterns.add(String.valueOf(item)));
                    } else {
                        patterns.addAll(List.of(String.valueOf(value).split(",")));
                    }
                });
            }
            return patterns;
        }
        String text = String.valueOf(predicate);
        return text.startsWith("Path=") ? List.of(text.substring("Path=".length()).split(",")) : List.of();
    }

    /** A filter's name, in shortcut form ("StripPrefix=1") or full form (name: StripPrefix). */
    private static String filterName(Object filter) {
        if (filter instanceof Map<?, ?> full) {
            return String.valueOf(full.get("name")).strip();
        }
        String text = String.valueOf(filter);
        int equals = text.indexOf('=');
        return (equals < 0 ? text : text.substring(0, equals)).strip();
    }

    private static Object walk(Object node, String... keys) {
        Object current = node;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }
}
