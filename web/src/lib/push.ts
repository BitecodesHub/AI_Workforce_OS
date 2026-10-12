// @find: push notifications, web push, service worker registration, subscribe this device, unsubscribe this device, VAPID key, test notification, add to home screen, iPhone push, my push devices, remove a device, /api/push
// @what: Turns push notifications on or off for this browser: registers the service worker, subscribes with the server's key, tells the server (in each workspace opened here), lists and removes the person's devices, and sends the test.
// @flow: Used by components/settings/PushNotificationsSection.tsx and lib/pushBridge.tsx; calls /api/push through api.ts; registers public/sw.js
import { api } from './api'
import { profile } from './session'

/*
 * Push notifications on this device.
 *
 * Nothing here runs until the person asks for it: the service worker is registered only when push
 * is turned on (or was turned on before, by the same person, in this browser), and the browser's
 * permission prompt appears only in answer to that click. Push is on for the device: the server
 * keeps the subscription against the person in each workspace they open here (it is told again
 * at most once a day per workspace, and whenever the browser renews the subscription), and stops
 * using it once that sign-in ends. This browser remembers whose push it is, so a different person
 * signing in here is not sent someone else's notifications, and signing out drops it - but a
 * session that merely expired does not, so push still works after the person signs in again.
 *
 * iPhone and iPad allow web push only to a site added to the Home Screen and opened from there;
 * in a Safari tab the browser offers no push at all, and this says so rather than failing.
 */

/** Where the service worker lives; its scope is the whole app. */
export const SERVICE_WORKER_URL = '/sw.js'

/** localStorage key holding the id of the person this browser's push subscription belongs to. */
export const PUSH_OWNER_KEY = 'aiwos.push.owner'

/** localStorage key holding when this browser last told the server its subscription, per person, workspace and endpoint. */
export const PUSH_SYNCED_KEY = 'aiwos.push.synced'

/** How long a told subscription is not told again, unless it changed: a day. */
export const PUSH_SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000

/** The most workspaces whose last telling is remembered. */
const SYNCED_KEEP = 20

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

/** When each subscription was last told to the server, keyed by person, workspace and endpoint. */
function syncedTimes(): Record<string, number> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(PUSH_SYNCED_KEY) ?? '{}')
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as Record<string, number>) : {}
  } catch {
    return {}
  }
}

/** Notes that this subscription was just told to the server in this workspace. Never throws. */
function noteSynced(key: string, at: number) {
  try {
    const kept = Object.entries({ ...syncedTimes(), [key]: at })
      .sort((a, b) => b[1] - a[1])
      .slice(0, SYNCED_KEEP)
    localStorage.setItem(PUSH_SYNCED_KEY, JSON.stringify(Object.fromEntries(kept)))
  } catch {
    /* Blocked storage: the server is told again next time, which is harmless. */
  }
}

// @find: forget push sync notes after a session ended
/**
 * Forgets every note of what was told, so the next sign-in tells the server afresh: the server
 * stops using a subscription whose sign-in ended, and the new sign-in must take it over. Never
 * throws.
 */
export function forgetPushSyncNotes(): void {
  forgetSynced()
}

/** Forgets every note of what was told, so the next visit tells the server afresh. Never throws. */
function forgetSynced() {
  try {
    localStorage.removeItem(PUSH_SYNCED_KEY)
  } catch {
    /* Nothing to forget. */
  }
}

/** The key one telling is remembered by. */
function syncKey(userId: string, workspaceId: string | null | undefined, endpoint: string): string {
  return `${userId}|${workspaceId ?? ''}|${endpoint}`
}

/** The workspace the signed-in session is for, or null. */
function currentWorkspace(): string | null {
  return profile()?.workspaceId ?? null
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
    noteSynced(syncKey(userId, currentWorkspace(), subscription.endpoint), Date.now())
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
  forgetSynced()
  if (!subscription) return
  try {
    await api<void>('/api/push/subscriptions', { method: 'DELETE', body: { endpoint: subscription.endpoint } })
  } catch {
    /* The browser's own unsubscribe below ends it either way: the server is told 410 next time and removes it. */
  }
  await subscription.unsubscribe().catch(() => false)
}

// @find: forget push when someone else signs in
/**
 * Drops this browser's subscription when it belongs to someone other than the person now signed
 * in (`userId`), so a shared computer does not show one person the next one's notifications. With
 * nobody signed in (`userId` null) nothing is dropped: the session may only have expired, and the
 * same person signing in again should find push still on. Signing out drops it instead
 * ({@link forgetPushOnSignOut}). Only the browser's side is dropped: the server stops sending once
 * the earlier person's sign-in ends, or at its next send. Never throws.
 */
export async function forgetPushOfOthers(userId: string | null, env: PushEnvironment | null = browser()): Promise<boolean> {
  const owner = pushOwner()
  if (!owner || userId === null || owner === userId) return false
  setPushOwner(null)
  forgetSynced()
  const subscription = await currentSubscription(env)
  if (subscription) await subscription.unsubscribe().catch(() => false)
  return true
}

// @find: sign out push, drop push on sign out
/**
 * For a person signing out on purpose: this browser forgets whose push it was and drops its
 * subscription, so nothing more of theirs shows here. The server stops using it too, since the
 * sign-in it belonged to has ended. Never throws.
 */
export async function forgetPushOnSignOut(env: PushEnvironment | null = browser()): Promise<void> {
  if (!pushOwner()) return
  setPushOwner(null)
  forgetSynced()
  const subscription = await currentSubscription(env)
  if (subscription) await subscription.unsubscribe().catch(() => false)
}

// @find: keep push subscription in step with the server
/**
 * For a person who turned push on in this browser: tells the server the subscription in the
 * workspace now open, so one the browser renewed, one the server dropped when an earlier sign-in
 * ended, or a workspace not yet told, is current - and so the server knows which sign-in it now
 * belongs to. The same subscription is told at most once a day per workspace unless `force`.
 * Asks nothing of the person. Never throws.
 */
export async function syncPush(
  userId: string,
  env: PushEnvironment | null = browser(),
  options: { workspaceId?: string | null; force?: boolean; now?: number } = {},
): Promise<void> {
  if (!env || pushOwner() !== userId || pushPermission(env) !== 'granted') return
  const workspaceId = options.workspaceId === undefined ? currentWorkspace() : options.workspaceId
  const now = options.now ?? Date.now()
  try {
    let subscription = await currentSubscription(env)
    if (!subscription) {
      const key = await api<PushKey>('/api/push/vapid-public-key')
      if (!key.enabled || !key.publicKey) return
      const registration = await readyRegistration(env)
      subscription = await subscribeWith(registration, key.publicKey)
    } else {
      const told = syncedTimes()[syncKey(userId, workspaceId, subscription.endpoint)]
      if (!options.force && told !== undefined && now - told < PUSH_SYNC_INTERVAL_MS) return
    }
    await saveSubscription(subscription)
    noteSynced(syncKey(userId, workspaceId, subscription.endpoint), now)
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

// @find: list my push devices; route: GET /api/push/subscriptions
/** The person's devices with push on in this workspace, newest first; an empty list for an answer that is not a list. */
export async function listPushDevices(): Promise<PushDevice[]> {
  const devices = await api<unknown>('/api/push/subscriptions')
  return Array.isArray(devices) ? (devices as PushDevice[]) : []
}

// @find: remove one of my push devices; route: DELETE /api/push/subscriptions/{id}
/** Turns push off on one of the person's devices, from any browser: a phone lost or handed on. */
export function removePushDevice(id: string): Promise<void> {
  return api<void>(`/api/push/subscriptions/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

/* ---- Messages from the service worker ----------------------------------------------------------- */

/** What the service worker posts when a notification is tapped and a window of the app is already open. */
export type PushNavigateMessage = { type: 'aiwos:navigate'; url: string }

/** Whether the text holds a control character (which the address parser silently drops) or a backslash. */
function hasUnsafeCharacter(value: string): boolean {
  for (let i = 0; i < value.length; i += 1) {
    const code = value.charCodeAt(i)
    if (code < 0x20 || code === 0x7f || code === 0x5c) return true
  }
  return false
}

// @find: in-app path check, open redirect guard
/**
 * `value` as a path inside the app at `origin` (path, query and fragment), or null when it would
 * lead anywhere else. Resolved the way the browser resolves it, so tricks that a prefix check
 * misses - a tab or line break the address parser drops ("/\t/evil.example"), a backslash, a
 * protocol-relative "//host" - cannot leave the app.
 */
export function inAppPath(value: unknown, origin: string): string | null {
  if (typeof value !== 'string' || !value.startsWith('/') || hasUnsafeCharacter(value)) return null
  try {
    const url = new URL(value, origin)
    return url.origin === origin ? `${url.pathname}${url.search}${url.hash}` : null
  } catch {
    return null
  }
}

// @find: notification click message, navigate from service worker
/** The in-app path a service worker message asks for, or null when it is not one of ours or not a path in this app. */
export function navigationFrom(data: unknown, origin: string = typeof window === 'undefined' ? '' : window.location.origin): string | null {
  if (typeof data !== 'object' || data === null) return null
  const message = data as Partial<PushNavigateMessage>
  if (message.type !== 'aiwos:navigate' || typeof message.url !== 'string') return null
  return origin ? inAppPath(message.url, origin) : null
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
