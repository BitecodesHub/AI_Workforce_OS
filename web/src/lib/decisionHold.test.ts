// @find: tests for undo window, decision hold, send after four seconds, undo cancels the send, next decision flushes
// @what: Unit tests for lib/decisionHold.ts: a decision is sent only when its window closes or the next one is made, and undo means it is never sent.
// @flow: Runs under vitest with fake timers; imports lib/decisionHold.ts only
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DecisionHold, UNDO_WINDOW_MS } from './decisionHold'
import type { HeldDecision } from './decisionHold'

describe('DecisionHold', () => {
  let sent: Array<HeldDecision<string>>
  let changes: Array<HeldDecision<string> | null>
  let hold: DecisionHold<string>

  beforeEach(() => {
    vi.useFakeTimers()
    sent = []
    changes = []
    hold = new DecisionHold<string>(
      (decision) => sent.push(decision),
      (held) => changes.push(held),
    )
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('sends nothing until the four-second window closes, then sends once', () => {
    hold.hold({ item: 'approval-1', kind: 'approve' })

    vi.advanceTimersByTime(UNDO_WINDOW_MS - 1)
    expect(sent).toEqual([])
    expect(hold.held).toEqual({ item: 'approval-1', kind: 'approve' })

    vi.advanceTimersByTime(1)
    expect(sent).toEqual([{ item: 'approval-1', kind: 'approve' }])
    expect(hold.held).toBeNull()

    vi.advanceTimersByTime(UNDO_WINDOW_MS * 3)
    expect(sent).toHaveLength(1)
  })

  it('never sends a decision that was undone', () => {
    hold.hold({ item: 'approval-1', kind: 'reject', note: 'Not this week.' })

    const back = hold.undo()
    vi.advanceTimersByTime(UNDO_WINDOW_MS * 2)

    expect(back).toEqual({ item: 'approval-1', kind: 'reject', note: 'Not this week.' })
    expect(sent).toEqual([])
    expect(hold.undo()).toBeNull()
    expect(changes).toEqual([{ item: 'approval-1', kind: 'reject', note: 'Not this week.' }, null])
  })

  it('sends the held decision at once when the next one is made, and holds the next', () => {
    hold.hold({ item: 'approval-1', kind: 'approve' })
    vi.advanceTimersByTime(1_000)

    hold.hold({ item: 'approval-2', kind: 'feedback', note: 'Softer tone.' })

    expect(sent).toEqual([{ item: 'approval-1', kind: 'approve' }])
    expect(hold.held?.item).toBe('approval-2')
    // The first one's timer is gone: nothing more is sent when it would have fired.
    vi.advanceTimersByTime(UNDO_WINDOW_MS - 1_000)
    expect(sent).toHaveLength(1)
    vi.advanceTimersByTime(1_000)
    expect(sent.map((decision) => decision.item)).toEqual(['approval-1', 'approval-2'])
  })

  it('sends on flush, as when the screen goes away, and a second flush does nothing', () => {
    hold.hold({ item: 'approval-1', kind: 'approve' })

    hold.flush()
    hold.flush()
    vi.advanceTimersByTime(UNDO_WINDOW_MS)

    expect(sent).toHaveLength(1)
  })

  it('uses the newest sender it was given', () => {
    const later: string[] = []
    hold.setSender((decision) => later.push(decision.item))

    hold.hold({ item: 'approval-9', kind: 'approve' })
    hold.flush()

    expect(sent).toEqual([])
    expect(later).toEqual(['approval-9'])
  })
})
