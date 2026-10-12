/*
 * @find: service worker, sw.js, push event, show notification, notificationclick, open deep link, focus window, web push receiver
 * @what: The service worker: shows each push notification and, when one is tapped, brings the app forward on its link.
 * @flow: Registered by web/src/lib/push.ts only once a person turns push on; messages come from orchestrator push.PushDelivery; a tap reaches lib/pushBridge.tsx as a message
 */
'use strict'

/*
 * Deliberately small, and it caches nothing: the app is not offline-capable, and a service worker
 * that served old files would outlive a deploy. It only receives push messages and handles the
 * tap on a notification.
 *
 * A message is JSON: { kind, title, body, tag, url }. The url is a path inside the app; anything
 * else (another site, a protocol-relative address) is replaced by the Approvals page, so a message
 * can never send a person somewhere outside the app.
 */

const FALLBACK_URL = '/approvals'
const FALLBACK_TITLE = 'Something is waiting for you'
const ICON = '/icons/icon-192.png'
// The status-bar glyph on Android: the mark alone, in white on transparent.
const BADGE = '/icons/badge-96.png'

/** Whether the text holds a control character (which the address parser silently drops) or a backslash. */
function hasUnsafeCharacter(value) {
  for (let i = 0; i < value.length; i += 1) {
    const code = value.charCodeAt(i)
    if (code < 0x20 || code === 0x7f || code === 0x5c) return true
  }
  return false
}

/**
 * A path inside this app, or the Approvals page. The value is resolved the way the browser would
 * resolve it and its origin compared, so a tab or line break the parser drops ("/<tab>/evil.example"
 * becomes "//evil.example"), a backslash or a protocol-relative address cannot lead elsewhere.
 */
function safePath(value) {
  if (typeof value !== 'string' || !value.startsWith('/') || hasUnsafeCharacter(value)) return FALLBACK_URL
  try {
    const url = new URL(value, self.location.origin)
    return url.origin === self.location.origin ? url.pathname + url.search + url.hash : FALLBACK_URL
  } catch (notAnAddress) {
    return FALLBACK_URL
  }
}

/** Text no longer than `max`, or the fallback when it is not text. */
function text(value, max, fallback) {
  if (typeof value !== 'string' || value.trim() === '') return fallback
  return value.length > max ? value.slice(0, max - 1) + '…' : value
}

/** The notification a push message asks for; a message that cannot be read still shows something. */
function readMessage(data) {
  let message = null
  try {
    message = data ? data.json() : null
  } catch (unreadable) {
    message = null
  }
  if (!message || typeof message !== 'object') message = {}
  return {
    title: text(message.title, 120, FALLBACK_TITLE),
    body: text(message.body, 300, 'Open the app to see what is waiting.'),
    tag: text(message.tag, 80, 'aiwos'),
    url: safePath(message.url),
  }
}

/** Shows the notification for one push event. */
function showFromPush(event) {
  const message = readMessage(event.data)
  return self.registration.showNotification(message.title, {
    body: message.body,
    tag: message.tag,
    icon: ICON,
    badge: BADGE,
    data: { url: message.url },
  })
}

/**
 * Brings the app to the notification's link: an open window of the app is focused and told where
 * to go (it navigates without reloading); with none open, a new one opens on the link.
 */
async function openFromClick(event) {
  const path = safePath(event.notification && event.notification.data ? event.notification.data.url : null)
  const origin = self.location.origin
  const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
  const ours = windows.filter((client) => {
    try {
      return new URL(client.url).origin === origin
    } catch (notAUrl) {
      return false
    }
  })
  const target = ours.find((client) => client.focused) || ours[0]
  if (target) {
    target.postMessage({ type: 'aiwos:navigate', url: path })
    return target.focus()
  }
  return self.clients.openWindow(new URL(path, origin).href)
}

self.addEventListener('install', () => {
  // Nothing to fetch ahead: take over at once, so a new version applies without a second visit.
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('push', (event) => {
  event.waitUntil(showFromPush(event))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  event.waitUntil(openFromClick(event))
})
