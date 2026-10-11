// @find: tests for skill service, skill, creates skill, trims fields, blank name, name too long, duplicate name conflict, rename to own name, bad agent, retired agent, unknown connector, connectors normalised, too many connectors, cross workspace not found, duplicate naming, seeds once, not reseeded after deleting all, seed skips taken names, seed picks agent by key, audit, SkillServiceTest, SkillService
// @what: Tests for SkillService in the orchestrator skill package, against an in-memory stand-in for the Skills repository.
// @flow: Exercises SkillService
package os.aiworkforce.orchestrator.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

/**
 * The rules every skill is held to, and the one-time seeding. The repository is an in-memory
 * stand-in that answers like the real one; the unique index and the seed marker's upsert are read
 * against Postgres in SkillsPersistenceTest.
 */
class SkillServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-7000-8000-000000000002");

    private final List<Skill> rows = Collections.synchronizedList(new ArrayList<>());
    private final Set<UUID> seeded = new HashSet<>();

    private Skills skills;
    private Agents agents;
    private AuditClient audit;
    private SkillService service;

    private Agent agent;

    @BeforeEach
    void setUp() {
        skills = mock(Skills.class);
        agents = mock(Agents.class);
        audit = mock(AuditClient.class);
        service = new SkillService(skills, agents, new ConnectorCatalog(), audit);
        RequestContext.setActor(Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of(), 0L));

        lenient().when(skills.findByOrgId(any())).thenAnswer(call -> rows.stream()
                .filter(s -> s.getOrgId().equals(call.getArgument(0)))
                .toList());
        lenient().when(skills.findByIdAndOrgId(any(), any())).thenAnswer(call -> rows.stream()
                .filter(s -> s.getId().equals(call.getArgument(0)) && s.getOrgId().equals(call.getArgument(1)))
                .findFirst());
        lenient().when(skills.nameTaken(any(), anyString())).thenAnswer(call -> named(call.getArgument(0), call.getArgument(1), null));
        lenient().when(skills.nameTakenByAnother(any(), anyString(), any()))
                .thenAnswer(call -> named(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        lenient().when(skills.save(any())).thenAnswer(call -> store(call.getArgument(0)));
        lenient().when(skills.saveAndFlush(any())).thenAnswer(call -> store(call.getArgument(0)));
        lenient().doAnswer(call -> rows.remove(call.<Skill>getArgument(0))).when(skills).delete(any());
        lenient().when(skills.markSeeded(any())).thenAnswer(call -> seeded.add(call.getArgument(0)) ? 1 : 0);

        agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setOrgId(ORG);
        agent.setKey("support");
        agent.setName("Sam");
        agent.setStatus("active");
        lenient().when(agents.findByIdAndOrgId(any(), any())).thenReturn(Optional.empty());
        lenient().when(agents.findByIdAndOrgId(agent.getId(), ORG)).thenReturn(Optional.of(agent));
        lenient().when(agents.findByOrgIdAndKey(any(), anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private boolean named(UUID orgId, String name, UUID except) {
        return rows.stream()
                .anyMatch(s -> s.getOrgId().equals(orgId)
                        && s.getName().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))
                        && !s.getId().equals(except));
    }

    private Skill store(Skill skill) {
        if (!rows.contains(skill)) {
            rows.add(skill);
        }
        return skill;
    }

    private static SkillService.SkillInput input(String name) {
        return new SkillService.SkillInput(name, "What it does", "Do the thing.", null, null);
    }

    private static String problemOf(ApiException e) {
        return String.valueOf(e.details().get("field"));
    }

    private ApiException refused(Runnable action) {
        return catchThrowableOfType(action::run, ApiException.class);
    }

    // ---- Creating and changing ----------------------------------------------------------------

    // @find: test creates skill, skill service
    @Test
    @DisplayName("a new skill is stored trimmed, with connectors lowercased and de-duplicated, and audited")
    void createsSkill() {
        Skill skill = service.create(
                ORG,
                new SkillService.SkillInput(
                        "  Release notes  ", null, "\n Write them. \n", agent.getId(), List.of("GitHub", " github", "slack")));

        assertThat(skill.getOrgId()).isEqualTo(ORG);
        assertThat(skill.getName()).isEqualTo("Release notes");
        assertThat(skill.getDescription()).isEmpty();
        assertThat(skill.getInstructions()).isEqualTo("Write them.");
        assertThat(skill.getDefaultAgentId()).isEqualTo(agent.getId());
        assertThat(skill.getRequiredConnectors()).containsExactly("github", "slack");
        assertThat(skill.isBuiltIn()).isFalse();
        verify(audit).record(eq(ORG), any(), eq("skill.create"), eq("skill"), eq(skill.getId().toString()), eq("succeeded"), any());
    }

    // @find: test blank name refused, skill service
    @Test
    @DisplayName("a blank or overlong name, an overlong description and blank or overlong instructions are refused")
    void fieldLimits() {
        assertThat(problemOf(refused(() -> service.create(ORG, input("   "))))).isEqualTo("name");
        assertThat(problemOf(refused(() -> service.create(ORG, input("x".repeat(121)))))).isEqualTo("name");
        assertThat(problemOf(refused(() -> service.create(
                        ORG, new SkillService.SkillInput("n", "d".repeat(501), "i", null, null)))))
                .isEqualTo("description");
        assertThat(problemOf(refused(() -> service.create(ORG, new SkillService.SkillInput("n", null, "  ", null, null)))))
                .isEqualTo("instructions");
        assertThat(problemOf(refused(() -> service.create(
                        ORG, new SkillService.SkillInput("n", null, "i".repeat(20_001), null, null)))))
                .isEqualTo("instructions");
        assertThat(refused(() -> service.create(ORG, input(""))).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(service.create(ORG, input("x".repeat(120))).getName()).hasSize(120);
        assertThat(rows).hasSize(1);
    }

    // @find: test duplicate name conflict, skill service
    @Test
    @DisplayName("a name already used in the workspace, in any case, is a conflict; another workspace may reuse it")
    void duplicateNameConflict() {
        service.create(ORG, input("Weekly digest"));

        ApiException conflict = refused(() -> service.create(ORG, input("weekly DIGEST")));

        assertThat(conflict.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(conflict.getMessage()).contains("weekly DIGEST").contains("already exists");
        assertThat(service.create(OTHER_ORG, input("Weekly digest")).getOrgId()).isEqualTo(OTHER_ORG);
    }

    // @find: test rename and conflict on update, skill service
    @Test
    @DisplayName("an edit may change a skill's own name's case, but not take another skill's name")
    void updateNames() {
        Skill first = service.create(ORG, input("Alpha"));
        service.create(ORG, input("Beta"));

        Skill renamed = service.update(ORG, first.getId(), input("ALPHA"));
        ApiException conflict = refused(() -> service.update(ORG, first.getId(), input("beta")));

        assertThat(renamed.getName()).isEqualTo("ALPHA");
        assertThat(conflict.code()).isEqualTo(ErrorCode.CONFLICT);
    }

    // @find: test update replaces every field, skill service
    @Test
    @DisplayName("an edit replaces every field, so leaving the agent and connectors out clears them")
    void updateReplaces() {
        Skill skill = service.create(
                ORG, new SkillService.SkillInput("Alpha", "d", "i", agent.getId(), List.of("github")));

        Skill updated = service.update(ORG, skill.getId(), new SkillService.SkillInput("Alpha", null, "new", null, null));

        assertThat(updated.getDescription()).isEmpty();
        assertThat(updated.getInstructions()).isEqualTo("new");
        assertThat(updated.getDefaultAgentId()).isNull();
        assertThat(updated.getRequiredConnectors()).isEmpty();
    }

    // @find: test bad agent refused, skill service
    @Test
    @DisplayName("a default agent from another workspace, an unknown one or a retired one is refused")
    void badAgent() {
        Agent elsewhere = new Agent();
        elsewhere.setId(UUID.randomUUID());
        elsewhere.setOrgId(OTHER_ORG);
        lenient().when(agents.findByIdAndOrgId(elsewhere.getId(), OTHER_ORG)).thenReturn(Optional.of(elsewhere));
        Agent retired = new Agent();
        retired.setId(UUID.randomUUID());
        retired.setOrgId(ORG);
        retired.setStatus("retired");
        lenient().when(agents.findByIdAndOrgId(retired.getId(), ORG)).thenReturn(Optional.of(retired));

        for (UUID id : List.of(elsewhere.getId(), UUID.randomUUID(), retired.getId())) {
            ApiException e = refused(() -> service.create(ORG, new SkillService.SkillInput("n", null, "i", id, null)));
            assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(problemOf(e)).isEqualTo("defaultAgentId");
        }
        assertThat(rows).isEmpty();
    }

    // @find: test keeping a retired default agent, skill service
    @Test
    @DisplayName("an edit that keeps a default agent retired since is allowed, so the skill can still be saved")
    void keepsRetiredAgent() {
        Skill skill = service.create(ORG, new SkillService.SkillInput("n", null, "i", agent.getId(), null));
        agent.setStatus("retired");

        Skill updated = service.update(ORG, skill.getId(), new SkillService.SkillInput("n", null, "j", agent.getId(), null));

        assertThat(updated.getDefaultAgentId()).isEqualTo(agent.getId());
    }

    // @find: test unknown connector refused, skill service
    @Test
    @DisplayName("an unknown or blank connector, or more than ten, is refused")
    void connectorRules() {
        ApiException unknown =
                refused(() -> service.create(ORG, new SkillService.SkillInput("n", null, "i", null, List.of("myspace"))));
        ApiException blank =
                refused(() -> service.create(ORG, new SkillService.SkillInput("n", null, "i", null, List.of(" "))));
        List<String> eleven = new ConnectorCatalog().all().stream()
                .map(c -> c.server())
                .limit(11)
                .toList();
        ApiException tooMany =
                refused(() -> service.create(ORG, new SkillService.SkillInput("n", null, "i", null, eleven)));

        assertThat(problemOf(unknown)).isEqualTo("requiredConnectors");
        assertThat(String.valueOf(unknown.details().get("problem"))).contains("myspace");
        assertThat(problemOf(blank)).isEqualTo("requiredConnectors");
        assertThat(eleven).hasSize(11);
        assertThat(problemOf(tooMany)).isEqualTo("requiredConnectors");
    }

    // ---- Workspaces ----------------------------------------------------------------------------

    // @find: test another workspaces skill not found, skill service
    @Test
    @DisplayName("another workspace's skill is not found, and cannot be read, edited, copied or deleted")
    void crossWorkspace() {
        Skill theirs = service.create(OTHER_ORG, input("Theirs"));

        assertThat(service.find(ORG, theirs.getId())).isEmpty();
        assertThat(service.find(OTHER_ORG, theirs.getId())).contains(theirs);
        assertThat(service.find(ORG, null)).isEmpty();
        for (Runnable action : List.<Runnable>of(
                () -> service.get(ORG, theirs.getId()),
                () -> service.update(ORG, theirs.getId(), input("Mine")),
                () -> service.duplicate(ORG, theirs.getId()),
                () -> service.delete(ORG, theirs.getId()))) {
            assertThat(refused(action).code()).isEqualTo(ErrorCode.NOT_FOUND);
        }
        assertThat(rows).containsExactly(theirs);
        assertThat(theirs.getName()).isEqualTo("Theirs");
    }

    // ---- Duplicating ---------------------------------------------------------------------------

    // @find: test duplicate naming, skill service
    @Test
    @DisplayName("copies are named X (copy), X (copy 2), X (copy 3), and are never built-in")
    void duplicateNaming() {
        Skill original = service.create(
                ORG, new SkillService.SkillInput("Digest", "d", "i", agent.getId(), List.of("github")));
        original.setBuiltIn(true);

        Skill first = service.duplicate(ORG, original.getId());
        Skill second = service.duplicate(ORG, original.getId());
        Skill third = service.duplicate(ORG, original.getId());

        assertThat(List.of(first.getName(), second.getName(), third.getName()))
                .containsExactly("Digest (copy)", "Digest (copy 2)", "Digest (copy 3)");
        assertThat(first.getId()).isNotEqualTo(original.getId());
        assertThat(first.isBuiltIn()).isFalse();
        assertThat(first.getInstructions()).isEqualTo("i");
        assertThat(first.getDefaultAgentId()).isEqualTo(agent.getId());
        assertThat(first.getRequiredConnectors()).containsExactly("github");
    }

    // @find: test copy name fits the limit, skill service
    @Test
    @DisplayName("a copy of a skill with a 120-character name is shortened to fit")
    void copyNameFits() {
        Skill original = service.create(ORG, input("n".repeat(120)));

        Skill copy = service.duplicate(ORG, original.getId());

        assertThat(copy.getName()).hasSize(120).endsWith(" (copy)");
    }

    // ---- Seeding -------------------------------------------------------------------------------

    // @find: test seeds once, skill service
    @Test
    @DisplayName("the first list seeds the five ready-made skills, sorted by name ignoring case, and only once")
    void seedsOnce() {
        service.create(ORG, input("apple pie"));

        List<Skill> first = service.list(ORG);
        List<Skill> second = service.list(ORG);

        assertThat(first).extracting(Skill::getName)
                .containsExactly(
                        "apple pie",
                        "Code review a PR",
                        "Draft a customer reply",
                        "Summarise a document",
                        "Triage support inbox",
                        "Weekly status report");
        assertThat(second).hasSize(6);
        assertThat(first.stream().filter(Skill::isBuiltIn)).hasSize(5);
        assertThat(rows.stream().filter(s -> s.getName().equals("Code review a PR")).findFirst().orElseThrow()
                        .getRequiredConnectors())
                .containsExactly("github");
        assertThat(service.list(OTHER_ORG)).hasSize(5);
    }

    // @find: test not reseeded after deleting all, skill service
    @Test
    @DisplayName("deleting every skill does not bring the ready-made ones back")
    void notReseeded() {
        for (Skill skill : service.list(ORG)) {
            service.delete(ORG, skill.getId());
        }

        assertThat(service.list(ORG)).isEmpty();
    }

    // @find: test seed skips taken names, skill service
    @Test
    @DisplayName("a ready-made skill whose name is already used is skipped rather than failing the list")
    void seedSkipsTakenNames() {
        Skill mine = service.create(ORG, input("WEEKLY status report"));

        List<Skill> listed = service.list(ORG);

        assertThat(listed).hasSize(5).contains(mine);
        assertThat(mine.isBuiltIn()).isFalse();
    }

    // @find: test seed picks agent by key, skill service
    @Test
    @DisplayName("a ready-made skill takes the workspace's active agent with its key, and none when it is paused")
    void seedPicksAgentByKey() {
        lenient().when(agents.findByOrgIdAndKey(ORG, "support")).thenReturn(Optional.of(agent));
        Agent paused = new Agent();
        paused.setId(UUID.randomUUID());
        paused.setStatus("paused");
        lenient().when(agents.findByOrgIdAndKey(ORG, "engineering-manager")).thenReturn(Optional.of(paused));

        List<Skill> listed = service.list(ORG);

        assertThat(listed).filteredOn(s -> s.getName().equals("Triage support inbox"))
                .extracting(Skill::getDefaultAgentId)
                .containsExactly(agent.getId());
        assertThat(listed).filteredOn(s -> s.getName().equals("Draft a customer reply"))
                .extracting(Skill::getDefaultAgentId)
                .containsExactly(agent.getId());
        assertThat(listed).filteredOn(s -> s.getName().equals("Code review a PR"))
                .extracting(Skill::getDefaultAgentId)
                .containsOnlyNulls();
        assertThat(listed).filteredOn(s -> s.getName().equals("Weekly status report"))
                .extracting(Skill::getDefaultAgentId)
                .containsOnlyNulls();
    }

    // @find: test ready-made skills are well formed, skill service
    @Test
    @DisplayName("every ready-made skill fits the rules a person's own skill is held to")
    void builtInsAreWellFormed() {
        ConnectorCatalog catalog = new ConnectorCatalog();
        for (BuiltInSkills.Definition definition : BuiltInSkills.ALL) {
            assertThat(definition.name().length()).isBetween(1, SkillService.MAX_NAME);
            assertThat(definition.description().length()).isBetween(1, SkillService.MAX_DESCRIPTION);
            int words = definition.instructions().strip().split("\\s+").length;
            assertThat(words).as(definition.name()).isBetween(120, 300);
            assertThat(definition.requiredConnectors()).allMatch(id -> catalog.find(id).isPresent());
        }
    }
}
