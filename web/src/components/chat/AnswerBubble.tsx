import { isSandboxStep } from '../run/traceModel'
import { IconButton, Tag } from '../ui'
import { useRunSteps } from '../../lib/queries'
import type { Agent, ChatMessage } from '../../lib/queries'
import type { useSpeaker } from '../../lib/voice'
import { AgentAvatar } from './AgentAvatar'

/**
 * Whether the run behind an answer used the offline sandbox model, mounted only once `runId` is
 * known so useRunSteps is never asked to fetch an empty path.
 */
function SandboxBadge({ runId }: { runId: string }) {
  // Fetched once, not polled: the run has already finished by the time an answer message exists.
  const stepsQuery = useRunSteps(runId, { active: false })
  const sandbox = (stepsQuery.data ?? []).some((step) => step.kind === 'model_call' && isSandboxStep(step))
  if (!sandbox) return null
  return <Tag tone="neutral">Offline sandbox model</Tag>
}

/** An agent's reply: its name, what it answered, whether a sandbox model stood in, and a way to hear it. */
export function AnswerBubble({
  message,
  agent,
  speaker,
  grouped,
}: {
  message: ChatMessage
  agent: Agent | undefined
  speaker: ReturnType<typeof useSpeaker>
  grouped: boolean
}) {
  const runId = message.detail.runId
  const name = agent?.name ?? 'An agent'
  const speaking = speaker.speaking

  return (
    <div className="chat-bubble-row chat-bubble-row-agent">
      {!grouped && <AgentAvatar name={name} category={agent?.category} />}
      <div className={`chat-bubble chat-bubble-agent${grouped ? ' chat-bubble-grouped' : ''}`}>
        {!grouped && (
          <div className="row chat-bubble-agent-name" style={{ gap: 'var(--space-2)', flexWrap: 'wrap' }}>
            <span>{name}</span>
            {runId && <SandboxBadge runId={runId} />}
          </div>
        )}
        <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', margin: 0 }}>{message.content}</p>
        <div className="row chat-bubble-agent-actions" style={{ gap: 'var(--space-3)' }}>
          {speaker.provider !== 'none' && (
            <IconButton
              label={speaking ? 'Stop reading aloud' : 'Read aloud'}
              aria-pressed={speaking}
              onClick={() => (speaking ? speaker.stop() : speaker.speak(message.content, message.agentId))}
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
        </div>
      </div>
    </div>
  )
}
