package os.aiworkforce.orchestrator.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.service.AgentRunner;
import os.aiworkforce.orchestrator.service.GeneralEmployee;
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
    private final GeneralEmployee generalEmployee;

    public AgentController(
            Agents agents,
            AgentVersions versions,
            AgentRunner runner,
            os.aiworkforce.orchestrator.repository.ToolGrants grants,
            GeneralEmployee generalEmployee) {
        this.agents = agents;
        this.versions = versions;
        this.runner = runner;
        this.grants = grants;
        this.generalEmployee = generalEmployee;
    }

    /** The longest summary returned, so a card can show it without a layout of its own. */
    static final int SUMMARY_LIMIT = 160;

    private static final Pattern FIRST_SENTENCE = Pattern.compile("^(.+?[.!?])(?=\\s|$)");

    /**
     * An agent as a list shows it.
     *
     * @param summary the first sentence of the current configuration's system prompt, at most
     *     {@value #SUMMARY_LIMIT} characters; an excerpt of the agent's own instructions, usually
     *     written in the second person. Null when the agent has no configuration.
     * @param tools the distinct tool servers the agent is granted, sorted; empty when it has none
     * @param fallback whether this is the workspace's General Employee, found by its flag
     */
    public record AgentView(
            UUID id,
            String key,
            String name,
            String category,
            String status,
            Integer revision,
            String summary,
            List<String> tools,
            String voiceId,
            boolean fallback) {}

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
            // 1 to 50; null means the default. A limit below 1 would fail every run before its first step.
            @Min(1) @Max(50) Integer maxSteps) {}

    static final int DEFAULT_MAX_STEPS = 12;

    public record RunRequest(@NotBlank @Size(max = 10_000) String instruction) {}

    public record GrantView(
            String server, List<String> tools, List<String> scopes, boolean requireApproval, Integer maxCallsPerRun) {}

    public record AgentDetail(
            UUID id,
            String key,
            String name,
            String category,
            String status,
            Integer revision,
            String systemPrompt,
            String goals,
            Integer maxSteps,
            boolean sealed,
            List<GrantView> grants,
            String summary,
            List<String> tools,
            String voiceId,
            boolean fallback) {}

    public record RunStarted(UUID runId, String status, String answer) {}

    @GetMapping
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "List the agents in this workspace")
    public List<AgentView> list() {
        UUID orgId = orgId();
        generalEmployee.ensure(orgId);
        return agents.findByOrgIdOrderByName(orgId).stream()
                .map(agent -> toView(agent, currentVersionOf(agent)))
                .toList();
    }

    @GetMapping("/{agentId}")
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "One agent, with its current configuration and tool grants")
    public AgentDetail get(@PathVariable UUID agentId) {
        Agent agent =
                agents.findByIdAndOrgId(agentId, orgId()).orElseThrow(() -> ApiException.notFound("agent", agentId));
        AgentVersion version = currentVersionOf(agent);
        List<AgentToolGrant> granted = grants.findByAgentIdAndEnabledTrue(agentId);
        List<GrantView> grantViews = granted.stream()
                .map(grant -> new GrantView(
                        grant.getServer(),
                        grant.getAllowedTools(),
                        grant.getScopes(),
                        grant.isRequireApproval(),
                        grant.getMaxCallsPerRun()))
                .toList();
        return new AgentDetail(
                agent.getId(),
                agent.getKey(),
                agent.getName(),
                agent.getCategory(),
                agent.getStatus(),
                version == null ? null : version.getRevision(),
                version == null ? null : version.getSystemPrompt(),
                version == null ? null : version.getGoals(),
                version == null ? null : version.getMaxSteps(),
                version != null && version.isSealed(),
                grantViews,
                version == null ? null : summarise(version.getSystemPrompt()),
                serverNames(granted),
                agent.getVoiceId(),
                GeneralEmployee.isFallback(agent));
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

        AgentVersion version =
                newVersion(agent, request.systemPrompt(), request.goals(), null, null, DEFAULT_MAX_STEPS);
        agent.setCurrentVersionId(version.getId());
        agents.save(agent);

        return toView(agent, version);
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
        Agent agent =
                agents.findByIdAndOrgId(agentId, orgId()).orElseThrow(() -> ApiException.notFound("agent", agentId));

        AgentVersion version = newVersion(
                agent,
                request.systemPrompt(),
                request.goals(),
                request.temperature(),
                request.maxOutputTokens(),
                request.maxSteps() == null ? DEFAULT_MAX_STEPS : request.maxSteps());
        agent.setCurrentVersionId(version.getId());
        agents.save(agent);

        return toView(agent, version);
    }

    @GetMapping("/{agentId}/versions")
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "Every revision of this agent's configuration")
    public List<AgentVersion> versions(@PathVariable UUID agentId) {
        agents.findByIdAndOrgId(agentId, orgId()).orElseThrow(() -> ApiException.notFound("agent", agentId));
        return versions.findByAgentIdOrderByRevisionDesc(agentId);
    }

    /**
     * Pauses an agent so its queued tasks wait rather than run.
     *
     * <p>A task already in progress finishes; only claiming a new one is affected. A paused
     * agent's queued work is held, not lost - it stays pending and is picked up again the moment
     * the agent resumes.
     */
    @PostMapping("/{agentId}/pause")
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Transactional
    @Operation(summary = "Pause an agent so its queued tasks wait rather than run")
    public AgentView pause(@PathVariable UUID agentId) {
        return setStatus(agentId, "paused");
    }

    @PostMapping("/{agentId}/resume")
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Transactional
    @Operation(summary = "Resume a paused agent")
    public AgentView resume(@PathVariable UUID agentId) {
        return setStatus(agentId, "active");
    }

    private AgentView setStatus(UUID agentId, String status) {
        Agent agent =
                agents.findByIdAndOrgId(agentId, orgId()).orElseThrow(() -> ApiException.notFound("agent", agentId));
        if ("retired".equals(agent.getStatus())) {
            throw new ApiException(
                    os.aiworkforce.platform.error.ErrorCode.CONFLICT, "A retired agent cannot be paused or resumed.");
        }
        agent.setStatus(status);
        agents.save(agent);
        return toView(agent, currentVersionOf(agent));
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

    private AgentView toView(Agent agent, AgentVersion version) {
        return new AgentView(
                agent.getId(),
                agent.getKey(),
                agent.getName(),
                agent.getCategory(),
                agent.getStatus(),
                version == null ? null : version.getRevision(),
                version == null ? null : summarise(version.getSystemPrompt()),
                serverNames(grants.findByAgentIdAndEnabledTrue(agent.getId())),
                agent.getVoiceId(),
                GeneralEmployee.isFallback(agent));
    }

    private AgentVersion currentVersionOf(Agent agent) {
        if (agent.getCurrentVersionId() == null) {
            return null;
        }
        return versions.findById(agent.getCurrentVersionId()).orElse(null);
    }

    private static List<String> serverNames(List<AgentToolGrant> granted) {
        return granted.stream()
                .map(AgentToolGrant::getServer)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The first sentence of a system prompt, as a short excerpt of what the agent was told to do.
     *
     * <p>Whitespace is collapsed first, because prompts are usually wrapped across lines. A
     * sentence longer than the limit is cut at the last word that fits and marked with an
     * ellipsis, so an excerpt never ends half way through a word.
     */
    static String summarise(String prompt) {
        if (prompt == null) {
            return null;
        }
        String text = prompt.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return null;
        }
        Matcher sentence = FIRST_SENTENCE.matcher(text);
        String first = sentence.find() ? sentence.group(1) : text;
        if (first.length() <= SUMMARY_LIMIT) {
            return first;
        }
        int cut = first.lastIndexOf(' ', SUMMARY_LIMIT - 1);
        String head = cut > 0 ? first.substring(0, cut) : first.substring(0, SUMMARY_LIMIT - 1);
        return head.replaceAll("[\\s,;:]+$", "") + "…";
    }

    private static UUID orgId() {
        return UUID.fromString(RequestContext.requireOrgId());
    }
}
