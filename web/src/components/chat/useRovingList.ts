// @find: roving tabindex, keyboard navigation, arrow keys list, accessible list, focus list item
// @what: Roving-tabindex keyboard navigation for a vertical list.
// @flow: Used by ChatSidebar and MessageList.
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { useState } from 'react'

/*
 * Roving tabindex for a vertical list: the conversation sidebar (B1.3) and the thread's own
 * articles (B1.5). Exactly one row is a Tab stop at a time; ArrowUp/ArrowDown, Home and End move
 * which one that is. The list itself renders `tabIndex` from `activeId` (0 for it, -1 for every
 * other row) - this hook only tracks which id that is and reacts to the keys.
 */

// @find: nextRovingId, next roving id, roving tabindex, keyboard navigation, arrow keys list, accessible list
/** Where the roving focus moves to for one of the four navigation keys, or null for an empty list. */
export function nextRovingId(
  ids: readonly string[],
  activeId: string | null,
  key: 'ArrowUp' | 'ArrowDown' | 'Home' | 'End',
): string | null {
  if (ids.length === 0) return null
  const currentIndex = activeId ? ids.indexOf(activeId) : -1
  switch (key) {
    case 'ArrowDown':
      return ids[(currentIndex + 1 + ids.length) % ids.length]!
    case 'ArrowUp':
      return ids[(currentIndex - 1 + ids.length) % ids.length]!
    case 'Home':
      return ids[0]!
    case 'End':
      return ids[ids.length - 1]!
  }
}

const NAV_KEYS = new Set(['ArrowUp', 'ArrowDown', 'Home', 'End'])

export type RovingList = {
  ids: string[]
  activeId: string | null
  setActiveId: (id: string) => void
  onKeyDown: (event: ReactKeyboardEvent) => void
}

// @find: useRovingList, use roving list, roving tabindex, keyboard navigation, arrow keys list, accessible list
/**
 * `ids` in the order they are rendered. The active id defaults to the first one, and is adjusted
 * during render (the "previous value" pattern, 0.2) whenever the list's own ids change so it never
 * points at a row that no longer exists.
 */
export function useRovingList(ids: readonly string[]): RovingList {
  const [activeId, setActiveIdState] = useState<string | null>(ids[0] ?? null)
  const [seenIds, setSeenIds] = useState(ids)

  const changed = seenIds.length !== ids.length || seenIds.some((id, index) => id !== ids[index])
  if (changed) {
    setSeenIds(ids)
    if (activeId === null || !ids.includes(activeId)) setActiveIdState(ids[0] ?? null)
  }

  function setActiveId(id: string) {
    if (ids.includes(id)) setActiveIdState(id)
  }

  function onKeyDown(event: ReactKeyboardEvent) {
    // Only the row itself (not a button, link or field inside it) drives the roving focus: a
    // control's own Enter, Space or arrow-key behaviour must not be shadowed by this list (B1.5).
    if (event.target !== event.currentTarget) return
    if (!NAV_KEYS.has(event.key)) return
    const next = nextRovingId(ids, activeId, event.key as 'ArrowUp' | 'ArrowDown' | 'Home' | 'End')
    if (next === null) return
    event.preventDefault()
    setActiveIdState(next)
  }

  return { ids: [...ids], activeId, setActiveId, onKeyDown }
}
