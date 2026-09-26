package os.aiworkforce.orchestrator.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Component
@ConditionalOnProperty(name = "aiwos.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoAgentSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoAgentSeeder.class);

    /** Matches the identity service's demo workspace, so the accounts and the agents meet. */
    public static final UUID DEMO_ORG_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private record Grant(String server, List<String> tools, List<String> scopes) {}

    private record DemoAgent(String key, String name, String category, String prompt, List<Grant> grants) {}

    private static final List<DemoAgent> AGENTS = List.of(
            new DemoAgent("hr", "HR", "operations",
                    """
                    You handle people operations for a small care provider. Screen applications \
                    against the stated requirements of the role, draft onboarding email in plain \
                    English, and book interviews. Leave email as a draft for a person to send.""",
                    List.of(
                            new Grant("gmail", List.of("list_messages", "get_message", "draft_message", "send_message"),
                                    List.of("gmail.readonly", "gmail.compose", "gmail.send")),
                            new Grant("calendar", List.of(), List.of("calendar.readonly", "calendar.events")))),
            new DemoAgent("engineering-manager", "Engineering Manager", "engineering",
                    """
                    You keep an engineering team's tickets current, summarise open pull requests, \
                    and write the standup note. Post to a channel only when asked.""",
                    List.of(
                            new Grant("github", List.of(), List.of("repo:read", "repo:write")),
                            new Grant("jira", List.of(), List.of("read:jira-work", "write:jira-work")),
                            new Grant("slack", List.of("list_channels", "get_messages", "post_message"),
                                    List.of("channels:read", "channels:history", "chat:write")))),
            new DemoAgent("research", "Research", "growth",
                    """
                    You compile market and competitor reports. Say what the sources support and \
                    what they do not, and write the summary into a shareable document.""",
                    List.of(new Grant("drive", List.of(), List.of("drive.readonly", "drive.file")))),
            new DemoAgent("support", "Customer Support", "support",
                    """
                    You triage support tickets and draft replies from the support handbook. When the \
                    handbook does not answer a question, escalate rather than guess.""",
                    List.of(
                            new Grant("gmail", List.of("list_messages", "get_message", "draft_message", "send_message"),
                                    List.of("gmail.readonly", "gmail.compose", "gmail.send")),
                            new Grant("slack", List.of("get_messages", "post_message"),
                                    List.of("channels:history", "chat:write")))));

    private final Agents agents;
    private final AgentVersions versions;
    private final ToolGrants grants;
    private final PlatformProperties properties;

    public DemoAgentSeeder(
            Agents agents, AgentVersions versions, ToolGrants grants, PlatformProperties properties) {
        this.agents = agents;
        this.versions = versions;
        this.grants = grants;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (properties.environment().isDeployed()) {
            return;
        }
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
                AgentToolGrant row = new AgentToolGrant();
                row.setId(UuidV7.generate());
                row.setOrgId(DEMO_ORG_ID);
                row.setAgentId(agent.getId());
                row.setServer(grant.server());
                row.setAllowedTools(grant.tools());
                row.setScopes(grant.scopes());
                row.setMaxCallsPerRun(10);
                grants.save(row);
            }
            created++;
        }
        if (created > 0) {
            log.info("Created {} demo agent(s) in workspace {}", created, DEMO_ORG_ID);
        }
    }
}
