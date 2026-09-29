import { useMemo, useRef, useState } from 'react'
import type { KeyboardEvent, MouseEvent as ReactMouseEvent } from 'react'
import { Tag } from '../ui'
import { truncateWords } from '../../lib/format'
import { CATEGORY_LABEL, categoryTone } from '../../lib/labels'
import { useMemberNames } from '../../lib/queries'
import type { Board, BoardAgent } from '../../lib/queries'
import { initials, profile } from '../../lib/session'
import { useReducedMotion } from '../../hooks/useReducedMotion'
import { FLOW_VIEWBOX, HUB, edgePath, flowEdges, flowLabelMax, ringLayout, stepLabel, tooltipPlacement } from './layout'
import type { NodePoint } from './layout'
import { currentTaskFor, requesterLabel } from './shared'

/*
 * The coordinator at the centre, every agent on a ring around it, and a line for every goal
 * currently moving between them (B2.7). It is the one screen that shows the workforce as a system
 * rather than a list: a glance says who is busy, who is waiting on a decision, and who is idle.
 */

export type NodeStatus = 'running' | 'asking' | 'waiting' | 'paused' | 'retired' | 'idle'

/** The same status precedence and wording AgentsStrip's rows use, so a name reads the same way in
    both places: paused, then retired, then running, then asking, then waiting for approval. */
export function nodeStatus(agent: BoardAgent): NodeStatus {
  if (agent.status === 'paused') return 'paused'
  if (agent.status === 'retired') return 'retired'
  if (agent.runningRunIds.length > 0) return 'running'
  if (agent.askingRunIds.length > 0) return 'asking'
  if (agent.waitingRunIds.length > 0) return 'waiting'
  return 'idle'
}

export function nodeStatusLabel(status: NodeStatus): string {
  switch (status) {
    case 'running':
      return 'Working now'
    case 'asking':
      return 'Waiting for an answer'
    case 'waiting':
      return 'Waiting for approval'
    case 'paused':
      return 'Paused'
    case 'retired':
      return 'Retired'
    default:
      return 'Idle'
  }
}

const MOVE_KEYS = new Set(['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp', 'Home', 'End'])

export function FlowMap({
  board,
  selectedAgentId,
  onSelectAgent,
  onOpenGoal,
}: {
  board: Board
  selectedAgentId: string | null
  onSelectAgent: (agentId: string | null) => void
  onOpenGoal: (goalId: string) => void
}) {
  const reducedMotion = useReducedMotion()
  const members = useMemberNames()
  const currentUserId = profile()?.userId ?? null
  const [hoveredId, setHoveredId] = useState<string | null>(null)
  const [focusedId, setFocusedId] = useState<string | null>(null)
  const nodeRefs = useRef(new Map<string, SVGGElement>())

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
  const placement = activePoint ? tooltipPlacement(activePoint) : 'above'

  const clearHover = (agentId: string) => setHoveredId((current) => (current === agentId ? null : current))
  const select = (agentId: string) => onSelectAgent(selectedAgentId === agentId ? null : agentId)

  const moveFocus = (fromId: string, key: string) => {
    const index = agents.findIndex((agent) => agent.id === fromId)
    if (index === -1) return
    let nextIndex = index
    if (key === 'Home') nextIndex = 0
    else if (key === 'End') nextIndex = agents.length - 1
    else if (key === 'ArrowRight' || key === 'ArrowDown') nextIndex = (index + 1) % agents.length
    else if (key === 'ArrowLeft' || key === 'ArrowUp') nextIndex = (index - 1 + agents.length) % agents.length
    const next = agents[nextIndex]
    if (!next) return
    setFocusedId(next.id)
    nodeRefs.current.get(next.id)?.focus()
  }

  const onNodeKeyDown = (event: KeyboardEvent<SVGGElement>, agentId: string) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      select(agentId)
    } else if (event.key === 'Escape') {
      if (selectedAgentId) {
        event.preventDefault()
        onSelectAgent(null)
      }
    } else if (MOVE_KEYS.has(event.key)) {
      event.preventDefault()
      moveFocus(agentId, event.key)
    }
  }

  const onEdgeClick = (event: ReactMouseEvent, goalId: string) => {
    event.preventDefault()
    onOpenGoal(goalId)
  }

  if (agents.length === 0) {
    return <p className="caption muted">No agents are set up in this workspace yet.</p>
  }

  const labelMax = flowLabelMax(agents.length)
  const rovingId = focusedId && agents.some((agent) => agent.id === focusedId) ? focusedId : agents[0]!.id

  return (
    <div className="orc-flowmap">
      {/* On a narrow screen the ring is given a fixed floor width instead of shrinking to fit: a
          node scaled down to fit a phone's width falls under the 44px touch target a thumb needs,
          and its label becomes too small to read. Scrolling this canvas keeps every node a
          reliable size, the same trade-off the swimlanes below already make. */}
      <div className="orc-flowmap-scroll">
        <div className="orc-flowmap-canvas">
          <svg
            className="orc-flowmap-svg"
            viewBox={`0 0 ${FLOW_VIEWBOX.width} ${FLOW_VIEWBOX.height}`}
            // A group, not an image: the agent nodes inside are focusable buttons, and an image
            // role would hide them from assistive technology.
            role="group"
            aria-label="A live map of the coordinator and every agent, with lines for goals moving between them"
          >
            {edges.map((edge) => {
              const from = edge.fromAgentId ? pointByAgent.get(edge.fromAgentId) : HUB
              const to = pointByAgent.get(edge.toAgentId)
              if (!from || !to) return null
              const d = edgePath(from, to)
              const className = edge.active
                ? 'orc-edge orc-edge-active'
                : edge.completed
                  ? 'orc-edge orc-edge-done'
                  : 'orc-edge'
              return (
                <g key={edge.key}>
                  <path d={d} className={className} />
                  {/* A wider, invisible hit path: the visible stroke is too thin to click reliably. */}
                  <path
                    d={d}
                    className="orc-edge-hit"
                    onClick={(event) => onEdgeClick(event, edge.goalId)}
                  />
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
              const tooltipText = agent.fallback ? 'Default for requests no specialist matches' : agent.name
              return (
                <g
                  key={agent.id}
                  ref={(node) => {
                    if (node) nodeRefs.current.set(agent.id, node)
                    else nodeRefs.current.delete(agent.id)
                  }}
                  className="orc-node"
                  data-status={status}
                  data-category={agent.category}
                  data-fallback={agent.fallback || undefined}
                  data-selected={selectedAgentId === agent.id || undefined}
                  role="button"
                  tabIndex={agent.id === rovingId ? 0 : -1}
                  aria-pressed={selectedAgentId === agent.id}
                  aria-label={`${agent.name}, ${nodeStatusLabel(status)}. Select to filter the board to this agent.`}
                  transform={`translate(${point.x}, ${point.y})`}
                  onClick={() => select(agent.id)}
                  onKeyDown={(event) => onNodeKeyDown(event, agent.id)}
                  onFocus={() => {
                    setHoveredId(agent.id)
                    setFocusedId(agent.id)
                  }}
                  onMouseEnter={() => setHoveredId(agent.id)}
                  onMouseLeave={() => clearHover(agent.id)}
                  onBlur={() => clearHover(agent.id)}
                >
                  {/* A native tooltip: a mouse user gets the full name even before the custom one
                      below renders, at no cost to anyone else - aria-label already gives assistive
                      tech the full, untruncated name regardless. */}
                  <title>{tooltipText}</title>
                  {status === 'running' && <circle r="25" className="orc-ring orc-ring-running" />}
                  {status === 'asking' && <circle r="25" className="orc-ring orc-ring-asking" />}
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
                  {agent.queued > 0 && (
                    <g className="orc-node-badge" aria-hidden="true">
                      <circle cx="14" cy="-14" r="9" />
                      <text x="14" y="-11" textAnchor="middle">
                        {agent.queued > 9 ? '9+' : agent.queued}
                      </text>
                    </g>
                  )}
                  <text textAnchor="middle" y="38" className="orc-node-label">
                    {truncateWords(agent.name, labelMax)}
                  </text>
                </g>
              )
            })}
          </svg>

          {activeAgent && activePoint && (
            <div
              className="orc-tooltip"
              data-placement={placement}
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
        </div>
      </div>

      <ul className="visually-hidden">
        {agents.map((agent) => {
          const work = currentTaskFor(board, agent.id)
          return (
            <li key={agent.id}>
              {agent.name}
              {': '}
              {work ? (
                <button type="button" onClick={() => onOpenGoal(work.goal.id)}>
                  working on &quot;{work.task.title}&quot; for {requesterLabel(work.goal, members, currentUserId)}
                </button>
              ) : (
                nodeStatusLabel(nodeStatus(agent))
              )}
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
          <span className="orc-legend-ring orc-ring-asking" aria-hidden="true" /> Waiting for an answer
        </span>
        <span className="orc-legend-item">
          <span className="orc-legend-ring orc-ring-waiting" aria-hidden="true" /> Waiting for approval
        </span>
        <span className="orc-legend-item">
          <svg className="orc-legend-pause" width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
            <rect x="1" y="0" width="3" height="10" rx="1" />
            <rect x="6" y="0" width="3" height="10" rx="1" />
          </svg>
          Paused
        </span>
      </div>
    </div>
  )
}
