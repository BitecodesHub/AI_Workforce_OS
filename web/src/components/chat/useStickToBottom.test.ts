// @find: tests for useStickToBottom, stick to bottom, auto scroll, scroll to latest, thread scroll, jump to latest, new message scroll
// @what: Automated tests for useStickToBottom.
// @flow: Run with the web test runner; covers useStickToBottom.
import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useStickToBottom } from './useStickToBottom'

/*
 * The thread follows the newest message's position, not how many messages are loaded: a full live
 * window keeps the same length as new messages arrive, and earlier messages loaded above the
 * reader add to the length without anything new arriving.
 */

function scroller() {
  const listeners = new Set<() => void>()
  const el = {
    scrollHeight: 2_000,
    scrollTop: 1_600,
    clientHeight: 400,
    scrollTo: vi.fn((options: { top: number }) => {
      el.scrollTop = Math.max(0, options.top - el.clientHeight)
    }),
    addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
    /** Moves the reader and fires the scroll listeners, the way the browser would. */
    scrollToTop(top: number) {
      el.scrollTop = top
      for (const listener of listeners) listener()
    },
  }
  return el
}

function setup(newestPosition: number) {
  const el = scroller()
  const ref = { current: el as unknown as HTMLElement }
  const hook = renderHook(
    ({ newest }) => useStickToBottom(ref, { newestPosition: newest, resetKey: 'c1', reducedMotion: true }),
    { initialProps: { newest: newestPosition } },
  )
  return { el, ...hook }
}

describe('useStickToBottom', () => {
  it('follows new messages while the reader is at the bottom, even once the window is full', () => {
    const { el, rerender } = setup(250)
    el.scrollTo.mockClear()
    // A full 200-message window: the length stays the same, the newest position moves on.
    rerender({ newest: 251 })
    expect(el.scrollTo).toHaveBeenCalledTimes(1)
  })

  it('counts what arrived while the reader was scrolled up', () => {
    const { el, result, rerender } = setup(250)
    act(() => el.scrollToTop(100))
    expect(result.current.nearBottom).toBe(false)
    el.scrollTo.mockClear()

    rerender({ newest: 253 })
    expect(el.scrollTo).not.toHaveBeenCalled()
    expect(result.current.newCount).toBe(3)

    act(() => el.scrollToTop(1_600))
    expect(result.current.newCount).toBe(0)
  })

  it('does not treat earlier messages loaded above as new', () => {
    const { el, result, rerender } = setup(250)
    act(() => el.scrollToTop(100))
    el.scrollTo.mockClear()
    rerender({ newest: 250 })
    expect(result.current.newCount).toBe(0)
    expect(el.scrollTo).not.toHaveBeenCalled()
  })

  it('keeps the reader in place while content goes in above them', () => {
    const { el, result } = setup(250)
    el.scrollTop = 300
    act(() =>
      result.current.keepPosition(() => {
        el.scrollHeight = 2_600
      }),
    )
    expect(el.scrollTop).toBe(900)
  })
})
