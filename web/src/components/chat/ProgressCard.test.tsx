import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Approval, BoardGoal } from '../../lib/queries'
import { clearSession, saveSession } from '../../lib/session'
import { ProgressCard } from './ProgressCard'
import { DetailsContext } from './detailsContext'
import type { RevealGoal } from './detailsContext'

/*
 * "Review" from the work strip reaches the decision even when the progress card was folded: the
 * card opens and Approve takes focus. A request made before the card mounted is an old one, and
 * does not take focus again when the thread is shown later.
 */

const GOAL: BoardGoal = {
  id: 'g1',
  title: 'Send the Q3 quote',
  description: '',
  status: 'running',
  createdAt: '2026-10-01T00:00:00Z',
  completedAt: null,
  source: 'chat',
  requestedBy: 'user-1',
  conversationId: 'c1',
  scheduleId: null,
  tasks: [
    {
      id: 't0',
      agentId: 'agent-1',
      title: 'Draft the quote',
      status: 'completed',
      position: 0,
      dependsOn: [],
      attempt: 0,
      maxAttempts: 2,
      result: null,
      failureReason: null,
      startedAt: null,
      completedAt: null,
      runId: 'run-0',
      runStatus: 'completed',
      stepCount: null,
      cost: null,
    },
    {
      id: 't1',
      agentId: 'agent-1',
      title: 'Send the quote',
      status: 'waiting_approval',
      position: 1,
      dependsOn: [],
      attempt: 0,
      maxAttempts: 2,
      result: null,
      failureReason: null,
      startedAt: null,
      completedAt: null,
      runId: 'run-1',
      runStatus: 'waiting_approval',
      stepCount: null,
      cost: null,
    },
  ],
}

const APPROVAL: Approval = {
  id: 'a1',
  runId: 'run-1',
  agentId: 'agent-1',
  tool: 'gmail__send_email',
  actionClass: 'external',
  summary: 'Send the Q3 quote to the customer',
  payload: '{"to":"buyer@example.com"}',
  status: 'pending',
  requestedAt: '2026-10-01T00:00:00Z',
  expiresAt: '2026-10-02T00:00:00Z',
}

beforeEach(() => {
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['approval:read', 'approval:decide'],
    displayName: 'Maya Manager',
    email: 'maya@example.com',
    role: 'manager',
  })
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString()
      if (url.startsWith('/api/approvals')) {
        return Promise.resolve(new Response(JSON.stringify([APPROVAL]), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      }
      return Promise.reject(new Error(`Unexpected fetch in this test: ${url}`))
    }),
  )
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

function setup(revealGoal: RevealGoal | null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const card = (reveal: RevealGoal | null): ReactNode => (
    <QueryClientProvider client={client}>
      <DetailsContext.Provider value={{ mode: 'auto', version: 0, revealGoal: reveal }}>
        <ProgressCard goal={GOAL} agentNames={{}} me="user-1" questions={[]} onStop={() => {}} onRetry={() => {}} />
      </DetailsContext.Provider>
    </QueryClientProvider>
  )
  const view = render(card(revealGoal))
  return { ...view, reveal: (next: RevealGoal | null) => view.rerender(card(next)) }
}

const toggle = () => screen.getByRole('button', { name: /Progress/ })

describe('ProgressCard reveal', () => {
  it('opens a folded card and focuses Approve when Review asks for it', async () => {
    const { reveal } = setup(null)
    await screen.findByRole('button', { name: 'Approve' })
    fireEvent.click(toggle())
    expect(toggle()).toHaveAttribute('aria-expanded', 'false')

    reveal({ goalId: 'g1', nonce: 1 })

    expect(toggle()).toHaveAttribute('aria-expanded', 'true')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Approve' })).toHaveFocus())
  })

  it('ignores a request for another goal', async () => {
    const { reveal } = setup(null)
    await screen.findByRole('button', { name: 'Approve' })
    fireEvent.click(toggle())
    reveal({ goalId: 'g2', nonce: 1 })
    expect(toggle()).toHaveAttribute('aria-expanded', 'false')
  })

  it('does not take focus for a request made before it mounted', async () => {
    setup({ goalId: 'g1', nonce: 3 })
    const approve = await screen.findByRole('button', { name: 'Approve' })
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(approve).not.toHaveFocus()
  })
})

describe('ProgressCard live card', () => {
  it('shows one compact header, plain step words, and a small Stop beside the Orchestrator link', async () => {
    setup(null)
    await screen.findByRole('button', { name: 'Approve' })
    expect(screen.getAllByText('Waiting for approval').length).toBeGreaterThan(0)
    expect(screen.getByText(/Step 2 of 2/)).toBeInTheDocument()
    const stop = screen.getByRole('button', { name: 'Stop' })
    expect(stop).toHaveClass('button-sm')
    expect(screen.getByRole('link', { name: 'Open in Orchestrator' })).toHaveAttribute('href', '/orchestrator?goal=g1')
    // The goal title is no longer drawn as a heading inside the card.
    expect(document.querySelector('.section-heading')).toBeNull()
  })
})
