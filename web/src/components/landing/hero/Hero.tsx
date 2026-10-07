import type { ReactElement } from 'react'
import { Eyebrow } from '../../ui'
import { revealStyle } from '../../../hooks/useReveal'
import { Icon } from '../shared/Icon'
import type { IconName } from '../shared/Icon'
import { CTA } from '../shared/landingFacts'
import { useDemoCta } from '../shared/useDemoCta'
import { useInPageLink } from '../shared/useInPageLink'
import { HeroConsole } from './HeroConsole'

/*
 * The first screen: what this is, the two ways in, and a working picture of it.
 *
 * Written for the person choosing the product, not the one who will run it: what the AI team
 * does, and the one promise that matters most to a business - nothing is sent or deleted without
 * a person's yes. Four plain benefits sit under the offer, each a link to the part of the page
 * that shows it. The console beneath is a simulation of the product doing that work, labelled as
 * one. The copy rises in on load, one line after another, only when motion is on.
 *
 * The first button offers the demo accounts only where this site has them; otherwise it leads to
 * the demos further down this page (see useDemoCta).
 */

const BENEFITS: ReadonlyArray<{ icon: IconName; label: string; href: string }> = [
  { icon: 'gate', label: 'Asks before it sends', href: '#approval' },
  { icon: 'document', label: 'Shows its sources', href: '#cited' },
  { icon: 'lock', label: 'The right access for everyone', href: '#safety' },
  { icon: 'check', label: 'A record of every decision', href: '#safety' },
]

export function Hero(): ReactElement {
  const inPage = useInPageLink()
  const demo = useDemoCta()

  return (
    <section className="lp-hero" aria-labelledby="hero-title">
      <div className="lp-shell lp-hero-grid">
        <div className="lp-hero-copy">
          {/* The eyebrow is first in the stagger, index 0, which is also the default. */}
          <Eyebrow>AI employees for your business</Eyebrow>

          <h1 id="hero-title" className="lp-hero-title" style={revealStyle(1)}>
            Hand the busywork to AI. <span className="lp-hero-accent">Keep the final say.</span>
          </h1>

          <p className="lp-hero-lead" style={revealStyle(2)}>
            Your AI team drafts emails, sorts customer questions, prepares reports and keeps routine work moving.
            You ask in plain words, and nothing is sent or deleted until a person says yes.
          </p>

          <div id="lp-hero-ctas" className="lp-hero-ctas" style={revealStyle(3)}>
            <a className="button button-primary lp-button-lg lp-sheen" href={demo.href} onClick={inPage}>
              {demo.label}
              <Icon name="arrow-right" />
            </a>
            <a className="button button-outline lp-button-lg" href={CTA.create.href}>
              {CTA.create.label}
            </a>
          </div>

          <p className="lp-hero-caption" style={revealStyle(4)}>
            <span className="lp-dot lp-dot-green" aria-hidden="true" />
            <span>
              {demo.accounts
                ? 'Try it with sample data. Nothing to install, and no technical skills needed.'
                : 'Try it on this page with sample data. Nothing to install, and no technical skills needed.'}
            </span>
          </p>
        </div>

        <ul className="lp-benefits" style={revealStyle(5)}>
          {BENEFITS.map((benefit) => (
            <li key={benefit.label}>
              <a className="lp-benefit" href={benefit.href} onClick={inPage}>
                <span className="lp-benefit-icon">
                  <Icon name={benefit.icon} size={16} />
                </span>
                <span>{benefit.label}</span>
              </a>
            </li>
          ))}
        </ul>

        <HeroConsole />
      </div>
    </section>
  )
}
