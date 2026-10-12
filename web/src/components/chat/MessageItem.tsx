// @find: thread item, message kind, render message, answer, question, routing, error, notice, schedule, progress, chat message dispatch
// @what: Picks the right component for each kind of thread item.
// @flow: Called by MessageList; renders AnswerBubble, UserBubble, ErrorCard, RoutingCard and others.
import { memo } from 'react'
import type { Agent, BoardGoal, ChatMessage, Member, QueuedMessage, RunQuestion } from '../../lib/queries'
import { isGoalActive } from '../../lib/queries'
import type { useSpeaker } from '../../lib/voice'
import { AnswerBubble } from './AnswerBubble'
import type { CardSend } from './AnswerBubble'
import { DocumentsCard } from './DocumentsCard'
import { ErrorCard } from './ErrorCard'
import { NoticeLine } from './NoticeLine'
import { ProgressCard } from './ProgressCard'
import { QuestionMessage } from './QuestionMessage'
import { RoutingCard } from './RoutingCard'
import { ScheduleCard } from './ScheduleCard'
import { UserBubble } from './UserBubble'
import { choiceMadeFor, goalHasAnswer, messageAuthor, resendText, routingMessageForGoal } from './chatModel'

/*
 * One thread item, dispatched by kind (B1.2, moved out of Chat.tsx). Wrapped in React.memo: a
 * conversation with a long history re-renders every item on each poll otherwise, for messages
 * whose own props never changed. It is handed its own goal rather than every goal in the thread,
 * so a goal's step count moving redraws that goal's cards and not the whole conversation.
 */

/** Props for one thread item: the message, whether it continues a run from the same author (grouped), whether it is the latest answer, its goal when known, the thread-wide questions, messages, agent and member lookups, the viewer id, the speaker (text to speech), ids of freshly arrived messages (announced to assistive technology) and the callbacks the cards raise (reroute, ask again, edit and resend, reply in own words, stop, retry, answer from documents). */
export type MessageItemProps = {
  message: ChatMessage
  grouped: boolean
  latest: boolean
  /** The goal this message belongs to, when the thread knows it. */
  goal?: BoardGoal | undefined
  questions: RunQuestion[]
  messages: ChatMessage[]
  agentNames: Record<string, Agent>
  memberNames: Record<string, Member>
  me: string | null
  speaker: ReturnType<typeof useSpeaker>
  freshIds: ReadonlySet<string>
  conversationId: string | null
  agentsForReroute: Agent[]
  reroutingId: string | null
  answeringMessageId: string | null
  composerTargetQuestionId: string | null
  onReroute: (messageId: string, agentId: string) => void
  onAskAgain: (goal: BoardGoal) => void
  onEditAndResend: (text: string) => void
  onReplyInOwnWords: (questionId: string) => void
  onStop: (goalId: string) => void
  onRetry: (goalId: string) => void
  onAnswerFromDocuments: (messageId: string) => void
  /** Sends a request from an answer card to the agent that wrote the answer; null where the person cannot start work. */
  onCardSend?: ((request: CardSend) => Promise<boolean>) | null | undefined
  /** Messages waiting their turn, so a card whose request waits shows it as sent. */
  queued?: readonly QueuedMessage[] | undefined
}

/**
 * Switch over message.kind that renders the card for the item.
 * text: UserBubble for a person's message, otherwise NoticeLine. routing: RoutingCard (settled once the goal has an answer or is no longer active). documents: DocumentsCard. progress: ProgressCard for its goal, nothing when the goal is not known. answer: AnswerBubble. schedule_suggestion: ScheduleCard. error: ErrorCard with the request text found by resendText. question: QuestionMessage with the matching RunQuestion. notice: NoticeLine. An unknown kind renders nothing. Pure dispatch with no state; every action is passed through as a callback.
 */
function MessageItemInner({
  message,
  grouped,
  latest,
  goal,
  questions,
  messages,
  agentNames,
  memberNames,
  me,
  speaker,
  freshIds,
  conversationId,
  agentsForReroute,
  reroutingId,
  answeringMessageId,
  composerTargetQuestionId,
  onReroute,
  onAskAgain,
  onEditAndResend,
  onReplyInOwnWords,
  onStop,
  onRetry,
  onAnswerFromDocuments,
  onCardSend = null,
  queued,
}: MessageItemProps) {
  switch (message.kind) {
    case 'text': {
      const author = messageAuthor(message, me, memberNames)
      return message.authorKind === 'user' ? (
        <UserBubble message={message} grouped={grouped} author={author} onEditAndResend={onEditAndResend} />
      ) : (
        <NoticeLine message={message} />
      )
    }
    case 'routing': {
      const settled = goalHasAnswer(message.goalId ?? '', messages) || (goal ? !isGoalActive(goal) : false)
      return (
        <RoutingCard
          message={message}
          agentNames={agentNames}
          {...(goal ? { goal } : {})}
          settled={settled}
          chosenName={choiceMadeFor(message.id, messages)}
          rerouting={reroutingId === message.id}
          onReroute={(agentId) => onReroute(message.id, agentId)}
        />
      )
    }
    case 'documents': {
      const started = messages.find((candidate) => candidate.detail.fromDocumentsMessageId === message.id)
      return (
        <DocumentsCard
          message={message}
          onAnswerFromDocuments={() => onAnswerFromDocuments(message.id)}
          answering={answeringMessageId === message.id}
          answerStartedMessageId={started?.id ?? null}
        />
      )
    }
    case 'progress':
      return goal ? (
        <ProgressCard goal={goal} agentNames={agentNames} me={me} questions={questions} onStop={onStop} onRetry={onRetry} />
      ) : null
    case 'answer':
      return (
        <AnswerBubble
          message={message}
          messages={messages}
          agent={message.agentId ? agentNames[message.agentId] : undefined}
          speaker={speaker}
          grouped={grouped}
          latest={latest}
          {...(goal ? { goal } : {})}
          agentsForReroute={agentsForReroute}
          conversationId={conversationId}
          onSendTo={(agentId) => onReroute(message.id, agentId)}
          onAskAgain={() => {
            if (goal) onAskAgain(goal)
          }}
          onCardSend={onCardSend}
          me={me}
          queued={queued}
        />
      )
    case 'schedule_suggestion':
      return <ScheduleCard message={message} />
    case 'error': {
      const routingMessageId = goal ? routingMessageForGoal(goal.id, messages)?.id : undefined
      return (
        <ErrorCard
          message={message}
          live={freshIds.has(message.id)}
          {...(goal ? { goal } : {})}
          {...(routingMessageId ? { routingMessageId } : {})}
          requestText={resendText(message, messages)}
          agentsForReroute={agentsForReroute}
          onRetry={onRetry}
          onResend={onEditAndResend}
          onReroute={onReroute}
        />
      )
    }
    case 'question': {
      const question = message.detail.questionId ? questions.find((q) => q.id === message.detail.questionId) : undefined
      const agent = question?.agentId ? agentNames[question.agentId] : undefined
      return (
        <QuestionMessage
          message={message}
          {...(question ? { question } : {})}
          {...(agent ? { agent } : {})}
          onReplyInOwnWords={onReplyInOwnWords}
          answeringInComposer={Boolean(question && question.id === composerTargetQuestionId)}
          me={me}
          nameOf={(userId) => memberNames[userId]?.displayName ?? 'Someone in the workspace'}
        />
      )
    }
    case 'notice':
      return <NoticeLine message={message} />
    default:
      return null
  }
}

// @find: MessageItem, message item, thread item, message kind, render message, answer
/** Memoised MessageItemInner, so a poll that changes nothing for this item does not redraw it. */
export const MessageItem = memo(MessageItemInner)
