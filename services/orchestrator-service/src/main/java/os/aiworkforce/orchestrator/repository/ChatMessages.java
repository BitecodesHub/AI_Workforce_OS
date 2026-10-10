// @find: chat messages repository, list messages in conversation, earlier messages, search messages, messages by goal, load older turns, ChatMessages
// @what: Spring Data repository for ChatMessage rows.
// @flow: Used by the chat service for history, paging and search.
// @find: chat messages repository, list messages in conversation, earlier messages, search messages, messages by goal, load older turns, ChatMessages
// @what: Spring Data repository for ChatMessage rows.
// @flow: Used by the chat service for history, paging and search.
package os.aiworkforce.orchestrator.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.ChatMessage;

/**
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all, so one interface per file is the convention (see the other
 * repositories in this package).
 */
public interface ChatMessages extends JpaRepository<ChatMessage, UUID> {

    // @find: list all messages in a conversation in order
    // @find: list all messages in a conversation in order
    List<ChatMessage> findByConversationIdOrderByPosition(UUID conversationId);

    // @find: get one message in a conversation
    // @find: get one message in a conversation
    Optional<ChatMessage> findByIdAndConversationId(UUID id, UUID conversationId);

    // @find: messages for a goal
    // @find: messages for a goal
    List<ChatMessage> findByGoalId(UUID goalId);

    // @find: latest messages in conversation, paged
    // @find: latest messages in conversation, paged
    /** The newest page of a conversation, for the message window (A3.6). */
    List<ChatMessage> findByConversationIdOrderByPositionDesc(UUID conversationId, Pageable page);

    // @find: older messages before a position, load earlier
    // @find: older messages before a position, load earlier
    /** An older page, for "Load earlier messages". */
    List<ChatMessage> findByConversationIdAndPositionLessThanOrderByPositionDesc(
            UUID conversationId, int before, Pageable page);

    // @find: earlier turns for model context
    // @find: earlier turns for model context
    /**
     * The turns an agent can be given as context before a given position, newest first: what
     * people wrote and what agents answered. Routing receipts, progress cards, notices and
     * questions are left out in the query itself, so a window of a given size holds that many turns
     * rather than whatever the coordinator happened to append between them.
     */
    @Query(
            """
            select m from ChatMessage m
            where m.conversationId = :conversationId and m.position < :before
              and ((m.kind = 'text' and m.authorKind = 'user') or m.kind = 'answer')
            order by m.position desc
            """)
    List<ChatMessage> findEarlierTurns(
            @Param("conversationId") UUID conversationId, @Param("before") int before, Pageable page);

    // @find: messages after a position, new messages
    // @find: messages after a position, new messages
    /** Messages appended after a given position, newest-goes-last - used to detect a race on a second click. */
    List<ChatMessage> findByConversationIdAndPositionGreaterThanOrderByPosition(UUID conversationId, int after);

    // @find: search message text, chat search
    // @find: search message text, chat search
    /**
     * [conversationId, messageId, content] of matching messages, newest first, for the
     * conversations on a search results page; the first row per conversation is its snippet.
     *
     * @param pattern a {@code lower(...) like} pattern already escaped by the caller
     */
    @Query(
            """
            select m.conversationId, m.id, m.content from ChatMessage m
            where m.conversationId in :ids and lower(m.content) like :pattern escape '!'
            order by m.position desc
            """)
    List<Object[]> matchesIn(@Param("ids") Collection<UUID> ids, @Param("pattern") String pattern, Pageable page);
}
