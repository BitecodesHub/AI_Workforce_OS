// @find: in view, on screen, intersection observer, scroll visible, lazy start, useInView hook
// @what: React hook that reports whether an element is on screen.
// @flow: Used by landing demos to start when scrolled to
import { useCallback, useState } from 'react'
import type { RefCallback } from 'react'

/*
 * Whether an element is on screen.
 *
 * State changes only inside the observer callback, never synchronously in an effect or during
 * render. Where IntersectionObserver does not exist the element is simply treated as visible, so
 * anything waiting on it still happens.
 */

export type InViewOptions = { threshold?: number; rootMargin?: string; once?: boolean }

// @find: useInView hook, element visible on screen
export function useInView<T extends Element>(
  options?: InViewOptions,
): { ref: RefCallback<T>; inView: boolean } {
  const threshold = options?.threshold ?? 0
  const rootMargin = options?.rootMargin ?? '0px'
  const once = options?.once ?? true

  const [inView, setInView] = useState<boolean>(() => typeof IntersectionObserver === 'undefined')

  const ref = useCallback(
    (node: T | null) => {
      if (!node || typeof IntersectionObserver === 'undefined') return undefined
      const observer = new IntersectionObserver(
        (entries) => {
          const entry = entries[entries.length - 1]
          if (!entry) return
          const visible = entry.isIntersecting && entry.intersectionRatio >= threshold
          if (once) {
            if (visible) {
              setInView(true)
              observer.disconnect()
            }
            return
          }
          setInView(visible)
        },
        { threshold, rootMargin },
      )
      observer.observe(node)
      return () => observer.disconnect()
    },
    [threshold, rootMargin, once],
  )

  return { ref, inView }
}
