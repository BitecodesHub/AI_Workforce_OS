import { CATEGORY_LABEL } from '../../lib/labels'
import { truncateWords } from '../../lib/format'
import type { Agent } from '../../lib/queries'
import { AgentAvatar } from './AgentAvatar'
import { describeAgent } from './chatModel'

/*
 * What an empty thread shows: a greeting, and the active agents as small chips. Choosing a chip
 * addresses the next message to that agent (it becomes a mention in the composer). What an agent
 * does sits in the chip's tooltip, written about the agent rather than to it.
 */
export function WelcomeScreen({ agents, onMention }: { agents: Agent[] | undefined; onMention?: (agent: Agent) => void }) {
  const active = (agents ?? []).filter((agent) => agent.status === 'active')

  return (
    <section className="chat-welcome" aria-labelledby="chat-welcome-title">
      <h2 id="chat-welcome-title" className="chat-welcome-title">
        What should your team work on?
      </h2>
      <p className="muted chat-welcome-lead">Ask in plain words below. The right agent, or agents, pick it up.</p>
      {active.length > 0 && (
        <ul className="chat-welcome-agents" aria-label="Agents you can ask">
          {active.map((agent) => {
            const category = CATEGORY_LABEL[agent.category] ?? agent.category
            const description = truncateWords(describeAgent(agent.summary, category), 120)
            return (
              <li key={agent.id}>
                <button
                  type="button"
                  className="chat-welcome-chip"
                  title={description}
                  aria-describedby={`chat-welcome-desc-${agent.id}`}
                  disabled={!onMention}
                  onClick={() => onMention?.(agent)}
                >
                  <AgentAvatar name={agent.name} category={agent.category} fallback={agent.fallback ?? false} size="sm" />
                  <span className="chat-welcome-chip-name">{agent.name}</span>
                </button>
                <span id={`chat-welcome-desc-${agent.id}`} className="visually-hidden">
                  {description}
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
