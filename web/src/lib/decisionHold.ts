// @find: undo decision, undo window, hold approval decision, four seconds undo, send after delay, flush decision, DecisionHold, useDecisionHold
// @what: Holds one decision for a few seconds before it is sent, so it can be undone; the next decision or leaving the screen sends it at once.
// @flow: Used by components/approvals/DecideDeck.tsx; tested in decisionHold.test.ts
import { useEffect, useState } from 'react'

/*
 * A swipe is quick, and so is a mistake. Each decision is held for a moment before it is sent:
 * the card leaves the deck at once, an Undo button stays for four seconds, and only when that
 * window closes is the request made. Deciding the next card sends the held one straight away,
 * so at most one decision is ever waiting, and leaving the screen sends it too - a person who
 * swiped Approve and closed the app meant it.
 */

/** How long a decision can be undone before it is sent, in milliseconds. */
export const UNDO_WINDOW_MS = 4_000

/** What can be decided in the Decide view. */
export type DecisionKind = 'approve' | 'reject' | 'feedback'

/** One decision waiting to be sent: the request, what was decided, and the note given with it. */
export type HeldDecision<T> = { item: T; kind: DecisionKind; note?: string }

/** The timer functions a hold uses, replaceable in tests. */
type Timers = {
  set: (callback: () => void, ms: number) => unknown
  clear: (handle: unknown) => void
}

const REAL_TIMERS: Timers = {
  set: (callback, ms) => window.setTimeout(callback, ms),
  clear: (handle) => window.clearTimeout(handle as number),
}

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
    private send: (decision: HeldDecision<T>) => void,
    private readonly onChange: (held: HeldDecision<T> | null) => void = () => {},
    private readonly windowMs: number = UNDO_WINDOW_MS,
    private readonly timers: Timers = REAL_TIMERS,
  ) {}

  /** The decision waiting to be sent, if any. */
  get held(): HeldDecision<T> | null {
    return this.current
  }

  /** Replaces what sending does, for a screen whose send function changes between renders. */
  setSender(send: (decision: HeldDecision<T>) => void): void {
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

  /** Sends the held decision now, if there is one. */
  flush(): void {
    const decision = this.current
    if (!decision) return
    this.stopTimer()
    this.current = null
    this.onChange(null)
    this.send(decision)
  }

  private stopTimer() {
    if (this.timer !== null) this.timers.clear(this.timer)
    this.timer = null
  }
}

// @find: use decision hold hook
/**
 * A DecisionHold for a screen: the held decision as state, and the hold flushed when the screen
 * goes away or the page is being closed. `send` may change between renders; the latest is used.
 */
export function useDecisionHold<T>(send: (decision: HeldDecision<T>) => void, windowMs: number = UNDO_WINDOW_MS) {
  const [held, setHeld] = useState<HeldDecision<T> | null>(null)
  const [hold] = useState(() => new DecisionHold<T>(send, setHeld, windowMs))
  useEffect(() => {
    hold.setSender(send)
  }, [hold, send])

  useEffect(() => {
    // A phone closing the tab fires pagehide, not unmount: the decision still goes.
    const onPageHide = () => hold.flush()
    window.addEventListener('pagehide', onPageHide)
    return () => {
      window.removeEventListener('pagehide', onPageHide)
      hold.flush()
    }
  }, [hold])

  return { held, hold }
}
