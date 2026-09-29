-- Clarifying questions, and the chat capabilities that come with them.
--
-- A run can now stop to ask the person it works for a question (status waiting_input), keep the
-- question in run_questions until it is answered, expires or is withdrawn, and record the ask in
-- its trace as a 'question' step. Chat gains a 'question' message and a neutral 'notice' message
-- (stopped, tried again), and each person can pin or archive a conversation for themselves.

ALTER TABLE runs DROP CONSTRAINT runs_status_valid;
ALTER TABLE runs ADD CONSTRAINT runs_status_valid CHECK (status IN
    ('running', 'waiting_approval', 'waiting_input', 'completed', 'failed', 'cancelled', 'abandoned'));

ALTER TABLE tasks DROP CONSTRAINT tasks_status_valid;
ALTER TABLE tasks ADD CONSTRAINT tasks_status_valid CHECK (status IN
    ('pending', 'ready', 'running', 'waiting_approval', 'waiting_input', 'completed', 'failed', 'cancelled', 'skipped'));

ALTER TABLE run_steps DROP CONSTRAINT run_steps_kind_valid;
ALTER TABLE run_steps ADD CONSTRAINT run_steps_kind_valid CHECK (kind IN
    ('model_call', 'tool_call', 'approval', 'question', 'handoff', 'memory_read', 'memory_write',
     'knowledge_query', 'note', 'error'));

ALTER TABLE chat_messages DROP CONSTRAINT chat_messages_kind_valid;
ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_kind_valid CHECK (kind IN
    ('text', 'routing', 'documents', 'progress', 'answer', 'schedule_suggestion', 'error', 'question', 'notice'));

CREATE TABLE run_questions (
    id              UUID        PRIMARY KEY,
    org_id          UUID        NOT NULL,
    run_id          UUID        NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    task_id         UUID        REFERENCES tasks (id) ON DELETE CASCADE,
    goal_id         UUID        REFERENCES goals (id) ON DELETE CASCADE,
    -- The conversation the question is shown in, when the goal came from chat. No foreign key,
    -- matching goals.conversation_id; a deleted conversation nulls it (see ChatController.delete).
    conversation_id UUID,
    agent_id        UUID        NOT NULL,
    -- The ask call's id, rewritten by AgentRunner so it is unique within the run (a provider's own
    -- ids can repeat: Gemini numbers calls from zero in every response). The answer is delivered as
    -- this call's tool result.
    tool_call_id    TEXT        NOT NULL,
    -- 1 to 4 questions: [{id, header, question, multiSelect, options: [{label, description, recommended}]}]
    questions       JSONB       NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'pending',
    -- {answers: [{questionId, selected: [label], other}], note, skipped}
    answer          JSONB,
    answered_by     UUID,
    answered_via    TEXT,
    answered_at     TIMESTAMPTZ,
    -- Who may answer by default: the goal's requester, or the person who started a direct run.
    requested_by    UUID,
    expires_at      TIMESTAMPTZ NOT NULL,
    closed_reason   TEXT,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      TEXT,
    updated_by      TEXT,
    CONSTRAINT run_questions_status_valid CHECK (status IN ('pending', 'answered', 'expired', 'cancelled')),
    CONSTRAINT run_questions_via_valid CHECK (answered_via IN ('chat', 'orchestrator', 'run', 'approvals'))
);

CREATE UNIQUE INDEX run_questions_call_unique ON run_questions (run_id, tool_call_id);
CREATE UNIQUE INDEX run_questions_one_pending ON run_questions (run_id) WHERE status = 'pending';
CREATE INDEX run_questions_pending_idx ON run_questions (org_id, created_at) WHERE status = 'pending';
CREATE INDEX run_questions_expiry_idx ON run_questions (expires_at) WHERE status = 'pending';
CREATE INDEX run_questions_run_idx ON run_questions (run_id, created_at);
CREATE INDEX run_questions_conversation_idx ON run_questions (conversation_id, created_at)
    WHERE conversation_id IS NOT NULL;

-- Runs parked for an answer, for the resume sweep and the board.
CREATE INDEX runs_waiting_input_idx ON runs (org_id) WHERE status = 'waiting_input';

-- The workspace's General Employee: the agent that takes a request no specialist fits. Found by
-- this flag, never by its key, so a custom agent someone already named "general" is not mistaken
-- for it. At most one per workspace.
ALTER TABLE agents ADD COLUMN is_fallback BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX agents_one_fallback ON agents (org_id) WHERE is_fallback;

-- Personal marks on a shared conversation: pinned to the top of one person's list, archived out
-- of it, or read up to a position. Nobody else's list changes.
CREATE TABLE conversation_marks (
    id                  UUID        PRIMARY KEY,
    org_id              UUID        NOT NULL,
    conversation_id     UUID        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    user_id             UUID        NOT NULL,
    pinned              BOOLEAN     NOT NULL DEFAULT FALSE,
    archived            BOOLEAN     NOT NULL DEFAULT FALSE,
    -- The highest message position this person has seen. NULL means never opened here.
    last_read_position  INTEGER,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX conversation_marks_unique ON conversation_marks (conversation_id, user_id);
CREATE INDEX conversation_marks_user_idx ON conversation_marks (org_id, user_id);
