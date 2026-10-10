// @find: tests for the incomplete answer notice, IncompleteAnswer, vitest, RunDetail component tests, Run page
// @what: Automated tests that check the the incomplete answer notice screen (/runs/:id) behaves as users expect.
// @flow: Renders RunDetail from RunDetail.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { IncompleteAnswer } from './RunDetail'

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
