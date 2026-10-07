import type { ReactElement } from 'react'
import '../styles/landing/index.css'
import { Eyebrow } from '../components/ui'
import { LandingRoot } from '../components/landing/shared/LandingRoot'
import { LandingSection, Reveal } from '../components/landing/shared/LandingSection'
import { SIMULATED_PAGE_NOTICE } from '../components/landing/shared/landingFacts'
import { LandingBar } from '../components/landing/hero/LandingBar'
import type { BarSection } from '../components/landing/hero/LandingBar'
import { DemoStage } from '../components/landing/sections/DemoStage'
import { RoleSwitcher } from '../components/landing/roles/RoleSwitcher'
import { PlatformBand } from '../components/landing/sections/PlatformBand'
import { LimitsSection } from '../components/landing/sections/LimitsSection'
import { FinalCta } from '../components/landing/sections/FinalCta'
import { LandingFooter } from '../components/landing/sections/LandingFooter'

/*
 * The technical detail behind the home page's promises, for the IT and security team that signs
 * a purchase off.
 *
 * All four demos in their technical voice (the approval gate with its tool names and permission
 * codes, provider failover, the audit hash chain, cited retrieval), the full permission map for
 * every role, what actually runs, and the limits, stated verbatim. Linked from the home page's
 * footer; nothing here is needed to understand the product, only to check it.
 */

const TRUST_SECTIONS: readonly BarSection[] = [
  { id: 'demos', label: 'Demos' },
  { id: 'roles', label: 'Permissions' },
  { id: 'platform', label: 'Platform' },
  { id: 'limits', label: 'Limits' },
]

export function Trust(): ReactElement {
  return (
    <LandingRoot>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <LandingBar sections={TRUST_SECTIONS} />
      <main id="main" className="lp-main" tabIndex={-1}>
        <section className="lp-hero lp-trust-hero" aria-labelledby="trust-title">
          <div className="lp-shell">
            <div className="lp-hero-copy lp-trust-copy">
              <Eyebrow>For IT and security teams</Eyebrow>
              <h1 id="trust-title" className="lp-hero-title lp-trust-title">
                How it keeps agents in check
              </h1>
              <p className="lp-hero-lead">
                The detail behind the promises on the overview: the approval gate, provider failover, the tamper-evident
                audit chain, cited retrieval, the permission model and what actually runs.
              </p>
              <p className="lp-hero-lead">
                Agent traces are kept for 180 days by default; administrators can change this.
              </p>
              <a className="lp-link lp-trust-back" href="/home">
                Back to the overview
              </a>
            </div>
          </div>
        </section>
        <LandingSection id="demos" labelledBy="demos-title">
          <Reveal className="lp-head">
            <div className="lp-head-main">
              <Eyebrow>Try it here</Eyebrow>
              <h2 id="demos-title" className="lp-h2">
                Four things that make it safe to switch on
              </h2>
            </div>
            <div className="lp-head-aside">
              <p className="lp-lead">Pick one and try it. Where a demo waits, time is compressed.</p>
              <p className="lp-notice-pill">
                <span className="lp-dot lp-dot-green" aria-hidden="true" />
                {SIMULATED_PAGE_NOTICE}
              </p>
            </div>
          </Reveal>
          <DemoStage />
        </LandingSection>
        <RoleSwitcher />
        <PlatformBand />
        <LimitsSection />
        <FinalCta
          eyebrow="Look around"
          title="Try it on the sandbox model"
          body="Runs on an offline sandbox model out of the box, so no API key is needed. Live providers are one stored key, one enabled provider and a routing policy away."
        />
      </main>
      <LandingFooter sections={TRUST_SECTIONS} other={{ label: 'Back to the overview', href: '/home' }} />
    </LandingRoot>
  )
}
