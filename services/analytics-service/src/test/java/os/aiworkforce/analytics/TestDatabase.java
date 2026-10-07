package os.aiworkforce.analytics;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The disposable PostgreSQL the persistence tests run against, opt-in because it needs one.
 *
 * <p>Two ways to provide it, so the tests run where Docker does not:
 * <ul>
 *   <li>{@code AIWOS_DATABASE_TESTS=true} starts a {@code postgres:16-alpine} container with
 *       Testcontainers (set {@code TESTCONTAINERS_RYUK_DISABLED=true} where the Ryuk image is not
 *       available), which is how the other services' persistence tests run.
 *   <li>{@code AIWOS_TEST_DATABASE_URL} (a JDBC URL without a schema, such as
 *       {@code jdbc:postgresql://127.0.0.1:55499/aiwos_test}) with {@code AIWOS_TEST_DATABASE_USER}
 *       and {@code AIWOS_TEST_DATABASE_PASSWORD} uses a database already running.
 * </ul>
 *
 * <p>Never point the second at a database anyone uses. The audit table cannot be cleaned up after a
 * run - refusing deletes is what it is for - so every test writes under workspaces of its own
 * making and leaves its rows behind.
 */
public final class TestDatabase {

    private static PostgreSQLContainer<?> container;

    private TestDatabase() {}

    /** Whether a database can be had, for {@code @EnabledIf}. */
    public static boolean available() {
        if (externalUrl() != null) {
            return true;
        }
        return "true".equals(System.getenv("AIWOS_DATABASE_TESTS")) && DockerClientFactory.instance().isDockerAvailable();
    }

    public static void register(DynamicPropertyRegistry registry) {
        String external = externalUrl();
        if (external != null) {
            registry.add("spring.datasource.url", () -> external + "?currentSchema=analytics");
            registry.add("spring.datasource.username", () -> env("AIWOS_TEST_DATABASE_USER", "aiwos"));
            registry.add("spring.datasource.password", () -> env("AIWOS_TEST_DATABASE_PASSWORD", "aiwos"));
            return;
        }
        PostgreSQLContainer<?> postgres = container();
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=analytics");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
        }
        return container;
    }

    private static String externalUrl() {
        String url = System.getenv("AIWOS_TEST_DATABASE_URL");
        return url == null || url.isBlank() ? null : url.strip();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
