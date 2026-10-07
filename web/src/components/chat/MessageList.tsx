import { memo, useMemo } from 'react'
import { formatDateTime } from '../../lib/format'
import type { Agent, BoardGoal, ChatMessage, Member, RunQuestion } from '../../lib/queries'
import type { useSpeaker } from '../../lib/voice'
import { MessageItem } from './MessageItem'
import { QuestionMessage } from './QuestionMessage'
import { lastAnswer, messageAuthor, orphanQuestions, withDayDividers } from './chatModel'
import { useRovingList } from './useRovingList'

/** The hidden heading an article opens with: "You, 10:42", "Priya Shah, 10:42", "Routing, 10:42". */
function articleLabel(message: ChatMessage, agentNames: Record<string, Agent>, memberNames: Record<string, Member>, me: string | null): string {
  const time = new Date(message.createdAt).toLocaleTimeString('en-AU', { hour: 'numeric', minute: '2-digit' })
  const who =
    message.kind === 'text'
      ? messageAuthor(message, me, memberNames).name
      : message.kind === 'answer'
        ? (message.agentId && agentNames[message.agentId]?.name) || 'An agent'
        : message.kind === 'routing'
          ? 'Routing'
          : message.kind === 'question'
            ? 'Question'
            : message.kind === 'error'
              ? 'Error'
              : 'Update'
  return `${who}, ${time}`
}

/*
 * The thread itself (B1.5): a day divider before the first message of each calendar day, then
 * every message dispatched to its own card by MessageItem, then any pending question that has no
 * message of its own yet (orphanQuestions). One roving-focus list spans every article, so the
 * thread is a single Tab stop the way the sidebar's conversation list is (B1.3).
 */

function MessageListInner({
  messages,
  goals,
  questions,
  agentNames,
  memberNames,
  me,
  speaker,
  freshIds,
  reroutingId,
  onReroute,
  onAskAgain,
  onEditAndResend,
  onReplyInOwnWords,
  onStop,
  onRetry,
  onAnswerFromDocuments,
  conversationId,
  agentsForReroute,
  answeringMessageId,
  composerTargetQuestionId,
  now,
}: {
  messages: ChatMessage[]
  goals: BoardGoal[]
  questions: RunQuestion[]
  agentNames: Record<string, Agent>
  memberNames: Record<string, Member>
  me: string | null
  speaker: ReturnType<typeof useSpeaker>
  freshIds: ReadonlySet<string>
  reroutingId: string | null
  onReroute: (messageId: string, agentId: string) => void
  onAskAgain: (goal: BoardGoal) => void
  onEditAndResend: (text: string) => void
  onReplyInOwnWords: (questionId: string) => void
  onStop: (goalId: string) => void
  onRetry: (goalId: string) => void
  onAnswerFromDocuments: (messageId: string) => void
  conversationId: string | null
  agentsForReroute: Agent[]
  answeringMessageId: string | null
  composerTargetQuestionId: string | null
  /**
   * Milliseconds since the epoch. Pass a number, not a new Date each render, so an unchanged clock
   * does not look like a changed prop; a Date is still read, for callers that have one.
   */
  now: number | Date
}) {
  const nowMs = typeof now === 'number' ? now : now.getTime()
  // A progress update draws its goal's card, and the thread's goals cover only the live window and
  // the work still going. An older update, loaded with earlier messages, would otherwise be an
  // empty article in the keyboard order; it is left out until its goal is known.
  const shown = useMemo(() => {
    const known = new Set(goals.map((goal) => goal.id))
    return messages.filter((message) => message.kind !== 'progress' || (message.goalId !== null && known.has(message.goalId)))
  }, [messages, goals])
  const entries = useMemo(() => withDayDividers(shown, new Date(nowMs)), [shown, nowMs])
  const goalById = useMemo(() => new Map(goals.map((goal) => [goal.id, goal])), [goals])
  const orphans = useMemo(() => orphanQuestions(questions, messages), [questions, messages])
  const articleIds = useMemo(
    () => [...shown.map((message) => message.id), ...orphans.map((question) => `orphan-${question.id}`)],
    [shown, orphans],
  )
  const roving = useRovingList(articleIds)
  const latestAnswerId = lastAnswer(messages)?.id ?? null

  return (
    <ol className="chat-messages">
      {entries.map((entry) =>
        entry.type === 'day' ? (
          <li key={entry.key} className="chat-day">
            <span>{entry.label}</span>
          </li>
        ) : (
          <li
            key={entry.message.id}
            id={`m-${entry.message.id}`}
            className="chat-item"
            data-kind={entry.message.kind}
            data-latest={entry.message.id === latestAnswerId || undefined}
          >
            <article
              aria-label={articleLabel(entry.message, agentNames, memberNames, me)}
              tabIndex={roving.activeId === entry.message.id ? 0 : -1}
              onKeyDown={roving.onKeyDown}
              style={{ outline: 'none' }}
            >
              <time
                className="chat-item-time caption"
                dateTime={entry.message.createdAt}
                title={formatDateTime(entry.message.createdAt)}
              >
                {new Date(entry.message.createdAt).toLocaleTimeString('en-AU', { hour: 'numeric', minute: '2-digit' })}
              </time>
              <MessageItem
                message={entry.message}
                grouped={entry.grouped}
                latest={entry.message.id === latestAnswerId}
                goal={entry.message.goalId ? goalById.get(entry.message.goalId) : undefined}
                questions={questions}
                messages={messages}
                agentNames={agentNames}
                memberNames={memberNames}
                me={me}
                speaker={speaker}
                freshIds={freshIds}
                conversationId={conversationId}
                agentsForReroute={agentsForReroute}
                reroutingId={reroutingId}
                answeringMessageId={answeringMessageId}
                composerTargetQuestionId={composerTargetQuestionId}
                onReroute={onReroute}
                onAskAgain={onAskAgain}
                onEditAndResend={onEditAndResend}
                onReplyInOwnWords={onReplyInOwnWords}
                onStop={onStop}
                onRetry={onRetry}
                onAnswerFromDocuments={onAnswerFromDocuments}
              />
            </article>
          </li>
        ),
      )}
      {orphans.map((question) => (
        <li key={question.id} className="chat-item" data-kind="question">
          <article tabIndex={roving.activeId === `orphan-${question.id}` ? 0 : -1} onKeyDown={roving.onKeyDown} style={{ outline: 'none' }}>
            <QuestionMessage
              question={question}
              {...(question.agentId && agentNames[question.agentId] ? { agent: agentNames[question.agentId] } : {})}
              onReplyInOwnWords={onReplyInOwnWords}
              answeringInComposer={question.id === composerTargetQuestionId}
              me={me}
              nameOf={(userId) => memberNames[userId]?.displayName ?? 'Someone in the workspace'}
            />
          </article>
        </li>
      ))}
    </ol>
  )
}

/**
 * Wrapped in React.memo: Chat re-renders on every poll, and a poll that changed nothing in the
 * thread hands this the same props. Chat passes the handlers through stable callbacks for this.
 */
export const MessageList = memo(MessageListInner)
