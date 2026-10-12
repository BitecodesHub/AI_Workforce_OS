// @find: answer actions, card actions, ask the agent from a card, send from card, AnswerActionsContext, useAnswerActions, chat link for cards
// @what: The context that tells answer cards what they may do where they are shown: send a card's request to the agent, what was already sent from each card, and where the conversation is.
// @flow: Provided by components/ui/Markdown.tsx from its actions prop (set by AnswerBubble in Chat and AnswerCard on run pages); read by the cards in components/answers.
import { createContext, useContext } from 'react'
import type { CardReply, CardRequest } from '../../lib/answerBlocks'

/*
 * A card is drawn the same everywhere an agent's answer is shown, but what it may do depends on
 * where it is. In Chat, under an answer, a card can send the person's next message to the agent
 * that wrote it. On a run's page there is no conversation to send into, so the card says where to
 * go instead. Where the person's role cannot start work, there is no send at all.
 */

/** What the cards in one answer may do. Every field is optional; an empty object means read and copy only. */
export type AnswerActions = {
  /**
   * Sends a card's request (a sentence, and the card's content as data) as the person's next chat
   * message to the agent that wrote the answer, naming the card by `index`, its place among the
   * answer's cards. Resolves to whether it was sent. Absent where nothing can be sent.
   */
  send?: ((index: number, request: CardRequest) => Promise<boolean>) | undefined
  /** The agent's name, for wording such as "Ask HR to send it". */
  agentName?: string | undefined
  /** What was already sent from each card of this answer, by the card's place (cardRepliesFor). */
  replies?: ReadonlyMap<number, CardReply> | undefined
  /** Where the conversation this answer came from can be opened, for a card shown outside Chat. */
  chatHref?: string | undefined
  /** The person's role cannot start agents on work, so cards only read and copy. */
  readOnly?: boolean | undefined
}

/** The actions for the answer being rendered; an empty object outside any answer. */
export const AnswerActionsContext = createContext<AnswerActions>({})

// @find: useAnswerActions, card actions hook
/** Reads what the cards of the current answer may do. */
export function useAnswerActions(): AnswerActions {
  return useContext(AnswerActionsContext)
}
