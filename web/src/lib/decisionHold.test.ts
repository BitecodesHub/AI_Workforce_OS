// @find: tests for undo window, decision hold, send after four seconds, undo cancels the send, next decision flushes, app put away sends, page hidden keepalive
// @what: Unit tests for lib/decisionHold.ts: a decision is sent only when its window closes or the next one is made, undo means it is never sent, and a hidden or closed page sends it at once as a request that outlives the page.
// @flow: Runs under vitest with fake timers; imports lib/decisionHold.ts only
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DecisionHold, UNDO_WINDOW_MS, useDecisionHold } from './decisionHold'
import type { HeldDecision, SendHow } from './decisionHold'

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

describe('useDecisionHold', () => {
  let sent: Array<{ item: string; how: SendHow }>
  const visibility = { state: 'visible' as DocumentVisibilityState }

  beforeEach(() => {
    vi.useFakeTimers()
    sent = []
    visibility.state = 'visible'
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility.state })
  })

  afterEach(() => {
    vi.useRealTimers()
    delete (document as unknown as { visibilityState?: unknown }).visibilityState
  })

  function mount() {
    return renderHook(() => useDecisionHold<string>((decision, how) => sent.push({ item: decision.item, how })))
  }

  it('sends at once, as a request that outlives the page, when the app is put away', () => {
    const { result } = mount()
    act(() => result.current.hold.hold({ item: 'approval-1', kind: 'approve' }))

    // Becoming visible again, or any other change, sends nothing.
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(sent).toEqual([])

    visibility.state = 'hidden'
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })

    expect(sent).toEqual([{ item: 'approval-1', how: { leaving: true } }])
    expect(result.current.held).toBeNull()
    act(() => vi.advanceTimersByTime(UNDO_WINDOW_MS * 2))
    expect(sent).toHaveLength(1)
  })

  it('sends at once, as a request that outlives the page, when the tab is closed', () => {
    const { result } = mount()
    act(() => result.current.hold.hold({ item: 'approval-1', kind: 'reject', note: 'No.' }))

    act(() => {
      window.dispatchEvent(new Event('pagehide'))
    })

    expect(sent).toEqual([{ item: 'approval-1', how: { leaving: true } }])
  })

  it('sends an ordinary request when the screen goes away inside the app, and nothing when nothing is held', () => {
    const first = mount()
    act(() => first.result.current.hold.hold({ item: 'approval-1', kind: 'approve' }))
    first.unmount()
    expect(sent).toEqual([{ item: 'approval-1', how: { leaving: false } }])

    const second = mount()
    visibility.state = 'hidden'
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    second.unmount()
    expect(sent).toHaveLength(1)
  })
})
