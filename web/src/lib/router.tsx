import { createContext, useCallback, useContext, useEffect, useState } from 'react'
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

type RouterValue = { path: string; search: URLSearchParams; navigate: (to: string) => void }

const RouterContext = createContext<RouterValue | null>(null)

export function RouterProvider({ children }: { children: ReactNode }) {
  const [location, setLocation] = useState(() => ({
    path: window.location.pathname,
    search: window.location.search,
  }))

  const navigate = useCallback((to: string) => {
    window.history.pushState({}, '', to)
    setLocation({ path: window.location.pathname, search: window.location.search })
    // A new screen starts at the top. Keeping the old scroll position lands a person half way
    // down a page they have never seen.
    window.scrollTo({ top: 0 })
  }, [])

  useEffect(() => {
    const onPop = () => setLocation({ path: window.location.pathname, search: window.location.search })
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
    return () => {
      window.removeEventListener('popstate', onPop)
      document.removeEventListener('click', onClick)
    }
  }, [navigate])

  return (
    <RouterContext.Provider
      value={{ path: location.path, search: new URLSearchParams(location.search), navigate }}
    >
      {children}
    </RouterContext.Provider>
  )
}

export function useRouter(): RouterValue {
  const value = useContext(RouterContext)
  if (!value) throw new Error('useRouter must be used inside RouterProvider')
  return value
}

/** Matches "/agents/:id" against a path, returning the parameters or null. */
export function match(pattern: string, path: string): Record<string, string> | null {
  const patternParts = pattern.split('/').filter(Boolean)
  const pathParts = path.split('/').filter(Boolean)
  if (patternParts.length !== pathParts.length) return null
  const params: Record<string, string> = {}
  for (let i = 0; i < patternParts.length; i++) {
    const expected = patternParts[i]!
    const actual = decodeURIComponent(pathParts[i]!)
    if (expected.startsWith(':')) params[expected.slice(1)] = actual
    else if (expected !== actual) return null
  }
  return params
}
