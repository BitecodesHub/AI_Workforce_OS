import type { ReactElement } from 'react'
import { Eyebrow } from '../../ui'
import { revealStyle } from '../../../hooks/useReveal'
import { Icon } from '../shared/Icon'
import { AGENTS, CTA, PERMISSION_CODE_COUNT, PROVIDERS, TOOL_SERVERS } from '../shared/landingFacts'
import { useInPageLink } from '../shared/useInPageLink'
import { HeroConsole } from './HeroConsole'

/*
 * The first screen: what this is, the two ways in, and a working picture of it.
 *
 * The copy column states the offer and four figures, each a link to the part of the page that
 * proves it. The console beside it is a simulation of the product doing that work, labelled as
 * one. The copy rises in on load, one line after another, only when motion is on.
 */

const FACTS: ReadonlyArray<{ value: string; label: string; href: string }> = [
  { value: String(AGENTS.length), label: 'agents', href: '#agents' },
  { value: `${PROVIDERS.length} + 1`, label: 'providers + sandbox', href: '#failover' },
  { value: String(TOOL_SERVERS.length), label: 'tool servers', href: '#approval' },
  { value: String(PERMISSION_CODE_COUNT), label: 'permission codes', href: '#roles' },
]

export function Hero(): ReactElement {
  const inPage = useInPageLink()

  return (
    <section className="lp-hero" aria-labelledby="hero-title">
      <div className="lp-shell lp-hero-grid">
        <div className="lp-hero-copy">
          {/* The eyebrow is first in the stagger, index 0, which is also the default. */}
          <Eyebrow>A governed AI workforce</Eyebrow>

          <h1 id="hero-title" className="lp-hero-title" style={revealStyle(1)}>
            A team of AI employees your company can <span className="lp-hero-accent">actually authorise</span>
          </h1>

          <p className="lp-hero-lead" style={revealStyle(2)}>
            Configure agents for the roles you already have, let them work from your own documents and your real
            tools, and keep a person in front of every action that cannot be taken back.
          </p>

          <div id="lp-hero-ctas" className="lp-hero-ctas" style={revealStyle(3)}>
            <a className="button button-primary lp-button-lg lp-sheen" href={CTA.demo.href}>
              {CTA.demo.label}
              <Icon name="arrow-right" />
            </a>
            <a className="button button-outline lp-button-lg" href={CTA.create.href}>
              {CTA.create.label}
            </a>
          </div>

          <p className="lp-hero-caption" style={revealStyle(4)}>
            <span className="lp-dot lp-dot-green" aria-hidden="true" />
            <span>Runs on an offline sandbox model out of the box. No API key is needed to look around.</span>
          </p>

          <ul className="lp-facts lp-glass" style={revealStyle(5)}>
            {FACTS.map((fact) => (
              <li key={fact.href}>
                <a className="lp-fact" href={fact.href} onClick={inPage}>
                  <span className="lp-fact-value">{fact.value}</span>{' '}
                  <span className="lp-fact-label lp-micro">{fact.label}</span>
                </a>
              </li>
            ))}
          </ul>
        </div>

        <HeroConsole />
      </div>
    </section>
  )
}
