// @find: tests for ErrorCard, chat error, agent failed, try again, retry, incomplete answer, step limit, output limit, error message, run failed
// @what: Automated tests for ErrorCard.
// @flow: Run with the web test runner; covers ErrorCard.
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ChatMessage } from '../../lib/queries'
import { ErrorCard } from './ErrorCard'
import { resendText } from './chatModel'

/*
 * "Try again" on a coordinator error puts the person's own request back in the message box. It
 * never puts the error sentence there, where pressing Enter would send it to an agent as work.
 */

const ERROR_SENTENCE = 'The coordinator could not decide who takes this. Try again, or mention an agent with @.'

function message(overrides: Partial<ChatMessage> & Pick<ChatMessage, 'id' | 'position'>): ChatMessage {
  return {
    authorKind: 'user',
    authorId: 'user-1',
    agentId: null,
    kind: 'text',
    content: '',
    detail: {},
    goalId: null,
    createdAt: '2026-10-01T00:00:00Z',
    ...overrides,
  }
}

function renderError(error: ChatMessage, thread: ChatMessage[]) {
  const onResend = vi.fn()
  render(
    <ErrorCard
      message={error}
      live={false}
      requestText={resendText(error, [...thread, error])}
      agentsForReroute={[]}
      onRetry={() => {}}
      onResend={onResend}
      onReroute={() => {}}
    />,
  )
  return onResend
}

describe('ErrorCard "Try again"', () => {
  it('prefills the request the error recorded, not the error sentence', () => {
    const error = message({
      id: 'e1',
      position: 1,
      kind: 'error',
      authorKind: 'coordinator',
      authorId: null,
      content: ERROR_SENTENCE,
      detail: { reason: ERROR_SENTENCE, requestText: '@Research compare the three supplier quotes' },
    })
    const onResend = renderError(error, [message({ id: 'u1', position: 0, content: '@Research compare the three supplier quotes' })])

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onResend).toHaveBeenCalledWith('@Research compare the three supplier quotes')
    expect(onResend).not.toHaveBeenCalledWith(ERROR_SENTENCE)
  })

  it('falls back to the nearest earlier message the person wrote', () => {
    const error = message({ id: 'e1', position: 3, kind: 'error', authorKind: 'coordinator', authorId: null, content: ERROR_SENTENCE })
    const onResend = renderError(error, [
      message({ id: 'u1', position: 0, content: 'An older request' }),
      message({ id: 'u2', position: 2, content: 'What is our refund policy?' }),
    ])

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(onResend).toHaveBeenCalledWith('What is our refund policy?')
  })

  it('offers no "Try again" when no request can be found', () => {
    const error = message({ id: 'e1', position: 0, kind: 'error', authorKind: 'coordinator', authorId: null, content: ERROR_SENTENCE })
    renderError(error, [])
    expect(screen.getByText(ERROR_SENTENCE)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
  })
})
