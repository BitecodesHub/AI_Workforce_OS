import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { createElement } from 'react'
import type { ReactElement, ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SCHEDULE_RUNS_PAGE_SIZE, flattenRunPages, nextRunsPage, useScheduleRunPages } from './scheduleQueries'
import type { ScheduleRun, ScheduleRunsPage } from './scheduleQueries'

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function wrapper(): (props: { children: ReactNode }) => ReactElement {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }) => createElement(QueryClientProvider, { client }, children)
}

afterEach(() => {
  vi.unstubAllGlobals()
})

const run = (id: string): ScheduleRun => ({
  id,
  title: 'Weekly digest',
  status: 'completed',
  createdAt: '2026-10-01T09:00:00Z',
  completedAt: null,
})

describe('nextRunsPage', () => {
  it('asks for the next page only while the server says there is an older one', () => {
    expect(nextRunsPage({ page: 0, hasMore: true })).toBe(1)
    expect(nextRunsPage({ page: 3, hasMore: false })).toBeUndefined()
  })
})

describe('flattenRunPages', () => {
  it('keeps newest-first order and lists a run once even when a page shifted underneath', () => {
    const pages = [{ runs: [run('r3'), run('r2')] }, { runs: [run('r2'), run('r1')] }]
    expect(flattenRunPages(pages).map((r) => r.id)).toEqual(['r3', 'r2', 'r1'])
  })
})

describe('useScheduleRunPages', () => {
  it('loads one page, then the older one on request, and stops when there is no more', async () => {
    const first: ScheduleRunsPage = { runs: [run('r2')], page: 0, size: 1, total: 2, hasMore: true }
    const second: ScheduleRunsPage = { runs: [run('r1')], page: 1, size: 1, total: 2, hasMore: false }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(first))
      .mockResolvedValueOnce(jsonResponse(second))
    vi.stubGlobal('fetch', fetchMock)

    const { result } = renderHook(() => useScheduleRunPages('s1'), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(String(fetchMock.mock.calls[0]?.[0])).toContain(`/api/schedules/s1/runs?page=0&size=${SCHEDULE_RUNS_PAGE_SIZE}`)
    expect(result.current.hasNextPage).toBe(true)

    await act(async () => {
      await result.current.fetchNextPage()
    })

    await waitFor(() => expect(result.current.data?.pages).toHaveLength(2))
    expect(String(fetchMock.mock.calls[1]?.[0])).toContain('/api/schedules/s1/runs?page=1')
    expect(flattenRunPages(result.current.data?.pages ?? []).map((r) => r.id)).toEqual(['r2', 'r1'])
    expect(result.current.hasNextPage).toBe(false)
  })

  it('fills a missing completion time with null', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({ runs: [{ id: 'r1', title: 'x', status: 'running', createdAt: '2026-10-01T09:00:00Z' }], page: 0, size: 20, total: 1, hasMore: false }),
      ),
    )

    const { result } = renderHook(() => useScheduleRunPages('s1'), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.pages[0]?.runs[0]?.completedAt).toBeNull()
  })

  it('asks for nothing without a schedule', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    renderHook(() => useScheduleRunPages(''), { wrapper: wrapper() })

    expect(fetchMock).not.toHaveBeenCalled()
  })
})
