// @find: push bridge, notification tap opens the app, service worker message, keep push subscription current, forget push when someone else signs in, PushBridge
// @what: Connects the service worker to the app: a tapped notification opens its link here, the person's subscription is kept current in each workspace they open, and push stops when someone else signs in.
// @flow: Mounted once in main.tsx inside RouterProvider; uses lib/push.ts, lib/session.ts and the router's navigate
import { useEffect, useRef, useState } from 'react'
import { forgetPushOfOthers, forgetPushSyncNotes, navigationFrom, syncPush } from './push'
import { useRouter } from './router'
import { SESSION_EVENT, maySignInSilently, profile } from './session'

/*
 * Nothing renders. A notification tapped while the app is open in a window arrives here as a
 * message from the service worker and becomes an ordinary in-app navigation (no reload). On every
 * move and every session change the bridge checks whose push this browser carries: the signed-in
 * person's is told to the server in the workspace now open (at most once a day each, unless it
 * changed; lib/push.ts keeps count), which also keeps the server's note of the sign-in it belongs
 * to fresh; a different person's is dropped, so a shared computer does not keep showing one
 * person's notifications to the next. Nobody being signed in drops nothing - that may be a session
 * that expired, and push should still be on when the same person signs in again - but it does
 * make the next sign-in tell the server at once, since the server stops using a subscription
 * whose sign-in ended. Signing out on purpose drops push where the sign-out happens
 * (lib/push.ts forgetPushOnSignOut).
 */

// @find: PushBridge component
/** The bridge between the service worker, the session and the router. Renders nothing; mounted once. */
export function PushBridge() {
  const { navigate, path } = useRouter()
  const [sessionVersion, setSessionVersion] = useState(0)
  const syncing = useRef(false)

  useEffect(() => {
    const container = typeof navigator === 'undefined' ? undefined : navigator.serviceWorker
    if (!container) return
    const onMessage = (event: MessageEvent) => {
      const url = navigationFrom(event.data)
      if (url) navigate(url)
    }
    container.addEventListener('message', onMessage)
    return () => container.removeEventListener('message', onMessage)
  }, [navigate])

  useEffect(() => {
    const onSession = () => setSessionVersion((version) => version + 1)
    window.addEventListener(SESSION_EVENT, onSession)
    return () => window.removeEventListener(SESSION_EVENT, onSession)
  }, [])

  useEffect(() => {
    const me = profile()
    if (!me?.userId) {
      // Signed out or expired (not a tab still restoring its session): the next sign-in is a new
      // session, which the server must be told at once, whatever was told earlier today.
      if (!maySignInSilently()) forgetPushSyncNotes()
      return
    }
    void forgetPushOfOthers(me.userId)
    // On every move, so a tab left open for days still tells the server once a day; syncPush
    // itself skips what was told today, and one telling at a time is enough.
    if (syncing.current) return
    syncing.current = true
    void syncPush(me.userId, undefined, { workspaceId: me.workspaceId ?? null }).finally(() => {
      syncing.current = false
    })
  }, [path, sessionVersion])

  return null
}
