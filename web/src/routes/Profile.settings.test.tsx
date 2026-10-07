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
