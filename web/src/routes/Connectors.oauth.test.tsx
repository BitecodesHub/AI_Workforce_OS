// @find: tests for connectors OAuth return, connected parameter, error parameter, vitest, Connectors component tests, Connectors page
// @what: Automated tests that check the connectors OAuth return screen (/connectors) behaves as users expect.
// @flow: Renders Connectors from Connectors.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { Connectors } from './Connectors'

/* The provider sends the browser back with ?connected= or ?error=; say so once, then clean the address. */

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url.split('?')[0] === '/api/integrations') {
        return json(200, [
          { server: 'gmail', displayName: 'Gmail', status: 'connected', sandbox: false, reconnectRequired: false, grantedScopes: [], missingScopes: [], tools: [], authType: 'oauth', liveAvailable: true },
        ])
      }
      return json(200, [])
    }),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

function open(path: string) {
  saveSession('token', { userId: 'u', workspaceId: 'w', permissions: ['integration:read'], displayName: 'Olivia', email: 'o@example.test', role: 'owner' })
  window.history.pushState({}, '', path)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <Connectors />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

describe('Connectors after the provider redirect', () => {
  it('confirms a known connector and removes the parameter', async () => {
    open('/connectors?connected=gmail&keep=1')
    expect(await screen.findByText('Gmail is connected.')).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toBe('?keep=1'))
  })

  it('shows an error as plain text and removes it', async () => {
    open('/connectors?error=' + encodeURIComponent('<b>Access was denied.</b>'))
    expect(await screen.findByText('<b>Access was denied.</b>')).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toBe(''))
  })

  it('ignores an unknown server name', async () => {
    open('/connectors?connected=nonsense')
    await waitFor(() => expect(window.location.search).toBe(''))
    expect(screen.queryByText(/is connected\./)).not.toBeInTheDocument()
  })
})
