package os.aiworkforce.orchestrator.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.service.AgentRunner;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.rbac.Permission;
import os.aiworkforce.platform.rbac.RequiresPermission;
import os.aiworkforce.platform.web.persistence.UuidV7;

/** Agents and their configuration. */
@RestController
@RequestMapping("/api/agents")
@Tag(name = "Agents")
public class AgentController {

    private final Agents agents;
    private final AgentVersions versions;
    private final AgentRunner runner;
    private final os.aiworkforce.orchestrator.repository.ToolGrants grants;

    public AgentController(
            Agents agents,
            AgentVersions versions,
            AgentRunner runner,
            os.aiworkforce.orchestrator.repository.ToolGrants grants) {
        this.agents = agents;
        this.versions = versions;
        this.runner = runner;
        this.grants = grants;
    }

    public record AgentView(
            UUID id, String key, String name, String category, String status, Integer revision) {}

    public record CreateAgentRequest(
            @NotBlank @Size(max = 60) String key,
            @NotBlank @Size(max = 120) String name,
            @NotBlank String category,
            @NotBlank @Size(max = 20_000) String systemPrompt,
            @Size(max = 4_000) String goals) {}

    public record UpdateConfigurationRequest(
            @NotBlank @Size(max = 20_000) String systemPrompt,
            @Size(max = 4_000) String goals,
            BigDecimal temperature,
            Integer maxOutputTokens,
            Integer maxSteps) {}

    public record RunRequest(@NotBlank @Size(max = 10_000) String instruction) {}

    public record GrantView(
            String server, List<String> tools, List<String> scopes, boolean requireApproval,
            Integer maxCallsPerRun) {}

    public record AgentDetail(
            UUID id, String key, String name, String category, String status,
            Integer revision, String systemPrompt, String goals, Integer maxSteps,
            boolean sealed, List<GrantView> grants) {}

    public record RunStarted(UUID runId, String status, String answer) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "List the agents in this workspace")
    public List<AgentView> list() {
        UUID orgId = orgId();
        return agents.findByOrgIdOrderByName(orgId).stream()
                .map(agent -> new AgentView(
                        agent.getId(), agent.getKey(), agent.getName(),
                        agent.getCategory(), agent.getStatus(), revisionOf(agent)))
                .toList();
    }

    @GetMapping("/{agentId}")
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "One agent, with its current configuration and tool grants")
    public AgentDetail get(@PathVariable UUID agentId) {
        Agent agent = agents.findByIdAndOrgId(agentId, orgId())
                .orElseThrow(() -> ApiException.notFound("agent", agentId));
        AgentVersion version = agent.getCurrentVersionId() == null
                ? null
                : versions.findById(agent.getCurrentVersionId()).orElse(null);
        List<GrantView> grantViews = grants.findByAgentIdAndEnabledTrue(agentId).stream()
                .map(grant -> new GrantView(grant.getServer(), grant.getAllowedTools(), grant.getScopes(),
                        grant.isRequireApproval(), grant.getMaxCallsPerRun()))
                .toList();
        return new AgentDetail(
                agent.getId(), agent.getKey(), agent.getName(), agent.getCategory(), agent.getStatus(),
                version == null ? null : version.getRevision(),
                version == null ? null : version.getSystemPrompt(),
                version == null ? null : version.getGoals(),
                version == null ? null : version.getMaxSteps(),
                version != null && version.isSealed(),
                grantViews);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.AGENT_CREATE)
    @Transactional
    @Operation(summary = "Add an agent")
    public AgentView create(@Valid @RequestBody CreateAgentRequest request) {
        UUID orgId = orgId();
        if (agents.findByOrgIdAndKey(orgId, request.key()).isPresent()) {
            throw new ApiException(
                    os.aiworkforce.platform.error.ErrorCode.ALREADY_EXISTS,
                    "An agent with that key already exists in this workspace.");
        }

        Agent agent = new Agent();
        agent.setId(UuidV7.generate());
        agent.setOrgId(orgId);
        agent.setKey(request.key());
        agent.setName(request.name());
        agent.setCategory(request.category());
        agents.save(agent);

        AgentVersion version = newVersion(agent, request.systemPrompt(), request.goals(), null, null, 12);
        agent.setCurrentVersionId(version.getId());
        agents.save(agent);

        return new AgentView(agent.getId(), agent.getKey(), agent.getName(),
                agent.getCategory(), agent.getStatus(), version.getRevision());
    }

    /**
     * Saves a new configuration.
     *
     * <p>Always a new revision, never an edit in place. A version a run has used is sealed, and
     * rewriting it would make an existing trace a record of a prompt that never ran.
     */
    @PutMapping("/{agentId}/configuration")
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Transactional
    @Operation(summary = "Save a new revision of an agent's configuration")
    public AgentView updateConfiguration(
            @PathVariable UUID agentId, @Valid @RequestBody UpdateConfigurationRequest request) {
        Agent agent = agents.findByIdAndOrgId(agentId, orgId())
                .orElseThrow(() -> ApiException.notFound("agent", agentId));

        AgentVersion version = newVersion(
                agent, request.systemPrompt(), request.goals(),
                request.temperature(), request.maxOutputTokens(),
                request.maxSteps() == null ? 12 : request.maxSteps());
        agent.setCurrentVersionId(version.getId());
        agents.save(agent);

        return new AgentView(agent.getId(), agent.getKey(), agent.getName(),
                agent.getCategory(), agent.getStatus(), version.getRevision());
    }

    @GetMapping("/{agentId}/versions")
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "Every revision of this agent's configuration")
    public List<AgentVersion> versions(@PathVariable UUID agentId) {
        agents.findByIdAndOrgId(agentId, orgId())
                .orElseThrow(() -> ApiException.notFound("agent", agentId));
        return versions.findByAgentIdOrderByRevisionDesc(agentId);
    }

    @PostMapping("/{agentId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.Codes.AGENT_RUN)
    @Operation(summary = "Give this agent something to do")
    public RunStarted run(@PathVariable UUID agentId, @Valid @RequestBody RunRequest request) {
        AgentRunner.Outcome outcome = runner.start(orgId(), agentId, null, request.instruction(), "manual");
        return new RunStarted(outcome.runId(), outcome.status(), outcome.answer());
    }

    private AgentVersion newVersion(
            Agent agent, String prompt, String goals, BigDecimal temperature, Integer maxOutput, int maxSteps) {
        AgentVersion version = new AgentVersion();
        version.setId(UuidV7.generate());
        version.setAgentId(agent.getId());
        version.setOrgId(agent.getOrgId());
        version.setRevision(versions.highestRevision(agent.getId()) + 1);
        version.setSystemPrompt(prompt);
        version.setGoals(goals == null ? "" : goals);
        version.setTemperature(temperature);
        version.setMaxOutputTokens(maxOutput);
        version.setMaxSteps(maxSteps);
        RequestContext.actor().ifPresent(actor -> version.setCreatedBy(actor.id()));
        return versions.save(version);
    }

    private Integer revisionOf(Agent agent) {
        if (agent.getCurrentVersionId() == null) {
            return null;
        }
        return versions.findById(agent.getCurrentVersionId()).map(AgentVersion::getRevision).orElse(null);
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
