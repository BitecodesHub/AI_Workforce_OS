// @find: tests for workspace settings, name, time zone, notifications, retention, vitest, Settings component tests, Settings page
// @what: Automated tests that check the workspace settings screen (/settings) behaves as users expect.
// @flow: Renders Settings from Settings.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { Settings } from './Settings'

/*
 * Workspace settings: the name and time zone are checked before anything is sent and saved with
 * the shapes the service expects, the notification form keeps a stored secret unless told
 * otherwise and tests only what is saved, and browser notifications ask for permission only when
 * they are turned on.
 */

type Call = { url: string; method: string; body: unknown }

let calls: Call[]
let workspace: { id: string; name: string; slug: string; timezone: string; status: string }
let notifications: { webhookUrl: string; hasSecret: boolean; events: string[]; availableEvents: Array<{ id: string; label: string }> }
let answers: Record<string, () => Response>

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function memoryStorage(): Storage {
  const stored = new Map<string, string>()
  return {
    get length() {
      return stored.size
    },
    clear: () => stored.clear(),
    getItem: (key) => stored.get(key) ?? null,
    key: (index) => [...stored.keys()][index] ?? null,
    removeItem: (key) => void stored.delete(key),
    setItem: (key, value) => void stored.set(key, String(value)),
  }
}

const originalStorage = Object.getOwnPropertyDescriptor(window, 'localStorage')

class FakeNotification {
  static permission: NotificationPermission = 'default'
  static requestPermission = vi.fn(async () => {
    FakeNotification.permission = 'granted'
    return 'granted' as NotificationPermission
  })
}

beforeEach(() => {
  calls = []
  answers = {}
  workspace = { id: 'ws-1', name: 'Acme Operations', slug: 'acme-operations', timezone: 'Australia/Melbourne', status: 'active' }
  notifications = {
    webhookUrl: '',
    hasSecret: false,
    events: ['approvalRaised', 'approvalExpired', 'schedulePaused'],
    availableEvents: [
      { id: 'approvalRaised', label: 'An agent is waiting for an approval' },
      { id: 'approvalExpired', label: 'An approval expired before anybody decided it' },
      { id: 'schedulePaused', label: 'A schedule paused itself after repeated failures' },
    ],
  }
  Object.defineProperty(window, 'localStorage', { configurable: true, value: memoryStorage() })
  FakeNotification.permission = 'default'
  FakeNotification.requestPermission.mockClear()
  vi.stubGlobal('Notification', FakeNotification)
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'ws-1',
    permissions: ['workspace:update', 'analytics:read'],
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'owner',
  })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ url, method, body: typeof init.body === 'string' ? JSON.parse(init.body) : null })
      const answer = answers[`${method} ${url}`]
      if (answer) return answer()
      if (method === 'GET' && url === '/api/workspaces/ws-1') return json(200, workspace)
      if (method === 'GET' && url === '/api/orchestrator/notification-settings') return json(200, notifications)
      return json(404, { code: 'not_found', detail: 'Not here.' })
    }),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
  if (originalStorage) Object.defineProperty(window, 'localStorage', originalStorage)
})

async function open() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <Settings />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
  await screen.findByLabelText('Workspace name')
  await screen.findByLabelText('Webhook address')
}

const sent = (method: string, url: string) => calls.find((call) => call.method === method && call.url === url)

describe('workspace name and time zone', () => {
  it('opens under an eyebrow, with the workspace as it is and the sentence about schedules', async () => {
    await open()

    expect(screen.getByRole('heading', { level: 1, name: 'Workspace settings' })).toBeInTheDocument()
    expect(screen.getByLabelText('Workspace name')).toHaveValue('Acme Operations')
    expect(screen.getByLabelText('Time zone')).toHaveValue('Australia/Melbourne')
    expect(
      screen.getByText('Existing schedules keep their own time zone; new runs use the new zone within 10 minutes.'),
    ).toBeInTheDocument()
    // Nothing has changed, so there is nothing to save.
    expect(screen.getByRole('button', { name: 'Save workspace settings' })).toBeDisabled()
  })

  it('refuses a blank name and a zone that is not in the list, and sends nothing', async () => {
    await open()

    fireEvent.change(screen.getByLabelText('Workspace name'), { target: { value: '   ' } })
    fireEvent.change(screen.getByLabelText('Time zone'), { target: { value: 'Nowhere/Land' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save workspace settings' }))

    expect(await screen.findByText('Enter a name for the workspace.')).toBeInTheDocument()
    expect(screen.getByText('Choose a time zone from the list, such as Australia/Melbourne.')).toBeInTheDocument()
    expect(screen.getByLabelText('Workspace name')).toHaveAttribute('aria-invalid', 'true')
    expect(calls.some((call) => call.method === 'PATCH')).toBe(false)
  })

  it('saves the trimmed name and the chosen zone to this workspace, and says so', async () => {
    answers['PATCH /api/workspaces/ws-1/settings'] = () =>
      json(200, { ...workspace, name: 'Acme Care', timezone: 'Europe/London' })
    await open()

    fireEvent.change(screen.getByLabelText('Workspace name'), { target: { value: '  Acme Care ' } })
    fireEvent.change(screen.getByLabelText('Time zone'), { target: { value: 'Europe/London' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save workspace settings' }))

    await waitFor(() => expect(sent('PATCH', '/api/workspaces/ws-1/settings')).toBeDefined())
    expect(sent('PATCH', '/api/workspaces/ws-1/settings')?.body).toEqual({ name: 'Acme Care', timezone: 'Europe/London' })
    expect(await screen.findByText('Workspace settings saved')).toBeInTheDocument()
    expect(screen.getByLabelText('Workspace name')).toHaveValue('Acme Care')
  })

  it('shows the service’s own complaint beside the field it names', async () => {
    answers['PATCH /api/workspaces/ws-1/settings'] = () =>
      json(422, {
        code: 'validation_failed',
        detail: 'Some of the values supplied are not valid.',
        errors: { field: 'timezone', problem: 'That is not a recognised time zone.' },
      })
    await open()

    fireEvent.change(screen.getByLabelText('Time zone'), { target: { value: 'Europe/London' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save workspace settings' }))

    expect(await screen.findByText('That is not a recognised time zone.')).toBeInTheDocument()
  })
})

describe('notifications', () => {
  it('keeps a stored secret when none is typed, and sends one only when it is replaced', async () => {
    notifications.webhookUrl = 'https://example.com/hook'
    notifications.hasSecret = true
    answers['PUT /api/orchestrator/notification-settings'] = () => json(200, notifications)
    await open()

    fireEvent.click(screen.getByRole('checkbox', { name: 'An approval expired before anybody decided it' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save notification settings' }))
    await waitFor(() => expect(sent('PUT', '/api/orchestrator/notification-settings')).toBeDefined())
    // No `secret` key at all: the service keeps the one it has.
    expect(sent('PUT', '/api/orchestrator/notification-settings')?.body).toEqual({
      webhookUrl: 'https://example.com/hook',
      events: ['approvalRaised', 'schedulePaused'],
    })

    calls = []
    fireEvent.change(screen.getByLabelText(/Replace the signing secret/), { target: { value: 'a-new-secret-value' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save notification settings' }))
    await waitFor(() => expect(sent('PUT', '/api/orchestrator/notification-settings')).toBeDefined())
    expect(sent('PUT', '/api/orchestrator/notification-settings')?.body).toMatchObject({ secret: 'a-new-secret-value' })
  })

  it('removes the secret by sending an empty one, only when asked', async () => {
    notifications.webhookUrl = 'https://example.com/hook'
    notifications.hasSecret = true
    answers['PUT /api/orchestrator/notification-settings'] = () => json(200, { ...notifications, hasSecret: false })
    await open()

    fireEvent.click(screen.getByRole('checkbox', { name: 'Remove the saved secret' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save notification settings' }))

    await waitFor(() => expect(sent('PUT', '/api/orchestrator/notification-settings')).toBeDefined())
    expect(sent('PUT', '/api/orchestrator/notification-settings')?.body).toMatchObject({ secret: '' })
  })

  it('refuses a malformed address and a short secret before sending, and an address with nothing to tell it', async () => {
    await open()

    fireEvent.change(screen.getByLabelText('Webhook address'), { target: { value: 'hooks.example.com' } })
    fireEvent.change(screen.getByLabelText(/Signing secret/), { target: { value: 'short' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save notification settings' }))

    expect(await screen.findByText('Enter a full web address, starting with https://.')).toBeInTheDocument()
    expect(screen.getByText('Use between 8 and 256 characters.')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Webhook address'), { target: { value: 'https://example.com/hook' } })
    for (const event of notifications.availableEvents) {
      fireEvent.click(screen.getByRole('checkbox', { name: event.label }))
    }
    fireEvent.click(screen.getByRole('button', { name: 'Save notification settings' }))
    expect(
      await screen.findByText('Choose at least one event, or clear the address to turn notifications off.'),
    ).toBeInTheDocument()
    expect(calls.some((call) => call.method === 'PUT')).toBe(false)
  })

  it('sends the test message only once there is a saved address and nothing unsaved beside it', async () => {
    notifications.webhookUrl = 'https://example.com/hook'
    answers['POST /api/orchestrator/notification-settings/test'] = () =>
      json(200, { delivered: true, statusCode: 200, message: 'The test message was delivered.' })
    await open()

    const test = screen.getByRole('button', { name: 'Send test message' })
    expect(test).toBeEnabled()

    fireEvent.change(screen.getByLabelText('Webhook address'), { target: { value: 'https://example.com/other' } })
    expect(test).toBeDisabled()
    expect(screen.getByText(/Save your changes first/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Webhook address'), { target: { value: 'https://example.com/hook' } })
    expect(test).toBeEnabled()
    fireEvent.click(test)

    expect(await screen.findByText('The test message was delivered.')).toBeInTheDocument()
    expect(sent('POST', '/api/orchestrator/notification-settings/test')).toBeDefined()
  })

  it('has no test to send while no address is saved', async () => {
    await open()
    expect(screen.getByRole('button', { name: 'Send test message' })).toBeDisabled()
    // Disabled, and says why.
    expect(screen.getByText(/Save a webhook address first/)).toBeInTheDocument()
  })
})

describe('browser notifications', () => {
  const toggle = () => screen.getByRole('switch', { name: /Show a notification in this browser/ })

  it('does not ask the browser for permission until the person turns it on', async () => {
    await open()
    expect(FakeNotification.requestPermission).not.toHaveBeenCalled()
    expect(toggle()).not.toBeChecked()

    await act(async () => {
      fireEvent.click(toggle())
    })

    expect(FakeNotification.requestPermission).toHaveBeenCalledTimes(1)
    expect(toggle()).toBeChecked()
    expect(window.localStorage.getItem('aiwos.notify.user-1')).toBe('1')
  })

  it('stays off, and says why, when the browser refuses', async () => {
    FakeNotification.requestPermission.mockImplementationOnce(async () => {
      FakeNotification.permission = 'denied'
      return 'denied' as NotificationPermission
    })
    await open()

    await act(async () => {
      fireEvent.click(toggle())
    })

    expect(toggle()).not.toBeChecked()
    expect(await screen.findByText(/did not allow notifications for this site/)).toBeInTheDocument()
    expect(window.localStorage.getItem('aiwos.notify.user-1')).toBeNull()
  })

  it('turns off without asking anything', async () => {
    window.localStorage.setItem('aiwos.notify.user-1', '1')
    FakeNotification.permission = 'granted'
    await open()
    expect(toggle()).toBeChecked()

    await act(async () => {
      fireEvent.click(toggle())
    })

    expect(toggle()).not.toBeChecked()
    expect(FakeNotification.requestPermission).not.toHaveBeenCalled()
  })
})

describe('budget', () => {
  it('links to the budget in Analytics for somebody who can read it', async () => {
    await open()
    const card = screen.getByRole('heading', { name: 'Budget' }).closest('section')!
    expect(within(card).getByRole('link', { name: 'Open the budget in Analytics' })).toHaveAttribute(
      'href',
      '/analytics#budget',
    )
  })
})

describe('a second pair of eyes', () => {
  it('shows the rule as it is and saves a new one with PUT /api/approvals/settings', async () => {
    answers['GET /api/approvals/settings'] = () => json(200, { requesterCannotApprove: 'off' })
    answers['PUT /api/approvals/settings'] = () => json(200, { requesterCannotApprove: 'all' })
    await open()

    const field = await screen.findByLabelText('Someone else must approve')
    expect(field).toHaveValue('off')
    const card = field.closest('section') as HTMLElement
    expect(within(card).getByRole('button', { name: 'Save' })).toBeDisabled()
    fireEvent.change(field, { target: { value: 'all' } })
    await act(async () => {
      fireEvent.click(within(card).getByRole('button', { name: 'Save' }))
    })

    expect(sent('PUT', '/api/approvals/settings')?.body).toEqual({ requesterCannotApprove: 'all' })
    expect(await screen.findByText('Saved. Someone other than the person who asked now decides these requests.')).toBeInTheDocument()
  })
})
