// @find: tests for the profile settings link, workspace settings link, permission, vitest, Profile component tests, Profile page
// @what: Automated tests that check the the profile settings link screen (/profile) behaves as users expect.
// @flow: Renders Profile from Profile.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { Profile } from './Profile'

/* Your profile leads to Workspace settings for the people who may change them, and to nobody else. */

beforeEach(() => {
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function open(permissions: string[]) {
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'ws-1',
    permissions,
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'owner',
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <Profile />
      </RouterProvider>
    </QueryClientProvider>,
  )
}

describe('the link to Workspace settings', () => {
  it('is on the profile of somebody who may update the workspace', () => {
    open(['workspace:update'])
    expect(screen.getByRole('link', { name: 'Workspace settings' })).toHaveAttribute('href', '/settings')
  })

  it('is not on the profile of anybody else', () => {
    open(['workspace:read', 'chat:use'])
    expect(screen.queryByRole('link', { name: 'Workspace settings' })).not.toBeInTheDocument()
  })
})

describe('the permission count', () => {
  it('counts what the catalogue lists, as Members and roles does, leaving planned codes out', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        const body =
          url === '/api/roles/permissions'
            ? [
                { code: 'workspace:read', resource: 'workspace', action: 'read', description: 'View workspace details.', administrative: false },
                { code: 'chat:use', resource: 'chat', action: 'use', description: 'Ask questions.', administrative: false },
              ]
            : []
        return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }),
    )
    open(['workspace:read', 'chat:use', 'run:replay'])
    await screen.findByText('Ask questions.')
    const term = screen.getByText('Permissions', { selector: 'dt' })
    expect(term.nextElementSibling).toHaveTextContent('2')
  })
})

describe('browser notifications', () => {
  it('can be turned on from the profile by somebody who cannot open Workspace settings', () => {
    open(['workspace:read', 'chat:use'])
    expect(screen.getByRole('switch', { name: /Show a notification in this browser/ })).toBeInTheDocument()
  })
})
