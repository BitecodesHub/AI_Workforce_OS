package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.ArrayList;
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
import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.Goal;
import os.aiworkforce.orchestrator.domain.Task;
import os.aiworkforce.orchestrator.repository.AgentVersions;
import os.aiworkforce.orchestrator.repository.Agents;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.ToolGrants;
import os.aiworkforce.orchestrator.schedule.ParsedSchedule;
import os.aiworkforce.orchestrator.service.GeneralEmployee;
import os.aiworkforce.orchestrator.service.GoalService;
import os.aiworkforce.platform.context.Actor;
import os.aiworkforce.platform.context.RequestContext;
import os.aiworkforce.platform.error.ApiException;
import os.aiworkforce.platform.error.ErrorCode;

class CoordinatorServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    private Conversations conversations;
    private ChatMessages messages;
    private Agents agents;
    private AgentVersions versions;
    private ToolGrants grants;
    private Goals goals;
    private GoalService goalService;
    private ModelRouterPlanner modelPlanner;
    private KnowledgeClient knowledge;
    private WorkspaceZoneLookup zones;
    private SchedulePreviewer schedulePreviewer;
    private GeneralEmployee generalEmployee;
    private ChatAppender appender;
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
        versions = mock(AgentVersions.class);
        grants = mock(ToolGrants.class);
        goals = mock(Goals.class);
        goalService = mock(GoalService.class);
        modelPlanner = mock(ModelRouterPlanner.class);
        knowledge = mock(KnowledgeClient.class);
        zones = mock(WorkspaceZoneLookup.class);
        schedulePreviewer = mock(SchedulePreviewer.class);
        generalEmployee = mock(GeneralEmployee.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        EntityManager entityManager = mock(EntityManager.class);
        appender = new ChatAppender(conversations, messages, entityManager);

        coordinator = new CoordinatorService(
                conversations,
                messages,
                agents,
                versions,
                grants,
                goals,
                goalService,
                modelPlanner,
                knowledge,
                zones,
                schedulePreviewer,
                generalEmployee,
                appender,
                transactionManager);

        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setOrgId(ORG);
        lenient()
                .when(conversations.findByIdAndOrgId(conversation.getId(), ORG))
                .thenReturn(Optional.of(conversation));
        lenient().when(conversations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(grants.findByAgentIdAndEnabledTrue(any())).thenReturn(List.of());
        lenient().when(zones.zoneFor(ORG)).thenReturn(ZoneId.of("Australia/Melbourne"));
        lenient().when(schedulePreviewer.tryParse(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(modelPlanner.plan(eq(ORG), any(), any())).thenReturn(Optional.empty());
        lenient().when(modelPlanner.plan(eq(ORG), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(generalEmployee.activeIn(any())).thenReturn(Optional.empty());
        lenient()
                .when(messages.findByConversationIdAndPositionLessThanOrderByPositionDesc(any(), anyInt(), any()))
                .thenReturn(List.of());
        lenient()
                .when(messages.findByConversationIdAndPositionGreaterThanOrderByPosition(any(), anyInt()))
                .thenReturn(List.of());

        research = agentRow("research", "Research");
        support = agentRow("support", "Customer Support");
        lenient().when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(research, support));
        lenient().when(agents.findByIdAndOrgId(research.getId(), ORG)).thenReturn(Optional.of(research));
        lenient().when(agents.findByIdAndOrgId(support.getId(), ORG)).thenReturn(Optional.of(support));

        requesterId = UUID.randomUUID();
        RequestContext.setActor(
                Actor.user(requesterId.toString(), ORG.toString(), "role", Set.of("chat:use", "task:create"), 0L));

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

    private static Agent fallbackAgent() {
        Agent agent = agentRow("general", "General Employee");
        agent.setFallback(true);
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
        when(modelPlanner.plan(eq(ORG), any(), any(), any()))
                .thenReturn(Optional.of(new ModelRouterPlanner.Plan(
                        List.of(new ModelRouterPlanner.PlannedStep(support.getId(), "Reply to the customer")),
                        "The model chose Customer Support.")));

        List<ChatMessage> created =
                coordinator.handleMessage(ORG, conversation.getId(), "please sort out this ticket", null, null);

        ChatMessage routing = created.get(1);
        assertThat(routing.getDetail()).containsEntry("mode", "model");
        assertThat(routing.getDetail()).containsEntry("reason", "The model chose Customer Support.");
        verify(goalService).createGoal(eq(ORG), any(), eq(true));
    }

    @Test
    @DisplayName("unmatched text goes to General Employee instead of a dead end")
    void unmatchedGoesToGeneralEmployee() {
        Agent general = fallbackAgent();
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(research, support, general));
        when(generalEmployee.activeIn(List.of(research, support, general))).thenReturn(Optional.of(general));

        List<ChatMessage> created =
                coordinator.handleMessage(ORG, conversation.getId(), "please take care of this for me", null, null);

        assertThat(created).hasSize(3);
        ChatMessage routing = created.get(1);
        assertThat(routing.getKind()).isEqualTo("routing");
        assertThat(routing.getDetail()).containsEntry("mode", "fallback");
        assertThat(routing.getDetail().get("reason"))
                .isEqualTo("No specialist matched this, so General Employee is taking it.");
        verify(goalService).createGoal(eq(ORG), any(), eq(true));
    }

    @Test
    @DisplayName("a paused General Employee is never chosen, and the choice card offers no fallback")
    void pausedGeneralShowsChoice() {
        Agent general = fallbackAgent();
        general.setStatus("paused");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(research, support, general));
        when(generalEmployee.activeIn(List.of(research, support, general))).thenReturn(Optional.empty());

        List<ChatMessage> created =
                coordinator.handleMessage(ORG, conversation.getId(), "please take care of this for me", null, null);

        assertThat(created).hasSize(2);
        ChatMessage routing = created.get(1);
        assertThat(routing.getDetail()).containsEntry("needsChoice", true);
        assertThat(routing.getContent()).isEqualTo(CoordinatorService.NO_MATCH_MESSAGE);
        verify(goalService, never()).createGoal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("a tie between two specialists asks, and lists General last among the alternatives")
    void tieShowsChoiceWithGeneralLast() {
        Agent alpha = agentRow("alpha", "Alpha Team");
        Agent beta = agentRow("beta", "Beta Team");
        Agent general = fallbackAgent();
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(alpha, beta, general));
        when(generalEmployee.activeIn(List.of(alpha, beta, general))).thenReturn(Optional.of(general));

        List<ChatMessage> created =
                coordinator.handleMessage(ORG, conversation.getId(), "team review please", null, null);

        ChatMessage routing = created.get(1);
        assertThat(routing.getDetail()).containsEntry("needsChoice", true);
        assertThat(routing.getContent()).isEqualTo(CoordinatorService.TIE_MESSAGE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alternatives =
                (List<Map<String, Object>>) routing.getDetail().get("alternatives");
        assertThat(alternatives).isNotEmpty();
        assertThat(alternatives.getLast().get("id")).isEqualTo(general.getId().toString());
        assertThat(alternatives.getLast().get("score")).isEqualTo(0);
    }

    @Test
    @DisplayName("a mention of a paused agent starts nothing, and offers a choice instead")
    void pausedMentionStartsNothing() {
        research.setStatus("paused");
        Agent general = fallbackAgent();
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(research, support, general));
        when(generalEmployee.activeIn(List.of(research, support, general))).thenReturn(Optional.of(general));

        List<ChatMessage> created =
                coordinator.handleMessage(ORG, conversation.getId(), "@Research help please", null, null);

        assertThat(created).hasSize(2);
        ChatMessage routing = created.get(1);
        assertThat(routing.getDetail()).containsEntry("needsChoice", true);
        assertThat(routing.getContent()).contains("Research is paused");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alternatives =
                (List<Map<String, Object>>) routing.getDetail().get("alternatives");
        assertThat(alternatives.getFirst().get("id")).isEqualTo(general.getId().toString());
        assertThat(alternatives.getFirst().get("score")).isEqualTo(0);
        verify(goalService, never()).createGoal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("fallback work names the colleagues the person can point to instead")
    void fallbackInstructionListsColleagues() {
        Agent general = fallbackAgent();
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(research, support, general));
        when(generalEmployee.activeIn(List.of(research, support, general))).thenReturn(Optional.of(general));

        coordinator.handleMessage(ORG, conversation.getId(), "please take care of this for me", null, null);

        ArgumentCaptor<GoalService.NewGoal> spec = ArgumentCaptor.forClass(GoalService.NewGoal.class);
        verify(goalService).createGoal(eq(ORG), spec.capture(), eq(true));
        String instruction = spec.getValue().tasks().getFirst().instruction();
        assertThat(instruction).contains("About this workspace: besides you, it has");
        assertThat(instruction).contains("Research");
        assertThat(instruction).contains("Customer Support");
    }

    @Test
    @DisplayName("a custom agent keyed 'general' without the fallback flag is scored as an ordinary specialist")
    void customAgentKeyedGeneralIsScoredAsSpecialist() {
        Agent customGeneral = agentRow("general", "General Helper");
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(customGeneral));
        when(generalEmployee.activeIn(List.of(customGeneral))).thenReturn(Optional.empty());

        List<ChatMessage> created =
                coordinator.handleMessage(ORG, conversation.getId(), "ask the general helper about this", null, null);

        ChatMessage routing = created.get(1);
        assertThat(routing.getDetail()).containsEntry("mode", "rules");
        assertThat(routing.getDetail()).containsEntry("needsChoice", false);
    }

    @Test
    @DisplayName("a document question searches the knowledge base rather than routing to an agent")
    void documentsQuestionSearchesKnowledge() {
        KnowledgeClient.Passage passage = new KnowledgeClient.Passage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Employee Handbook",
                "https://example/doc",
                3,
                "Refunds",
                "Refunds are processed within five days.",
                0.9);
        when(knowledge.search(eq(ORG), any(), any()))
                .thenReturn(Optional.of(new KnowledgeClient.SearchResult(List.of(passage), true)));

        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "What is our refund policy?", null, "Bearer token");

        assertThat(created).hasSize(2);
        ChatMessage documents = created.get(1);
        assertThat(documents.getKind()).isEqualTo("documents");
        assertThat(documents.getDetail()).containsEntry("grounded", true);
        verify(goalService, never()).createGoal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
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
    @DisplayName("when no document answers a question, it is routed like any request, not always to General")
    void documentsNotGroundedIsRoutedLikeWork() {
        Agent general = fallbackAgent();
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(research, support, general));
        when(generalEmployee.activeIn(List.of(research, support, general))).thenReturn(Optional.of(general));
        when(knowledge.search(eq(ORG), any(), any()))
                .thenReturn(Optional.of(new KnowledgeClient.SearchResult(List.of(), false)));

        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "What is our refund policy?", null, "Bearer token");

        ChatMessage routing = created.get(1);
        assertThat(routing.getKind()).isEqualTo("routing");
        // "refund" is Customer Support's word, so the question reaches Support, not General.
        assertThat(routing.getDetail()).containsEntry("mode", "rules");
        verify(goalService).createGoal(eq(ORG), any(), eq(true));
    }

    @Test
    @DisplayName("without task:create, an ungrounded search keeps the documents card even with General active")
    void documentsWithoutTaskCreateKeepsCard() {
        Agent general = fallbackAgent();
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(research, support, general));
        when(generalEmployee.activeIn(List.of(research, support, general))).thenReturn(Optional.of(general));
        when(knowledge.search(eq(ORG), any(), any()))
                .thenReturn(Optional.of(new KnowledgeClient.SearchResult(List.of(), false)));
        RequestContext.setActor(Actor.user(requesterId.toString(), ORG.toString(), "role", Set.of("chat:use"), 0L));

        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "What is our refund policy?", null, "Bearer token");

        ChatMessage documents = created.get(1);
        assertThat(documents.getKind()).isEqualTo("documents");
        assertThat(documents.getDetail()).containsEntry("grounded", false);
        verify(goalService, never()).createGoal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("a schedule phrase becomes a suggestion, naming the agent the same routing would choose")
    void schedulePhraseBecomesSuggestion() {
        when(schedulePreviewer.tryParse(any(), any(), any()))
                .thenReturn(Optional.of(
                        new ParsedSchedule("recurring", "0 0 9 * * MON-FRI", null, "every weekday at 9am")));
        when(modelPlanner.plan(eq(ORG), eq("summarise support tickets"), any()))
                .thenReturn(Optional.of(new ModelRouterPlanner.Plan(
                        List.of(new ModelRouterPlanner.PlannedStep(support.getId(), "summarise support tickets")),
                        "reason")));

        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "every weekday at 9am summarise support tickets", null, null);

        assertThat(created).hasSize(2);
        ChatMessage suggestion = created.get(1);
        assertThat(suggestion.getKind()).isEqualTo("schedule_suggestion");
        assertThat(suggestion.getDetail())
                .containsEntry("agentId", support.getId().toString());
        assertThat(suggestion.getDetail()).containsEntry("agentName", "Customer Support");
        assertThat(suggestion.getDetail()).containsEntry("instruction", "summarise support tickets");
        assertThat(suggestion.getDetail()).containsEntry("cron", "0 0 9 * * MON-FRI");
        verify(goalService, never()).createGoal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("a goal the engine refuses at validation time becomes an honest error message")
    void refusedGoalBecomesErrorMessage() {
        org.mockito.Mockito.doThrow(ApiException.validation("tasks", "a chat message may start at most 5 tasks"))
                .when(goalService)
                .validate(any());

        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "please handle this", List.of(research.getId(), support.getId()), null);

        assertThat(created).hasSize(2);
        ChatMessage error = created.get(1);
        assertThat(error.getKind()).isEqualTo("error");
        assertThat(error.getContent()).contains("at most 5 tasks");
    }

    @Test
    @DisplayName("validate passes but createGoal refuses in a race - the error still carries the problem text")
    void createGoalValidationRaceBecomesItsOwnSentence() {
        when(goalService.createGoal(eq(ORG), any(), eq(true)))
                .thenThrow(ApiException.validation("tasks", "an agent may not appear twice"));

        List<ChatMessage> created =
                coordinator.handleMessage(ORG, conversation.getId(), "@Research handle this", null, null);

        assertThat(created).hasSize(2);
        ChatMessage error = created.get(1);
        assertThat(error.getKind()).isEqualTo("error");
        assertThat(error.getContent()).containsIgnoringCase("an agent may not appear twice");
    }

    @Test
    @DisplayName("a person's message survives even an unexpected failure acting on the decision")
    void personMessageSurvivesWhenApplyFails() {
        when(goalService.createGoal(eq(ORG), any(), eq(true))).thenThrow(new IllegalStateException("boom"));

        List<ChatMessage> created =
                coordinator.handleMessage(ORG, conversation.getId(), "@Research handle this", null, null);

        assertThat(created).isNotEmpty();
        assertThat(created.getFirst().getKind()).isEqualTo("text");
        assertThat(created.getFirst().getContent()).isEqualTo("@Research handle this");
        assertThat(created).anySatisfy(m -> assertThat(m.getKind()).isEqualTo("error"));
    }

    @Test
    @DisplayName("earlier turns are prepended to the first task's instruction only")
    void threadContextPrependedToFirstTaskOnly() {
        ChatMessage earlierUser = ChatMessage.of(
                ORG, conversation.getId(), 0, "user", requesterId, null, "text", "context from before", Map.of(), null);
        when(messages.findByConversationIdAndPositionLessThanOrderByPositionDesc(
                        eq(conversation.getId()), anyInt(), any()))
                .thenReturn(new ArrayList<>(List.of(earlierUser)));

        coordinator.handleMessage(
                ORG, conversation.getId(), "@Research @Customer Support please handle this", null, null);

        ArgumentCaptor<GoalService.NewGoal> spec = ArgumentCaptor.forClass(GoalService.NewGoal.class);
        verify(goalService).createGoal(eq(ORG), spec.capture(), eq(true));
        assertThat(spec.getValue().tasks().get(0).instruction()).contains("context from before");
        assertThat(spec.getValue().tasks().get(1).instruction()).doesNotContain("context from before");
    }

    @Test
    @DisplayName("the planner is given the agent whose reply was last in the conversation as a hint")
    void plannerGetsLastAnswerAgentHint() {
        ChatMessage earlierAnswer = ChatMessage.of(
                ORG, conversation.getId(), 0, "agent", null, support.getId(), "answer", "Here you go.", Map.of(), null);
        when(messages.findByConversationIdAndPositionLessThanOrderByPositionDesc(
                        eq(conversation.getId()), anyInt(), any()))
                .thenReturn(new ArrayList<>(List.of(earlierAnswer)));

        coordinator.handleMessage(ORG, conversation.getId(), "now make it shorter", null, null);

        verify(modelPlanner)
                .plan(
                        eq(ORG),
                        any(),
                        any(),
                        argThat((ModelRouterPlanner.PlanHints hints) -> hints.lastAnswerAgent() == support));
    }

    // ---- Reroute ----------------------------------------------------------------------------

    private ChatMessage routingMessageFor(UUID goalId, Map<String, Object> extraDetail) {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("needsChoice", false);
        detail.putAll(extraDetail);
        return ChatMessage.of(
                ORG, conversation.getId(), 1, "coordinator", null, null, "routing", "needs a choice", detail, goalId);
    }

    @Test
    @DisplayName("rerouting cancels the active goal and starts a new one for the chosen agent")
    void rerouteCancelsAndStartsAgain() {
        UUID oldGoalId = UUID.randomUUID();
        Goal oldGoal = new Goal();
        oldGoal.setId(oldGoalId);
        oldGoal.setOrgId(ORG);
        oldGoal.setStatus("running");
        oldGoal.setRequestedBy(requesterId);
        when(goals.findByIdAndOrgId(oldGoalId, ORG)).thenReturn(Optional.of(oldGoal));

        ChatMessage userMessage = ChatMessage.of(
                ORG,
                conversation.getId(),
                0,
                "user",
                requesterId,
                null,
                "text",
                "please sort out this ticket",
                Map.of(),
                null);
        ChatMessage routingMessage = routingMessageFor(oldGoalId, Map.of());
        when(messages.findByIdAndConversationId(routingMessage.getId(), conversation.getId()))
                .thenReturn(Optional.of(routingMessage));
        when(messages.findByConversationIdOrderByPosition(conversation.getId()))
                .thenReturn(new ArrayList<>(List.of(userMessage, routingMessage)));

        List<ChatMessage> created =
                coordinator.reroute(ORG, conversation.getId(), routingMessage.getId(), support.getId());

        verify(goalService).cancel(ORG, oldGoalId, "Sent to Customer Support instead.");
        assertThat(created).hasSize(2);
        ChatMessage newRouting = created.get(0);
        assertThat(newRouting.getDetail()).containsEntry("mode", "manual");
        assertThat(newRouting.getDetail().get("reason")).isEqualTo("You chose Customer Support.");
        ArgumentCaptor<GoalService.NewGoal> spec = ArgumentCaptor.forClass(GoalService.NewGoal.class);
        verify(goalService).createGoal(eq(ORG), spec.capture(), eq(true));
        assertThat(spec.getValue().tasks().getFirst().instruction()).isEqualTo("please sort out this ticket");
        assertThat(spec.getValue().tasks().getFirst().agentId()).isEqualTo(support.getId());
    }

    @Test
    @DisplayName("reroute prefers the routing message's own requestText over the progress line before it")
    void rerouteUsesRequestTextNotProgress() {
        ChatMessage routingMessage = routingMessageFor(null, Map.of("requestText", "the real original request"));
        when(messages.findByIdAndConversationId(routingMessage.getId(), conversation.getId()))
                .thenReturn(Optional.of(routingMessage));

        List<ChatMessage> created =
                coordinator.reroute(ORG, conversation.getId(), routingMessage.getId(), support.getId());

        ArgumentCaptor<GoalService.NewGoal> spec = ArgumentCaptor.forClass(GoalService.NewGoal.class);
        verify(goalService).createGoal(eq(ORG), spec.capture(), eq(true));
        assertThat(spec.getValue().tasks().getFirst().instruction()).isEqualTo("the real original request");
        assertThat(created).hasSize(2);
    }

    @Test
    @DisplayName("rerouting a needs-choice card that was already settled is a conflict")
    void rerouteOfSettledChoiceIsConflict() {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("needsChoice", true);
        ChatMessage routingMessage = ChatMessage.of(
                ORG, conversation.getId(), 1, "coordinator", null, null, "routing", "choose one", detail, null);
        when(messages.findByIdAndConversationId(routingMessage.getId(), conversation.getId()))
                .thenReturn(Optional.of(routingMessage));
        ChatMessage alreadyChosen = ChatMessage.of(
                ORG,
                conversation.getId(),
                2,
                "coordinator",
                null,
                null,
                "routing",
                "You chose Research.",
                Map.of("rerouteOf", routingMessage.getId().toString()),
                null);
        when(messages.findByConversationIdAndPositionGreaterThanOrderByPosition(conversation.getId(), 1))
                .thenReturn(List.of(alreadyChosen));

        assertThatThrownBy(
                        () -> coordinator.reroute(ORG, conversation.getId(), routingMessage.getId(), support.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        verify(goalService, never()).createGoal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("a reroute race that only shows up after the lock is also a conflict")
    void rerouteRecheckAfterLockIsConflict() {
        ChatMessage routingMessage = routingMessageFor(null, Map.of("requestText", "the real original request"));
        when(messages.findByIdAndConversationId(routingMessage.getId(), conversation.getId()))
                .thenReturn(Optional.of(routingMessage));
        ChatMessage raceWinner = ChatMessage.of(
                ORG,
                conversation.getId(),
                2,
                "coordinator",
                null,
                null,
                "routing",
                "You chose Research.",
                Map.of("rerouteOf", routingMessage.getId().toString()),
                null);
        when(messages.findByConversationIdAndPositionGreaterThanOrderByPosition(conversation.getId(), 1))
                .thenReturn(List.of(), List.of(raceWinner));

        assertThatThrownBy(
                        () -> coordinator.reroute(ORG, conversation.getId(), routingMessage.getId(), support.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("rerouting someone else's active work without task:cancel is refused")
    void rerouteOfSomeoneElsesActiveGoalIs403() {
        UUID oldGoalId = UUID.randomUUID();
        Goal oldGoal = new Goal();
        oldGoal.setId(oldGoalId);
        oldGoal.setOrgId(ORG);
        oldGoal.setStatus("running");
        oldGoal.setRequestedBy(UUID.randomUUID());
        when(goals.findByIdAndOrgId(oldGoalId, ORG)).thenReturn(Optional.of(oldGoal));
        ChatMessage routingMessage = routingMessageFor(oldGoalId, Map.of());
        when(messages.findByIdAndConversationId(routingMessage.getId(), conversation.getId()))
                .thenReturn(Optional.of(routingMessage));

        assertThatThrownBy(
                        () -> coordinator.reroute(ORG, conversation.getId(), routingMessage.getId(), support.getId()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        verify(goalService, never()).cancel(any(), any(), any());
    }

    @Test
    @DisplayName("a task:cancel holder may reroute someone else's active work")
    void rerouteOfSomeoneElsesActiveGoalByCancelHolderIsAllowed() {
        RequestContext.setActor(Actor.user(
                requesterId.toString(), ORG.toString(), "role", Set.of("chat:use", "task:create", "task:cancel"), 0L));
        UUID oldGoalId = UUID.randomUUID();
        Goal oldGoal = new Goal();
        oldGoal.setId(oldGoalId);
        oldGoal.setOrgId(ORG);
        oldGoal.setStatus("running");
        oldGoal.setRequestedBy(UUID.randomUUID());
        when(goals.findByIdAndOrgId(oldGoalId, ORG)).thenReturn(Optional.of(oldGoal));
        ChatMessage routingMessage = routingMessageFor(oldGoalId, Map.of());
        when(messages.findByIdAndConversationId(routingMessage.getId(), conversation.getId()))
                .thenReturn(Optional.of(routingMessage));

        List<ChatMessage> created =
                coordinator.reroute(ORG, conversation.getId(), routingMessage.getId(), support.getId());

        assertThat(created).hasSize(2);
        verify(goalService).cancel(eq(ORG), eq(oldGoalId), any());
    }

    // ---- Stop and retry -----------------------------------------------------------------------

    @Test
    @DisplayName("stopping a goal requires being its requester or holding task:cancel")
    void stopRequiresRequesterOrCancel() {
        UUID goalId = UUID.randomUUID();
        Goal goal = new Goal();
        goal.setId(goalId);
        goal.setOrgId(ORG);
        goal.setStatus("running");
        goal.setConversationId(conversation.getId());
        goal.setRequestedBy(UUID.randomUUID());
        when(goals.findByIdAndOrgId(goalId, ORG)).thenReturn(Optional.of(goal));

        assertThatThrownBy(() -> coordinator.stopGoal(ORG, conversation.getId(), goalId))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED));
        verify(goalService, never()).cancel(any(), any(), any());

        goal.setRequestedBy(requesterId);
        CoordinatorService.GoalActionResult result = coordinator.stopGoal(ORG, conversation.getId(), goalId);
        assertThat(result.status()).isEqualTo("cancelled");
        verify(goalService).cancel(ORG, goalId, "Stopped from the chat.");
    }

    @Test
    @DisplayName("retry passes the acting person to the goal service and returns where it resumed from")
    void retryPassesTheActorToGoalService() {
        UUID goalId = UUID.randomUUID();
        Goal goal = new Goal();
        goal.setId(goalId);
        goal.setOrgId(ORG);
        goal.setConversationId(conversation.getId());
        when(goals.findByIdAndOrgId(goalId, ORG)).thenReturn(Optional.of(goal));
        Goal resumed = new Goal();
        resumed.setId(goalId);
        resumed.setStatus("running");
        Task fromTask = new Task();
        fromTask.setId(UUID.randomUUID());
        when(goalService.retry(eq(ORG), eq(goalId), any(Actor.class)))
                .thenReturn(new GoalService.RetryResult(resumed, fromTask));

        CoordinatorService.GoalActionResult result = coordinator.retryGoal(ORG, conversation.getId(), goalId);

        assertThat(result.status()).isEqualTo("running");
        assertThat(result.fromTaskId()).isEqualTo(fromTask.getId());
        ArgumentCaptor<Actor> actorCaptor = ArgumentCaptor.forClass(Actor.class);
        verify(goalService).retry(eq(ORG), eq(goalId), actorCaptor.capture());
        assertThat(actorCaptor.getValue().id()).isEqualTo(requesterId.toString());
    }

    // ---- Answer from documents ------------------------------------------------------------------

    private ChatMessage documentsMessageWithPassages() {
        Map<String, Object> passage = Map.of("documentTitle", "Handbook", "content", "Leave accrues monthly.");
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("query", "How much leave do I get?");
        detail.put("grounded", true);
        detail.put("passages", List.of(passage));
        return ChatMessage.of(
                ORG,
                conversation.getId(),
                3,
                "coordinator",
                null,
                null,
                "documents",
                "Found 1 passage(s) that may help.",
                detail,
                null);
    }

    @Test
    @DisplayName("answer from documents builds a numbered prompt from the card's own passages")
    void answerFromDocumentsBuildsNumberedPassages() {
        Agent general = fallbackAgent();
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(research, support, general));
        when(generalEmployee.activeIn(List.of(research, support, general))).thenReturn(Optional.of(general));
        ChatMessage documents = documentsMessageWithPassages();
        when(messages.findByIdAndConversationId(documents.getId(), conversation.getId()))
                .thenReturn(Optional.of(documents));

        List<ChatMessage> created = coordinator.answerFromDocuments(ORG, conversation.getId(), documents.getId(), null);

        assertThat(created).hasSize(2);
        ArgumentCaptor<GoalService.NewGoal> spec = ArgumentCaptor.forClass(GoalService.NewGoal.class);
        verify(goalService).createGoal(eq(ORG), spec.capture(), eq(true));
        String instruction = spec.getValue().tasks().getFirst().instruction();
        assertThat(instruction).contains("[1] Handbook");
        assertThat(instruction).contains("Leave accrues monthly.");
        assertThat(spec.getValue().tasks().getFirst().agentId()).isEqualTo(general.getId());
    }

    @Test
    @DisplayName("the target message is loaded through its own conversation, never by id alone")
    void answerFromDocumentsLoadsMessageThroughItsConversation() {
        Agent general = fallbackAgent();
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(general));
        when(generalEmployee.activeIn(any())).thenReturn(Optional.of(general));
        ChatMessage documents = documentsMessageWithPassages();
        when(messages.findByIdAndConversationId(documents.getId(), conversation.getId()))
                .thenReturn(Optional.of(documents));

        coordinator.answerFromDocuments(ORG, conversation.getId(), documents.getId(), null);

        verify(messages).findByIdAndConversationId(documents.getId(), conversation.getId());
        verify(messages, never()).findById(any());
    }

    @Test
    @DisplayName("a second click to answer from the same documents card is a conflict")
    void answerFromDocumentsTwiceIsConflict() {
        Agent general = fallbackAgent();
        when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(general));
        when(generalEmployee.activeIn(any())).thenReturn(Optional.of(general));
        ChatMessage documents = documentsMessageWithPassages();
        when(messages.findByIdAndConversationId(documents.getId(), conversation.getId()))
                .thenReturn(Optional.of(documents));
        ChatMessage alreadyAnswered = ChatMessage.of(
                ORG,
                conversation.getId(),
                4,
                "coordinator",
                null,
                null,
                "routing",
                "You asked General Employee to answer from these passages.",
                Map.of("fromDocumentsMessageId", documents.getId().toString()),
                null);
        when(messages.findByConversationIdAndPositionGreaterThanOrderByPosition(conversation.getId(), 3))
                .thenReturn(List.of(alreadyAnswered));

        assertThatThrownBy(() -> coordinator.answerFromDocuments(ORG, conversation.getId(), documents.getId(), null))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        verify(goalService, never()).createGoal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("a short reply that points back at earlier work reads as a follow-up; a new question does not")
    void followUpDetection() {
        assertThat(CoordinatorService.looksLikeFollowUp("make it shorter")).isTrue();
        assertThat(CoordinatorService.looksLikeFollowUp("now write another version for managers"))
                .isTrue();
        assertThat(CoordinatorService.looksLikeFollowUp("What is java?")).isFalse();
        assertThat(CoordinatorService.looksLikeFollowUp("")).isFalse();
    }

    @Test
    @DisplayName("requests that ask to be told something search the documents; plain tasks do not")
    void informationalRequestsAreRecognised() {
        assertThat(CoordinatorService.looksInformational("Tell me about our SIH project")).isTrue();
        assertThat(CoordinatorService.looksInformational("It is there in the knowledge base")).isTrue();
        assertThat(CoordinatorService.looksInformational("Draft a welcome email for a new starter")).isFalse();
    }

    @Test
    @DisplayName("a follow-up that names no subject is searched together with the earlier request")
    void followUpSearchUsesTheEarlierRequest() {
        ChatMessage earlier = ChatMessage.of(
                ORG, conversation.getId(), 0, "user", null, null, "text", "Tell me about our SIH project", null, null);
        assertThat(CoordinatorService.searchQueryFor("It is there in the knowledge base", List.of(earlier)))
                .isEqualTo("Tell me about our SIH project It is there in the knowledge base");
        assertThat(CoordinatorService.searchQueryFor("What is our leave policy?", List.of(earlier)))
                .isEqualTo("What is our leave policy?");
    }

    @Test
    @DisplayName("passages go ahead of the request, numbered, and end with the request marker")
    void knowledgeBlockShape() {
        String block = CoordinatorService.withKnowledge(
                "", List.of(Map.of("documentTitle", "SIH deck", "content", "Quantified cyber risk.")));
        assertThat(block).contains("[1] SIH deck\nQuantified cyber risk.").endsWith("Request:\n");
    }
}
