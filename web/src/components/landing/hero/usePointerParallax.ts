import { useCallback, useEffect, useRef, useSyncExternalStore } from 'react'
import type { RefCallback } from 'react'

/*
 * Pointer parallax for the hero console's glass layers.
 *
 * The pointer's position over the host is normalised to -1..1 on each axis and eased towards at
 * 0.1 of the remaining distance per animation frame. The result is written to the stage as --px
 * and --py, and hero.css turns it into a small translate on each layer: nothing rotates, nothing
 * re-renders React, and the loop stops as soon as the layers have settled.
 *
 * It only runs where it can be seen and is wanted: the caller passes motion on, not paused and in
 * view, and the hook adds the last condition itself, a fine pointer on a window at least 1180px
 * wide, where the console sits beside the copy. When it switches off, the variables are removed
 * and the stage is marked so hero.css eases the layers back to rest.
 */

const WIDE_FINE_POINTER = '(pointer: fine) and (min-width: 1180px)'
const EASE = 0.1
const SETTLED = 0.001

function pointerQuery(): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null
  return window.matchMedia(WIDE_FINE_POINTER)
}

function subscribe(onChange: () => void): () => void {
  const list = pointerQuery()
  if (!list) return () => {}
  if (typeof list.addEventListener === 'function') {
    list.addEventListener('change', onChange)
    return () => list.removeEventListener('change', onChange)
  }
  list.addListener(onChange)
  return () => list.removeListener(onChange)
}

function getSnapshot(): boolean {
  return pointerQuery()?.matches ?? false
}

function getServerSnapshot(): boolean {
  return false
}

function clampUnit(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.max(-1, Math.min(1, value))
}

export function usePointerParallax(enabled: boolean): {
  hostRef: RefCallback<HTMLElement>
  stageRef: RefCallback<HTMLElement>
} {
  const wideFinePointer = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot)
  const active = enabled && wideFinePointer

  const hostNode = useRef<HTMLElement | null>(null)
  const stageNode = useRef<HTMLElement | null>(null)

  const hostRef = useCallback((node: HTMLElement | null) => {
    hostNode.current = node
    return () => {
      hostNode.current = null
    }
  }, [])

  const stageRef = useCallback((node: HTMLElement | null) => {
    stageNode.current = node
    return () => {
      stageNode.current = null
    }
  }, [])

  useEffect(() => {
    const host = hostNode.current
    const stage = stageNode.current
    if (!active || !host || !stage) return undefined
    if (typeof requestAnimationFrame !== 'function') return undefined

    let targetX = 0
    let targetY = 0
    let x = 0
    let y = 0
    let frame = 0

    const write = () => {
      stage.style.setProperty('--px', x.toFixed(4))
      stage.style.setProperty('--py', y.toFixed(4))
    }

    const tick = () => {
      frame = 0
      const stepX = (targetX - x) * EASE
      const stepY = (targetY - y) * EASE
      if (Math.abs(stepX) < SETTLED && Math.abs(stepY) < SETTLED) {
        // Close enough that the next step would be invisible: land exactly and stop the loop.
        x = targetX
        y = targetY
        write()
        return
      }
      x += stepX
      y += stepY
      write()
      frame = requestAnimationFrame(tick)
    }

    const start = () => {
      if (!frame) frame = requestAnimationFrame(tick)
    }

    const onMove = (event: PointerEvent) => {
      const box = host.getBoundingClientRect()
      if (box.width <= 0 || box.height <= 0) return
      targetX = clampUnit(((event.clientX - box.left) / box.width) * 2 - 1)
      targetY = clampUnit(((event.clientY - box.top) / box.height) * 2 - 1)
      start()
    }

    const onLeave = () => {
      targetX = 0
      targetY = 0
      start()
    }

    stage.dataset.parallax = 'on'
    host.addEventListener('pointermove', onMove, { passive: true })
    host.addEventListener('pointerleave', onLeave)

    return () => {
      host.removeEventListener('pointermove', onMove)
      host.removeEventListener('pointerleave', onLeave)
      if (frame) cancelAnimationFrame(frame)
      delete stage.dataset.parallax
      stage.style.removeProperty('--px')
      stage.style.removeProperty('--py')
    }
  }, [active])

  return { hostRef, stageRef }
}
