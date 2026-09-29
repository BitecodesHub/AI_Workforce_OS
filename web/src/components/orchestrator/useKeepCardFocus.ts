import { useLayoutEffect, useRef } from 'react'
import type { RefObject } from 'react'

/*
 * Keeps keyboard focus on a card through a live update, even when the card moved column and is
 * therefore a new element the browser would otherwise drop focus from to <body> (B2.2).
 *
 * The focused card's id is tracked with plain `focusin`/`focusout` listeners, never read from a
 * ref during render (0.2); a `useLayoutEffect` keyed on the board's own data then restores focus
 * once the new cards have painted, and reports a column change so the caller can announce it.
 */
export function useKeepCardFocus({
  containerRef,
  columnOf,
  onMoved,
  version,
}: {
  containerRef: RefObject<HTMLElement | null>
  /** The column a card id is in right now, or undefined once the card is no longer on the board. */
  columnOf: (cardId: string) => string | undefined
  /** Called once focus has been restored to a card whose column changed since it was last focused. */
  onMoved?: (cardId: string, column: string) => void
  /** Anything that changes when the board's data changes, so the restore check runs after it renders. */
  version: unknown
}) {
  const focusedId = useRef<string | null>(null)
  const focusedColumn = useRef<string | null>(null)

  useLayoutEffect(() => {
    const container = containerRef.current
    if (!container) return

    const onFocusIn = (event: FocusEvent) => {
      const card = event.target instanceof Element ? event.target.closest('[data-card-id]') : null
      const id = card?.getAttribute('data-card-id') ?? null
      focusedId.current = id
      focusedColumn.current = id ? (columnOf(id) ?? null) : null
    }
    const onFocusOut = (event: FocusEvent) => {
      // A focus that lands nowhere (relatedTarget null) left the page entirely: nothing here is
      // still the thing a live update should try to refocus.
      if (event.relatedTarget === null) {
        focusedId.current = null
        focusedColumn.current = null
      }
    }

    container.addEventListener('focusin', onFocusIn)
    container.addEventListener('focusout', onFocusOut)
    return () => {
      container.removeEventListener('focusin', onFocusIn)
      container.removeEventListener('focusout', onFocusOut)
    }
  }, [containerRef, columnOf])

  useLayoutEffect(() => {
    const id = focusedId.current
    if (!id) return
    if (typeof document === 'undefined' || document.activeElement !== document.body) return
    const container = containerRef.current
    if (!container) return
    const selector = `[data-card-id="${typeof CSS !== 'undefined' && CSS.escape ? CSS.escape(id) : id}"]`
    const card = container.querySelector<HTMLElement>(selector)
    if (!card) return
    card.focus()
    const column = columnOf(id)
    if (column && column !== focusedColumn.current) {
      focusedColumn.current = column
      onMoved?.(id, column)
    }
    // `version` stands in for "the board just re-rendered", which is when a card that moved
    // column becomes a new element worth re-focusing.
  }, [version, containerRef, columnOf, onMoved])
}
