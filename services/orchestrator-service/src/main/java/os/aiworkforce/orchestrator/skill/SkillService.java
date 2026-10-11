// @find: skill service, list skills, find skill, create skill, update skill, delete skill, duplicate skill, copy skill, seed skills, ready made skills, skill validation, skill name unique, required connectors check, SkillService
// @what: Business logic for skills: listing (seeding the ready-made ones once), finding, creating, editing, deleting and duplicating, with validation and audit.
// @flow: Called by SkillController and by the chat coordinator (find); stores through Skills; checks agents and the connector catalog
package os.aiworkforce.orchestrator.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import os.aiworkforce.mcp.catalog.ConnectorCatalog;
import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.service.AuditClient;
import os.aiworkforce.orchestrator.service.LifecycleAnnouncer;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Keeps a workspace's skills.
 *
 * <p>A workspace is given the ready-made skills the first time its skills are listed, once: the
 * {@code skill_seeds} marker is written in the same transaction, so deleting every skill later
 * does not bring them back, and two first visits at once seed only one set.
 *
 * <p>Every change is validated here rather than by annotations on the request, so the chat
 * coordinator, the console and any later caller are held to one set of rules with one set of
 * messages, and audited once it commits.
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    static final int MAX_NAME = 120;
    static final int MAX_DESCRIPTION = 500;
    static final int MAX_INSTRUCTIONS = 20_000;
    static final int MAX_CONNECTORS = 10;

    private static final String RESOURCE = "skill";

    /** Lists sort by name as a person reads it, so "agenda" and "Budget" are in alphabetical order. */
    private static final Comparator<Skill> BY_NAME =
            Comparator.comparing(Skill::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(Skill::getName);

    private final Skills skills;
    private final Agents agents;
    private final ConnectorCatalog catalog;
    private final AuditClient audit;

    public SkillService(Skills skills, Agents agents, ConnectorCatalog catalog, AuditClient audit) {
        this.skills = skills;
        this.agents = agents;
        this.catalog = catalog;
        this.audit = audit;
    }

    /**
     * What a person sends to create or replace a skill. Every field is the whole new value: a
     * null description becomes empty, and a null default agent or connector list clears it.
     */
    public record SkillInput(
            String name,
            String description,
            String instructions,
            UUID defaultAgentId,
            List<String> requiredConnectors) {}

    // ---- Reading --------------------------------------------------------------------------------

    /** Every skill in the workspace, by name ignoring case; seeds the ready-made ones the first time. */
    // @find: list skills
    @Transactional
    public List<Skill> list(UUID orgId) {
        if (skills.markSeeded(orgId) == 1) {
            seed(orgId);
        }
        List<Skill> all = new ArrayList<>(skills.findByOrgId(orgId));
        all.sort(BY_NAME);
        return all;
    }

    /**
     * One skill of this workspace, or empty when there is none - including when the id belongs to
     * another workspace, so a caller can never tell that one exists. Used by the chat coordinator
     * when a message is sent with a skill.
     */
    // @find: find skill by id, skill for a chat message
    @Transactional(readOnly = true)
    public Optional<Skill> find(UUID orgId, UUID id) {
        if (orgId == null || id == null) {
            return Optional.empty();
        }
        return skills.findByIdAndOrgId(id, orgId);
    }

    /** One skill of this workspace, or {@code NOT_FOUND}. */
    // @find: get one skill
    @Transactional(readOnly = true)
    public Skill get(UUID orgId, UUID id) {
        return find(orgId, id).orElseThrow(() -> ApiException.notFound(RESOURCE, id));
    }

    // ---- Changing -------------------------------------------------------------------------------

    // @find: create skill, new skill
    @Transactional
    public Skill create(UUID orgId, SkillInput input) {
        Valid valid = validate(orgId, input, null);
        if (skills.nameTaken(orgId, valid.name())) {
            throw nameConflict(valid.name());
        }
        Skill skill = new Skill();
        skill.setId(UuidV7.generate());
        skill.setOrgId(orgId);
        apply(skill, valid);
        save(skill);
        log.info("Skill {} created in workspace {}", skill.getId(), orgId);
        recordAfterCommit(orgId, "skill.create", skill, Map.of());
        return skill;
    }

    /** Replaces a skill's name, description, instructions, default agent and connectors. */
    // @find: update skill, edit skill
    @Transactional
    public Skill update(UUID orgId, UUID id, SkillInput input) {
        Skill skill = get(orgId, id);
        Valid valid = validate(orgId, input, skill.getDefaultAgentId());
        if (skills.nameTakenByAnother(orgId, valid.name(), skill.getId())) {
            throw nameConflict(valid.name());
        }
        String previousName = skill.getName();
        apply(skill, valid);
        save(skill);
        recordAfterCommit(orgId, "skill.update", skill, Map.of("previousName", previousName));
        return skill;
    }

    // @find: delete skill, remove skill
    @Transactional
    public void delete(UUID orgId, UUID id) {
        Skill skill = get(orgId, id);
        skills.delete(skill);
        recordAfterCommit(orgId, "skill.delete", skill, Map.of());
    }

    /**
     * A copy of a skill under the first free name of "X (copy)", "X (copy 2)", "X (copy 3)"...
     * The copy is the person's own, never built-in, whatever the original was.
     */
    // @find: duplicate skill, copy skill
    @Transactional
    public Skill duplicate(UUID orgId, UUID id) {
        Skill original = get(orgId, id);
        Skill copy = new Skill();
        copy.setId(UuidV7.generate());
        copy.setOrgId(orgId);
        copy.setName(copyName(orgId, original.getName()));
        copy.setDescription(original.getDescription());
        copy.setInstructions(original.getInstructions());
        copy.setDefaultAgentId(original.getDefaultAgentId());
        copy.setRequiredConnectors(original.getRequiredConnectors());
        copy.setBuiltIn(false);
        save(copy);
        recordAfterCommit(orgId, "skill.duplicate", copy, Map.of("sourceId", original.getId().toString()));
        return copy;
    }

    /** The first "X (copy)" or "X (copy N)" not taken, shortening X so the whole fits the limit. */
    String copyName(UUID orgId, String name) {
        for (int n = 1; ; n++) {
            String suffix = n == 1 ? " (copy)" : " (copy " + n + ")";
            String base = name.length() + suffix.length() > MAX_NAME
                    ? name.substring(0, MAX_NAME - suffix.length()).strip()
                    : name;
            String candidate = base + suffix;
            if (!skills.nameTaken(orgId, candidate)) {
                return candidate;
            }
        }
    }

    // ---- Validation -----------------------------------------------------------------------------

    /** A request after trimming and checking, ready to apply. */
    private record Valid(
            String name, String description, String instructions, UUID defaultAgentId, List<String> connectors) {}

    /**
     * Checks a request against every rule, refusing the first that fails with {@code
     * VALIDATION_FAILED} naming the field.
     *
     * @param currentAgentId the skill's default agent before this change, which may be kept as it
     *     is even if that agent has since been retired or removed
     */
    private Valid validate(UUID orgId, SkillInput input, UUID currentAgentId) {
        if (input == null) {
            throw ApiException.validation("name", "Give the skill a name.");
        }
        String name = input.name() == null ? "" : input.name().strip();
        if (name.isEmpty()) {
            throw ApiException.validation("name", "Give the skill a name.");
        }
        if (name.length() > MAX_NAME) {
            throw ApiException.validation("name", "A skill name can be at most " + MAX_NAME + " characters.");
        }
        String description = input.description() == null ? "" : input.description().strip();
        if (description.length() > MAX_DESCRIPTION) {
            throw ApiException.validation(
                    "description", "A description can be at most " + MAX_DESCRIPTION + " characters.");
        }
        String instructions = input.instructions() == null ? "" : input.instructions().strip();
        if (instructions.isEmpty()) {
            throw ApiException.validation("instructions", "Write the instructions the skill should follow.");
        }
        if (instructions.length() > MAX_INSTRUCTIONS) {
            throw ApiException.validation(
                    "instructions", "Instructions can be at most " + MAX_INSTRUCTIONS + " characters.");
        }
        UUID agentId = input.defaultAgentId();
        if (agentId != null && !agentId.equals(currentAgentId)) {
            Optional<Agent> agent = agents.findByIdAndOrgId(agentId, orgId);
            if (agent.isEmpty() || agent.get().isRetired()) {
                throw ApiException.validation("defaultAgentId", "Choose an agent in this workspace.");
            }
        }
        return new Valid(name, description, instructions, agentId, connectors(input.requiredConnectors()));
    }

    /** Connector ids trimmed, lowercased and de-duplicated, each one the catalog knows. */
    private List<String> connectors(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String raw : requested) {
            String id = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                throw ApiException.validation("requiredConnectors", "A connector name cannot be blank.");
            }
            if (catalog.find(id).isEmpty()) {
                throw ApiException.validation("requiredConnectors", "There is no connector called \"" + id + "\".");
            }
            ids.add(id);
        }
        if (ids.size() > MAX_CONNECTORS) {
            throw ApiException.validation(
                    "requiredConnectors", "A skill can list at most " + MAX_CONNECTORS + " connectors.");
        }
        return List.copyOf(ids);
    }

    private static void apply(Skill skill, Valid valid) {
        skill.setName(valid.name());
        skill.setDescription(valid.description());
        skill.setInstructions(valid.instructions());
        skill.setDefaultAgentId(valid.defaultAgentId());
        skill.setRequiredConnectors(valid.connectors());
    }

    private static ApiException nameConflict(String name) {
        return ApiException.conflict("A skill called \"" + name + "\" already exists in this workspace.");
    }

    /**
     * Writes a skill now, so a name that slipped past the check in a race is refused as the same
     * conflict rather than as a server error when the transaction commits.
     */
    private void save(Skill skill) {
        try {
            skills.saveAndFlush(skill);
        } catch (DataIntegrityViolationException e) {
            throw nameConflict(skill.getName());
        }
    }

    // ---- Seeding --------------------------------------------------------------------------------

    /**
     * Writes the ready-made skills for a workspace that has just been marked seeded. A name the
     * workspace already uses - someone created it before ever listing - is skipped rather than
     * failing the whole list.
     */
    private void seed(UUID orgId) {
        int written = 0;
        for (BuiltInSkills.Definition definition : BuiltInSkills.ALL) {
            if (skills.nameTaken(orgId, definition.name())) {
                continue;
            }
            Skill skill = new Skill();
            skill.setId(UuidV7.generate());
            skill.setOrgId(orgId);
            skill.setName(definition.name());
            skill.setDescription(definition.description());
            skill.setInstructions(definition.instructions().strip());
            skill.setDefaultAgentId(preferredAgent(orgId, definition.agentKey()));
            skill.setRequiredConnectors(definition.requiredConnectors());
            skill.setBuiltIn(true);
            skills.save(skill);
            written++;
        }
        log.info("Seeded {} ready-made skills in workspace {}", written, orgId);
    }

    /** The active agent with this key in the workspace, or null to route as usual. */
    private UUID preferredAgent(UUID orgId, String key) {
        if (key == null) {
            return null;
        }
        return agents.findByOrgIdAndKey(orgId, key)
                .filter(Agent::isActive)
                .map(Agent::getId)
                .orElse(null);
    }

    // ---- Audit ----------------------------------------------------------------------------------

    private void recordAfterCommit(UUID orgId, String action, Skill skill, Map<String, Object> extra) {
        Actor actor = RequestContext.actor().orElse(Actor.SYSTEM);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", skill.getName());
        detail.putAll(extra);
        Map<String, Object> frozen = Collections.unmodifiableMap(detail);
        String skillId = skill.getId().toString();
        LifecycleAnnouncer.afterCommit(
                () -> audit.record(orgId, actor, action, RESOURCE, skillId, "succeeded", frozen));
    }
}
