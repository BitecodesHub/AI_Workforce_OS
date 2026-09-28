import { useMemo, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { Tag } from '../ui'
import { truncateWords } from '../../lib/format'
import { CATEGORY_LABEL, categoryTone } from '../../lib/labels'
import { useMemberNames } from '../../lib/queries'
import type { Board, BoardAgent } from '../../lib/queries'
import { initials, profile } from '../../lib/session'
import { useReducedMotion } from '../../hooks/useReducedMotion'
import { FLOW_VIEWBOX, HUB, edgePath, flowEdges, ringLayout, stepLabel } from './layout'
import type { NodePoint } from './layout'
import { currentTaskFor, requesterLabel } from './shared'

/*
 * The coordinator at the centre, every agent on a ring around it, and a line for every goal
 * currently moving between them. It is the one screen that shows the workforce as a system rather
 * than a list: a glance says who is busy, who is waiting on a decision, and who is idle.
 */

type NodeStatus = 'running' | 'waiting' | 'paused' | 'idle'

function nodeStatus(agent: BoardAgent): NodeStatus {
  if (agent.status === 'paused') return 'paused'
  if (agent.runningRunIds.length > 0) return 'running'
  if (agent.waitingRunIds.length > 0) return 'waiting'
  return 'idle'
}

function nodeStatusLabel(status: NodeStatus): string {
  switch (status) {
    case 'running':
      return 'Working now'
    case 'waiting':
      return 'Waiting for approval'
    case 'paused':
      return 'Paused'
    default:
      return 'Idle'
  }
}

export function FlowMap({
  board,
  selectedAgentId,
  onSelectAgent,
}: {
  board: Board
  selectedAgentId: string | null
  onSelectAgent: (agentId: string | null) => void
}) {
  const reducedMotion = useReducedMotion()
  const members = useMemberNames()
  const currentUserId = profile()?.userId ?? null
  const [hoveredId, setHoveredId] = useState<string | null>(null)

  const agents = useMemo(() => [...board.agents].sort((a, b) => a.name.localeCompare(b.name)), [board.agents])
  const points = useMemo(() => ringLayout(agents.length), [agents.length])
  const pointByAgent = useMemo(() => {
    const map = new Map<string, NodePoint>()
    agents.forEach((agent, index) => map.set(agent.id, points[index]!))
    return map
  }, [agents, points])
  const edges = useMemo(() => flowEdges(board.goals), [board.goals])

  const activeId = hoveredId ?? selectedAgentId
  const activeAgent = activeId ? agents.find((agent) => agent.id === activeId) : undefined
  const activePoint = activeId ? pointByAgent.get(activeId) : undefined
  const activeWork = activeId ? currentTaskFor(board, activeId) : null

  const clearHover = (agentId: string) => setHoveredId((current) => (current === agentId ? null : current))
  const select = (agentId: string) => onSelectAgent(selectedAgentId === agentId ? null : agentId)
  const onNodeKeyDown = (event: KeyboardEvent<SVGGElement>, agentId: string) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      select(agentId)
    }
  }

  if (agents.length === 0) {
    return <p className="caption muted">No agents are set up in this workspace yet.</p>
  }

  return (
    <div className="orc-flowmap">
      <svg
        className="orc-flowmap-svg"
        viewBox={`0 0 ${FLOW_VIEWBOX.width} ${FLOW_VIEWBOX.height}`}
        role="img"
        aria-label="A live map of the coordinator and every agent, with lines for goals moving between them"
      >
        {edges.map((edge) => {
          const from = edge.fromAgentId ? pointByAgent.get(edge.fromAgentId) : HUB
          const to = pointByAgent.get(edge.toAgentId)
          if (!from || !to) return null
          const d = edgePath(from, to)
          const className = edge.active ? 'orc-edge orc-edge-active' : edge.completed ? 'orc-edge orc-edge-done' : 'orc-edge'
          return (
            <g key={edge.key}>
              <path d={d} className={className} />
              {edge.active && !reducedMotion && (
                <circle r="3.4" className="orc-edge-dot">
                  <animateMotion dur="1.6s" repeatCount="indefinite" path={d} />
                </circle>
              )}
            </g>
          )
        })}

        <g className="orc-hub" aria-hidden="true">
          <circle cx={HUB.x} cy={HUB.y} r="27" />
          <text x={HUB.x} y={HUB.y - 3} textAnchor="middle" className="orc-hub-label">
            Coordi-
          </text>
          <text x={HUB.x} y={HUB.y + 11} textAnchor="middle" className="orc-hub-label">
            nator
          </text>
        </g>

        {agents.map((agent) => {
          const point = pointByAgent.get(agent.id)
          if (!point) return null
          const status = nodeStatus(agent)
          return (
            <g
              key={agent.id}
              className="orc-node"
              data-status={status}
              data-category={agent.category}
              data-selected={selectedAgentId === agent.id || undefined}
              role="button"
              tabIndex={0}
              aria-pressed={selectedAgentId === agent.id}
              aria-label={`${agent.name}, ${nodeStatusLabel(status)}. Select to filter the board to this agent.`}
              transform={`translate(${point.x}, ${point.y})`}
              onClick={() => select(agent.id)}
              onKeyDown={(event) => onNodeKeyDown(event, agent.id)}
              onMouseEnter={() => setHoveredId(agent.id)}
              onMouseLeave={() => clearHover(agent.id)}
              onFocus={() => setHoveredId(agent.id)}
              onBlur={() => clearHover(agent.id)}
            >
              {status === 'running' && <circle r="25" className="orc-ring orc-ring-running" />}
              {status === 'waiting' && <circle r="25" className="orc-ring orc-ring-waiting" />}
              <circle r="20" className="orc-node-circle" />
              <text textAnchor="middle" y="4.5" className="orc-node-initials">
                {initials(agent.name)}
              </text>
              {status === 'paused' && (
                <g className="orc-pause-glyph">
                  <rect x="-5" y="-6.5" width="3.2" height="13" rx="1" />
                  <rect x="1.8" y="-6.5" width="3.2" height="13" rx="1" />
                </g>
              )}
              <text textAnchor="middle" y="38" className="orc-node-label">
                {truncateWords(agent.name, 14)}
              </text>
            </g>
          )
        })}
      </svg>

      {activeAgent && activePoint && (
        <div
          className="orc-tooltip"
          style={{
            left: `${(activePoint.x / FLOW_VIEWBOX.width) * 100}%`,
            top: `${(activePoint.y / FLOW_VIEWBOX.height) * 100}%`,
          }}
        >
          <p className="caption orc-tooltip-name">{activeAgent.name}</p>
          {activeWork ? (
            <>
              <p className="caption muted">{truncateWords(activeWork.task.title, 90)}</p>
              <p className="caption">
                {requesterLabel(activeWork.goal, members, currentUserId)}
                {' · '}
                {stepLabel(activeWork.goal, activeWork.task)}
              </p>
            </>
          ) : (
            <p className="caption muted">{nodeStatusLabel(nodeStatus(activeAgent))}</p>
          )}
        </div>
      )}

      <ul className="visually-hidden">
        {agents.map((agent) => {
          const work = currentTaskFor(board, agent.id)
          return (
            <li key={agent.id}>
              {agent.name}
              {': '}
              {work
                ? `working on "${work.task.title}" for ${requesterLabel(work.goal, members, currentUserId)}`
                : nodeStatusLabel(nodeStatus(agent))}
            </li>
          )
        })}
      </ul>

      <div className="orc-legend">
        {(['operations', 'engineering', 'growth', 'support'] as const).map((category) => (
          <span key={category} className="orc-legend-item">
            <Tag tone={categoryTone(category)} withDot>
              {CATEGORY_LABEL[category]}
            </Tag>
          </span>
        ))}
        <span className="orc-legend-item">
          <span className="orc-legend-ring orc-ring-running" aria-hidden="true" /> Running
        </span>
        <span className="orc-legend-item">
          <span className="orc-legend-ring orc-ring-waiting" aria-hidden="true" /> Waiting for approval
        </span>
        <span className="orc-legend-item">
          <span className="orc-legend-dot" aria-hidden="true" /> Held
        </span>
      </div>
    </div>
  )
}
