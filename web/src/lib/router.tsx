import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
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

export type NavigateOptions = {
  /** Replace the current history entry instead of adding one, for state such as list filters. */
  replace?: boolean
  /** Scroll to the top (or to the #fragment) afterwards. Defaults to true, or false with replace. */
  scroll?: boolean
}

type RouterValue = {
  path: string
  search: URLSearchParams
  /** The #fragment, including the '#', or an empty string. */
  hash: string
  navigate: (to: string, options?: NavigateOptions) => void
}

const RouterContext = createContext<RouterValue | null>(null)

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

export function RouterProvider({ children }: { children: ReactNode }) {
  const [location, setLocation] = useState(readLocation)

  const navigate = useCallback((to: string, options: NavigateOptions = {}) => {
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
    const onPop = () => setLocation(readLocation())
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

export function useRouter(): RouterValue {
  const value = useContext(RouterContext)
  if (!value) throw new Error('useRouter must be used inside RouterProvider')
  return value
}

const APP_NAME = 'AI Workforce OS'

/**
 * Names the browser tab after what the screen shows ("Maya · AI Workforce OS"), so tabs and
 * history entries can be told apart. Does nothing until the title is known; the shell's
 * route-level title stands until then.
 */
export function useDocumentTitle(title?: string | null) {
  useEffect(() => {
    if (title) document.title = `${title} · ${APP_NAME}`
  }, [title])
}

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
