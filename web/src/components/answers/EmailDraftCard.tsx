// @find: email draft card, edit email draft, ask the agent to send it, copy email, draft email in chat, EmailDraftCard, send email approval
// @what: An editable email draft from an agent's answer, with Copy and "Ask the agent to send it" (a chat message that still waits for approval).
// @flow: Rendered by AnswerBlockCard for an email-draft block; sends through AnswerActions.send (Chat's own send) with emailSendRequest, never from the browser.
import type { KeyboardEvent, RefObject } from 'react'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Button, Spinner, Textarea } from '../ui'
import { CopyButton } from '../ui/CopyButton'
import type { EmailDraftBlock } from '../../lib/answerBlocks'
import { LIMITS, emailSendRequest, emailText } from '../../lib/answerBlocks'
import { useAnswerActions } from './answerActions'
import { CardShell, ElsewhereNote } from './CardShell'

type Draft = Pick<EmailDraftBlock, 'to' | 'cc' | 'subject' | 'body'>
type Field = keyof Draft

/** Whether two drafts say exactly the same thing. */
function sameDraft(a: Draft, b: Draft): boolean {
  return a.to === b.to && a.cc === b.cc && a.subject === b.subject && a.body === b.body
}

/**
 * The draft a request from this card asked to have sent (its reply in AnswerActions.replies), when
 * the reply holds a whole draft; null otherwise. Read defensively: the content comes from the
 * server's record of the message.
 */
function sentDraft(content: Record<string, unknown> | undefined): Draft | null {
  if (!content) return null
  const { to, cc, subject, body } = content
  if (typeof to !== 'string' || typeof subject !== 'string' || typeof body !== 'string') return null
  return { to, cc: typeof cc === 'string' ? cc : '', subject, body }
}

/**
 * Grows a text box to show everything in it, re-measured whenever `value` changes, so no part of
 * a draft can sit out of sight while the person decides to send it. Side effects: sets the box's
 * inline height.
 */
function useFitToContent(ref: RefObject<HTMLTextAreaElement | null>, value: string): void {
  useLayoutEffect(() => {
    const box = ref.current
    if (!box) return
    box.style.height = 'auto'
    // scrollHeight is 0 where nothing is laid out (a test); the rows attribute then stands.
    if (box.scrollHeight > 0) box.style.height = `${box.scrollHeight + 2}px`
  }, [ref, value])
}

/** Enter in a one-line address box would start a new line in an email address; it does nothing instead. */
function noNewLine(event: KeyboardEvent<HTMLTextAreaElement>) {
  if (event.key === 'Enter') event.preventDefault()
}

/** What each field is called in a message about what is missing. */
const MISSING: Record<'to' | 'subject' | 'body', string> = {
  to: 'Add at least one recipient.',
  subject: 'Add a subject.',
  body: 'Write the message.',
}

// @find: EmailDraftCard, editable email draft card
/**
 * The draft as four fields the person can change: to, copy, subject and message. The address
 * fields wrap, so every recipient stays in view on a phone, and the message grows to show all of
 * it. Copy puts the edited email on the clipboard. "Ask <agent> to send it" sends the edited draft
 * to the same agent as the person's next chat message: a sentence naming the subject and the
 * recipients, with the draft itself as data (emailSendRequest), so nothing written in the draft can
 * act as the person's own instruction. The agent's run does the sending, so it waits for a person's
 * approval like any outbound action. Nothing is ever sent from the browser itself.
 *
 * After a request has gone, the button rests until the draft is changed again, so one click cannot
 * ask twice; a request already sent from this very card (AnswerActions.replies) is mentioned after a
 * reload, and the card then starts from the version that request asked to send. A missing field is said under it and focus moves to it; the status line says what
 * happened, and focus stays on the button or moves to that line, never to the page. Where the card
 * cannot send (a run's page, a role that cannot start work) the button is replaced by a note.
 */
export function EmailDraftCard({ block, index }: { block: EmailDraftBlock; index: number }) {
  const { send, agentName, replies } = useAnswerActions()
  const original: Draft = { to: block.to, cc: block.cc, subject: block.subject, body: block.body }
  // After a reload the card starts from the version the person last asked to send, so their edits
  // are not lost and "already asked to send this draft" describes what is shown; Undo goes back to
  // the agent's own draft.
  const [draft, setDraft] = useState<Draft>(() => sentDraft(replies?.get(index)?.content) ?? original)
  const [asked, setAsked] = useState<Draft | null>(null)
  const [state, setState] = useState<'idle' | 'sending' | 'failed'>('idle')
  const [errors, setErrors] = useState<Partial<Record<Field, string | undefined>>>({})
  const [checked, setChecked] = useState(0)
  const card = useRef<HTMLDivElement | null>(null)
  const status = useRef<HTMLParagraphElement | null>(null)
  const message = useRef<HTMLTextAreaElement | null>(null)
  const to = useRef<HTMLTextAreaElement | null>(null)
  const cc = useRef<HTMLTextAreaElement | null>(null)
  useFitToContent(message, draft.body)
  useFitToContent(to, draft.to)
  useFitToContent(cc, draft.cc)
  const edited = !sameDraft(draft, original)
  const alreadyAsked = asked !== null && sameDraft(asked, draft)
  const who = agentName ?? 'the agent'
  const reply = replies?.get(index)

  // After a check finds something missing, focus goes to the first field that says so.
  useEffect(() => {
    if (checked > 0) card.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
  }, [checked])

  const change = (field: Field) => (value: string) => {
    setDraft((current) => ({ ...current, [field]: value }))
    setErrors((current) => ({ ...current, [field]: undefined }))
    if (state === 'failed') setState('idle')
  }

  // Set before the first await, so a second click that comes before React has drawn the "sending"
  // state (two clicks in one task) cannot ask twice for the same draft.
  const inFlight = useRef(false)

  const ask = async () => {
    if (!send || state === 'sending' || alreadyAsked || inFlight.current) return
    const missing: Partial<Record<Field, string | undefined>> = {}
    if (!draft.to.trim()) missing.to = MISSING.to
    if (!draft.subject.trim()) missing.subject = MISSING.subject
    if (!draft.body.trim()) missing.body = MISSING.body
    setErrors(missing)
    if (Object.keys(missing).length > 0) {
      setChecked((count) => count + 1)
      return
    }
    setState('sending')
    inFlight.current = true
    const snapshot = { ...draft }
    const sent = await send(index, emailSendRequest(snapshot)).catch(() => false)
    inFlight.current = false
    if (sent) {
      setAsked(snapshot)
      setState('idle')
      // The button now rests; the line that says what happened takes focus.
      status.current?.focus()
    } else {
      setState('failed')
    }
  }

  const missingCount = Object.values(errors).filter(Boolean).length
  const note =
    state === 'failed'
      ? 'Your request could not be sent. Check the conversation and try again.'
      : missingCount > 0
        ? `The draft is not ready to send: ${Object.values(errors).filter(Boolean).join(' ')}`
        : state === 'sending'
          ? `Sending your request to ${who}.`
          : alreadyAsked
            ? `Sent to ${who} as your next message. The email goes only after a person approves it.`
            : reply
              ? `${reply.mine ? 'You' : 'Someone in this conversation'} already asked ${who} to send this draft${reply.queued ? '; the request waits its turn' : ''}. Asking again sends another request.`
              : 'Nothing is sent from here. Asking sends your edited draft to the agent, and the email waits for approval before it goes.'

  return (
    <CardShell kind="Email draft" title={draft.subject || block.subject}>
      <div className="answer-email-fields" ref={card}>
        <Textarea
          ref={to}
          label="To"
          rows={1}
          className="answer-address"
          value={draft.to}
          onChange={(event) => change('to')(event.target.value)}
          onKeyDown={noNewLine}
          error={errors.to}
          maxLength={LIMITS.recipientsText}
          inputMode="email"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          autoComplete="off"
        />
        <Textarea
          ref={cc}
          label="Cc"
          optional
          rows={1}
          className="answer-address"
          value={draft.cc}
          onChange={(event) => change('cc')(event.target.value)}
          onKeyDown={noNewLine}
          maxLength={LIMITS.recipientsText}
          inputMode="email"
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          autoComplete="off"
        />
        <Textarea
          label="Subject"
          rows={1}
          className="answer-address"
          value={draft.subject}
          onChange={(event) => change('subject')(event.target.value)}
          onKeyDown={noNewLine}
          error={errors.subject}
          maxLength={LIMITS.subject}
        />
        <Textarea
          ref={message}
          label="Message"
          value={draft.body}
          rows={Math.max(5, draft.body.split('\n').length + 1)}
          onChange={(event) => change('body')(event.target.value)}
          error={errors.body}
          maxLength={LIMITS.body}
        />
      </div>
      <div className="answer-card-actions">
        {send && (
          <Button
            variant="primary"
            className="button-sm"
            icon={state === 'sending' ? <Spinner /> : undefined}
            aria-busy={state === 'sending' || undefined}
            aria-disabled={state === 'sending' || alreadyAsked || undefined}
            onClick={() => void ask()}
          >
            {alreadyAsked ? `Asked ${who}` : `Ask ${who} to send it`}
          </Button>
        )}
        <CopyButton text={() => emailText(draft)} label="Copy email" variant="text" />
        {edited && (
          <button
            type="button"
            className="button button-quiet button-sm"
            onClick={() => {
              setDraft(original)
              setErrors({})
            }}
          >
            Undo my changes
          </button>
        )}
      </div>
      {send ? (
        <p className="answer-card-note" role="status" ref={status} tabIndex={-1}>
          {note}
        </p>
      ) : (
        <ElsewhereNote what="have this email sent" />
      )}
    </CardShell>
  )
}
