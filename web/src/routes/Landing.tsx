// @find: landing page, home page, marketing page, public page, buyers, what is this, hero, how it works, FAQ, pricing, call to action, /home, Landing
// @what: The public home page written for company buyers in plain words.
// @flow: Routed from App.tsx at /home; assembled from components/landing sections and styles/landing
import type { ReactElement } from 'react'
import '../styles/landing/index.css'
import { Eyebrow } from '../components/ui'
import { LandingRoot } from '../components/landing/shared/LandingRoot'
import { LandingSection, Reveal } from '../components/landing/shared/LandingSection'
import { SIMULATED_PAGE_NOTICE } from '../components/landing/shared/landingFacts'
import { LandingBar } from '../components/landing/hero/LandingBar'
import { Hero } from '../components/landing/hero/Hero'
import { HowItWorks } from '../components/landing/sections/HowItWorks'
import { AgentsSection } from '../components/landing/sections/AgentsSection'
import { DemoStage } from '../components/landing/sections/DemoStage'
import type { StageItem } from '../components/landing/sections/DemoStage'
import { SafetySection } from '../components/landing/sections/SafetySection'
import { Faq } from '../components/landing/sections/Faq'
import { FinalCta } from '../components/landing/sections/FinalCta'
import { LandingFooter } from '../components/landing/sections/LandingFooter'

/*
 * The public home page, written for the people who choose the product rather than the engineers
 * who will run it.
 *
 * It says what the AI team does and the promise that matters most to a business - nothing is sent
 * or deleted without a person's yes - then shows it: how it works in three steps, the ready-made
 * assistants, two demos anybody can follow, why it is safe to switch on, and the questions buyers
 * ask. The technical detail an IT team reviews (the permission map, provider failover, the audit
 * chain, what actually runs) lives on its own page, /trust, linked from the footer.
 *
 * Every demo runs in the browser, says it is simulated, and sends nothing; where a demo waits,
 * time is compressed. Every claim matches what the platform ships.
 */

const HOME_DEMOS: readonly StageItem[] = [
  { id: 'approval', name: 'It asks before it sends' },
  { id: 'cited', name: 'It answers from your documents' },
]

// @find: Landing component, public home page, /home
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
        <HowItWorks />
        <AgentsSection />
        <LandingSection id="demos" labelledBy="demos-title">
          <Reveal className="lp-head">
            <div className="lp-head-main">
              <Eyebrow>See it work</Eyebrow>
              <h2 id="demos-title" className="lp-h2">
                Try it right here
              </h2>
            </div>
            <div className="lp-head-aside">
              <p className="lp-lead">Two short demos you can use now. Where one waits, time is sped up.</p>
              <p className="lp-notice-pill">
                <span className="lp-dot lp-dot-green" aria-hidden="true" />
                {SIMULATED_PAGE_NOTICE}
              </p>
            </div>
          </Reveal>
          <DemoStage items={HOME_DEMOS} voice="plain" />
        </LandingSection>
        <SafetySection />
        <Faq />
        <FinalCta />
      </main>
      <LandingFooter />
    </LandingRoot>
  )
}
