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
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The gateway routes every prefix the web client's development proxy routes, to the same service.
 *
 * <p>The two tables are kept by hand, and they drifted once already: the routing policy and the
 * invitations list worked against the dev proxy and failed behind the gateway, where the screens
 * showed "Nothing configured" and "No open invitations" instead of an error. This reads both files
 * as they are, without starting the gateway.
 */
class GatewayRoutesTest {

    private static final Pattern VITE_TARGET = Pattern.compile("const (\\w+) = 'http://localhost:(\\d+)'");
    private static final Pattern VITE_ROUTE = Pattern.compile("'(/[^']+)':\\s*(\\w+)");
    private static final Pattern PORT = Pattern.compile("localhost:(\\d+)");

    private record Route(String id, String port, List<String> patterns) {}

    @Test
    @DisplayName("every prefix the dev proxy routes reaches the same service through the gateway")
    void gatewayCoversDevProxy() throws IOException {
        Path vite = Path.of("").toAbsolutePath().resolve("../../web/vite.config.ts").normalize();
        // The check needs the whole repository. A build of the services alone has nothing to compare.
        assumeTrue(Files.exists(vite), "web/vite.config.ts is not present in this checkout");

        Map<String, String> proxied = viteRoutes(Files.readString(vite));
        List<Route> routes = gatewayRoutes();
        assertThat(proxied).isNotEmpty();
        assertThat(routes).isNotEmpty();

        List<String> problems = new ArrayList<>();
        proxied.forEach((prefix, port) -> {
            Optional<Route> route = routes.stream()
                    .filter(candidate -> candidate.patterns().stream().anyMatch(pattern -> covers(pattern, prefix)))
                    .findFirst();
            if (route.isEmpty()) {
                problems.add(prefix + " has no gateway route");
            } else if (!route.get().port().equals(port)) {
                problems.add(prefix + " goes to port " + port + " in development but to route '"
                        + route.get().id() + "' on port " + route.get().port() + " through the gateway");
            }
        });
        assertThat(problems).isEmpty();
    }

    /** A pattern covers a prefix when it is the prefix itself or anything beneath it. */
    private static boolean covers(String pattern, String prefix) {
        return pattern.equals(prefix) || pattern.startsWith(prefix + "/");
    }

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

    @SuppressWarnings("unchecked")
    private static List<Route> gatewayRoutes() throws IOException {
        try (InputStream in = GatewayRoutesTest.class.getResourceAsStream("/application.yml")) {
            assertThat(in).as("the gateway's application.yml on the test classpath").isNotNull();
            List<Route> routes = new ArrayList<>();
            for (Object document : new Yaml().loadAll(in)) {
                Object list = walk(document, "spring", "cloud", "gateway", "server", "webflux", "routes");
                if (!(list instanceof List<?> entries)) {
                    continue;
                }
                for (Object entry : entries) {
                    Map<String, Object> route = (Map<String, Object>) entry;
                    Matcher port = PORT.matcher(String.valueOf(route.get("uri")));
                    List<String> patterns = new ArrayList<>();
                    for (Object predicate : (List<Object>) route.getOrDefault("predicates", List.of())) {
                        String text = String.valueOf(predicate);
                        if (text.startsWith("Path=")) {
                            patterns.addAll(List.of(text.substring("Path=".length()).split(",")));
                        }
                    }
                    routes.add(new Route(
                            String.valueOf(route.get("id")), port.find() ? port.group(1) : "", patterns));
                }
            }
            return routes;
        }
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
