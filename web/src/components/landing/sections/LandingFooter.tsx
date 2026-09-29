import type { ReactElement } from 'react'
import { HOME_SECTIONS } from '../hero/LandingBar'
import type { BarSection } from '../hero/LandingBar'
import { CTA } from '../shared/landingFacts'
import { useInPageLink } from '../shared/useInPageLink'

/*
 * The page's closing line, centred: every section of the page, the two ways in, the other page
 * (the technical details for IT teams, or the way back home from them), and what this is.
 */

export type LandingFooterProps = {
  sections?: readonly BarSection[]
  /** The link to the other public page. */
  other?: { label: string; href: string }
}

export function LandingFooter({ sections = HOME_SECTIONS, other = CTA.technical }: LandingFooterProps): ReactElement {
  const inPage = useInPageLink()
  return (
    <footer className="lp-footer">
      <div className="lp-shell">
        <div className="lp-footer-inner">
          <nav className="lp-footer-nav" aria-label="Footer">
            {sections.map((section) => (
              <a key={section.id} className="nav-pill" href={`#${section.id}`} onClick={inPage}>
                {section.label}
              </a>
            ))}
            <a className="nav-pill" href={CTA.signIn.href}>
              {CTA.signIn.label}
            </a>
            <a className="nav-pill" href={CTA.create.href}>
              {CTA.create.label}
            </a>
          </nav>
          <a className="lp-footer-other" href={other.href}>
            {other.label}
          </a>
          <p className="caption lp-footer-caption">AI Workforce OS, an enterprise multi-agent platform.</p>
        </div>
      </div>
    </footer>
  )
}
