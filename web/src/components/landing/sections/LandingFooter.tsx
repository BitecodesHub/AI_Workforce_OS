// @find: landing footer, footer links, page footer, sections list, LandingFooter
// @what: The closing footer of the public pages.
// @flow: Used by Landing and Trust
import type { ReactElement } from 'react'
import { HOME_SECTIONS } from '../hero/LandingBar'
import type { BarSection } from '../hero/LandingBar'
import { CTA } from '../shared/landingFacts'
import { useInPageLink } from '../shared/useInPageLink'

/*
 * The page's closing line, centred: every section of the page, the two ways in, the other page
 * (the technical details for IT teams, or the way back home from them), and what this is.
 */

/** Props: sections (the in-page links to list, default HOME_SECTIONS) and other (the link to the other public page). */
export type LandingFooterProps = {
  sections?: readonly BarSection[]
  /** The link to the other public page. */
  other?: { label: string; href: string }
}

// @find: LandingFooter component, footer
/** Closing footer of the public pages: a link for every section (smooth-scrolled by useInPageLink), Sign in, Create your workspace, the link to the other public page (the page for IT teams, or the way back home) and a one-line description of the product. Static. Rendered by the Landing and Trust routes. */
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
