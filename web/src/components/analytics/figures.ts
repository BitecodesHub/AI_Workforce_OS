import { formatCount, formatDuration, formatMoney } from '../../lib/format'
import type { AgentInsight, Delta, InsightsWindow } from '../../lib/insightsQueries'
import { WINDOW_DAYS } from '../../lib/insightsQueries'

/*
 * How the manager figures are put into words. Pure, so every rule that decides what a tile or a
 * cell may say is tested without drawing anything.
 *
 * The rules are the same ones the service keeps: a figure that is not known is said not to be
 * known, never shown as a zero; a rate over a handful of runs is not shown; a run that used a real
 * model and cost nothing is unpriced, never free.
 */

export const NOT_ENOUGH_RUNS = 'Not enough runs yet'
export const UNPRICED = 'Unpriced'
export const NOT_ESTIMATED = 'Not estimated'

const EMPTY = '—'

/** '75%'; an em dash when there is no rate. */
export function formatPercent(rate: number | null | undefined, digits = 0): string {
  if (rate == null || !Number.isFinite(rate)) return EMPTY
  return `${(rate * 100).toFixed(digits)}%`
}

/** A wait or an answer time given in seconds: '2 min 00 s', '1 h 05 min'. An em dash when unknown. */
export function formatSeconds(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds)) return EMPTY
  return formatDuration(Math.max(0, seconds) * 1000)
}

/** Hours to a tenth, without a trailing '.0': '6 h', '1.5 h'. */
export function formatHours(hours: number | null | undefined): string {
  if (hours == null || !Number.isFinite(hours)) return EMPTY
  if (hours > 0 && hours < 0.05) return 'Under 0.1 h'
  const rounded = Math.round(hours * 10) / 10
  return `${Number.isInteger(rounded) ? rounded.toFixed(0) : rounded.toFixed(1)} h`
}

/** 'previous 7 days': what a change is measured against. */
export function previousPeriod(window: InsightsWindow): string {
  return `previous ${WINDOW_DAYS[window]} days`
}

/** How a figure is written, which decides how its change is. */
export type FigureKind = 'count' | 'money' | 'points' | 'seconds' | 'hours'

function formatChange(amount: number, kind: FigureKind): string {
  switch (kind) {
    case 'count':
      return formatCount(amount)
    case 'money':
      return formatMoney(amount)
    case 'points':
      return `${Math.round(amount * 100)} points`
    case 'seconds':
      return formatDuration(amount * 1000)
    case 'hours':
      return formatHours(amount)
  }
}

/**
 * A figure's change against the window before, in words: 'Up 3 (300%) on the previous 7 days',
 * 'Down US$0.20 on the previous 30 days', 'Same as the previous 7 days'. When there is nothing to
 * compare with it says so, and a change from nothing carries no percentage.
 */
export function describeChange(delta: Delta | undefined, kind: FigureKind, window: InsightsWindow): string {
  const against = previousPeriod(window)
  if (!delta || delta.current === null) return ''
  if (delta.change === null) return `No figure for the ${against} to compare with`
  if (Math.abs(delta.change) < 1e-9) return `Same as the ${against}`
  const direction = delta.change > 0 ? 'Up' : 'Down'
  const amount = formatChange(Math.abs(delta.change), kind)
  const percent =
    delta.changePercent !== null && kind !== 'points' ? ` (${Math.abs(Math.round(delta.changePercent))}%)` : ''
  return `${direction} ${amount}${percent} on the ${against}`
}

/** The rounded-up top of a chart's scale: 1, 2, 5, 10, 20, 50 and so on, so the gridlines read cleanly. */
export function niceMax(value: number): number {
  if (!Number.isFinite(value) || value <= 0) return 1
  const power = 10 ** Math.floor(Math.log10(value))
  for (const step of [1, 2, 5, 10]) {
    if (value <= step * power) return step * power
  }
  return 10 * power
}

/** The day as a short label for a chart's axis: '14 Oct'. The day is a UTC date, written YYYY-MM-DD. */
export function dayLabel(day: string): string {
  const [year, month, date] = day.split('-').map(Number)
  if (!year || !month || !date) return day
  return new Date(Date.UTC(year, month - 1, date)).toLocaleDateString('en-AU', {
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  })
}

/* ---- One agent ------------------------------------------------------------------------------------ */

/** The success rate, or the words for why there is none. */
export function successText(agent: Pick<AgentInsight, 'enoughRuns' | 'successRate' | 'finishedRuns'>): string {
  if (!agent.enoughRuns || agent.successRate === null) return NOT_ENOUGH_RUNS
  return formatPercent(agent.successRate)
}

/** What an agent's runs cost: a figure, 'Unpriced' when no run had a price, or 'Free' on the sandbox. */
export function costText(agent: Pick<AgentInsight, 'totalCost' | 'unpricedRuns' | 'sandboxRuns' | 'runs'>): string {
  if (agent.totalCost === null) return UNPRICED
  if (agent.totalCost === 0) {
    if (agent.runs === 0) return EMPTY
    // Nothing was charged: say whether that is because the sandbox answered, or because the
    // catalogue has no price. A real model that cost nothing is not free.
    if (agent.unpricedRuns > 0) return UNPRICED
    return agent.sandboxRuns > 0 ? 'Free (sandbox)' : formatMoney(0)
  }
  return agent.unpricedRuns > 0 ? `${formatMoney(agent.totalCost)} and more` : formatMoney(agent.totalCost)
}

/** Thumbs up over ratings, with how many there were; 'No ratings yet' when nobody has rated. */
export function satisfactionText(agent: Pick<AgentInsight, 'ratings' | 'thumbsDown' | 'satisfactionRate'>): string {
  if (agent.ratings === 0 || agent.satisfactionRate === null) return 'No ratings yet'
  const down = agent.thumbsDown === 0 ? 'no thumbs down' : `${formatCount(agent.thumbsDown)} thumbs down`
  return `${formatPercent(agent.satisfactionRate)} of ${formatCount(agent.ratings)} ratings, ${down}`
}

/** Hours an agent returned, as an estimate, or 'Not estimated' when nobody has said what a task is worth. */
export function hoursText(agent: Pick<AgentInsight, 'minutesPerTask' | 'hoursReturned'>): string {
  if (agent.minutesPerTask === null || agent.hoursReturned === null) return NOT_ESTIMATED
  return formatHours(agent.hoursReturned)
}

/* ---- One run, in the Runs list --------------------------------------------------------------------- */

/**
 * What a run cost, for the Runs list. The list does not know which model answered, so a run that
 * used tokens and cost nothing is called unpriced - either the catalogue has no price for its
 * model or the offline sandbox answered, and the run's own page says which - and a run that used
 * nothing yet (still starting, or stopped before a model call) has no cost to show.
 */
export function runCost(run: { cost: number; promptTokens: number; completionTokens: number }): {
  text: string
  /** For sorting; null when there is no price, so those runs sort last either way round. */
  value: number | null
  title?: string
} {
  const cost = Number(run.cost)
  if (Number.isFinite(cost) && cost > 0) return { text: formatMoney(cost), value: cost }
  const tokens = (Number(run.promptTokens) || 0) + (Number(run.completionTokens) || 0)
  if (tokens > 0) {
    return {
      text: UNPRICED,
      value: null,
      title: 'Used a model with no price on file, or the offline sandbox, which is free. Open the run to see which.',
    }
  }
  return { text: EMPTY, value: null, title: 'No model call has been made for this run yet.' }
}
