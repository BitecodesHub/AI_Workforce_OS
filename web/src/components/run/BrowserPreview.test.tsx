// @find: tests for browser preview status line, a person has control, deciding what to do next, waiting for a person to hand back the web browser
// @what: Component tests for BrowserPreview's status line: what the agent is doing, and that it says the agent waits while a person holds the web browser (from the steps, or from the caller).
// @flow: Renders BrowserPreview inside QueryClientProvider with a mocked fetch for the run's steps and live view
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearSession, saveSession } from '../../lib/session'
import { BrowserPreview } from './BrowserShot'

const AT = '2026-10-13T08:00:00Z'
const OPENED = {
  id: 's1',
  position: 1,
  kind: 'tool_call',
  detail: { tool: 'browser.open_page', status: 'SUCCEEDED', url: 'https://example.com/', pageTitle: 'Example Domain' },
  promptTokens: 0,
  completionTokens: 0,
  durationMs: 900,
  occurredAt: AT,
}
const TAKEN = {
  id: 's2',
  position: 2,
  kind: 'note',
  detail: { type: 'taken_over', summary: 'A person took control of the web browser.' },
  promptTokens: 0,
  completionTokens: 0,
  durationMs: 0,
  occurredAt: AT,
}

let steps: unknown[]

beforeEach(() => {
  steps = [OPENED]
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'ws-1',
    permissions: ['run:read'],
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'manager',
  })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const json = (body: unknown) =>
        new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
      if (url === '/api/runs/r-1/steps') return json(steps)
      if (url === '/api/runs/r-1/browser/live') {
        return json({ busy: false, action: '', since: null, url: 'https://example.com/', title: 'Example Domain', frame: 'AAAA' })
      }
      return new Response(JSON.stringify({ code: 'not_found' }), { status: 404 })
    }),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

/** Renders the preview of a working run, with whatever the caller knows about a person's control. */
function renderPreview(personInControl?: boolean) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <BrowserPreview runId="r-1" active personInControl={personInControl} />
    </QueryClientProvider>,
  )
}

describe('BrowserPreview status line', () => {
  it('says the agent is deciding between steps while it works', async () => {
    renderPreview()
    expect(await screen.findByText('Deciding what to do next…')).toBeInTheDocument()
  })

  it('says the agent waits, rather than deciding, while its steps show a person holds the browser', async () => {
    steps = [OPENED, TAKEN]
    renderPreview()
    expect(await screen.findByText('Waiting for a person to hand back the web browser.')).toBeInTheDocument()
    expect(screen.queryByText('Deciding what to do next…')).not.toBeInTheDocument()
  })

  it('follows the caller when it knows the takeover state better than the steps do', async () => {
    // Control came back a moment ago; the agent has not written its next step yet.
    steps = [OPENED, TAKEN]
    renderPreview(false)
    expect(await screen.findByText('Deciding what to do next…')).toBeInTheDocument()
  })
})
