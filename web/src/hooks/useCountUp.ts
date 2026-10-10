// @find: count up number, animated number, counter animation, figures, reduced motion, useCountUp hook
// @what: React hook that counts a number up to its target once active, skipping the animation when motion is reduced.
// @flow: Used by landing and dashboard figures; calls useReducedMotion
import { useEffect, useRef, useState } from 'react'
import { useReducedMotion } from './useReducedMotion'

/*
 * A number that counts up to its target once it becomes active.
 *
 * The displayed value is decoration: callers render it aria-hidden, with the real target beside
 * it in visually hidden text, so a screen reader never hears the intermediate figures. State
 * changes only inside animation frames, never synchronously in the effect.
 */

const RETARGET_MS = 300

type FrameScheduler = {
  request: (callback: (now: number) => void) => number
  cancel: (handle: number) => void
}

function frameScheduler(): FrameScheduler {
  if (typeof requestAnimationFrame === 'function' && typeof cancelAnimationFrame === 'function') {
    return {
      request: (callback) => requestAnimationFrame(callback),
      cancel: (handle) => cancelAnimationFrame(handle),
    }
  }
  return {
    request: (callback) => Number(setTimeout(() => callback(performance.now()), 16)),
    cancel: (handle) => clearTimeout(handle),
  }
}

function easeOutCubic(progress: number): number {
  return 1 - Math.pow(1 - progress, 3)
}

// @find: useCountUp hook, animate number
export function useCountUp(target: number, active: boolean, durationMs = 600): number {
  const reduced = useReducedMotion()
  const [shown, setShown] = useState(0)
  const displayed = useRef(0)
  const started = useRef(false)

  useEffect(() => {
    if (reduced || !active) return undefined
    if (started.current && displayed.current === target) return undefined

    const scheduler = frameScheduler()
    const from = displayed.current
    const duration = started.current ? RETARGET_MS : durationMs
    let startedAt: number | null = null
    let handle = 0

    const tick = (now: number) => {
      // Marked here rather than in the effect body, so StrictMode's discarded first run does
      // not turn the opening count into a 300ms retarget.
      started.current = true
      if (startedAt === null) startedAt = now
      const progress = duration <= 0 ? 1 : Math.min(1, (now - startedAt) / duration)
      const value = progress >= 1 ? target : from + (target - from) * easeOutCubic(progress)
      displayed.current = value
      setShown(Math.round(value))
      if (progress < 1) handle = scheduler.request(tick)
    }

    handle = scheduler.request(tick)
    return () => scheduler.cancel(handle)
  }, [reduced, active, target, durationMs])

  return reduced ? target : shown
}
