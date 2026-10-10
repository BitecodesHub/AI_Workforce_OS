// @find: agents api, create agent, list agents, agent detail, update agent configuration, pause agent, resume agent, retire agent, restore agent, run agent, start a run, agent versions, /api/agents, Agents page, New agent dialog, Run now button
// @what: REST endpoints to create, configure, pause, retire and run agents and list their versions.
// @flow: Called by the web app Agents page; calls AgentRunner, RunExecutor and the Agents repository
package os.aiworkforce.orchestrator.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
     * @param description what the agent does, in one line written about it; null when nobody has
     *     written one, and the console then derives a line from {@code summary}
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
            String description,
            List<String> tools,
            String voiceId,
            boolean fallback) {

        /** The view of an agent at {@code version} (null when it has none), granted {@code tools}. */
        static AgentView of(Agent agent, AgentVersion version, List<String> tools) {
            return new AgentView(
                    agent.getId(),
                    agent.getKey(),
                    agent.getName(),
                    agent.getCategory(),
                    agent.getStatus(),
                    version == null ? null : version.getRevision(),
                    version == null ? null : summarise(version.getSystemPrompt()),
                    agent.getDescription(),
                    tools,
                    agent.getVoiceId(),
                    GeneralEmployee.isFallback(agent));
        }
    }

    /** The longest description, so it stays one line on a card. */
    static final int DESCRIPTION_LIMIT = 200;

    /** The categories an agent may have; the database holds the same list as a check constraint. */
    static final List<String> CATEGORIES = List.of("operations", "engineering", "growth", "support");

    public record CreateAgentRequest(
            @NotBlank @Size(max = 60) String key,
            @NotBlank @Size(max = 120) String name,
            @NotBlank String category,
            @NotBlank @Size(max = 20_000) String systemPrompt,
            @Size(max = 4_000) String goals,
            @Size(max = DESCRIPTION_LIMIT) String description) {}

    /** A description of null or blank clears it, so the console derives one from the instructions again. */
    public record DescriptionRequest(@Size(max = DESCRIPTION_LIMIT) String description) {}

    public record UpdateConfigurationRequest(
            @NotBlank @Size(max = 20_000) String systemPrompt,
            @Size(max = 4_000) String goals,
            // 0 to 2, the range every supported provider accepts; null means the model's default.
            @DecimalMin("0") @DecimalMax("2") BigDecimal temperature,
            @Min(1) Integer maxOutputTokens,
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
            BigDecimal temperature,
            Integer maxOutputTokens,
            boolean sealed,
            List<GrantView> grants,
            String summary,
            String description,
            List<String> tools,
            String voiceId,
            boolean fallback) {}

    public record RunStarted(UUID runId, String status, String answer) {}

    // @find: list agents, GET /api/agents, Agents page
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

    // @find: get agent detail, GET /api/agents/{agentId}
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
                version == null ? null : version.getTemperature(),
                version == null ? null : version.getMaxOutputTokens(),
                version != null && version.isSealed(),
                grantViews,
                version == null ? null : summarise(version.getSystemPrompt()),
                agent.getDescription(),
                serverNames(granted),
                agent.getVoiceId(),
                GeneralEmployee.isFallback(agent));
    }

    // @find: create agent, new agent, POST /api/agents, New agent dialog
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.Codes.AGENT_CREATE)
    @Transactional
    @Operation(summary = "Add an agent")
    public AgentView create(@Valid @RequestBody CreateAgentRequest request) {
        UUID orgId = orgId();
        if (!CATEGORIES.contains(request.category())) {
            throw ApiException.validation("category", "must be one of " + String.join(", ", CATEGORIES));
        }
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
        agent.setDescription(cleanDescription(request.description()));
        agents.save(agent);

        AgentVersion version =
                newVersion(agent, request.systemPrompt(), request.goals(), null, null, DEFAULT_MAX_STEPS);
        agent.setCurrentVersionId(version.getId());
        agents.save(agent);

        return toView(agent, version);
    }

    // @find: update agent configuration, edit agent instructions model tools, PUT /api/agents/{agentId}/configuration
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

    // @find: set agent description, PUT /api/agents/{agentId}/description
    /**
     * Sets or clears the one line that says what the agent does. It is about the agent, not
     * something the agent is told, so it is not part of a configuration revision.
     */
    @PutMapping("/{agentId}/description")
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Transactional
    @Operation(summary = "Set or clear the one line that says what an agent does")
    public AgentView setDescription(@PathVariable UUID agentId, @Valid @RequestBody DescriptionRequest request) {
        Agent agent =
                agents.findByIdAndOrgId(agentId, orgId()).orElseThrow(() -> ApiException.notFound("agent", agentId));
        agent.setDescription(cleanDescription(request.description()));
        agents.save(agent);
        return toView(agent, currentVersionOf(agent));
    }

    /** One line, whitespace collapsed; null for blank. */
    static String cleanDescription(String description) {
        if (description == null) {
            return null;
        }
        String clean = description.strip().replaceAll("\\s+", " ");
        return clean.isEmpty() ? null : clean;
    }

    // @find: agent versions, version history, GET /api/agents/{agentId}/versions
    @GetMapping("/{agentId}/versions")
    @RequiresPermission(Permission.Codes.AGENT_READ)
    @Operation(summary = "Every revision of this agent's configuration")
    public List<AgentVersion> versions(@PathVariable UUID agentId) {
        agents.findByIdAndOrgId(agentId, orgId()).orElseThrow(() -> ApiException.notFound("agent", agentId));
        return versions.findByAgentIdOrderByRevisionDesc(agentId);
    }

    // @find: pause agent, POST /api/agents/{agentId}/pause
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

    // @find: resume agent, unpause, POST /api/agents/{agentId}/resume
    @PostMapping("/{agentId}/resume")
    @RequiresPermission(Permission.Codes.AGENT_UPDATE)
    @Transactional
    @Operation(summary = "Resume a paused agent")
    public AgentView resume(@PathVariable UUID agentId) {
        return setStatus(agentId, "active");
    }

    /** Why a schedule was paused when its agent was retired, shown beside the schedule. */
    public static final String RETIRED_SCHEDULE_REASON = "Paused because its agent was retired.";

    /** Pauses the schedules of a retired agent; set by Spring, absent where a test builds this by hand. */
    private os.aiworkforce.orchestrator.schedule.ScheduleService scheduleService;

    private os.aiworkforce.orchestrator.schedule.Schedules schedules;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSchedules(
            os.aiworkforce.orchestrator.schedule.ScheduleService scheduleService,
            os.aiworkforce.orchestrator.schedule.Schedules schedules) {
        this.scheduleService = scheduleService;
        this.schedules = schedules;
    }

    // @find: retire agent, archive, delete agent, POST /api/agents/{agentId}/retire
    /**
     * Retires an agent: it is archived, not deleted. Chat, routing and schedules stop reaching it,
     * its runs, traces, memory and revisions are kept, and it can be restored. The General
     * Employee cannot be retired - it answers whatever no other agent takes.
     */
    @PostMapping("/{agentId}/retire")
    @RequiresPermission(Permission.Codes.AGENT_DELETE)
    @Transactional
    @Operation(summary = "Retire an agent: hide it from chat, routing and schedules, keeping its history")
    public AgentView retire(@PathVariable UUID agentId) {
        UUID orgId = orgId();
        Agent agent =
                agents.findByIdAndOrgId(agentId, orgId).orElseThrow(() -> ApiException.notFound("agent", agentId));
        if (GeneralEmployee.isFallback(agent)) {
            throw new ApiException(
                    os.aiworkforce.platform.error.ErrorCode.CONFLICT,
                    "The General Employee cannot be retired: it answers anything no other agent takes. Pause it instead.");
        }
        if (!"retired".equals(agent.getStatus())) {
            agent.setStatus("retired");
            agents.save(agent);
            if (scheduleService != null && schedules != null) {
                for (var schedule : schedules.findByOrgIdOrderByNameAsc(orgId)) {
                    if (agentId.equals(schedule.getAgentId()) && schedule.isEnabled()) {
                        scheduleService.pauseInternal(orgId, schedule.getId(), RETIRED_SCHEDULE_REASON);
                    }
                }
            }
        }
        return toView(agent, currentVersionOf(agent));
    }

    // @find: restore agent, unarchive, POST /api/agents/{agentId}/restore
    /** Brings a retired agent back, paused, so nobody's queued work starts before someone resumes it. */
    @PostMapping("/{agentId}/restore")
    @RequiresPermission(Permission.Codes.AGENT_DELETE)
    @Transactional
    @Operation(summary = "Restore a retired agent; it comes back paused")
    public AgentView restore(@PathVariable UUID agentId) {
        Agent agent =
                agents.findByIdAndOrgId(agentId, orgId()).orElseThrow(() -> ApiException.notFound("agent", agentId));
        if (!"retired".equals(agent.getStatus())) {
            throw new ApiException(
                    os.aiworkforce.platform.error.ErrorCode.CONFLICT, "Only a retired agent can be restored.");
        }
        agent.setStatus("paused");
        agents.save(agent);
        return toView(agent, currentVersionOf(agent));
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

    /** Drives a started run in the background; set by Spring alongside the constructor's beans. */
    private os.aiworkforce.orchestrator.service.RunExecutor runExecutor;

    @org.springframework.beans.factory.annotation.Autowired
    void setRunExecutor(os.aiworkforce.orchestrator.service.RunExecutor runExecutor) {
        this.runExecutor = runExecutor;
    }

    // @find: run agent, give agent a task, start agent run, POST /api/agents/{agentId}/runs
    /**
     * Starts a run and answers at once, with the run's id; the agent works in the background and
     * the run's page shows each step as it happens.
     *
     * <p>The checks happen here, so a paused agent or one with no configuration is still refused
     * with an error before anything is saved. Driving the run inside this request would hold the
     * person for as long as the agent took, past the gateway's one-minute limit.
     */
    @PostMapping("/{agentId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.Codes.AGENT_RUN)
    @Operation(summary = "Give this agent something to do")
    public RunStarted run(@PathVariable UUID agentId, @Valid @RequestBody RunRequest request) {
        UUID orgId = orgId();
        UUID runId = runner.prepare(orgId, agentId, null, request.instruction(), "manual");
        // As the person who started it, so its audit entries and tokens carry their identity.
        runExecutor.submitDrive(orgId, runId, RequestContext.requireActor());
        return new RunStarted(runId, "running", null);
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
        return AgentView.of(agent, version, serverNames(grants.findByAgentIdAndEnabledTrue(agent.getId())));
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
