// @find: browser screenshot, web browser screenshot in trace, page screenshot, live view, current page, shotId, GET /api/browser/shots/{id}, full size screenshot dialog
// @what: Shows the screenshot the web browser took after a step, and the browser-window preview of every page a run visited, live while it works.
// @flow: Used by TraceStep, RunTraceCompact and the Chat ProgressCard; reads the run steps the trace already polls.
import { useEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'
import { Dialog } from '../ui'
import { fetchAudio } from '../../lib/voice'
import { useBrowserLive, useRunSteps } from '../../lib/queries'
import { browserShots, personHasBrowser, usesBrowser } from './traceModel'
import type { BrowserShotRef } from './traceModel'

/**
 * The picture behind GET /api/browser/shots/{id}. The endpoint needs the viewer's bearer token,
 * which an <img src> cannot send, so the bytes are fetched and shown from an object URL. Nothing is
 * fetched until the picture scrolls near the screen, and the URL is revoked when it is replaced.
 */
function useShotImage(shotId: string, element: RefObject<HTMLElement | null>) {
  const [src, setSrc] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  // Without IntersectionObserver (older browsers, tests) the picture is fetched straight away.
  const [visible, setVisible] = useState(() => typeof IntersectionObserver === 'undefined')

  useEffect(() => {
    const node = element.current
    if (visible || !node) return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setVisible(true)
          observer.disconnect()
        }
      },
      { rootMargin: '200px' },
    )
    observer.observe(node)
    return () => observer.disconnect()
  }, [element, visible])

  useEffect(() => {
    if (!visible) return
    let cancelled = false
    let objectUrl: string | null = null
    fetchAudio(`/api/browser/shots/${shotId}`, { method: 'GET' })
      .then((blob) => {
        if (cancelled) return
        objectUrl = URL.createObjectURL(blob)
        setSrc(objectUrl)
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })
    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [shotId, visible])

  return { src, failed }
}

/** Alternative text for a screenshot: "Screenshot of" the page title, else its address, else "the page". */
function altText(shot: BrowserShotRef): string {
  return `Screenshot of ${shot.title || shot.url || 'the page'}`
}

// @find: BrowserShot, browser screenshot, screenshot in run trace, open full size
/** One step's screenshot: the page address above it, and the full-size picture a click away. */
export function BrowserShot({ shot, compact = false }: { shot: BrowserShotRef; compact?: boolean }) {
  const frame = useRef<HTMLDivElement>(null)
  const { src, failed } = useShotImage(shot.shotId, frame)
  const [open, setOpen] = useState(false)

  return (
    <div ref={frame} className={`browser-shot${compact ? ' browser-shot-compact' : ''}`}>
      {shot.url && (
        <p className="browser-shot-url caption muted" title={shot.url}>
          {shot.url}
        </p>
      )}
      {failed ? (
        <p className="caption muted">The screenshot could not be loaded.</p>
      ) : src ? (
        <button
          type="button"
          className="browser-shot-button"
          onClick={() => setOpen(true)}
          aria-label={`${altText(shot)}, open full size`}
        >
          <img className="browser-shot-image" src={src} alt={altText(shot)} loading="lazy" decoding="async" />
        </button>
      ) : (
        <div className="browser-shot-placeholder caption muted">Loading the screenshot…</div>
      )}
      {src && (
        <Dialog
          open={open}
          onClose={() => setOpen(false)}
          eyebrow="Web browser"
          title={shot.title || 'Screenshot'}
          description={shot.url ?? undefined}
        >
          <img className="browser-shot-full" src={src} alt={altText(shot)} />
        </Dialog>
      )}
    </div>
  )
}

// @find: BrowserPreview, LiveView, live view, browser preview, browser window, pages visited, filmstrip, latest screenshot
/**
 * What the run's web browser is doing, drawn as a small browser window: an address bar, a status
 * line ("Opening youtube.com…", "Deciding what to do next…"), the page, and a strip of every page
 * the run visited.
 *
 * While the run works and has turned to the browser (usesBrowser), the live view
 * (useBrowserLive, GET /api/runs/{id}/browser/live) is asked for every second and its frame is
 * shown, so the page appears as it loads rather than only after each step. Picking a page in the
 * strip pins it until it is picked again. Once the run has finished, the stored screenshots of each
 * step are shown instead (a click opens one full size). Renders nothing for a run that never used
 * the browser. Used by the Chat progress card, under an answer, in the compact trace and on the
 * Agent desk.
 *
 * While a person holds the browser the status line says the agent is waiting for them, not
 * "Deciding what to do next" (the Agent desk showed both at once, 13 Oct 2026). Whether a person
 * holds it comes from personInControl when the caller knows the takeover state (the Agent desk),
 * else from the steps (personHasBrowser).
 */
export function BrowserPreview({
  runId,
  active,
  personInControl,
}: {
  runId: string
  active: boolean
  /** Whether a person holds the browser now; left out, the steps decide. */
  personInControl?: boolean | undefined
}) {
  const steps = useRunSteps(runId, { active }).data
  const shots = browserShots(steps)
  // The live view is asked for only while the run works and only once it has turned to the browser.
  const watching = active && usesBrowser(steps)
  const live = useBrowserLive(runId, watching).data ?? null
  const [pinned, setPinned] = useState<string | null>(null)
  const [open, setOpen] = useState(false)
  const liveFrame = watching && live && live.frame ? live : null
  if (shots.length === 0 && !liveFrame && !(watching && live)) return null
  const latest = shots.length > 0 ? shots[shots.length - 1]! : null
  const pinnedShot = shots.find((shot) => shot.shotId === pinned) ?? null
  // While the run works the live frame is shown, unless an earlier page was picked in the strip.
  const showingLive = Boolean(liveFrame) && !pinnedShot
  const shown = pinnedShot ?? latest
  const address = showingLive ? live!.url : (shown?.url ?? null)
  const position = shown ? shots.findIndex((shot) => shot.shotId === shown.shotId) + 1 : 0
  const held = personInControl ?? personHasBrowser(steps)
  const status = watching
    ? held
      ? 'Waiting for a person to hand back the web browser.'
      : live?.busy
        ? `${live.action}…`
        : 'Deciding what to do next…'
    : null

  return (
    <figure className="browser-preview" aria-label="Web browser preview" data-live={showingLive || undefined}>
      <div className="browser-preview-bar">
        <span className="browser-preview-dots" aria-hidden="true">
          <span />
          <span />
          <span />
        </span>
        <p className="browser-preview-address" title={address ?? undefined}>
          <span className="browser-preview-lock" aria-hidden="true">
            <svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8">
              <rect x="3" y="7" width="10" height="7" rx="1.5" />
              <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
            </svg>
          </span>
          <span className="browser-preview-host">{displayAddress(address)}</span>
        </p>
        {showingLive ? (
          <span className="browser-preview-live">
            <span className="chat-pulse-dot" aria-hidden="true" />
            Live
          </span>
        ) : shown ? (
          <span className="browser-preview-count caption">
            Page {position} of {shots.length}
          </span>
        ) : null}
      </div>
      {status && (
        <p className="browser-preview-status caption" aria-live="polite">
          {status}
        </p>
      )}
      {showingLive ? (
        <LivePage frame={live!.frame} title={live!.title || live!.url} busy={live!.busy} />
      ) : shown ? (
        <PreviewPage key={shown.shotId} shot={shown} onOpen={() => setOpen(true)} open={open} onClose={() => setOpen(false)} />
      ) : (
        <div className="browser-preview-page">
          <p className="browser-preview-empty caption muted">Opening the page…</p>
        </div>
      )}
      {shots.length > 1 && (
        <div className="browser-preview-strip" role="group" aria-label="Pages visited">
          {shots.map((shot, index) => (
            <Thumbnail
              key={shot.shotId}
              shot={shot}
              index={index + 1}
              selected={!showingLive && shot.shotId === shown?.shotId}
              onSelect={() => setPinned(shot.shotId === pinned || shot.shotId === latest?.shotId ? null : shot.shotId)}
            />
          ))}
        </div>
      )}
      <figcaption className="visually-hidden">
        {showingLive ? 'Live view of the page the agent has open: ' : 'Last page the agent saw: '}
        {(showingLive ? live!.title || live!.url : shown?.title || shown?.url) || 'the page'}
      </figcaption>
    </figure>
  )
}

/**
 * The live frame of the page the agent has open, swapped in place each second without a flash:
 * the new picture is decoded before it replaces the old one. Dimmed slightly while a step is
 * still loading, so it reads as "in progress".
 */
function LivePage({ frame, title, busy }: { frame: string; title: string; busy: boolean }) {
  const [shown, setShown] = useState(frame)
  useEffect(() => {
    if (frame === shown) return
    let cancelled = false
    const next = new Image()
    next.src = `data:image/jpeg;base64,${frame}`
    next
      .decode()
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setShown(frame)
      })
    return () => {
      cancelled = true
    }
  }, [frame, shown])
  return (
    <div className="browser-preview-page" data-busy={busy || undefined}>
      <img className="browser-preview-image" src={`data:image/jpeg;base64,${shown}`} alt={`Live view of ${title || 'the page'}`} />
    </div>
  )
}

/** Address-bar text for a page: host, path (the bare root is omitted) and query; "Opening a page" without a URL; the raw text if it does not parse as a URL. */
function displayAddress(url: string | null): string {
  if (!url) return 'Opening a page'
  try {
    const parsed = new URL(url)
    const path = parsed.pathname === '/' ? '' : parsed.pathname
    return `${parsed.host}${path}${parsed.search}`
  } catch {
    return url
  }
}

/** The page area of the preview: the screenshot as a button that opens the full-size Dialog, with loading and failure text. It fetches the image with useShotImage. */
function PreviewPage({
  shot,
  onOpen,
  open,
  onClose,
}: {
  shot: BrowserShotRef
  onOpen: () => void
  open: boolean
  onClose: () => void
}) {
  const frame = useRef<HTMLDivElement>(null)
  const { src, failed } = useShotImage(shot.shotId, frame)
  return (
    <div ref={frame} className="browser-preview-page">
      {failed ? (
        <p className="browser-preview-empty caption muted">The screenshot could not be loaded.</p>
      ) : src ? (
        <button type="button" className="browser-preview-open" onClick={onOpen} aria-label={`${altText(shot)}, open full size`}>
          <img className="browser-preview-image" src={src} alt={altText(shot)} decoding="async" />
        </button>
      ) : (
        <p className="browser-preview-empty caption muted">Loading the page…</p>
      )}
      {src && (
        <Dialog open={open} onClose={onClose} eyebrow="Web browser" title={shot.title || 'Screenshot'} description={shot.url ?? undefined}>
          <img className="browser-shot-full" src={src} alt={altText(shot)} />
        </Dialog>
      )}
    </div>
  )
}

/** One thumbnail in the pages-visited strip, a toggle button (aria-pressed) named by the page number and title. Its image loads lazily with useShotImage. */
function Thumbnail({
  shot,
  index,
  selected,
  onSelect,
}: {
  shot: BrowserShotRef
  index: number
  selected: boolean
  onSelect: () => void
}) {
  const frame = useRef<HTMLButtonElement>(null)
  const { src } = useShotImage(shot.shotId, frame)
  return (
    <button
      ref={frame}
      type="button"
      className="browser-preview-thumb"
      aria-pressed={selected}
      aria-label={`Page ${index}: ${shot.title || shot.url || 'page'}`}
      title={shot.title || shot.url || undefined}
      onClick={onSelect}
    >
      {src ? <img src={src} alt="" /> : <span aria-hidden="true">{index}</span>}
    </button>
  )
}
