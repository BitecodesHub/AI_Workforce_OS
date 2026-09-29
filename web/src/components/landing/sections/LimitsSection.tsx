import type { ReactElement } from 'react'
import { Icon } from '../shared/Icon'
import { LandingSection, Reveal, SectionHead } from '../shared/LandingSection'

/*
 * What the platform does not do, for the IT team reviewing it: four equal cells in one row,
 * directly above the call to action, so nobody reaches the button without having read them.
 * The home page answers the same limits as plain questions (see Faq.tsx).
 *
 * The four limits are carried over verbatim from the previous home page.
 */

const LIMITS: ReadonlyArray<string> = [
  'Agents are not autonomous. They stop at every outbound or destructive action and wait for a person.',
  'Answers are only as good as the documents you connect. With nothing indexed, an agent will tell you it cannot answer.',
  'The offline model is a placeholder. It returns sensible-looking text so the platform can be tried, and says so wherever it answers.',
  'Connecting a live tool account needs an administrator. Until then every server runs against a sandbox and nothing leaves your machine.',
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
