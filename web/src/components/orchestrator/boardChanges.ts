// @find: board changes, what changed, live update, highlight changed cards, pending changes, updates paused, diffCards, countChanged, Orchestrator board, Freshness
// @what: Works out which board cards moved between one poll and the next so only they are highlighted.
// @flow: Used by Board.tsx and Freshness; reads cards from layout.ts buildBoardCards
import type { Board } from '../../lib/queries'
import { buildBoardCards } from './layout'
import type { BoardCard } from './layout'

/*
 * What changed on the board between one poll and the next, so a live update can highlight exactly
 * the cards that moved rather than flashing the whole page, and Freshness can say how many changes
 * are waiting behind a paused view (B2.2, B2.11).
 */

/** A card's own signature: which column it is in, which task it shows, and that task's status. */
function signatureOf(card: BoardCard): string {
  return `${card.column}|${card.task?.id ?? ''}|${card.task?.status ?? ''}`
}

/**
 * The ids of goals whose card signature changed between the two boards. A card present in only
 * one of the two (a goal that just appeared, or aged off the window) is not "changed" - it is new
 * or gone, and the inbox and the board's own empty states already say so.
 */
// @find: diff cards, changed card ids, highlight changed cards on live update
export function diffCards(previous: Board | null, next: Board): Set<string> {
  if (!previous) return new Set()
  const before = new Map(buildBoardCards(previous).map((card) => [card.id, signatureOf(card)]))
  const changed = new Set<string>()
  for (const card of buildBoardCards(next)) {
    const earlier = before.get(card.id)
    if (earlier !== undefined && earlier !== signatureOf(card)) changed.add(card.id)
  }
  return changed
}

/** How many cards changed - what Freshness reports as "N changes since" while updates are paused. */
// @find: count changed cards, pending changes behind paused view
export function countChanged(previous: Board | null, next: Board): number {
  return diffCards(previous, next).size
}
