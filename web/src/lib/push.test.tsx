// @find: tests for push notifications client, enable push flow, disable push, service worker registration, PushManager subscribe, forget push on sign out, expired session keeps push, push sync once a day per workspace, push devices list remove, push settings section, push bridge navigation
// @what: Tests for lib/push.ts, lib/pushBridge.tsx and the push settings section, with the service worker, PushManager and Notification replaced by stand-ins.
// @flow: Runs under vitest + jsdom; fetch is stubbed per test
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  PUSH_OWNER_KEY,
  PUSH_SYNC_INTERVAL_MS,
  base64UrlToBytes,
  bytesToBase64Url,
  disablePush,
  enablePush,
  forgetPushOfOthers,
  forgetPushOnSignOut,
  inAppPath,
  linkWebManifest,
  navigationFrom,
  pushOwner,
  pushSupport,
  syncPush,
} from './push'
import type { PushEnvironment } from './push'
import { PushBridge } from './pushBridge'
import { RouterProvider } from './router'
import { clearSession, saveSession } from './session'
import { PushNotificationsSection } from '../components/settings/PushNotificationsSection'

const SERVER_KEY = 'BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8'
const OTHER_KEY = 'BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4'
const ENDPOINT = 'https://fcm.googleapis.com/fcm/send/device-1'

type Request = { url: string; method: string; body: unknown }

/** A browser with a service worker, PushManager and Notification that record what is asked of them. */
function fakeBrowser(options: { permission?: NotificationPermission; answer?: NotificationPermission; existingKey?: string | null; registered?: boolean } = {}) {
  let current: ReturnType<typeof subscriptionWith> | null =
    options.existingKey === undefined || options.existingKey === null ? null : subscriptionWith(options.existingKey)
  let registered = options.registered ?? current !== null
  const pushManager = {
    getSubscription: vi.fn(async () => current),
    subscribe: vi.fn(async (subscribeOptions: PushSubscriptionOptionsInit) => {
      current = subscriptionWith(bytesToBase64Url(subscribeOptions.applicationServerKey as Uint8Array))
      return current
    }),
  }
  const registration = { pushManager }
  const serviceWorker = {
    register: vi.fn(async () => {
      registered = true
      return registration
    }),
    ready: Promise.resolve(registration),
    getRegistration: vi.fn(async () => (registered ? registration : undefined)),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }
  const NotificationApi = {
    permission: options.permission ?? 'default',
    requestPermission: vi.fn(async () => {
      NotificationApi.permission = options.answer ?? 'granted'
      return NotificationApi.permission
    }),
  }
  const env = {
    navigator: { serviceWorker, userAgent: 'Mozilla/5.0 (Linux; Android 14) Chrome/129.0', maxTouchPoints: 5 },
    window: { PushManager: function PushManager() {}, Notification: NotificationApi, matchMedia: () => ({ matches: false }) },
  } as unknown as PushEnvironment
  return { env, pushManager, serviceWorker, NotificationApi, subscription: () => current }

  function subscriptionWith(key: string) {
    const subscription = {
      endpoint: ENDPOINT,
      options: { applicationServerKey: base64UrlToBytes(key).buffer },
      toJSON: () => ({ endpoint: ENDPOINT, expirationTime: null, keys: { p256dh: OTHER_KEY, auth: 'BTBZMqHH6r4Tts7J_aSIgg' } }),
      unsubscribe: vi.fn(async () => {
        if (current === subscription) current = null
        return true
      }),
    }
    return subscription
  }
}

let requests: Request[]
let reply: (url: string, method: string) => { status: number; body: unknown }

/** An in-memory localStorage; recent Node versions shadow jsdom's with one that does not work. */
function memoryStorage(): Storage {
  const items = new Map<string, string>()
  return {
    get length() {
      return items.size
    },
    clear: () => items.clear(),
    getItem: (key) => items.get(key) ?? null,
    key: (index) => [...items.keys()][index] ?? null,
    removeItem: (key) => void items.delete(key),
    setItem: (key, value) => void items.set(key, String(value)),
  }
}

beforeEach(() => {
  requests = []
  vi.stubGlobal('localStorage', memoryStorage())
  // The router scrolls after navigating; jsdom does not implement it.
  vi.stubGlobal('scrollTo', vi.fn())
  reply = (url) =>
    url.includes('vapid-public-key')
      ? { status: 200, body: { enabled: true, publicKey: SERVER_KEY } }
      : { status: url.endsWith('/subscriptions') ? 201 : 200, body: {} }
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      requests.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      const answer = reply(url, method)
      return Promise.resolve(
        new Response(answer.status === 204 ? null : JSON.stringify(answer.body), {
          status: answer.status,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
})

describe('pushSupport', () => {
  it('says yes where a service worker, PushManager and notifications all exist', () => {
    expect(pushSupport(fakeBrowser().env)).toBe('supported')
  })

  it('asks an iPhone in a Safari tab to add the app to the Home Screen first', () => {
    const iphoneTab = {
      navigator: { userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) Safari/604.1', maxTouchPoints: 5, standalone: false },
      window: { matchMedia: () => ({ matches: false }) },
    } as unknown as PushEnvironment
    expect(pushSupport(iphoneTab)).toBe('needs-home-screen')
    const oldDesktop = { navigator: { userAgent: 'Mozilla/5.0 (Windows NT 10.0)', maxTouchPoints: 0 }, window: {} } as unknown as PushEnvironment
    expect(pushSupport(oldDesktop)).toBe('unsupported')
    expect(pushSupport(null)).toBe('unsupported')
  })
})

describe('enablePush', () => {
  it('asks permission, registers the worker, subscribes with the server key and saves the subscription', async () => {
    const browser = fakeBrowser()

    const outcome = await enablePush('user-1', browser.env)

    expect(outcome).toBe('on')
    expect(browser.NotificationApi.requestPermission).toHaveBeenCalledTimes(1)
    expect(browser.serviceWorker.register).toHaveBeenCalledWith('/sw.js', { scope: '/' })
    const subscribeOptions = browser.pushManager.subscribe.mock.calls[0]![0]
    expect(subscribeOptions.userVisibleOnly).toBe(true)
    expect(bytesToBase64Url(subscribeOptions.applicationServerKey as Uint8Array)).toBe(SERVER_KEY)
    expect(requests.map((request) => `${request.method} ${request.url}`)).toEqual([
      'GET /api/push/vapid-public-key',
      'POST /api/push/subscriptions',
    ])
    expect(requests[1]!.body).toEqual({
      endpoint: ENDPOINT,
      expirationTime: null,
      keys: { p256dh: OTHER_KEY, auth: 'BTBZMqHH6r4Tts7J_aSIgg' },
    })
    expect(pushOwner()).toBe('user-1')
  })

  it('stops, registering nothing, when the browser refuses permission', async () => {
    const browser = fakeBrowser({ answer: 'denied' })

    expect(await enablePush('user-1', browser.env)).toBe('denied')
    expect(browser.serviceWorker.register).not.toHaveBeenCalled()
    expect(requests).toEqual([])
    expect(pushOwner()).toBeNull()
  })

  it('says push is unavailable when the server offers no key, and registers nothing', async () => {
    const browser = fakeBrowser({ permission: 'granted' })
    reply = () => ({ status: 200, body: { enabled: false, publicKey: null } })

    expect(await enablePush('user-1', browser.env)).toBe('unavailable')
    expect(browser.serviceWorker.register).not.toHaveBeenCalled()
  })

  it('replaces a subscription made with another key, which the server could never reach', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: OTHER_KEY })
    const old = browser.subscription()!

    expect(await enablePush('user-1', browser.env)).toBe('on')
    expect(old.unsubscribe).toHaveBeenCalled()
    expect(browser.pushManager.subscribe).toHaveBeenCalled()
  })

  it('keeps a subscription already made with the server key', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })

    expect(await enablePush('user-1', browser.env)).toBe('on')
    expect(browser.pushManager.subscribe).not.toHaveBeenCalled()
  })

  it('reports a failure to save rather than claiming push is on', async () => {
    const browser = fakeBrowser({ permission: 'granted' })
    reply = (url) =>
      url.includes('vapid') ? { status: 200, body: { enabled: true, publicKey: SERVER_KEY } } : { status: 422, body: { code: 'validation_failed' } }

    expect(await enablePush('user-1', browser.env)).toBe('failed')
    expect(pushOwner()).toBeNull()
  })
})

describe('disablePush, forgetPushOfOthers and syncPush', () => {
  it('turning off tells the server, drops the browser subscription and forgets the owner', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')
    const subscription = browser.subscription()!

    await disablePush(browser.env)

    expect(requests).toEqual([{ url: '/api/push/subscriptions', method: 'DELETE', body: { endpoint: ENDPOINT } }])
    expect(subscription.unsubscribe).toHaveBeenCalled()
    expect(pushOwner()).toBeNull()
  })

  it('drops the browser subscription even when the server cannot be told', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    const subscription = browser.subscription()!
    reply = () => ({ status: 503, body: { code: 'upstream_unavailable' } })

    await disablePush(browser.env)

    expect(subscription.unsubscribe).toHaveBeenCalled()
  })

  it('drops push that belongs to someone else now signed in, and keeps the owner’s own', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')

    expect(await forgetPushOfOthers('user-1', browser.env)).toBe(false)
    expect(browser.subscription()).not.toBeNull()

    expect(await forgetPushOfOthers('user-2', browser.env)).toBe(true)
    expect(browser.subscription()).toBeNull()
    expect(pushOwner()).toBeNull()
  })

  it('keeps push when nobody is signed in, as after a session expired, so it is still on after signing in again', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')

    expect(await forgetPushOfOthers(null, browser.env)).toBe(false)

    expect(browser.subscription()).not.toBeNull()
    expect(pushOwner()).toBe('user-1')
    await syncPush('user-1', browser.env, { workspaceId: 'workspace-1' })
    expect(requests.map((request) => `${request.method} ${request.url}`)).toEqual(['POST /api/push/subscriptions'])
  })

  it('signing out on purpose drops this browser’s push', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')

    await forgetPushOnSignOut(browser.env)

    expect(browser.subscription()).toBeNull()
    expect(pushOwner()).toBeNull()
  })

  it('tells the server the owner’s subscription in each workspace, at most once a day unless asked, and nothing for anyone else', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')
    const posts = () => requests.filter((request) => request.method === 'POST').length
    const start = Date.parse('2026-10-13T09:00:00Z')

    await syncPush('user-2', browser.env, { workspaceId: 'workspace-a', now: start })
    expect(requests).toEqual([])

    await syncPush('user-1', browser.env, { workspaceId: 'workspace-a', now: start })
    expect(requests.map((request) => `${request.method} ${request.url}`)).toEqual(['POST /api/push/subscriptions'])

    // Every visit after that, the same day, tells the server nothing (no audit entry, no write).
    await syncPush('user-1', browser.env, { workspaceId: 'workspace-a', now: start + 60_000 })
    await syncPush('user-1', browser.env, { workspaceId: 'workspace-a', now: start + PUSH_SYNC_INTERVAL_MS - 1 })
    expect(posts()).toBe(1)

    // Another workspace opened here is told; so is the first again a day later, or when asked.
    await syncPush('user-1', browser.env, { workspaceId: 'workspace-b', now: start + 120_000 })
    expect(posts()).toBe(2)
    await syncPush('user-1', browser.env, { workspaceId: 'workspace-a', now: start + PUSH_SYNC_INTERVAL_MS })
    expect(posts()).toBe(3)
    await syncPush('user-1', browser.env, { workspaceId: 'workspace-a', now: start + PUSH_SYNC_INTERVAL_MS + 1, force: true })
    expect(posts()).toBe(4)
  })

  it('a subscription the browser renewed is told at once, whatever was told today', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')
    const start = Date.parse('2026-10-13T09:00:00Z')
    await syncPush('user-1', browser.env, { workspaceId: 'workspace-a', now: start })

    // The browser dropped its subscription: the next visit subscribes again and tells the server.
    await browser.subscription()!.unsubscribe()
    await syncPush('user-1', browser.env, { workspaceId: 'workspace-a', now: start + 1_000 })

    expect(browser.pushManager.subscribe).toHaveBeenCalled()
    expect(requests.filter((request) => request.method === 'POST' && request.url === '/api/push/subscriptions')).toHaveLength(2)
  })
})

describe('small parts', () => {
  it('accepts only an in-app path from the service worker', () => {
    expect(navigationFrom({ type: 'aiwos:navigate', url: '/approvals#approval-1' })).toBe('/approvals#approval-1')
    expect(navigationFrom({ type: 'aiwos:navigate', url: '/chat?c=1#question-2' })).toBe('/chat?c=1#question-2')
    expect(navigationFrom({ type: 'aiwos:navigate', url: 'https://evil.example' })).toBeNull()
    expect(navigationFrom({ type: 'aiwos:navigate', url: '//evil.example' })).toBeNull()
    expect(navigationFrom({ type: 'other', url: '/x' })).toBeNull()
    expect(navigationFrom('nope')).toBeNull()
  })

  it('cannot be led off the app by a tab, line break or backslash the address parser drops or rewrites', () => {
    const origin = 'https://console.example.com'
    for (const trick of ['/\t/evil.example', '/\n/evil.example', '/\r/evil.example', '/\\evil.example', '/\u0000x']) {
      expect(inAppPath(trick, origin)).toBeNull()
      expect(navigationFrom({ type: 'aiwos:navigate', url: trick }, origin)).toBeNull()
    }
    expect(inAppPath('/approvals?tab=decided#approval-3', origin)).toBe('/approvals?tab=decided#approval-3')
  })

  it('round-trips a base64url key', () => {
    expect(bytesToBase64Url(base64UrlToBytes(SERVER_KEY))).toBe(SERVER_KEY)
    expect(base64UrlToBytes(SERVER_KEY)).toHaveLength(65)
  })

  it('links the web app manifest once', () => {
    linkWebManifest(document)
    linkWebManifest(document)
    const links = document.querySelectorAll('link[rel="manifest"]')
    expect(links).toHaveLength(1)
    expect(links[0]!.getAttribute('href')).toBe('/manifest.webmanifest')
  })
})

/* ---- With the real browser globals replaced ------------------------------------------------------- */

/** Puts a fake browser's service worker, PushManager and Notification onto the jsdom globals. */
function install(browser: ReturnType<typeof fakeBrowser>) {
  const env = browser.env as unknown as { navigator: { serviceWorker: unknown }; window: { Notification: unknown } }
  Object.defineProperty(window.navigator, 'serviceWorker', { value: env.navigator.serviceWorker, configurable: true })
  vi.stubGlobal('PushManager', function PushManager() {})
  vi.stubGlobal('Notification', env.window.Notification)
}

function uninstall() {
  delete (window.navigator as unknown as { serviceWorker?: unknown }).serviceWorker
}

describe('the settings section', () => {
  afterEach(uninstall)

  it('tells the server again at once when push is on here but the server no longer lists this browser', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    install(browser)
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')
    let listed: unknown[] = []
    reply = (url, method) => {
      if (url === '/api/push/subscriptions' && method === 'GET') return { status: 200, body: listed }
      if (url === '/api/push/subscriptions' && method === 'POST') {
        listed = [{ id: 'device-here', endpoint: ENDPOINT, label: 'Chrome on Android', createdAt: '2026-10-13T09:00:00Z', lastSuccessAt: null, failureCount: 0 }]
        return { status: 201, body: listed[0] }
      }
      return { status: 200, body: {} }
    }

    render(<PushNotificationsSection userId="user-1" />)

    const list = await screen.findByRole('list', { name: 'Devices with push notifications' })
    expect(list).toHaveTextContent('This device')
    expect(requests.filter((request) => request.method === 'POST' && request.url === '/api/push/subscriptions')).toHaveLength(1)
    expect(screen.getByRole('switch', { name: /Push notifications on this device/ })).toBeChecked()
  })

  it('lists the person’s devices, marks this one, and removes another by its id', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    install(browser)
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')
    let devices = [
      { id: 'device-here', endpoint: ENDPOINT, label: 'Chrome on Android', createdAt: '2026-10-01T09:00:00Z', lastSuccessAt: null, failureCount: 0 },
      {
        id: 'device-lost',
        endpoint: 'https://web.push.apple.com/lost',
        label: 'Safari on iPhone',
        createdAt: '2026-09-01T09:00:00Z',
        lastSuccessAt: '2026-10-10T09:00:00Z',
        failureCount: 0,
      },
    ]
    reply = (url, method) => {
      if (url === '/api/push/subscriptions' && method === 'GET') return { status: 200, body: devices }
      if (url === '/api/push/subscriptions/device-lost' && method === 'DELETE') {
        devices = devices.filter((device) => device.id !== 'device-lost')
        return { status: 204, body: null }
      }
      return { status: 200, body: {} }
    }

    render(<PushNotificationsSection userId="user-1" />)

    const list = await screen.findByRole('list', { name: 'Devices with push notifications' })
    expect(list).toHaveTextContent('Chrome on Android')
    expect(list).toHaveTextContent('This device')
    expect(list).toHaveTextContent('Safari on iPhone')
    // This device is turned off with the switch; only the others have a Remove button.
    const removeButtons = screen.getAllByRole('button', { name: /^Remove / })
    expect(removeButtons).toHaveLength(1)
    expect(removeButtons[0]).toHaveAccessibleName(/Safari on iPhone/)

    fireEvent.click(removeButtons[0]!)

    expect(await screen.findByText('Push notifications are off on Safari on iPhone.')).toBeInTheDocument()
    expect(requests.some((request) => request.method === 'DELETE' && request.url === '/api/push/subscriptions/device-lost')).toBe(true)
    await waitFor(() => expect(screen.queryByText('Safari on iPhone')).not.toBeInTheDocument())
  })

  it('moves focus to the status line once the removed device’s row, and its Remove button, have gone', async () => {
    // Found in the browser on 13 Oct 2026: after Remove, focus fell to the page itself, so a
    // keyboard or screen-reader user was sent back to the top.
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    install(browser)
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')
    let devices = [
      {
        id: 'device-lost',
        endpoint: 'https://web.push.apple.com/lost',
        label: 'Safari on iPhone',
        createdAt: '2026-09-01T09:00:00Z',
        lastSuccessAt: null,
        failureCount: 0,
      },
    ]
    reply = (url, method) => {
      if (url === '/api/push/subscriptions' && method === 'GET') return { status: 200, body: devices }
      if (url === '/api/push/subscriptions/device-lost' && method === 'DELETE') {
        devices = []
        return { status: 204, body: null }
      }
      return { status: 200, body: {} }
    }

    render(<PushNotificationsSection userId="user-1" />)
    const remove = await screen.findByRole('button', { name: /^Remove Safari on iPhone/ })
    remove.focus()
    expect(remove).toHaveFocus()

    fireEvent.click(remove)

    const status = await screen.findByText('Push notifications are off on Safari on iPhone.')
    await waitFor(() => expect(screen.queryByRole('button', { name: /^Remove / })).not.toBeInTheDocument())
    await waitFor(() => expect(status).toHaveFocus())
  })

  it('turns push on, sends the test and turns it off again', async () => {
    const browser = fakeBrowser()
    install(browser)
    reply = (url) => {
      if (url.includes('vapid-public-key')) return { status: 200, body: { enabled: true, publicKey: SERVER_KEY } }
      if (url.endsWith('/test')) {
        return { status: 200, body: { devices: 1, delivered: 1, removed: 0, message: 'The test notification was sent to your device.' } }
      }
      return { status: url.endsWith('/subscriptions') ? 201 : 200, body: {} }
    }

    render(<PushNotificationsSection userId="user-1" />)
    const toggle = await screen.findByRole('switch', { name: /Push notifications on this device/ })
    await waitFor(() => expect(toggle).not.toBeDisabled())
    expect(toggle).not.toBeChecked()
    expect(screen.getByText(/add the app to your Home Screen/)).toBeInTheDocument()

    fireEvent.click(toggle)
    expect(await screen.findByText('Push notifications are on for this device.')).toBeInTheDocument()
    expect(toggle).toBeChecked()

    fireEvent.click(screen.getByRole('button', { name: 'Send a test notification' }))
    expect(await screen.findByText('The test notification was sent to your device.')).toBeInTheDocument()
    expect(requests.some((request) => request.method === 'POST' && request.url === '/api/push/test')).toBe(true)

    fireEvent.click(toggle)
    expect(await screen.findByText('Push notifications are off for this device.')).toBeInTheDocument()
    expect(requests.some((request) => request.method === 'DELETE')).toBe(true)
    expect(screen.queryByRole('button', { name: 'Send a test notification' })).not.toBeInTheDocument()
  })

  it('explains a refusal in words and leaves the switch off', async () => {
    const browser = fakeBrowser({ answer: 'denied' })
    install(browser)

    render(<PushNotificationsSection userId="user-1" />)
    const toggle = await screen.findByRole('switch', { name: /Push notifications on this device/ })
    await waitFor(() => expect(toggle).not.toBeDisabled())
    fireEvent.click(toggle)

    expect(await screen.findByText(/did not allow notifications/)).toBeInTheDocument()
    expect(toggle).not.toBeChecked()
  })

  it('is switched off and says why in a browser without push', async () => {
    render(<PushNotificationsSection userId="user-1" />)

    expect(screen.getByRole('switch', { name: /Push notifications on this device/ })).toBeDisabled()
    expect(screen.getByText('This browser cannot receive push notifications.')).toBeInTheDocument()
    // The device list is still asked for: another device can be removed from a browser without push.
    await waitFor(() => expect(requests.some((request) => request.method === 'GET' && request.url === '/api/push/subscriptions')).toBe(true))
  })
})

describe('PushBridge', () => {
  afterEach(uninstall)

  it('opens the link a tapped notification names, inside the app', async () => {
    const browser = fakeBrowser()
    install(browser)
    window.history.replaceState({}, '', '/chat')

    render(
      <RouterProvider>
        <PushBridge />
      </RouterProvider>,
    )
    const onMessage = browser.serviceWorker.addEventListener.mock.calls.find((call) => call[0] === 'message')![1] as unknown as (
      event: { data: unknown },
    ) => void
    act(() => onMessage({ data: { type: 'aiwos:navigate', url: '/approvals#approval-7' } }))

    expect(window.location.pathname).toBe('/approvals')
    expect(window.location.hash).toBe('#approval-7')
  })

  it('keeps push when the session merely ended, and drops it when someone else signs in here', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    install(browser)
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')

    // Nobody signed in, no session to restore: an expired session looks exactly like this.
    render(
      <RouterProvider>
        <PushBridge />
      </RouterProvider>,
    )
    act(() => window.dispatchEvent(new Event('aiwos:session')))
    await act(async () => {})
    expect(browser.subscription()).not.toBeNull()
    expect(pushOwner()).toBe('user-1')

    act(() =>
      saveSession('token', {
        userId: 'user-2',
        workspaceId: 'workspace-1',
        permissions: [],
        displayName: 'Priya Shah',
        email: 'priya@demo.test',
        role: 'employee',
      }),
    )
    await waitFor(() => expect(browser.subscription()).toBeNull())
    expect(pushOwner()).toBeNull()
  })

  it('after a session ended, the next sign-in tells the server at once, even if it was told earlier that day', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    install(browser)
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')
    // Told this morning, under the sign-in that has since ended.
    await syncPush('user-1', browser.env, { workspaceId: 'workspace-1' })
    requests = []

    render(
      <RouterProvider>
        <PushBridge />
      </RouterProvider>,
    )
    act(() => window.dispatchEvent(new Event('aiwos:session')))
    act(() =>
      saveSession('token', {
        userId: 'user-1',
        workspaceId: 'workspace-1',
        permissions: [],
        displayName: 'Maya Manager',
        email: 'maya@demo.test',
        role: 'manager',
      }),
    )

    await waitFor(() => expect(requests.some((request) => request.url === '/api/push/subscriptions' && request.method === 'POST')).toBe(true))
  })

  it('tells the server the signed-in owner’s subscription again on arrival', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    install(browser)
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')
    saveSession('token', {
      userId: 'user-1',
      workspaceId: 'workspace-1',
      permissions: [],
      displayName: 'Maya Manager',
      email: 'maya@demo.test',
      role: 'manager',
    })

    render(
      <RouterProvider>
        <PushBridge />
      </RouterProvider>,
    )

    await waitFor(() => expect(requests.some((request) => request.url === '/api/push/subscriptions' && request.method === 'POST')).toBe(true))
    expect(browser.subscription()).not.toBeNull()
  })
})
