// @find: router, routes, navigate, navigation, route params, search params, page title, tab title, attention count, redirects, old address, useRouter, RouterProvider, setPageTitle, setAttentionCount, useDocumentTitle, redirectFor, match, links, history, Back button, scroll to hash, URL, leave guard, unsaved changes, useLeaveGuard, leaveWhenAllowed
// @what: The console's small in-house router: current path and query, navigation without reload, route parameters, tab title with attention count, and old-address redirects.
// @flow: Used by App.tsx, main.tsx, useListFilter, attention and nearly every screen through useRouter.
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'

/*
 * A small router.
 *
 * Deliberately small: the application has a fixed set of screens, and what it needs is a current
 * path, a way to move between paths without a full reload, and route parameters for the detail
 * screens. Anything an <a href="/..."> points at is intercepted, so links stay real links - they
 * can be opened in a new tab, copied and bookmarked - rather than click handlers pretending to be
 * links.
 */

/** Options for navigate: replace the history entry, and whether to scroll afterwards. */
export type NavigateOptions = {
  /** Replace the current history entry instead of adding one, for state such as list filters. */
  replace?: boolean
  /** Scroll to the top (or to the #fragment) afterwards. Defaults to true, or false with replace. */
  scroll?: boolean
  /** Leave even though a screen has asked to be asked first (its own Save, or a confirmed Leave). */
  force?: boolean
}

/*
 * A screen with unsaved work can ask to be asked before the person leaves it: every way of
 * leaving - a link, navigate(), Back and Forward, signing out - goes through the guard, which
 * shows its own question and calls `proceed` if the person chooses to leave. One screen holds the
 * guard at a time; a move within the same path (a filter, a query flag) never asks.
 */

/** Asks before leaving; calls `proceed` to leave after all. */
export type LeaveGuard = (proceed: () => void) => void

let leaveGuard: LeaveGuard | null = null

// @find: leave guard, ask before leaving, unsaved changes, block navigation
/** Registers a leave guard while `guard` is set; the latest registered screen wins, and unmounting removes it. */
export function useLeaveGuard(guard: LeaveGuard | null) {
  useEffect(() => {
    if (!guard) return
    leaveGuard = guard
    return () => {
      if (leaveGuard === guard) leaveGuard = null
    }
  }, [guard])
}

// @find: leave when allowed, sign out with unsaved changes
/** Runs an action that leaves the screen (such as signing out) at once, or after the guard's question when a screen holds one. */
export function leaveWhenAllowed(action: () => void) {
  const guard = leaveGuard
  if (guard) guard(action)
  else action()
}

/** The path part of an address, resolved against the current location. */
function pathOf(to: string): string {
  try {
    return new URL(to, window.location.href).pathname
  } catch {
    return to.split(/[?#]/)[0] ?? to
  }
}

type RouterValue = {
  path: string
  search: URLSearchParams
  /** The #fragment, including the '#', or an empty string. */
  hash: string
  navigate: (to: string, options?: NavigateOptions) => void
}

const RouterContext = createContext<RouterValue | null>(null)

/** Current path, query string and fragment from the browser's location. */
const readLocation = () => ({
  path: window.location.pathname,
  search: window.location.search,
  hash: window.location.hash,
})

/**
 * Brings the #fragment's element into view, after moving to the top of the page unless
 * `fromTop` is false. The target usually renders only after its data arrives, so it is looked
 * for over a short while rather than once.
 */
function scrollToTarget(hash: string, fromTop = true) {
  if (fromTop) window.scrollTo({ top: 0 })
  if (!hash || hash === '#') return
  let id: string
  try {
    id = decodeURIComponent(hash.slice(1))
  } catch {
    return
  }
  let attempts = 0
  const look = () => {
    const target = document.getElementById(id)
    if (target) {
      target.scrollIntoView?.({ block: 'start' })
      return
    }
    attempts += 1
    if (attempts < 20) window.setTimeout(look, 100)
  }
  window.requestAnimationFrame(look)
}

// @find: router provider, app routing, history listener
// @find: router provider, navigation, history, link interception; wraps the whole app (main.tsx)
/**
 * Provides the current path, query string, #fragment and navigate() to the whole app (mounted once in main.tsx).
 * Flow: location state is read from window.location. navigate(to, { replace, scroll, force }) pushes or replaces a history entry, updates state, and scrolls to the top or to the #fragment (replaced entries keep their position unless scroll is true); while a screen holds a leave guard (useLeaveGuard), a move to another path asks it first unless force is set. Back and Forward arrive through popstate; with a leave guard held, the address is put back and the guard asked before moving on. A document-wide click listener turns plain left-clicks on same-origin links that start with "/" into navigate() calls, leaving alone modified clicks, target _blank, downloads and links that start with "//". A deep link with a #fragment on first load is scrolled to once it renders.
 */
export function RouterProvider({ children }: { children: ReactNode }) {
  const [location, setLocation] = useState(readLocation)
  // Where the screen is, for undoing a Back the leave guard holds up.
  const shown = useRef(location)
  useEffect(() => {
    shown.current = location
  }, [location])

  const navigate = useCallback(function go(to: string, options: NavigateOptions = {}) {
    const guard = leaveGuard
    if (!options.force && guard && pathOf(to) !== window.location.pathname) {
      guard(() => go(to, { ...options, force: true }))
      return
    }
    const replace = options.replace ?? false
    if (replace) window.history.replaceState(window.history.state, '', to)
    else window.history.pushState({}, '', to)
    setLocation(readLocation())
    // A new screen starts at the top. Keeping the old scroll position lands a person half way
    // down a page they have never seen. A replaced entry is the same screen with new state (a
    // filter, a search), so it keeps its place unless asked otherwise.
    if (options.scroll ?? !replace) scrollToTarget(window.location.hash)
  }, [])

  useEffect(() => {
    const onPop = () => {
      const next = readLocation()
      const guard = leaveGuard
      const here = shown.current
      if (guard && next.path !== here.path) {
        // Back or Forward has already moved the address; put it back while the guard asks.
        const target = `${next.path}${next.search}${next.hash}`
        window.history.pushState({}, '', `${here.path}${here.search}${here.hash}`)
        guard(() => {
          window.history.pushState({}, '', target)
          setLocation(readLocation())
        })
        return
      }
      setLocation(next)
    }
    window.addEventListener('popstate', onPop)

    const onClick = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0) return
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
      const anchor = (event.target as HTMLElement).closest('a')
      if (!anchor || anchor.target === '_blank' || anchor.hasAttribute('download')) return
      const href = anchor.getAttribute('href')
      if (!href || !href.startsWith('/') || href.startsWith('//')) return
      event.preventDefault()
      navigate(href)
    }
    document.addEventListener('click', onClick)

    // A deep link with a fragment (/approvals#approval-...) lands on its item once it renders.
    // The browser keeps its own scroll position on first load, so there is no jump to the top.
    if (window.location.hash) scrollToTarget(window.location.hash, false)

    return () => {
      window.removeEventListener('popstate', onPop)
      document.removeEventListener('click', onClick)
    }
  }, [navigate])

  // The same URLSearchParams object until the query string changes, so screens can depend on it.
  const search = useMemo(() => new URLSearchParams(location.search), [location.search])
  const value = useMemo(
    () => ({ path: location.path, search, hash: location.hash, navigate }),
    [location.path, search, location.hash, navigate],
  )

  return <RouterContext.Provider value={value}>{children}</RouterContext.Provider>
}

/** Moves nowhere: a page rendered ahead of time has no history to change. */
function stayPut(): void {
  // The browser's own RouterProvider takes over once the page loads.
}

// @find: static router provider, server rendering, prerender, fixed path, entry-server
/**
 * Provides a fixed path, with an empty query and fragment, to a screen rendered ahead of time
 * (src/entry-server.tsx). It reads no browser global, so it works under Node, and its navigate()
 * does nothing. The browser always uses RouterProvider.
 */
export function StaticRouterProvider({ path, children }: { path: string; children: ReactNode }) {
  const value = useMemo<RouterValue>(() => ({ path, search: new URLSearchParams(), hash: '', navigate: stayPut }), [path])
  return <RouterContext.Provider value={value}>{children}</RouterContext.Provider>
}

// @find: use router, navigate, current path, query string
// @find: use router, current path, navigate, search params; used by: every page
/**
 * Returns the router value { path, search, hash, navigate }. search is the same URLSearchParams object until the query string changes. Throws when used outside RouterProvider.
 */
export function useRouter(): RouterValue {
  const value = useContext(RouterContext)
  if (!value) throw new Error('useRouter must be used inside RouterProvider')
  return value
}

const APP_NAME = 'AI Workforce OS'

/*
 * The tab title has two parts that change independently: what the screen is called, and how many
 * things are waiting on this person ("(2) Approvals · AI Workforce OS"). Either can change first
 * - a detail screen names itself once its record arrives, the count changes whenever the poll
 * answers - so both are kept here and the title is written from the pair, rather than one writer
 * overwriting what the other wrote.
 */
let pageTitle = APP_NAME
let waitingCount = 0

/** A leading "(3) " a screen added itself: the count is ours to write, once. */
const LEADING_COUNT = /^\(\d+\)\s+/

/** Writes document.title: the page title, prefixed with "(n)" when n items are waiting on the person. */
function writeTitle() {
  document.title = waitingCount > 0 ? `(${waitingCount}) ${pageTitle}` : pageTitle
}

// @find: set page title, browser tab title
// @find: set tab title, page title
/** Sets the screen's part of the tab title, as the shell does on every route. */
export function setPageTitle(title: string): void {
  pageTitle = title.replace(LEADING_COUNT, '')
  writeTitle()
}

// @find: tab title count, things waiting on this person; used by: attention.ts
/** Sets how many things are waiting on this person; 0 shows none. Every route shows it. */
export function setAttentionCount(count: number): void {
  const next = Math.max(0, Math.floor(count))
  if (next === waitingCount) return
  waitingCount = next
  writeTitle()
}

// @find: use document title, page title per screen
// @find: name the browser tab after the screen; used by: Agent detail page, Orchestrator page
/**
 * Names the browser tab after what the screen shows ("Maya · AI Workforce OS"), so tabs and
 * history entries can be told apart. Does nothing until the title is known; the shell's
 * route-level title stands until then.
 */
export function useDocumentTitle(title?: string | null) {
  useEffect(() => {
    if (title) setPageTitle(`${title} · ${APP_NAME}`)
  }, [title])
}

/**
 * Addresses that moved, old to new, so a bookmark or a link pasted into a message before the move
 * still lands on the screen it meant. Integrations became Connectors on 3 October 2026.
 */
const REDIRECTS: Readonly<Record<string, string>> = {
  '/integrations': '/connectors',
}

// @find: redirect for, old path redirects
// @find: old address redirect, integrations to connectors; used by: App.tsx
/** Where an old address now lives, or null for an address that has not moved. A trailing slash is ignored. */
export function redirectFor(path: string): string | null {
  const trimmed = path.length > 1 ? path.replace(/\/+$/, '') : path
  return REDIRECTS[trimmed] ?? null
}

// @find: match route pattern, route parameters like /agents/:id; used by: App.tsx
/** Matches "/agents/:id" against a path, returning the parameters or null. */
export function match(pattern: string, path: string): Record<string, string> | null {
  const patternParts = pattern.split('/').filter(Boolean)
  const pathParts = path.split('/').filter(Boolean)
  if (patternParts.length !== pathParts.length) return null
  const params: Record<string, string> = {}
  for (let i = 0; i < patternParts.length; i++) {
    const expected = patternParts[i]!
    let actual: string
    try {
      actual = decodeURIComponent(pathParts[i]!)
    } catch {
      // A malformed escape in a typed or pasted URL is a path that matches nothing, not a crash.
      return null
    }
    if (expected.startsWith(':')) params[expected.slice(1)] = actual
    else if (expected !== actual) return null
  }
  return params
}
