-- Private conversations.
--
-- A conversation is either 'workspace' (everyone with Chat access reads it, as before) or 'private'
-- (its creator and the people they add read it, and so does anybody holding chat:read_all, which
-- every such read is audited for). Every conversation that exists today stays 'workspace': nobody
-- chose privacy for those, and hiding them now would silently take work away from the people who
-- were using it. New conversations take the workspace's chat.defaultVisibility setting.

ALTER TABLE conversations
    ADD COLUMN visibility TEXT NOT NULL DEFAULT 'workspace'
        CHECK (visibility IN ('private', 'workspace'));

-- Who else may read a private conversation. user_id is text, like conversations.created_by, so the
-- creator and the participants are compared the same way.
CREATE TABLE conversation_participants (
    conversation_id UUID        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    user_id         TEXT        NOT NULL,
    added_by        TEXT        NOT NULL,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX conversation_participants_user_idx ON conversation_participants (user_id);

-- The list asks for a person's private conversations; a partial index keeps that cheap while almost
-- every row is 'workspace'.
CREATE INDEX conversations_private_idx ON conversations (org_id, created_by) WHERE visibility = 'private';
