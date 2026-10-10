// @find: tests for approval queries, pending approvals paging, decided approvals, approval count, run approval, decide approvals, bulk decision, approval item row
// @what: Tests for approval paging helpers and the queue, history, count and bulk decision hooks.
import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  DECIDED_PAGE_SIZE,
  PENDING_PAGE_SIZE,
  approvalItemRow,
  flattenApprovalPages,
  nextApprovalPage,
  useApprovalCount,
  useDecideApprovals,
  useDecidedApprovalPages,
  usePendingApprovalPages,
  useRunApproval,
} from './approvalQueries'
import type { ApprovalItem } from './approvalQueries'
import { clearSession, saveSession } from './session'

/*
 * The queue and its history a page at a time, and a bulk decision. What they ask the server for is
 * the contract the Approvals screen leans on: the queue soonest-expiring first, the history from
 * the server rather than from this visit, and every write refreshing the approvals and the board.
 */

function item(id: string, overrides: Partial<ApprovalItem> = {}): ApprovalItem {
  return {
    id,
    runId: `run-${id}`,
    agentId: 'agent-1',
    tool: 'gmail.send_message',
    actionClass: 'OUTBOUND',
    summary: 'Send something outside the workspace using gmail.send_message',
    payload: '{"to":"jane@customer.example"}',
    status: 'pending',
    requestedAt: '2026-10-04T09:00:00Z',
    expiresAt: '2026-10-05T09:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    goalId: null,
    goalTitle: null,
    requestedBy: null,
    taskInstruction: null,
    canDecide: true,
    ...overrides,
  }
}

const many = (n: number, from = 0) => Array.from({ length: n }, (_, index) => item(`a-${from + index}`))

let requests: Array<{ url: string; method: string; body: unknown }>
let respond: (url: string, method: string) => unknown

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const Wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
  return { client, Wrapper }
}

beforeEach(() => {
  requests = []
  respond = () => []
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      requests.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      return Promise.resolve(json(respond(url, method)))
    }),
  )
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['approval:read', 'approval:decide'],
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  })
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

describe('paging helpers', () => {
  it('offers the next page only while pages come back full', () => {
    expect(nextApprovalPage(many(PENDING_PAGE_SIZE), 0, PENDING_PAGE_SIZE)).toBe(1)
    expect(nextApprovalPage(many(PENDING_PAGE_SIZE - 1), 3, PENDING_PAGE_SIZE)).toBeUndefined()
    expect(nextApprovalPage([], 0, PENDING_PAGE_SIZE)).toBeUndefined()
  })

  it('lists each approval once even when the queue shifted between two pages', () => {
    const merged = flattenApprovalPages([[item('1'), item('2')], [item('2'), item('3')]])
    expect(merged.map((approval) => approval.id)).toEqual(['1', '2', '3'])
  })

  it('fills what an older server leaves out, and falls back to the role for who may decide', () => {
    const { canDecide: _omitted, ...bare } = item('1')
    void _omitted
    const row = approvalItemRow(bare)
    expect(row).toMatchObject({ decidedBy: null, goalTitle: null, requestedBy: null, taskInstruction: null })
    // The signed-in role holds approval:decide.
    expect(row.canDecide).toBe(true)
    // What the server says is kept, even when it says no.
    expect(approvalItemRow(item('2', { canDecide: false })).canDecide).toBe(false)
  })
})

describe('usePendingApprovalPages', () => {
  it('asks for the queue a page at a time, and stops when a page comes back short', async () => {
    respond = (url) => (url.includes('page=0') ? many(PENDING_PAGE_SIZE) : many(3, PENDING_PAGE_SIZE))
    const { Wrapper } = wrapper()

    const { result } = renderHook(() => usePendingApprovalPages(), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.data).toBeDefined())

    expect(requests[0]?.url).toBe(`/api/approvals?status=pending&page=0&size=${PENDING_PAGE_SIZE}`)
    expect(result.current.hasNextPage).toBe(true)

    await act(async () => {
      await result.current.fetchNextPage()
    })
    await waitFor(() => expect(result.current.data?.pages).toHaveLength(2))

    expect(requests[1]?.url).toBe(`/api/approvals?status=pending&page=1&size=${PENDING_PAGE_SIZE}`)
    expect(result.current.hasNextPage).toBe(false)
    expect(flattenApprovalPages(result.current.data?.pages ?? [])).toHaveLength(PENDING_PAGE_SIZE + 3)
  })
})

describe('useDecidedApprovalPages', () => {
  it('asks the server for every decision by default, newest first', async () => {
    respond = () => [item('d-1', { status: 'rejected', decisionNote: 'Wrong recipient.' })]
    const { Wrapper } = wrapper()

    const { result } = renderHook(() => useDecidedApprovalPages({}), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.data).toBeDefined())

    expect(requests[0]?.url).toBe(`/api/approvals?status=decided&page=0&size=${DECIDED_PAGE_SIZE}`)
    expect(result.current.data?.pages[0]?.[0]?.decisionNote).toBe('Wrong recipient.')
    expect(result.current.hasNextPage).toBe(false)
  })

  it('narrows by outcome and by agent on the server, not only on what has loaded', async () => {
    const { Wrapper } = wrapper()

    const { result } = renderHook(() => useDecidedApprovalPages({ status: 'rejected', agentId: 'agent-7' }), {
      wrapper: Wrapper,
    })
    await waitFor(() => expect(result.current.data).toBeDefined())

    expect(requests[0]?.url).toBe(`/api/approvals?status=rejected&agentId=agent-7&page=0&size=${DECIDED_PAGE_SIZE}`)
  })
})

describe('useRunApproval and useApprovalCount', () => {
  it('finds the request one run is parked on, by run', async () => {
    respond = () => [item('p-1', { runId: 'run-9' })]
    const { Wrapper } = wrapper()

    const { result } = renderHook(() => useRunApproval('run-9'), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.data).toBeDefined())

    expect(requests[0]?.url).toBe('/api/approvals?status=pending&runId=run-9&size=1')
    expect(result.current.data?.id).toBe('p-1')
  })

  it('reads nothing as no request, not as an error', async () => {
    respond = () => []
    const { Wrapper } = wrapper()

    const { result } = renderHook(() => useRunApproval('run-9'), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toBeNull()
  })

  it('does not ask for a run that is not named, or when told not to', () => {
    const { Wrapper } = wrapper()
    renderHook(() => useRunApproval(''), { wrapper: Wrapper })
    renderHook(() => useRunApproval('run-1', { enabled: false }), { wrapper: Wrapper })

    expect(requests).toEqual([])
  })

  it('counts what is waiting without loading a payload', async () => {
    respond = () => ({ pending: 7, canDecide: 4 })
    const { Wrapper } = wrapper()

    const { result } = renderHook(() => useApprovalCount({ agentId: 'agent-2' }), { wrapper: Wrapper })
    await waitFor(() => expect(result.current.data).toBeDefined())

    expect(requests[0]?.url).toBe('/api/approvals/count?agentId=agent-2')
    expect(result.current.data).toEqual({ pending: 7, canDecide: 4 })
  })
})

describe('useDecideApprovals', () => {
  it('posts every id with one decision and one note, and refreshes the approvals and the board', async () => {
    respond = () => ({
      results: [
        { id: 'a', result: 'decided', status: 'approved', message: null },
        { id: 'b', result: 'already_decided', status: 'rejected', message: 'That approval has already been decided.' },
      ],
    })
    const { client, Wrapper } = wrapper()
    const invalidate = vi.spyOn(client, 'invalidateQueries')

    const { result } = renderHook(() => useDecideApprovals(), { wrapper: Wrapper })
    let answer: Awaited<ReturnType<typeof result.current.mutateAsync>> | undefined
    await act(async () => {
      answer = await result.current.mutateAsync({ ids: ['a', 'b'], approved: true, note: 'Fine.' })
    })

    expect(requests[0]).toMatchObject({
      url: '/api/approvals/decisions',
      method: 'POST',
      body: { ids: ['a', 'b'], approved: true, note: 'Fine.' },
    })
    expect(answer?.results.map((row) => row.result)).toEqual(['decided', 'already_decided'])
    const keys = invalidate.mock.calls.map((call) => JSON.stringify(call[0]?.queryKey))
    expect(keys).toContain('["approvals"]')
    expect(keys).toContain('["board"]')
  })

  it('refreshes them even when the call failed, so what was decided clears', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('{"code":"internal_error","detail":"x"}', { status: 500 }))),
    )
    const { client, Wrapper } = wrapper()
    const invalidate = vi.spyOn(client, 'invalidateQueries')

    const { result } = renderHook(() => useDecideApprovals(), { wrapper: Wrapper })
    await act(async () => {
      await result.current.mutateAsync({ ids: ['a'], approved: false }).catch(() => undefined)
    })

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['approvals'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['board'] })
  })
})
