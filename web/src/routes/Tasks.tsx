import { useCallback, useMemo, useState } from 'react'
import {
  Button,
  Card,
  ConfirmDialog,
  DataTable,
  EmptyState,
  Eyebrow,
  FilterBar,
  FilterEmpty,
  Notice,
  PageHeader,
  StatusTag,
  Tag,
  Time,
} from '../components/ui'
import type { Column, FilterFacet } from '../components/ui'
import { BackLink, EmptyIcon, QueryState } from '../components/ui/QueryState'
import { TaskDialog } from '../components/ui/TaskDialog'
import { describeApiError } from '../lib/api'
import { formatCount, formatElapsed, formatRunElapsed, truncateWords } from '../lib/format'
import { categoryTone, statusLabel } from '../lib/labels'
import {
  isGoalActive,
  useAgentNames,
  useAgents,
  useCancelGoal,
  useGoal,
  useGoalPages,
  type Goal,
  type Task,
} from '../lib/queries'
import { useDocumentTitle, useRouter } from '../lib/router'
import { useToast } from '../lib/toast'
import { can } from '../lib/session'
import { useListFilter } from '../lib/useListFilter'
import { useNow } from '../lib/useNow'

/*
 * Goals and their tasks.
 *
 * The list shows each goal once; a goal's tasks, their agents and what each one produced live on
 * the goal's own view at /tasks?goal=<id>, which loads the goal by id so a deep link or an older
 * goal opens as reliably as a recent one.
 */

const GOAL_STATUSES = ['planning', 'running', 'waiting', 'completed', 'failed', 'cancelled'] as const

/** Statuses after which a task will not run again. */
const FINISHED_TASK = new Set(['completed', 'failed', 'cancelled', 'skipped'])

/** Below this many goals a search bar is more clutter than help. */
const FILTER_THRESHOLD = 5

/** A result longer than this is shortened here; the run trace holds the whole answer. */
const RESULT_PREVIEW = 1_200

function isTaskFinished(task: Task): boolean {
  return FINISHED_TASK.has(task.status.toLowerCase())
}

function goalSearchText(goal: Goal): string {
  return [goal.title, goal.description, statusLabel('goal', goal.status).label, ...goal.tasks.map((task) => task.title)].join(
    ' ',
  )
}

const GOAL_FACETS = { status: (goal: Goal) => goal.status.toLowerCase() }

const GOAL_COLUMNS: Column<Goal>[] = [
  { key: 'title', header: 'Goal', render: (goal) => goal.title, sortValue: (goal) => goal.title },
  {
    key: 'status',
    header: 'Status',
    render: (goal) => <StatusTag kind="goal" status={goal.status} />,
    sortValue: (goal) => statusLabel('goal', goal.status).label,
  },
  {
    key: 'progress',
    header: 'Tasks done',
    numeric: true,
    render: (goal) => {
      const done = goal.tasks.filter((task) => task.status.toLowerCase() === 'completed').length
      return `${formatCount(done)} of ${formatCount(goal.tasks.length)}`
    },
  },
  {
    key: 'createdAt',
    header: 'Created',
    render: (goal) => <Time iso={goal.createdAt} />,
    sortValue: (goal) => Date.parse(goal.createdAt),
  },
]

export function Tasks() {
  const { search } = useRouter()
  const goalId = search.get('goal')
  return goalId ? <GoalDetail id={goalId} /> : <GoalList />
}

/* ---- The list of goals ------------------------------------------------------------------------ */

function GoalList() {
  const query = useGoalPages()
  const [taskDialogOpen, setTaskDialogOpen] = useState(false)
  const canCreate = can('task:create')

  const goals = useMemo(() => query.data?.pages.flat(), [query.data])
  const filter = useListFilter({ rows: goals, text: goalSearchText, facets: GOAL_FACETS })

  return (
    <div className="page">
      <PageHeader
        eyebrow="What the workforce is doing"
        title="Tasks"
        description="A goal is broken into tasks, and a task starts only once the tasks before it have finished. Open a goal to see its tasks and what each one produced."
        action={canCreate ? <Button onClick={() => setTaskDialogOpen(true)}>Give an agent a task</Button> : undefined}
      />

      {/* Without onSuccess the dialog takes the person to the work it started. */}
      {canCreate && <TaskDialog open={taskDialogOpen} onClose={() => setTaskDialogOpen(false)} />}

      <QueryState
        // Once goals are showing, a failure to load an older page is reported beside the button
        // rather than replacing the list.
        query={{ data: goals, error: goals ? null : query.error, isLoading: query.isLoading, refetch: query.refetch }}
        permission="task:read"
        what="the goals list"
        isEmpty={(data) => data.length === 0}
        empty={
          <div style={{ marginTop: 'var(--space-7)' }}>
            <Card>
              <EmptyState
                icon={<EmptyIcon kind="task" />}
                title="No goals yet"
                body={
                  canCreate
                    ? 'Give an agent a task to start the first goal.'
                    : 'No goal has been created in this workspace yet.'
                }
                action={canCreate ? <Button onClick={() => setTaskDialogOpen(true)}>Give an agent a task</Button> : undefined}
              />
            </Card>
          </div>
        }
        rows={5}
      >
        {(loaded) => {
          const filtering = loaded.length > FILTER_THRESHOLD || filter.active
          const shown = filtering ? filter.filtered : loaded
          const statusCounts = filter.counts.status ?? {}
          const selectedStatuses = filter.selected.status ?? []
          const statusFacet: FilterFacet = {
            param: 'status',
            label: 'Status',
            options: GOAL_STATUSES.filter(
              (status) => (statusCounts[status] ?? 0) > 0 || selectedStatuses.includes(status),
            ).map((status) => ({
              value: status,
              label: statusLabel('goal', status).label,
              count: statusCounts[status] ?? 0,
            })),
            selected: selectedStatuses,
            onToggle: (value) => filter.toggle('status', value),
          }
          return (
            <div style={{ marginTop: 'var(--space-6)' }}>
              <Card as="section">
                <Eyebrow as="h2">Goals</Eyebrow>
                {filtering && (
                  <FilterBar
                    searchLabel="Search goals"
                    query={filter.query}
                    onQueryChange={filter.setQuery}
                    placeholder="Goal or task title"
                    facets={[statusFacet]}
                    shown={shown.length}
                    total={loaded.length}
                    scopeNote={
                      query.hasNextPage
                        ? `Search and the status filter cover the ${formatCount(loaded.length)} most recent goals loaded so far.`
                        : undefined
                    }
                    active={filter.active}
                    onClear={filter.clear}
                  />
                )}
                {shown.length > 0 ? (
                  <DataTable
                    columns={GOAL_COLUMNS}
                    rows={shown}
                    getKey={(goal) => goal.id}
                    getRowHref={(goal) => `/tasks?goal=${encodeURIComponent(goal.id)}`}
                    caption="Goals in this workspace, newest first, with their status and how many of their tasks are done."
                  />
                ) : (
                  <FilterEmpty onClear={filter.clear} what="goals" />
                )}
                {query.hasNextPage && (
                  <div style={{ marginTop: 'var(--space-5)' }}>
                    <Button
                      variant="outline"
                      onClick={() => void query.fetchNextPage()}
                      loading={query.isFetchingNextPage}
                    >
                      Show older goals
                    </Button>
                  </div>
                )}
                {query.isFetchNextPageError && (
                  <div style={{ marginTop: 'var(--space-4)' }}>
                    <Notice tone="warning" live>
                      Older goals could not be loaded. {describeApiError(query.error)}
                    </Notice>
                  </div>
                )}
              </Card>
            </div>
          )
        }}
      </QueryState>
    </div>
  )
}

/* ---- One goal --------------------------------------------------------------------------------- */

function GoalDetail({ id }: { id: string }) {
  const query = useGoal(id)
  useDocumentTitle(query.data ? truncateWords(query.data.title, 60) : null)

  return (
    <div className="page">
      <BackLink href="/tasks" label="Back to tasks" />
      <QueryState
        query={query}
        permission="task:read"
        what="this goal"
        rows={5}
        notFound={
          <a className="link" href="/tasks">
            See every goal
          </a>
        }
      >
        {(goal) => <GoalView goal={goal} />}
      </QueryState>
    </div>
  )
}

function GoalView({ goal }: { goal: Goal }) {
  const toast = useToast()
  const cancelGoal = useCancelGoal(goal.id)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [cancelError, setCancelError] = useState<string | null>(null)
  const columns = useTaskColumns()

  const canCancel = can('task:cancel') && isGoalActive(goal)
  const unfinished = goal.tasks.filter((task) => !isTaskFinished(task)).length
  const withOutcome = goal.tasks.filter((task) => task.result || task.failureReason)
  const description = goal.description?.trim()

  const confirmCancel = async () => {
    setCancelError(null)
    try {
      await cancelGoal.mutateAsync()
      toast.success('Goal cancelled')
      setConfirmOpen(false)
    } catch (error) {
      setCancelError(describeApiError(error))
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="Goal"
        title={goal.title}
        description={description && description !== goal.title.trim() ? description : undefined}
        meta={
          <>
            <StatusTag kind="goal" status={goal.status} withDot />
            <span className="caption">
              Created <Time iso={goal.createdAt} />
            </span>
            {goal.completedAt && (
              <span className="caption">
                Finished <Time iso={goal.completedAt} />
              </span>
            )}
          </>
        }
        action={
          canCancel ? (
            <Button
              variant="outline"
              onClick={() => {
                setCancelError(null)
                setConfirmOpen(true)
              }}
            >
              Cancel goal
            </Button>
          ) : undefined
        }
      />

      <div style={{ marginTop: 'var(--space-6)' }}>
        <Card as="section">
          <Eyebrow as="h2">Tasks</Eyebrow>
          <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
            Each task runs once the tasks before it have finished. Open a task to read its run trace.
          </p>
          {goal.tasks.length > 0 ? (
            <DataTable
              columns={columns}
              rows={goal.tasks}
              getKey={(task) => task.id}
              getRowHref={(task) => (task.runId ? `/runs/${task.runId}` : undefined)}
              caption={`Tasks for the goal "${truncateWords(goal.title, 80)}", with the agent, status and time of each.`}
            />
          ) : (
            <EmptyState
              titleAs="h3"
              icon={<EmptyIcon kind="task" />}
              title="No tasks yet"
              body="This goal has not been broken into tasks."
            />
          )}
        </Card>
      </div>

      {withOutcome.length > 0 && (
        <div style={{ marginTop: 'var(--space-6)' }}>
          <Card as="section">
            <Eyebrow as="h2">What the tasks produced</Eyebrow>
            <div className="stack" style={{ gap: 'var(--space-5)', marginTop: 'var(--space-4)' }}>
              {withOutcome.map((task) => (
                <TaskOutcome key={task.id} task={task} />
              ))}
            </div>
          </Card>
        </div>
      )}

      <ConfirmDialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={confirmCancel}
        eyebrow="Cancel goal"
        title="Cancel this goal?"
        description={cancelDescription(unfinished)}
        confirmLabel="Cancel goal"
        tone="danger"
        loading={cancelGoal.isPending}
        error={cancelError}
      />
    </>
  )
}

/** What cancelling does, from GoalService.cancel: open tasks close, and a run still going is stopped. */
function cancelDescription(unfinished: number): string {
  if (unfinished === 0) return 'Its tasks have all finished, so only the goal itself is marked cancelled.'
  const tasks = unfinished === 1 ? 'Its one unfinished task is' : `Its ${formatCount(unfinished)} unfinished tasks are`
  return `${tasks} cancelled and will not run again. A run still in progress is stopped, and any approval it is waiting on is withdrawn.`
}

/** The words before a task's failure reason. A skipped task never ran, so nothing of it "failed". */
function failureLeadFor(status: string): string {
  switch (status) {
    case 'failed':
      return 'Why it failed: '
    case 'cancelled':
      return 'Why it stopped: '
    case 'skipped':
      return 'Why it was skipped: '
    default:
      // Waiting to retry, or running again: the reason belongs to the attempt before this one.
      return 'The last attempt failed: '
  }
}

function TaskOutcome({ task }: { task: Task }) {
  const failureLead = failureLeadFor(task.status.toLowerCase())
  const result = task.result ?? ''
  const shortened = result.length > RESULT_PREVIEW
  return (
    <article style={{ borderTop: '1px solid var(--line)', paddingTop: 'var(--space-5)' }}>
      <div
        className="row"
        style={{ justifyContent: 'space-between', gap: 'var(--space-3)', flexWrap: 'wrap', marginBottom: 'var(--space-3)' }}
      >
        <h3 className="section-heading" style={{ fontSize: '15px' }}>
          {task.title}
        </h3>
        <StatusTag kind="task" status={task.status} />
      </div>
      {task.failureReason && (
        <p style={{ marginBottom: 'var(--space-3)' }}>
          <strong>{failureLead}</strong>
          {task.failureReason}
        </p>
      )}
      {result && (
        <p style={{ whiteSpace: shortened ? 'normal' : 'pre-wrap', overflowWrap: 'anywhere', marginBottom: 'var(--space-3)' }}>
          {shortened ? truncateWords(result, RESULT_PREVIEW) : result}
        </p>
      )}
      {task.runId && (
        <a className="link" href={`/runs/${task.runId}`}>
          {shortened ? 'Read the full result in the run trace' : 'Open the run trace'}
        </a>
      )}
    </article>
  )
}

/**
 * The task table's columns. Built here rather than once per module because the agent names and
 * the running clock are live.
 */
function useTaskColumns(): Column<Task>[] {
  const agentsQuery = useAgents()
  const agents = useAgentNames()
  const now = useNow(10_000)
  // Loading, or a failed request, is not the same as a missing agent: nothing is claimed about
  // an agent until the list has actually arrived.
  const agentsKnown = agentsQuery.data !== undefined

  const agentCell = useCallback(
    (agentId: string | null) => {
      if (!agentId) return <Tag>No agent</Tag>
      const agent = agents[agentId]
      if (agent) {
        return (
          <Tag tone={categoryTone(agent.category)} withDot title={agent.name}>
            {agent.name}
          </Tag>
        )
      }
      return agentsKnown ? <Tag>Unknown agent</Tag> : <span className="caption">—</span>
    },
    [agents, agentsKnown],
  )

  return useMemo<Column<Task>[]>(
    () => [
      { key: 'title', header: 'Task', render: (task) => task.title },
      { key: 'agent', header: 'Agent', render: (task) => agentCell(task.agentId) },
      { key: 'status', header: 'Status', render: (task) => <StatusTag kind="task" status={task.status} /> },
      {
        key: 'attempt',
        header: 'Attempt',
        render: (task) => (
          <span className="caption">
            {formatCount(task.attempt)} of {formatCount(task.maxAttempts)}
          </span>
        ),
      },
      {
        key: 'time',
        header: 'Time',
        numeric: true,
        render: (task) => {
          if (!task.startedAt) return <span className="caption">Not started</span>
          const status = task.status.toLowerCase()
          // A task held for an approval is not working; a clock counting up would suggest it is.
          if (status === 'waiting_approval') {
            return (
              <span className="caption">
                Started <Time iso={task.startedAt} />
              </span>
            )
          }
          if (status === 'running') return formatRunElapsed({ status, startedAt: task.startedAt }, now)
          // Without an end (a task waiting to retry, say) there is no duration to show, and
          // measuring to now would keep a stopped task's clock running.
          return task.completedAt ? formatElapsed(task.startedAt, task.completedAt, now) : '—'
        },
      },
    ],
    [agentCell, now],
  )
}
