// @find: tests for analytics figures, formatting tests, describeChange tests, chart scale tests, agent success and cost text, run cost in Runs list
// @what: Tests the number formatting and wording helpers in figures.ts.
import { describe, expect, it } from 'vitest'
import type { Delta } from '../../lib/insightsQueries'
import {
  NOT_ENOUGH_RUNS,
  NOT_ESTIMATED,
  UNPRICED,
  costText,
  dayLabel,
  describeChange,
  formatHours,
  formatPercent,
  formatSeconds,
  hoursText,
  niceMax,
  runCost,
  satisfactionText,
  successText,
} from './figures'

/*
 * What a figure is allowed to say. The rules are the service's: a figure that is not known says so
 * rather than reading as zero, a rate over a few runs is not shown, and a run with no price is
 * unpriced, never free.
 */

const delta = (current: number | null, previous: number | null, change: number | null, changePercent: number | null): Delta => ({
  current,
  previous,
  change,
  changePercent,
})

describe('formatting', () => {
  it('writes a rate as a percentage and an unknown one as a dash', () => {
    expect(formatPercent(0.75)).toBe('75%')
    expect(formatPercent(0.6667, 1)).toBe('66.7%')
    expect(formatPercent(null)).toBe('—')
    expect(formatPercent(Number.NaN)).toBe('—')
  })

  it('writes hours to a tenth without a trailing .0', () => {
    expect(formatHours(6)).toBe('6 h')
    expect(formatHours(1.5)).toBe('1.5 h')
    expect(formatHours(0.01)).toBe('Under 0.1 h')
    expect(formatHours(0)).toBe('0 h')
    expect(formatHours(null)).toBe('—')
  })

  it('writes a wait given in seconds', () => {
    expect(formatSeconds(600)).toBe('10 min 00 s')
    expect(formatSeconds(3_900)).toBe('1 h 05 min')
    expect(formatSeconds(null)).toBe('—')
  })

  it('labels a chart day as day and month, from a UTC date', () => {
    expect(dayLabel('2026-10-14')).toBe('14 Oct')
    expect(dayLabel('2026-01-01')).toBe('1 Jan')
    expect(dayLabel('not a day')).toBe('not a day')
  })
})

describe('describeChange', () => {
  it('says which way a count moved, by how much and by what share', () => {
    expect(describeChange(delta(4, 1, 3, 300), 'count', '7d')).toBe('Up 3 (300%) on the previous 7 days')
    expect(describeChange(delta(1, 4, -3, -75), 'count', '30d')).toBe('Down 3 (75%) on the previous 30 days')
  })

  it('writes money as money and a rate as points, with no percentage of a percentage', () => {
    expect(describeChange(delta(0.88, 0.4, 0.48, 120), 'money', '7d')).toBe('Up US$0.48 (120%) on the previous 7 days')
    expect(describeChange(delta(0.8, 1, -0.2, -20), 'points', '7d')).toBe('Down 20 points on the previous 7 days')
  })

  it('says so when nothing changed, and when there is nothing to compare with', () => {
    expect(describeChange(delta(3, 3, 0, 0), 'count', '7d')).toBe('Same as the previous 7 days')
    expect(describeChange(delta(3, null, null, null), 'count', '7d')).toBe(
      'No figure for the previous 7 days to compare with',
    )
  })

  it('says nothing about a figure that is itself unknown, or missing', () => {
    expect(describeChange(delta(null, 3, null, null), 'count', '7d')).toBe('')
    expect(describeChange(undefined, 'count', '7d')).toBe('')
  })

  it('leaves out the percentage when the previous figure was nothing', () => {
    expect(describeChange(delta(2, 0, 2, null), 'count', '7d')).toBe('Up 2 on the previous 7 days')
  })
})

describe('a chart scale', () => {
  it('rounds the top of the scale up to a figure that reads cleanly', () => {
    expect(niceMax(3)).toBe(5)
    expect(niceMax(7)).toBe(10)
    expect(niceMax(0.6)).toBe(1)
    expect(niceMax(12)).toBe(20)
    expect(niceMax(0)).toBe(1)
    expect(niceMax(Number.NaN)).toBe(1)
  })
})

describe('one agent', () => {
  const base = {
    runs: 7,
    finishedRuns: 6,
    enoughRuns: true,
    successRate: 0.6667,
    totalCost: 0.82,
    unpricedRuns: 0,
    sandboxRuns: 0,
  }

  it('shows a success rate only with five finished runs', () => {
    expect(successText(base)).toBe('67%')
    expect(successText({ ...base, enoughRuns: false, finishedRuns: 4, successRate: null })).toBe(NOT_ENOUGH_RUNS)
    expect(successText({ ...base, enoughRuns: true, successRate: null })).toBe(NOT_ENOUGH_RUNS)
  })

  it('never shows an unknown cost as zero', () => {
    expect(costText({ ...base, totalCost: null, unpricedRuns: 4 })).toBe(UNPRICED)
    expect(costText({ ...base, totalCost: 0, runs: 3, unpricedRuns: 3 })).toBe(UNPRICED)
  })

  it('calls an unpaid sandbox run free, and a priced one by its cost', () => {
    expect(costText({ ...base, totalCost: 0, runs: 5, sandboxRuns: 5 })).toBe('Free (sandbox)')
    expect(costText(base)).toBe('US$0.82')
  })

  it('says a partly priced total is a floor', () => {
    expect(costText({ ...base, totalCost: 0.3, unpricedRuns: 1 })).toBe('US$0.30 and more')
  })

  it('shows nothing for an agent that has not run', () => {
    expect(costText({ ...base, runs: 0, totalCost: 0 })).toBe('—')
  })

  it('reports satisfaction with how many ratings it rests on', () => {
    expect(satisfactionText({ ratings: 4, thumbsDown: 1, satisfactionRate: 0.75 })).toBe(
      '75% of 4 ratings, 1 thumbs down',
    )
    expect(satisfactionText({ ratings: 2, thumbsDown: 0, satisfactionRate: 1 })).toBe(
      '100% of 2 ratings, no thumbs down',
    )
    expect(satisfactionText({ ratings: 1, thumbsDown: 0, satisfactionRate: 1 })).toBe('100% of 1 rating, no thumbs down')
    expect(satisfactionText({ ratings: 0, thumbsDown: 0, satisfactionRate: null })).toBe('No ratings yet')
  })

  it('shows hours only for an agent somebody has put a task time on', () => {
    expect(hoursText({ minutesPerTask: 30, hoursReturned: 1 })).toBe('1 h')
    expect(hoursText({ minutesPerTask: null, hoursReturned: null })).toBe(NOT_ESTIMATED)
  })
})

describe('what a run cost, in the Runs list', () => {
  it('shows the price of a priced run and sorts by it', () => {
    expect(runCost({ cost: 0.0042, promptTokens: 100, completionTokens: 50 })).toEqual({ text: 'US$0.0042', value: 0.0042 })
  })

  it('calls a run that used a model and cost nothing unpriced, with no value to sort by', () => {
    const cost = runCost({ cost: 0, promptTokens: 100, completionTokens: 50 })
    expect(cost.text).toBe(UNPRICED)
    expect(cost.value).toBeNull()
    expect(cost.title).toMatch(/no price on file/i)
  })

  it('shows a dash for a run that has not made a model call', () => {
    const cost = runCost({ cost: 0, promptTokens: 0, completionTokens: 0 })
    expect(cost.text).toBe('—')
    expect(cost.value).toBeNull()
  })

  it('says Free for a run on free catalogue models or the sandbox, and Unpriced for one with no price on file', () => {
    expect(runCost({ cost: 0, promptTokens: 100, completionTokens: 5, pricing: 'free' }).text).toBe('Free')
    expect(runCost({ cost: 0, promptTokens: 100, completionTokens: 5, pricing: 'sandbox' }).text).toBe('Free')
    expect(runCost({ cost: 0, promptTokens: 100, completionTokens: 5, pricing: 'unpriced' }).text).toBe(UNPRICED)
    expect(runCost({ cost: 0, promptTokens: 0, completionTokens: 0, pricing: 'none' }).text).toBe('—')
  })

  it('reads a cost that arrives as text', () => {
    expect(runCost({ cost: '0.5' as unknown as number, promptTokens: 1, completionTokens: 1 }).text).toBe('US$0.50')
  })
})
