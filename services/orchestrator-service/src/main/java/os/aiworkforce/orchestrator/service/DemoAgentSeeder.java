package os.aiworkforce.orchestrator.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.AgentVersion;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.platform.config.PlatformProperties;
import os.aiworkforce.platform.web.persistence.UuidV7;

/**
 * Gives the demo workspace four agents to work with.
 *
 * <p>Someone signing in with a demo account and finding an empty Agents page has to understand
 * the configuration model before they can see anything happen, which is the wrong order. Four
 * agents that already exist - each with a persona, a grant, and one tool it may use only with
 * approval - let the first click be "give it a task" rather than "read the documentation".
 *
 * <p>Local and test only, idempotent, and it never rewrites an agent that already exists.
 *
 * <p>{@link #seed()} runs at start-up and must never fail it. Its three steps - creating whatever
 * agents are missing, upgrading whichever prompts a person never touched, and ensuring the General
 * Employee - each run in their own transaction and their own {@code try/catch}, so a problem in one
 * never undoes what an earlier step already committed, and never stops the service from starting.
 */
@Component
@ConditionalOnProperty(name = "aiwos.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoAgentSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoAgentSeeder.class);

    /** Matches the identity service's demo workspace, so the accounts and the agents meet. */
    public static final UUID DEMO_ORG_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private record Grant(String server, List<String> tools, List<String> scopes) {}

    private record DemoAgent(String key, String name, String category, String prompt, List<Grant> grants) {}

    /**
     * The prompts every demo agent shipped with before A5.2. Kept verbatim so {@link
     * #upgradePrompts()} can tell an untouched legacy prompt (safe to replace with a new revision)
     * from one a person has since edited (never touched again).
     */
    private static final Map<String, String> LEGACY_PROMPTS = Map.of(
            "hr",
            """
            You handle people operations for a small care provider. Screen applications \
            against the stated requirements of the role, draft onboarding email in plain \
            English, and book interviews. Leave email as a draft for a person to send.""",
            "engineering-manager",
            """
            You keep an engineering team's tickets current, summarise open pull requests, \
            and write the standup note. Post to a channel only when asked.""",
            "research",
            """
            You compile market and competitor reports. Say what the sources support and \
            what they do not, and write the summary into a shareable document.""",
            "support",
            """
            You triage support tickets and draft replies from the support handbook. When the \
            handbook does not answer a question, escalate rather than guess.""");

    private static final List<DemoAgent> AGENTS = List.of(
            new DemoAgent(
                    "hr",
                    "HR",
                    "operations",
                    """
                    You handle people operations for a small care provider: screening applications, drafting onboarding and candidate email, and booking interviews.

                    How you work
                    - Read before you write: check the mailbox or the calendar for what already exists before drafting or booking.
                    - Draft email and leave it as a draft unless the person asks you to send it. Sending waits for a person's approval.
                    - Before booking an interview, check the calendar for clashes and propose a time that is free.
                    - When a detail is missing but a sensible default exists, use a clearly marked placeholder such as [start date] or [manager name], and list the placeholders at the end so the person can fill them in.
                    - Ask with the person__ask_question tool only when the choice changes the work: which candidate, which role, or which of several free interview slots. Offer the likely options, with the one you recommend first.
                    - Never reply only that the request is incomplete. Draft what you can and say what is still needed.""",
                    List.of(
                            new Grant(
                                    "gmail",
                                    List.of("list_messages", "get_message", "draft_message", "send_message"),
                                    List.of("gmail.readonly", "gmail.compose", "gmail.send")),
                            new Grant("calendar", List.of(), List.of("calendar.readonly", "calendar.events")),
                            new Grant("voice", List.of("create_voice_note"), List.of()))),
            new DemoAgent(
                    "engineering-manager",
                    "Engineering Manager",
                    "engineering",
                    """
                    You keep an engineering team's tickets current, summarise open pull requests, and write the standup note.

                    How you work
                    - Read before you write: list issues or pull requests before creating or changing anything.
                    - Summaries lead with work that is blocked, then what changed, then what happens next.
                    - Post to a channel only when the person asks for it. Posting waits for a person's approval.
                    - When the repository, project or channel is not named and your standing goals do not say which to use, ask with the person__ask_question tool. Offer the ones you can see or that were used before; the person can always write another.
                    - Never reply only that the request is incomplete. Do the part you can and say what is still needed.""",
                    List.of(
                            new Grant("github", List.of(), List.of("repo:read", "repo:write")),
                            new Grant("jira", List.of(), List.of("read:jira-work", "write:jira-work")),
                            new Grant(
                                    "slack",
                                    List.of("list_channels", "get_messages", "post_message"),
                                    List.of("channels:read", "channels:history", "chat:write")))),
            new DemoAgent(
                    "research",
                    "Research",
                    "growth",
                    """
                    You compile market and competitor reports from the workspace's Google Drive documents and from general knowledge.

                    How you work
                    - You have no web access. Say which points come from a Drive file, naming it, and which come from general knowledge, and say when something may be out of date.
                    - Say what the sources support and what they do not.
                    - When the market, the competitors or the period to cover is not stated and a wrong guess would waste the work, ask with the person__ask_question tool, offering the likely sets as options with your recommendation first.
                    - When the report is finished, save it as a new document with the Drive tool and give its name.
                    - Never reply only that the request is incomplete. Outline what you can and say what is still needed.""",
                    List.of(new Grant("drive", List.of(), List.of("drive.readonly", "drive.file")))),
            new DemoAgent(
                    "support",
                    "Customer Support",
                    "support",
                    """
                    You triage support tickets and draft replies from the support handbook kept in the workspace's Google Drive.

                    How you work
                    - Look up the handbook before drafting a reply, and quote the part you relied on.
                    - Draft replies and leave them as drafts unless the person asks you to send one. Sending waits for a person's approval.
                    - When the handbook does not answer the question, do not guess. Draft a short holding reply, say what you could not confirm, and ask the person with the person__ask_question tool what to do: escalate in Slack, use the holding reply, or leave it for a person.
                    - Never reply only that the request is incomplete. Draft what you can and say what is still needed.""",
                    List.of(
                            new Grant(
                                    "gmail",
                                    List.of("list_messages", "get_message", "draft_message", "send_message"),
                                    List.of("gmail.readonly", "gmail.compose", "gmail.send")),
                            new Grant(
                                    "slack",
                                    List.of("get_messages", "post_message"),
                                    List.of("channels:history", "chat:write")),
                            new Grant("voice", List.of("create_voice_note"), List.of()))));

    /** The drive grant backfilled onto the demo Support agent, for workspaces seeded before it had one. */
    private static final Grant SUPPORT_DRIVE_GRANT =
            new Grant("drive", List.of("list_files", "get_file"), List.of("drive.readonly"));

    private final Agents agents;
    private final AgentVersions versions;
    private final ToolGrants grants;
    private final PlatformProperties properties;
    private final GeneralEmployee generalEmployee;
    private final TransactionTemplate requiresNew;

    public DemoAgentSeeder(
            Agents agents,
            AgentVersions versions,
            ToolGrants grants,
            PlatformProperties properties,
            GeneralEmployee generalEmployee,
            PlatformTransactionManager transactionManager) {
        this.agents = agents;
        this.versions = versions;
        this.grants = grants;
        this.properties = properties;
        this.generalEmployee = generalEmployee;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (properties.environment().isDeployed()) {
            return;
        }
        try {
            requiresNew.executeWithoutResult(status -> createMissingAgents());
        } catch (RuntimeException e) {
            log.warn("Could not create the demo agents: {}", e.getMessage());
        }
        try {
            requiresNew.executeWithoutResult(status -> upgradePrompts());
        } catch (RuntimeException e) {
            log.warn("Could not upgrade the demo agents' prompts: {}", e.getMessage());
        }
        // Opens its own transaction and never throws - safe to call unconditionally, last.
        generalEmployee.ensure(DEMO_ORG_ID);
    }

    private void createMissingAgents() {
        int created = 0;
        for (DemoAgent demo : AGENTS) {
            if (agents.findByOrgIdAndKey(DEMO_ORG_ID, demo.key()).isPresent()) {
                continue;
            }
            Agent agent = new Agent();
            agent.setId(UuidV7.generate());
            agent.setOrgId(DEMO_ORG_ID);
            agent.setKey(demo.key());
            agent.setName(demo.name());
            agent.setCategory(demo.category());
            agents.save(agent);

            AgentVersion version = new AgentVersion();
            version.setId(UuidV7.generate());
            version.setAgentId(agent.getId());
            version.setOrgId(DEMO_ORG_ID);
            version.setRevision(1);
            version.setSystemPrompt(demo.prompt());
            version.setMaxSteps(8);
            version.setCreatedBy("system");
            versions.save(version);

            agent.setCurrentVersionId(version.getId());
            agents.save(agent);

            for (Grant grant : demo.grants()) {
                saveGrant(agent.getId(), grant);
            }
            created++;
        }
        if (created > 0) {
            log.info("Created {} demo agent(s) in workspace {}", created, DEMO_ORG_ID);
        }
    }

    /**
     * Moves an untouched legacy prompt to its A5.2 replacement, as a new revision, and backfills
     * the Support agent's Drive grant for a workspace seeded before it had one. Never touches a
     * version a person has since edited, and never rewrites a version more than once.
     */
    private void upgradePrompts() {
        int upgraded = 0;
        for (DemoAgent demo : AGENTS) {
            Agent agent = agents.findByOrgIdAndKey(DEMO_ORG_ID, demo.key()).orElse(null);
            if (agent == null || agent.getCurrentVersionId() == null) {
                continue;
            }
            AgentVersion current =
                    versions.findById(agent.getCurrentVersionId()).orElse(null);
            String legacy = LEGACY_PROMPTS.get(demo.key());
            if (current == null || legacy == null) {
                continue;
            }
            if (!"system".equals(current.getCreatedBy())) {
                continue;
            }
            if (!current.getSystemPrompt().strip().equals(legacy.strip())) {
                continue;
            }

            AgentVersion revision = new AgentVersion();
            revision.setId(UuidV7.generate());
            revision.setAgentId(agent.getId());
            revision.setOrgId(DEMO_ORG_ID);
            revision.setRevision(versions.highestRevision(agent.getId()) + 1);
            revision.setSystemPrompt(demo.prompt());
            revision.setGoals(current.getGoals());
            revision.setTemperature(current.getTemperature());
            revision.setMaxOutputTokens(current.getMaxOutputTokens());
            revision.setMaxSteps(current.getMaxSteps());
            revision.setCreatedBy("system");
            versions.save(revision);

            agent.setCurrentVersionId(revision.getId());
            agents.save(agent);
            upgraded++;
        }
        if (upgraded > 0) {
            log.info("Upgraded {} demo agent prompt(s) in workspace {}", upgraded, DEMO_ORG_ID);
        }
        backfillSupportDriveGrant();
    }

    private void backfillSupportDriveGrant() {
        Agent support = agents.findByOrgIdAndKey(DEMO_ORG_ID, "support").orElse(null);
        if (support == null) {
            return;
        }
        if (grants.findByAgentIdAndServer(support.getId(), SUPPORT_DRIVE_GRANT.server())
                .isPresent()) {
            return;
        }
        saveGrant(support.getId(), SUPPORT_DRIVE_GRANT);
    }

    private void saveGrant(UUID agentId, Grant grant) {
        AgentToolGrant row = new AgentToolGrant();
        row.setId(UuidV7.generate());
        row.setOrgId(DEMO_ORG_ID);
        row.setAgentId(agentId);
        row.setServer(grant.server());
        row.setAllowedTools(grant.tools());
        row.setScopes(grant.scopes());
        row.setMaxCallsPerRun(10);
        grants.save(row);
    }
}
