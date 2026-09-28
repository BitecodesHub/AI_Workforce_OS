import { useRef, useState } from 'react'
import type { FocusEvent as ReactFocusEvent } from 'react'
import { Card, Eyebrow, Tag } from '../ui'
import { categoryTone } from '../../lib/labels'
import type { Agent, ChatMessage } from '../../lib/queries'
import { routingModeLabel } from './chatModel'

/*
 * The routing receipt: who is on it, why, and a way to send the same request to someone else
 * instead - always available, not only when the coordinator could not decide on its own.
 */

function closeOnFocusOut(close: () => void) {
  return (event: ReactFocusEvent<HTMLDivElement>) => {
    const next = event.relatedTarget
    if (next instanceof Node && !event.currentTarget.contains(next)) close()
  }
}

export function RoutingCard({
  message,
  agentNames,
  onReroute,
  rerouting,
}: {
  message: ChatMessage
  agentNames: Record<string, Agent>
  onReroute: (agentId: string) => void
  rerouting: boolean
}) {
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement | null>(null)
  const detail = message.detail
  const agents = detail.agents ?? []
  const matched = detail.matched ?? []
  const alternatives = detail.alternatives ?? []
  const needsChoice = detail.needsChoice === true

  const others = Object.values(agentNames).filter(
    (agent) => agent.status === 'active' && !agents.some((chosen) => chosen.id === agent.id),
  )

  return (
    <Card as="article" className="chat-routing-card">
      <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', alignItems: 'baseline' }}>
        <Eyebrow as="h2">{routingModeLabel(detail.mode)}</Eyebrow>
      </div>

      {agents.length > 0 && (
        <ul className="chat-routing-agents">
          {agents.map((routed) => {
            const known = agentNames[routed.id]
            return (
              <li key={routed.id}>
                <Tag tone={categoryTone(known?.category)} withDot>
                  {routed.name}
                </Tag>
                {routed.instruction && <p className="caption muted chat-routing-instruction">{routed.instruction}</p>}
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

      {needsChoice && (
        <div className="chat-routing-choices">
          <p className="caption muted">Not sure who should take this. Choose someone:</p>
          <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap' }}>
            {(alternatives.length > 0 ? alternatives : others.map((agent) => ({ id: agent.id, name: agent.name, score: 0 }))).map(
              (candidate) => (
                <button
                  key={candidate.id}
                  type="button"
                  className="button button-outline button-sm"
                  disabled={rerouting}
                  onClick={() => onReroute(candidate.id)}
                >
                  {candidate.name}
                </button>
              ),
            )}
          </div>
        </div>
      )}

      {!needsChoice && others.length > 0 && (
        <div className="more-menu chat-routing-menu" ref={menuRef} onBlur={closeOnFocusOut(() => setMenuOpen(false))}>
          <button type="button" className="link chat-routing-menu-trigger" onClick={() => setMenuOpen((open) => !open)}>
            Send to someone else
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
                  <span className="menu-item-note">{agent.summary ?? ''}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </Card>
  )
}
