import { MenuButton } from '../ui/Menu'
import { Notice } from '../ui'
import { sentenceCase } from '../../lib/format'
import type { Agent, BoardGoal, ChatMessage } from '../../lib/queries'
import { can, profile } from '../../lib/session'
import { canRetryGoal, errorHelp } from './chatModel'

/** Something the coordinator or an agent could not do, said plainly, with a way to try again. */
export function ErrorCard({
  message,
  live,
  goal,
  routingMessageId,
  agentsForReroute,
  onRetry,
  onResend,
  onReroute,
}: {
  message: ChatMessage
  live: boolean
  goal?: BoardGoal
  routingMessageId?: string
  agentsForReroute: Agent[]
  onRetry: (goalId: string) => void
  onResend: (text: string) => void
  onReroute: (messageId: string, agentId: string) => void
}) {
  const reason = message.detail.reason
  const help = errorHelp(message.detail.code)
  const me = profile()?.userId ?? null
  const retryable = goal ? canRetryGoal(goal, me, can) : false
  const requestText = message.detail.requestText ?? message.content

  return (
    <Notice tone="warning" live={live}>
      <p style={{ margin: 0 }}>{message.content || (reason ? sentenceCase(reason) : 'Something went wrong with this request.')}</p>
      <div className="row" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-3)', flexWrap: 'wrap' }}>
        {goal && retryable ? (
          <button type="button" className="link" onClick={() => onRetry(goal.id)}>
            Try again
          </button>
        ) : (
          <button type="button" className="link" onClick={() => onResend(requestText)}>
            Try again
          </button>
        )}
        {routingMessageId && agentsForReroute.length > 0 && (
          <MenuButton
            label="Choose another agent"
            trigger="link"
            text="Choose another agent"
            items={agentsForReroute.map((agent) => ({
              id: agent.id,
              label: agent.name,
              onSelect: () => onReroute(routingMessageId, agent.id),
            }))}
          />
        )}
        {message.detail.runId && (
          <a className="link caption" href={`/runs/${message.detail.runId}`}>
            Open trace
          </a>
        )}
        {help && (
          <span className="caption muted">
            {help.text} {help.href && <a href={help.href}>Go there</a>}
          </span>
        )}
      </div>
    </Notice>
  )
}
