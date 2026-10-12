// @find: tests for the service worker, sw.js push handler, notificationclick handler, open deep link, focus window, open redirect tab newline
// @what: Runs public/sw.js in isolation against a stand-in service worker scope and checks its push and notification-click handlers.
// @flow: Reads web/public/sw.js from disk and evaluates it with a fake `self`; no browser needed
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const SOURCE = readFileSync(join(__dirname, '..', 'public', 'sw.js'), 'utf8')
const ORIGIN = 'https://console.example.com'

type Listener = (event: unknown) => void
type FakeClient = { url: string; focused: boolean; postMessage: ReturnType<typeof vi.fn>; focus: ReturnType<typeof vi.fn> }

/** A service worker scope with just what sw.js touches, recording what it does. */
function scope(windows: FakeClient[] = []) {
  const listeners = new Map<string, Listener>()
  const self = {
    location: { origin: ORIGIN },
    addEventListener: (type: string, listener: Listener) => listeners.set(type, listener),
    skipWaiting: vi.fn(),
    registration: { showNotification: vi.fn(() => Promise.resolve()) },
    clients: {
      claim: vi.fn(() => Promise.resolve()),
      matchAll: vi.fn(() => Promise.resolve(windows)),
      openWindow: vi.fn(() => Promise.resolve(null)),
    },
  }
  new Function('self', SOURCE)(self)
  return { self, listeners }
}

/** Dispatches an event to the handler and waits for what it passed to waitUntil. */
async function dispatch(listeners: Map<string, Listener>, type: string, event: Record<string, unknown>) {
  let work: Promise<unknown> = Promise.resolve()
  listeners.get(type)!({ ...event, waitUntil: (promise: Promise<unknown>) => (work = promise) })
  await work
}

function pushData(value: unknown) {
  return { json: () => (typeof value === 'string' ? JSON.parse(value) : value) }
}

function client(url: string, focused = false): FakeClient {
  return { url, focused, postMessage: vi.fn(), focus: vi.fn(() => Promise.resolve()) }
}

describe('service worker', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('registers install, activate, push and notificationclick, and takes over at once', async () => {
    const { self, listeners } = scope()
    expect([...listeners.keys()].sort()).toEqual(['activate', 'install', 'notificationclick', 'push'])
    listeners.get('install')!({})
    expect(self.skipWaiting).toHaveBeenCalled()
    await dispatch(listeners, 'activate', {})
    expect(self.clients.claim).toHaveBeenCalled()
  })

  it('shows a push message with its title, body, tag and link', async () => {
    const { self, listeners } = scope()

    await dispatch(listeners, 'push', {
      data: pushData({
        kind: 'approval',
        title: 'An agent needs your approval',
        body: 'Send an email in Gmail. Open it to approve or reject.',
        tag: 'approval-123',
        url: '/approvals#approval-123',
      }),
    })

    expect(self.registration.showNotification).toHaveBeenCalledWith('An agent needs your approval', {
      body: 'Send an email in Gmail. Open it to approve or reject.',
      tag: 'approval-123',
      icon: '/icons/icon-192.png',
      badge: '/icons/badge-96.png',
      data: { url: '/approvals#approval-123' },
    })
  })

  it('still shows something for a message it cannot read, and never links outside the app', async () => {
    const { self, listeners } = scope()

    await dispatch(listeners, 'push', { data: { json: () => JSON.parse('not json') } })
    await dispatch(listeners, 'push', { data: null })
    await dispatch(listeners, 'push', { data: pushData({ title: 'Hi', url: 'https://evil.example/phish' }) })
    await dispatch(listeners, 'push', { data: pushData({ title: 'Hi', url: '//evil.example/x' }) })

    const calls = self.registration.showNotification.mock.calls as unknown as Array<[string, { data: { url: string } }]>
    expect(calls).toHaveLength(4)
    expect(calls[0]![0]).toBe('Something is waiting for you')
    for (const [, options] of calls) expect(options.data.url).toBe('/approvals')
  })

  it('on a tap, brings an open window of the app forward and tells it where to go', async () => {
    const other = client('https://elsewhere.example/page')
    const background = client(`${ORIGIN}/agents`)
    const front = client(`${ORIGIN}/chat`, true)
    const { self, listeners } = scope([other, background, front])
    const notification = { close: vi.fn(), data: { url: '/approvals#approval-9' } }

    await dispatch(listeners, 'notificationclick', { notification })

    expect(notification.close).toHaveBeenCalled()
    expect(front.postMessage).toHaveBeenCalledWith({ type: 'aiwos:navigate', url: '/approvals#approval-9' })
    expect(front.focus).toHaveBeenCalled()
    expect(background.postMessage).not.toHaveBeenCalled()
    expect(other.postMessage).not.toHaveBeenCalled()
    expect(self.clients.openWindow).not.toHaveBeenCalled()
  })

  it('on a tap with no window of the app open, opens one on the link', async () => {
    const { self, listeners } = scope([client('https://elsewhere.example/')])

    await dispatch(listeners, 'notificationclick', { notification: { close: vi.fn(), data: { url: '/runs/run-1' } } })

    expect(self.clients.openWindow).toHaveBeenCalledWith(`${ORIGIN}/runs/run-1`)
  })

  it('cannot be led off the app by characters the address parser drops or rewrites', async () => {
    const { self, listeners } = scope()
    // Each of these passes a "starts with one slash" check, yet a browser would resolve it to another site.
    const tricks = ['/\t/evil.example', '/\n/evil.example/x', '/\r/evil.example', '/\\evil.example', '/\u0000/evil.example']
    // Proof that they are tricks: resolved as a browser would, the first four land on another site.
    for (const url of tricks.slice(0, 4)) expect(new URL(url, ORIGIN).origin).not.toBe(ORIGIN)

    for (const url of tricks) {
      await dispatch(listeners, 'push', { data: pushData({ title: 'Hi', url }) })
      await dispatch(listeners, 'notificationclick', { notification: { close: vi.fn(), data: { url } } })
    }

    const shown = self.registration.showNotification.mock.calls as unknown as Array<[string, { data: { url: string } }]>
    for (const [, options] of shown) expect(options.data.url).toBe('/approvals')
    const opened = self.clients.openWindow.mock.calls as unknown as Array<[string]>
    expect(opened).toHaveLength(tricks.length)
    for (const [href] of opened) expect(href).toBe(`${ORIGIN}/approvals`)
  })

  it('keeps the path, query and fragment of a link inside the app', async () => {
    const { self, listeners } = scope()

    await dispatch(listeners, 'notificationclick', {
      notification: { close: vi.fn(), data: { url: '/chat?c=conv-1#question-9' } },
    })

    expect(self.clients.openWindow).toHaveBeenCalledWith(`${ORIGIN}/chat?c=conv-1#question-9`)
  })

  it('opens the Approvals page for a tapped notification whose link is not a path in the app', async () => {
    const { self, listeners } = scope()

    await dispatch(listeners, 'notificationclick', { notification: { close: vi.fn(), data: { url: 'javascript:alert(1)' } } })

    expect(self.clients.openWindow).toHaveBeenCalledWith(`${ORIGIN}/approvals`)
  })
})
