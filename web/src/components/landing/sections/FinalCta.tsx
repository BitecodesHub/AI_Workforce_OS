import type { ReactElement } from 'react'
import { Eyebrow } from '../../ui'
import { Icon } from '../shared/Icon'
import { LandingSection } from '../shared/LandingSection'
import { CTA, DEMO_ACCOUNTS_HEDGE } from '../shared/landingFacts'

/*
 * The closing call to action, centred: what trying it involves, and the two ways in. Shared by
 * the home page and the page for IT teams.
 */

export type FinalCtaProps = {
  eyebrow?: string
  title?: string
  body?: string
}

export function FinalCta({
  eyebrow = 'See it for yourself',
  title = 'Watch your AI team at work',
  body = 'Sign in with a demo account in one click, as a manager, an employee or a viewer, and see exactly what each one can do. It runs on sample data, so nothing real is sent.',
}: FinalCtaProps): ReactElement {
  return (
    <LandingSection id="start" labelledBy="cta-title">
      <div className="lp-cta-wrap">
        <span className="lp-aurora-low lp-cta-aurora" aria-hidden="true" />
        <div className="lp-cta lp-glass">
          <Eyebrow>{eyebrow}</Eyebrow>
          <h2 id="cta-title" className="lp-h2">
            {title}
          </h2>
          <p className="lp-copy lp-cta-copy">{body}</p>
          <div className="lp-cta-actions">
            <a className="button button-primary lp-button-lg lp-sheen" href={CTA.demo.href}>
              {CTA.demo.label}
              <Icon name="arrow-right" />
            </a>
            <a className="button button-outline lp-button-lg" href={CTA.create.href}>
              {CTA.create.label}
            </a>
          </div>
          <p className="caption lp-cta-hedge">{DEMO_ACCOUNTS_HEDGE}</p>
        </div>
      </div>
    </LandingSection>
  )
}
