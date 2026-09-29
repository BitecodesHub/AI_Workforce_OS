import { useEffect } from 'react'
import type { RefObject } from 'react'

/*
 * j/k and the arrow keys move focus between board cards (B2.5, B2.12). Bound directly on the
 * board's own container, so the keys are active only while focus is already inside it - a plain
 * `[data-card-id]` button reachable by Tab, never a page-wide listener that would steal j and k
 * from a search box or a textarea elsewhere on the page (WCAG 2.1.4).
 */
export function useBoardKeyboard(containerRef: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    const onKeyDown = (event: KeyboardEvent) => {
      const key = event.key
      const moves = key === 'j' || key === 'k' || key === 'ArrowDown' || key === 'ArrowUp' || key === 'Home' || key === 'End'
      if (!moves) return
      const target = event.target
      if (target instanceof HTMLElement && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)) {
        return
      }

      const cards = Array.from(container.querySelectorAll<HTMLElement>('[data-card-id]'))
      if (cards.length === 0) return
      const current = target instanceof HTMLElement ? target.closest<HTMLElement>('[data-card-id]') : null
      const index = current ? cards.indexOf(current) : -1

      let next: HTMLElement | undefined
      if (key === 'Home') next = cards[0]
      else if (key === 'End') next = cards[cards.length - 1]
      else if (key === 'j' || key === 'ArrowDown') next = cards[index === -1 ? 0 : Math.min(index + 1, cards.length - 1)]
      else next = cards[index === -1 ? 0 : Math.max(index - 1, 0)]

      if (next && next !== current) {
        event.preventDefault()
        next.focus()
      }
    }

    container.addEventListener('keydown', onKeyDown)
    return () => container.removeEventListener('keydown', onKeyDown)
  }, [containerRef])
}
