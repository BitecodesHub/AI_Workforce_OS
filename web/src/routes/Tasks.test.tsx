import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Goal } from '../lib/queries'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { Tasks } from './Tasks'

/*
 * The Tasks status filter is sent to the server, so "Failed" finds every failed goal in the
 * workspace, including ones far older than the pages already loaded.
 */

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function goal(id: string, title: string, status: string): Goal {
  return {
    id,
    title,
    description: '',
    status,
    createdAt: '2026-10-04T00:00:00Z',
    completedAt: null,
    source: 'manual',
    requestedBy: null,
    conversationId: null,
    scheduleId: null,
    tasks: [],
  }
}

/** Recent goals, none of them failed. The failed one is older than anything the first page holds. */
const RECENT = [goal('g1', 'Plan the offsite', 'completed'), goal('g2', 'Order the catering', 'running')]
const OLD_FAILURE = goal('g0', 'Reconcile last quarter', 'failed')

let goalRequests: string[]

beforeEach(() => {
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['task:read', 'task:create', 'agent:read'],
    displayName: 'Maya Manager',
    email: 'maya@example.com',
    role: 'manager',
  })
  goalRequests = []
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString()
      if (url.startsWith('/api/goals?')) {
        goalRequests.push(url)
        const status = new URL(url, 'http://localhost').searchParams.get('status')
        return Promise.resolve(json(status === 'failed' ? [OLD_FAILURE] : RECENT))
      }
      if (url === '/api/agents') return Promise.resolve(json([]))
      return Promise.reject(new Error(`No network in tests: ${url}`))
    }),
  )
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

function renderTasks(path: string) {
  window.history.pushState({}, '', path)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <Tasks />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
  return client
}

const statusParam = (url: string) => new URL(url, 'http://localhost').searchParams.get('status')

describe('Tasks: the status filter runs on the server', () => {
  it('asks for no status at first, then for failed goals when Failed is chosen, and shows the older failure', async () => {
    // Enough rows that the search and filter bar is offered at all.
    const many = Array.from({ length: 6 }, (_, index) => goal(`g${index + 10}`, `Goal ${index + 10}`, 'completed'))
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString()
        if (url.startsWith('/api/goals?')) {
          goalRequests.push(url)
          return Promise.resolve(json(statusParam(url) === 'failed' ? [OLD_FAILURE] : [...RECENT, ...many]))
        }
        if (url === '/api/agents') return Promise.resolve(json([]))
        return Promise.reject(new Error(`No network in tests: ${url}`))
      }),
    )
    renderTasks('/tasks')
    expect(await screen.findByText('Plan the offsite')).toBeInTheDocument()
    expect(goalRequests.map(statusParam)).toEqual([null])

    fireEvent.click(await screen.findByRole('button', { name: /^Failed/ }))

    await waitFor(() => expect(goalRequests.map(statusParam)).toContain('failed'))
    expect(await screen.findByText('Reconcile last quarter')).toBeInTheDocument()
    expect(window.location.search).toContain('status=failed')
  })

  it('reads the status from the link, so a shared Failed link asks for failed goals straight away', async () => {
    renderTasks('/tasks?status=failed')

    expect(await screen.findByText('Reconcile last quarter')).toBeInTheDocument()
    expect(goalRequests.map(statusParam)).toEqual(['failed'])
  })

  it('does not send a status the server would refuse', async () => {
    renderTasks('/tasks?status=exploded')

    await waitFor(() => expect(goalRequests).toHaveLength(1))
    expect(statusParam(goalRequests[0]!)).toBeNull()
  })

  it('says nothing matches, with a way back, when the chosen status has no goals', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString()
        if (url.startsWith('/api/goals?')) return Promise.resolve(json([]))
        if (url === '/api/agents') return Promise.resolve(json([]))
        return Promise.reject(new Error(`No network in tests: ${url}`))
      }),
    )
    renderTasks('/tasks?status=cancelled')

    // Not "No goals yet": the workspace may well have goals, just none that were cancelled.
    expect(await screen.findByRole('search', { name: 'Search goals' })).toBeInTheDocument()
    expect(screen.queryByText('No goals yet')).toBeNull()
  })
})
