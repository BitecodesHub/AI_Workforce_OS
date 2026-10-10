// @find: routing receipt, who is on it, why this agent, send to someone else, reroute, hand off, routing decision, coordinator
// @what: Receipt showing which agent took the request, why, and a way to send it elsewhere.
// @flow: Used by MessageItem.
import { useContext, useRef, useState } from 'react'
import type { FocusEvent as ReactFocusEvent } from 'react'
import { Card, Eyebrow, Tag } from '../ui'
import { useCollapsed } from '../ui/Collapsible'
import { truncateWords } from '../../lib/format'
import { categoryTone } from '../../lib/labels'
import { canStopGoal } from '../../lib/goals'
import { isGoalActive } from '../../lib/queries'
import type { Agent, BoardGoal, ChatMessage } from '../../lib/queries'
import { can, profile } from '../../lib/session'
import { agentDescription, becauseText, routingModeLabel, routingSummary } from './chatModel'
import { DetailsContext } from './detailsContext'

/*
 * The routing receipt: who is on it, why, and a way to send the same request to someone else
 * instead - always available once the goal is no longer active for anyone but its owner (D-17).
 * Collapsed to one line once the goal has settled (B1.5), following the thread-wide Details mode.
 */

function closeOnFocusOut(close: () => void) {
  return (event: ReactFocusEvent<HTMLDivElement>) => {
    const next = event.relatedTarget
    if (next instanceof Node && !event.currentTarget.contains(next)) close()
  }
}

// @find: RoutingCard, routing card, routing receipt, who is on it, why this agent, send to someone else
export function RoutingCard({
  message,
  agentNames,
  goal,
  chosenName,
  rerouting,
  onReroute,
}: {
  message: ChatMessage
  agentNames: Record<string, Agent>
  goal?: BoardGoal
  /** Whether the goal already has an answer, or has finished (B1.5). */
  settled: boolean
  chosenName: string | null
  rerouting: boolean
  onReroute: (agentId: string) => void
}) {
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement | null>(null)
  const details = useContext(DetailsContext)
  const override = details.mode === 'auto' ? null : details.mode
  // A receipt, not a headline: one line saying who took the work, with the why one click away.
  // It starts folded whether or not the work has settled; the choice card below is the exception.
  const [open, setOpen] = useCollapsed(null, false, override, details.version)

  const detail = message.detail
  const agents = detail.agents ?? []
  const matched = detail.matched ?? []
  const alternatives = detail.alternatives ?? []
  const needsChoice = detail.needsChoice === true && !chosenName

  const others = Object.values(agentNames).filter(
    (agent) => agent.status === 'active' && !agents.some((chosen) => chosen.id === agent.id),
  )

  const me = profile()?.userId ?? null
  // D-17: reroute controls cancel active work, so they need the same authority as Stop.
  const canSendElsewhere = !goal || !isGoalActive(goal) || canStopGoal(goal, me, can)

  const body = (
    <>
      {agents.length > 0 && (
        <ul className="chat-routing-agents">
          {agents.map((routed) => {
            const known = agentNames[routed.id]
            return (
              <li key={routed.id}>
                <Tag tone={categoryTone(known?.category)} withDot>
                  {routed.name}
                </Tag>
                {agents.length > 1 && routed.instruction && (
                  <p className="caption muted chat-routing-instruction">{routed.instruction}</p>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {detail.reason && <p className="muted">{detail.reason}</p>}

      {matched.length > 0 && (
        <div className="chat-routing-matched">
          {matched.map((word) => (
            <span key={word} className="chat-routing-matched-chip">
              {word}
            </span>
          ))}
        </div>
      )}

      <div className="chat-routing-choices">
        <p className="caption muted">Not sure who should take this. Choose someone:</p>
        <div className="stack" style={{ gap: 'var(--space-2)' }}>
          {(alternatives.length > 0 ? alternatives : others.map((agent) => ({ id: agent.id, name: agent.name, score: 0 }))).map(
            (candidate) => (
              <button
                key={candidate.id}
                type="button"
                className="chat-choice"
                disabled={rerouting}
                onClick={() => onReroute(candidate.id)}
              >
                {candidate.name}
              </button>
            ),
          )}
        </div>
      </div>
    </>
  )

  if (needsChoice) {
    return (
      <Card as="article" className="chat-routing-card">
        <Eyebrow as="p">{routingModeLabel(detail.mode)}</Eyebrow>
        {body}
      </Card>
    )
  }

  const summary = routingSummary(message, agentNames)
  // A mention's line already says "as you asked"; its reason ("You mentioned @X") only repeats it.
  const reason = detail.reason && detail.mode !== 'mention' ? becauseText(detail.reason) : ''
  const reasonShort = truncateWords(reason, 70)
  const hasMore = reason.length > reasonShort.length || matched.length > 0 || agents.some((a) => agents.length > 1 && a.instruction)
  const canChange = !chosenName && canSendElsewhere && others.length > 0

  // One quiet line: who took it, why (cut short, whole on expand or hover), and a way to change it.
  return (
    <article className="chat-routeline" aria-label="Routing">
      <div className="chat-routeline-text">
        <span className="chat-routeline-who">{chosenName ? `You chose ${chosenName}` : summary}</span>
        {reason && !/using \d+ passage/.test(summary) && (
          <>
            <span aria-hidden="true"> · </span>
            {hasMore ? (
              <button
                type="button"
                className="chat-routeline-why"
                aria-expanded={open}
                title={reason}
                onClick={() => setOpen(!open)}
              >
                because {open ? 'of the reason below' : reasonShort}
              </button>
            ) : (
              <span title={reason}>because {reason}</span>
            )}
          </>
        )}
        {canChange && (
          <>
            <span aria-hidden="true"> · </span>
            <span className="more-menu chat-routing-menu" ref={menuRef} onBlur={closeOnFocusOut(() => setMenuOpen(false))}>
              <button
                type="button"
                className="link chat-routing-menu-trigger"
                aria-expanded={menuOpen}
                aria-haspopup="true"
                aria-label="Change who takes this request"
                onClick={() => setMenuOpen((v) => !v)}
              >
                Change
              </button>
              {menuOpen && (
                <div className="menu-panel" role="group" aria-label="Send to someone else">
                  {others.map((agent) => (
                    <button
                      key={agent.id}
                      type="button"
                      className="menu-item"
                      disabled={rerouting}
                      onClick={() => {
                        setMenuOpen(false)
                        onReroute(agent.id)
                      }}
                    >
                      <span className="menu-item-label">{agent.name}</span>
                      <span className="menu-item-note">{agentDescription(agent)}</span>
                    </button>
                  ))}
                </div>
              )}
            </span>
          </>
        )}
      </div>
      {open && (
        <div className="chat-routeline-more">
          {detail.reason && <p>{detail.reason}</p>}
          {matched.length > 0 && (
            <div className="chat-routing-matched">
              {matched.map((word) => (
                <span key={word} className="chat-routing-matched-chip">
                  {word}
                </span>
              ))}
            </div>
          )}
          {agents.length > 1 &&
            agents.map((routed) =>
              routed.instruction ? (
                <p key={routed.id} className="caption">
                  {routed.name}: {routed.instruction}
                </p>
              ) : null,
            )}
        </div>
      )}
    </article>
  )
}
