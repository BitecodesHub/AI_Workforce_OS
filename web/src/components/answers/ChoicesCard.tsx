// @find: choices card, pick an option, answer buttons, choose next step, reply with a choice, ChoicesCard
// @what: A question from an agent's answer with two to four option buttons; a click sends the question with that option as the person's next message.
// @flow: Rendered by AnswerBlockCard for a choices block; sends through AnswerActions.send (choiceRequest); a choice already sent from this card (AnswerActions.replies) shows as made.
import { useId, useRef, useState } from 'react'
import type { ChoicesBlock } from '../../lib/answerBlocks'
import { choiceRequest } from '../../lib/answerBlocks'
import { useAnswerActions } from './answerActions'
import { CardShell, ElsewhereNote } from './CardShell'

// @find: ChoicesCard, option buttons card
/**
 * The question and its options. Where the card can send, each option is a button that sends the
 * question with that option ("My choice for "...": PDF") as the person's next message, naming this
 * card, so the agent reads it against this question even when newer answers came after it. The
 * chosen option stays marked (aria-pressed) and the others rest, so one question gets one answer. A
 * choice already sent from this very card - found by the card, never by matching words - shows as
 * made after a reload too, saying whether the viewer or someone else in the conversation made it.
 *
 * While a choice is being sent the buttons say so (aria-disabled) but keep focus, so a keyboard or
 * screen-reader user does not lose their place; the chosen button stays focusable afterwards.
 * Elsewhere the options are listed with a note on where to answer.
 */
export function ChoicesCard({ block, index }: { block: ChoicesBlock; index: number }) {
  const { send, replies } = useAnswerActions()
  const id = useId()
  const reply = replies?.get(index)
  const earlier = typeof reply?.content.option === 'string' && block.options.includes(reply.content.option) ? reply.content.option : null
  const [chosen, setChosen] = useState<string | null>(null)
  const [sending, setSending] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  const made = chosen ?? earlier
  const byOther = chosen === null && earlier !== null && reply?.mine === false

  // Set before the first await and cleared only when the send fails, so a second click that comes
  // before React has drawn the "sending" state (two clicks in one task) cannot send a second answer.
  const inFlight = useRef(false)

  const choose = async (option: string) => {
    if (!send || made || sending || inFlight.current) return
    inFlight.current = true
    setSending(option)
    setFailed(false)
    const sent = await send(index, choiceRequest(block.question, option)).catch(() => false)
    setSending(null)
    if (sent) setChosen(option)
    else {
      inFlight.current = false
      setFailed(true)
    }
  }

  const status = failed
    ? 'Your choice could not be sent. Try again.'
    : sending
      ? `Sending "${sending}".`
      : made
        ? `${byOther ? 'Someone in this conversation chose' : 'You chose'} "${made}".${chosen === null && reply?.queued ? ' It starts when the current answer finishes.' : ''}`
        : 'Your choice is sent with this question as your reply in this conversation.'

  return (
    <CardShell kind="Choose one">
      <p className="answer-choices-question" id={`${id}-question`}>
        {block.question}
      </p>
      {send ? (
        <div className="answer-choices" role="group" aria-labelledby={`${id}-question`}>
          {block.options.map((option) => (
            <button
              key={option}
              type="button"
              className="button button-outline button-sm answer-choice"
              aria-pressed={made === option}
              aria-busy={sending === option || undefined}
              aria-disabled={Boolean(sending) || undefined}
              disabled={Boolean(made && made !== option)}
              onClick={() => void choose(option)}
            >
              {option}
            </button>
          ))}
        </div>
      ) : (
        <ul className="answer-choices-list">
          {block.options.map((option) => (
            <li key={option}>{option}</li>
          ))}
        </ul>
      )}
      {send ? (
        <p className="answer-card-note" role="status">
          {status}
        </p>
      ) : (
        <ElsewhereNote what="choose" />
      )}
    </CardShell>
  )
}
