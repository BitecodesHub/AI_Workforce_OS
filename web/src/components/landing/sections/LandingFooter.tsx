import type { ReactElement } from 'react'
import { CTA } from '../shared/landingFacts'

/* The page's closing line: what this is, and the two ways in. */

export function LandingFooter(): ReactElement {
  return (
    <footer className="lp-footer">
      <div className="lp-shell lp-footer-row">
        <p className="caption lp-footer-caption">AI Workforce OS, an enterprise multi-agent platform.</p>
        <nav className="lp-footer-nav" aria-label="Footer">
          <a className="nav-pill" href={CTA.signIn.href}>
            {CTA.signIn.label}
          </a>
          <a className="nav-pill" href={CTA.create.href}>
            {CTA.create.label}
          </a>
        </nav>
      </div>
    </footer>
  )
}
