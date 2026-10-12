// @find: tests for query state, missing record, not found, page heading, 404, stale banner keeps content mounted, stale data keeps the screen, QueryState
// @what: Tests the heading shown when a record is missing, and that a polling screen's content stays mounted, with its own state, while the stale banner comes and goes.
// @flow: Covers QueryState.tsx.
import { useEffect, useState } from 'react'
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
  type Query = { data: { lines: number }; isLoading: boolean; error: unknown; refetch: () => unknown; dataUpdatedAt: number }
  const ok: Query = { data: { lines: 3 }, isLoading: false, error: null, refetch: vi.fn(), dataUpdatedAt: 0 }
  const failed: Query = { ...ok, error: new ApiError(503, 'unavailable', 'Down.', true, {}) }

  it('keeps the content mounted while the stale banner comes and goes', () => {
    let mounts = 0
    function Content() {
      useEffect(() => {
        mounts += 1
      }, [])
      return <p>Content</p>
    }
    const view = (query: Query) => (
      <QueryState query={query} permission="run:read" what="the feed" keepData>
        {() => <Content />}
      </QueryState>
    )

    const { rerender } = render(view(ok))
    const content = screen.getByText('Content')
    rerender(view(failed))
    expect(screen.getByText(/Lost connection/)).toBeInTheDocument()
    rerender(view(ok))
    expect(screen.queryByText(/Lost connection/)).not.toBeInTheDocument()

    expect(mounts).toBe(1)
    expect(screen.getByText('Content')).toBe(content)
  })

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
