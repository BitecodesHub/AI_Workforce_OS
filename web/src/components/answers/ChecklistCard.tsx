// @find: checklist card, tick items, onboarding checklist, to-do list in answer, tell the agent what is done, ChecklistCard
// @what: A checklist from an agent's answer that the person can tick; ticks change only this view unless the person tells the agent.
// @flow: Rendered by AnswerBlockCard for a checklist block; "Tell the agent what is done" sends through AnswerActions.send.
import { useId, useRef, useState } from 'react'
import { Button, Spinner } from '../ui'
import type { ChecklistBlock } from '../../lib/answerBlocks'
import { checklistUpdateRequest } from '../../lib/answerBlocks'
import { useAnswerActions } from './answerActions'
import { CardShell } from './CardShell'

// @find: ChecklistCard, tickable checklist card
/**
 * The items as checkboxes, starting as the agent marked them, with how many are done. Ticking
 * changes only what this person sees, and the card says so. When the ticks differ from the
 * agent's and the card can send, "Tell <agent> what is done" sends a sentence with the count as the
 * person's next message, with every item and its tick as data (checklistUpdateRequest); nothing
 * else leaves the page. The button goes once it has been used, so focus moves to the line that says
 * it was sent rather than falling to the page.
 */
export function ChecklistCard({ block, index }: { block: ChecklistBlock; index: number }) {
  const { send, agentName } = useAnswerActions()
  const id = useId()
  const status = useRef<HTMLParagraphElement | null>(null)
  const [done, setDone] = useState<boolean[]>(() => block.items.map((item) => item.done))
  const [sentFor, setSentFor] = useState<string | null>(null)
  const [state, setState] = useState<'idle' | 'sending' | 'failed'>('idle')
  const count = done.filter(Boolean).length
  const changed = done.some((value, index) => value !== block.items[index]!.done)
  const items = block.items.map((item, index) => ({ text: item.text, done: done[index]! }))
  const key = done.map((value) => (value ? '1' : '0')).join('')
  const who = agentName ?? 'the agent'

  // Set before the first await, so a second click that comes before React has drawn the "sending"
  // state (two clicks in one task) cannot send the same update twice.
  const inFlight = useRef(false)

  const tell = async () => {
    if (!send || state === 'sending' || inFlight.current) return
    inFlight.current = true
    setState('sending')
    const sent = await send(index, checklistUpdateRequest(block.title, items)).catch(() => false)
    inFlight.current = false
    setState(sent ? 'idle' : 'failed')
    if (sent) {
      setSentFor(key)
      status.current?.focus()
    }
  }

  return (
    <CardShell kind="Checklist" title={block.title}>
      <p className="answer-card-note" id={`${id}-progress`}>
        {count} of {block.items.length} done
      </p>
      <ul className="answer-checklist" aria-describedby={`${id}-progress`}>
        {block.items.map((item, index) => (
          <li key={index}>
            <label className="answer-check">
              <input
                type="checkbox"
                checked={done[index] ?? false}
                onChange={(event) => {
                  const checked = event.target.checked
                  setDone((current) => current.map((value, at) => (at === index ? checked : value)))
                  if (state === 'failed') setState('idle')
                }}
              />
              <span className={done[index] ? 'answer-check-done' : undefined}>{item.text}</span>
            </label>
          </li>
        ))}
      </ul>
      <p className="answer-card-note" role="status" ref={status} tabIndex={-1}>
        {state === 'failed'
          ? 'This could not be sent. Check the conversation and try again.'
          : sentFor === key
            ? `Sent to ${who} as your next message.`
            : send
              ? `Ticking an item changes only this view. ${who === 'the agent' ? 'The agent' : who} is not told unless you use the button below.`
              : 'Ticking an item changes only this view. The agent is not told.'}
      </p>
      {send && changed && sentFor !== key && (
        <div className="answer-card-actions">
          <Button
            variant="outline"
            className="button-sm"
            icon={state === 'sending' ? <Spinner /> : undefined}
            aria-busy={state === 'sending' || undefined}
            aria-disabled={state === 'sending' || undefined}
            onClick={() => void tell()}
          >
            Tell {who} what is done
          </Button>
        </div>
      )}
    </CardShell>
  )
}
