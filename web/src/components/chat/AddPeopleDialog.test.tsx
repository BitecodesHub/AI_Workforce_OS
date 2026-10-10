// @find: tests for AddPeopleDialog, add people to chat, share conversation, private conversation, conversation members, invite to chat, who can read, Add people dialog, canUseChat, chat permission, role
// @what: Automated tests for AddPeopleDialog.
// @flow: Run with the web test runner; covers AddPeopleDialog.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearSession, saveSession } from '../../lib/session'
import { AddPeopleDialog, canUseChat } from './AddPeopleDialog'

/* Adding people to a private conversation: only people whose role can open Chat can be added. */

if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
  }
}

const MEMBERS = [
  { userId: 'me', displayName: 'Erin Employee', email: 'erin@example.test', role: 'employee', status: 'active' },
  { userId: 'u-m', displayName: 'Maya Manager', email: 'maya@example.test', role: 'manager', status: 'active' },
  { userId: 'u-v', displayName: 'Vic Viewer', email: 'vic@example.test', role: 'viewer', status: 'active' },
]

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const body = url.startsWith('/api/users') ? MEMBERS : url.includes('/participants') ? { userIds: [] } : []
      return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }),
  )
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

describe('canUseChat', () => {
  it('judges a role by its own permissions when the roles could be read', () => {
    const roles = [
      { name: 'auditor', permissions: ['run:read'] },
      { name: 'helper', permissions: ['chat:use'] },
    ]
    expect(canUseChat('auditor', roles)).toBe(false)
    expect(canUseChat('helper', roles)).toBe(true)
  })

  it('knows the built-in viewer cannot open Chat when the roles cannot be read', () => {
    expect(canUseChat('viewer', undefined)).toBe(false)
    expect(canUseChat('employee', undefined)).toBe(true)
  })
})

describe('AddPeopleDialog', () => {
  it('lists a viewer with the reason, and offers no box to tick', async () => {
    saveSession('token', {
      userId: 'me',
      workspaceId: 'ws-1',
      permissions: ['chat:use', 'member:read'],
      displayName: 'Erin Employee',
      email: 'erin@example.test',
      role: 'employee',
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <AddPeopleDialog open conversationId="c-1" onClose={() => {}} />
      </QueryClientProvider>,
    )
    expect(await screen.findByRole('checkbox', { name: /Maya Manager/ })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /Vic Viewer/ })).not.toBeInTheDocument()
    expect(screen.getByText('Cannot be added: the viewer role cannot open Chat.')).toBeInTheDocument()
  })
})
