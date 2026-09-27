import { describe, expect, it } from 'vitest'
import { COMPOSED_ELAPSED_MS, CYCLE_MS, heroFrame } from './heroConsoleModel'

/*
 * The hero console is a pure function of elapsed time, so every frame the figure can show is
 * checked here without a clock or a DOM.
 */

function row(elapsed: number, id: 'hr' | 'eng' | 'research' | 'support') {
  const found = heroFrame(elapsed).rows.find((entry) => entry.id === id)
  if (!found) throw new Error(`row ${id} is missing at ${elapsed}ms`)
  return found
}

describe('heroFrame stats', () => {
  it('opens with three runs in progress and nothing waiting', () => {
    expect(heroFrame(0).stats).toEqual({ total: 18, running: 3, waiting: 0, completed: 15 })
  })

  it('counts the Research run as completed at three seconds', () => {
    expect(heroFrame(3_000).stats).toEqual({ total: 18, running: 2, waiting: 0, completed: 16 })
  })

  it('moves the HR run to waiting when the approval arrives at 4.5 seconds', () => {
    expect(heroFrame(4_500).stats).toEqual({ total: 18, running: 1, waiting: 1, completed: 16 })
  })

  it('starts a new run at six seconds', () => {
    expect(heroFrame(6_000).stats).toEqual({ total: 19, running: 2, waiting: 1, completed: 16 })
  })

  it('wraps the totals after a minute but keeps the HR run waiting', () => {
    expect(heroFrame(60_000).stats).toEqual({ total: 18, running: 2, waiting: 1, completed: 15 })
  })

  it('never reports a negative number of running runs, and the parts always add up', () => {
    for (let elapsed = 0; elapsed <= 3 * 60_000; elapsed += 250) {
      const { stats } = heroFrame(elapsed)
      expect(stats.running, `running at ${elapsed}ms`).toBeGreaterThanOrEqual(0)
      expect(stats.running + stats.waiting + stats.completed, `sum at ${elapsed}ms`).toBe(stats.total)
    }
  })

  it('keeps the running count equal to the rows shown as running', () => {
    for (let elapsed = 0; elapsed <= 2 * 60_000; elapsed += 250) {
      const frame = heroFrame(elapsed)
      const runningRows = frame.rows.filter((entry) => entry.status === 'running').length
      expect(frame.stats.running, `rows at ${elapsed}ms`).toBe(runningRows)
    }
  })
})

describe('heroFrame approval', () => {
  it('has not arrived before 4.5 seconds', () => {
    const frame = heroFrame(4_499)
    expect(frame.approvalArrived).toBe(false)
    expect(frame.approvalsBadge).toBe(0)
    expect(row(4_499, 'hr').status).toBe('running')
  })

  it('persists after the first minute and beyond', () => {
    for (const elapsed of [4_500, 59_999, 60_000, 60_250, 64_000, 125_000, 10 * 60_000]) {
      const frame = heroFrame(elapsed)
      expect(frame.approvalArrived, `arrived at ${elapsed}ms`).toBe(true)
      expect(frame.approvalsBadge, `badge at ${elapsed}ms`).toBe(1)
      expect(row(elapsed, 'hr').status, `HR at ${elapsed}ms`).toBe('waiting')
    }
  })

  it('freezes the HR time when the run parks', () => {
    expect(row(0, 'hr').seconds).toBe(42)
    expect(row(4_500, 'hr').seconds).toBe(46)
    expect(row(60_000, 'hr').seconds).toBe(46)
  })
})

describe('heroFrame trace', () => {
  it('shows all three lines at three seconds, in order', () => {
    const trace = heroFrame(3_000).trace
    expect(trace.map((line) => line.verb)).toEqual(['skip', 'fail', 'ok'])
    expect(trace.map((line) => line.provider)).toEqual(['groq', 'openrouter', 'gemini'])
  })

  it('adds the lines one at a time', () => {
    expect(heroFrame(799).trace).toHaveLength(0)
    expect(heroFrame(800).trace).toHaveLength(1)
    expect(heroFrame(1_800).trace).toHaveLength(2)
    expect(heroFrame(2_999).trace).toHaveLength(2)
  })

  it('clears the trace at the end of each loop', () => {
    expect(heroFrame(9_599).trace).toHaveLength(3)
    expect(heroFrame(9_700).trace).toHaveLength(0)
    expect(heroFrame(CYCLE_MS + 3_000).trace).toHaveLength(3)
  })
})

describe('heroFrame rows', () => {
  it('composes the reduced-motion frame at five seconds', () => {
    const frame = heroFrame(COMPOSED_ELAPSED_MS)
    expect(frame.trace).toHaveLength(3)
    expect(frame.approvalArrived).toBe(true)
    expect(row(COMPOSED_ELAPSED_MS, 'hr').status).toBe('waiting')
    expect(row(COMPOSED_ELAPSED_MS, 'research').status).toBe('completed')
  })

  it('freezes the Research time while completed, then starts the next run from zero', () => {
    expect(row(2_999, 'research')).toMatchObject({ status: 'running', seconds: 63 })
    expect(row(3_000, 'research')).toMatchObject({ status: 'completed', seconds: 64 })
    expect(row(5_999, 'research')).toMatchObject({ status: 'completed', seconds: 64 })
    expect(row(6_000, 'research')).toMatchObject({ status: 'running', seconds: 0 })
    expect(row(CYCLE_MS, 'research')).toMatchObject({ status: 'running', seconds: 4 })
    expect(row(CYCLE_MS + 3_000, 'research')).toMatchObject({ status: 'completed', seconds: 7 })
  })

  it('treats negative and non-finite input as the start', () => {
    expect(heroFrame(-500)).toEqual(heroFrame(0))
    expect(heroFrame(Number.NaN)).toEqual(heroFrame(0))
  })
})
