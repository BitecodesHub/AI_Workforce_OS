// @find: tests for the incomplete answer notice, IncompleteAnswer, answer card access on the run page, vitest, RunDetail component tests, Run page
// @what: Automated tests that check the the incomplete answer notice screen (/runs/:id) behaves as users expect.
// @flow: Renders RunDetail from RunDetail.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { IncompleteAnswer, answerCardAccess } from './RunDetail'

/*
 * A run that stopped at a limit shows what it wrote as an incomplete answer, apart from the
 * answer a finished run gives.
 */

describe('IncompleteAnswer', () => {
  it('names what it is, says it is unfinished, and shows the text', () => {
    render(<IncompleteAnswer text="Three of the five suppliers are covered so far." instruction="Compare the five supplier quotes" />)

    expect(screen.getByText('Incomplete answer')).toBeInTheDocument()
    expect(screen.getByText(/stopped before it finished/)).toBeInTheDocument()
    expect(screen.getByText('Three of the five suppliers are covered so far.')).toBeInTheDocument()
    expect(screen.getByText(/Compare the five supplier quotes/)).toBeInTheDocument()
  })

  it('does not call itself an answer', () => {
    render(<IncompleteAnswer text="Half a report." />)
    expect(screen.queryByText('Answer')).toBeNull()
  })
})

describe('answerCardAccess', () => {
  const chatGoal = { source: 'chat', conversationId: 'c1' } as const
  const roles = (...codes: string[]) => (permission: string) => codes.includes(permission)

  it('lets a role that can start work act through the conversation', () => {
    expect(answerCardAccess(chatGoal, roles('chat:use', 'task:create'))).toEqual({ chatHref: '/chat?c=c1', readOnly: false })
  })

  it('keeps a role that can use Chat but not start work read-only, as Chat does', () => {
    expect(answerCardAccess(chatGoal, roles('chat:use'))).toEqual({ chatHref: '/chat?c=c1', readOnly: true })
  })

  it('has no conversation link for a run from no conversation, or for a role without Chat', () => {
    expect(answerCardAccess({ source: 'manual', conversationId: null }, roles('chat:use', 'task:create'))).toEqual({
      chatHref: undefined,
      readOnly: false,
    })
    expect(answerCardAccess(chatGoal, roles('task:create'))).toEqual({ chatHref: undefined, readOnly: true })
  })
})
