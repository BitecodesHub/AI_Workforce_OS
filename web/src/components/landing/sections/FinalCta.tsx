// @find: final call to action, closing cta, create your workspace, try the demo, sign up, FinalCta
// @what: The closing call-to-action block shared by the home and Trust pages.
// @flow: Uses useDemoCta
import type { ReactElement } from 'react'
import { Eyebrow } from '../../ui'
import { Icon } from '../shared/Icon'
import { LandingSection } from '../shared/LandingSection'
import { CTA } from '../shared/landingFacts'
import { useDemoCta } from '../shared/useDemoCta'
import { useInPageLink } from '../shared/useInPageLink'

/*
 * The closing call to action, centred: what trying it involves, and the two ways in. Shared by
 * the home page and the page for IT teams.
 *
 * The default copy says nothing about demo accounts, because a site may not offer them; the first
 * button offers them only where it does, and otherwise returns to the demos on the page (see
 * useDemoCta).
 */

export type FinalCtaProps = {
  eyebrow?: string
  title?: string
  body?: string
}

// @find: FinalCta component, closing call to action
export function FinalCta({
  eyebrow = 'See it for yourself',
  title = 'Watch your AI team at work',
  body = 'See how your AI team drafts the work, asks before anything is sent and shows where its answers come from. The demos run on sample data, so nothing real is sent.',
}: FinalCtaProps): ReactElement {
  const inPage = useInPageLink()
  const demo = useDemoCta()

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
            <a className="button button-primary lp-button-lg lp-sheen" href={demo.href} onClick={inPage}>
              {demo.label}
              <Icon name="arrow-right" />
            </a>
            <a className="button button-outline lp-button-lg" href={CTA.create.href}>
              {CTA.create.label}
            </a>
          </div>
        </div>
      </div>
    </LandingSection>
  )
}
