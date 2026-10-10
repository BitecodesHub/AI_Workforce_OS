// @find: tests for chat appender, chat, positions increase with no gaps, preview cut at word boundary, record message called once per append, lock flushes then refreshes, lock returns empty for another org, ChatAppenderTest, ChatAppender
// @what: Tests for ChatAppender in the orchestrator chat package (5 test methods).
// @flow: Exercises ChatAppender
package os.aiworkforce.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import os.aiworkforce.orchestrator.domain.ChatMessage;
import os.aiworkforce.orchestrator.domain.Conversation;
import os.aiworkforce.orchestrator.repository.ChatMessages;
import os.aiworkforce.orchestrator.repository.Conversations;

class ChatAppenderTest {

    private static final UUID ORG = UUID.randomUUID();

    private Conversations conversations;
    private ChatMessages messages;
    private EntityManager entityManager;
    private ChatAppender appender;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        conversations = mock(Conversations.class);
        messages = mock(ChatMessages.class);
        entityManager = mock(EntityManager.class);
        appender = new ChatAppender(conversations, messages, entityManager);

        conversation = new Conversation();
        conversation.setId(UUID.randomUUID());
        conversation.setOrgId(ORG);
        when(conversations.findByIdAndOrgId(conversation.getId(), ORG)).thenReturn(Optional.of(conversation));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // @find: test positions increase with no gaps, chat appender
    @Test
    @DisplayName("positions increase with no gaps across several appends")
    void positionsIncreaseWithNoGaps() {
        ChatMessage first = appender.append(conversation, "user", null, null, "text", "hello", Map.of(), null);
        ChatMessage second =
                appender.append(conversation, "coordinator", null, null, "routing", "routed", Map.of(), null);
        ChatMessage third =
                appender.append(conversation, "agent", null, UUID.randomUUID(), "answer", "done", Map.of(), null);

        assertThat(first.getPosition()).isZero();
        assertThat(second.getPosition()).isEqualTo(1);
        assertThat(third.getPosition()).isEqualTo(2);
        assertThat(conversation.getMessageCount()).isEqualTo(3);
    }

    // @find: test preview cut at word boundary, chat appender
    @Test
    @DisplayName("the preview is cut at 120 characters, at a word boundary")
    void previewCutAtWordBoundary() {
        String longContent = "word ".repeat(40).strip();
        appender.append(conversation, "user", null, null, "text", longContent, Map.of(), null);

        assertThat(conversation.getLastMessagePreview()).hasSizeLessThanOrEqualTo(120);
        assertThat(conversation.getLastMessagePreview()).doesNotEndWith(" ");
        assertThat(longContent).startsWith(conversation.getLastMessagePreview());
    }

    // @find: test record message called once per append, chat appender
    @Test
    @DisplayName("recordMessage is called exactly once per append")
    void recordMessageCalledOncePerAppend() {
        Conversation spyConversation = spy(conversation);

        appender.append(spyConversation, "user", null, null, "text", "hi", Map.of(), null);

        verify(spyConversation, times(1)).recordMessage(any());
    }

    // @find: test lock flushes then refreshes, chat appender
    @Test
    @DisplayName("lock flushes, then refreshes with a pessimistic write lock, in that order")
    void lockFlushesThenRefreshes() {
        doNothing().when(entityManager).flush();
        doNothing().when(entityManager).refresh(any(), eq(LockModeType.PESSIMISTIC_WRITE));

        Optional<Conversation> locked = appender.lock(ORG, conversation.getId());

        assertThat(locked).contains(conversation);
        InOrder order = inOrder(entityManager);
        order.verify(entityManager).flush();
        order.verify(entityManager).refresh(conversation, LockModeType.PESSIMISTIC_WRITE);
    }

    // @find: test lock returns empty for another org, chat appender
    @Test
    @DisplayName("lock returns empty for a conversation belonging to another org")
    void lockReturnsEmptyForAnotherOrg() {
        UUID otherOrg = UUID.randomUUID();
        when(conversations.findByIdAndOrgId(conversation.getId(), otherOrg)).thenReturn(Optional.empty());

        assertThat(appender.lock(otherOrg, conversation.getId())).isEmpty();
    }
}
