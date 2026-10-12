// @find: push notifications, web push, service worker registration, subscribe this device, unsubscribe this device, VAPID key, test notification, add to home screen, iPhone push, /api/push
// @what: Turns push notifications on or off for this browser: registers the service worker, subscribes with the server's key, tells the server, and sends the test.
// @flow: Used by components/settings/PushNotificationsSection.tsx and lib/pushBridge.tsx; calls /api/push through api.ts; registers public/sw.js
import { api } from './api'

/*
 * Push notifications on this device.
 *
 * Nothing here runs until the person asks for it: the service worker is registered only when push
 * is turned on (or was turned on before, by the same person, in this browser), and the browser's
 * permission prompt appears only in answer to that click. The server keeps the subscription
 * against the person; this browser remembers whose it is, so a different person signing in here
 * is not sent someone else's notifications.
 *
 * iPhone and iPad allow web push only to a site added to the Home Screen and opened from there;
 * in a Safari tab the browser offers no push at all, and this says so rather than failing.
 */

/** Where the service worker lives; its scope is the whole app. */
export const SERVICE_WORKER_URL = '/sw.js'

/** localStorage key holding the id of the person this browser's push subscription belongs to. */
export const PUSH_OWNER_KEY = 'aiwos.push.owner'

/** Whether this browser can receive push: yes, only once added to the Home Screen (iPhone, iPad), or not at all. */
export type PushSupport = 'supported' | 'needs-home-screen' | 'unsupported'

/** How turning push on ended. */
export type EnablePushOutcome = 'on' | 'denied' | 'unsupported' | 'needs-home-screen' | 'unavailable' | 'failed'

/** The server's answer to GET /api/push/vapid-public-key. */
export type PushKey = { enabled: boolean; publicKey: string | null }

/** One of the person's devices, as GET /api/push/subscriptions lists it. */
export type PushDevice = {
  id: string
  endpoint: string
  label: string
  createdAt: string
  lastSuccessAt: string | null
  failureCount: number
}

/** How the test went (POST /api/push/test). */
export type PushTestResult = { devices: number; delivered: number; removed: number; message: string }

/** The browser objects push needs; replaceable in tests. */
export type PushEnvironment = {
  navigator: Navigator
  window: Window
}

/** The real browser, or null where there is none. */
function browser(): PushEnvironment | null {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') return null
  return { navigator, window }
}

// @find: is this an iPhone or iPad
/** Whether the device is an iPhone, iPad or iPod, including an iPad that presents itself as a Mac. */
export function isAppleMobile(env: PushEnvironment | null = browser()): boolean {
  if (!env) return false
  const agent = env.navigator.userAgent || ''
  if (/iPhone|iPad|iPod/.test(agent)) return true
  return /Macintosh/.test(agent) && (env.navigator.maxTouchPoints ?? 0) > 1
}

/** Whether the app was opened from the Home Screen rather than in a browser tab. */
export function isStandalone(env: PushEnvironment | null = browser()): boolean {
  if (!env) return false
  const legacy = (env.navigator as Navigator & { standalone?: boolean }).standalone
  if (legacy === true) return true
  try {
    return env.window.matchMedia?.('(display-mode: standalone)').matches ?? false
  } catch {
    return false
  }
}

// @find: can this browser receive push
/** Whether this browser can receive push notifications, and if not, whether adding it to the Home Screen would help. */
export function pushSupport(env: PushEnvironment | null = browser()): PushSupport {
  if (!env) return 'unsupported'
  const hasWorker = 'serviceWorker' in env.navigator && Boolean(env.navigator.serviceWorker)
  const hasPush = 'PushManager' in env.window && 'Notification' in env.window
  if (hasWorker && hasPush) return 'supported'
  if (isAppleMobile(env) && !isStandalone(env)) return 'needs-home-screen'
  return 'unsupported'
}

/** The browser's notification permission, or 'unsupported'. */
export function pushPermission(env: PushEnvironment | null = browser()): NotificationPermission | 'unsupported' {
  if (!env || !('Notification' in env.window)) return 'unsupported'
  return (env.window as Window & { Notification: typeof Notification }).Notification.permission
}

// @find: decode VAPID public key for subscribe
/** A base64url key (as the server sends it) as the bytes PushManager.subscribe wants. */
export function base64UrlToBytes(base64Url: string): Uint8Array<ArrayBuffer> {
  const padded = base64Url.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (base64Url.length % 4)) % 4)
  const raw = atob(padded)
  const bytes = new Uint8Array(new ArrayBuffer(raw.length))
  for (let i = 0; i < raw.length; i += 1) bytes[i] = raw.charCodeAt(i)
  return bytes
}

/** Bytes as base64url without padding, to compare with a key the server sent. */
export function bytesToBase64Url(bytes: ArrayBuffer | Uint8Array | null | undefined): string {
  if (!bytes) return ''
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes)
  let raw = ''
  for (const byte of view) raw += String.fromCharCode(byte)
  return btoa(raw).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/* ---- Who this browser's push belongs to --------------------------------------------------------- */

/** The person this browser's push belongs to, or null. Never throws. */
export function pushOwner(): string | null {
  try {
    return localStorage.getItem(PUSH_OWNER_KEY)
  } catch {
    return null
  }
}

/** Remembers (or forgets, with null) whose this browser's push is. Never throws. */
function setPushOwner(userId: string | null) {
  try {
    if (userId) localStorage.setItem(PUSH_OWNER_KEY, userId)
    else localStorage.removeItem(PUSH_OWNER_KEY)
  } catch {
    /* Blocked storage: the server still knows; this browser just cannot tell who it belonged to. */
  }
}

/* ---- The service worker and the subscription ------------------------------------------------------ */

// The registration that controls the app, without registering one. Null where there is none.
async function existingRegistration(env: PushEnvironment): Promise<ServiceWorkerRegistration | null> {
  try {
    return (await env.navigator.serviceWorker.getRegistration('/')) ?? null
  } catch {
    return null
  }
}

// @find: this browser's push subscription
/** This browser's current push subscription, without registering anything. Null where there is none. */
export async function currentSubscription(env: PushEnvironment | null = browser()): Promise<PushSubscription | null> {
  if (!env || pushSupport(env) !== 'supported') return null
  const registration = await existingRegistration(env)
  if (!registration) return null
  try {
    return await registration.pushManager.getSubscription()
  } catch {
    return null
  }
}

// Registers the service worker (once; the browser keeps it) and waits until it is ready, for at most ten seconds.
async function readyRegistration(env: PushEnvironment): Promise<ServiceWorkerRegistration> {
  await env.navigator.serviceWorker.register(SERVICE_WORKER_URL, { scope: '/' })
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      env.navigator.serviceWorker.ready,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error('The service worker did not start.')), 10_000)
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
}

/** Sends this browser's subscription to the server, which saves it against the signed-in person. */
async function saveSubscription(subscription: PushSubscription): Promise<void> {
  const json = subscription.toJSON()
  await api<PushDevice>('/api/push/subscriptions', {
    method: 'POST',
    body: { endpoint: json.endpoint ?? subscription.endpoint, expirationTime: json.expirationTime ?? null, keys: json.keys ?? {} },
  })
}

/**
 * A subscription made with the server's key: the existing one when it already is, otherwise a new
 * one (an old one made with another key is dropped first, since the server could never reach it).
 */
async function subscribeWith(registration: ServiceWorkerRegistration, publicKey: string): Promise<PushSubscription> {
  const existing = await registration.pushManager.getSubscription()
  if (existing) {
    const existingKey = bytesToBase64Url(existing.options?.applicationServerKey ?? null)
    if (!existingKey || existingKey === publicKey) return existing
    await existing.unsubscribe().catch(() => false)
  }
  return registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: base64UrlToBytes(publicKey) })
}

// @find: turn on push for this device, enable push
/**
 * Turns push on for this browser, for `userId`. Asks the browser's permission now (the person
 * just asked for notifications), fetches the server's key, registers the service worker,
 * subscribes, and saves the subscription on the server. Never throws: every way it can end is an
 * outcome the settings card can explain.
 */
export async function enablePush(userId: string, env: PushEnvironment | null = browser()): Promise<EnablePushOutcome> {
  const support = pushSupport(env)
  if (support !== 'supported' || !env) return support === 'needs-home-screen' ? 'needs-home-screen' : 'unsupported'
  const NotificationApi = (env.window as Window & { Notification: typeof Notification }).Notification
  let permission = NotificationApi.permission
  if (permission === 'default') {
    try {
      permission = await NotificationApi.requestPermission()
    } catch {
      return 'denied'
    }
  }
  if (permission !== 'granted') return 'denied'
  try {
    const key = await api<PushKey>('/api/push/vapid-public-key')
    if (!key.enabled || !key.publicKey) return 'unavailable'
    const registration = await readyRegistration(env)
    const subscription = await subscribeWith(registration, key.publicKey)
    await saveSubscription(subscription)
    setPushOwner(userId)
    return 'on'
  } catch {
    return 'failed'
  }
}

// @find: turn off push for this device, disable push
/**
 * Turns push off for this browser: the server forgets the subscription, and the browser drops it,
 * so even a server that missed the first step can no longer reach it. Never throws.
 */
export async function disablePush(env: PushEnvironment | null = browser()): Promise<void> {
  const subscription = await currentSubscription(env)
  setPushOwner(null)
  if (!subscription) return
  try {
    await api<void>('/api/push/subscriptions', { method: 'DELETE', body: { endpoint: subscription.endpoint } })
  } catch {
    /* The browser's own unsubscribe below ends it either way: the server is told 410 next time and removes it. */
  }
  await subscription.unsubscribe().catch(() => false)
}

// @find: forget push when someone else signs in, sign out push
/**
 * Drops this browser's subscription when it belongs to someone other than the person now signed in
 * (`userId`), or to anyone once nobody is (`userId` null and no session to restore), so a shared
 * computer does not show one person the next one's notifications. Only the browser's side is
 * dropped: the server learns at its next send that the device is gone. Never throws.
 */
export async function forgetPushOfOthers(userId: string | null, env: PushEnvironment | null = browser()): Promise<boolean> {
  const owner = pushOwner()
  if (!owner || owner === userId) return false
  setPushOwner(null)
  const subscription = await currentSubscription(env)
  if (subscription) await subscription.unsubscribe().catch(() => false)
  return true
}

// @find: keep push subscription in step with the server
/**
 * For a person who turned push on in this browser: tells the server the subscription again, so one
 * the browser renewed, or the server dropped, is current. Asks nothing of the person. Never throws.
 */
export async function syncPush(userId: string, env: PushEnvironment | null = browser()): Promise<void> {
  if (!env || pushOwner() !== userId || pushPermission(env) !== 'granted') return
  try {
    const subscription = await currentSubscription(env)
    if (subscription) {
      await saveSubscription(subscription)
      return
    }
    const key = await api<PushKey>('/api/push/vapid-public-key')
    if (!key.enabled || !key.publicKey) return
    const registration = await readyRegistration(env)
    await saveSubscription(await subscribeWith(registration, key.publicKey))
  } catch {
    /* Tried again the next time the app opens. */
  }
}

/** Whether push is on in this browser for `userId`: their subscription, here. */
export async function isPushOn(userId: string, env: PushEnvironment | null = browser()): Promise<boolean> {
  if (pushOwner() !== userId) return false
  return (await currentSubscription(env)) !== null
}

// @find: send a test notification; route: POST /api/push/test
/** Asks the server to send the test notification to every one of the person's devices. */
export function sendTestPush(): Promise<PushTestResult> {
  return api<PushTestResult>('/api/push/test', { method: 'POST' })
}

/* ---- Messages from the service worker ----------------------------------------------------------- */

/** What the service worker posts when a notification is tapped and a window of the app is already open. */
export type PushNavigateMessage = { type: 'aiwos:navigate'; url: string }

// @find: notification click message, navigate from service worker
/** The in-app path a service worker message asks for, or null when it is not one of ours or not a path in this app. */
export function navigationFrom(data: unknown): string | null {
  if (typeof data !== 'object' || data === null) return null
  const message = data as Partial<PushNavigateMessage>
  if (message.type !== 'aiwos:navigate' || typeof message.url !== 'string') return null
  const url = message.url
  return url.startsWith('/') && !url.startsWith('//') && !url.includes('\\') ? url : null
}

// @find: link the web app manifest
/** Adds the web app manifest link (once), so the app can be added to a phone's Home Screen. Never throws. */
export function linkWebManifest(doc: Document | null = typeof document === 'undefined' ? null : document): void {
  if (!doc || doc.querySelector('link[rel="manifest"]')) return
  try {
    const link = doc.createElement('link')
    link.rel = 'manifest'
    link.href = '/manifest.webmanifest'
    doc.head.appendChild(link)
  } catch {
    /* No head to add it to. */
  }
}
