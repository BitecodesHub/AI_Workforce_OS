// @find: replay player, time-lapse, play pause, speed 1x 10x 60x, scrubber, seek, timestamps, previous event, next event, reduced motion step by step, useReplayClock, ReplayPlayer
// @what: The replay controls (play and pause, 1x, 10x and 60x, a scrubber with timestamps, previous and next event) and the clock that moves through a goal's timeline.
// @flow: Used by routes/WorkflowRun.tsx; times and events come from lib/workflowReplay buildReplay
import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import { Button } from '../ui'
import { formatDuration } from '../../lib/format'
import { nextEventTime, previousEventTime } from '../../lib/workflowReplay'
import type { Replay } from '../../lib/workflowReplay'

/*
 * With motion allowed, playing moves the clock smoothly at the chosen speed. Under
 * prefers-reduced-motion it moves one event per second instead, with nothing animating in between:
 * the same story, told step by step.
 */

export const SPEEDS = [1, 10, 60] as const
export type Speed = (typeof SPEEDS)[number]

// @find: scrubber keyboard step, replay seek by key
/**
 * Where a key press on the scrubber moves the replay, in milliseconds from its start, or null for
 * a key the scrubber leaves alone. The arrows move one second or a hundredth of the replay,
 * whichever is longer; Page Up and Page Down a tenth; Home and End to either end. The scrubber
 * itself counts in milliseconds so that its end is the replay's real end.
 */
export function scrubberKeyTarget(key: string, elapsed: number, span: number): number | null {
  const small = Math.max(1000, span / 100)
  const large = Math.max(small, span / 10)
  const moves: Record<string, number> = {
    ArrowLeft: -small,
    ArrowDown: -small,
    ArrowRight: small,
    ArrowUp: small,
    PageDown: -large,
    PageUp: large,
  }
  if (key === 'Home') return 0
  if (key === 'End') return span
  const move = moves[key]
  if (move === undefined) return null
  return Math.min(span, Math.max(0, elapsed + move))
}

/** A wall-clock time with seconds, "9:04:05 am". */
export function clockTime(ms: number): string {
  return new Date(ms).toLocaleTimeString('en-AU', { hour: 'numeric', minute: '2-digit', second: '2-digit' })
}

// @find: replay clock hook, playback position
/**
 * The replay's clock: where it is, whether it plays, and how fast. Starts at the end, so a
 * finished goal first shows how it ended. Playing from the end starts again from the beginning.
 * Side effects: an animation frame loop while playing with motion, a one-second interval while
 * playing under reduced motion; both stop at the end and on unmount.
 */
export function useReplayClock(replay: Replay, reduceMotion: boolean) {
  const [at, setAt] = useState(replay.end)
  const [playing, setPlaying] = useState(false)
  const [speed, setSpeed] = useState<Speed>(10)
  const position = useRef(at)
  useEffect(() => {
    position.current = at
  }, [at])

  // A timeline that grows (a goal still running when the page opened) keeps the clock inside it.
  const clamped = Math.min(Math.max(at, replay.start), replay.end)

  useEffect(() => {
    if (!playing) return
    if (reduceMotion) {
      const timer = window.setInterval(() => {
        const next = nextEventTime(replay, position.current)
        setAt(next)
        if (next >= replay.end) setPlaying(false)
      }, 1000)
      return () => window.clearInterval(timer)
    }
    let frame = 0
    let last = performance.now()
    const tick = (now: number) => {
      const next = Math.min(replay.end, position.current + (now - last) * speed)
      last = now
      position.current = next
      setAt(next)
      if (next >= replay.end) {
        setPlaying(false)
        return
      }
      frame = window.requestAnimationFrame(tick)
    }
    frame = window.requestAnimationFrame(tick)
    return () => window.cancelAnimationFrame(frame)
  }, [playing, reduceMotion, replay, speed])

  const play = useCallback(() => {
    if (position.current >= replay.end) {
      position.current = replay.start
      setAt(replay.start)
    }
    setPlaying(true)
  }, [replay])
  const pause = useCallback(() => setPlaying(false), [])
  const seek = useCallback(
    (ms: number) => {
      const next = Math.min(Math.max(ms, replay.start), replay.end)
      position.current = next
      setAt(next)
    },
    [replay],
  )
  return { at: clamped, playing, speed, setSpeed, play, pause, seek }
}

// @find: replay player component, replay controls
/**
 * The controls: Play or Pause, previous and next event, the speed choice (ignored under reduced
 * motion, where playing steps one event a second), and a scrubber labelled with the time at its
 * position, the elapsed time and the time the goal ended.
 */
export function ReplayPlayer({
  replay,
  at,
  playing,
  speed,
  reduceMotion,
  onPlay,
  onPause,
  onSeek,
  onSpeed,
  current,
}: {
  replay: Replay
  at: number
  playing: boolean
  speed: Speed
  reduceMotion: boolean
  onPlay: () => void
  onPause: () => void
  onSeek: (ms: number) => void
  onSpeed: (speed: Speed) => void
  /** The sentence for the latest event at the position, announced while paused. */
  current: string | null
}) {
  const span = Math.max(1, replay.end - replay.start)
  const elapsed = at - replay.start
  return (
    <section className="wf-replay" aria-label="Replay">
      <div className="wf-replay-row">
        <Button
          variant="primary"
          onClick={playing ? onPause : onPlay}
          aria-label={playing ? 'Pause the replay' : 'Play the replay'}
          disabled={replay.events.length === 0}
        >
          {playing ? 'Pause' : 'Play'}
        </Button>
        <Button variant="outline" onClick={() => onSeek(previousEventTime(replay, at))} disabled={at <= replay.start}>
          Previous event
        </Button>
        <Button variant="outline" onClick={() => onSeek(nextEventTime(replay, at))} disabled={at >= replay.end}>
          Next event
        </Button>
        <div className="wf-speed" role="group" aria-label="Replay speed">
          {SPEEDS.map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={speed === option}
              className="wf-speed-option"
              onClick={() => onSpeed(option)}
            >
              {option}x
            </button>
          ))}
        </div>
      </div>
      {reduceMotion && <p className="caption muted">Your device asks for less motion, so the replay moves one event at a time.</p>}
      <div className="wf-scrubber">
        <label className="field-label" htmlFor="wf-scrubber-input">
          Replay position
        </label>
        <input
          id="wf-scrubber-input"
          type="range"
          min={0}
          max={span}
          step={1}
          value={Math.round(elapsed)}
          aria-valuetext={`${clockTime(at)}, ${formatDuration(elapsed)} in`}
          onChange={(event) => onSeek(replay.start + Number(event.target.value))}
          onKeyDown={(event: ReactKeyboardEvent<HTMLInputElement>) => {
            const target = scrubberKeyTarget(event.key, elapsed, span)
            if (target === null) return
            event.preventDefault()
            onSeek(replay.start + target)
          }}
        />
        <div className="wf-scrubber-times caption">
          <time dateTime={new Date(replay.start).toISOString()}>{clockTime(replay.start)}</time>
          <span className="wf-scrubber-now">
            <time dateTime={new Date(at).toISOString()}>{clockTime(at)}</time>
            <span className="muted"> ({formatDuration(elapsed)} in)</span>
          </span>
          <time dateTime={new Date(replay.end).toISOString()}>{clockTime(replay.end)}</time>
        </div>
      </div>
      <p className="visually-hidden" aria-live="polite">
        {!playing && current ? current : ''}
      </p>
    </section>
  )
}
