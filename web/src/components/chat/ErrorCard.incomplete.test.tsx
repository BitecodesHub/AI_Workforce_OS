// @find: tests for ErrorCard.incomplete, incomplete, chat error, agent failed, try again, retry, incomplete answer, step limit, output limit, error message, run failed
// @what: Automated tests for ErrorCard.incomplete.
// @flow: Run with the web test runner; covers ErrorCard.
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ChatMessage } from '../../lib/queries'
import { ErrorCard, incompleteAnswerOf } from './ErrorCard'

/*
 * An agent that stopped at its step or output limit still wrote something. The error card offers
 * it, folded away, as an incomplete answer.
 */

function errorMessage(detail: Record<string, unknown>): ChatMessage {
  return {
    id: 'e1',
    position: 4,
    authorKind: 'agent',
    authorId: null,
    agentId: 'agent-1',
    kind: 'error',
    content: 'The agent reached its step limit of 2 without finishing.',
    detail: { reason: 'The agent reached its step limit of 2 without finishing.', code: 'step_limit', ...detail },
    goalId: null,
    createdAt: '2026-10-04T10:00:00Z',
  }
}

function renderCard(message: ChatMessage) {
  return render(
    <ErrorCard
      message={message}
      live={false}
      requestText={null}
      agentsForReroute={[]}
      onRetry={() => {}}
      onResend={() => {}}
      onReroute={() => {}}
    />,
  )
}

describe('ErrorCard incomplete answer', () => {
  it('offers what the agent wrote, folded away until it is opened', () => {
    const { container } = renderCard(errorMessage({ incompleteAnswer: 'Three of the five suppliers are covered so far.' }))

    const disclosure = container.querySelector('details')
    expect(disclosure).not.toBeNull()
    expect(disclosure).not.toHaveAttribute('open')
    expect(screen.getByText('Incomplete answer')).toBeInTheDocument()

    fireEvent.click(screen.getByText('Incomplete answer'))
    expect(disclosure).toHaveAttribute('open')
    expect(screen.getByText('Three of the five suppliers are covered so far.')).toBeInTheDocument()
  })

  it('shows no such section when the task kept no text', () => {
    const { container } = renderCard(errorMessage({}))
    expect(container.querySelector('details')).toBeNull()
    expect(screen.queryByText('Incomplete answer')).toBeNull()
  })

  it('ignores an answer that is blank or not text', () => {
    expect(incompleteAnswerOf(errorMessage({ incompleteAnswer: '   ' }))).toBeNull()
    expect(incompleteAnswerOf(errorMessage({ incompleteAnswer: 42 }))).toBeNull()
    expect(incompleteAnswerOf(errorMessage({ incompleteAnswer: 'Half a report.' }))).toBe('Half a report.')
  })
})
