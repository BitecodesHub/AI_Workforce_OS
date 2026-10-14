// @find: command map, home dashboard, overview, start page, team room glance, approvals waiting, week figures, recent runs, /, Command Map page
// @what: The signed-in home page: a one-screen overview that opens on the team room glance, then approvals waiting, this week's figures and recent runs.
// @flow: Routed from App.tsx at /; renders TeamRoomGlance; links onward to the Team room, Approvals and Runs
import { useState } from 'react'
import { Button, Card, DataTable, EmptyState, Eyebrow, Notice, StatRow, StatTile, StatusTag, Tag, Time } from '../components/ui'
import type { Column } from '../components/ui'
import { QueryState, EmptyIcon } from '../components/ui/QueryState'
import { TaskDialog } from '../components/ui/TaskDialog'
import { TeamRoomGlance } from '../components/team-room/TeamRoomGlance'
import { describeChange } from '../components/analytics/figures'
import { formatCount, formatMoney, formatRelativeTicked, formatRunElapsed, truncateWords } from '../lib/format'
import { useAgentInsights, useInsights } from '../lib/insightsQueries'
import { categoryTone, startedByLabel } from '../lib/labels'
import { useAgentNames, useAgents, useApprovals, useRuns, useTaskIndex } from '../lib/queries'
import type { Run } from '../lib/queries'
import { can } from '../lib/session'
import { useNow } from '../lib/useNow'

/*
 * The first screen after signing in.
 *
 * It answers three questions in order: what is my AI team doing right now (the team room glance,
 * which opens the screen), is anything waiting on me, and what have they been doing. The figures
 * cover the last seven days and say how they moved against the seven before; the live activity
 * under them is the latest few runs, with a link to all of them. Setup, model routing and the
 * getting-started tour live on their own pages (Settings and more), not here: the owner asked for
 * a shorter first screen on 13 Oct 2026.
 */

/** Runs shown under the figures. The Runs page has the rest, filterable and sortable. */
const LIVE_ACTIVITY_ROWS = 8

// @find: CommandMap component, home dashboard, overview page, team room glance, /
/**
 * The Command Map (route /, the first screen after signing in; no route permission, each part checks its own).
 * No visible title block: the screen opens on the team room glance, whose eyebrow and heading lead it, with a
 * visually hidden h1 so screen readers and focus on arrival still find the page. The glance carries the page's
 * one action, Give an agent a task (task:create), which opens the task dialog (it opens the new run or goal
 * itself). Then approvals waiting (approval:read) and the recent runs behind QueryState (permission run:read;
 * the empty state differs for people who can create tasks). A signed-out visit to "/" shows the public home
 * page instead (see App).
 */
export function CommandMap() {
  const runsQuery = useRuns()
  const [taskDialogOpen, setTaskDialogOpen] = useState(false)
  const canCreate = can('task:create')
  const giveTask = canCreate ? <Button onClick={() => setTaskDialogOpen(true)}>Give an agent a task</Button> : undefined

  return (
    <div className="page">
      <h1 className="visually-hidden">Command Map</h1>

      {/* No onSuccess: once the task starts, the dialog opens its run, or its goal if no run started. */}
      {canCreate && <TaskDialog open={taskDialogOpen} onClose={() => setTaskDialogOpen(false)} />}

      <div className="page-sections">
        <TeamRoomGlance eyebrow="Your workforce, live" action={giveTask} />
        <ApprovalsWaiting />

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
// @find: approvals waiting card, pending count
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
 * The week's figures with how they moved against the week before, from the same insights the
 * Analytics page reads. Somebody who can open Analytics gets goals, spend and the change in each;
 * somebody who can only read runs gets what the agents' own rows add up to, without the comparison
 * or the money. A failed or unreadable answer shows nothing rather than a row of zeros.
 */
// @find: this week figures, runs, cost, success
function WeekFigures() {
  const canAnalytics = can('analytics:read')
  const canRuns = can('run:read')
  const insights = useInsights('7d', { enabled: canAnalytics })
  const agents = useAgentInsights('7d', { enabled: canRuns && !canAnalytics })
  const approvals = useApprovals({ enabled: can('approval:read') })
  const waiting = approvals.data?.length ?? null

  // The figures count the whole workspace; the lists behind them leave out work from private
  // conversations this person is not part of, so a figure can be larger than its list.
  const privateNote = can('chat:read_all')
    ? ''
    : ' The figures include work from private chats you are not part of, which the lists do not show.'
  const caption = (
    <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
      The last 7 days{canAnalytics ? ', compared with the 7 days before' : ''}. Select a figure to see more.
      {privateNote}
    </p>
  )

  const waitingTile =
    waiting === null ? null : (
      <StatTile
        label="Approvals waiting"
        value={formatCount(waiting)}
        unit="right now"
        href={can('approval:read') ? '/approvals' : undefined}
      />
    )

  if (canAnalytics && insights.data) {
    const { goals, spend, deltas } = insights.data
    const note = (text: string) => (text ? { note: text } : {})
    return (
      <div>
        <StatRow>
          <StatTile
            label="Goals completed"
            value={formatCount(goals.completed)}
            href="/analytics?window=7d"
            {...note(describeChange(deltas.goalsCompleted, 'count', '7d'))}
          />
          <StatTile
            label="Goals failed"
            value={formatCount(goals.failed)}
            href="/analytics?window=7d"
            {...note(describeChange(deltas.goalsFailed, 'count', '7d'))}
          />
          <StatTile
            label="Spend"
            value={formatMoney(spend.total)}
            href="/analytics?window=7d#budget"
            {...note(describeChange(deltas.spend, 'money', '7d'))}
          />
          {waitingTile}
        </StatRow>
        {caption}
      </div>
    )
  }

  if (!canAnalytics && agents.data) {
    const rows = agents.data.agents
    const sum = (pick: (row: (typeof rows)[number]) => number) => rows.reduce((total, row) => total + pick(row), 0)
    return (
      <div>
        <StatRow>
          <StatTile label="Runs finished" value={formatCount(sum((row) => row.finishedRuns))} href="/runs" />
          <StatTile label="Completed" value={formatCount(sum((row) => row.completed))} href="/runs?status=completed" />
          <StatTile label="Failed" value={formatCount(sum((row) => row.failed))} href="/runs?status=failed" />
          {waitingTile}
        </StatRow>
        {caption}
      </div>
    )
  }

  return null
}

// @find: recent runs list on the home page
/**
 * The live activity list: the latest eight runs (the full list is on the Runs page) with agent, status, what started each run, start time and duration, refreshing its clock every 15 seconds. Task titles link to the goal when the person holds task:read. A link leads to all runs.
 */
function RecentRuns({ runs }: { runs: Run[] }) {
  const agentsQuery = useAgents()
  const agents = useAgentNames()
  // Every role can read tasks today; the check keeps a narrower custom role from a failed request.
  const taskIndex = useTaskIndex({ enabled: can('task:read') })
  // Running durations tick; everything else here changes only when the list refreshes.
  const now = useNow(15_000)

  const agentName = (run: Run) =>
    agents[run.agentId]?.name ?? (agentsQuery.isLoading ? 'Loading…' : 'Unknown agent')
  const shown = runs.slice(0, LIVE_ACTIVITY_ROWS)

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
      <WeekFigures />

      <Card as="section">
        <Eyebrow as="h2">Live activity</Eyebrow>
        <DataTable
          columns={columns}
          rows={shown}
          getKey={(run) => run.id}
          getRowHref={(run) => `/runs/${run.id}`}
          getRowLabel={(run) => {
            const name = agents[run.agentId]?.name
            const started = formatRelativeTicked(run.startedAt, now, 15_000)
            return name ? `Open the ${name} run started ${started}` : `Open the run started ${started}`
          }}
          caption={`The ${shown.length === 1 ? 'latest agent run' : `${shown.length} latest agent runs`}, newest first.`}
        />
        <p style={{ marginTop: 'var(--space-4)' }}>
          <a className="link" href="/runs">
            See all runs
          </a>
        </p>
      </Card>
    </>
  )
}
