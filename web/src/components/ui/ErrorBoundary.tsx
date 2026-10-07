import { Component, useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { QueryErrorResetBoundary } from '@tanstack/react-query'
import { ApiError } from '../../lib/api'
import { useRouter } from '../../lib/router'
import { isSignedIn } from '../../lib/session'
import { Button, Eyebrow } from './index'

/*
 * What a person sees when a screen fails while drawing, instead of a blank page.
 *
 * Without a boundary, React unmounts the whole application on a render error, navigation bar
 * included, and leaves a white page with no message and no way out. The shell wraps each screen
 * in one of these inside its main landmark, so the navigation bar survives and moving to another
 * screen starts afresh; the public pages and the application as a whole have one each as well.
 *
 * A screen whose code cannot be fetched (its chunk was replaced by a newer deploy while the tab
 * was open) gets one automatic reload, which picks up the new version. The reload is remembered
 * per build, so a chunk that is missing for some other reason cannot reload the page in a loop.
 */

/** How a failed dynamic import reads in Chromium, Safari, Firefox and Vite's CSS preloader. */
const CHUNK_FAILURE =
  /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module|Expected a JavaScript module|Unable to preload CSS/

/** Whether `error` is a screen's code failing to load, rather than a screen failing to draw. */
export function isChunkLoadError(error: unknown): boolean {
  if (!(error instanceof Error)) return false
  return error.name === 'ChunkLoadError' || CHUNK_FAILURE.test(error.message)
}

const RELOADED_FOR = 'aiwos.reloadedFor:'

/** Set once a chunk has failed in this page load, so the flag below is not cleared after it. */
let chunkFailedThisLoad = false

/**
 * The flag naming this build: its main script's address, which changes with every deploy. A
 * reload spent on one build never stops the next build recovering the same way.
 */
function reloadFlag(): string {
  const scripts = document.querySelectorAll<HTMLScriptElement>('script[type="module"][src]')
  const build = Array.from(scripts, (script) => script.getAttribute('src')).join(' ')
  return RELOADED_FOR + (build || 'unknown')
}

/**
 * Reloads the page, unless this build already had its one automatic reload in this tab. Returns
 * whether it did. Without readable storage there is no way to know, so it does not.
 */
export function reloadOnce(): boolean {
  chunkFailedThisLoad = true
  try {
    const flag = reloadFlag()
    if (sessionStorage.getItem(flag) !== null) return false
    sessionStorage.setItem(flag, String(Date.now()))
  } catch {
    return false
  }
  window.location.reload()
  return true
}

/**
 * Clears the reload flags once this page has run for `afterMs` without a chunk failing, which is
 * what a successful load means here: a later failure, after another deploy, gets its own reload.
 * The time matters - a reload lands on the screen that failed, so a load that fails again does so
 * within moments, while the flag still stands. Returns a cancel function.
 */
export function clearReloadFlagWhenSettled(afterMs = 10_000): () => void {
  const timer = window.setTimeout(() => {
    if (chunkFailedThisLoad) return
    try {
      for (let index = sessionStorage.length - 1; index >= 0; index--) {
        const key = sessionStorage.key(index)
        if (key?.startsWith(RELOADED_FOR)) sessionStorage.removeItem(key)
      }
    } catch {
      /* Storage that cannot be read holds no flags. */
    }
  }, afterMs)
  return () => window.clearTimeout(timer)
}

/**
 * Logs a render failure with the screen's path (never its query or fragment, which can carry an
 * invitation token or an address) and nothing a person typed or the server returned about them.
 * An ApiError is logged by status, code and request id only: its message may quote a record.
 */
export function logRenderError(kind: 'caught' | 'uncaught', error: unknown, componentStack?: string): void {
  const path = typeof window === 'undefined' ? '' : window.location.pathname
  const detail =
    error instanceof ApiError
      ? { status: error.status, code: error.code, requestId: error.requestId ?? null }
      : error instanceof Error
        ? error
        : { thrown: typeof error }
  console.error(`[aiwos] ${kind} render error on ${path}`, detail, componentStack ?? '')
}

/**
 * 'screen' sits inside the shell's main landmark. 'page' brings its own, for the public pages and
 * the last-resort boundary around the whole application.
 */
type Variant = 'screen' | 'page'

type BoundaryProps = {
  children: ReactNode
  variant: Variant
  /** A change starts the screen afresh: the route path, so going elsewhere clears the failure. */
  resetKey: string
  /** Lets failed queries fetch again when the screen starts afresh, rather than rethrow. */
  onReset: () => void
}

type BoundaryState = { failed: boolean; error: unknown }

class Boundary extends Component<BoundaryProps, BoundaryState> {
  state: BoundaryState = { failed: false, error: null }

  static getDerivedStateFromError(error: unknown): BoundaryState {
    return { failed: true, error }
  }

  // The failure itself is logged once, centrally, by the root's onCaughtError (main.tsx).
  componentDidCatch(error: unknown) {
    if (isChunkLoadError(error)) reloadOnce()
  }

  componentDidUpdate(previous: BoundaryProps) {
    if (this.state.failed && previous.resetKey !== this.props.resetKey) {
      this.props.onReset()
      this.setState({ failed: false, error: null })
    }
  }

  render() {
    if (!this.state.failed) return this.props.children
    return <Fallback error={this.state.error} variant={this.props.variant} />
  }
}

function Fallback({ error, variant }: { error: unknown; variant: Variant }) {
  const heading = useRef<HTMLHeadingElement>(null)
  const chunk = isChunkLoadError(error)
  const requestId = error instanceof ApiError ? error.requestId : undefined
  // A signed-out visitor has no Command Map: "/" is the public home page for them.
  const signedIn = isSignedIn()

  // The control that had focus went with the screen. Focus lands on what happened instead, so a
  // screen reader announces it and the next Tab reaches Reload. Focus that is still somewhere
  // real (the navigation bar, say) is left where it is.
  useEffect(() => {
    const active = document.activeElement
    if (!active || active === document.body) heading.current?.focus({ preventScroll: true })
  }, [])

  const body = (
    <div className="page">
      <Eyebrow>{chunk ? 'Update available' : 'Something went wrong'}</Eyebrow>
      <h1 className="page-title" tabIndex={-1} ref={heading}>
        {chunk ? 'A newer version is available' : 'This screen hit a problem.'}
      </h1>
      <p className="page-description">
        {chunk
          ? 'This screen could not be loaded, usually because the app was updated after this page was opened. Reload to get the latest version.'
          : 'Reload to try again, or carry on from another screen.'}
      </p>
      {requestId && <p className="caption muted">Reference: {requestId}</p>}
      <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', marginTop: 'var(--space-5)' }}>
        <Button onClick={() => window.location.reload()}>Reload</Button>
        <a className="button button-outline" href={signedIn ? '/' : '/home'}>
          {signedIn ? 'Go to Command Map' : 'Go to the home page'}
        </a>
      </div>
    </div>
  )

  if (variant === 'screen') return body
  return (
    <main id="main" tabIndex={-1}>
      {body}
    </main>
  )
}

/**
 * Catches a render failure in `children` and shows what happened, with a way to reload and a way
 * out. Starts afresh when the route changes. Needs the router, so it sits inside RouterProvider.
 */
export function ErrorBoundary({ children, variant = 'screen' }: { children: ReactNode; variant?: Variant }) {
  const { path } = useRouter()
  return (
    <QueryErrorResetBoundary>
      {({ reset }) => (
        <Boundary variant={variant} resetKey={path} onReset={reset}>
          {children}
        </Boundary>
      )}
    </QueryErrorResetBoundary>
  )
}
