// @find: hero, first screen, landing hero, headline, call to action, try the demo, dark stage, globe, orbit cards, what is this product, Hero
// @what: The first screen of the home page: a dark stage with the offer, two ways in, a wireframe globe and two floating cards.
// @flow: Uses HeroGlobe, useDemoCta and useInPageLink; styled by styles/landing/sky.css
import type { ReactElement } from 'react'
import { Eyebrow } from '../../ui'
import { revealStyle } from '../../../hooks/useReveal'
import { Icon } from '../shared/Icon'
import { CTA } from '../shared/landingFacts'
import { useDemoCta } from '../shared/useDemoCta'
import { useInPageLink } from '../shared/useInPageLink'
import { useLandingMotion } from '../shared/LandingRoot'
import { HeroGlobe } from './HeroGlobe'

/*
 * The first screen: what this is, the two ways in, and a picture of a connected team.
 *
 * The offer sits on a dark, rounded stage under a starfield, with a glowing wireframe globe rising
 * from the bottom edge. Two glass cards float over the globe, each a true fact about the product
 * and a link to the part of the page that shows it. The stage stays dark in both themes; its
 * colours are the --stage-* tokens.
 *
 * The first button offers the demo accounts only where this site has them; otherwise it leads to
 * the demos further down this page (see useDemoCta).
 */

/**
 * The lead under the headline. Its first sentence names the product and says what it does, so a
 * search or answer engine quoting the page has one sentence that answers "what is AI Workforce
 * OS". Exported for llms-full.txt (src/seo/crawlerFiles.ts), which repeats it word for word.
 */
export const HERO_LEAD =
  'AI Workforce OS gives your business a team of AI assistants that draft emails, sort customer questions, prepare reports and keep routine work moving. You ask in plain words, and nothing is sent or deleted until a person says yes.'

/** Seven model providers, one segment each: OpenRouter, Groq, NVIDIA NIM, Gemini, Bedrock, Anthropic, OpenAI. */
const PROVIDER_COUNT = 7

// @find: Hero component, home page hero
/**
 * First screen of the home page, on a dark stage that stays dark in both themes.
 * Renders the h1 offer ("Hand the busywork to AI. Keep the final say."), a lead paragraph, two calls to action
 * (the first from useDemoCta: the demo accounts where this site has them, otherwise an in-page link to the demos,
 * smooth-scrolled by useInPageLink; the second to create a workspace), a caption, the HeroGlobe and two glass cards
 * that link to the #cited and #safety sections (one states the seven providers). The copy is staggered with
 * revealStyle. A Pause motion button (aria-pressed) appears unless reduced motion is on and flips the page's
 * ambient motion through useLandingMotion (WCAG 2.2.2). The id lp-hero-ctas lets LandingBar hide its own call to
 * action while these are on screen. Static: no data requests.
 */
export function Hero(): ReactElement {
  const inPage = useInPageLink()
  const demo = useDemoCta()
  const motion = useLandingMotion()

  return (
    <section className="lp-hero" aria-labelledby="hero-title">
      <div className="lp-sky">
        <div className="lp-sky-copy">
          {/* The eyebrow is first in the stagger, index 0, which is also the default. */}
          <Eyebrow>AI employees for your business</Eyebrow>

          <h1 id="hero-title" className="lp-sky-title" style={revealStyle(1)}>
            Hand the busywork to AI. <span className="lp-sky-accent">Keep the final say.</span>
          </h1>

          <p className="lp-sky-lead" style={revealStyle(2)}>
            {HERO_LEAD}
          </p>

          <div id="lp-hero-ctas" className="lp-sky-ctas" style={revealStyle(3)}>
            <a className="lp-sky-button lp-sky-button-light" href={demo.href} onClick={inPage}>
              {demo.label}
            </a>
            <a className="lp-sky-button lp-sky-button-glass" href={CTA.create.href}>
              {CTA.create.label}
            </a>
          </div>

          <p className="lp-sky-caption" style={revealStyle(4)}>
            <span className="lp-dot lp-dot-green" aria-hidden="true" />
            <span>
              {demo.accounts
                ? 'Try it with sample data. Nothing to install, and no technical skills needed.'
                : 'Try it on this page with sample data. Nothing to install, and no technical skills needed.'}
            </span>
          </p>
        </div>

        <div className="lp-sky-orbit" style={revealStyle(5)}>
          <HeroGlobe />

          <a className="lp-orbit-card lp-orbit-card-left" href="#cited" onClick={inPage}>
            <span className="lp-orbit-card-head">
              <span className="lp-orbit-card-label">Answers you can check</span>
              <span className="lp-orbit-card-arrow" aria-hidden="true">
                <Icon name="arrow-right" size={14} />
              </span>
            </span>
            <span className="lp-orbit-card-title">Every answer shows the page it came from</span>
          </a>

          <a className="lp-orbit-card lp-orbit-card-right" href="#safety" onClick={inPage}>
            <span className="lp-orbit-card-head">
              <span className="lp-orbit-card-label">AI providers</span>
              <span className="lp-orbit-card-arrow" aria-hidden="true">
                <Icon name="arrow-right" size={14} />
              </span>
            </span>
            <span className="lp-orbit-card-figure">{PROVIDER_COUNT}</span>
            <span className="lp-orbit-card-meter" aria-hidden="true">
              {Array.from({ length: PROVIDER_COUNT }, (_, index) => (
                <span key={index} />
              ))}
            </span>
            <span className="lp-orbit-card-note">If one is busy, the next one answers.</span>
          </a>
        </div>

        {!motion.reduced && (
          <button
            type="button"
            className="lp-sky-pause"
            aria-pressed={motion.ambientPaused}
            onClick={() => motion.setAmbientPaused(!motion.ambientPaused)}
          >
            <Icon name={motion.ambientPaused ? 'play' : 'pause'} size={14} />
            {motion.ambientPaused ? 'Play motion' : 'Pause motion'}
          </button>
        )}
      </div>

    </section>
  )
}
