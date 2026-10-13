// @find: server render, prerender, build-time render, is server, renderToString, prerendered markup, useServerRender
// @what: React hook that reports true while a screen is rendered to HTML at build time and false in the browser.
// @flow: Used by routes/SignIn.tsx to keep the prerendered form inert until the app runs; src/entry-server.tsx renders the screens under Node

import { useSyncExternalStore } from 'react'

/*
 * The public pages are rendered to HTML at build time (scripts/prerender.mjs) and then replaced,
 * not hydrated, when the app mounts with createRoot (main.tsx). Anything a visitor does to the
 * prerendered markup before that is lost, so a screen can use this to leave controls inert in the
 * HTML only. React reads the server snapshot during a server render and the client snapshot in
 * the browser, so no browser global is touched and nothing re-renders.
 */

/** Nothing to subscribe to: whether this is a server render never changes while a page is open. */
function subscribe(): () => void {
  return () => undefined
}

/** In the browser: not a server render. */
function clientSnapshot(): boolean {
  return false
}

/** During renderToString at build time: a server render. */
function serverSnapshot(): boolean {
  return true
}

// @find: useServerRender hook, is prerendering
/** True while the component renders to HTML on the server (the build-time prerender), false in the browser. */
export function useServerRender(): boolean {
  return useSyncExternalStore(subscribe, clientSnapshot, serverSnapshot)
}
