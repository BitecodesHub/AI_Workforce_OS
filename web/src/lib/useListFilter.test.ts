// @find: tests for list filter, useListFilter, search, facets, query string
// @what: Unit tests for list filtering.
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider, useRouter } from './router'
import { useListFilter } from './useListFilter'

type Row = { id: string; title: string; status: string; tags: string[] }

const ROWS: Row[] = [
  { id: '1', title: 'Draft the weekly rota', status: 'running', tags: ['people'] },
  { id: '2', title: 'Summarise support inbox', status: 'failed', tags: ['support', 'email'] },
  { id: '3', title: 'Weekly invoice run', status: 'completed', tags: ['finance', 'email'] },
  { id: '4', title: 'Triage failed builds', status: 'failed', tags: ['engineering'] },
]

const wrapper = ({ children }: { children: ReactNode }) => createElement(RouterProvider, null, children)

function useHarness(rows: Row[] | undefined) {
  const filter = useListFilter({
    rows,
    text: (row: Row) => row.title,
    facets: { status: (row: Row) => row.status, tag: (row: Row) => row.tags },
  })
  return { filter, router: useRouter() }
}

beforeEach(() => {
  window.history.replaceState({}, '', '/tasks?goal=g1')
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('useListFilter', () => {
  it('shows every row with no filter', () => {
    const { result } = renderHook(() => useHarness(ROWS), { wrapper })
    expect(result.current.filter.filtered).toHaveLength(4)
    expect(result.current.filter.active).toBe(false)
    expect(result.current.filter.total).toBe(4)
    expect(result.current.filter.counts.status).toEqual({ running: 1, failed: 2, completed: 1 })
  })

  it('matches every search term, ignoring case, and keeps it in the URL', () => {
    const { result } = renderHook(() => useHarness(ROWS), { wrapper })
    const before = window.history.length
    act(() => result.current.filter.setQuery('WEEKLY run'))
    expect(result.current.filter.filtered.map((row) => row.id)).toEqual(['3'])
    expect(result.current.filter.active).toBe(true)
    expect(window.location.search).toBe('?goal=g1&q=WEEKLY+run')
    // Replaced, not pushed: Back leaves the list instead of undoing keystrokes.
    expect(window.history.length).toBe(before)
    expect(window.scrollTo).not.toHaveBeenCalled()
  })

  it('combines values within a facet with OR and facets with AND', () => {
    const { result } = renderHook(() => useHarness(ROWS), { wrapper })
    act(() => result.current.filter.toggle('status', 'failed'))
    act(() => result.current.filter.toggle('status', 'completed'))
    expect(result.current.filter.selected.status).toEqual(['failed', 'completed'])
    expect(window.location.search).toBe('?goal=g1&status=failed,completed')
    expect(result.current.filter.filtered.map((row) => row.id)).toEqual(['2', '3', '4'])

    act(() => result.current.filter.toggle('tag', 'email'))
    expect(result.current.filter.filtered.map((row) => row.id)).toEqual(['2', '3'])

    // Counts for one facet apply the others but not itself.
    expect(result.current.filter.counts.status).toEqual({ failed: 1, completed: 1 })
    expect(result.current.filter.counts.tag).toEqual({ support: 1, email: 2, finance: 1, engineering: 1 })

    act(() => result.current.filter.toggle('status', 'failed'))
    expect(result.current.filter.selected.status).toEqual(['completed'])
  })

  it('selects one value, clears a facet and clears everything', () => {
    const { result } = renderHook(() => useHarness(ROWS), { wrapper })
    act(() => result.current.filter.setOnly('status', 'running'))
    expect(result.current.filter.filtered.map((row) => row.id)).toEqual(['1'])
    act(() => result.current.filter.setOnly('status', null))
    expect(result.current.filter.selected.status).toEqual([])

    act(() => {
      result.current.filter.setQuery('weekly')
      result.current.filter.toggle('tag', 'email')
    })
    // Two changes in one event both land.
    expect(window.location.search).toBe('?goal=g1&q=weekly&tag=email')
    act(() => result.current.filter.clear())
    expect(window.location.search).toBe('?goal=g1')
    expect(result.current.filter.active).toBe(false)
  })

  it('reads its state from the URL it was opened with', () => {
    window.history.replaceState({}, '', '/tasks?q=inbox&status=failed')
    const { result } = renderHook(() => useHarness(ROWS), { wrapper })
    expect(result.current.filter.query).toBe('inbox')
    expect(result.current.filter.filtered.map((row) => row.id)).toEqual(['2'])
  })

  it('treats rows that have not loaded as an empty list', () => {
    const { result } = renderHook(() => useHarness(undefined), { wrapper })
    expect(result.current.filter.filtered).toEqual([])
    expect(result.current.filter.total).toBe(0)
  })
})

describe('router navigate options', () => {
  it('pushes and scrolls by default, replaces without scrolling on request', () => {
    const { result } = renderHook(() => useRouter(), { wrapper })
    const before = window.history.length
    act(() => result.current.navigate('/runs'))
    expect(window.history.length).toBe(before + 1)
    expect(window.scrollTo).toHaveBeenCalledTimes(1)
    expect(result.current.path).toBe('/runs')

    act(() => result.current.navigate('/runs?status=failed', { replace: true }))
    expect(window.history.length).toBe(before + 1)
    expect(window.scrollTo).toHaveBeenCalledTimes(1)
    expect(result.current.search.get('status')).toBe('failed')

    act(() => result.current.navigate('/runs?status=running', { replace: true, scroll: true }))
    expect(window.scrollTo).toHaveBeenCalledTimes(2)
  })

  it('keeps the same search object until the query string changes', () => {
    const { result, rerender } = renderHook(() => useRouter(), { wrapper })
    const first = result.current.search
    rerender()
    expect(result.current.search).toBe(first)
  })
})
