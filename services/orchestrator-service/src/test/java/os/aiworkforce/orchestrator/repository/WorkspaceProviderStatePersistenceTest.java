package os.aiworkforce.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import os.aiworkforce.orchestrator.domain.LlmProviderEntity;
import os.aiworkforce.orchestrator.domain.WorkspaceModelAvailability;
import os.aiworkforce.orchestrator.domain.WorkspaceProviderSetting;

/**
 * V10 and the per-workspace provider state against the real schema: Flyway V1 to V10, then
 * Hibernate's validation, the native upserts run twice so {@code on conflict} is exercised, the
 * advisory lock, and the CHECK constraint.
 *
 * <p>The isolation tests stub these repositories, so they cannot see a mapping the schema rejects
 * or SQL Postgres will not run - and with {@code ddl-auto: validate} the first is the orchestrator
 * refusing to start. Opt-in, because it needs Docker: run with {@code AIWOS_DATABASE_TESTS=true}
 * (and, where the Ryuk image is not available, {@code TESTCONTAINERS_RYUK_DISABLED=true}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
class WorkspaceProviderStatePersistenceTest {

    private static final UUID ORG_A = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID ORG_B = UUID.fromString("00000000-0000-7000-8000-0000000000b2");
    private static final String LLAMA = "meta-llama/llama-3.3-70b-instruct";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        // The native upserts name their tables without a schema, as they do in production, where
        // the connection URL selects the schema.
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=orchestrator");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private Providers providers;

    @Autowired
    private WorkspaceProviderSettings settings;

    @Autowired
    private WorkspaceModelAvailabilities availability;

    @Autowired
    private EntityManager entities;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V10 offers every seeded provider and keeps what a workspace starts with as before")
    void migration() {
        Map<String, Object> groq =
                jdbc.queryForMap("select enabled, workspace_default_enabled from llm_providers where id = 'groq'");
        Map<String, Object> sandbox =
                jdbc.queryForMap("select enabled, workspace_default_enabled from llm_providers where id = 'sandbox'");

        assertThat(groq).containsEntry("enabled", true).containsEntry("workspace_default_enabled", false);
        assertThat(sandbox).containsEntry("enabled", true).containsEntry("workspace_default_enabled", true);
        assertThat(jdbc.queryForObject("select count(*) from llm_providers where not enabled", Long.class))
                .isZero();

        LlmProviderEntity mapped = providers.findById("sandbox").orElseThrow();
        assertThat(mapped.isWorkspaceDefaultEnabled()).isTrue();
        assertThat(providers.findById("groq").orElseThrow().isWorkspaceDefaultEnabled()).isFalse();
    }

    @Test
    @DisplayName("turning a provider on and off twice upserts one row, and leaves the key state alone")
    void upsertEnabled() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        settings.markCredentialRejected(ORG_A, "groq", now);

        assertThat(settings.upsertEnabled(ORG_A, "groq", true, now, "user-1")).isEqualTo(1);
        assertThat(settings.upsertEnabled(ORG_A, "groq", false, now.plusSeconds(1), "user-2")).isEqualTo(1);

        entities.clear();
        WorkspaceProviderSetting row = settings.findById(new WorkspaceProviderSetting.Key(ORG_A, "groq"))
                .orElseThrow();
        assertThat(row.getEnabled()).isFalse();
        assertThat(row.getUpdatedBy()).isEqualTo("user-2");
        assertThat(row.getCredentialStatus()).isEqualTo(WorkspaceProviderSetting.REJECTED);
        assertThat(settings.findByOrgId(ORG_A)).hasSize(1);
        assertThat(settings.findByOrgId(ORG_B)).isEmpty();
    }

    @Test
    @DisplayName("a rejection is recorded twice without conflict, and cleared only for its own workspace")
    void credentialRejection() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(settings.markCredentialRejected(ORG_A, "openrouter", now)).isEqualTo(1);
        assertThat(settings.markCredentialRejected(ORG_A, "openrouter", now.plusSeconds(1))).isEqualTo(1);
        settings.markCredentialRejected(ORG_B, "openrouter", now);

        // A row with no choice in it leaves the workspace on the provider's default.
        entities.clear();
        assertThat(settings.findById(new WorkspaceProviderSetting.Key(ORG_A, "openrouter"))
                        .orElseThrow()
                        .getEnabled())
                .isNull();

        assertThat(settings.clearRejectedCredential(ORG_B, "openrouter", now.plusSeconds(2))).isEqualTo(1);
        assertThat(settings.clearRejectedCredential(ORG_B, "openrouter", now.plusSeconds(3))).isZero();

        entities.clear();
        assertThat(settings.findById(new WorkspaceProviderSetting.Key(ORG_A, "openrouter"))
                        .orElseThrow()
                        .getCredentialStatus())
                .isEqualTo(WorkspaceProviderSetting.REJECTED);
        assertThat(settings.findById(new WorkspaceProviderSetting.Key(ORG_B, "openrouter"))
                        .orElseThrow()
                        .getCredentialStatus())
                .isEqualTo(WorkspaceProviderSetting.VALID);
    }

    @Test
    @DisplayName("the CHECK constraint refuses a credential status the code never writes")
    void credentialStatusIsChecked() {
        assertThatThrownBy(() -> jdbc.update(
                        "insert into workspace_provider_settings (org_id, provider_id, credential_status)"
                                + " values (?, 'groq', 'missing')",
                        ORG_A))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the workspace lock can be taken, and taken again in the same transaction")
    void workspaceLock() {
        assertThat(settings.holdWorkspaceLock(ORG_A)).isEqualTo(1);
        assertThat(settings.holdWorkspaceLock(ORG_A)).isEqualTo(1);
        assertThat(settings.holdWorkspaceLock(ORG_B)).isEqualTo(1);
    }

    @Test
    @DisplayName("a model note is upserted, never shortened while in force, and lapsed notes do not count")
    void modelAvailability() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant soon = now.plus(Duration.ofMinutes(15));
        Instant later = now.plus(Duration.ofHours(6));

        assertThat(availability.upsert(ORG_A, "openrouter", LLAMA, soon, "MODEL_NOT_FOUND", "gone")).isEqualTo(1);
        assertThat(availability.upsert(ORG_A, "openrouter", LLAMA, later, "INSUFFICIENT_CREDIT", "empty"))
                .isEqualTo(1);
        assertThat(availability.upsert(ORG_A, "openrouter", LLAMA, soon, "MODEL_NOT_FOUND", "gone again"))
                .isZero();

        entities.clear();
        WorkspaceModelAvailability row = availability
                .findById(new WorkspaceModelAvailability.Key(ORG_A, "openrouter", LLAMA))
                .orElseThrow();
        assertThat(row.getUnavailableUntil()).isEqualTo(later);
        assertThat(row.getCause()).isEqualTo("INSUFFICIENT_CREDIT");
        assertThat(row.getReason()).isEqualTo("empty");

        availability.upsert(ORG_B, "openrouter", LLAMA, soon, "MODEL_NOT_FOUND", "gone");
        assertThat(availability.findOtherWorkspacesReporting("openrouter", LLAMA, "MODEL_NOT_FOUND", ORG_A, now))
                .containsExactly(ORG_B);
        assertThat(availability.findOtherWorkspacesReporting("openrouter", LLAMA, "MODEL_NOT_FOUND", ORG_B, now))
                .isEmpty();
        assertThat(availability.findOtherWorkspacesReporting(
                        "openrouter", LLAMA, "MODEL_NOT_FOUND", ORG_A, soon.plusSeconds(1)))
                .isEmpty();
    }
}
