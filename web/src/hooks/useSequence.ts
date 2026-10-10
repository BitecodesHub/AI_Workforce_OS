// @find: sequence, timed steps, scripted demo, play steps, timeline animation, landing demo, useSequence hook
// @what: React hook that plays a list of timed steps, used by the scripted landing demos.
// @flow: Used by landing demos; calls useReducedMotion
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef } from 'react'
import { useReducedMotion } from './useReducedMotion'

/*
 * Plays a list of timed steps.
 *
 * With motion on, each step gets its own timer, including the one at 0, so a sequence cleared by
 * StrictMode's simulated unmount can simply be played again. With reduced motion, every step runs
 * at once in order of time, so the caller lands on the end state without waiting for anything.
 */

export type SequenceStep = { at: number; run: () => void }

export type Sequence = {
  play: (steps: ReadonlyArray<SequenceStep>) => void
  cancel: () => void
}

// @find: useSequence hook, play timed demo steps
export function useSequence(): Sequence {
  const reduced = useReducedMotion()
  const reducedRef = useRef(reduced)
  const timers = useRef<Array<ReturnType<typeof setTimeout>>>([])

  // Kept in a ref so play keeps one identity for the life of the component.
  useLayoutEffect(() => {
    reducedRef.current = reduced
  }, [reduced])

  const cancel = useCallback(() => {
    for (const timer of timers.current) clearTimeout(timer)
    timers.current = []
  }, [])

  const play = useCallback(
    (steps: ReadonlyArray<SequenceStep>) => {
      cancel()
      if (reducedRef.current) {
        const ordered = [...steps].sort((a, b) => a.at - b.at)
        for (const step of ordered) step.run()
        return
      }
      timers.current = steps.map((step) => setTimeout(step.run, Math.max(0, step.at)))
    },
    [cancel],
  )

  useEffect(() => cancel, [cancel])

  return useMemo(() => ({ play, cancel }), [play, cancel])
}
