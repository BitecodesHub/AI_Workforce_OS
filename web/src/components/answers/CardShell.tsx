// @find: answer card shell, card frame, card heading, card kind label, open conversation note, CardShell, ElsewhereNote
// @what: The frame every answer card shares (kind label, a heading, body) and the note shown where a card cannot act.
// @flow: Used by EmailDraftCard, TableCard, ChartCard, ChecklistCard and ChoicesCard.
import type { ReactNode } from 'react'
import { useId } from 'react'
import { useAnswerActions } from './answerActions'

// @find: CardShell, answer card frame
/**
 * A card inside an answer: a bordered group with the kind as a small label and a heading - the
 * card's title, or its kind when it has none - then the card's own body. The heading is an h5,
 * Markdown's own heading floor, so heading navigation finds every card without a card ever
 * outranking the page; the group is named by that heading. A group rather than a region, so an
 * answer with several cards does not fill a screen reader's landmark list. Side effects: none.
 */
export function CardShell({ kind, title, children }: { kind: string; title?: string | undefined; children: ReactNode }) {
  const headingId = `${useId()}-heading`
  return (
    <div className="answer-card" role="group" aria-labelledby={headingId}>
      <div className="answer-card-head">
        {title ? (
          <>
            <p className="answer-card-kind" aria-hidden="true">
              {kind}
            </p>
            <h5 className="answer-card-title" id={headingId}>
              <span className="visually-hidden">{kind}: </span>
              {title}
            </h5>
          </>
        ) : (
          // With no title, the kind is the heading, drawn as the same small label.
          <h5 className="answer-card-kind" id={headingId}>
            {kind}
          </h5>
        )}
      </div>
      {children}
    </div>
  )
}

// @find: ElsewhereNote, cannot act here, open conversation in chat
/**
 * Said in place of a card's action where it cannot be taken here. For a role that cannot start
 * work (`readOnly`): that the card is there to read and copy. On a run's page from a conversation
 * the person may open (`chatHref`): `what` completes "To ..., reply to the agent in its
 * conversation." with a link to it. Anywhere else: "To ..., ask an agent in Chat." Side effects: none.
 */
export function ElsewhereNote({ what }: { what: string }) {
  const { chatHref, readOnly } = useAnswerActions()
  if (readOnly) return <p className="answer-card-note">Your role can read and copy this card, but cannot ask agents to act on it.</p>
  if (!chatHref) return <p className="answer-card-note">To {what}, ask an agent in Chat.</p>
  return (
    <p className="answer-card-note">
      To {what}, reply to the agent in its conversation.{' '}
      <a className="link" href={chatHref}>
        Open the conversation
      </a>
    </p>
  )
}
