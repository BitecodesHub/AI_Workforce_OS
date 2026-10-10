// @find: demo agents, seed agents, demo workspace, starter agents on startup, seed demo data, empty Agents page, ApplicationReadyEvent, grants for demo agents
// @what: Seeds four ready-made agents with grants into the demo workspace when the service starts.
// @flow: Runs on ApplicationReadyEvent; uses AgentTemplates
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
import os.aiworkforce.orchestrator.service.AgentTemplates.Grant;
import os.aiworkforce.orchestrator.service.AgentTemplates.Template;
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
 * <p>The four agents come from {@link AgentTemplates}, the same catalogue a real workspace starts
 * from, so the prompts cannot drift. What belongs to the demo alone is here: the grants it gives
 * them, and the legacy prompts it upgrades. A real workspace gets neither - it adds an assistant
 * from the catalogue with no grants, and nothing here ever touches its agents.
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

    /**
     * The prompts every demo agent shipped with before A5.2. Kept verbatim so {@link
     * #upgradePrompts(UUID)} can tell an untouched legacy prompt (safe to replace with a new
     * revision) from one a person has since edited (never touched again). Demo workspace only: no
     * other workspace was ever seeded with them.
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

    // @find: seed demo agents on startup
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (properties.environment().isDeployed()) {
            return;
        }
        try {
            requiresNew.executeWithoutResult(status -> createMissingAgents(DEMO_ORG_ID));
        } catch (RuntimeException e) {
            log.warn("Could not create the demo agents: {}", e.getMessage());
        }
        try {
            requiresNew.executeWithoutResult(status -> upgradePrompts(DEMO_ORG_ID));
        } catch (RuntimeException e) {
            log.warn("Could not upgrade the demo agents' prompts: {}", e.getMessage());
        }
        // Opens its own transaction and never throws - safe to call unconditionally, last.
        generalEmployee.ensure(DEMO_ORG_ID);
    }

    private void createMissingAgents(UUID orgId) {
        int created = 0;
        for (Template template : AgentTemplates.all()) {
            if (agents.findByOrgIdAndKey(orgId, template.key()).isPresent()) {
                continue;
            }
            Agent agent = new Agent();
            agent.setId(UuidV7.generate());
            agent.setOrgId(orgId);
            agent.setKey(template.key());
            agent.setName(template.name());
            agent.setCategory(template.category());
            agent.setDescription(template.description());
            agents.save(agent);

            AgentVersion version = new AgentVersion();
            version.setId(UuidV7.generate());
            version.setAgentId(agent.getId());
            version.setOrgId(orgId);
            version.setRevision(1);
            version.setSystemPrompt(template.prompt());
            version.setMaxSteps(8);
            version.setCreatedBy("system");
            versions.save(version);

            agent.setCurrentVersionId(version.getId());
            agents.save(agent);

            for (Grant grant : template.demoGrants()) {
                saveGrant(orgId, agent.getId(), grant);
            }
            created++;
        }
        if (created > 0) {
            log.info("Created {} demo agent(s) in workspace {}", created, orgId);
        }
    }

    /**
     * Moves an untouched legacy prompt to its A5.2 replacement, as a new revision, and backfills
     * the Support agent's Drive grant for a workspace seeded before it had one. Never touches a
     * version a person has since edited, and never rewrites a version more than once.
     */
    private void upgradePrompts(UUID orgId) {
        int upgraded = 0;
        for (Template template : AgentTemplates.all()) {
            Agent agent = agents.findByOrgIdAndKey(orgId, template.key()).orElse(null);
            if (agent == null || agent.getCurrentVersionId() == null) {
                continue;
            }
            AgentVersion current =
                    versions.findById(agent.getCurrentVersionId()).orElse(null);
            String legacy = LEGACY_PROMPTS.get(template.key());
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
            revision.setOrgId(orgId);
            revision.setRevision(versions.highestRevision(agent.getId()) + 1);
            revision.setSystemPrompt(template.prompt());
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
            log.info("Upgraded {} demo agent prompt(s) in workspace {}", upgraded, orgId);
        }
        backfillSupportDriveGrant(orgId);
    }

    private void backfillSupportDriveGrant(UUID orgId) {
        Agent support = agents.findByOrgIdAndKey(orgId, "support").orElse(null);
        if (support == null) {
            return;
        }
        if (grants.findByAgentIdAndServer(support.getId(), SUPPORT_DRIVE_GRANT.server())
                .isPresent()) {
            return;
        }
        saveGrant(orgId, support.getId(), SUPPORT_DRIVE_GRANT);
    }

    private void saveGrant(UUID orgId, UUID agentId, Grant grant) {
        AgentToolGrant row = new AgentToolGrant();
        row.setId(UuidV7.generate());
        row.setOrgId(orgId);
        row.setAgentId(agentId);
        row.setServer(grant.server());
        row.setAllowedTools(grant.tools());
        row.setScopes(grant.scopes());
        row.setMaxCallsPerRun(10);
        grants.save(row);
    }
}
