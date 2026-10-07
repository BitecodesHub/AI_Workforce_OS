package os.aiworkforce.orchestrator.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The files attached to chat messages, read and written with plain SQL.
 *
 * <p>Not a JPA entity on purpose: a row carries up to 25 MB of bytes, and the only reads that need
 * them are a download and a run that shows a picture to a model. Every other read - the chip in
 * the composer, the card on a sent message, the text a run is given - selects the columns it needs
 * and never the {@code content} column, which a mapped entity would load every time. Every query
 * names the workspace, so a row from another workspace is never read, whatever id is asked for.
 */
@Repository
public class ChatAttachments {

    /** Everything but the bytes and the extracted text. */
    private static final String COLUMNS = "id, org_id, conversation_id, message_id, goal_id, uploaded_by, name,"
            + " media_type, kind, size_bytes, content_hash, status, page_count, problem, notice,"
            + " knowledge_document_id, created_at";

    /**
     * One attached file, without its bytes.
     *
     * @param text what was read out of it; null unless the query asked for it
     */
    public record Row(
            UUID id,
            UUID orgId,
            UUID conversationId,
            UUID messageId,
            UUID goalId,
            String uploadedBy,
            String name,
            String mediaType,
            String kind,
            long size,
            String contentHash,
            String status,
            Integer pageCount,
            String problem,
            String notice,
            UUID knowledgeDocumentId,
            Instant createdAt,
            String text) {

        public boolean isImage() {
            return "image".equals(kind);
        }

        public boolean isReady() {
            return "ready".equals(status);
        }

        public boolean isSent() {
            return messageId != null;
        }
    }

    /** A new file, as it is stored. */
    public record NewAttachment(
            UUID id,
            UUID orgId,
            UUID conversationId,
            String uploadedBy,
            String name,
            String mediaType,
            String kind,
            byte[] content,
            String contentHash,
            String status,
            String text,
            Integer pageCount,
            String problem,
            String notice) {}

    private final JdbcTemplate jdbc;

    public ChatAttachments(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(NewAttachment a) {
        jdbc.update(
                "insert into chat_attachments (id, org_id, conversation_id, uploaded_by, name, media_type, kind,"
                        + " size_bytes, content_hash, content, status, extracted_text, page_count, problem, notice)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                a.id(),
                a.orgId(),
                a.conversationId(),
                a.uploadedBy(),
                a.name(),
                a.mediaType(),
                a.kind(),
                (long) a.content().length,
                a.contentHash(),
                a.content(),
                a.status(),
                a.text(),
                a.pageCount(),
                a.problem(),
                a.notice());
    }

    public Optional<Row> find(UUID orgId, UUID id) {
        return jdbc.query("select " + COLUMNS + ", null as extracted_text from chat_attachments where org_id = ? and id = ?",
                        this::row, orgId, id)
                .stream()
                .findFirst();
    }

    /** The files, in the order asked for, that exist in this workspace; missing ids are left out. */
    public List<Row> findAll(UUID orgId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Row> found = jdbc.query(
                "select " + COLUMNS + ", null as extracted_text from chat_attachments where org_id = ? and id in ("
                        + marks(ids) + ")",
                this::row,
                args(orgId, ids));
        return ids.stream()
                .flatMap(id -> found.stream().filter(row -> row.id().equals(id)).findFirst().stream())
                .toList();
    }

    public Optional<byte[]> content(UUID orgId, UUID id) {
        return jdbc.query(
                        "select content from chat_attachments where org_id = ? and id = ?",
                        (rs, n) -> rs.getBytes(1),
                        orgId,
                        id)
                .stream()
                .findFirst();
    }

    /** The files a goal was started with, with their text, oldest first. */
    public List<Row> forGoal(UUID orgId, UUID goalId) {
        return jdbc.query(
                "select " + COLUMNS + ", extracted_text from chat_attachments where org_id = ? and goal_id = ?"
                        + " order by created_at, id",
                this::row,
                orgId,
                goalId);
    }

    /** As {@link #forGoal}, for exactly these files: a run being rebuilt reads what it read before. */
    public List<Row> withText(UUID orgId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                "select " + COLUMNS + ", extracted_text from chat_attachments where org_id = ? and id in ("
                        + marks(ids) + ") order by created_at, id",
                this::row,
                args(orgId, ids));
    }

    /** Ties drafts to the message that sent them, and to its conversation if they had none yet. */
    public int bindToMessage(UUID orgId, UUID conversationId, UUID messageId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        Object[] args = new Object[ids.size() + 3];
        args[0] = messageId;
        args[1] = conversationId;
        System.arraycopy(args(orgId, ids), 0, args, 2, ids.size() + 1);
        return jdbc.update(
                "update chat_attachments set message_id = ?, conversation_id = ? where org_id = ? and id in ("
                        + marks(ids) + ") and message_id is null",
                args);
    }

    /** The goal a message's files were given to. */
    public int linkGoal(UUID orgId, UUID messageId, UUID goalId) {
        return jdbc.update(
                "update chat_attachments set goal_id = ? where org_id = ? and message_id = ? and goal_id is null",
                goalId,
                orgId,
                messageId);
    }

    /** Work sent to someone else instead: its files go with it. */
    public int moveGoal(UUID orgId, UUID fromGoalId, UUID toGoalId) {
        return jdbc.update(
                "update chat_attachments set goal_id = ? where org_id = ? and goal_id = ?", toGoalId, orgId, fromGoalId);
    }

    /**
     * A choice made after the coordinator could not choose: the files of the last message the
     * person sent before that choice go to the work it started.
     */
    public int linkLatestUserMessage(UUID orgId, UUID conversationId, int beforePosition, UUID goalId) {
        return jdbc.update(
                "update chat_attachments set goal_id = ? where org_id = ? and goal_id is null and message_id ="
                        + " (select m.id from chat_messages m where m.org_id = ? and m.conversation_id = ?"
                        + " and m.author_kind = 'user' and m.position < ? order by m.position desc limit 1)",
                goalId,
                orgId,
                orgId,
                conversationId,
                beforePosition);
    }

    public int delete(UUID orgId, UUID id) {
        return jdbc.update("delete from chat_attachments where org_id = ? and id = ?", orgId, id);
    }

    public void markSaved(UUID orgId, UUID id, UUID documentId) {
        jdbc.update(
                "update chat_attachments set knowledge_document_id = ? where org_id = ? and id = ?", documentId, orgId, id);
    }

    /** Drafts nobody sent, older than the cut-off, across every workspace. */
    public int deleteUnsentBefore(Instant cutoff) {
        return jdbc.update(
                "delete from chat_attachments where message_id is null and created_at < ?", Timestamp.from(cutoff));
    }

    private static String marks(List<UUID> ids) {
        return String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
    }

    private static Object[] args(UUID orgId, List<UUID> ids) {
        Object[] args = new Object[ids.size() + 1];
        args[0] = orgId;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        return args;
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("org_id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getObject("message_id", UUID.class),
                rs.getObject("goal_id", UUID.class),
                rs.getString("uploaded_by"),
                rs.getString("name"),
                rs.getString("media_type"),
                rs.getString("kind"),
                rs.getLong("size_bytes"),
                rs.getString("content_hash"),
                rs.getString("status"),
                (Integer) rs.getObject("page_count"),
                rs.getString("problem"),
                rs.getString("notice"),
                rs.getObject("knowledge_document_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("extracted_text"));
    }
}
