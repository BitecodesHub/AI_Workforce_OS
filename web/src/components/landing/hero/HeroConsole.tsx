// @find: hero console, simulated console, command map, routing trace, activity table, approval layer, pause motion, glass layers, HeroConsole
// @what: The hero figure: a simulated replica of the console with command map, routing trace and a pending approval.
// @flow: Used by Hero; frames from heroConsoleModel; parallax from usePointerParallax
import { useCallback, useEffect, useState } from 'react'
import type { ReactElement } from 'react'
import { Eyebrow, Tag } from '../../ui'
import type { TagTone } from '../../ui'
import { useInView } from '../../../hooks/useInView'
import { Icon } from '../shared/Icon'
import { useLandingMotion } from '../shared/LandingRoot'
import { useInPageLink } from '../shared/useInPageLink'
import { COMPOSED_ELAPSED_MS, heroFrame } from './heroConsoleModel'
import type { HeroFrame, HeroRow } from './heroConsoleModel'
import { usePointerParallax } from './usePointerParallax'

/*
 * The hero figure: a simulated replica of the console at work, built from three glass layers.
 *
 * At the back is the Command Map with its live activity table. In front of it float the routing
 * trace for a sample run and, once the HR run reaches gmail.send_message, the approval it is
 * waiting on. Everything is driven by heroFrame(elapsed), a pure function, so the figure can be
 * frozen at any moment: reduced motion shows the composed frame at five seconds, and the "Pause
 * motion" control simply stops the clock.
 *
 * The clock only runs while motion is on, the page is not paused, at least 30% of the figure is
 * on screen and the tab is visible. The back and trace layers are decorative and hidden from
 * assistive technology; a visually hidden sentence says what the figure shows, and the approval
 * layer, the one part with a link, is exposed only once it has arrived.
 */

const TICK_MS = 250

/** The three trace slots, always rendered so the layer keeps its height between loops. */
const TRACE_SLOTS = heroFrame(COMPOSED_ELAPSED_MS).trace

const STAT_CELLS: ReadonlyArray<{ key: keyof HeroFrame['stats']; label: string }> = [
  { key: 'total', label: 'Runs total' },
  { key: 'running', label: 'Running' },
  { key: 'waiting', label: 'Waiting for approval' },
  { key: 'completed', label: 'Completed' },
]

/* The trace in plain words: what happened to each provider, not the router's own terms. */
const TRACE_VERB: Record<HeroFrame['trace'][number]['verb'], string> = {
  skip: 'Skipped',
  fail: 'Busy',
  ok: 'Answered',
}

const TRACE_DETAIL: Record<HeroFrame['trace'][number]['verb'], string> = {
  skip: 'Not responding, so it moved on',
  fail: 'Too busy, so it tried the next one',
  ok: 'Took over and replied',
}

const TRACE_PROVIDER: Record<string, string> = {
  groq: 'Groq',
  openrouter: 'OpenRouter',
  gemini: 'Google Gemini',
}

const STATUS_TAG: Record<HeroRow['status'], { tone: TagTone; label: string }> = {
  running: { tone: 'blue', label: 'Running' },
  waiting: { tone: 'warning', label: 'Waiting' },
  completed: { tone: 'success', label: 'Completed' },
}

/** The product's L-mark, drawn as Brand draws it. */
function ConsoleMark(): ReactElement {
  return (
    <svg
      className="lp-console-mark"
      width="12"
      height="15"
      viewBox="0 0 20 25"
      fill="none"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M3 0.5 V21.5 H14" stroke="var(--blue)" strokeWidth="6" strokeLinecap="square" strokeLinejoin="miter" />
      <rect x="12" y="0.5" width="8" height="8" rx="1" fill="var(--blue)" />
    </svg>
  )
}

function StatusCell({ status }: { status: HeroRow['status'] }): ReactElement {
  const { tone, label } = STATUS_TAG[status]
  return (
    // Keyed on the status so a change remounts it and it ticks into place.
    <span key={status} className="lp-console-tick lp-anim-tick">
      <Tag tone={tone}>
        {status === 'running' && <span className="lp-dot lp-dot-blue lp-pulse" aria-hidden="true" />}
        {label}
      </Tag>
    </span>
  )
}

function BackLayer({ frame }: { frame: HeroFrame }): ReactElement {
  return (
    <div className="lp-console-layer lp-console-back lp-glass" aria-hidden="true">
      <div className="lp-console-chrome">
        <div className="lp-console-brand">
          <ConsoleMark />
          <span className="lp-console-nav">
            <span className="lp-console-pill" data-active="true">
              Command Map
            </span>
            <span className="lp-console-pill">Agents</span>
            <span className="lp-console-pill">
              Approvals
              {frame.approvalsBadge === 1 && <span className="lp-console-badge lp-anim-tick">1</span>}
            </span>
          </span>
        </div>
        <span className="lp-console-model">
          <span className="lp-dot lp-dot-green" />
          sandbox model
        </span>
      </div>

      <div className="lp-console-head">
        <Eyebrow>Your workforce, in focus</Eyebrow>
        <p className="lp-console-title">Command Map</p>
      </div>

      <dl className="lp-console-stats">
        {STAT_CELLS.map((cell) => {
          const value = frame.stats[cell.key]
          return (
            <div key={cell.key} className="lp-console-stat">
              <dt>{cell.label}</dt>
              <dd>
                <span key={value} className="lp-console-tick lp-anim-tick">
                  {value}
                </span>
              </dd>
            </div>
          )
        })}
      </dl>

      <div className="lp-console-activity">
        <p className="lp-console-subhead">Live activity</p>
        <table className="table lp-console-table">
          <thead>
            <tr>
              <th scope="col">Agent</th>
              <th scope="col">Goal</th>
              <th scope="col">Status</th>
              <th scope="col" className="table-numeric">
                Time
              </th>
            </tr>
          </thead>
          <tbody>
            {frame.rows.map((row) => (
              <tr key={row.id}>
                <td>
                  <Tag tone={row.category} withDot>
                    {row.agent}
                  </Tag>
                </td>
                <td>{row.goal}</td>
                <td>
                  <StatusCell status={row.status} />
                </td>
                <td className="table-numeric tabular">
                  <span key={row.seconds} className="lp-console-tick lp-anim-tick">
                    {row.seconds}
                  </span>
                  <span className="lp-console-unit">s</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function TraceLayer({ frame }: { frame: HeroFrame }): ReactElement {
  return (
    <div className="lp-console-layer lp-console-trace lp-glass" aria-hidden="true">
      <Eyebrow>Backup AI · switched on its own</Eyebrow>
      <ol className="lp-console-lines">
        {TRACE_SLOTS.map((slot) => {
          const line = frame.trace.find((entry) => entry.id === slot.id)
          const shown = line ?? slot
          return (
            <li
              // A new key when a line arrives, so it mounts and slides in.
              key={line ? `line-${slot.id}` : `slot-${slot.id}`}
              className={line ? 'lp-console-line lp-anim-slide' : 'lp-console-line'}
              data-state={line ? 'shown' : 'pending'}
              data-verb={shown.verb}
            >
              <span className="lp-console-verb">{TRACE_VERB[shown.verb]}</span>
              <span className="lp-console-provider">{TRACE_PROVIDER[shown.provider] ?? shown.provider}</span>
              <span className="lp-console-detail">{TRACE_DETAIL[shown.verb]}</span>
            </li>
          )
        })}
      </ol>
    </div>
  )
}

// @find: HeroConsole component, hero figure
export function HeroConsole(): ReactElement {
  const { reduced, ambientPaused, setAmbientPaused } = useLandingMotion()
  const inPage = useInPageLink()
  const [elapsed, setElapsed] = useState<number>(() => (reduced ? COMPOSED_ELAPSED_MS : 0))
  const { ref: viewRef, inView } = useInView<HTMLElement>({ threshold: 0.3, once: false })

  const live = !reduced && !ambientPaused && inView
  const { hostRef, stageRef } = usePointerParallax(live)

  useEffect(() => {
    if (!live) return undefined
    const timer = setInterval(() => {
      if (typeof document !== 'undefined' && document.hidden) return
      setElapsed((value) => value + TICK_MS)
    }, TICK_MS)
    return () => clearInterval(timer)
  }, [live])

  const figureRef = useCallback(
    (node: HTMLElement | null) => {
      if (!node) return undefined
      const releaseView = viewRef(node)
      const releaseHost = hostRef(node)
      return () => {
        if (typeof releaseView === 'function') releaseView()
        if (typeof releaseHost === 'function') releaseHost()
      }
    },
    [viewRef, hostRef],
  )

  const frame = heroFrame(reduced ? COMPOSED_ELAPSED_MS : elapsed)
  const arrived = frame.approvalArrived

  return (
    <figure ref={figureRef} className="lp-console" aria-labelledby="lp-console-caption">
      <span className="lp-bezel" aria-hidden="true" />

      <div ref={stageRef} className="lp-console-stage">
        <div className="lp-console-depth" data-depth="back">
          <span className="lp-console-pool" aria-hidden="true" />
          <BackLayer frame={frame} />
        </div>

        <div className="lp-console-depth" data-depth="trace">
          <span className="lp-console-pool" aria-hidden="true" />
          <TraceLayer frame={frame} />
        </div>

        <div className="lp-console-depth" data-depth="approval" data-arrived={arrived ? 'true' : 'false'}>
          <span className="lp-console-pool" aria-hidden="true" />
          <div
            className={
              arrived
                ? 'lp-console-layer lp-console-approval lp-glass lp-anim-rise'
                : 'lp-console-layer lp-console-approval lp-glass'
            }
            data-arrived={arrived ? 'true' : 'false'}
            {...(arrived ? {} : { inert: true, 'aria-hidden': true })}
          >
            <Eyebrow>
              <span className="lp-dot lp-dot-warning lp-pulse" aria-hidden="true" />
              Outgoing email
            </Eyebrow>
            <p className="lp-console-approval-title">Send the welcome email</p>
            <div className="lp-console-tags">
              <Tag tone="operations" withDot>
                HR
              </Tag>
              <Tag tone="warning">Waiting for approval</Tag>
            </div>
            <p className="caption">Exactly what will be sent</p>
            <dl className="lp-console-payload">
              <div>
                <dt>to</dt>
                <dd>newhire@example.com</dd>
              </div>
              <div>
                <dt>subject</dt>
                <dd>Welcome to the team</dd>
              </div>
              <div>
                <dt>body</dt>
                <dd>Hello, welcome to the team.</dd>
              </div>
            </dl>
            <a className="lp-link" href="#approval" onClick={inPage}>
              Try deciding it yourself
              <Icon name="arrow-right" />
            </a>
          </div>
        </div>
      </div>

      <p className="visually-hidden">
        The Command Map showing what each AI assistant is working on, a backup AI provider taking over when the
        first two were unavailable, and an email waiting for a person to approve it.
      </p>

      {!reduced && (
        <button
          type="button"
          className="lp-hero-pause"
          aria-pressed={ambientPaused}
          onClick={() => setAmbientPaused(!ambientPaused)}
        >
          <Icon name={ambientPaused ? 'play' : 'pause'} />
          Pause motion
        </button>
      )}

      <figcaption id="lp-console-caption" className="lp-console-caption">
        Illustration of the console, simulated in your browser. Figures are examples.
      </figcaption>
    </figure>
  )
}
