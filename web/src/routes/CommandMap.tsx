import { useState } from 'react'
import {
  Button,
  Card,
  DataTable,
  EmptyState,
  Eyebrow,
  Notice,
  PageHeader,
  StatRow,
  StatTile,
  StatusTag,
  Tag,
  Time,
} from '../components/ui'
import type { Column } from '../components/ui'
import { QueryState, EmptyIcon } from '../components/ui/QueryState'
import { TaskDialog } from '../components/ui/TaskDialog'
import { GettingStarted } from '../components/command-map/GettingStarted'
import { formatCount, formatRelative, formatRunElapsed, truncateWords } from '../lib/format'
import { categoryTone, startedByLabel } from '../lib/labels'
import {
  RUN_PAGE_SIZE,
  useAgentNames,
  useAgents,
  useApprovals,
  useCredentials,
  useModelPolicy,
  useProviders,
  useRuns,
  useTaskIndex,
} from '../lib/queries'
import type { Run } from '../lib/queries'
import { liveRouting, routingSummary } from '../lib/routing'
import { can } from '../lib/session'
import { useNow } from '../lib/useNow'

/*
 * The first screen after signing in.
 *
 * It answers three questions in order: is anything waiting on me, which model are the agents
 * really answering on, and what have they been doing. Every figure says what it covers (the most
 * recent runs, not all of them) and opens the list behind it.
 */

export function CommandMap() {
  const runsQuery = useRuns()
  const [taskDialogOpen, setTaskDialogOpen] = useState(false)
  const canCreate = can('task:create')
  const canReadIntegrations = can('integration:read')
  const canChat = can('chat:use')
  const canOrchestrate = can('run:read')
  const giveTask = canCreate ? <Button onClick={() => setTaskDialogOpen(true)}>Give an agent a task</Button> : undefined

  return (
    <div className="page">
      <PageHeader
        eyebrow="Your workforce, in focus"
        title="Command Map"
        description="What your agents have been doing recently, and anything waiting on a decision."
        action={
          <>
            {canChat && (
              <a className="button button-outline" href="/chat">
                Chat
              </a>
            )}
            {canOrchestrate && (
              <a className="button button-outline" href="/orchestrator">
                Orchestrator
              </a>
            )}
            {canReadIntegrations && (
              <a className="button button-outline" href="/integrations">
                Integrations
              </a>
            )}
            {giveTask}
          </>
        }
      />

      {/* No onSuccess: once the task starts, the dialog opens its run, or its goal if no run started. */}
      {canCreate && <TaskDialog open={taskDialogOpen} onClose={() => setTaskDialogOpen(false)} />}

      <div className="stack" style={{ gap: 'var(--space-6)' }}>
        <ApprovalsWaiting />
        <RoutingStatus />
        <GettingStarted />

        <QueryState
          query={runsQuery}
          permission="run:read"
          what="recent runs"
          isEmpty={(data) => data.length === 0}
          empty={
            <Card>
              <EmptyState
                icon={<EmptyIcon kind="task" />}
                title="No runs yet"
                body={
                  canCreate
                    ? 'No agent has run yet. Give an agent a task to see its trace here.'
                    : 'No agent has run yet. Traces appear here once someone gives an agent a task.'
                }
                action={giveTask}
              />
            </Card>
          }
          rows={4}
        >
          {(runs) => <RecentRuns runs={runs} />}
        </QueryState>
      </div>
    </div>
  )
}

/** Pending approvals, shared with the navigation badge through the ['approvals'] cache. */
function ApprovalsWaiting() {
  const canRead = can('approval:read')
  const approvals = useApprovals({ enabled: canRead })
  const count = approvals.data?.length ?? 0
  if (!canRead || count === 0) return null
  return (
    <Notice tone="info">
      <span>
        {count === 1 ? '1 action is waiting for a decision.' : `${formatCount(count)} actions are waiting for a decision.`}{' '}
        <a className="link" href="/approvals">
          Open Approvals
        </a>
      </span>
    </Notice>
  )
}

/**
 * Which model runs are sent to, worked out from the routing policy, the providers and the stored
 * keys. Shown only to roles that can read all three, and only once all three have loaded: a claim
 * on the first screen that turns out to be wrong is worse than none, so a failed request makes no
 * claim at all. Other roles see sandbox answers marked on each trace instead.
 */
function RoutingStatus() {
  const canRead = can('provider:read')
  const canManage = can('provider:manage')
  const policy = useModelPolicy({ enabled: canRead })
  const providers = useProviders({ enabled: canRead })
  const credentials = useCredentials({ enabled: canRead })

  if (!canRead || policy.error || providers.error || credentials.error) return null
  if (!policy.data || !providers.data || !credentials.data) return null

  const routing = liveRouting(policy.data, providers.data, credentials.data)
  const summary = routingSummary(routing, { canManage })
  const link = (
    <a className="link" href="/routing">
      {summary.linkToRouting ? 'Set up model routing' : 'See model routing'}
    </a>
  )

  if (routing.live) {
    return (
      <p className="caption">
        {summary.text} {link}
      </p>
    )
  }
  return (
    <Notice tone={summary.tone}>
      <span>
        {summary.text} {link}
      </span>
    </Notice>
  )
}

function RecentRuns({ runs }: { runs: Run[] }) {
  const agentsQuery = useAgents()
  const agents = useAgentNames()
  // Every role can read tasks today; the check keeps a narrower custom role from a failed request.
  const taskIndex = useTaskIndex({ enabled: can('task:read') })
  // Running durations tick; everything else here changes only when the list refreshes.
  const now = useNow(15_000)

  const agentName = (run: Run) =>
    agents[run.agentId]?.name ?? (agentsQuery.isLoading ? 'Loading…' : 'Unknown agent')
  const count = (status: string) => runs.filter((run) => run.status === status).length
  const allLoaded = runs.length < RUN_PAGE_SIZE

  const columns: Column<Run>[] = [
    {
      key: 'agent',
      header: 'Agent',
      render: (run) => {
        const name = agentName(run)
        return (
          <Tag tone={categoryTone(agents[run.agentId]?.category)} withDot title={name}>
            {name}
          </Tag>
        )
      },
    },
    { key: 'status', header: 'Status', render: (run) => <StatusTag kind="run" status={run.status} /> },
    {
      key: 'startedBy',
      header: 'Started by',
      render: (run) => {
        const entry = run.taskId ? taskIndex[run.taskId] : undefined
        if (entry) {
          return (
            <a className="link" href={`/tasks?goal=${entry.goal.id}`} title={entry.goal.title}>
              <span className="visually-hidden">Goal: </span>
              {truncateWords(entry.goal.title, 48)}
            </a>
          )
        }
        return <span className="muted">{startedByLabel(run)}</span>
      },
    },
    { key: 'started', header: 'Started', render: (run) => <Time iso={run.startedAt} /> },
    { key: 'duration', header: 'Duration', numeric: true, render: (run) => formatRunElapsed(run, now) },
  ]

  return (
    <>
      <div>
        <StatRow>
          <StatTile label="Recent runs" value={formatCount(runs.length)} href="/runs" />
          <StatTile label="Completed" value={formatCount(count('completed'))} href="/runs?status=completed" />
          <StatTile
            label="Waiting for a person"
            value={formatCount(count('waiting_approval') + count('waiting_input'))}
            href="/approvals"
          />
          <StatTile label="Failed" value={formatCount(count('failed'))} href="/runs?status=failed" />
        </StatRow>
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          {allLoaded ? 'Across every run so far.' : `Across the ${RUN_PAGE_SIZE} most recent runs.`} Select a figure
          to see those runs.
        </p>
      </div>

      <Card as="section">
        <Eyebrow as="h2">Live activity</Eyebrow>
        <DataTable
          columns={columns}
          rows={runs}
          getKey={(run) => run.id}
          getRowHref={(run) => `/runs/${run.id}`}
          getRowLabel={(run) => {
            const name = agents[run.agentId]?.name
            const started = formatRelative(run.startedAt, now)
            return name ? `Open the ${name} run started ${started}` : `Open the run started ${started}`
          }}
          caption={
            allLoaded ? 'Every agent run so far, newest first.' : `The ${RUN_PAGE_SIZE} most recent agent runs, newest first.`
          }
        />
        <p style={{ marginTop: 'var(--space-4)' }}>
          <a className="link" href="/runs">
            See every run
          </a>
        </p>
      </Card>
    </>
  )
}
