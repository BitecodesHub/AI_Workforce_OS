import { CopyButton } from '../ui/CopyButton'
import { Markdown } from '../ui/Markdown'
import { MenuButton } from '../ui/Menu'
import { IconButton, Tag } from '../ui'
import type { Agent, BoardGoal, ChatMessage } from '../../lib/queries'
import type { useSpeaker } from '../../lib/voice'
import { AgentAvatar } from '../ui/AgentAvatar'

/**
 * An agent's reply: its name, whether a sandbox model stood in, the answer itself (as safe
 * Markdown) and its action bar - copy, read aloud, the full trace, and a menu to send the same
 * request elsewhere or ask again.
 */
export function AnswerBubble({
  message,
  agent,
  speaker,
  grouped,
  latest,
  agentsForReroute,
  conversationId,
  onSendTo,
  onAskAgain,
}: {
  message: ChatMessage
  agent: Agent | undefined
  speaker: ReturnType<typeof useSpeaker>
  grouped: boolean
  latest: boolean
  goal?: BoardGoal
  agentsForReroute: Agent[]
  conversationId: string | null
  onSendTo: (agentId: string) => void
  onAskAgain: () => void
}) {
  const runId = message.detail.runId
  const name = agent?.name ?? 'An agent'
  const speaking = speaker.speakingKey === message.id
  const others = agentsForReroute.filter((candidate) => candidate.id !== message.agentId).slice(0, 6)

  return (
    <div className="chat-bubble-row chat-bubble-row-agent">
      {!grouped && <AgentAvatar name={name} category={agent?.category} fallback={agent?.fallback ?? false} />}
      <div className={`chat-answer${grouped ? ' chat-bubble-grouped' : ''}`} data-latest={latest || undefined}>
        {!grouped && (
          <div className="row chat-answer-head" style={{ gap: 'var(--space-2)', flexWrap: 'wrap', alignItems: 'center' }}>
            <span className="chat-bubble-agent-name">{name}</span>
            <Tag tone="neutral">AI agent</Tag>
            {message.detail.sandbox === true && <Tag tone="neutral">Offline sandbox model</Tag>}
          </div>
        )}
        <Markdown text={message.content} />
        <div className="row chat-actions" style={{ gap: 'var(--space-3)' }}>
          <CopyButton text={message.content} label="Copy" />
          {speaker.provider !== 'none' && (
            <IconButton
              label={speaking ? 'Stop reading aloud' : 'Read aloud'}
              aria-pressed={speaking}
              onClick={() => (speaking ? speaker.stop() : speaker.speak(message.content, message.agentId, { force: true, key: message.id }))}
            >
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M3 6h2.3L9 3.2v9.6L5.3 10H3z" fill="currentColor" />
                <path d="M11.2 5.3a4 4 0 0 1 0 5.4" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
              </svg>
            </IconButton>
          )}
          {runId && (
            <a className="link caption" href={`/runs/${runId}`}>
              Open full trace
            </a>
          )}
          <MenuButton
            label="More actions"
            trigger="icon"
            align="end"
            icon={
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <circle cx="3.5" cy="8" r="1.4" fill="currentColor" />
                <circle cx="8" cy="8" r="1.4" fill="currentColor" />
                <circle cx="12.5" cy="8" r="1.4" fill="currentColor" />
              </svg>
            }
            items={[
              ...others.map((candidate) => ({
                id: `send-${candidate.id}`,
                label: `Send to ${candidate.name}`,
                onSelect: () => onSendTo(candidate.id),
              })),
              { id: 'ask-again', label: 'Ask again', onSelect: onAskAgain },
              {
                id: 'copy-link',
                label: 'Copy link to this message',
                onSelect: () =>
                  void navigator.clipboard?.writeText(`${window.location.origin}/chat?c=${conversationId ?? ''}#m-${message.id}`),
              },
            ]}
          />
        </div>
      </div>
    </div>
  )
}
