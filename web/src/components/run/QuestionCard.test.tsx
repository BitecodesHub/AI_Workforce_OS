// @find: tests for QuestionCard, agent question, clarifying question, answer question, choose option, Other answer, answered count, question closed, run waiting for answer, Approvals page questions
// @what: Automated tests for QuestionCard.
// @flow: Run with the web test runner; covers QuestionCard.
import axe from 'axe-core'
import type { ComponentProps } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { QuestionCard } from './QuestionCard'
import type { RunQuestion } from '../../lib/queries'

/*
 * The clarifying question card, in every state it can be in. See B4.5 for the full behaviour this
 * checks against; this file covers the rules that matter most for a person answering for real:
 * an option never sends by itself, a gap is explained in words that match where the card sits, and
 * a successful send lands focus on the result rather than leaving it on a control that just
 * disappeared.
 */

const AGENT_NAME = 'Ops Agent'

function makeQuestion(overrides: Partial<RunQuestion> = {}): RunQuestion {
  return {
    id: 'q-1',
    runId: 'run-1',
    taskId: null,
    goalId: 'goal-1',
    conversationId: null,
    agentId: 'agent-1',
    goalTitle: 'Plan the offsite',
    status: 'pending',
    questions: [
      {
        id: 'i1',
        header: 'Date range',
        question: 'Which period should the report cover?',
        multiSelect: false,
        options: [
          { label: 'Last quarter', description: 'July to September 2026.', recommended: true },
          { label: 'Year to date', description: 'January to today.', recommended: false },
        ],
      },
    ],
    answer: null,
    answeredBy: null,
    answeredVia: null,
    answeredAt: null,
    requestedBy: 'user-1',
    createdAt: new Date(Date.now() - 2 * 60_000).toISOString(),
    expiresAt: new Date(Date.now() + 23 * 60 * 60_000).toISOString(),
    closedReason: null,
    canAnswer: true,
    extendable: false,
    runStatus: 'waiting_input',
    ...overrides,
  }
}

function renderCard(overrides: Partial<ComponentProps<typeof QuestionCard>> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const question = overrides.question ?? makeQuestion()
  return render(
    <QueryClientProvider client={client}>
      <QuestionCard question={question} agentName={AGENT_NAME} via="orchestrator" {...overrides} />
    </QueryClientProvider>,
  )
}

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchMock = vi.fn(() => Promise.reject(new Error('No network expected in this test')))
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('QuestionCard, open state', () => {
  it('has no axe violations', async () => {
    const { container } = renderCard()
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((v) => v.id)).toEqual([])
  })

  it('has no axe violations when compact', async () => {
    const { container } = renderCard({ compact: true })
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((v) => v.id)).toEqual([])
  })

  it('never sends when an option is clicked', () => {
    renderCard()
    fireEvent.click(screen.getByRole('radio', { name: /Last quarter/ }))
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows the composer wording when a gap is left in Chat', () => {
    renderCard({ via: 'chat', onReplyInOwnWords: vi.fn() })
    fireEvent.click(screen.getByRole('button', { name: /Send answer/ }))
    expect(screen.getByText('Answer every question, or reply in your own words.')).toBeInTheDocument()
  })

  it('shows the Other wording when a gap is left outside Chat', () => {
    renderCard({ via: 'orchestrator' })
    fireEvent.click(screen.getByRole('button', { name: /Send answer/ }))
    expect(screen.getByText('Answer every question, or use Other to write your own answer.')).toBeInTheDocument()
  })

  it('submits on Ctrl+Enter', async () => {
    fetchMock.mockResolvedValue(
      new Response(
        JSON.stringify({ question: makeQuestion({ status: 'answered' }), runStatus: 'completed' }),
        { status: 202 },
      ),
    )
    const { container } = renderCard()
    fireEvent.click(screen.getByRole('radio', { name: /Last quarter/ }))
    fireEvent.keyDown(container.querySelector('form')!, { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    const [path, init] = fetchMock.mock.calls[0]!
    expect(path).toBe('/api/orchestrator/questions/q-1/answer')
    expect((init as RequestInit).method).toBe('POST')
  })

  it('moves focus to the closed card once the send succeeds', async () => {
    fetchMock.mockResolvedValue(
      new Response(
        JSON.stringify({
          question: makeQuestion({
            status: 'answered',
            answer: { answers: [{ questionId: 'i1', selected: ['Last quarter'], other: null }], note: null, skipped: false },
            answeredBy: 'me',
            answeredVia: 'orchestrator',
            answeredAt: new Date().toISOString(),
          }),
          runStatus: 'completed',
        }),
        { status: 202 },
      ),
    )
    const { container } = renderCard({ me: 'me' })
    fireEvent.click(screen.getByRole('radio', { name: /Last quarter/ }))
    fireEvent.click(screen.getByRole('button', { name: /Send answer/ }))
    await waitFor(() => expect(container.querySelector('.question-card-closed')).not.toBeNull())
    const closed = container.querySelector('.question-card-closed')
    expect(document.activeElement).toBe(closed)
  })
})

describe('QuestionCard, read-only for a viewer who cannot answer', () => {
  it('shows the options disabled and who can answer', () => {
    renderCard({
      question: makeQuestion({ canAnswer: false, requestedBy: 'user-2' }),
      nameOf: (id) => (id === 'user-2' ? 'Priya' : id),
    })
    expect(screen.getByRole('radio', { name: /Last quarter/ })).toBeDisabled()
    expect(screen.getByText(/Waiting for Priya to answer\. Anyone who can cancel work can also answer it\./)).toBeInTheDocument()
  })
})

describe('QuestionCard, context line outside Chat', () => {
  it('names the goal and who it was asked of, and says when answering for someone else', () => {
    renderCard({
      via: 'orchestrator',
      me: 'me',
      nameOf: (id) => (id === 'user-1' ? 'Priya' : id),
      question: makeQuestion({ requestedBy: 'user-1', canAnswer: true }),
    })
    expect(screen.getByText(/For: Plan the offsite · Asked of Priya/)).toBeInTheDocument()
    expect(screen.getByText(/You are answering for Priya\./)).toBeInTheDocument()
  })

  it('is not shown in Chat', () => {
    renderCard({ via: 'chat', me: 'me', question: makeQuestion({ requestedBy: 'user-1' }) })
    expect(screen.queryByText(/Asked of/)).toBeNull()
  })
})

describe('QuestionCard, closed states', () => {
  it('says the agent is finishing when the run is still active', () => {
    renderCard({
      question: makeQuestion({ status: 'expired', runStatus: 'running', expiresAt: new Date().toISOString() }),
    })
    expect(screen.getByText(/is finishing with what it has\./)).toBeInTheDocument()
  })

  it('says the agent finished when the run is done', () => {
    renderCard({
      question: makeQuestion({ status: 'expired', runStatus: 'completed', expiresAt: new Date().toISOString() }),
    })
    expect(screen.getByText(/finished with what it had\./)).toBeInTheDocument()
  })

  it('shows the reason a question was withdrawn', () => {
    renderCard({
      question: makeQuestion({ status: 'cancelled', closedReason: 'The goal this run belonged to was cancelled.' }),
    })
    expect(screen.getByText(/This question was withdrawn: The goal this run belonged to was cancelled\./)).toBeInTheDocument()
  })

  it('falls back to a generic reason when none was recorded', () => {
    renderCard({ question: makeQuestion({ status: 'cancelled', closedReason: null }) })
    expect(screen.getByText(/This question was withdrawn because the work was stopped\./)).toBeInTheDocument()
  })
})
