// @find: tests for OutcomesCard, goal outcomes card test, analytics outcomes
// @what: Tests the Outcomes card on the Analytics page.
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Insights } from '../../lib/insightsQueries'
import { OutcomesCard } from './OutcomesCard'

/* Goals by where they came from: each source reads as a whole phrase, never "Goals from given directly". */

const insights = {
  runs: { total: 0, byStatus: {}, active: 0, unpriced: 0, sandboxOnly: 0 },
  approvals: { raised: 0, approved: 0, rejected: 0, expired: 0, pending: 0, medianDecisionSeconds: null, p90DecisionSeconds: null },
  questions: { asked: 0, answered: 0, medianAnswerSeconds: null, per100Runs: null },
  failureReasons: [],
  goals: {
    bySource: [
      { source: 'chat', completed: 97, failed: 34 },
      { source: 'manual', completed: 10, failed: 1 },
      { source: 'schedule', completed: 1, failed: 0 },
    ],
  },
} as unknown as Insights

describe('OutcomesCard', () => {
  it('names each goal source in plain words', () => {
    render(<OutcomesCard insights={insights} />)
    expect(screen.getByText('Goals from chat')).toBeInTheDocument()
    expect(screen.getByText('Goals given directly')).toBeInTheDocument()
    expect(screen.getByText('Goals from schedules')).toBeInTheDocument()
    expect(screen.queryByText(/from given directly/)).not.toBeInTheDocument()
  })
})
