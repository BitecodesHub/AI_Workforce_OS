// @find: tests for coordinator attachments, chat, only afile, question with file is work, nothing to send, CoordinatorAttachmentsTest, CoordinatorAttachments
// @what: Tests for CoordinatorAttachments in the orchestrator chat package (3 test methods).
// @flow: Exercises CoordinatorAttachments
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import os.aiworkforce.orchestrator.domain.Agent;
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
 * A message with files: the files are checked and tied to it, the message records them for its
 * card, the planner and the agent read their names, and the work it starts is given them.
 */
class CoordinatorAttachmentsTest {

    private static final UUID ORG = UUID.randomUUID();

    private Conversations conversations;
    private ChatMessages messages;
    private Agents agents;
    private GoalService goalService;
    private ModelRouterPlanner planner;
    private AttachmentService attachments;
    private CoordinatorService coordinator;
    private Conversation conversation;
    private Agent research;
    private Goal goal;
    private ChatAttachments.Row file;

    @BeforeEach
    void setUp() {
        conversations = mock(Conversations.class);
        messages = mock(ChatMessages.class);
        agents = mock(Agents.class);
        goalService = mock(GoalService.class);
        planner = mock(ModelRouterPlanner.class);
        attachments = mock(AttachmentService.class);
        KnowledgeClient knowledge = mock(KnowledgeClient.class);
        WorkspaceZoneLookup zones = mock(WorkspaceZoneLookup.class);
        SchedulePreviewer previewer = mock(SchedulePreviewer.class);
        GeneralEmployee general = mock(GeneralEmployee.class);
        ToolGrants grants = mock(ToolGrants.class);
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
        ReflectionTestUtils.setField(coordinator, "attachmentService", attachments);

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

        research = new Agent();
        research.setId(UUID.randomUUID());
        research.setKey("research");
        research.setName("Research");
        research.setCategory("research");
        research.setStatus("active");
        lenient().when(agents.findByOrgIdOrderByName(ORG)).thenReturn(List.of(research));
        lenient().when(agents.findByIdAndOrgId(research.getId(), ORG)).thenReturn(Optional.of(research));

        RequestContext.setActor(
                Actor.user(UUID.randomUUID().toString(), ORG.toString(), "role", Set.of("chat:use", "task:create"), 0L));

        goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setOrgId(ORG);
        lenient().when(goalService.createGoal(eq(ORG), any(), eq(true))).thenReturn(goal);

        file = new ChatAttachments.Row(
                UUID.randomUUID(), ORG, null, null, null, "me", "q3-report.pdf", "application/pdf", "pdf", 2048, "h",
                "ready", 3, null, null, null, Instant.now(), null);
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    // @find: test only afile, coordinator attachments
    @Test
    @DisplayName("a message with only a file is recorded with the file, routed as work naming it, and its goal is given the file")
    void onlyAFile() {
        when(attachments.checkForSend(eq(ORG), any(), eq(conversation.getId()), eq(List.of(file.id()))))
                .thenReturn(List.of(file));

        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "", List.of(research.getId()), List.of(file.id()), null);

        ChatMessage sent = created.getFirst();
        assertThat(sent.getContent()).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) sent.getDetail().get("attachments");
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card).containsEntry("name", "q3-report.pdf").containsEntry("kind", "pdf").containsEntry("pageCount", 3);
        });
        verify(attachments).bind(ORG, conversation.getId(), sent.getId(), List.of(file));
        assertThat(conversation.getTitle()).isEqualTo("q3-report.pdf");

        ArgumentCaptor<GoalService.NewGoal> spec = ArgumentCaptor.forClass(GoalService.NewGoal.class);
        verify(goalService).createGoal(eq(ORG), spec.capture(), eq(true));
        assertThat(spec.getValue().tasks().getFirst().instruction())
                .contains("Look at the attached file and tell me what it contains.")
                .contains("Attached: q3-report.pdf (PDF, 3 pages).");
        verify(attachments).linkGoal(ORG, sent.getId(), goal.getId());
    }

    // @find: test question with file is work, coordinator attachments
    @Test
    @DisplayName("a question with a file is work for an agent, never a search of the workspace's documents, and the planner sees the name")
    void questionWithFileIsWork() {
        when(attachments.checkForSend(eq(ORG), any(), eq(conversation.getId()), any())).thenReturn(List.of(file));

        List<ChatMessage> created = coordinator.handleMessage(
                ORG, conversation.getId(), "What does this say about revenue?", null, List.of(file.id()), null);

        assertThat(created).extracting(ChatMessage::getKind).doesNotContain("documents", "schedule_suggestion");
        ArgumentCaptor<String> planned = ArgumentCaptor.forClass(String.class);
        verify(planner).plan(eq(ORG), planned.capture(), any(), any());
        assertThat(planned.getValue()).contains("What does this say about revenue?").contains("q3-report.pdf");
    }

    // @find: test nothing to send, coordinator attachments
    @Test
    @DisplayName("a message with neither text nor files is refused before anything is written")
    void nothingToSend() {
        assertThatThrownBy(() -> coordinator.handleMessage(ORG, conversation.getId(), " ", null, List.of(), null))
                .isInstanceOf(ApiException.class);
        verify(messages, never()).save(any());
    }
}
