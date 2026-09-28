package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.schedule.ParsedSchedule;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;

import java.util.Set;

class CoordinatorServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    private Conversations conversations;
    private ChatMessages messages;
    private Agents agents;
    private ToolGrants grants;
    private Goals goals;
    private GoalService goalService;
    private ModelRouterPlanner modelPlanner;
    private KnowledgeClient knowledge;
    private WorkspaceZoneLookup zones;
    private SchedulePreviewer schedulePreviewer;
    private CoordinatorService coordinator;

    private Conversation conversation;
    private Agent research;
    private Agent support;
    private UUID requesterId;

    @BeforeEach
    void setUp() {
        conversations = mock(Conversations.class);
        messages = mock(ChatMessages.class);
        agents = mock(Agents.class);
        grants = mock(ToolGrants.class);
        goals = mock(Goals.class);
        goalService = mock(GoalService.class);
        modelPlanner = mock(ModelRouterPlanner.class);
        knowledge = mock(KnowledgeClient.class);
        zones = mock(WorkspaceZoneLookup.class);
        schedulePreviewer = mock(SchedulePreviewer.class);
        coordinator = new CoordinatorService(
                conversations, messages, agents, grants, goals, goalService, modelPlanner, knowledge, zones, schedulePreviewer);

        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setOrgId(ORG);
        when(conversations.findByIdAndOrgId(conversation.getId(), ORG)).thenReturn(Optional.of(conversation));
        lenient().when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(grants.findByAgentIdAndEnabledTrue(any())).thenReturn(List.of());
        when(zones.zoneFor(ORG)).thenReturn(ZoneId.of("Australia/Melbourne"));
        lenient().when(schedulePreviewer.tryParse(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(modelPlanner.plan(any(), any(), any())).thenReturn(Optional.empty());

        research = agentRow("research", "Research");
        support = agentRow("support", "Customer Support");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(research, support));
        lenient().when(agents.findByIdAndOrgId(research.getId(), ORG)).thenReturn(Optional.of(research));
        lenient().when(agents.findByIdAndOrgId(support.getId(), ORG)).thenReturn(Optional.of(support));

        requesterId = UUID.randomUUID();
        RequestContext.setActor(Actor.user(requesterId.toString(), ORG.toString(), "role", Set.of("chat:use"), 0L));

        Goal fakeGoal = new Goal();
        fakeGoal.setId(UUID.randomUUID());
        fakeGoal.setOrgId(ORG);
        lenient().when(goalService.createGoal(eq(ORG), any(), eq(true))).thenReturn(fakeGoal);
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private static Agent agentRow(String key, String name) {
        Agent agent = new Agent();
        agent.setId(UUID.randomUUID());
        agent.setKey(key);
        agent.setName(name);
        agent.setCategory(key);
        agent.setStatus("active");
        return agent;
    }

    @Test
    @DisplayName("several mentions chain into one goal, each task depending on the one before it")
    void mentionsChainIntoOneGoal() {
        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "@Research @Customer Support please handle this", null, null);

        assertThat(created).hasSize(3);
        assertThat(created.get(0).getKind()).isEqualTo("text");
        assertThat(created.get(0).getAuthorKind()).isEqualTo("user");

        ChatMessage routing = created.get(1);
        assertThat(routing.getKind()).isEqualTo("routing");
        assertThat(routing.getDetail()).containsEntry("mode", "mention");
        assertThat(routing.getDetail().get("reason")).isEqualTo("You mentioned @Research and @Customer Support.");

        ChatMessage progress = created.get(2);
        assertThat(progress.getKind()).isEqualTo("progress");

        ArgumentCaptor<GoalService.NewGoal> spec = ArgumentCaptor.forClass(GoalService.NewGoal.class);
        verify(goalService).createGoal(eq(ORG), spec.capture(), eq(true));
        GoalService.NewGoal newGoal = spec.getValue();
        assertThat(newGoal.source()).isEqualTo("chat");
        assertThat(newGoal.conversationId()).isEqualTo(conversation.getId());
        assertThat(newGoal.requestedBy()).isEqualTo(requesterId);
        assertThat(newGoal.tasks()).hasSize(2);
        assertThat(newGoal.tasks().get(0).agentId()).isEqualTo(research.getId());
        assertThat(newGoal.tasks().get(0).dependsOnPositions()).isEmpty();
        assertThat(newGoal.tasks().get(1).agentId()).isEqualTo(support.getId());
        assertThat(newGoal.tasks().get(1).dependsOnPositions()).containsExactly(0);

        assertThat(conversation.getTitle()).isNotBlank();
    }

    @Test
    @DisplayName("the model router's plan is used, and routed as mode 'model', when it answers")
    void modelPlanIsUsedWhenAvailable() {
        when(modelPlanner.plan(eq(ORG), any(), any())).thenReturn(Optional.of(
                new ModelRouterPlanner.Plan(
                        List.of(new ModelRouterPlanner.PlannedStep(support.getId(), "Reply to the customer")),
                        "The model chose Customer Support.")));

        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "please sort out this ticket", null, null);

        ChatMessage routing = created.get(1);
        assertThat(routing.getDetail()).containsEntry("mode", "model");
        assertThat(routing.getDetail()).containsEntry("reason", "The model chose Customer Support.");
        verify(goalService).createGoal(eq(ORG), any(), eq(true));
    }

    @Test
    @DisplayName("when nothing routes, a routing message asks rather than creating a goal")
    void needsChoiceCreatesNoGoal() {
        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "please take care of this for me", null, null);

        assertThat(created).hasSize(2);
        ChatMessage routing = created.get(1);
        assertThat(routing.getKind()).isEqualTo("routing");
        assertThat(routing.getDetail()).containsEntry("needsChoice", true);
        assertThat(routing.getContent()).isEqualTo(CoordinatorService.NEEDS_CHOICE_MESSAGE);
        verify(goalService, never()).createGoal(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a document question searches the knowledge base rather than routing to an agent")
    void documentsQuestionSearchesKnowledge() {
        KnowledgeClient.Passage passage = new KnowledgeClient.Passage(
                UUID.randomUUID(), UUID.randomUUID(), "Employee Handbook", "https://example/doc", 3,
                "Refunds", "Refunds are processed within five days.", 0.9);
        when(knowledge.search(eq(ORG), any(), any())).thenReturn(Optional.of(
                new KnowledgeClient.SearchResult(List.of(passage), true)));

        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "What is our refund policy?", null, "Bearer token");

        assertThat(created).hasSize(2);
        ChatMessage documents = created.get(1);
        assertThat(documents.getKind()).isEqualTo("documents");
        assertThat(documents.getDetail()).containsEntry("grounded", true);
        verify(goalService, never()).createGoal(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("an unreachable knowledge service produces an honest error, not a stack trace")
    void documentsFailureIsReportedHonestly() {
        when(knowledge.search(eq(ORG), any(), any())).thenReturn(Optional.empty());

        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "What is our refund policy?", null, "Bearer token");

        ChatMessage error = created.get(1);
        assertThat(error.getKind()).isEqualTo("error");
        assertThat(error.getContent()).isEqualTo(CoordinatorService.DOCUMENTS_UNAVAILABLE);
    }

    @Test
    @DisplayName("a schedule phrase becomes a suggestion, naming the agent the same routing would choose")
    void schedulePhraseBecomesSuggestion() {
        when(schedulePreviewer.tryParse(any(), any(), any())).thenReturn(Optional.of(
                new ParsedSchedule("recurring", "0 0 9 * * MON-FRI", null, "every weekday at 9am")));
        when(modelPlanner.plan(eq(ORG), eq("summarise support tickets"), any())).thenReturn(Optional.of(
                new ModelRouterPlanner.Plan(
                        List.of(new ModelRouterPlanner.PlannedStep(support.getId(), "summarise support tickets")),
                        "reason")));

        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "every weekday at 9am summarise support tickets", null, null);

        assertThat(created).hasSize(2);
        ChatMessage suggestion = created.get(1);
        assertThat(suggestion.getKind()).isEqualTo("schedule_suggestion");
        assertThat(suggestion.getDetail()).containsEntry("agentId", support.getId().toString());
        assertThat(suggestion.getDetail()).containsEntry("agentName", "Customer Support");
        assertThat(suggestion.getDetail()).containsEntry("instruction", "summarise support tickets");
        assertThat(suggestion.getDetail()).containsEntry("cron", "0 0 9 * * MON-FRI");
        verify(goalService, never()).createGoal(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a goal the engine refuses becomes an honest error message, not a failed request")
    void refusedGoalBecomesErrorMessage() {
        when(goalService.createGoal(eq(ORG), any(), eq(true)))
                .thenThrow(ApiException.validation("tasks", "a chat message may start at most 5 tasks"));

        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "please handle this", List.of(research.getId(), support.getId()), null);

        assertThat(created).hasSize(2);
        ChatMessage error = created.get(1);
        assertThat(error.getKind()).isEqualTo("error");
        assertThat(error.getContent()).contains("at most 5 tasks");
    }

    @Test
    @DisplayName("rerouting cancels the active goal and starts a new one for the chosen agent")
    void rerouteCancelsAndStartsAgain() {
        UUID oldGoalId = UUID.randomUUID();
        Goal oldGoal = new Goal();
        oldGoal.setId(oldGoalId);
        oldGoal.setOrgId(ORG);
        oldGoal.setStatus("running");
        when(goals.findByIdAndOrgId(oldGoalId, ORG)).thenReturn(Optional.of(oldGoal));

        ChatMessage userMessage = ChatMessage.of(
                ORG, conversation.getId(), 0, "user", requesterId, null, "text",
                "please sort out this ticket", java.util.Map.of(), null);
        ChatMessage routingMessage = ChatMessage.of(
                ORG, conversation.getId(), 1, "coordinator", null, null, "routing",
                "needs a choice", java.util.Map.of(), oldGoalId);
        when(messages.findByIdAndConversationId(routingMessage.getId(), conversation.getId()))
                .thenReturn(Optional.of(routingMessage));
        when(messages.findByConversationIdOrderByPosition(conversation.getId()))
                .thenReturn(new ArrayList<>(List.of(userMessage, routingMessage)));

        List<ChatMessage> created = coordinator.reroute(ORG, conversation.getId(), routingMessage.getId(), support.getId());

        verify(goalService).cancel(ORG, oldGoalId);
        assertThat(created).hasSize(2);
        ChatMessage newRouting = created.get(0);
        assertThat(newRouting.getDetail()).containsEntry("mode", "manual");
        assertThat(newRouting.getDetail().get("reason")).isEqualTo("You chose Customer Support.");
        ArgumentCaptor<GoalService.NewGoal> spec = ArgumentCaptor.forClass(GoalService.NewGoal.class);
        verify(goalService).createGoal(eq(ORG), spec.capture(), eq(true));
        assertThat(spec.getValue().tasks().getFirst().instruction()).isEqualTo("please sort out this ticket");
        assertThat(spec.getValue().tasks().getFirst().agentId()).isEqualTo(support.getId());
    }
}
