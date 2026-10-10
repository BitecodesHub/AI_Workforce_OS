// @find: reduced motion, less motion, accessibility, prefers-reduced-motion, animation off, useReducedMotion hook
// @what: React hook that tells whether the visitor asked their system for less motion.
// @flow: Used by useCountUp, usePointerSpot, useSequence and landing animations
import { useSyncExternalStore } from 'react'

/*
 * Whether the visitor has asked their system for less motion.
 *
 * Anything that cannot answer the question - server rendering, a test environment without
 * matchMedia, an old embedded browser - is treated as "reduce". The safe failure is a page that
 * shows every end state at once, never one that animates at somebody who asked it not to.
 */

const QUERY = '(prefers-reduced-motion: reduce)'

function mediaQueryList(): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null
  return window.matchMedia(QUERY)
}

function subscribe(onChange: () => void): () => void {
  const list = mediaQueryList()
  if (!list) return () => {}
  if (typeof list.addEventListener === 'function') {
    list.addEventListener('change', onChange)
    return () => list.removeEventListener('change', onChange)
  }
  // Safari before 14 only offers the older listener API.
  list.addListener(onChange)
  return () => list.removeListener(onChange)
}

function getSnapshot(): boolean {
  const list = mediaQueryList()
  return list ? list.matches : true
}

function getServerSnapshot(): boolean {
  return true
}

// @find: useReducedMotion hook, respect reduced motion
export function useReducedMotion(): boolean {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot)
}
