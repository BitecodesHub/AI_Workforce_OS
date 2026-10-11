// @find: browser screenshot, web browser screenshot in trace, page screenshot, live view, current page, shotId, GET /api/browser/shots/{id}, full size screenshot dialog
// @what: Shows the screenshot the web browser took after a step, and the Live view of a running run's latest page.
// @flow: Used by TraceStep, RunTraceCompact and the Chat ProgressCard; reads the run steps the trace already polls.
import { useEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'
import { Dialog } from '../ui'
import { fetchAudio } from '../../lib/voice'
import { useRunSteps } from '../../lib/queries'
import { latestBrowserShot } from './traceModel'
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

// @find: LiveView, live view, browser live view, current page of running run, latest screenshot
/**
 * The page the run's browser has open now: its newest screenshot and address, replaced as new
 * steps arrive (the steps query already polls while the run is active). Shows nothing until the
 * run has used the browser.
 */
export function LiveView({ runId, active }: { runId: string; active: boolean }) {
  const steps = useRunSteps(runId, { active }).data
  const shot = latestBrowserShot(steps)
  if (!shot) return null
  return (
    <div className="browser-live">
      <p className="browser-live-label caption">
        {active && <span className="chat-pulse-dot" aria-hidden="true" />}
        {active ? 'Live view' : 'Last page seen'}
      </p>
      <BrowserShot key={shot.shotId} shot={shot} compact />
    </div>
  )
}
