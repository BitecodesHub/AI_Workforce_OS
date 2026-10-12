// @find: tests for push notifications client, enable push flow, disable push, service worker registration, PushManager subscribe, forget push on sign out, push settings section, push bridge navigation
// @what: Tests for lib/push.ts, lib/pushBridge.tsx and the push settings section, with the service worker, PushManager and Notification replaced by stand-ins.
// @flow: Runs under vitest + jsdom; fetch is stubbed per test
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  PUSH_OWNER_KEY,
  base64UrlToBytes,
  bytesToBase64Url,
  disablePush,
  enablePush,
  forgetPushOfOthers,
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

  it('drops push that belongs to someone else, or to anyone once nobody is signed in, and keeps the owner’s own', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')

    expect(await forgetPushOfOthers('user-1', browser.env)).toBe(false)
    expect(browser.subscription()).not.toBeNull()

    expect(await forgetPushOfOthers('user-2', browser.env)).toBe(true)
    expect(browser.subscription()).toBeNull()
    expect(pushOwner()).toBeNull()

    const again = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')
    expect(await forgetPushOfOthers(null, again.env)).toBe(true)
    expect(again.subscription()).toBeNull()
  })

  it('tells the server the owner’s subscription again, and does nothing for anyone else', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')

    await syncPush('user-2', browser.env)
    expect(requests).toEqual([])

    await syncPush('user-1', browser.env)
    expect(requests.map((request) => `${request.method} ${request.url}`)).toEqual(['POST /api/push/subscriptions'])
  })
})

describe('small parts', () => {
  it('accepts only an in-app path from the service worker', () => {
    expect(navigationFrom({ type: 'aiwos:navigate', url: '/approvals#approval-1' })).toBe('/approvals#approval-1')
    expect(navigationFrom({ type: 'aiwos:navigate', url: 'https://evil.example' })).toBeNull()
    expect(navigationFrom({ type: 'aiwos:navigate', url: '//evil.example' })).toBeNull()
    expect(navigationFrom({ type: 'other', url: '/x' })).toBeNull()
    expect(navigationFrom('nope')).toBeNull()
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

  it('is switched off and says why in a browser without push', () => {
    render(<PushNotificationsSection userId="user-1" />)

    expect(screen.getByRole('switch', { name: /Push notifications on this device/ })).toBeDisabled()
    expect(screen.getByText('This browser cannot receive push notifications.')).toBeInTheDocument()
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

  it('drops push of a person who signed out, but not while a tab is still signing in', async () => {
    const browser = fakeBrowser({ permission: 'granted', existingKey: SERVER_KEY })
    install(browser)
    localStorage.setItem(PUSH_OWNER_KEY, 'user-1')
    localStorage.setItem('aiwos.signedIn', '1')

    const view = render(
      <RouterProvider>
        <PushBridge />
      </RouterProvider>,
    )
    await act(async () => {})
    expect(browser.subscription()).not.toBeNull()

    localStorage.removeItem('aiwos.signedIn')
    view.rerender(
      <RouterProvider>
        <PushBridge />
      </RouterProvider>,
    )
    act(() => window.dispatchEvent(new Event('aiwos:session')))
    await waitFor(() => expect(browser.subscription()).toBeNull())
    expect(pushOwner()).toBeNull()
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
