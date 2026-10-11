// @find: tests for skills persistence, repository, skill round trip, required connectors jsonb, unique name ignoring case, seed marker once, name taken query, SkillsPersistenceTest, Skills
// @what: Tests for the Skills repository and SkillService seeding against the real schema: JSONB connectors, the case-insensitive unique index and the once-only seed marker.
// @flow: Exercises Skills and the V23 migration
package os.aiworkforce.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.orchestrator.DatabaseForTests;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.skill.Skill;
import os.aiworkforce.orchestrator.skill.SkillService;
import os.aiworkforce.orchestrator.skill.Skills;
import os.aiworkforce.platform.web.persistence.JpaAuditingConfig;

/**
 * Skills against the real schema. The service test uses an in-memory stand-in, so it cannot see
 * an index Postgres enforces or a native upsert it will not run. Opt-in, because it needs a
 * database: run with {@code AIWOS_DATABASE_TESTS=true} (see {@link DatabaseForTests}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "AIWOS_DATABASE_TESTS", matches = "true")
@Import({JpaAuditingConfig.class, SkillService.class, ConnectorCatalog.class})
class SkillsPersistenceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-0000000005a1");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-0000000005b2");

    private static DatabaseForTests.Connection connection;

    @BeforeAll
    static void database() {
        connection = DatabaseForTests.connection();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> connection.urlInSchema());
        registry.add("spring.datasource.username", () -> connection.user());
        registry.add("spring.datasource.password", () -> connection.password());
    }

    @Autowired
    private Skills skills;

    @Autowired
    private EntityManager entities;

    @Autowired
    private SkillService service;

    @MockitoBean
    private AuditClient audit;

    private Skill skill(UUID orgId, String name) {
        Skill skill = new Skill();
        skill.setOrgId(orgId);
        skill.setName(name);
        skill.setInstructions("Do it.");
        skill.setRequiredConnectors(List.of("github", "slack"));
        return skill;
    }

    // @find: test skill round trip, skills persistence
    @Test
    @DisplayName("a skill is stored and read back with its connectors as a JSON list")
    void roundTrip() {
        Skill saved = skills.saveAndFlush(skill(ORG, "Digest"));
        entities.clear();

        Skill read = skills.findByIdAndOrgId(saved.getId(), ORG).orElseThrow();

        assertThat(read.getRequiredConnectors()).containsExactly("github", "slack");
        assertThat(read.getDescription()).isEmpty();
        assertThat(read.getCreatedAt()).isNotNull();
        assertThat(read.getCreatedBy()).isEqualTo("system");
        assertThat(skills.findByIdAndOrgId(saved.getId(), OTHER_ORG)).isEmpty();
    }

    // @find: test unique name ignoring case, skills persistence
    @Test
    @DisplayName("two skills in a workspace cannot share a name in any case; two workspaces can")
    void uniqueNameIgnoringCase() {
        skills.saveAndFlush(skill(ORG, "Digest"));
        skills.saveAndFlush(skill(OTHER_ORG, "Digest"));

        assertThat(skills.nameTaken(ORG, "DIGEST")).isTrue();
        assertThat(skills.nameTaken(ORG, "Other")).isFalse();
        assertThatThrownBy(() -> skills.saveAndFlush(skill(ORG, "digest")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // @find: test seed marker once, skills persistence
    @Test
    @DisplayName("the seed marker is written once per workspace: the first call reports 1, every later one 0")
    void seedMarkerOnce() {
        assertThat(skills.markSeeded(ORG)).isEqualTo(1);
        assertThat(skills.markSeeded(ORG)).isZero();
        assertThat(skills.markSeeded(OTHER_ORG)).isEqualTo(1);
    }

    // @find: test service seeds once against the real schema, skills persistence
    @Test
    @DisplayName("the service seeds a workspace once through the real marker, and not again after every skill is deleted")
    void serviceSeedsOnce() {
        UUID org = UUID.randomUUID();

        assertThat(service.list(org)).hasSize(5).allMatch(Skill::isBuiltIn);
        assertThat(service.list(org)).hasSize(5);
        for (Skill skill : service.list(org)) {
            service.delete(org, skill.getId());
        }
        skills.flush();

        assertThat(service.list(org)).isEmpty();
    }
}
