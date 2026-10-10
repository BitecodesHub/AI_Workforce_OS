// @find: tests for conversation queries, chat, pinned rows come first on page zero only, archived rows excluded from all and listed alone in archived, has more detection, needs answer activity, managers see others questions as waiting not needs answer, needs approval only for approvers, working and idle activity, needs you ignores scope and archive, ConversationQueriesTest, ConversationQueries
// @what: Tests for ConversationQueries in the orchestrator chat package (11 test methods).
// @flow: Exercises ConversationQueries
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.domain.ConversationMark;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.ConversationMarks;
import os.aiworkforce.orchestrator.repository.Conversations;
import os.aiworkforce.orchestrator.repository.Goals;
import os.aiworkforce.orchestrator.repository.Runs;
import os.aiworkforce.orchestrator.repository.Tasks;
import os.aiworkforce.orchestrator.service.QuestionService;
import os.aiworkforce.platform.context.Actor;

class ConversationQueriesTest {

    private static final UUID ORG = UUID.randomUUID();

    private Conversations conversations;
    private ConversationMarks marks;
    private ChatMessages messages;
    private Goals goals;
    private Tasks tasks;
    private Runs runs;
    private QuestionService questions;
    private ConversationQueries queries;

    private UUID me;
    private Actor actor;

    @BeforeEach
    void setUp() {
        conversations = mock(Conversations.class);
        marks = mock(ConversationMarks.class);
        messages = mock(ChatMessages.class);
        goals = mock(Goals.class);
        tasks = mock(Tasks.class);
        runs = mock(Runs.class);
        questions = mock(QuestionService.class);
        queries = new ConversationQueries(conversations, marks, messages, goals, tasks, runs, questions);

        me = UUID.randomUUID();
        actor = Actor.user(me.toString(), ORG.toString(), "role", Set.of("chat:use"), 0L);

        lenient().when(marks.findByOrgIdAndUserId(eq(ORG), eq(me))).thenReturn(List.of());
        lenient().when(questions.conversationsNeedingAnswerFrom(any(), any())).thenReturn(List.of());
        lenient().when(tasks.conversationsWaitingForApproval(any())).thenReturn(List.of());
        lenient().when(questions.pendingByConversation(any(), anyCollection())).thenReturn(List.of());
        lenient().when(goals.activeByConversation(any(), anyCollection())).thenReturn(List.of());
        lenient()
                .when(tasks.waitingApprovalByConversation(any(), anyCollection()))
                .thenReturn(List.of());
        lenient()
                .when(conversations.searchExcluding(any(), anyCollection(), anyBoolean(), any(), anyBoolean(), anyString(), any()))
                .thenReturn(List.of());
        lenient()
                .when(conversations.searchAmong(any(), anyCollection(), anyBoolean(), any(), anyBoolean(), anyString()))
                .thenReturn(List.of());
    }

    private static Conversation conversationRow() {
        Conversation c = new Conversation();
        c.setId(UUID.randomUUID());
        c.setOrgId(ORG);
        c.setTitle("A conversation");
        return c;
    }

    private static ConversationMark markFor(UUID conversationId, UUID userId, boolean pinned, boolean archived) {
        ConversationMark mark = new ConversationMark();
        mark.setId(UUID.randomUUID());
        mark.setOrgId(ORG);
        mark.setConversationId(conversationId);
        mark.setUserId(userId);
        mark.setPinned(pinned);
        mark.setArchived(archived);
        return mark;
    }

    // @find: test pinned rows come first on page zero only, conversation queries
    @Test
    @DisplayName("a pinned conversation shows in the pinned group on page 0 only")
    void pinnedRowsComeFirstOnPageZeroOnly() {
        Conversation pinned = conversationRow();
        when(marks.findByOrgIdAndUserId(ORG, me)).thenReturn(List.of(markFor(pinned.getId(), me, true, false)));
        when(conversations.searchAmong(eq(ORG), eq(Set.of(pinned.getId())), anyBoolean(), any(), anyBoolean(), anyString()))
                .thenReturn(List.of(pinned));

        ConversationQueries.ConversationPage firstPage = queries.list(ORG, actor, "", "all", 0, 20);
        assertThat(firstPage.pinned())
                .extracting(ConversationQueries.ConversationView::id)
                .containsExactly(pinned.getId());

        ConversationQueries.ConversationPage secondPage = queries.list(ORG, actor, "", "all", 1, 20);
        assertThat(secondPage.pinned()).isEmpty();
    }

    // @find: test archived rows excluded from all and listed alone in archived, conversation queries
    @Test
    @DisplayName("an archived conversation is excluded from 'all' and listed alone under 'archived'")
    void archivedRowsExcludedFromAllAndListedAloneInArchived() {
        Conversation archived = conversationRow();
        when(marks.findByOrgIdAndUserId(ORG, me)).thenReturn(List.of(markFor(archived.getId(), me, false, true)));
        when(conversations.searchAmong(eq(ORG), eq(Set.of(archived.getId())), eq(false), any(), anyBoolean(), anyString()))
                .thenReturn(List.of(archived));

        ArgumentCaptor<Collection<UUID>> excludedCaptor = ArgumentCaptor.forClass(Collection.class);
        queries.list(ORG, actor, "", "all", 0, 20);
        verify(conversations)
                .searchExcluding(eq(ORG), excludedCaptor.capture(), anyBoolean(), any(), anyBoolean(), anyString(), any());
        assertThat(excludedCaptor.getValue()).contains(archived.getId());

        ConversationQueries.ConversationPage archivedPage = queries.list(ORG, actor, "", "archived", 0, 20);
        assertThat(archivedPage.conversations())
                .extracting(ConversationQueries.ConversationView::id)
                .containsExactly(archived.getId());
        assertThat(archivedPage.pinned()).isEmpty();
        assertThat(archivedPage.needsYou()).isEmpty();
    }

    // @find: test has more detection, conversation queries
    @Test
    @DisplayName("hasMore is true only when one row more than the page size came back")
    void hasMoreDetection() {
        List<Conversation> rows = List.of(conversationRow(), conversationRow(), conversationRow());
        when(conversations.searchExcluding(eq(ORG), anyCollection(), anyBoolean(), any(), anyBoolean(), anyString(), any()))
                .thenReturn(rows);

        ConversationQueries.ConversationPage page = queries.list(ORG, actor, "", "all", 0, 2);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.conversations()).hasSize(2);
    }

    // @find: test needs answer activity, conversation queries
    @Test
    @DisplayName("activity: a pending question the caller asked for is 'needs_answer'")
    void needsAnswerActivity() {
        Conversation c = conversationRow();
        stubMainList(c);
        when(questions.pendingByConversation(eq(ORG), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[] {c.getId(), me}));

        assertActivityOf(c.getId(), "needs_answer");
    }

    // @find: test managers see others questions as waiting not needs answer, conversation queries
    @Test
    @DisplayName("managers see someone else's open question as waiting, not as their own to answer")
    void managersSeeOthersQuestionsAsWaitingNotNeedsAnswer() {
        Actor manager = Actor.user(me.toString(), ORG.toString(), "manager", Set.of("chat:use", "task:cancel"), 0L);
        Conversation c = conversationRow();
        stubMainList(c);
        when(questions.pendingByConversation(eq(ORG), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[] {c.getId(), UUID.randomUUID()}));

        ConversationQueries.ConversationPage page = queries.list(ORG, manager, "", "all", 0, 20);
        assertThat(activityOf(page, c.getId())).isEqualTo("waiting_answer");
    }

    // @find: test needs approval only for approvers, conversation queries
    @Test
    @DisplayName("a task waiting for approval is 'needs_approval' only for someone who can decide it")
    void needsApprovalOnlyForApprovers() {
        Conversation c = conversationRow();
        stubMainList(c);
        when(tasks.waitingApprovalByConversation(eq(ORG), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[] {c.getId(), 1L}));

        assertActivityOf(c.getId(), "waiting_approval");

        Actor approver = Actor.user(me.toString(), ORG.toString(), "role", Set.of("chat:use", "approval:decide"), 0L);
        ConversationQueries.ConversationPage page = queries.list(ORG, approver, "", "all", 0, 20);
        assertThat(activityOf(page, c.getId())).isEqualTo("needs_approval");
    }

    // @find: test working and idle activity, conversation queries
    @Test
    @DisplayName("an active goal with nothing waiting is 'working'; otherwise a conversation is 'idle'")
    void workingAndIdleActivity() {
        Conversation working = conversationRow();
        Conversation idle = conversationRow();
        when(conversations.searchExcluding(eq(ORG), anyCollection(), anyBoolean(), any(), anyBoolean(), anyString(), any()))
                .thenReturn(List.of(working, idle));
        when(goals.activeByConversation(eq(ORG), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[] {working.getId(), 1L}));

        ConversationQueries.ConversationPage page = queries.list(ORG, actor, "", "all", 0, 20);
        assertThat(activityOf(page, working.getId())).isEqualTo("working");
        assertThat(activityOf(page, idle.getId())).isEqualTo("idle");
    }

    // @find: test needs you ignores scope and archive, conversation queries
    @Test
    @DisplayName("needs-you is not limited by scope or by the caller's own archive")
    void needsYouIgnoresScopeAndArchive() {
        Conversation needsYou = conversationRow();
        when(marks.findByOrgIdAndUserId(ORG, me)).thenReturn(List.of(markFor(needsYou.getId(), me, false, true)));
        when(questions.conversationsNeedingAnswerFrom(ORG, me)).thenReturn(List.of(needsYou.getId()));
        when(conversations.searchAmong(eq(ORG), eq(Set.of(needsYou.getId())), eq(false), any(), anyBoolean(), anyString()))
                .thenReturn(List.of(needsYou));

        ConversationQueries.ConversationPage page = queries.list(ORG, actor, "", "mine", 0, 20);

        assertThat(page.needsYou())
                .extracting(ConversationQueries.ConversationView::id)
                .containsExactly(needsYou.getId());
    }

    // @find: test unread needs amarker, conversation queries
    @Test
    @DisplayName("unread is false with no read marker, and true when the marker sits behind the newest message")
    void unreadNeedsAMarker() {
        Conversation c = conversationRow();
        c.recordMessage("hello");
        c.recordMessage("world");
        stubMainList(c);

        ConversationQueries.ConversationPage noMarker = queries.list(ORG, actor, "", "all", 0, 20);
        assertThat(noMarker.conversations().getFirst().unread()).isFalse();

        ConversationMark readMark = markFor(c.getId(), me, false, false);
        readMark.setLastReadPosition(0);
        when(marks.findByOrgIdAndUserId(ORG, me)).thenReturn(List.of(readMark));

        ConversationQueries.ConversationPage behind = queries.list(ORG, actor, "", "all", 0, 20);
        assertThat(behind.conversations().getFirst().unread()).isTrue();
    }

    // @find: test snippet shows the matching message, conversation queries
    @Test
    @DisplayName("the search snippet shows the message that actually matched")
    void snippetShowsTheMatchingMessage() {
        Conversation c = conversationRow();
        stubMainList(c);
        UUID messageId = UUID.randomUUID();
        when(messages.matchesIn(anyCollection(), anyString(), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[] {c.getId(), messageId, "the refund policy covers this exactly"}));

        ConversationQueries.ConversationPage page = queries.list(ORG, actor, "refund", "all", 0, 20);

        ConversationQueries.SearchMatch match = page.conversations().getFirst().match();
        assertThat(match).isNotNull();
        assertThat(match.snippet()).contains("refund policy");
    }

    // @find: test like escaping, conversation queries
    @Test
    @DisplayName("a search term with LIKE metacharacters is escaped and matched literally")
    void likeEscaping() {
        Conversation c = conversationRow();
        stubMainList(c);

        queries.list(ORG, actor, "50%_off", "all", 0, 20);

        ArgumentCaptor<String> patternCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversations)
                .searchExcluding(eq(ORG), anyCollection(), anyBoolean(), any(), anyBoolean(), patternCaptor.capture(), any());
        assertThat(patternCaptor.getValue()).isEqualTo("%50!%!_off%");
    }

    private void stubMainList(Conversation... rows) {
        when(conversations.searchExcluding(eq(ORG), anyCollection(), anyBoolean(), any(), anyBoolean(), anyString(), any()))
                .thenReturn(List.of(rows));
    }

    private void assertActivityOf(UUID conversationId, String expected) {
        ConversationQueries.ConversationPage page = queries.list(ORG, actor, "", "all", 0, 20);
        assertThat(activityOf(page, conversationId)).isEqualTo(expected);
    }

    private static String activityOf(ConversationQueries.ConversationPage page, UUID conversationId) {
        return page.conversations().stream()
                .filter(v -> v.id().equals(conversationId))
                .findFirst()
                .orElseThrow()
                .activity();
    }
}
