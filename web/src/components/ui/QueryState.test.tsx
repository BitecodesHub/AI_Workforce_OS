// @find: tests for query state, missing record, not found, page heading, 404, QueryState
// @what: Tests the heading shown when a record is missing.
// @flow: Covers QueryState.tsx.
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../lib/api'
import { QueryState } from './QueryState'

/* A detail page whose record is missing keeps a page heading; a section inside a page does not take one. */

const missing = { data: undefined, isLoading: false, error: new ApiError(404, 'not_found', 'Not here.', false, {}), refetch: vi.fn() }

describe('QueryState for a missing record', () => {
  it('heads a detail page with an h1 when it offers a way out', () => {
    render(
      <QueryState query={missing} permission="run:read" what="this run" notFound={<a href="/runs">See every run</a>}>
        {() => null}
      </QueryState>,
    )
    expect(screen.getByRole('heading', { level: 1, name: 'This item does not exist' })).toBeInTheDocument()
  })

  it('stays a section heading without one', () => {
    render(
      <QueryState query={missing} permission="run:read" what="this run">
        {() => null}
      </QueryState>,
    )
    expect(screen.getByRole('heading', { level: 2, name: 'This item does not exist' })).toBeInTheDocument()
  })
})
