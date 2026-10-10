// @find: chat error, agent failed, try again, retry, incomplete answer, step limit, output limit, error message, run failed
// @what: Plain-words error card in the thread with a Try again button and any partial answer.
// @flow: Used by MessageItem.
import { MenuButton } from '../ui/Menu'
import { Markdown } from '../ui/Markdown'
import { Notice } from '../ui'
import { sentenceCase } from '../../lib/format'
import type { Agent, BoardGoal, ChatMessage } from '../../lib/queries'
import { can, profile } from '../../lib/session'
import { canRetryGoal, errorHelp } from './chatModel'

/**
 * Something the coordinator or an agent could not do, said plainly, with a way to try again.
 *
 * "Try again" retries the goal when it can be retried; otherwise it puts the person's own request
 * back in the message box (`requestText`, found by chatModel's resendText). It never puts the
 * error's own sentence there, and when no request can be found it is not offered at all.
 *
 * An agent that stopped at its step or output limit still wrote something. The server puts that on
 * the message as `incompleteAnswer`, and it is offered here folded away and called what it is.
 */

// @find: incompleteAnswerOf, incomplete answer of, chat error, agent failed, try again, retry
/**
 * What the server kept of an answer the agent did not finish. The value is checked at run time
 * as well as typed, because the detail comes from the network.
 */
export function incompleteAnswerOf(message: ChatMessage): string | null {
  const value: unknown = message.detail.incompleteAnswer
  return typeof value === 'string' && value.trim() ? value : null
}

// @find: ErrorCard, error card, chat error, agent failed, try again, retry
export function ErrorCard({
  message,
  live,
  goal,
  routingMessageId,
  requestText,
  agentsForReroute,
  onRetry,
  onResend,
  onReroute,
}: {
  message: ChatMessage
  live: boolean
  goal?: BoardGoal
  routingMessageId?: string
  requestText: string | null
  agentsForReroute: Agent[]
  onRetry: (goalId: string) => void
  onResend: (text: string) => void
  onReroute: (messageId: string, agentId: string) => void
}) {
  const reason = message.detail.reason
  const help = errorHelp(message.detail.code)
  const me = profile()?.userId ?? null
  const retryable = goal ? canRetryGoal(goal, me, can) : false
  const incomplete = incompleteAnswerOf(message)

  return (
    <Notice tone="warning" live={live}>
      <p style={{ margin: 0 }}>{message.content || (reason ? sentenceCase(reason) : 'Something went wrong with this request.')}</p>
      {incomplete && (
        <details style={{ marginTop: 'var(--space-3)' }}>
          <summary className="link" style={{ cursor: 'pointer' }}>
            Incomplete answer
          </summary>
          <p className="caption" style={{ margin: 'var(--space-2) 0 var(--space-3)' }}>
            The agent stopped before it finished, so this may be missing parts.
          </p>
          <Markdown text={incomplete} />
        </details>
      )}
      <div className="row" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-3)', flexWrap: 'wrap' }}>
        {goal && retryable ? (
          <button type="button" className="link" onClick={() => onRetry(goal.id)}>
            Try again
          </button>
        ) : requestText ? (
          <button type="button" className="link" onClick={() => onResend(requestText)}>
            Try again
          </button>
        ) : null}
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
