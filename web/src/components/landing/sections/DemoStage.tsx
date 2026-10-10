// @find: demo stage, demo tabs, demos, try the demos, approval failover audit cited, plain voice, technical voice, DemoStage, #demos
// @what: One row of tabs over a single stage that shows one working demo at a time.
// @flow: Used by Landing and Trust; renders the four demos; listens for lp:reveal
import { useCallback, useEffect, useRef, useState } from 'react'
import type { CSSProperties, KeyboardEvent, ReactElement } from 'react'
import { flushSync } from 'react-dom'
import { Icon } from '../shared/Icon'
import type { IconName } from '../shared/Icon'
import type { DemoArea } from '../shared/DemoFrame'
import type { DemoVoice } from '../shared/voice'
import { REVEAL_EVENT, type RevealDetail } from '../shared/revealEvent'
import { ApprovalDemo } from '../agent-run/ApprovalDemo'
import { FailoverDemo } from '../failover/FailoverDemo'
import { AuditChainDemo } from './AuditChainDemo'
import { CitedAnswerDemo } from '../answer/CitedAnswerDemo'

/*
 * The demos, one at a time.
 *
 * Four full demos stacked in a grid made the page several screens tall and asked the visitor to
 * read all of them at once. Here they sit behind one row of tabs over a single stage: the tabs
 * are the list of what is worth trying, the stage shows one working demo at full width. A page
 * chooses which demos it shows and in which voice: the home page shows two in plain words, the
 * page for IT teams all four in technical terms.
 *
 * A demo that is not showing is hidden, not unmounted, so each keeps its state and its anchor id
 * (#approval, #failover, #audit, #cited). Hidden demos have no layout, so each one's autoplay
 * waits until its tab is opened and it is actually on screen. A link to a hidden demo, from the
 * hero's facts or the console, opens its tab before the page scrolls to it - see useInPageLink,
 * which announces the target with the lp:reveal event.
 *
 * Keyboard: the tabs follow the ARIA tabs pattern with automatic activation. Arrow keys move
 * between them, Home and End jump to the ends, and only the selected tab is in the Tab order.
 */

/** One tab: which demo, and what its tab is called on this page. */
export type StageItem = { id: DemoArea; name: string }

const REGISTRY: Record<DemoArea, { icon: IconName; render: (voice: DemoVoice) => ReactElement }> = {
  approval: { icon: 'gate', render: (voice) => <ApprovalDemo voice={voice} /> },
  failover: { icon: 'route', render: () => <FailoverDemo /> },
  audit: { icon: 'link', render: () => <AuditChainDemo /> },
  cited: { icon: 'document', render: (voice) => <CitedAnswerDemo voice={voice} /> },
}

/** All four, as the page for IT teams shows them. */
export const TECHNICAL_ITEMS: readonly StageItem[] = [
  { id: 'approval', name: 'Approval gate' },
  { id: 'failover', name: 'Model routing' },
  { id: 'audit', name: 'Audit chain' },
  { id: 'cited', name: 'Cited answers' },
]

export type DemoStageProps = {
  items?: readonly StageItem[]
  voice?: DemoVoice
}

function demoFromHash(items: readonly StageItem[]): DemoArea | null {
  if (typeof window === 'undefined') return null
  const id = decodeURIComponent(window.location.hash.slice(1))
  return items.find((item) => item.id === id)?.id ?? null
}

// @find: DemoStage component, demo tabs
export function DemoStage({ items = TECHNICAL_ITEMS, voice = 'technical' }: DemoStageProps): ReactElement {
  const [selected, setSelected] = useState<DemoArea>(() => demoFromHash(items) ?? items[0]!.id)
  const tabRefs = useRef<Partial<Record<DemoArea, HTMLButtonElement | null>>>({})

  // A link elsewhere on the page names a demo: open it synchronously, so the scroll that follows
  // lands on a laid-out panel rather than on a hidden one.
  useEffect(() => {
    const onReveal = (event: Event) => {
      const id = (event as CustomEvent<RevealDetail>).detail?.id
      const demo = items.find((candidate) => candidate.id === id)
      if (demo) flushSync(() => setSelected(demo.id))
    }
    const onHash = () => {
      const id = demoFromHash(items)
      if (id) setSelected(id)
    }
    window.addEventListener(REVEAL_EVENT, onReveal)
    window.addEventListener('hashchange', onHash)
    return () => {
      window.removeEventListener(REVEAL_EVENT, onReveal)
      window.removeEventListener('hashchange', onHash)
    }
  }, [items])

  const select = useCallback((id: DemoArea, focus: boolean) => {
    setSelected(id)
    if (focus) tabRefs.current[id]?.focus()
  }, [])

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const current = items.findIndex((demo) => demo.id === selected)
    let next = -1
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = (current + 1) % items.length
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = (current - 1 + items.length) % items.length
    else if (event.key === 'Home') next = 0
    else if (event.key === 'End') next = items.length - 1
    if (next < 0) return
    event.preventDefault()
    select(items[next]!.id, true)
  }

  const active = items.findIndex((demo) => demo.id === selected)
  const thumb = {
    '--lp-stage-count': items.length,
    '--lp-stage-active': active,
    '--lp-stage-col': active % 2,
    '--lp-stage-row': Math.floor(active / 2),
  } as CSSProperties

  return (
    <div className="lp-stage">
      <div
        className="lp-stage-tabs"
        role="tablist"
        aria-label="Demos"
        data-count={items.length}
        style={thumb}
        onKeyDown={onKeyDown}
      >
        <span className="lp-stage-thumb" aria-hidden="true" />
        {items.map((demo, position) => {
          const isSelected = demo.id === selected
          return (
            <button
              key={demo.id}
              ref={(node) => {
                tabRefs.current[demo.id] = node
              }}
              type="button"
              role="tab"
              id={`${demo.id}-tab`}
              className="lp-stage-tab"
              aria-selected={isSelected}
              aria-controls={`${demo.id}-panel`}
              tabIndex={isSelected ? 0 : -1}
              onClick={() => select(demo.id, false)}
            >
              <span className="lp-stage-tab-icon">
                <Icon name={REGISTRY[demo.id].icon} size={16} />
              </span>
              <span className="lp-stage-tab-index">{String(position + 1).padStart(2, '0')}</span>
              <span className="lp-stage-tab-name">{demo.name}</span>
            </button>
          )
        })}
      </div>

      <div className="lp-stage-panels">
        {items.map(({ id }) => (
          <div
            key={id}
            role="tabpanel"
            id={`${id}-panel`}
            aria-labelledby={`${id}-tab`}
            className="lp-stage-panel"
            hidden={id !== selected}
          >
            {REGISTRY[id].render(voice)}
          </div>
        ))}
      </div>
    </div>
  )
}
