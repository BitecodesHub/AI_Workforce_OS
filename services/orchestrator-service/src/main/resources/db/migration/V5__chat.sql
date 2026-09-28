-- The chat coordinator: conversations, and every message inside them.
--
-- A conversation is the unit a person opens; a message is one turn inside it, whichever of the
-- coordinator, an agent or the person wrote it. message_count and last_message_preview are kept on
-- the conversation row itself, updated in the same write that appends a message, so the
-- conversation list needs no join to show what a person would expect to see there - and so that
-- write is also what advances updated_at, keeping "newest first" meaning "most recently active".

CREATE TABLE conversations (
    id                   UUID        PRIMARY KEY,
    org_id               UUID        NOT NULL,
    title                TEXT        NOT NULL DEFAULT '',
    message_count        INT         NOT NULL DEFAULT 0,
    last_message_preview TEXT        NOT NULL DEFAULT '',
    version              BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           TEXT,
    updated_by           TEXT
);

CREATE INDEX conversations_org_updated_idx ON conversations (org_id, updated_at DESC);

CREATE TABLE chat_messages (
    id              UUID        PRIMARY KEY,
    org_id          UUID        NOT NULL,
    conversation_id UUID        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    position        INT         NOT NULL,
    author_kind     TEXT        NOT NULL,
    -- Who wrote it, when that is a person rather than the coordinator or an agent.
    author_id       UUID,
    -- Which agent replied, set on an agent's own turn (an "answer" message) and null otherwise.
    agent_id        UUID,
    kind            TEXT        NOT NULL,
    content         TEXT        NOT NULL DEFAULT '',
    -- The shape differs completely by kind - a routing receipt, a set of cited passages, a
    -- schedule preview - so, as with run_steps, this is JSON rather than a table of mostly-null
    -- columns.
    detail          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- The goal this message is about, for a routing, progress, answer or error message; null for
    -- a plain text, documents or schedule-suggestion message.
    goal_id         UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chat_messages_author_kind_valid CHECK (author_kind IN ('user', 'coordinator', 'agent', 'system')),
    CONSTRAINT chat_messages_kind_valid CHECK (kind IN
        ('text', 'routing', 'documents', 'progress', 'answer', 'schedule_suggestion', 'error'))
);

CREATE UNIQUE INDEX chat_messages_position_unique ON chat_messages (conversation_id, position);
CREATE INDEX chat_messages_conversation_idx ON chat_messages (org_id, conversation_id, position);
CREATE INDEX chat_messages_goal_idx ON chat_messages (goal_id) WHERE goal_id IS NOT NULL;
