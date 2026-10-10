// @find: reveal on scroll, fade in, appear when scrolled, stagger, revealStyle, useReveal hook
// @what: React hook that marks an element revealed the first time it scrolls into view, plus a helper for staggered delays.
// @flow: Used by landing sections
import type { CSSProperties, RefCallback } from 'react'

/*
 * Marks an element as revealed the first time it scrolls into view.
 *
 * One observer serves the whole page rather than one per element. The attribute is written
 * straight onto the node, so revealing a tile never re-renders it, and base.css decides what
 * "revealed" looks like: under reduced motion, nothing is ever hidden in the first place.
 *
 * Never render data-inview from JSX on an element that uses this ref: React would overwrite the
 * attribute on the next render and hide the element again.
 */

let sharedObserver: IntersectionObserver | null = null

function revealObserver(): IntersectionObserver | null {
  if (typeof IntersectionObserver === 'undefined') return null
  if (!sharedObserver) {
    sharedObserver = new IntersectionObserver(
      (entries, observer) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue
          const target = entry.target
          if (target instanceof HTMLElement) target.dataset.inview = 'true'
          observer.unobserve(target)
        }
      },
      { threshold: 0, rootMargin: '0px 0px -12% 0px' },
    )
  }
  return sharedObserver
}

function attachReveal(node: HTMLElement | null): (() => void) | undefined {
  if (!node) return undefined
  if (node.dataset.inview === 'true') return undefined
  const observer = revealObserver()
  if (!observer) {
    node.dataset.inview = 'true'
    return undefined
  }
  observer.observe(node)
  return () => observer.unobserve(node)
}

/** A stable ref callback: the same function on every render, so React never re-attaches it. */
// @find: useReveal hook, reveal on scroll
export function useReveal<T extends HTMLElement>(): RefCallback<T> {
  return attachReveal
}

/** The reveal index, read by base.css for the stagger. */
// @find: revealStyle, stagger delay by index
export function revealStyle(index: number): CSSProperties {
  return { '--i': String(index) } as CSSProperties
}
