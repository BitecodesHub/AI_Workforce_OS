// @find: skills api, list skills, get skill, create skill, update skill, delete skill, duplicate skill, /api/skills, Skills page, skill picker, SkillController
// @what: REST endpoints behind the Skills page and the chat composer's skill picker.
// @flow: Calls SkillService; views built by toView with agent names resolved from Agents
package os.aiworkforce.orchestrator.skill;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;

/**
 * Skills: a workspace's reusable playbooks.
 *
 * <p>Reading needs {@code chat:use}, since anyone who can chat can apply a skill to a message.
 * Writing needs {@code agent:update}: a skill shapes how agents work for everyone in the
 * workspace, so it is held to the same permission as changing an agent rather than one of its
 * own. A skill of another workspace is not found, so its existence is never confirmed.
 */
@RestController
@RequestMapping("/api/skills")
@Tag(name = "Skills")
public class SkillController {

    private final SkillService service;
    private final Agents agents;

    public SkillController(SkillService service, Agents agents) {
        this.service = service;
        this.agents = agents;
    }

    /** A new skill, or the whole new value of an existing one. Checked by {@link SkillService}. */
    public record SkillRequest(
            String name,
            String description,
            String instructions,
            UUID defaultAgentId,
            List<String> requiredConnectors) {}

    /**
     * One skill as the console reads it.
     *
     * @param defaultAgentName the default agent's name, or null when there is none or it has been
     *     removed since
     * @param createdBy who created the skill (a person's id, or {@code system})
     */
    public record SkillView(
            String id,
            String name,
            String description,
            String instructions,
            String defaultAgentId,
            String defaultAgentName,
            List<String> requiredConnectors,
            boolean builtIn,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {}

    // @find: list skills, GET /api/skills, Skills page, skill picker
    @GetMapping
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "Every skill in this workspace, by name")
    public List<SkillView> list() {
        UUID orgId = orgId();
        List<Skill> skills = service.list(orgId);
        Map<UUID, String> names = agents.findByOrgIdOrderByName(orgId).stream()
                .collect(Collectors.toMap(Agent::getId, Agent::getName, (first, second) -> first));
        return skills.stream().map(skill -> toView(skill, names::get)).toList();
    }

    // @find: get skill, GET /api/skills/{id}
    @GetMapping("/{skillId}")
    @RequiresPermission(Permission.Codes.CHAT_USE)
    @Operation(summary = "One skill")
    public SkillView get(@PathVariable UUID skillId) {
        return toView(service.get(orgId(), skillId));
    }

    // @find: create skill, POST /api/skills, New skill dialog
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Create a skill")
    public SkillView create(@RequestBody SkillRequest request) {
        return toView(service.create(orgId(), input(request)));
    }

    // @find: update skill, edit skill, PUT /api/skills/{id}
    @PutMapping("/{skillId}")
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Replace a skill's name, description, instructions, default agent and connectors")
    public SkillView update(@PathVariable UUID skillId, @RequestBody SkillRequest request) {
        return toView(service.update(orgId(), skillId, input(request)));
    }

    // @find: delete skill, DELETE /api/skills/{id}
    @DeleteMapping("/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Delete a skill")
    public void delete(@PathVariable UUID skillId) {
        service.delete(orgId(), skillId);
    }

    // @find: duplicate skill, copy skill, POST /api/skills/{id}/duplicate
    @PostMapping("/{skillId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Operation(summary = "Copy a skill under a new name")
    public SkillView duplicate(@PathVariable UUID skillId) {
        return toView(service.duplicate(orgId(), skillId));
    }

    private static SkillService.SkillInput input(SkillRequest request) {
        if (request == null) {
            return null;
        }
        return new SkillService.SkillInput(
                request.name(),
                request.description(),
                request.instructions(),
                request.defaultAgentId(),
                request.requiredConnectors());
    }

    private SkillView toView(Skill skill) {
        UUID orgId = skill.getOrgId();
        return toView(skill, agentId -> agents.findByIdAndOrgId(agentId, orgId)
                .map(Agent::getName)
                .orElse(null));
    }

    private static SkillView toView(Skill skill, Function<UUID, String> agentName) {
        UUID agentId = skill.getDefaultAgentId();
        return new SkillView(
                skill.getId().toString(),
                skill.getName(),
                skill.getDescription(),
                skill.getInstructions(),
                agentId == null ? null : agentId.toString(),
                agentId == null ? null : agentName.apply(agentId),
                List.copyOf(skill.getRequiredConnectors()),
                skill.isBuiltIn(),
                skill.getCreatedBy(),
                skill.getCreatedAt(),
                skill.getUpdatedAt());
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
