-- Workflows: repeatable work a person draws on a canvas, and the record of each time one ran.
--
--   workflows.graph        the canvas as JSON: {"nodes": [...], "edges": [...]}. Each node is one
--                          step (an agent, its instruction, an optional title, the connectors it
--                          needs and its place on the canvas); each edge says one step hands its
--                          result to the next. Checked by WorkflowValidator, never by the database:
--                          the rules (at most 40 steps, no loops, active agents) change with the
--                          product, and a CHECK here would turn a rule change into a migration.
--   workflows.version      the optimistic lock. A save names the version it was based on, and a
--                          save based on an older one is refused with 409 rather than overwriting
--                          somebody else's change.
--   workflow_runs          one row per run: the goal it created, the workflow version it ran and a
--                          copy of the graph as it was then, so a replay draws the canvas the run
--                          actually followed even after the workflow has been edited. node_ids
--                          maps each task position in the goal back to its node on that canvas.
--                          inputs keeps the values the person filled in when starting the run.
--                          Deleting a workflow keeps its runs (workflow_id becomes null), so the
--                          replay of work it already did still draws the canvas it followed;
--                          workflow_name keeps the name it had when it ran.
--   tasks.explicit_dependencies
--                          true for the tasks of a goal a workflow started: depends_on is then the
--                          complete list, so a step nothing hands over to starts at once and a
--                          failed step stops only the steps after it on its own branch. False (every
--                          existing task) keeps the old rule: an empty depends_on waits for every
--                          earlier position.

CREATE TABLE workflows (
    id          UUID          PRIMARY KEY,
    org_id      UUID          NOT NULL,
    name        VARCHAR(120)  NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    graph       JSONB         NOT NULL DEFAULT '{"nodes": [], "edges": []}'::jsonb,
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  VARCHAR(64),
    updated_by  VARCHAR(64),
    CONSTRAINT workflows_name_length CHECK (char_length(name) BETWEEN 1 AND 120)
);

-- The Workflows page lists a workspace's workflows, most recently changed first.
CREATE INDEX workflows_org_updated_idx ON workflows (org_id, updated_at DESC);

CREATE TABLE workflow_runs (
    id               UUID        PRIMARY KEY,
    org_id           UUID        NOT NULL,
    workflow_id      UUID        REFERENCES workflows (id) ON DELETE SET NULL,
    workflow_name    VARCHAR(120) NOT NULL,
    workflow_version BIGINT      NOT NULL,
    goal_id          UUID        NOT NULL REFERENCES goals (id) ON DELETE CASCADE,
    graph            JSONB       NOT NULL,
    node_ids         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    inputs           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       VARCHAR(64),
    updated_by       VARCHAR(64)
);

-- A workflow's run history, newest first.
CREATE INDEX workflow_runs_workflow_idx ON workflow_runs (workflow_id, created_at DESC);

-- A goal is created by at most one workflow run, and the replay looks the run up by its goal.
CREATE UNIQUE INDEX workflow_runs_goal_uq ON workflow_runs (goal_id);

ALTER TABLE tasks ADD COLUMN explicit_dependencies BOOLEAN NOT NULL DEFAULT false;
