import type { AgentCategory } from '../shared/landingFacts'

/*
 * What the hero's simulated console shows at a given moment.
 *
 * One pure function of elapsed time, so the figure can be frozen at any instant (reduced motion
 * shows the composed frame at five seconds), tested without a clock, and paused simply by not
 * advancing the number. Every figure below is an example and the figure says so in its caption.
 *
 * The loop is ten seconds long and six loops make a minute: a Research run finishes three seconds
 * in, a new run starts at six, and the routing trace for that run skips an unavailable provider on
 * the way to an answer. The HR run parks on an outbound email at four and a half seconds and stays
 * parked, because nothing on this page approves it.
 */

export const CYCLE_MS = 10_000

export const COMPOSED_ELAPSED_MS = 5_000

/** When the HR run reaches gmail.send_message and waits for a person. It never resets. */
const ARRIVAL_MS = 4_500

const MINUTE_MS = 60_000

export type HeroRow = {
  id: 'hr' | 'eng' | 'research' | 'support'
  agent: string
  category: AgentCategory
  goal: string
  status: 'running' | 'completed' | 'waiting'
  seconds: number
}

export type HeroTraceLine = {
  id: 1 | 2 | 3
  verb: 'skip' | 'fail' | 'ok'
  provider: string
  detail: string
}

export type HeroFrame = {
  stats: { total: number; running: number; waiting: number; completed: number }
  rows: ReadonlyArray<HeroRow>
  trace: ReadonlyArray<HeroTraceLine>
  approvalArrived: boolean
  approvalsBadge: 0 | 1
}

/** Each line appears at its own moment in the loop, and the whole trace clears at 9.6s. */
const TRACE: ReadonlyArray<{ at: number; line: HeroTraceLine }> = [
  { at: 800, line: { id: 1, verb: 'skip', provider: 'groq', detail: 'circuit open' } },
  {
    at: 1_800,
    line: { id: 2, verb: 'fail', provider: 'openrouter', detail: '429, honoured Retry-After, failed over' },
  },
  { at: 3_000, line: { id: 3, verb: 'ok', provider: 'gemini', detail: 'answered' } },
]

const TRACE_CLEARS_AT = 9_600

function researchRow(c: number, t: number): HeroRow {
  const base = { id: 'research', agent: 'Research', category: 'growth', goal: 'Summarise competitor pricing' } as const
  const earlierRunStart = c === 0 ? 61 : 4
  if (t < 3_000) {
    return { ...base, status: 'running', seconds: earlierRunStart + Math.floor(t / 1_000) }
  }
  if (t < 6_000) {
    // Finished: the time stays at what it read when the run completed.
    return { ...base, status: 'completed', seconds: earlierRunStart + 3 }
  }
  return { ...base, status: 'running', seconds: Math.floor((t - 6_000) / 1_000) }
}

export function heroFrame(elapsedMs: number): HeroFrame {
  // Negative or non-finite input is treated as the very start, so the function is total.
  const elapsed = Number.isFinite(elapsedMs) ? Math.max(0, elapsedMs) : 0
  const e = elapsed % MINUTE_MS
  const c = Math.floor(e / CYCLE_MS)
  const t = e % CYCLE_MS

  const arrived = elapsed >= ARRIVAL_MS

  const total = 18 + c + (t >= 6_000 ? 1 : 0)
  const completed = 15 + c + (t >= 3_000 ? 1 : 0)
  const waiting = arrived ? 1 : 0
  const running = total - completed - waiting

  const rows: ReadonlyArray<HeroRow> = [
    {
      id: 'hr',
      agent: 'HR',
      category: 'operations',
      goal: 'Onboard the new hire',
      status: arrived ? 'waiting' : 'running',
      seconds: 42 + Math.floor(Math.min(elapsed, ARRIVAL_MS) / 1_000),
    },
    {
      id: 'eng',
      agent: 'Engineering Manager',
      category: 'engineering',
      goal: 'Post the standup note',
      status: 'running',
      seconds: 18 + Math.floor(e / 1_000),
    },
    researchRow(c, t),
    {
      id: 'support',
      agent: 'Customer Support',
      category: 'support',
      goal: 'Triage the overnight queue',
      status: 'completed',
      seconds: 51,
    },
  ]

  const trace = t < TRACE_CLEARS_AT ? TRACE.filter((entry) => t >= entry.at).map((entry) => entry.line) : []

  return {
    stats: { total, running, waiting, completed },
    rows,
    trace,
    approvalArrived: arrived,
    approvalsBadge: arrived ? 1 : 0,
  }
}
