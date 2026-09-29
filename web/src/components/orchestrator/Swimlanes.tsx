import { useMemo, useState } from 'react'
import { Button, DataTable, StatusTag } from '../ui'
import type { Column } from '../ui'
import { formatCount, formatTimeIn } from '../../lib/format'
import { statusLabel } from '../../lib/labels'
import type { Board, BoardTimelineEntry } from '../../lib/queries'
import { useRouter } from '../../lib/router'
import { laneBarRect, laneTicks, laneWindow, pctOf } from './layout'

/*
 * One lane per agent, across the selected window, a bar for every run (B2.8). It answers the
 * question the flow map cannot: not just who is busy now, but how the window actually went.
 */

/** 15 minutes for an hour or two, an hour for six, three hours for a day or "today" - the same
    breakpoints layout.ts's tickStepFor uses for a BoardWindow, restated in plain minutes since
    this component is handed the window's length directly rather than its server code. */
function stepForMinutes(windowMinutes: number): number {
  if (windowMinutes <= 120) return 15 * 60 * 1000
  if (windowMinutes <= 360) return 60 * 60 * 1000
  return 3 * 60 * 60 * 1000
}

function barClass(status: string): string {
  switch (status.toLowerCase()) {
    case 'running':
      return 'orc-bar orc-bar-running'
    case 'waiting_approval':
      return 'orc-bar orc-bar-waiting'
    case 'waiting_input':
      return 'orc-bar orc-bar-asking'
    case 'completed':
      return 'orc-bar orc-bar-completed'
    case 'failed':
      return 'orc-bar orc-bar-failed'
    default:
      return 'orc-bar orc-bar-cancelled'
  }
}

function barLabel(agentName: string, entry: BoardTimelineEntry, timezone: string): string {
  const status = statusLabel('run', entry.status).label
  const started = formatTimeIn(Date.parse(entry.startedAt), timezone)
  const ended = entry.completedAt ? formatTimeIn(Date.parse(entry.completedAt), timezone) : 'now'
  return `${agentName} · ${status} · ${started} to ${ended}`
}

export function Swimlanes({
  board,
  now,
  windowMinutes,
  timezone,
  onOpenGoal,
}: {
  board: Board
  now: number
  windowMinutes: number
  timezone: string
  onOpenGoal: (goalId: string) => void
}) {
  const { navigate } = useRouter()
  const [showIdle, setShowIdle] = useState(false)
  const [asTable, setAsTable] = useState(false)

  const window = useMemo(() => laneWindow(now, windowMinutes), [now, windowMinutes])
  const ticks = useMemo(() => laneTicks(window, stepForMinutes(windowMinutes)), [window, windowMinutes])
  const agents = useMemo(() => [...board.agents].sort((a, b) => a.name.localeCompare(b.name)), [board.agents])
  const agentNames = useMemo(() => Object.fromEntries(board.agents.map((agent) => [agent.id, agent.name])), [board.agents])
  const byAgent = useMemo(() => {
    const map = new Map<string, BoardTimelineEntry[]>()
    for (const entry of board.timeline) {
      const list = map.get(entry.agentId)
      if (list) list.push(entry)
      else map.set(entry.agentId, [entry])
    }
    return map
  }, [board.timeline])

  const activeAgents = agents.filter((agent) => (byAgent.get(agent.id) ?? []).length > 0)
  const idleAgents = agents.filter((agent) => (byAgent.get(agent.id) ?? []).length === 0)
  const shownAgents = showIdle ? agents : activeAgents

  const goalTitle = (goalId: string | null) => (goalId ? (board.goals.find((goal) => goal.id === goalId)?.title ?? board.failedToday.find((goal) => goal.id === goalId)?.title ?? 'Goal') : 'Direct run')

  const columns: Column<BoardTimelineEntry>[] = [
    { key: 'agent', header: 'Agent', render: (entry) => agentNames[entry.agentId] ?? 'Unknown agent' },
    { key: 'status', header: 'Status', render: (entry) => <StatusTag kind="run" status={entry.status} /> },
    { key: 'started', header: 'Started', render: (entry) => formatTimeIn(Date.parse(entry.startedAt), timezone) },
    {
      key: 'ended',
      header: 'Ended',
      render: (entry) => (entry.completedAt ? formatTimeIn(Date.parse(entry.completedAt), timezone) : 'Still running'),
    },
    { key: 'goal', header: 'Goal', render: (entry) => goalTitle(entry.goalId) },
  ]

  if (board.timeline.length === 0) {
    return <p className="caption muted">{board.window === 'TODAY' ? 'No runs today yet.' : 'No runs in the last window.'}</p>
  }

  return (
    <div className="stack" style={{ gap: 'var(--space-4)' }}>
      <div className="row" style={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: 'var(--space-3)' }}>
        <p className="caption muted">Times in {timezone}</p>
        <Button variant="quiet" onClick={() => setAsTable((current) => !current)}>
          {asTable ? 'Show as lanes' : 'Show as a table'}
        </Button>
      </div>

      {asTable ? (
        <DataTable
          columns={columns}
          rows={[...board.timeline]}
          getKey={(entry) => entry.runId}
          caption="Every run in the selected window, by agent, status and time."
          getRowHref={(entry) => (entry.goalId ? `/orchestrator?goal=${entry.goalId}` : `/runs/${entry.runId}`)}
          onRowOpen={(entry) => (entry.goalId ? onOpenGoal(entry.goalId) : navigate(`/runs/${entry.runId}`))}
        />
      ) : (
        <div className="orc-swimlanes-scroll">
          <div className="orc-swimlanes">
            <div className="orc-lane-ticks">
              <span className="orc-lane-label" aria-hidden="true" />
              <div className="orc-lane-track">
                {ticks.map((tick) => (
                  <span key={tick} className="orc-tick" style={{ left: `${pctOf(tick, window)}%` }}>
                    <span className="orc-tick-label">{formatTimeIn(tick, timezone)}</span>
                  </span>
                ))}
                <span className="orc-now-line" style={{ left: `${pctOf(now, window)}%` }}>
                  <span className="orc-now-label">Now</span>
                </span>
              </div>
            </div>

            {shownAgents.map((agent) => {
              const entries = byAgent.get(agent.id) ?? []
              return (
                <div key={agent.id} className="orc-lane">
                  <span className="orc-lane-label" title={agent.name}>
                    {agent.name}
                  </span>
                  <div className="orc-lane-track">
                    {entries.map((entry) => {
                      const rect = laneBarRect(entry, window)
                      const label = barLabel(agent.name, entry, timezone)
                      return (
                        <a
                          key={entry.runId}
                          href={entry.goalId ? `/orchestrator?goal=${entry.goalId}` : `/runs/${entry.runId}`}
                          className={barClass(entry.status)}
                          // Held back from the right edge so a bar at its 24px floor stays in the lane.
                          style={{ left: `min(${rect.leftPct}%, calc(100% - 24px))`, width: `${rect.widthPct}%` }}
                          aria-label={label}
                          onClick={(event) => {
                            if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
                            if (!entry.goalId) return
                            event.preventDefault()
                            onOpenGoal(entry.goalId)
                          }}
                        >
                          <span className="orc-bar-tip">{label}</span>
                        </a>
                      )
                    })}
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {!showIdle && idleAgents.length > 0 && (
        <Button variant="quiet" onClick={() => setShowIdle(true)}>
          Show {formatCount(idleAgents.length)} idle agents
        </Button>
      )}

      <div className="orc-legend">
        <span className="orc-legend-item">
          <span className="orc-legend-swatch orc-bar-running" aria-hidden="true" /> Running
        </span>
        <span className="orc-legend-item">
          <span className="orc-legend-swatch orc-bar-waiting" aria-hidden="true" /> Waiting
        </span>
        <span className="orc-legend-item">
          <span className="orc-legend-swatch orc-bar-completed" aria-hidden="true" /> Completed
        </span>
        <span className="orc-legend-item">
          <span className="orc-legend-swatch orc-bar-failed" aria-hidden="true" /> Failed
        </span>
        <span className="orc-legend-item">
          <span className="orc-legend-swatch orc-bar-cancelled" aria-hidden="true" /> Cancelled
        </span>
      </div>
    </div>
  )
}
