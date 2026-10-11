// @find: tests for coordinator message context, skill applied to run, skill default agent, mention overrides skill, knowledge scoped search, repository routed to github agent, CoordinatorContextTest
// @what: Tests that what the composer's "+" menu attaches (skill, knowledge, repository) reaches routing and the run.
// @flow: Exercises CoordinatorService.send with a MessageContext, MessageContext and ContextPrompt
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.AgentToolGrant;
import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.service.GeneralEmployee;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;

/**
 * A message with a skill, chosen documents or a repository: the skill's playbook reaches every
 * task and its default agent takes the message unless someone is mentioned, the chosen documents
 * are the only ones searched, and repository work goes to an agent that can read GitHub.
 */
class CoordinatorContextTest {

    private static final UUID ORG = UUID.randomUUID();

    private ChatMessages messages;
    private Agents agents;
    private GoalService goalService;
    private ModelRouterPlanner planner;
    private KnowledgeClient knowledge;
    private ToolGrants grants;
    private SkillLookup skills;
    private CoordinatorService coordinator;
    private Conversation conversation;
    private Agent research;
    private Agent engineering;
    private Agent support;

    @BeforeEach
    void setUp() {
        Conversations conversations = mock(Conversations.class);
        messages = mock(ChatMessages.class);
        agents = mock(Agents.class);
        goalService = mock(GoalService.class);
        planner = mock(ModelRouterPlanner.class);
        knowledge = mock(KnowledgeClient.class);
        grants = mock(ToolGrants.class);
        skills = mock(SkillLookup.class);
        WorkspaceZoneLookup zones = mock(WorkspaceZoneLookup.class);
        SchedulePreviewer previewer = mock(SchedulePreviewer.class);
        GeneralEmployee general = mock(GeneralEmployee.class);
        ChatAppender appender = new ChatAppender(conversations, messages, mock(EntityManager.class));
        coordinator = new CoordinatorService(
                conversations,
                messages,
                agents,
                mock(AgentVersions.class),
                grants,
                mock(Goals.class),
                goalService,
                planner,
                knowledge,
                zones,
                previewer,
                general,
                appender,
                mock(PlatformTransactionManager.class));
        coordinator.useSkills(skills);

        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setOrgId(ORG);
        lenient().when(conversations.findByIdAndOrgId(conversation.getId(), ORG)).thenReturn(Optional.of(conversation));
        lenient().when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(grants.findByAgentIdAndEnabledTrue(any())).thenReturn(List.of());
        lenient().when(zones.zoneFor(ORG)).thenReturn(ZoneId.of("Australia/Melbourne"));
        lenient().when(previewer.tryParse(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(planner.plan(eq(ORG), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(general.activeIn(any())).thenReturn(Optional.empty());
        lenient().when(messages.findEarlierTurns(any(), anyInt(), any())).thenReturn(List.of());

        research = agent("research", "Research");
        engineering = agent("engineering-manager", "Engineering Manager");
        support = agent("support", "Support");
        lenient().when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(engineering, research, support));
        for (Agent a : List.of(research, engineering, support)) {
            lenient().when(agents.findByIdAndOrgId(a.getId(), ORG)).thenReturn(Optional.of(a));
        }

        RequestContext.setActor(Actor.user(
                UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("chat:use", "task:create"), 0L));

        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        lenient().when(goalService.createGoal(eq(ORG), any(), eq(true))).thenReturn(goal);
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private static Agent agent(String key, String name) {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setKey(key);
        agent.setName(name);
        agent.setCategory("operations");
        agent.setStatus("active");
        return agent;
    }

    private SkillLookup.ResolvedSkill skill(UUID defaultAgentId) {
        SkillLookup.ResolvedSkill skill = new SkillLookup.ResolvedSkill(
                UUID.randomUUID(),
                "Weekly status report",
                "1. List what shipped.\n2. List what is blocked.\nEnd with a heading 'Next week'.",
                defaultAgentId,
                List.of("github"));
        when(skills.find(ORG, skill.id())).thenReturn(Optional.of(skill));
        return skill;
    }

    private GoalService.NewGoal startedGoal() {
        ArgumentCaptor<GoalService.NewGoal> spec = ArgumentCaptor.forClass(GoalService.NewGoal.class);
        verify(goalService).createGoal(eq(ORG), spec.capture(), eq(true));
        return spec.getValue();
    }

    @Test
    @DisplayName("a skill's default agent takes the message, every task gets the playbook, and the message shows the skill")
    void skillRoutesToDefaultAgentAndAppliesPlaybook() {
        SkillLookup.ResolvedSkill skill = skill(support.getId());

        CoordinatorService.SendOutcome outcome = coordinator.send(
                ORG,
                conversation.getId(),
                "Do this week's report for the platform team",
                null,
                null,
                MessageContext.of(null, null, skill.id()),
                "Bearer x");

        GoalService.NewGoal goal = startedGoal();
        assertThat(goal.tasks()).singleElement().satisfies(task -> {
            assertThat(task.agentId()).isEqualTo(support.getId());
            assertThat(task.instruction())
                    .contains("Do this week's report for the platform team")
                    .contains("## Skill to follow: Weekly status report")
                    .contains("List what is blocked.")
                    .contains("It expects these connectors: github");
        });
        // The request text kept for "Try again" is what the person wrote, without the playbook.
        assertThat(goal.description()).doesNotContain("Skill to follow");
        ChatMessage sent = outcome.messages().getFirst();
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) sent.getDetail().get("context");
        assertThat(context).containsKey("skill");
        assertThat(context.get("skill").toString()).contains("Weekly status report");
        verify(planner, never()).plan(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a mention wins over the skill's default agent, and the playbook still applies")
    void mentionOverridesSkillDefaultAgent() {
        SkillLookup.ResolvedSkill skill = skill(support.getId());

        coordinator.send(
                ORG,
                conversation.getId(),
                "Do the report",
                List.of(research.getId()),
                null,
                MessageContext.of(null, null, skill.id()),
                "Bearer x");

        assertThat(startedGoal().tasks()).singleElement().satisfies(task -> {
            assertThat(task.agentId()).isEqualTo(research.getId());
            assertThat(task.instruction()).contains("## Skill to follow: Weekly status report");
        });
    }

    @Test
    @DisplayName("a skill chosen with no words runs as 'Run the ... skill.'")
    void skillAloneHasWords() {
        SkillLookup.ResolvedSkill skill = skill(null);

        CoordinatorService.SendOutcome outcome = coordinator.send(
                ORG, conversation.getId(), "  ", null, null, MessageContext.of(null, null, skill.id()), "Bearer x");

        assertThat(outcome.messages().getFirst().getContent()).isEqualTo("Run the Weekly status report skill.");
    }

    @Test
    @DisplayName("a skill that no longer exists is refused before anything is written")
    void missingSkillRefused() {
        UUID gone = UUID.randomUUID();
        when(skills.find(ORG, gone)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.send(
                        ORG, conversation.getId(), "Report", null, null, MessageContext.of(null, null, gone), "Bearer x"))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.details().toString())
                        .contains("skillId"));
        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("chosen documents are the only ones searched, and only their passages reach the agent")
    void knowledgeIsScoped() {
        UUID source = UUID.randomUUID();
        UUID chosenDoc = UUID.randomUUID();
        UUID otherDoc = UUID.randomUUID();
        KnowledgeClient.Passage fromChosen = new KnowledgeClient.Passage(
                UUID.randomUUID(), chosenDoc, source, "Refund policy", null, 1, null, "Refunds within 30 days.", 0.9);
        KnowledgeClient.Passage fromOther = new KnowledgeClient.Passage(
                UUID.randomUUID(), otherDoc, source, "Price list", null, 1, null, "Gold plan costs 99.", 0.8);
        when(knowledge.search(eq(ORG), anyString(), eq("Bearer x"), eq(List.of(source)), anyInt()))
                .thenReturn(Optional.of(new KnowledgeClient.SearchResult(List.of(fromChosen, fromOther), true, false)));

        coordinator.send(
                ORG,
                conversation.getId(),
                "How long do customers have to ask for their money back?",
                null,
                null,
                MessageContext.of(List.of(new MessageContext.KnowledgeRef(source, chosenDoc, "Refund policy")), null, null),
                "Bearer x");

        String instruction = startedGoal().tasks().getFirst().instruction();
        assertThat(instruction)
                .contains("Refunds within 30 days.")
                .doesNotContain("Gold plan costs 99.")
                .contains("## Documents the person chose")
                .contains("\"Refund policy\"");
        verify(knowledge, never()).search(eq(ORG), anyString(), anyString());
    }

    @Test
    @DisplayName("repository work goes to an agent granted GitHub and names the repository, branch and path")
    void repoGoesToGitHubAgent() {
        AgentToolGrant github = new AgentToolGrant();
        github.setAgentId(engineering.getId());
        github.setServer("github");
        when(grants.findByAgentIdAndEnabledTrue(engineering.getId())).thenReturn(List.of(github));

        coordinator.send(
                ORG,
                conversation.getId(),
                "What does this project do?",
                null,
                null,
                MessageContext.of(null, new MessageContext.RepoRef("acme", "website", "main", "docs/"), null),
                "Bearer x");

        assertThat(startedGoal().tasks()).singleElement().satisfies(task -> {
            assertThat(task.agentId()).isEqualTo(engineering.getId());
            assertThat(task.instruction())
                    .contains("GitHub repository acme/website on branch main, in docs")
                    .contains("repo \"acme/website\" and ref \"main\"")
                    .contains("Do not change it");
        });
    }

    @Test
    @DisplayName("a message context is checked: bad repository names are refused and an empty one is NONE")
    void contextValidation() {
        assertThat(MessageContext.of(List.of(), null, null)).isSameAs(MessageContext.NONE);
        assertThatThrownBy(() -> MessageContext.of(null, new MessageContext.RepoRef("acme", "../etc", null, null), null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> MessageContext.of(null, new MessageContext.RepoRef("acme", "site", "main", "../x"), null))
                .isInstanceOf(ApiException.class);
        assertThat(new MessageContext.RepoRef("acme", "site", "main", "src/app").label()).isEqualTo("acme/site@main/src/app");
        assertThat(new MessageContext.RepoRef("acme", "site", null, null).label()).isEqualTo("acme/site");
    }

    @Test
    @DisplayName("a stored context comes back the same, and never carries the skill's text")
    void storedRoundTrip() {
        UUID source = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();
        MessageContext context = MessageContext.of(
                List.of(new MessageContext.KnowledgeRef(source, null, "Handbook")),
                new MessageContext.RepoRef("acme", "site", "dev", null),
                skillId);

        Map<String, Object> stored = context.toStored();
        MessageContext back = MessageContext.fromStored(stored);

        assertThat(back.knowledge()).isEqualTo(context.knowledge());
        assertThat(back.repo()).isEqualTo(context.repo());
        assertThat(back.skillId()).isEqualTo(skillId);
        assertThat(stored.toString()).doesNotContain("instructions");
        assertThat(context.covers(source, UUID.randomUUID())).isTrue();
    }

    @Test
    @DisplayName("rerouting keeps the request's skill, repository, chosen documents and files")
    void rerouteKeepsContext() {
        AttachmentService attachments = mock(AttachmentService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(coordinator, "attachmentService", attachments);
        SkillLookup.ResolvedSkill skill = skill(null);
        UUID source = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        KnowledgeClient.Passage passage = new KnowledgeClient.Passage(
                UUID.randomUUID(), doc, source, "Refund policy", null, 1, null, "Refunds within 30 days.", 0.9);
        when(knowledge.search(eq(ORG), anyString(), eq("Bearer x"), eq(List.of(source)), anyInt()))
                .thenReturn(Optional.of(new KnowledgeClient.SearchResult(List.of(passage), true, false)));

        CoordinatorService.SendOutcome outcome = coordinator.send(
                ORG,
                conversation.getId(),
                "Summarise refunds for the site",
                List.of(research.getId()),
                null,
                MessageContext.of(
                        List.of(new MessageContext.KnowledgeRef(source, doc, "Refund policy")),
                        new MessageContext.RepoRef("acme", "website", "main", null),
                        skill.id()),
                "Bearer x");
        List<ChatMessage> thread = outcome.messages();
        ChatMessage routing = thread.stream().filter(m -> "routing".equals(m.getKind())).findFirst().orElseThrow();
        when(messages.findByIdAndConversationId(routing.getId(), conversation.getId())).thenReturn(Optional.of(routing));
        when(messages.findByConversationIdOrderByPosition(conversation.getId())).thenReturn(thread);

        coordinator.reroute(ORG, conversation.getId(), routing.getId(), support.getId());

        ArgumentCaptor<GoalService.NewGoal> specs = ArgumentCaptor.forClass(GoalService.NewGoal.class);
        verify(goalService, times(2)).createGoal(eq(ORG), specs.capture(), eq(true));
        GoalService.NewTask rerouted = specs.getAllValues().get(1).tasks().getFirst();
        assertThat(rerouted.agentId()).isEqualTo(support.getId());
        assertThat(rerouted.instruction())
                .contains("Summarise refunds for the site")
                .contains("## Skill to follow: Weekly status report")
                .contains("GitHub repository acme/website on branch main")
                .contains("## Documents the person chose")
                .contains("Refunds within 30 days.");
        verify(attachments).followReroute(eq(ORG), eq(conversation.getId()), any(), anyInt(), any());
    }
}
