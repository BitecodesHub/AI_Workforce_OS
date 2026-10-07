import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { ModelRouting } from './ModelRouting'

/*
 * Model routing opens Connect your AI from its header and from /routing?connect=1, for people who
 * can manage providers, and takes the parameter out of the address once it has acted on it so a
 * reload does not open the dialog again.
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

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const path = url.split('?')[0]
      if (path === '/api/providers') {
        return json(200, [
          {
            id: 'openrouter',
            displayName: 'OpenRouter',
            kind: 'OPENAI_COMPATIBLE',
            enabled: true,
            platformEnabled: true,
            credentialRef: 'provider:openrouter',
            credentialStatus: 'unknown',
            circuitState: 'CLOSED',
            regions: [],
            modelCount: 1,
          },
        ])
      }
      if (path === '/api/model-policy') return json(200, { configured: false, exhaustedBehaviour: 'FAIL_CLOSED', candidates: [] })
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

function open(path: string, permissions: string[]) {
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'ws-1',
    permissions,
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'owner',
  })
  window.history.pushState({}, '', path)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <ModelRouting />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

const MANAGER = ['provider:read', 'provider:manage']

describe('Connect your AI on Model routing', () => {
  it('opens from the header button', async () => {
    open('/routing', MANAGER)

    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Connect your AI' }))
    })

    expect(await screen.findByRole('dialog', { name: 'Connect a live AI model' })).toBeInTheDocument()
  })

  it('opens from ?connect=1, and takes the parameter out of the address, keeping any others', async () => {
    open('/routing?connect=1&keep=this', MANAGER)

    expect(await screen.findByRole('dialog', { name: 'Connect a live AI model' })).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toBe('?keep=this'))
    expect(window.location.pathname).toBe('/routing')
  })

  it('does not open from ?connect=1 for somebody who cannot manage providers, and offers no button', async () => {
    open('/routing?connect=1', ['provider:read'])

    await screen.findByRole('heading', { name: 'Model routing' })
    expect(screen.queryByRole('dialog', { name: 'Connect a live AI model' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Connect your AI' })).not.toBeInTheDocument()
  })

  it('is not opened by a plain visit', async () => {
    open('/routing', MANAGER)

    await screen.findByRole('button', { name: 'Connect your AI' })
    expect(screen.queryByRole('dialog', { name: 'Connect a live AI model' })).not.toBeInTheDocument()
  })
})
