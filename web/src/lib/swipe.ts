// @find: swipe to decide, swipe threshold, swipe right approve, swipe left reject, swipe up feedback, decide deck keyboard shortcuts, deck order, read-only approvals skipped, swipe label
// @what: The rules of the Decide view as plain functions: how far a drag must go to count, what each key does, and the order of the deck.
// @flow: Used by components/approvals/DecideDeck.tsx; tested in swipe.test.ts

/*
 * Deciding by swipe, as rules a test can hold still.
 *
 * A drag only counts once it has gone far enough that it cannot be a scroll or a wobble: sideways,
 * a third of the card's width (and never less than 96 px); upwards, 96 px. Until then the card
 * follows the finger and says what letting go would do; past it, the label changes to "Release
 * to ..." so the person knows the decision is armed. A drag downwards does nothing, because that
 * is how a phone scrolls.
 */

/** What a swipe asks for: right approves, left rejects, up sends back with feedback. */
export type SwipeDirection = 'approve' | 'reject' | 'feedback'

/** Where a drag stands: which way it points, how far towards counting (0 to 1), and whether letting go now decides. */
export type SwipeReading = { direction: SwipeDirection | null; progress: number; armed: boolean }

/** The shortest sideways drag that counts, in pixels, however narrow the card. */
export const SWIPE_MIN_PX = 96
/** The share of the card's width a sideways drag must cover. */
export const SWIPE_WIDTH_SHARE = 1 / 3
/** The upward drag that counts, in pixels. */
export const SWIPE_UP_PX = 96
/** Movement smaller than this is a tap or a tremor, not a drag. */
export const DEAD_ZONE_PX = 10

const NOTHING: SwipeReading = { direction: null, progress: 0, armed: false }

// @find: swipe threshold for a card width
/** How far sideways a drag must go on a card this wide. */
export function swipeThreshold(cardWidth: number): number {
  const width = Number.isFinite(cardWidth) && cardWidth > 0 ? cardWidth : 0
  return Math.max(SWIPE_MIN_PX, Math.round(width * SWIPE_WIDTH_SHARE))
}

// @find: read a swipe, which way and how far
/**
 * Reads a drag of `dx`, `dy` pixels (right and down positive) on a card `cardWidth` wide. The axis
 * that moved more decides the direction; a downward drag, or one inside the dead zone, is nothing.
 */
export function readSwipe(dx: number, dy: number, cardWidth: number): SwipeReading {
  const across = Math.abs(dx)
  const up = -dy
  if (Math.max(across, Math.abs(dy)) < DEAD_ZONE_PX) return NOTHING
  if (across >= Math.abs(dy)) {
    const threshold = swipeThreshold(cardWidth)
    return { direction: dx > 0 ? 'approve' : 'reject', progress: Math.min(1, across / threshold), armed: across >= threshold }
  }
  if (up > 0) return { direction: 'feedback', progress: Math.min(1, up / SWIPE_UP_PX), armed: up >= SWIPE_UP_PX }
  return NOTHING
}

// @find: what the swipe label says while dragging
/**
 * What the card says while it is dragged: the action, then "Release to ..." once letting go would
 * do it. A card the person cannot decide is only ever skipped.
 */
export function swipeLabel(direction: SwipeDirection, canDecide: boolean, armed: boolean): string {
  if (!canDecide) return armed ? 'Release to skip' : 'Skip'
  switch (direction) {
    case 'approve':
      return armed ? 'Release to approve' : 'Approve'
    case 'reject':
      return armed ? 'Release to reject' : 'Reject'
    case 'feedback':
      return armed ? 'Release to send back' : 'Send back'
  }
}

/** What a key does in the Decide view. */
export type DeckKeyAction = 'approve' | 'reject' | 'feedback' | 'next' | 'previous' | 'undo'

// @find: decide keyboard shortcuts, A approve, R reject, F feedback, arrows
/**
 * A approves, R rejects, F sends back with feedback, the arrow keys move between requests and U
 * undoes the last decision while it can still be undone. A key held with Cmd, Ctrl or Alt is the
 * browser's, not the deck's.
 */
export function deckKeyAction(event: {
  key: string
  ctrlKey?: boolean
  metaKey?: boolean
  altKey?: boolean
}): DeckKeyAction | null {
  if (event.ctrlKey || event.metaKey || event.altKey) return null
  switch (event.key) {
    case 'a':
    case 'A':
      return 'approve'
    case 'r':
    case 'R':
      return 'reject'
    case 'f':
    case 'F':
      return 'feedback'
    case 'u':
    case 'U':
      return 'undo'
    case 'ArrowRight':
      return 'next'
    case 'ArrowLeft':
      return 'previous'
    default:
      return null
  }
}

// @find: deck order, decidable first, read-only last
/**
 * The deck: the requests this person can decide first, in the queue's own order (soonest to
 * expire), then the ones they can only read. Neither group is reordered.
 */
export function deckOrder<T extends { canDecide: boolean }>(items: readonly T[]): T[] {
  return [...items.filter((item) => item.canDecide), ...items.filter((item) => !item.canDecide)]
}

/**
 * The position to show after the deck changed: the same request when it is still there, else the
 * one that took its place, else the last. -1 for an empty deck.
 */
export function deckPosition(ids: readonly string[], currentId: string | null, lastIndex: number): number {
  if (ids.length === 0) return -1
  if (currentId !== null) {
    const at = ids.indexOf(currentId)
    if (at >= 0) return at
  }
  return Math.min(Math.max(lastIndex, 0), ids.length - 1)
}
