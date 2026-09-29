import { Card, Eyebrow } from '../ui'
import { CATEGORY_LABEL } from '../../lib/labels'
import { truncateWords } from '../../lib/format'
import type { Agent } from '../../lib/queries'
import { AgentAvatar } from './AgentAvatar'
import { suggestionChips } from './chatModel'

/** What an empty thread shows: who is here to help, and four things worth trying. */
export function WelcomeScreen({
  agents,
  can,
  onPick,
}: {
  agents: Agent[] | undefined
  can: (code: string) => boolean
  onPick: (text: string) => void
}) {
  const active = (agents ?? []).filter((agent) => agent.status === 'active')
  const chips = suggestionChips(agents ?? [], can)

  return (
    <div className="chat-welcome">
      <Card as="section">
        <Eyebrow as="h2">Your workforce</Eyebrow>
        <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
          Ask for work in plain words below. The right agent, or agents, pick it up.
        </p>
        {active.length > 0 && (
          <ul className="chat-welcome-agents">
            {active.map((agent) => (
              <li key={agent.id} className="chat-welcome-agent">
                <AgentAvatar name={agent.name} category={agent.category} />
                <span>
                  <span className="chat-welcome-agent-name">{agent.name}</span>
                  <span className="caption muted chat-welcome-agent-summary">
                    {agent.summary ? truncateWords(agent.summary, 90) : CATEGORY_LABEL[agent.category] ?? agent.category}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <div className="chat-suggestions">
        {chips.map((text: string) => (
          <button key={text} type="button" className="chat-suggestion-chip" onClick={() => onPick(text)}>
            {text}
          </button>
        ))}
      </div>
    </div>
  )
}
