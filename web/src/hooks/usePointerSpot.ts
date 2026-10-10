// @find: pointer spot, mouse glow, hover light, spotlight, cursor follow, usePointerSpot hook
// @what: React hook that tracks the pointer over an element for a soft spotlight effect, off under reduced motion.
// @flow: Used by landing cards; calls useReducedMotion
import { useCallback } from 'react'
import type { RefCallback } from 'react'
import { useReducedMotion } from './useReducedMotion'

/*
 * Feeds the pointer position to the .lp-spot highlight as --mx and --my, in px from the element's
 * top left corner.
 *
 * It listens only where a fine pointer can hover and motion is allowed; on touch screens and under
 * reduced motion it attaches nothing at all. Writes are coalesced into one per animation frame,
 * and the element's box is measured inside that frame rather than on every pointer event.
 */

const FINE_POINTER = '(hover: hover) and (pointer: fine)'

// @find: usePointerSpot hook, hover spotlight
export function usePointerSpot<T extends HTMLElement>(): RefCallback<T> {
  const reduced = useReducedMotion()

  return useCallback(
    (node: T | null) => {
      if (!node || reduced) return undefined
      if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined
      if (!window.matchMedia(FINE_POINTER).matches) return undefined
      if (typeof requestAnimationFrame !== 'function') return undefined

      let frame = 0
      let clientX = 0
      let clientY = 0

      const write = () => {
        frame = 0
        const box = node.getBoundingClientRect()
        node.style.setProperty('--mx', `${Math.round(clientX - box.left)}px`)
        node.style.setProperty('--my', `${Math.round(clientY - box.top)}px`)
      }

      const onMove = (event: PointerEvent) => {
        clientX = event.clientX
        clientY = event.clientY
        if (!frame) frame = requestAnimationFrame(write)
      }

      node.addEventListener('pointermove', onMove, { passive: true })
      return () => {
        node.removeEventListener('pointermove', onMove)
        if (frame) cancelAnimationFrame(frame)
      }
    },
    [reduced],
  )
}
