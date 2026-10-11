// @find: browser screenshot, web browser screenshot in trace, page screenshot, live view, current page, shotId, GET /api/browser/shots/{id}, full size screenshot dialog
// @what: Shows the screenshot the web browser took after a step, and the browser-window preview of every page a run visited, live while it works.
// @flow: Used by TraceStep, RunTraceCompact and the Chat ProgressCard; reads the run steps the trace already polls.
import { useEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'
import { Dialog } from '../ui'
import { fetchAudio } from '../../lib/voice'
import { useRunSteps } from '../../lib/queries'
import { browserShots } from './traceModel'
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
 * What the run's web browser saw, drawn as a small browser window: an address bar with the page
 * being shown, the page itself (a click opens it full size), and a strip of every page the run
 * visited. While the run is working it follows the newest page as steps arrive (the steps query
 * already polls); choosing a page in the strip pins it until "Latest" is chosen. Shows nothing until
 * the run has used the browser.
 */
export function BrowserPreview({ runId, active }: { runId: string; active: boolean }) {
  const steps = useRunSteps(runId, { active }).data
  const shots = browserShots(steps)
  const [pinned, setPinned] = useState<string | null>(null)
  const [open, setOpen] = useState(false)
  if (shots.length === 0) return null
  const latest = shots[shots.length - 1]!
  const shown = shots.find((shot) => shot.shotId === pinned) ?? latest
  const following = shown.shotId === latest.shotId
  const position = shots.findIndex((shot) => shot.shotId === shown.shotId) + 1

  return (
    <figure className="browser-preview" aria-label="Web browser preview">
      <div className="browser-preview-bar">
        <span className="browser-preview-dots" aria-hidden="true">
          <span />
          <span />
          <span />
        </span>
        <p className="browser-preview-address" title={shown.url ?? undefined}>
          <span className="browser-preview-lock" aria-hidden="true">
            <svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8">
              <rect x="3" y="7" width="10" height="7" rx="1.5" />
              <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
            </svg>
          </span>
          <span className="browser-preview-host">{displayAddress(shown.url)}</span>
        </p>
        {active && following ? (
          <span className="browser-preview-live">
            <span className="chat-pulse-dot" aria-hidden="true" />
            Live
          </span>
        ) : (
          <span className="browser-preview-count caption">
            Page {position} of {shots.length}
          </span>
        )}
      </div>
      <PreviewPage key={shown.shotId} shot={shown} onOpen={() => setOpen(true)} open={open} onClose={() => setOpen(false)} />
      {shots.length > 1 && (
        <div className="browser-preview-strip" role="group" aria-label="Pages visited">
          {shots.map((shot, index) => (
            <Thumbnail
              key={shot.shotId}
              shot={shot}
              index={index + 1}
              selected={shot.shotId === shown.shotId}
              onSelect={() => setPinned(shot.shotId === latest.shotId ? null : shot.shotId)}
            />
          ))}
        </div>
      )}
      <figcaption className="visually-hidden">
        {active ? 'Live view of the page the agent has open: ' : 'Last page the agent saw: '}
        {shown.title || shown.url || 'the page'}
      </figcaption>
    </figure>
  )
}

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
