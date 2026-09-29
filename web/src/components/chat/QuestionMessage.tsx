import { QuestionCard } from '../run/QuestionCard'
import type { Agent, ChatMessage, RunQuestion } from '../../lib/queries'

/**
 * A question message in the thread: WP3's `QuestionCard`, wrapped so a pending one reads the
 * "reply in your own words" affordance into the composer instead of a form of its own here (D14).
 * `QuestionCard` already carries its own `id="question-<id>"` for the "Go to question" deep link,
 * so this wrapper adds no id of its own.
 */
export function QuestionMessage({
  message,
  question,
  agent,
  onReplyInOwnWords,
  answeringInComposer = false,
  me = null,
  nameOf,
}: {
  message?: ChatMessage
  question?: RunQuestion
  agent?: Agent
  onReplyInOwnWords: (questionId: string) => void
  answeringInComposer?: boolean
  me?: string | null
  nameOf?: (userId: string) => string
}) {
  if (!question) {
    return <p className="caption muted">{message?.content || 'This question is no longer available.'}</p>
  }
  return (
    <div className="chat-question">
      <QuestionCard
        question={question}
        agentName={agent?.name ?? 'The agent'}
        agentCategory={agent?.category}
        agentFallback={agent?.fallback}
        via="chat"
        headingLevel="h4"
        onReplyInOwnWords={() => onReplyInOwnWords(question.id)}
        answeringInComposer={answeringInComposer}
        me={me}
        nameOf={nameOf}
      />
    </div>
  )
}
