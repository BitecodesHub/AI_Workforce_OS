// @find: tests for swipe rules, swipe threshold, swipe up only from the grab handle, deck keyboard shortcuts, deck order, swipe label
// @what: Unit tests for lib/swipe.ts: when a drag counts, which way it points, what each key does and the order of the deck.
// @flow: Runs under vitest; imports lib/swipe.ts only
import { describe, expect, it } from 'vitest'
import {
  DEAD_ZONE_PX,
  SWIPE_MIN_PX,
  SWIPE_UP_PX,
  deckKeyAction,
  deckOrder,
  deckPosition,
  readSwipe,
  swipeLabel,
  swipeThreshold,
} from './swipe'

describe('swipeThreshold', () => {
  it('is a third of the card, never less than 96 px', () => {
    expect(swipeThreshold(240)).toBe(SWIPE_MIN_PX)
    // A card on a 375 px phone (343 px wide) needs 114 px.
    expect(swipeThreshold(343)).toBe(114)
    expect(swipeThreshold(720)).toBe(240)
    expect(swipeThreshold(0)).toBe(SWIPE_MIN_PX)
    expect(swipeThreshold(Number.NaN)).toBe(SWIPE_MIN_PX)
  })
})

describe('readSwipe', () => {
  it('ignores a tap or a tremor inside the dead zone', () => {
    expect(readSwipe(DEAD_ZONE_PX - 1, -3, 400)).toEqual({ direction: null, progress: 0, armed: false })
  })

  it('reads right as approve and arms only at the threshold', () => {
    const threshold = swipeThreshold(400)
    expect(readSwipe(threshold - 1, 5, 400)).toMatchObject({ direction: 'approve', armed: false })
    expect(readSwipe(threshold - 1, 5, 400).progress).toBeLessThan(1)
    expect(readSwipe(threshold, 5, 400)).toEqual({ direction: 'approve', progress: 1, armed: true })
    expect(readSwipe(threshold * 3, 5, 400).progress).toBe(1)
  })

  it('reads left as reject, with the same threshold', () => {
    const threshold = swipeThreshold(400)
    expect(readSwipe(-threshold + 1, 0, 400)).toMatchObject({ direction: 'reject', armed: false })
    expect(readSwipe(-threshold, 0, 400)).toMatchObject({ direction: 'reject', armed: true })
  })

  it('reads up from the grab handle as send back once it has gone 96 px, and down as nothing', () => {
    expect(readSwipe(4, -(SWIPE_UP_PX - 1), 400, true)).toMatchObject({ direction: 'feedback', armed: false })
    expect(readSwipe(4, -SWIPE_UP_PX, 400, true)).toMatchObject({ direction: 'feedback', armed: true })
    expect(readSwipe(4, 200, 400, true)).toEqual({ direction: null, progress: 0, armed: false })
  })

  it('leaves an upward drag anywhere else to the page, which is scrolling', () => {
    expect(readSwipe(4, -SWIPE_UP_PX * 2, 400)).toEqual({ direction: null, progress: 0, armed: false })
    expect(readSwipe(4, -SWIPE_UP_PX * 2, 400, false)).toEqual({ direction: null, progress: 0, armed: false })
    // A sideways swipe still counts wherever it starts.
    expect(readSwipe(200, -20, 400)).toMatchObject({ direction: 'approve', armed: true })
  })

  it('lets the axis that moved more decide', () => {
    expect(readSwipe(150, -120, 400, true).direction).toBe('approve')
    expect(readSwipe(90, -120, 400, true).direction).toBe('feedback')
  })
})

describe('swipeLabel', () => {
  it('names the action, then says to release once armed', () => {
    expect(swipeLabel('approve', true, false)).toBe('Approve')
    expect(swipeLabel('approve', true, true)).toBe('Release to approve')
    expect(swipeLabel('reject', true, true)).toBe('Release to reject')
    expect(swipeLabel('feedback', true, false)).toBe('Send back')
  })

  it('only ever skips a request the person cannot decide', () => {
    expect(swipeLabel('approve', false, false)).toBe('Skip')
    expect(swipeLabel('reject', false, true)).toBe('Release to skip')
  })
})

describe('deckKeyAction', () => {
  it('maps A, R, F, U and the arrows, in either case', () => {
    expect(deckKeyAction({ key: 'a' })).toBe('approve')
    expect(deckKeyAction({ key: 'A' })).toBe('approve')
    expect(deckKeyAction({ key: 'r' })).toBe('reject')
    expect(deckKeyAction({ key: 'F' })).toBe('feedback')
    expect(deckKeyAction({ key: 'u' })).toBe('undo')
    expect(deckKeyAction({ key: 'ArrowRight' })).toBe('next')
    expect(deckKeyAction({ key: 'ArrowLeft' })).toBe('previous')
    expect(deckKeyAction({ key: 'x' })).toBeNull()
  })

  it('leaves a key held with Cmd, Ctrl or Alt to the browser', () => {
    expect(deckKeyAction({ key: 'a', metaKey: true })).toBeNull()
    expect(deckKeyAction({ key: 'r', ctrlKey: true })).toBeNull()
    expect(deckKeyAction({ key: 'f', altKey: true })).toBeNull()
  })
})

describe('deckOrder and deckPosition', () => {
  const items = [
    { id: 'a', canDecide: false },
    { id: 'b', canDecide: true },
    { id: 'c', canDecide: false },
    { id: 'd', canDecide: true },
  ]

  it('puts what the person can decide first, keeping the queue order in each group', () => {
    expect(deckOrder(items).map((item) => item.id)).toEqual(['b', 'd', 'a', 'c'])
  })

  it('stays on the same request, else the one that took its place, else the last', () => {
    expect(deckPosition(['b', 'd', 'a'], 'd', 0)).toBe(1)
    expect(deckPosition(['b', 'a'], 'd', 1)).toBe(1)
    expect(deckPosition(['b'], null, 3)).toBe(0)
    expect(deckPosition([], 'b', 0)).toBe(-1)
  })
})
