-- One answer at a time per conversation.
--
-- A message sent while the conversation already has work in progress is kept here, in order,
-- and starts by itself when that work ends. It joins the thread as an ordinary message only when
-- it starts, so the thread reads question, answer, question, answer.
--
--   actor        who sent it, as the request knew them (id, role, permissions), so the work starts
--                later with exactly the authority it was sent with. Never shown to readers.
--   status       queued, starting (claimed by "Start now anyway" while the old work stops), or
--                expired (not started within 24 hours; shown as "Expired - send again").
--
-- conversations.deciding_since / deciding_message_id mark a message whose routing is still being
-- decided (before any goal exists), so a second message sent in that moment is queued too. Stale
-- marks (a crash mid-decision) are ignored after a few minutes.

CREATE TABLE chat_queued_messages (
    id              UUID        PRIMARY KEY,
    org_id          UUID        NOT NULL,
    conversation_id UUID        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    author_id       UUID,
    text            TEXT        NOT NULL DEFAULT '',
    agent_ids       JSONB       NOT NULL DEFAULT '[]',
    attachment_ids  JSONB       NOT NULL DEFAULT '[]',
    actor           JSONB       NOT NULL DEFAULT '{}',
    status          TEXT        NOT NULL DEFAULT 'queued',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chat_queued_status_valid CHECK (status IN ('queued', 'starting', 'expired'))
);

CREATE INDEX chat_queued_conversation_idx ON chat_queued_messages (conversation_id, created_at);
CREATE INDEX chat_queued_waiting_idx ON chat_queued_messages (status, created_at) WHERE status <> 'expired';

ALTER TABLE conversations ADD COLUMN deciding_since TIMESTAMPTZ;
ALTER TABLE conversations ADD COLUMN deciding_message_id UUID;
