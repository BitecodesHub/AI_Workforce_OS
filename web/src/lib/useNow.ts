// @find: use now, current time, ticking clock, relative time refresh, shared timer, hidden tab
// @what: Gives components a shared ticking current time for relative times and running clocks.
// @flow: Used with format.ts for relative times
import { useCallback, useSyncExternalStore } from 'react'

/*
 * The current time, for components that show relative times ("5 minutes ago") or running clocks.
 *
 * Every component asking for the same interval shares one timer, so a table of fifty relative
 * times re-renders from a single tick rather than fifty. Ticks are skipped while the tab is
 * hidden, and a tab that becomes visible again ticks at once, so a person returning after an hour
 * never reads "just now" on something an hour old.
 */

type Clock = {
  now: number
  listeners: Set<() => void>
  stop?: () => void
}

const clocks = new Map<number, Clock>()

function clockFor(intervalMs: number): Clock {
  let clock = clocks.get(intervalMs)
  if (!clock) {
    clock = { now: Date.now(), listeners: new Set() }
    clocks.set(intervalMs, clock)
  }
  return clock
}

function tick(clock: Clock) {
  clock.now = Date.now()
  for (const listener of clock.listeners) listener()
}

function start(clock: Clock, intervalMs: number) {
  const timer = setInterval(() => {
    if (typeof document !== 'undefined' && document.hidden) return
    tick(clock)
  }, intervalMs)
  const onVisibility = () => {
    if (!document.hidden) tick(clock)
  }
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisibility)
  clock.stop = () => {
    clearInterval(timer)
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisibility)
  }
}

function subscribe(intervalMs: number, listener: () => void): () => void {
  const clock = clockFor(intervalMs)
  clock.listeners.add(listener)
  if (!clock.stop) {
    start(clock, intervalMs)
    // The first subscriber may have rendered with a time read long before it mounted; bring it
    // up to date now rather than on the first tick, up to a whole interval later.
    tick(clock)
  }
  return () => {
    clock.listeners.delete(listener)
    if (clock.listeners.size === 0) {
      clock.stop?.()
      clocks.delete(intervalMs)
    }
  }
}

// @find: use now, tick every minute, current timestamp hook
/**
 * Milliseconds since the epoch, refreshed every `intervalMs` (default one minute) while the
 * component is mounted. Pass it as `now` to the functions in ./format.ts.
 */
export function useNow(intervalMs: number = 60_000): number {
  const period = Math.max(1000, intervalMs)
  // Stable per period: a new subscribe function on every render would unsubscribe and
  // resubscribe each time, restarting the shared timer.
  const subscribeToClock = useCallback((listener: () => void) => subscribe(period, listener), [period])
  const read = useCallback(() => clockFor(period).now, [period])
  return useSyncExternalStore(subscribeToClock, read, read)
}
