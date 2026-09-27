import { useCallback } from 'react'
import type { ReactElement, ReactNode } from 'react'
import { Eyebrow, Tag } from '../../ui'
import { revealStyle, useReveal } from '../../../hooks/useReveal'
import { usePointerSpot } from '../../../hooks/usePointerSpot'

/*
 * The frame every demo tile sits in.
 *
 * It carries the parts no demo may leave out: the "Simulated" tag, one polite status line for
 * announcements, and the bento area that places the tile. The status is a live region from the
 * first render and starts empty, so the first thing a demo announces is actually heard.
 */

export type DemoArea = 'approval' | 'failover' | 'audit' | 'cited'

export type DemoFrameProps = {
  area: DemoArea
  index: '01' | '02' | '03' | '04'
  name: string
  title: string
  lead: ReactNode
  status: string
  children: ReactNode
  footnote?: string
  busy?: boolean
}

const REVEAL_ORDER: Record<DemoArea, number> = { approval: 0, failover: 1, audit: 2, cited: 3 }

export function DemoFrame({
  area,
  index,
  name,
  title,
  lead,
  status,
  children,
  footnote,
  busy,
}: DemoFrameProps): ReactElement {
  const revealRef = useReveal<HTMLElement>()
  const spotRef = usePointerSpot<HTMLElement>()

  const ref = useCallback(
    (node: HTMLElement | null) => {
      if (!node) return undefined
      const releaseReveal = revealRef(node)
      const releaseSpot = spotRef(node)
      return () => {
        if (typeof releaseReveal === 'function') releaseReveal()
        if (typeof releaseSpot === 'function') releaseSpot()
      }
    },
    [revealRef, spotRef],
  )

  return (
    <article
      ref={ref}
      id={area}
      data-area={area}
      className="lp-demo lp-frost lp-spot lp-reveal"
      style={revealStyle(REVEAL_ORDER[area])}
      aria-labelledby={`${area}-title`}
    >
      <header className="lp-demo-head">
        <Eyebrow>
          {index} · {name}
        </Eyebrow>
        <Tag tone="neutral">Simulated</Tag>
      </header>
      <h3 id={`${area}-title`} className="lp-demo-title">
        {title}
      </h3>
      <p className="lp-demo-lead">{lead}</p>
      <div className="lp-demo-body" aria-busy={busy || undefined}>
        {children}
      </div>
      <p className="lp-demo-status" role="status" aria-atomic="true">
        {status}
      </p>
      {footnote && <p className="lp-demo-foot">{footnote}</p>}
    </article>
  )
}
