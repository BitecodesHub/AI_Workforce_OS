package os.aiworkforce.orchestrator.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import os.aiworkforce.orchestrator.domain.Conversation;

/**
 * Spring Data scans for top-level repository interfaces. A repository nested inside a holder
 * class is not found at all, so one interface per file is the convention (see the other
 * repositories in this package).
 */
public interface Conversations extends JpaRepository<Conversation, UUID> {

    /** Every conversation in the workspace, most recently active first - visible to anyone in it. */
    List<Conversation> findByOrgIdOrderByUpdatedAtDesc(UUID orgId, Pageable pageable);

    Optional<Conversation> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The workspace's conversations, excluding a set of ids (pinned and archived, already listed
     * separately), optionally narrowed to the caller's own and to a search pattern.
     *
     * @param readAll whether the caller may read other people's private conversations (chat:read_all)
     * @param pattern {@code ''} when there is no search, otherwise a {@code lower(...) like}
     *     pattern already escaped by the caller
     */
    @Query(
            """
            select c from Conversation c
            where c.orgId = :orgId and c.id not in :excluded
              and (:mine = false or c.createdBy = :actorId)
              and (:readAll = true or c.visibility = 'workspace' or c.createdBy = :actorId
                   or exists (select 1 from ConversationParticipant p where p.conversationId = c.id and p.userId = :actorId))
              and (:pattern = '' or lower(c.title) like :pattern escape '!'
                   or exists (select 1 from ChatMessage m where m.conversationId = c.id and lower(m.content) like :pattern escape '!'))
            order by c.updatedAt desc
            """)
    List<Conversation> searchExcluding(
            @Param("orgId") UUID orgId,
            @Param("excluded") Collection<UUID> excluded,
            @Param("mine") boolean mine,
            @Param("actorId") String actorId,
            @Param("readAll") boolean readAll,
            @Param("pattern") String pattern,
            Pageable page);

    /** The same search as {@link #searchExcluding}, restricted to a fixed set of ids (pinned, needs-you, archived). */
    @Query(
            """
            select c from Conversation c
            where c.orgId = :orgId and c.id in :ids
              and (:mine = false or c.createdBy = :actorId)
              and (:readAll = true or c.visibility = 'workspace' or c.createdBy = :actorId
                   or exists (select 1 from ConversationParticipant p where p.conversationId = c.id and p.userId = :actorId))
              and (:pattern = '' or lower(c.title) like :pattern escape '!'
                   or exists (select 1 from ChatMessage m where m.conversationId = c.id and lower(m.content) like :pattern escape '!'))
            order by c.updatedAt desc
            """)
    List<Conversation> searchAmong(
            @Param("orgId") UUID orgId,
            @Param("ids") Collection<UUID> ids,
            @Param("mine") boolean mine,
            @Param("actorId") String actorId,
            @Param("readAll") boolean readAll,
            @Param("pattern") String pattern);
}
