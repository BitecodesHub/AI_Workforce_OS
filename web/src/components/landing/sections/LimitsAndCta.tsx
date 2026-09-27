import type { ReactElement } from 'react'
import { Eyebrow } from '../../ui'
import { Icon } from '../shared/Icon'
import { LandingSection, Reveal } from '../shared/LandingSection'
import { CTA, DEMO_ACCOUNTS_HEDGE } from '../shared/landingFacts'

/*
 * What the platform does not do, stated beside the final call to action rather than below it, so
 * nobody reaches the button without having read the limits.
 *
 * The four limits are carried over verbatim from the previous home page.
 */

const LIMITS: ReadonlyArray<string> = [
  'Agents are not autonomous. They stop at every outbound or destructive action and wait for a person.',
  'Answers are only as good as the documents you connect. With nothing indexed, an agent will tell you it cannot answer.',
  'The offline model is a placeholder. It returns sensible-looking text so the platform can be tried, and says so wherever it answers.',
  'Connecting a live tool account needs an administrator. Until then every server runs against a sandbox and nothing leaves your machine.',
]

export function LimitsAndCta(): ReactElement {
  return (
    <LandingSection id="limits" labelledBy="limits-title">
      <div className="lp-limits-split">
        <Reveal className="lp-limits lp-frost">
          <Eyebrow>What it does not do</Eyebrow>
          <h2 id="limits-title" className="lp-h2">
            Stated plainly, so nothing here is a surprise later
          </h2>
          <ol className="lp-limits-list lp-hairline-grid">
            {LIMITS.map((limit, index) => (
              <li key={limit} className="lp-hairline-cell lp-limits-item">
                <span className="lp-limits-mark" aria-hidden="true">
                  <span className="lp-limits-index">{String(index + 1).padStart(2, '0')}</span>
                  <Icon name="slash" className="lp-limits-icon" />
                </span>
                <p className="lp-limits-text">{limit}</p>
              </li>
            ))}
          </ol>
        </Reveal>

        <div className="lp-cta-wrap">
          <span className="lp-aurora-low lp-cta-aurora" aria-hidden="true" />
          <section className="lp-cta lp-glass" aria-labelledby="cta-title">
            <Eyebrow>Look around</Eyebrow>
            <h2 id="cta-title" className="lp-h2">
              Try it on the sandbox model
            </h2>
            <p className="lp-copy lp-cta-copy">
              Runs on an offline sandbox model out of the box, so no API key is needed. Live providers are one stored
              key, one enabled provider and a routing policy away.
            </p>
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
          </section>
        </div>
      </div>
    </LandingSection>
  )
}
