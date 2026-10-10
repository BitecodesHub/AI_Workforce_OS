// @find: tests for the audit log, verification, filters, intact, vitest, AuditLog component tests, Audit log page
// @what: Automated tests that check the the audit log screen (/audit) behaves as users expect.
// @flow: Renders AuditLog from AuditLog.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { AuditLog } from './AuditLog'

/*
 * Verifying the chain. Entry numbers are shared by every workspace, so a workspace's last entry
 * number is usually higher than how many it holds; the result must not read as entries skipped.
 */

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.startsWith('/api/audit/verify')) return json(200, { verified: true, checked: 1585, lastSequence: 1599 })
      return json(200, [])
    }),
  )
  saveSession('t', {
    userId: 'u1',
    workspaceId: 'w1',
    permissions: ['audit:read', 'member:read', 'agent:read'],
    displayName: 'Ava',
    email: 'ava@example.test',
    role: 'owner',
  })
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

describe('AuditLog verification', () => {
  it('says every entry of this workspace is intact, without implying a gap', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <RouterProvider>
          <AuditLog />
        </RouterProvider>
      </QueryClientProvider>,
    )
    fireEvent.click(await screen.findByRole('button', { name: 'Verify integrity' }))
    expect(
      await screen.findByText('Chain verified: all 1,585 entries in this workspace are intact, the newest being entry 1,599.'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/entries checked/)).not.toBeInTheDocument()
  })

  it('keeps the filters on screen when they match nothing, and offers to clear them', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <RouterProvider>
          <AuditLog />
        </RouterProvider>
      </QueryClientProvider>,
    )
    fireEvent.change(await screen.findByLabelText('From'), { target: { value: '2030-01-01' } })
    expect(await screen.findByText('No entries match these filters')).toBeInTheDocument()
    expect(screen.getByLabelText('From')).toHaveValue('2030-01-01')

    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(await screen.findByText('No audit entries yet')).toBeInTheDocument()
    expect(screen.getByLabelText('From')).toHaveValue('')
  })
})
