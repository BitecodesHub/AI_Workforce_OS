-- Skills: reusable playbooks a person applies to a chat message from the composer's "+" menu.
--
--   instructions          markdown, applied to the run of the message the skill is used with
--   default_agent_id      the agent that takes the message when nobody is mentioned; null = route
--                         as usual. Not a foreign key: retiring or deleting an agent must not
--                         delete the playbook, and a missing agent is simply ignored.
--   required_connectors   connector server ids (github, gmail, ...) the playbook needs, shown to
--                         the person; never enforced here, the agent's own grants decide.
--
-- skill_seeds marks a workspace that has been given the ready-made skills once, so deleting them
-- all does not bring them back.
--
-- chat_queued_messages.context keeps what was attached to a message that waits its turn
-- (knowledge, a repository, a skill), so it starts later exactly as it was sent.

CREATE TABLE skills (
    id                  UUID         PRIMARY KEY,
    org_id              UUID         NOT NULL,
    name                VARCHAR(120) NOT NULL,
    description         VARCHAR(500) NOT NULL DEFAULT '',
    instructions        TEXT         NOT NULL,
    default_agent_id    UUID,
    required_connectors JSONB        NOT NULL DEFAULT '[]',
    built_in            BOOLEAN      NOT NULL DEFAULT FALSE,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    CONSTRAINT skills_instructions_length CHECK (char_length(instructions) BETWEEN 1 AND 20000)
);

CREATE UNIQUE INDEX skills_org_name_uq ON skills (org_id, lower(name));
CREATE INDEX skills_org_idx ON skills (org_id, name);

CREATE TABLE skill_seeds (
    org_id    UUID        PRIMARY KEY,
    seeded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE chat_queued_messages ADD COLUMN context JSONB;
