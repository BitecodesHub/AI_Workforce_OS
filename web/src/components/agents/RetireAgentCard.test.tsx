// @find: tests for RetireAgentCard, retire agent, restore agent, archive agent, delete agent, bring back agent, fallback agent, Employee cannot be retired, agent page, agent:delete, Retire card
// @what: Automated tests for RetireAgentCard.
// @flow: Run with the web test runner; covers RetireAgentCard.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearSession, saveSession } from '../../lib/session'
import { ToastProvider } from '../../lib/toast'
import { RetireAgentCard, retireBlockedReason } from './RetireAgentCard'

if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const LEGAL = { id: 'a-legal', name: 'Legal', status: 'active', fallback: false }

let calls: Array<{ url: string; method: string }>

function open(agent: typeof LEGAL, permissions: string[]) {
  calls = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      calls.push({ url, method: init.method ?? 'GET' })
      const status = url.endsWith('/retire') ? 'retired' : 'paused'
      return new Response(JSON.stringify({ ...agent, status }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }),
  )
  saveSession('token', {
    userId: 'u1',
    workspaceId: 'ws-1',
    permissions,
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'owner',
  })
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
      <ToastProvider>
        <RetireAgentCard agent={agent} />
      </ToastProvider>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

describe('RetireAgentCard', () => {
  it('asks first, then retires with POST /retire', async () => {
    open(LEGAL, ['agent:read', 'agent:update', 'agent:delete'])

    fireEvent.click(screen.getByRole('button', { name: 'Retire agent' }))
    const dialog = screen.getByRole('dialog', { name: 'Retire Legal?' })
    expect(calls).toEqual([])
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Retire Legal' }))
    })
    expect(calls).toEqual([{ url: '/api/agents/a-legal/retire', method: 'POST' }])
  })

  it('restores a retired agent without asking', async () => {
    open({ ...LEGAL, status: 'retired' }, ['agent:read', 'agent:update', 'agent:delete'])

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Restore Legal' }))
    })
    expect(calls).toEqual([{ url: '/api/agents/a-legal/restore', method: 'POST' }])
  })

  it('is disabled for the General Employee, with the reason beside it', () => {
    open({ ...LEGAL, name: 'General Employee', fallback: true }, ['agent:read', 'agent:update', 'agent:delete'])

    const button = screen.getByRole('button', { name: 'Retire agent' })
    expect(button).toBeDisabled()
    expect(button).toHaveAccessibleDescription(/cannot be retired/)
  })

  it('is disabled for a role that may change agents but not retire them, saying who can', () => {
    open(LEGAL, ['agent:read', 'agent:update'])

    const button = screen.getByRole('button', { name: 'Retire agent' })
    expect(button).toBeDisabled()
    expect(button).toHaveAccessibleDescription('Your role cannot retire or restore agents. An owner or admin can.')
  })

  it('is not shown to somebody who cannot change agents at all', () => {
    open(LEGAL, ['agent:read'])
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByText(/Retire/)).not.toBeInTheDocument()
  })

  it('names the fallback rule before the permission one', () => {
    expect(retireBlockedReason({ fallback: true, status: 'active' }, false)).toMatch(/General Employee/)
    expect(retireBlockedReason({ fallback: false, status: 'active' }, true)).toBeNull()
  })
})
