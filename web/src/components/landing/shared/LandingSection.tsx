// @find: landing section, section header, reveal, scroll reveal, SectionHead, Reveal, LandingSection
// @what: Section scaffolding, split header and reveal wrapper for the public page.
// @flow: Used by every section
import type { ReactElement, ReactNode } from 'react'
import { Eyebrow } from '../../ui'
import { revealStyle, useReveal } from '../../../hooks/useReveal'

/*
 * Section scaffolding for the public page: the section itself, its split header, and the reveal
 * wrapper everything else on the page is built from.
 */

function isPresent(node: ReactNode): boolean {
  return node !== undefined && node !== null && node !== false && node !== ''
}

/* ---- LandingSection ------------------------------------------------------------------------ */

export type LandingSectionProps = {
  id: string
  labelledBy: string
  children: ReactNode
  className?: string
  tight?: boolean
}

/**
 * One band of the page. tabIndex -1 makes it a target for in-page links, so focus lands where
 * the page scrolled to; it is never in the tab order.
 */
// @find: LandingSection component, section wrapper
export function LandingSection({ id, labelledBy, children, className, tight }: LandingSectionProps): ReactElement {
  const classes = ['lp-section', tight ? 'lp-section-tight' : '', className ?? ''].filter(Boolean).join(' ')
  return (
    <section id={id} className={classes} aria-labelledby={labelledBy} tabIndex={-1}>
      <div className="lp-shell">{children}</div>
    </section>
  )
}

/* ---- Reveal -------------------------------------------------------------------------------- */

export type RevealTag = 'div' | 'article' | 'li' | 'figure' | 'aside' | 'header' | 'section'

export type RevealProps = {
  as?: RevealTag
  index?: number
  className?: string
  id?: string
  labelledBy?: string
  children: ReactNode
}

/** Rises into place the first time it scrolls into view; under reduced motion it is simply there. */
// @find: Reveal component, scroll reveal
export function Reveal({ as, index, className, id, labelledBy, children }: RevealProps): ReactElement {
  const ref = useReveal<HTMLElement>()
  const Element = as ?? 'div'
  return (
    <Element
      ref={ref}
      className={className ? `lp-reveal ${className}` : 'lp-reveal'}
      style={revealStyle(index ?? 0)}
      {...(id !== undefined ? { id } : {})}
      {...(labelledBy !== undefined ? { 'aria-labelledby': labelledBy } : {})}
    >
      {children}
    </Element>
  )
}

/* ---- SectionHead --------------------------------------------------------------------------- */

export type SectionHeadProps = {
  eyebrow: string
  title: ReactNode
  titleId: string
  lead?: ReactNode
  aside?: ReactNode
  band?: boolean
}

/** The split header: eyebrow and heading on the left, the lead and any aside on the right. */
// @find: SectionHead component, section header
export function SectionHead({ eyebrow, title, titleId, lead, aside, band }: SectionHeadProps): ReactElement {
  const hasLead = isPresent(lead)
  const hasAside = isPresent(aside)
  return (
    <Reveal className="lp-head">
      <div className="lp-head-main">
        <Eyebrow>{eyebrow}</Eyebrow>
        <h2 id={titleId} className={band ? 'lp-h2 lp-h2-band' : 'lp-h2'}>
          {title}
        </h2>
      </div>
      {(hasLead || hasAside) && (
        <div className="lp-head-aside">
          {hasLead && <p className="lp-lead">{lead}</p>}
          {hasAside && aside}
        </div>
      )}
    </Reveal>
  )
}
