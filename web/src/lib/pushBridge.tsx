// @find: push bridge, notification tap opens the app, service worker message, keep push subscription current, forget push on sign out, PushBridge
// @what: Connects the service worker to the app: a tapped notification opens its link here, the person's subscription is kept current, and push stops when someone else signs in or everyone signs out.
// @flow: Mounted once in main.tsx inside RouterProvider; uses lib/push.ts, lib/session.ts and the router's navigate
import { useEffect, useRef, useState } from 'react'
import { forgetPushOfOthers, navigationFrom, syncPush } from './push'
import { useRouter } from './router'
import { SESSION_EVENT, maySignInSilently, profile } from './session'

/*
 * Nothing renders. A notification tapped while the app is open in a window arrives here as a
 * message from the service worker and becomes an ordinary in-app navigation (no reload). On every
 * move and every session change the bridge checks whose push this browser carries: the signed-in
 * person's is told to the server again once per visit (a browser may renew its subscription
 * quietly); anyone else's is dropped, so a shared computer does not keep showing one person's
 * notifications after they signed out.
 */

// @find: PushBridge component
/** The bridge between the service worker, the session and the router. Renders nothing; mounted once. */
export function PushBridge() {
  const { navigate, path } = useRouter()
  const [sessionVersion, setSessionVersion] = useState(0)
  const syncedFor = useRef<string | null>(null)

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
    const me = profile()?.userId ?? null
    // No profile yet but a session to restore is a tab still signing in, not a sign-out.
    if (me !== null || !maySignInSilently()) void forgetPushOfOthers(me)
    if (me !== null && syncedFor.current !== me) {
      syncedFor.current = me
      void syncPush(me)
    }
  }, [path, sessionVersion])

  return null
}
