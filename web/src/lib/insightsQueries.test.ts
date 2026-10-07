import { describe, expect, it } from 'vitest'
import {
  capUsed,
  figure,
  fileNameFrom,
  normaliseAgentInsights,
  normaliseBudget,
  normaliseConversationRatings,
  normaliseInsights,
  normaliseRunRatings,
  normaliseUsageReport,
  normaliseValueSettings,
  parseWindow,
  windowRange,
} from './insightsQueries'

/*
 * What arrives from the service, read defensively: a number is a number whether it came as a JSON
 * number or as text, a missing figure stays missing instead of becoming a zero, and an answer that
 * is not the expected shape is nothing at all, so a screen shows its own words rather than 0s.
 */

describe('figure', () => {
  it('reads numbers and numbers written as text, and nothing else', () => {
    expect(figure(3)).toBe(3)
    expect(figure('0.25')).toBe(0.25)
    expect(figure(' 7 ')).toBe(7)
    expect(figure('')).toBeNull()
    expect(figure('lots')).toBeNull()
    expect(figure(null)).toBeNull()
    expect(figure(undefined)).toBeNull()
    expect(figure(Number.POSITIVE_INFINITY)).toBeNull()
    expect(figure({})).toBeNull()
  })
})

describe('windows', () => {
  it('takes 7d, 30d or 90d and defaults to 30d for anything else', () => {
    expect(parseWindow('7d')).toBe('7d')
    expect(parseWindow('90d')).toBe('90d')
    expect(parseWindow('14d')).toBe('30d')
    expect(parseWindow(null)).toBe('30d')
    expect(parseWindow(undefined)).toBe('30d')
  })

  it('covers the same UTC days the service does: today and the days before it', () => {
    const now = new Date('2026-10-15T12:00:00Z')
    expect(windowRange('7d', now)).toEqual({ from: '2026-10-09', to: '2026-10-15' })
    expect(windowRange('30d', now)).toEqual({ from: '2026-09-16', to: '2026-10-15' })
  })

  it('cuts the day in UTC, not in the zone of whoever is looking', () => {
    // 23:30 UTC on the 15th is already the 16th in Sydney, but the service is still on the 15th.
    expect(windowRange('7d', new Date('2026-10-15T23:30:00Z')).to).toBe('2026-10-15')
    expect(windowRange('7d', new Date('2026-10-15T00:30:00Z')).from).toBe('2026-10-09')
  })

  it('spans a month end', () => {
    expect(windowRange('7d', new Date('2026-11-02T06:00:00Z'))).toEqual({ from: '2026-10-27', to: '2026-11-02' })
  })
})

describe('normaliseInsights', () => {
  it('is nothing for an answer that is not insights', () => {
    expect(normaliseInsights([])).toBeNull()
    expect(normaliseInsights(null)).toBeNull()
    expect(normaliseInsights('<html>')).toBeNull()
    expect(normaliseInsights({})).toBeNull()
  })

  it('reads every section, with decimals as numbers', () => {
    const insights = normaliseInsights({
      window: '7d',
      goals: {
        completed: 4,
        failed: 1,
        cancelled: 1,
        bySource: [{ source: 'chat', completed: 3, failed: 0 }],
        byDay: [{ day: '2026-10-14', completed: 2, failed: 0 }],
      },
      tasks: { completed: 4, failed: 1, successRate: '0.8000', completedWithoutRetry: 3, withoutRetryRate: 0.75 },
      runs: { total: 11, finished: 10, active: 1, byStatus: { completed: 6, failed: 2 }, unpriced: 1, sandboxOnly: 1 },
      spend: {
        total: 0.88,
        byDay: [{ day: '2026-10-14', cost: '0.6' }],
        costPerCompletedGoal: 0.3,
        costedGoals: 2,
        unpricedGoals: 1,
        sandboxGoals: 1,
        failedOrCancelledSpend: 0.17,
        unpricedRuns: 1,
      },
      approvals: { raised: 5, approved: 2, rejected: 1, expired: 1, cancelled: 0, pending: 1, medianDecisionSeconds: 600, p90DecisionSeconds: 3000 },
      questions: { asked: 3, answered: 2, per100Runs: 27.3, medianAnswerSeconds: 450 },
      failureReasons: [{ reason: 'Provider timed out', count: 2 }],
      value: { hourlyRate: 60, agentsEstimated: 1, agentsNotEstimated: 1, completedTasks: 2, hoursReturned: 1, humanEquivalentCost: 60, netValue: 59.18 },
      deltas: { goalsCompleted: { current: 4, previous: 1, change: 3, changePercent: 300 } },
    })

    expect(insights?.goals.completed).toBe(4)
    expect(insights?.tasks.successRate).toBe(0.8)
    expect(insights?.runs.byStatus.completed).toBe(6)
    expect(insights?.spend.byDay[0]?.cost).toBe(0.6)
    expect(insights?.approvals.p90DecisionSeconds).toBe(3000)
    expect(insights?.questions.per100Runs).toBe(27.3)
    expect(insights?.value?.netValue).toBe(59.18)
    expect(insights?.deltas.goalsCompleted?.changePercent).toBe(300)
  })

  it('keeps an absent figure absent: no rate, no cost per goal, no estimate', () => {
    const insights = normaliseInsights({
      goals: { completed: 0, failed: 0, cancelled: 0 },
      tasks: { completed: 0, failed: 0 },
      spend: { total: 0 },
      approvals: { raised: 0 },
    })

    expect(insights?.tasks.successRate).toBeNull()
    expect(insights?.spend.costPerCompletedGoal).toBeNull()
    expect(insights?.approvals.medianDecisionSeconds).toBeNull()
    expect(insights?.questions.per100Runs).toBeNull()
    expect(insights?.value).toBeNull()
  })

  it('drops rows that are not rows and reasons that are not text', () => {
    const insights = normaliseInsights({
      goals: { completed: 1, failed: 0, cancelled: 0, byDay: ['x', null, { day: '2026-10-14', completed: 1, failed: 0 }] },
      failureReasons: [{ reason: '', count: 2 }, 7],
    })

    expect(insights?.goals.byDay).toHaveLength(1)
    expect(insights?.failureReasons).toEqual([{ reason: 'No reason recorded', count: 2 }])
  })
})

describe('normaliseAgentInsights', () => {
  it('is nothing unless there is a list of agents', () => {
    expect(normaliseAgentInsights([])).toBeNull()
    expect(normaliseAgentInsights({ window: '7d' })).toBeNull()
  })

  it('reads each row, keeping what is unknown unknown', () => {
    const result = normaliseAgentInsights({
      window: '7d',
      hourlyRate: null,
      agents: [
        {
          agentId: 'a1',
          name: 'Alpha',
          runs: 7,
          finishedRuns: 6,
          completed: 4,
          failed: 1,
          cancelled: 1,
          enoughRuns: true,
          successRate: 0.6667,
          totalCost: 0.82,
          ratings: 2,
          thumbsDown: 1,
          satisfactionRate: 0.5,
          minutesPerTask: 30,
          hoursReturned: 1,
        },
        { agentId: 'a2', name: 'Beta', runs: 4, enoughRuns: false },
      ],
    })

    const [alpha, beta] = result?.agents ?? []
    expect(alpha?.successRate).toBe(0.6667)
    expect(alpha?.thumbsDown).toBe(1)
    expect(beta?.successRate).toBeNull()
    expect(beta?.totalCost).toBeNull()
    expect(beta?.hoursReturned).toBeNull()
    expect(beta?.minutesPerTask).toBeNull()
    expect(beta?.enoughRuns).toBe(false)
    expect(result?.hourlyRate).toBeNull()
  })
})

describe('the value inputs, the budget and the usage report', () => {
  it('reads the inputs, with no rate or minutes as null', () => {
    const settings = normaliseValueSettings({
      hourlyRate: 60,
      agents: [{ agentId: 'a1', name: 'Alpha', minutesPerTask: 30 }, { agentId: 'a2', name: 'Beta' }],
    })

    expect(settings?.hourlyRate).toBe(60)
    expect(settings?.agents.map((agent) => agent.minutesPerTask)).toEqual([30, null])
    expect(normaliseValueSettings([])).toBeNull()
    expect(normaliseValueSettings({ agents: [] })?.hourlyRate).toBeNull()
  })

  it('reads the budget, with an absent cap as null and any other behaviour at a cap as stop', () => {
    const budget = normaliseBudget({ monthlyCap: '50', onExhausted: 'sandbox', spentThisMonth: 12.4, remaining: 37.6, periodStart: '2026-10-01T00:00:00Z' })

    expect(budget?.monthlyCap).toBe(50)
    expect(budget?.perRunCap).toBeNull()
    expect(budget?.onExhausted).toBe('sandbox')
    expect(budget?.spentThisMonth).toBe(12.4)
    expect(normaliseBudget({ onExhausted: 'anything' })?.onExhausted).toBe('stop')
    expect(normaliseBudget([])).toBeNull()
  })

  it('works out the share of the cap used, and none without a cap', () => {
    expect(capUsed({ monthlyCap: 50, spentThisMonth: 40 })).toBe(0.8)
    expect(capUsed({ monthlyCap: 50, spentThisMonth: 75 })).toBe(1.5)
    expect(capUsed({ monthlyCap: null, spentThisMonth: 40 })).toBeNull()
    expect(capUsed({ monthlyCap: 0, spentThisMonth: 0 })).toBe(1)
    expect(capUsed({ monthlyCap: 0, spentThisMonth: 1 })).toBe(Number.POSITIVE_INFINITY)
  })

  it('reads a usage report, a line at a time', () => {
    const report = normaliseUsageReport({
      groupBy: 'model',
      totals: { label: 'Total', cost: 0.88, failedAttemptCost: 0.15, attempts: 9, totalTokens: 1200 },
      groups: [{ key: 'groq/llama', label: 'groq/llama', cost: 0.5, attempts: 4 }],
    })

    expect(report?.totals.failedAttemptCost).toBe(0.15)
    expect(report?.groups[0]?.label).toBe('groq/llama')
    expect(report?.groups[0]?.failedAttempts).toBe(0)
    expect(normaliseUsageReport({})).toBeNull()
  })
})

describe('ratings', () => {
  it('reads the ratings of a conversation by message, ignoring any that are not a thumb', () => {
    const ratings = normaliseConversationRatings({
      ratings: [
        { messageId: 'm1', rating: 1 },
        { messageId: 'm2', rating: -1, reason: 'Wrong' },
        { messageId: 'm3', rating: 0 },
        { rating: 1 },
      ],
    })

    expect(Object.keys(ratings)).toEqual(['m1', 'm2'])
    expect(ratings.m2).toEqual({ messageId: 'm2', rating: -1, reason: 'Wrong' })
    expect(ratings.m1?.reason).toBeNull()
    expect(normaliseConversationRatings([])).toEqual({})
  })

  it('reads the ratings of a run in the order sent', () => {
    const rows = normaliseRunRatings({
      ratings: [
        { messageId: 'm2', userId: 'u1', rating: -1, reason: 'Incomplete', updatedAt: '2026-10-14T11:00:00Z' },
        { messageId: 'm1', userId: 'u2', rating: 1 },
      ],
    })

    expect(rows.map((row) => row.messageId)).toEqual(['m2', 'm1'])
    expect(rows[0]?.reason).toBe('Incomplete')
    expect(rows[1]?.updatedAt).toBeNull()
    expect(normaliseRunRatings(null)).toEqual([])
  })
})

describe('fileNameFrom', () => {
  it('finds the name the service gave the file', () => {
    expect(fileNameFrom('attachment; filename="usage-attempts_2026-10-01_to_2026-10-15.csv"')).toBe(
      'usage-attempts_2026-10-01_to_2026-10-15.csv',
    )
    expect(fileNameFrom("attachment; filename*=UTF-8''usage%20report.csv")).toBe('usage report.csv')
    expect(fileNameFrom('attachment')).toBeNull()
    expect(fileNameFrom(null)).toBeNull()
  })
})
