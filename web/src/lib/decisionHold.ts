// @find: undo decision, undo window, hold approval decision, four seconds undo, send after delay, flush decision, app put away, page hidden, keepalive, DecisionHold, useDecisionHold
// @what: Holds one decision for a few seconds before it is sent, so it can be undone; the next decision, leaving the screen, or the app being put away or closed sends it at once.
// @flow: Used by components/approvals/DecideDeck.tsx; tested in decisionHold.test.ts
import { useEffect, useState } from 'react'

/*
 * A swipe is quick, and so is a mistake. Each decision is held for a moment before it is sent:
 * the card leaves the deck at once, an Undo button stays for four seconds, and only when that
 * window closes is the request made. Deciding the next card sends the held one straight away,
 * so at most one decision is ever waiting.
 *
 * A person who swiped Approve and then pressed Home or closed the tab meant it, so the held
 * decision is sent the moment the page is hidden (a phone putting the app away fires only that,
 * and may then freeze or discard the page) or closed. It goes as a keepalive request, which the
 * browser finishes even while the page unloads; an ordinary one would be cancelled with it.
 */

/** How long a decision can be undone before it is sent, in milliseconds. */
export const UNDO_WINDOW_MS = 4_000

/** What can be decided in the Decide view. */
export type DecisionKind = 'approve' | 'reject' | 'feedback'

/** One decision waiting to be sent: the request, what was decided, and the note given with it. */
export type HeldDecision<T> = { item: T; kind: DecisionKind; note?: string }

/** How a decision is being sent: `leaving` when the page is being hidden or closed, so the request must outlive it. */
export type SendHow = { leaving: boolean }

/** Sends one decision. */
export type DecisionSender<T> = (decision: HeldDecision<T>, how: SendHow) => void

/** The timer functions a hold uses, replaceable in tests. */
type Timers = {
  set: (callback: () => void, ms: number) => unknown
  clear: (handle: unknown) => void
}

const REAL_TIMERS: Timers = {
  set: (callback, ms) => window.setTimeout(callback, ms),
  clear: (handle) => window.clearTimeout(handle as number),
}

const STAYING: SendHow = { leaving: false }

// @find: decision hold, undo window logic
/**
 * At most one decision, held until its window closes. `send` is called exactly once for each
 * decision that is not undone: when its window closes, when another decision is held, or when the
 * hold is flushed. `onChange` hears every change to what is held.
 */
export class DecisionHold<T> {
  private current: HeldDecision<T> | null = null
  private timer: unknown = null

  constructor(
    private send: DecisionSender<T>,
    private readonly onChange: (held: HeldDecision<T> | null) => void = () => {},
    private readonly windowMs: number = UNDO_WINDOW_MS,
    private readonly timers: Timers = REAL_TIMERS,
  ) {}

  /** The decision waiting to be sent, if any. */
  get held(): HeldDecision<T> | null {
    return this.current
  }

  /** Replaces what sending does, for a screen whose send function changes between renders. */
  setSender(send: DecisionSender<T>): void {
    this.send = send
  }

  /** Holds a decision; one already held is sent first, without waiting for its window. */
  hold(decision: HeldDecision<T>): void {
    this.flush()
    this.current = decision
    this.timer = this.timers.set(() => this.flush(), this.windowMs)
    this.onChange(decision)
  }

  /** Takes the held decision back without sending it. Returns it, or null when nothing was held. */
  undo(): HeldDecision<T> | null {
    const decision = this.current
    if (!decision) return null
    this.stopTimer()
    this.current = null
    this.onChange(null)
    return decision
  }

  /**
   * Sends the held decision now, if there is one. `how.leaving` says the page is being hidden or
   * closed, so the sender makes a request that outlives it.
   */
  flush(how: SendHow = STAYING): void {
    const decision = this.current
    if (!decision) return
    this.stopTimer()
    this.current = null
    this.onChange(null)
    this.send(decision, how)
  }

  private stopTimer() {
    if (this.timer !== null) this.timers.clear(this.timer)
    this.timer = null
  }
}

// @find: use decision hold hook
/**
 * A DecisionHold for a screen: the held decision as state, and the hold flushed when the screen
 * goes away, and as a request that outlives the page when the page is hidden (the app put away,
 * another tab chosen) or closed. `send` may change between renders; the latest is used.
 */
export function useDecisionHold<T>(send: DecisionSender<T>, windowMs: number = UNDO_WINDOW_MS) {
  const [held, setHeld] = useState<HeldDecision<T> | null>(null)
  const [hold] = useState(() => new DecisionHold<T>(send, setHeld, windowMs))
  useEffect(() => {
    hold.setSender(send)
  }, [hold, send])

  useEffect(() => {
    const leaving = () => hold.flush({ leaving: true })
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') leaving()
    }
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('pagehide', leaving)
    return () => {
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('pagehide', leaving)
      hold.flush()
    }
  }, [hold])

  return { held, hold }
}
