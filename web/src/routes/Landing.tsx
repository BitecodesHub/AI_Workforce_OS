import type { ReactElement } from 'react'
import '../styles/landing/index.css'
import { Eyebrow } from '../components/ui'
import { LandingRoot } from '../components/landing/shared/LandingRoot'
import { LandingSection, Reveal } from '../components/landing/shared/LandingSection'
import { SIMULATED_PAGE_NOTICE } from '../components/landing/shared/landingFacts'
import { LandingBar } from '../components/landing/hero/LandingBar'
import { Hero } from '../components/landing/hero/Hero'
import { AgentsSection } from '../components/landing/sections/AgentsSection'
import { ApprovalDemo } from '../components/landing/agent-run/ApprovalDemo'
import { FailoverDemo } from '../components/landing/failover/FailoverDemo'
import { AuditChainDemo } from '../components/landing/sections/AuditChainDemo'
import { CitedAnswerDemo } from '../components/landing/answer/CitedAnswerDemo'
import { RoleSwitcher } from '../components/landing/roles/RoleSwitcher'
import { PlatformBand } from '../components/landing/sections/PlatformBand'
import { LimitsAndCta } from '../components/landing/sections/LimitsAndCta'
import { LandingFooter } from '../components/landing/sections/LandingFooter'

/*
 * The public home page.
 *
 * It explains what the product is by showing it: what an agent may do, what stops it, what
 * happens when a provider fails, and who can see what. Every demo runs in the browser, says it is
 * simulated, and sends nothing; where a demo waits, time is compressed. The claims come from
 * landingFacts.ts and the owners' data files, which match the code the platform ships, and the
 * limits sit beside the final call to action so nobody reaches the button without reading them.
 */

export function Landing(): ReactElement {
  return (
    <LandingRoot>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <LandingBar />
      {/* tabIndex so the skip link actually moves focus somewhere, not just the viewport - a
          skip link that scrolls but never focuses leaves a keyboard user with no visible
          indication of where they landed. */}
      <main id="main" className="lp-main" tabIndex={-1}>
        <Hero />
        <AgentsSection />
        <LandingSection id="demos" labelledBy="demos-title">
          <Reveal className="lp-head">
            <div className="lp-head-main">
              <Eyebrow>Try it here</Eyebrow>
              <h2 id="demos-title" className="lp-h2">
                Four things that make it safe to switch on
              </h2>
            </div>
            <div className="lp-head-aside">
              <p className="lp-notice-pill">
                <span className="lp-dot lp-dot-green" aria-hidden="true" />
                {SIMULATED_PAGE_NOTICE}
              </p>
              <p className="caption">Where a demo waits, time is compressed.</p>
            </div>
          </Reveal>
          <div className="lp-bento">
            <ApprovalDemo />
            <FailoverDemo />
            <AuditChainDemo />
            <CitedAnswerDemo />
          </div>
        </LandingSection>
        <RoleSwitcher />
        <PlatformBand />
        <LimitsAndCta />
      </main>
      <LandingFooter />
    </LandingRoot>
  )
}
