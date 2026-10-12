// @find: tests for query state, missing record, not found, page heading, 404, stale data keeps the screen, QueryState
// @what: Tests the heading shown when a record is missing, and that a failed poll with keepData adds the stale banner without remounting the screen beneath.
// @flow: Covers QueryState.tsx.
import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
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

describe('QueryState keeping data through a failed poll', () => {
  /** A screen with state of its own, counting how often it was mounted. */
  let mounts = 0
  function Counter() {
    const [count, setCount] = useState(() => {
      mounts += 1
      return 0
    })
    return (
      <button type="button" onClick={() => setCount(count + 1)}>
        Clicked {count}
      </button>
    )
  }

  it('adds the stale banner above the screen without remounting it, and takes it away again', () => {
    mounts = 0
    const loaded = { data: ['a'], isLoading: false, error: null as unknown, refetch: vi.fn(), dataUpdatedAt: Date.parse('2026-10-13T09:00:00Z') }
    const view = (query: typeof loaded) => (
      <QueryState query={query} permission="approval:read" what="the approvals queue" keepData>
        {() => <Counter />}
      </QueryState>
    )
    const { rerender } = render(view(loaded))
    fireEvent.click(screen.getByRole('button', { name: 'Clicked 0' }))

    rerender(view({ ...loaded, error: new ApiError(0, 'network_error', 'Offline.', true, {}) }))
    expect(screen.getByText(/Lost connection/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clicked 1' })).toBeInTheDocument()

    rerender(view(loaded))
    expect(screen.queryByText(/Lost connection/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clicked 1' })).toBeInTheDocument()
    expect(mounts).toBe(1)
  })
})
