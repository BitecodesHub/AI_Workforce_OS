import { useCallback } from 'react'
import type { MouseEvent } from 'react'
import { useLandingMotion } from './LandingRoot'
import { REVEAL_EVENT, type RevealDetail } from './revealEvent'

/*
 * Click handler for links to a section of this page, such as href="#roles".
 *
 * It scrolls to the target (smoothly unless the visitor reduces motion), records the hash in the
 * address bar, and moves keyboard focus to the target so the next Tab continues from there
 * rather than from the link. Scrolling is done here, never with scroll-behavior on html, because
 * that would also animate the router's scroll to the top of every new screen.
 *
 * Anything it cannot handle - a modified click, a missing target, an environment without
 * scrollIntoView - is left to the browser's own navigation.
 */

export function useInPageLink(): (event: MouseEvent<HTMLAnchorElement>) => void {
  const { reduced } = useLandingMotion()

  return useCallback(
    (event: MouseEvent<HTMLAnchorElement>) => {
      if (event.defaultPrevented || event.button !== 0) return
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return

      const href = event.currentTarget.getAttribute('href')
      if (!href || !href.startsWith('#') || href.length < 2) return

      const id = decodeURIComponent(href.slice(1))
      const target = document.getElementById(id)
      if (!target || typeof target.scrollIntoView !== 'function') return

      event.preventDefault()
      // A demo waits behind a tab. Naming it first lets its stage open the tab synchronously, so
      // the scroll below lands on a laid-out panel. Anything else ignores the event.
      window.dispatchEvent(new CustomEvent<RevealDetail>(REVEAL_EVENT, { detail: { id } }))
      target.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' })
      if (typeof window.history?.pushState === 'function') window.history.pushState(null, '', `#${id}`)

      // A target that cannot take focus, such as a demo tile, is made programmatically focusable
      // so focus still follows the scroll. It never enters the tab order.
      if (target.tabIndex < 0 && !target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1')
      target.focus({ preventScroll: true })
    },
    [reduced],
  )
}
