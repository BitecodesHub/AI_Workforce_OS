// @find: tests for navbar, primary navigation, nav permissions, Connectors link, role based menu, sign out asks a screen with unsaved changes, Navbar
// @what: Tests that the navbar offers destinations only to roles with the needed permissions.
// @flow: Renders Navbar with a saved session and mocked fetch.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider, useLeaveGuard } from '../../lib/router'
import type { LeaveGuard } from '../../lib/router'
import { clearSession, isSignedIn, saveSession } from '../../lib/session'
import { Navbar } from './Navbar'

/*
 * The primary navigation offers a destination only to a role that can use it. Connectors sits in
 * the capsule beside Agents for anybody holding integration:read, and is not offered a second
 * time behind the gear.
 */

function signIn(permissions: string[]) {
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions,
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  })
}

/** A screen holding unsaved work, as the workflow editor does. */
function GuardedScreen({ guard }: { guard: LeaveGuard }) {
  useLeaveGuard(guard)
  return null
}

function renderNavbar(currentPath = '/', guard: LeaveGuard | null = null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <Navbar currentPath={currentPath} />
        {guard && <GuardedScreen guard={guard} />}
      </RouterProvider>
    </QueryClientProvider>,
  )
}

const primary = () => within(document.getElementById('primary-navigation')!)

beforeEach(() => {
  // The approvals badge and the questions count are fetched for roles that may read them.
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))),
  )
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

describe('Navbar', () => {
  it('offers Connectors beside Agents to a role that can read connectors', () => {
    signIn(['agent:read', 'integration:read', 'knowledge:read'])
    renderNavbar()
    const links = primary().getAllByRole('link').map((link) => link.textContent)
    expect(links).toEqual(['Command Map', 'Agents', 'Connectors', 'Knowledge'])
    expect(primary().getByRole('link', { name: 'Connectors' })).toHaveAttribute('href', '/connectors')
  })

  it('leaves Connectors out for a role without integration:read', () => {
    signIn(['agent:read', 'knowledge:read'])
    renderNavbar()
    expect(primary().queryByRole('link', { name: 'Connectors' })).not.toBeInTheDocument()
    expect(primary().getByRole('link', { name: 'Agents' })).toBeInTheDocument()
  })

  it('marks Connectors as the current page there', () => {
    signIn(['integration:read'])
    renderNavbar('/connectors')
    expect(primary().getByRole('link', { name: 'Connectors' })).toHaveAttribute('aria-current', 'page')
  })

  it('does not offer Connectors a second time behind the gear, and keeps Runs reachable there', () => {
    signIn(['integration:read', 'run:read', 'provider:read'])
    renderNavbar()
    fireEvent.click(screen.getByRole('button', { name: 'Settings and more' }))
    const panel = within(screen.getByRole('group', { name: 'Settings and more' }))
    expect(panel.queryByRole('link', { name: /Connectors|Integrations/ })).not.toBeInTheDocument()
    expect(panel.getByRole('link', { name: /^Runs/ })).toHaveAttribute('href', '/runs')
  })

  it('asks a screen with unsaved work before signing out, and signs out only once it proceeds', async () => {
    vi.stubGlobal('scrollTo', vi.fn())
    signIn(['task:read'])
    window.history.replaceState(null, '', '/workflows/wf-1')
    let proceed: (() => void) | null = null
    const guard = vi.fn<LeaveGuard>((next) => {
      proceed = next
    })
    renderNavbar('/workflows/wf-1', guard)
    fireEvent.click(screen.getByRole('button', { name: 'Your account' }))
    fireEvent.click(screen.getByRole('button', { name: /Sign out/ }))

    expect(guard).toHaveBeenCalledTimes(1)
    expect(isSignedIn()).toBe(true)
    expect(window.location.pathname).toBe('/workflows/wf-1')
    act(() => proceed!())
    await waitFor(() => expect(window.location.pathname).toBe('/sign-in'))
    expect(isSignedIn()).toBe(false)
    window.history.replaceState(null, '', '/')
  })
})
