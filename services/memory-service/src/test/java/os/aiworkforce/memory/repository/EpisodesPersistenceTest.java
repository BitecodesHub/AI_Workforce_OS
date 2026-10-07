package os.aiworkforce.memory.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import os.aiworkforce.memory.domain.Episode;

/**
 * The search query against the real schema: scoped to one workspace always, and to one agent when
 * an agent is named.
 *
 * <p>The unit tests mock the repository, so they cannot see how Postgres treats the agent filter.
 * A null parameter in a native query has no type of its own, which is what the cast in
 * {@link Episodes#search} is for; this proves both the null and the named case actually run.
 * Opt-in, because it needs Docker: run with {@code AIWOS_DATABASE_TESTS=true} (and, where the
 * Ryuk image is not available, {@code TESTCONTAINERS_RYUK_DISABLED=true}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
class EpisodesPersistenceTest {

    private static final UUID ORG_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID ORG_B = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    private static final UUID AGENT_1 = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID AGENT_2 = UUID.fromString("00000000-0000-7000-8000-0000000000a2");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        // The native search names its table without a schema, as it does in production, where the
        // connection URL selects the schema.
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=memory");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private Episodes episodes;

    @BeforeEach
    void seed() {
        episodes.saveAndFlush(Episode.of(ORG_A, AGENT_1, "decision", "Invoices go out on the first", 8));
        episodes.saveAndFlush(Episode.of(ORG_A, AGENT_2, "observation", "Invoices are paid late in March", 5));
        episodes.saveAndFlush(Episode.of(ORG_B, AGENT_1, "decision", "Invoices are never sent by post", 8));
    }

    private static List<String> summaries(List<Episode> found) {
        return found.stream().map(Episode::getSummary).toList();
    }

    @Test
    @DisplayName("a search for one agent returns only that agent's memories in the workspace")
    void namedAgent() {
        List<Episode> found = episodes.search(ORG_A, AGENT_1, "invoices", PageRequest.of(0, 10));

        assertThat(summaries(found)).containsExactly("Invoices go out on the first");
    }

    @Test
    @DisplayName("a search with no agent covers the whole workspace and nothing beyond it")
    void noAgent() {
        List<Episode> found = episodes.search(ORG_A, null, "invoices", PageRequest.of(0, 10));

        assertThat(summaries(found))
                .containsExactlyInAnyOrder("Invoices go out on the first", "Invoices are paid late in March");
    }

    @Test
    @DisplayName("the same agent id in another workspace never reaches this one")
    void otherWorkspace() {
        List<Episode> found = episodes.search(ORG_B, AGENT_1, "invoices", PageRequest.of(0, 10));

        assertThat(summaries(found)).containsExactly("Invoices are never sent by post");
    }
}
