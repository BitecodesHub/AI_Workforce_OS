-- Answer ratings, and the indexes the manager insights read through.
--
-- A rating is one person's thumbs up or thumbs down on one agent answer in chat, with an optional
-- reason. It is the only quality signal the product collects, so it is kept as its own table
-- rather than as a column on chat_messages: a message is never rewritten once written, a person
-- may change their mind, and the ratings are read by agent and by run far more often than by
-- message.
--
-- agent_id and run_id are copied from the answer message when the rating is written, by the
-- server and never from the client, so the per-agent satisfaction figure and the ratings shown on
-- a run's trace are a plain indexed read instead of a join through a JSON column. They are facts
-- about the message, which never changes, so the copy cannot drift.

CREATE TABLE chat_message_feedback (
    id              UUID        PRIMARY KEY,
    org_id          UUID        NOT NULL,
    conversation_id UUID        NOT NULL,
    -- Deleting a conversation deletes its messages, and a rating of a message that no longer
    -- exists, with a reason that may quote it, has nothing left to be about.
    message_id      UUID        NOT NULL REFERENCES chat_messages (id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL,
    agent_id        UUID,
    run_id          UUID,
    rating          SMALLINT    NOT NULL,
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chat_message_feedback_rating_valid CHECK (rating IN (-1, 1)),
    CONSTRAINT chat_message_feedback_reason_length CHECK (reason IS NULL OR char_length(reason) <= 500)
);

-- One vote per person per answer: a second one replaces the first.
CREATE UNIQUE INDEX chat_message_feedback_unique ON chat_message_feedback (org_id, message_id, user_id);

-- A person's votes in one conversation, so the thread can show which answers they rated.
CREATE INDEX chat_message_feedback_conversation_idx ON chat_message_feedback (conversation_id, user_id);

-- The ratings on one run's trace.
CREATE INDEX chat_message_feedback_run_idx ON chat_message_feedback (run_id) WHERE run_id IS NOT NULL;

-- Satisfaction per agent over a window.
CREATE INDEX chat_message_feedback_agent_idx ON chat_message_feedback (org_id, agent_id, updated_at);

-- ---------------------------------------------------------------------------------------------
-- Indexes for the insights windows (7, 30 and 90 days), so none of them reads a whole table.
-- runs (org_id, started_at DESC) already exists as runs_org_started_idx (V1), and llm_usage
-- (org_id, occurred_at DESC) as llm_usage_org_time_idx (V1).
-- ---------------------------------------------------------------------------------------------

-- Goals and tasks are counted on the day they finished.
CREATE INDEX IF NOT EXISTS goals_org_completed_idx ON goals (org_id, completed_at) WHERE completed_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS tasks_org_completed_idx ON tasks (org_id, completed_at) WHERE completed_at IS NOT NULL;

-- Approvals are counted on the day they were raised, whatever became of them; the existing
-- approvals_pending_idx holds only the ones still waiting.
CREATE INDEX IF NOT EXISTS approvals_org_requested_idx ON approvals (org_id, requested_at);

-- Questions likewise; run_questions_pending_idx holds only the open ones.
CREATE INDEX IF NOT EXISTS run_questions_org_created_idx ON run_questions (org_id, created_at);
