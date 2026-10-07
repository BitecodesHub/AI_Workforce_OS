import { useEffect, useRef, useState } from 'react'
import { Button, IconButton, Textarea } from '../ui'
import { REASON_MAX, useConversationRatings, useRateAnswer } from '../../lib/insightsQueries'
import { can } from '../../lib/session'

/*
 * A thumbs up or down on an agent's answer, and when it is a thumbs down, a chance to say why.
 *
 * The rating is saved the moment a thumb is pressed, so one that is never explained still counts;
 * the reasons - wrong, incomplete, did not follow instructions, or a few words of the person's
 * own - are offered after, and are optional. Pressing the chosen thumb again takes the rating back.
 * The thumbs show the saved state at once and go back if the service refuses it.
 *
 * Split in two so the thumbs sit in the answer's action bar and the reason form sits under it:
 * useAnswerRating holds the state, RatingButtons and RatingReason draw it.
 */

export const REASONS = [
  { id: 'wrong', label: 'Wrong' },
  { id: 'incomplete', label: 'Incomplete' },
  { id: 'instructions', label: 'Did not follow instructions' },
] as const

export type ReasonId = (typeof REASONS)[number]['id']

/**
 * The reason as it is saved: the chosen preset and the person's own words together, the words
 * alone when no preset was chosen, and null when there is neither. Cut at the length the service
 * keeps, at a word where it can.
 */
export function composeReason(preset: ReasonId | null, words: string): string | null {
  const label = REASONS.find((reason) => reason.id === preset)?.label ?? null
  const own = words.trim().replace(/\s+/g, ' ')
  const joined = label && own ? `${label}: ${own}` : (label ?? own)
  if (!joined) return null
  if (joined.length <= REASON_MAX) return joined
  const cut = joined.slice(0, REASON_MAX)
  const space = cut.lastIndexOf(' ')
  return (space > REASON_MAX * 0.6 ? cut.slice(0, space) : cut).trimEnd()
}

export type AnswerRatingState = {
  /** Whether the viewer may rate at all; nothing is drawn when they may not. */
  enabled: boolean
  rating: 1 | -1 | null
  /** The reason already saved with a thumbs down, if there is one. */
  reason: string | null
  reasonOpen: boolean
  saved: boolean
  failed: boolean
  /** The id of the thumbs-down button, so focus can return to it when the form closes. */
  downId: string
  vote: (rating: 1 | -1) => void
  sendReason: (preset: ReasonId | null, words: string) => void
  closeReason: () => void
}

/** Puts focus back on the thumbs-down button after its form closes, so a keyboard user keeps their place. */
function focusDown(id: string) {
  document.getElementById(id)?.focus()
}

export function useAnswerRating(conversationId: string | null, messageId: string): AnswerRatingState {
  const enabled = can('chat:use') && conversationId !== null
  const ratings = useConversationRatings(conversationId, { enabled })
  const rate = useRateAnswer(conversationId)
  const [reasonOpen, setReasonOpen] = useState(false)
  const [saved, setSaved] = useState(false)
  const [failed, setFailed] = useState(false)
  const downId = `rate-down-${messageId}`

  const current = ratings.data?.[messageId]

  const run = (input: { rating: 1 | -1 | null; reason?: string | null }, then?: () => void) => {
    setFailed(false)
    rate.mutate(
      { messageId, ...input },
      {
        onSuccess: then ?? (() => {}),
        onError: () => {
          setFailed(true)
          setReasonOpen(false)
        },
      },
    )
  }

  return {
    enabled,
    rating: current?.rating ?? null,
    reason: current?.reason ?? null,
    reasonOpen,
    saved,
    failed,
    downId,
    vote: (rating) => {
      setSaved(false)
      if (current?.rating === rating) {
        // The same thumb again takes the rating back.
        setReasonOpen(false)
        run({ rating: null })
        return
      }
      setReasonOpen(rating === -1)
      run({ rating, reason: null })
    },
    sendReason: (preset, words) => {
      const reason = composeReason(preset, words)
      if (reason === null) {
        setReasonOpen(false)
        focusDown(downId)
        return
      }
      run({ rating: -1, reason }, () => setSaved(true))
      setReasonOpen(false)
      focusDown(downId)
    },
    closeReason: () => {
      setReasonOpen(false)
      focusDown(downId)
    },
  }
}

function Thumb({ up }: { up: boolean }) {
  return (
    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <g transform={up ? undefined : 'rotate(180 8 8)'}>
        <path
          d="M5 7.2 7.4 2.4c.9 0 1.6.7 1.6 1.6v2.2h3.3c.8 0 1.4.8 1.2 1.6l-.9 4.4a1.5 1.5 0 0 1-1.5 1.2H5V7.2Z"
          stroke="currentColor"
          strokeWidth="1.3"
          strokeLinejoin="round"
        />
        <path d="M5 7.2H2.9v6.4H5" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
      </g>
    </svg>
  )
}

/** The two thumbs, for the answer's action bar. */
export function RatingButtons({ state }: { state: AnswerRatingState }) {
  if (!state.enabled) return null
  return (
    <>
      <IconButton
        label="Good answer"
        aria-pressed={state.rating === 1}
        onClick={() => state.vote(1)}
        style={state.rating === 1 ? { color: 'var(--blue)' } : undefined}
      >
        <Thumb up />
      </IconButton>
      <IconButton
        id={state.downId}
        label="Not a good answer"
        aria-pressed={state.rating === -1}
        onClick={() => state.vote(-1)}
        style={state.rating === -1 ? { color: 'var(--blue)' } : undefined}
      >
        <Thumb up={false} />
      </IconButton>
    </>
  )
}

/** What follows a thumbs down: the reasons to choose from, and a word of thanks or of failure. */
export function RatingReason({ state }: { state: AnswerRatingState }) {
  if (!state.enabled) return null

  if (state.failed) {
    return (
      <p className="caption" role="alert" style={{ margin: 'var(--space-2) 0 0' }}>
        Your rating was not saved. Try again.
      </p>
    )
  }
  // Mounted only while open, so each time starts with nothing chosen and nothing typed.
  if (state.reasonOpen) return <ReasonForm state={state} />
  if (state.saved && state.rating === -1) {
    return (
      <p className="caption" role="status" style={{ margin: 'var(--space-2) 0 0' }}>
        Thanks, your note was saved.
      </p>
    )
  }
  return null
}

function ReasonForm({ state }: { state: AnswerRatingState }) {
  const [preset, setPreset] = useState<ReasonId | null>(null)
  const [words, setWords] = useState('')
  const panel = useRef<HTMLDivElement | null>(null)

  // Opening the form moves focus into it, so a keyboard user who pressed the thumb lands on the
  // first reason, and a screen reader says what the group is for.
  useEffect(() => {
    panel.current?.querySelector('button')?.focus()
  }, [])

  return (
    <div
      ref={panel}
      role="group"
      aria-label="Why was this answer not good?"
      className="stack"
      style={{ gap: 'var(--space-3)', marginTop: 'var(--space-3)' }}
    >
      <p className="caption" style={{ margin: 0 }}>
        Thanks. What was wrong? You can skip this.
      </p>
      <div className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap' }}>
        {REASONS.map((reason) => (
          <Button
            key={reason.id}
            variant={preset === reason.id ? 'primary' : 'outline'}
            className="button-sm"
            aria-pressed={preset === reason.id}
            onClick={() => setPreset((current) => (current === reason.id ? null : reason.id))}
          >
            {reason.label}
          </Button>
        ))}
      </div>
      <Textarea
        label="Anything else"
        optional
        rows={2}
        maxLength={REASON_MAX}
        value={words}
        onChange={(event) => setWords(event.target.value)}
      />
      <div className="row" style={{ gap: 'var(--space-3)' }}>
        <Button className="button-sm" onClick={() => state.sendReason(preset, words)}>
          Send
        </Button>
        <Button variant="quiet" className="button-sm" onClick={state.closeReason}>
          Not now
        </Button>
      </div>
    </div>
  )
}
