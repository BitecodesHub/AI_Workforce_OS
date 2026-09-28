import { useMemo } from 'react'
import { formatDateTime } from '../../lib/format'
import { statusLabel } from '../../lib/labels'
import type { Board, BoardTimelineEntry } from '../../lib/queries'
import { laneBarRect, laneTicks, laneWindow, pctOf } from './layout'

/*
 * One lane per agent, the last two hours, a bar for every run. It answers the question the flow
 * map cannot: not just who is busy now, but how the last little while actually went.
 */

function tickLabel(atMs: number): string {
  const date = new Date(atMs)
  const hours = date.getHours()
  const hour12 = hours % 12 === 0 ? 12 : hours % 12
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${hour12}:${minutes}`
}

function barClass(status: string): string {
  switch (status.toLowerCase()) {
    case 'running':
      return 'orc-bar orc-bar-running'
    case 'waiting_approval':
      return 'orc-bar orc-bar-waiting'
    case 'completed':
      return 'orc-bar orc-bar-completed'
    case 'failed':
      return 'orc-bar orc-bar-failed'
    default:
      return 'orc-bar orc-bar-cancelled'
  }
}

function barLabel(agentName: string, entry: BoardTimelineEntry): string {
  const started = formatDateTime(entry.startedAt)
  const status = statusLabel('run', entry.status).label.toLowerCase()
  return `${agentName}, ${status}, started ${started}`
}

export function Swimlanes({ board, now }: { board: Board; now: number }) {
  const window = useMemo(() => laneWindow(now), [now])
  const ticks = useMemo(() => laneTicks(window), [window])
  const agents = useMemo(() => [...board.agents].sort((a, b) => a.name.localeCompare(b.name)), [board.agents])
  const byAgent = useMemo(() => {
    const map = new Map<string, BoardTimelineEntry[]>()
    for (const entry of board.timeline) {
      const list = map.get(entry.agentId)
      if (list) list.push(entry)
      else map.set(entry.agentId, [entry])
    }
    return map
  }, [board.timeline])

  if (board.timeline.length === 0) {
    return <p className="caption muted">No runs in the last two hours.</p>
  }

  return (
    <div className="orc-swimlanes-scroll">
      <div className="orc-swimlanes">
        <div className="orc-lane-ticks">
          <span className="orc-lane-label" aria-hidden="true" />
          <div className="orc-lane-track">
            {ticks.map((tick) => (
              <span key={tick} className="orc-tick" style={{ left: `${pctOf(tick, window)}%` }}>
                <span className="orc-tick-label">{tickLabel(tick)}</span>
              </span>
            ))}
            <span className="orc-now-line" style={{ left: `${pctOf(now, window)}%` }}>
              <span className="orc-now-label">Now</span>
            </span>
          </div>
        </div>

        {agents.map((agent) => {
          const entries = byAgent.get(agent.id) ?? []
          return (
            <div key={agent.id} className="orc-lane">
              <span className="orc-lane-label" title={agent.name}>
                {agent.name}
              </span>
              <div className="orc-lane-track">
                {entries.map((entry) => {
                  const rect = laneBarRect(entry, window)
                  return (
                    <a
                      key={entry.runId}
                      href={`/runs/${entry.runId}`}
                      className={barClass(entry.status)}
                      style={{ left: `${rect.leftPct}%`, width: `${rect.widthPct}%` }}
                      aria-label={barLabel(agent.name, entry)}
                    />
                  )
                })}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
