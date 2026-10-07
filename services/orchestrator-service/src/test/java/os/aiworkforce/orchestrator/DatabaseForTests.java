package os.aiworkforce.orchestrator;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The Postgres the database tests run against: a container when Docker is there, or the scratch
 * database that {@code AIWOS_TEST_DATABASE_URL} names (a JDBC URL), with {@code
 * AIWOS_TEST_DATABASE_USER} and {@code AIWOS_TEST_DATABASE_PASSWORD}.
 *
 * <p>Those tests are opt-in ({@code AIWOS_DATABASE_TESTS=true}). A named database has the
 * orchestrator schema created in it by Flyway and its tables emptied by the tests, so it must be a
 * throwaway one and never one that holds anything. Where the Ryuk image is not available, set
 * {@code TESTCONTAINERS_RYUK_DISABLED=true} too.
 */
public final class DatabaseForTests {

    /** Where to connect. */
    public record Connection(String url, String user, String password) {

        /** The same, with the connection starting in the orchestrator schema, as the service's does. */
        public String urlInSchema() {
            return url + (url.contains("?") ? "&" : "?") + "currentSchema=orchestrator";
        }
    }

    private static PostgreSQLContainer<?> container;

    private DatabaseForTests() {}

    /**
     * The database to use, starting a container the first time when none is named. A test that
     * finds neither Docker nor a named database is skipped rather than failed.
     */
    public static synchronized Connection connection() {
        String url = System.getenv("AIWOS_TEST_DATABASE_URL");
        if (url != null && !url.isBlank()) {
            return new Connection(
                    url,
                    System.getenv().getOrDefault("AIWOS_TEST_DATABASE_USER", "postgres"),
                    System.getenv().getOrDefault("AIWOS_TEST_DATABASE_PASSWORD", ""));
        }
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "no Docker, and no AIWOS_TEST_DATABASE_URL");
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
            Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        }
        return new Connection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}
