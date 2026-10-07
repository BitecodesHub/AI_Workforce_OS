-- Files a person attaches to a chat message.
--
-- An attachment belongs to one conversation and is read under that conversation's own rules: a
-- private thread's files are as private as its messages. It is uploaded before the message is
-- sent, so conversation_id is null for a file attached in a chat that does not exist yet (bound
-- the moment the first message creates it) and message_id is null until the message is sent.
-- goal_id is the work that message started, which is how a run finds the files it was given.
--
-- The bytes live here, beside the conversation, rather than on a disk: every service instance
-- reads the same rows, a backup carries them, and deleting a conversation deletes its files by
-- cascade. 25 MB is the most a single file may be, enforced before the insert.
--
-- extracted_text is what the knowledge service read out of the file (it is not added to the
-- workspace's documents unless the person asks), and problem is why it could not be read.

CREATE TABLE chat_attachments (
    id                    UUID        PRIMARY KEY,
    org_id                UUID        NOT NULL,
    conversation_id       UUID        REFERENCES conversations (id) ON DELETE CASCADE,
    message_id            UUID        REFERENCES chat_messages (id) ON DELETE CASCADE,
    goal_id               UUID,
    uploaded_by           TEXT        NOT NULL,
    name                  TEXT        NOT NULL,
    media_type            TEXT        NOT NULL,
    kind                  TEXT        NOT NULL,
    size_bytes            BIGINT      NOT NULL,
    content_hash          TEXT        NOT NULL,
    content               BYTEA       NOT NULL,
    status                TEXT        NOT NULL,
    extracted_text        TEXT,
    page_count            INT,
    problem               TEXT,
    notice                TEXT,
    knowledge_document_id UUID,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chat_attachments_status_valid CHECK (status IN ('ready', 'unreadable')),
    CONSTRAINT chat_attachments_kind_valid CHECK (kind IN
        ('pdf', 'document', 'presentation', 'spreadsheet', 'text', 'image')),
    CONSTRAINT chat_attachments_size_valid CHECK (size_bytes > 0 AND size_bytes <= 26214400)
);

CREATE INDEX chat_attachments_conversation_idx ON chat_attachments (org_id, conversation_id);
CREATE INDEX chat_attachments_message_idx ON chat_attachments (message_id) WHERE message_id IS NOT NULL;
CREATE INDEX chat_attachments_goal_idx ON chat_attachments (goal_id) WHERE goal_id IS NOT NULL;
-- Unsent drafts, swept once they are a day old.
CREATE INDEX chat_attachments_unsent_idx ON chat_attachments (created_at) WHERE message_id IS NULL;
