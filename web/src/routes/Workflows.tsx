// @find: workflows page, list workflows, new workflow, delete workflow, last run, watch a run, replay a goal, replay other work, /workflows, Workflows page
// @what: The Workflows page: the workspace's canvas workflows with their last run, a way to start a new one, and replays of recent multi-step goals.
// @flow: Routed from App.tsx at /workflows; uses lib/workflowQueries (useWorkflows, useDeleteWorkflow) and useGoals for the replay list; links to /workflows/:id and /workflows/run/:goalId
import { useState } from 'react'
import { Button, Card, ConfirmDialog, EmptyState, PageHeader, StatusTag, Time } from '../components/ui'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { describeApiError } from '../lib/api'
import { plural } from '../lib/format'
import { isGoalActive, useGoals } from '../lib/queries'
import { can } from '../lib/session'
import { useToast } from '../lib/toast'
import { canEditWorkflows, useDeleteWorkflow, useWorkflows } from '../lib/workflowQueries'
import type { WorkflowSummary } from '../lib/workflowQueries'

/*
 * A workflow is repeatable work drawn once on a canvas and run whenever it is needed. Everyone who
 * can read tasks sees the list and can watch or replay a run; only someone who can start work
 * (task:create) can build or run one, and only its maker or someone who can cancel work can
 * change or delete it.
 */

/** How many recent multi-step goals the replay list offers at first, and how many more each "Show more" adds. */
const REPLAY_LIST = 6

// @find: workflow card, one workflow in the list
/** One workflow: its name (opening the editor), description, size, when it changed and its last run. */
function WorkflowCard({
  workflow,
  canEdit,
  onDelete,
}: {
  workflow: WorkflowSummary
  canEdit: boolean
  onDelete: () => void
}) {
  const last = workflow.lastRun
  const lastActive = last ? isGoalActive({ status: last.status ?? '' }) : false
  const canDelete = canEdit && (workflow.canChange ?? true)
  return (
    <Card as="article" className="wf-card">
      <h2 className="wf-card-title">
        <a className="link" href={`/workflows/${workflow.id}`}>
          {workflow.name}
        </a>
      </h2>
      {workflow.description && <p className="wf-card-description muted">{workflow.description}</p>}
      <dl className="wf-card-facts">
        <div>
          <dt className="caption muted">Size</dt>
          <dd>
            {plural(workflow.steps, 'step', 'steps')}, {plural(workflow.connections, 'connection', 'connections')}
          </dd>
        </div>
        <div>
          <dt className="caption muted">Changed</dt>
          <dd>
            <Time iso={workflow.updatedAt} />
          </dd>
        </div>
        <div>
          <dt className="caption muted">Last run</dt>
          <dd>{last ? <StatusTag kind="goal" status={last.status} withDot /> : <span className="muted">Not run yet</span>}</dd>
        </div>
      </dl>
      <div className="wf-card-actions">
        {/* Someone who may not change this one opens it to read and run it, not to edit it. */}
        <a className="button button-outline" href={`/workflows/${workflow.id}`}>
          {canDelete ? 'Open the editor' : 'Open'}
        </a>
        {last && (
          <a className="button button-quiet" href={`/workflows/run/${last.goalId}`}>
            {lastActive ? 'Watch the run' : 'Replay the last run'}
          </a>
        )}
        {canDelete && (
          <Button variant="quiet" onClick={onDelete} aria-label={`Delete ${workflow.name}`}>
            Delete
          </Button>
        )}
      </div>
    </Card>
  )
}

// @find: replay other work, recent multi-step goals, show more
/**
 * Recent finished goals with more than one task, each with a link to replay it; "Show more" adds
 * the next few. A goal's own sheet on the Orchestrator also links to its replay.
 */
function ReplayOtherWork() {
  const goals = useGoals()
  const [shown, setShown] = useState(REPLAY_LIST)
  const finished = (goals.data ?? []).filter((goal) => goal.tasks.length > 1 && !isGoalActive(goal))
  const chains = finished.slice(0, shown)
  if (goals.isLoading || chains.length === 0) return null
  return (
    <section className="wf-section" aria-labelledby="wf-replay-other">
      <h2 id="wf-replay-other" className="section-heading">
        Replay recent work
      </h2>
      <p className="caption muted">Any goal that passed work from one agent to another can be replayed, wherever it started.</p>
      <ul className="wf-replay-list">
        {chains.map((goal) => (
          <li key={goal.id}>
            <span className="wf-replay-title">{goal.title}</span>
            <span className="caption muted">
              {plural(goal.tasks.length, 'step', 'steps')}, <Time iso={goal.completedAt ?? goal.createdAt} />
            </span>
            <StatusTag kind="goal" status={goal.status} />
            <a className="link" href={`/workflows/run/${goal.id}`} aria-label={`Replay ${goal.title}`}>
              Replay
            </a>
          </li>
        ))}
      </ul>
      {finished.length > shown && (
        <Button variant="quiet" onClick={() => setShown((count) => count + REPLAY_LIST)}>
          Show more
        </Button>
      )}
    </section>
  )
}

// @find: Workflows page component
/**
 * The Workflows page: header with New workflow (task:create), the list in its loading, empty,
 * error and permission states, a confirmation before a delete, and the replay list.
 */
export function Workflows() {
  const canEdit = canEditWorkflows(can)
  const toast = useToast()
  const query = useWorkflows()
  const remove = useDeleteWorkflow()
  const [deleting, setDeleting] = useState<WorkflowSummary | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const confirmDelete = async () => {
    if (!deleting) return
    setDeleteError(null)
    try {
      await remove.mutateAsync(deleting.id)
      toast.success(`${deleting.name} was deleted`)
      setDeleting(null)
    } catch (error) {
      setDeleteError(describeApiError(error))
    }
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Repeatable work"
        title="Workflows"
        description="Draw a piece of work once: which agents do it, in what order, and what each one hands to the next. Run it whenever it is needed, watch it move, and replay it afterwards."
        action={
          canEdit ? (
            <a className="button button-primary" href="/workflows/new">
              New workflow
            </a>
          ) : undefined
        }
      />

      <QueryState
        query={query}
        permission="task:read"
        what="the workflows list"
        isEmpty={(rows) => rows.length === 0}
        empty={
          <Card as="section">
            <EmptyState
              icon={<EmptyIcon kind="task" />}
              title="No workflows yet"
              body={
                canEdit
                  ? 'Build your first one: drag agents onto a canvas, connect them in the order the work flows, and run it.'
                  : 'Nobody has built a workflow in this workspace yet.'
              }
              action={
                canEdit ? (
                  <a className="button button-primary" href="/workflows/new">
                    New workflow
                  </a>
                ) : undefined
              }
            />
          </Card>
        }
        rows={4}
      >
        {(workflows) => (
          <div className="wf-grid">
            {workflows.map((workflow) => (
              <WorkflowCard
                key={workflow.id}
                workflow={workflow}
                canEdit={canEdit}
                onDelete={() => {
                  setDeleteError(null)
                  setDeleting(workflow)
                }}
              />
            ))}
          </div>
        )}
      </QueryState>

      <ReplayOtherWork />

      <ConfirmDialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        onConfirm={confirmDelete}
        eyebrow="Delete a workflow"
        title={`Delete ${deleting?.name ?? 'this workflow'}?`}
        description="The workflow is removed. The work it already did stays in Tasks and Runs, and its runs can still be replayed."
        confirmLabel="Delete"
        tone="danger"
        loading={remove.isPending}
        error={deleteError}
      />
    </div>
  )
}
