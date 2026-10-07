import type { ReactElement } from 'react'
import { Icon } from '../shared/Icon'
import { LandingSection, Reveal, SectionHead } from '../shared/LandingSection'
import { CONNECTOR_LABEL, LIVE_CONNECTORS, listOf } from '../shared/landingFacts'

/*
 * What the platform does not do, for the IT team reviewing it: four equal cells in one row,
 * directly above the call to action, so nobody reaches the button without having read them.
 * The home page answers the same limits as plain questions (see Faq.tsx).
 *
 * The connector limit names both lists from landingFacts, which copies mcp-core's
 * ConnectorCatalog, so it cannot claim more live connectors than the catalogue has.
 */

/** In a sentence, "webhooks" reads as the thing it is rather than as a product name. */
function proseLabel(id: (typeof LIVE_CONNECTORS)[number]): string {
  return id === 'webhook' ? 'webhooks' : CONNECTOR_LABEL[id]
}

const LIMITS: ReadonlyArray<string> = [
  'Agents are not autonomous. They stop at every outbound or destructive action and wait for a person.',
  'Answers are only as good as the documents you connect. With nothing indexed, an agent will tell you it cannot answer.',
  'The offline model is a placeholder. It returns sensible-looking text so the platform can be tried, and says so wherever it answers.',
  `Each connector reaches a real account only once an administrator adds its access token or signs in with the provider (${listOf(LIVE_CONNECTORS.map(proseLabel))}). Until then agents work with practice data and nothing is sent.`,
]

export function LimitsSection(): ReactElement {
  return (
    <LandingSection id="limits" labelledBy="limits-title">
      <SectionHead
        eyebrow="What it does not do"
        title="Stated plainly, so nothing here is a surprise later"
        titleId="limits-title"
      />
      <Reveal className="lp-limits">
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
    </LandingSection>
  )
}
