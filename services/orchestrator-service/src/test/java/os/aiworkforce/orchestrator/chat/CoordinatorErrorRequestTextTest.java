package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import os.aiworkforce.orchestrator.domain.Agent;
import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
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
 * Every error the coordinator records carries the person's own request as {@code requestText}, so
 * the chat's "Try again" puts that request back in the message box rather than the error sentence.
 */
class CoordinatorErrorRequestTextTest {

    private static final UUID ORG = UUID.randomUUID();

    private Agents agents;
    private GoalService goalService;
    private KnowledgeClient knowledge;
    private CoordinatorService coordinator;
    private Conversation conversation;
    private Agent research;

    @BeforeEach
    void setUp() {
        Conversations conversations = mock(Conversations.class);
        ChatMessages messages = mock(ChatMessages.class);
        agents = mock(Agents.class);
        AgentVersions versions = mock(AgentVersions.class);
        ToolGrants grants = mock(ToolGrants.class);
        Goals goals = mock(Goals.class);
        goalService = mock(GoalService.class);
        ModelRouterPlanner modelPlanner = mock(ModelRouterPlanner.class);
        knowledge = mock(KnowledgeClient.class);
        WorkspaceZoneLookup zones = mock(WorkspaceZoneLookup.class);
        SchedulePreviewer schedulePreviewer = mock(SchedulePreviewer.class);
        GeneralEmployee generalEmployee = mock(GeneralEmployee.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ChatAppender appender = new ChatAppender(conversations, messages, mock(EntityManager.class));

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
        lenient().when(messages.findEarlierTurns(any(), anyInt(), any())).thenReturn(List.of());
        lenient()
                .when(messages.findByConversationIdAndPositionGreaterThanOrderByPosition(any(), anyInt()))
                .thenReturn(List.of());

        research = new Agent();
        research.setId(UUID.randomUUID());
        research.setKey("research");
        research.setName("Research");
        research.setCategory("research");
        research.setStatus("active");
        lenient().when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(research));
        lenient().when(agents.findByIdAndOrgId(research.getId(), ORG)).thenReturn(Optional.of(research));

        RequestContext.setActor(Actor.user(
                UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("chat:use", "task:create"), 0L));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private ChatMessage onlyError(List<ChatMessage> created) {
        List<ChatMessage> errors =
                created.stream().filter(m -> "error".equals(m.getKind())).toList();
        assertThat(errors).hasSize(1);
        return errors.getFirst();
    }

    private static void assertCarriesRequest(ChatMessage error, String request) {
        assertThat(error.getDetail()).containsEntry("requestText", request);
        assertThat(error.getDetail()).containsKey("reason");
        assertThat(error.getContent()).isNotEqualTo(request);
    }

    @Test
    @DisplayName("an error while deciding keeps the person's request on the error message")
    void decideFailureKeepsRequest() {
        when(agents.findByOrgIdOrderByName(ORG)).thenThrow(new IllegalStateException("database away"));
        String request = "@Research compare the three supplier quotes";

        ChatMessage error = onlyError(coordinator.handleMessage(ORG, conversation.getId(), request, null, null));

        assertThat(error.getContent()).startsWith("The coordinator could not decide who takes this");
        assertCarriesRequest(error, request);
    }

    @Test
    @DisplayName("a goal refused at validation keeps the person's request on the error message")
    void refusedGoalKeepsRequest() {
        doThrow(ApiException.validation("tasks", "a chat message may start at most 5 tasks"))
                .when(goalService)
                .validate(any());
        String request = "@Research handle this please";

        ChatMessage error = onlyError(coordinator.handleMessage(ORG, conversation.getId(), request, null, null));

        assertThat(error.getContent()).contains("at most 5 tasks");
        assertCarriesRequest(error, request);
    }

    @Test
    @DisplayName("an unreachable document search keeps the person's question on the error message")
    void documentsUnavailableKeepsRequest() {
        when(knowledge.search(eq(ORG), any(), any())).thenReturn(Optional.empty());
        String request = "What is our refund policy?";

        ChatMessage error =
                onlyError(coordinator.handleMessage(ORG, conversation.getId(), request, null, "Bearer token"));

        assertThat(error.getContent()).isEqualTo(CoordinatorService.DOCUMENTS_UNAVAILABLE);
        assertCarriesRequest(error, request);
    }

    @Test
    @DisplayName("a failure while acting on the decision keeps the person's request on the error message")
    void applyFailureKeepsRequest() {
        when(goalService.createGoal(eq(ORG), any(), eq(true))).thenThrow(new IllegalStateException("boom"));
        String request = "@Research handle this";

        List<ChatMessage> created = coordinator.handleMessage(ORG, conversation.getId(), request, null, null);

        assertThat(created.getFirst().getContent()).isEqualTo(request);
        ChatMessage error = onlyError(created);
        assertThat(error.getContent()).startsWith("Something went wrong starting this work");
        assertCarriesRequest(error, request);
    }
}
