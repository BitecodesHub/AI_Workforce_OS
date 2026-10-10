// @find: tests for members and roles, invite, roles, permissions, vitest, Members component tests, Members page
// @what: Automated tests that check the members and roles screen (/members) behaves as users expect.
// @flow: Renders Members from Members.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { Members } from './Members'

/*
 * Members and roles as an administrator: the owner role is never offered, an owner's row has no
 * actions, invitations can be revoked and sent again, and a member can be given a password reset
 * link. The identity and organisation services are answered by a stub that records each call.
 */

// jsdom has the <dialog> element but not its modal methods; every dialog here opens with one.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const ORG = 'org-1'
const EVERYTHING = ['workspace:read', 'workspace:delete', 'member:read', 'member:invite', 'member:update', 'member:remove', 'role:read', 'role:create', 'chat:use']
const ADMIN = EVERYTHING.filter((code) => code !== 'workspace:delete')

const ROLES = [
  { id: 'r-owner', name: 'owner', description: 'Full control.', system: true, permissionVersion: 1, permissions: EVERYTHING, holders: 1 },
  { id: 'r-admin', name: 'admin', description: 'Manages people.', system: true, permissionVersion: 1, permissions: ADMIN, holders: 1 },
  { id: 'r-employee', name: 'employee', description: 'Asks questions.', system: true, permissionVersion: 1, permissions: ['workspace:read', 'chat:use'], holders: 1 },
]

const MEMBERS = [
  { userId: 'u-olivia', displayName: 'Olivia Owner', email: 'olivia@example.test', role: 'owner', status: 'active' },
  { userId: 'u-arjun', displayName: 'Arjun Admin', email: 'arjun@example.test', role: 'admin', status: 'active' },
  { userId: 'u-emma', displayName: 'Emma Employee', email: 'emma@example.test', role: 'employee', status: 'active' },
]

const future = new Date(Date.now() + 3 * 24 * 3600 * 1000).toISOString()

const INVITATIONS = [
  { invitationId: 'inv-1', email: 'nia@example.test', roleName: 'employee', status: 'pending', expiresAt: future },
  { invitationId: 'inv-2', email: 'sam@example.test', roleName: 'employee', status: 'accepted', expiresAt: future, acceptedAt: future },
]

type Call = { method: string; url: string; body: unknown }
let calls: Call[] = []

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function answer(method: string, url: string): Response {
  if (method === 'GET' && url === '/api/users') return json(200, MEMBERS)
  if (method === 'GET' && url === '/api/roles') return json(200, ROLES)
  if (method === 'GET' && url === '/api/roles/permissions') return json(200, [])
  if (method === 'GET' && url === `/api/orgs/${ORG}/invitations`) return json(200, INVITATIONS)
  if (method === 'DELETE' && url === `/api/orgs/${ORG}/invitations/inv-1`) return json(200, { ...INVITATIONS[0], status: 'revoked' })
  if (method === 'POST' && url === `/api/orgs/${ORG}/invitations`) {
    return json(201, {
      ...INVITATIONS[0],
      invitationId: 'inv-3',
      token: 'fresh',
      acceptUrl: 'http://localhost:5173/accept-invite?token=fresh',
    })
  }
  if (method === 'POST' && url === '/api/users/u-emma/password-reset-link') {
    return json(201, { url: '/sign-in?reset=reset-token', expiresAt: new Date(Date.now() + 30 * 60 * 1000).toISOString() })
  }
  return json(404, { code: 'not_found', detail: 'Not here.' })
}

beforeEach(() => {
  calls = []
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ method, url, body: typeof init.body === 'string' ? JSON.parse(init.body) : null })
      return answer(method, url)
    }),
  )
  saveSession('admin-token', {
    userId: 'u-arjun',
    workspaceId: ORG,
    permissions: ADMIN,
    displayName: 'Arjun Admin',
    email: 'arjun@example.test',
    role: 'admin',
  })
  window.history.pushState({}, '', '/admin/members')
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

async function renderMembers() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <Members />
      </RouterProvider>
    </QueryClientProvider>,
  )
  // Every list has arrived: the members, the invitations and the roles.
  await screen.findByText('nia@example.test')
  await screen.findByText('Manages people.')
  await screen.findByText('Olivia Owner')
}

function optionTexts(select: HTMLElement): string[] {
  return within(select)
    .getAllByRole('option')
    .map((option) => option.textContent ?? '')
}

describe('Members as an administrator', () => {
  it('opens under its eyebrow="Who can do what"', async () => {
    await renderMembers()
    expect(screen.getByText('Who can do what')).toBeInTheDocument()
  })

  it('shows no actions on an owner, and says why', async () => {
    await renderMembers()

    expect(screen.queryByRole('button', { name: 'Change role for Olivia Owner' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove Olivia Owner' })).not.toBeInTheDocument()
    expect(screen.getByText('Only an owner can change an owner.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Change role for Emma Employee' })).toBeInTheDocument()
  })

  it('never offers Owner, neither when inviting nor when changing a role', async () => {
    await renderMembers()

    fireEvent.click(screen.getByRole('button', { name: 'Invite someone' }))
    const inviteRoles = optionTexts(screen.getByRole('combobox', { name: 'Role' }))
    expect(inviteRoles).toContain('Admin')
    expect(inviteRoles).not.toContain('Owner')
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))

    fireEvent.click(screen.getByRole('button', { name: 'Change role for Emma Employee' }))
    const changeRoles = optionTexts(screen.getByRole('combobox', { name: 'New role' }))
    expect(changeRoles).toEqual(['Admin', 'Employee'])
  })

  it('says removal can be undone by inviting them again, and that their schedules pause', async () => {
    await renderMembers()

    fireEvent.click(screen.getByRole('button', { name: 'Remove Emma Employee' }))

    expect(screen.getByText('They can be invited again later. Their schedules will be paused.')).toBeInTheDocument()
  })

  it('revokes a pending invitation after confirming, and offers nothing on an accepted one', async () => {
    await renderMembers()

    expect(screen.queryByRole('button', { name: 'Revoke the invitation for sam@example.test' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Revoke the invitation for nia@example.test' }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Revoke invitation' }))
    })

    expect(calls).toContainEqual({ method: 'DELETE', url: `/api/orgs/${ORG}/invitations/inv-1`, body: null })
  })

  it('sends an invitation again as a new one, and keeps the new link on the page', async () => {
    await renderMembers()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Resend the invitation to nia@example.test' }))
    })

    expect(calls).toContainEqual({
      method: 'POST',
      url: `/api/orgs/${ORG}/invitations`,
      body: { email: 'nia@example.test', roleName: 'employee' },
    })
    expect(screen.getByDisplayValue('http://localhost:5173/accept-invite?token=fresh')).toHaveFocus()
  })

  it('creates a password reset link with its 30-minute expiry, but not for the viewer themselves', async () => {
    await renderMembers()

    expect(
      screen.queryByRole('button', { name: 'Create a password reset link for Arjun Admin' }),
    ).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Create a password reset link for Emma Employee' }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Create link' }))
    })

    const link = screen.getByLabelText('Password reset link')
    expect(link).toHaveValue(`${window.location.origin}/sign-in?reset=reset-token`)
    expect(screen.getByText(/expires in 30 minutes/)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Copy link' }).length).toBeGreaterThan(0)
  })
})

describe('Members as the only owner', () => {
  it('offers no role change or removal on the last owner, and says why', async () => {
    saveSession('owner-token', {
      userId: 'u-olivia',
      workspaceId: ORG,
      permissions: EVERYTHING,
      displayName: 'Olivia Owner',
      email: 'olivia@example.test',
      role: 'owner',
    })
    await renderMembers()

    expect(screen.queryByRole('button', { name: 'Change role for Olivia Owner' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove yourself' })).not.toBeInTheDocument()
    expect(screen.getByText('The last owner cannot be demoted or removed.')).toBeInTheDocument()
    // Everyone else can still be managed.
    expect(screen.getByRole('button', { name: 'Remove Arjun Admin' })).toBeInTheDocument()
  })
})
