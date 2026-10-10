// @find: media query, screen size, responsive, breakpoint, phone width, useMediaQuery hook
// @what: React hook that tells whether a CSS media query matches.
// @flow: Used by components that change layout by screen size
import { useSyncExternalStore } from 'react'

/*
 * Whether a CSS media query currently matches, read through useSyncExternalStore rather than an
 * effect that calls setState (0.2): the subscription and the value it produces are one operation,
 * so there is no render where the query has changed but the state has not caught up yet.
 *
 * jsdom has no matchMedia, so both the subscription and the snapshot read false there instead of
 * throwing.
 */
// @find: useMediaQuery hook, breakpoint match
export function useMediaQuery(query: string): boolean {
  const subscribe = (onStoreChange: () => void) => {
    if (typeof matchMedia !== 'function') return () => {}
    const list = matchMedia(query)
    list.addEventListener('change', onStoreChange)
    return () => list.removeEventListener('change', onStoreChange)
  }
  const getSnapshot = () => (typeof matchMedia === 'function' ? matchMedia(query).matches : false)
  return useSyncExternalStore(subscribe, getSnapshot, () => false)
}
