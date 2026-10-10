// @find: tests for BudgetCard, budget at the cap, spend cap reached, budget card test
// @what: Tests the Budget card behaviour when spending is at the cap.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearSession, saveSession } from '../../lib/session'
import { BudgetCard } from './BudgetCard'

/*
 * At the monthly cap the guard refuses only calls that cost something; a free model still answers.
 * The notice must say so rather than claim every run that needs a model is stopped.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      json(200, {
        monthlyCap: 0.01,
        perRunCap: null,
        perAgentDailyCap: null,
        onExhausted: 'stop',
        spentThisMonth: 0.0535,
        remaining: 0,
        projectedMonthEnd: 0.22,
        periodStart: '2026-10-01T00:00:00Z',
      }),
    ),
  )
  saveSession('t', {
    userId: 'u1',
    workspaceId: 'w1',
    permissions: ['budget:read', 'budget:manage'],
    displayName: 'Ava',
    email: 'ava@example.test',
    role: 'owner',
  })
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

describe('BudgetCard at the cap', () => {
  it('says paid models stop and free ones still answer', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <BudgetCard />
      </QueryClientProvider>,
    )
    expect(await screen.findByText(/has been reached/)).toHaveTextContent(
      'Runs that need a paid model are stopped until the cap is raised or the month ends. Free models still answer.',
    )
  })
})
