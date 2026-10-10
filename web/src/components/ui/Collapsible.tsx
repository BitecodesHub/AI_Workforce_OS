// @find: collapsible card, expand collapse, accordion, show hide section, remember collapsed, useCollapsed, find in page, Collapsible
// @what: Card that collapses to a summary line while keeping its body in the page, with a hook that remembers the state.
// @flow: Used by detail screens; persists via lib/persist.
import type { ReactNode } from 'react'
import { useEffect, useId, useRef, useState } from 'react'
import { readStored, writeStored } from '../../lib/persist'

/*
 * A card that can be shrunk to a summary line without losing its content.
 *
 * The body never leaves the DOM: it is only hidden, and hidden the browser's own way so that
 * find-in-page can still reach text inside a collapsed card and reopen it. Losing that was the
 * whole point of building this instead of an if in the caller.
 */

type HeadingLevel = 'h2' | 'h3' | 'h4' | 'p'

// @find: collapsible card, expand collapse section
export function Collapsible({
  title,
  summary,
  open,
  onToggle,
  headingLevel = 'h2',
  actions,
  children,
  className,
}: {
  title: ReactNode
  /** Shown beside the title only while collapsed. */
  summary?: ReactNode
  open: boolean
  onToggle: (open: boolean) => void
  headingLevel?: HeadingLevel
  actions?: ReactNode
  children: ReactNode
  className?: string
}) {
  const bodyId = useId()
  const bodyRef = useRef<HTMLDivElement>(null)
  const Heading = headingLevel

  // React 19's typings accept only a boolean `hidden`, so `until-found` - the value that lets the
  // browser's own find-in-page reopen a collapsed card - is set through a ref instead of as JSX.
  useEffect(() => {
    const body = bodyRef.current
    if (!body) return
    if (open) {
      body.hidden = false
      return
    }
    if (typeof document !== 'undefined' && 'onbeforematch' in document.body) {
      body.setAttribute('hidden', 'until-found')
    } else {
      body.hidden = true
    }
  }, [open])

  useEffect(() => {
    const body = bodyRef.current
    if (!body) return
    const onBeforeMatch = () => onToggle(true)
    body.addEventListener('beforematch', onBeforeMatch)
    return () => body.removeEventListener('beforematch', onBeforeMatch)
  }, [onToggle])

  return (
    <div className={`collapsible ${className ?? ''}`.trim()}>
      <div className="collapsible-header">
        <Heading className="collapsible-heading">
          <button
            type="button"
            className="collapsible-toggle"
            aria-expanded={open}
            aria-controls={bodyId}
            onClick={() => onToggle(!open)}
          >
            <svg
              className="collapsible-chevron"
              data-open={open}
              width="12"
              height="12"
              viewBox="0 0 12 12"
              fill="none"
              aria-hidden="true"
            >
              <path d="M4 2.5 8 6l-4 3.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <span className="collapsible-title">{title}</span>
          </button>
        </Heading>
        {!open && summary && <span className="collapsible-summary">{summary}</span>}
        {actions && <div className="collapsible-actions">{actions}</div>}
      </div>
      <div id={bodyId} ref={bodyRef}>
        {children}
      </div>
    </div>
  )
}

const isBoolean = (value: unknown): value is boolean => typeof value === 'boolean'

// @find: remember collapsed state, useCollapsed hook
/**
 * The open/closed state a caller hands to `Collapsible`, remembered across a reload when
 * `storageKey` is given, and overridable in bulk (a Details mode switching every card at once)
 * without losing a manual toggle made since the last such switch.
 *
 * A person who opens or closes one card by hand keeps that choice until `overrideVersion` changes
 * again - the "previous value" pattern used elsewhere for state adjusted during render (0.2),
 * rather than a `setState` inside an effect mirroring the override.
 */
export function useCollapsed(
  storageKey: string | null,
  defaultOpen: boolean,
  override?: 'expanded' | 'collapsed' | null,
  overrideVersion = 0,
): [boolean, (open: boolean) => void] {
  const [open, setOpen] = useState<boolean>(() =>
    storageKey ? readStored<boolean>(storageKey, defaultOpen, isBoolean) : defaultOpen,
  )
  const [followedVersion, setFollowedVersion] = useState(overrideVersion)

  if (override && overrideVersion !== followedVersion) {
    setFollowedVersion(overrideVersion)
    const next = override === 'expanded'
    if (next !== open) setOpen(next)
  }

  const toggle = (next: boolean) => {
    setOpen(next)
    if (storageKey) writeStored(storageKey, next)
  }

  return [open, toggle]
}
