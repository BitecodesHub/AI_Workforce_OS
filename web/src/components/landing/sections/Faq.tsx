// @find: faq, frequently asked questions, buyer questions, limits answered, offline model, email and calendar, connectors, practice data, who may approve, who is it for, how to start, Faq, #faq
// @what: Plain-words questions and answers for buyers, built on native details elements.
// @flow: Used by the home page; the questions and answers come from faqData.ts, which the FAQPage structured data also reads
import type { ReactElement } from 'react'
import { Icon } from '../shared/Icon'
import { LandingSection, Reveal, SectionHead } from '../shared/LandingSection'
import { FAQ_ITEMS } from './faqData'

/*
 * The questions a buyer asks, answered plainly. The questions and answers live in faqData.ts,
 * which the structured data for search and answer engines reads too, so the two cannot disagree.
 *
 * Native details elements, so each question opens with a click, Enter or Space, and a screen
 * reader announces it as expanded or collapsed without any script. A closed answer is still in
 * the page's HTML, so a crawler that does not run JavaScript reads every one.
 */

// @find: Faq component, FAQ section
/** Home page section "Questions" (id faq). Renders each question from FAQ_ITEMS as a native details and summary element inside a Reveal, so it opens with a click, Enter or Space and is announced as expanded or collapsed without script. Static: no state, no requests. */
export function Faq(): ReactElement {
  return (
    <LandingSection id="faq" labelledBy="faq-title">
      <SectionHead eyebrow="Questions" title="What people ask before they start" titleId="faq-title" />
      <Reveal className="lp-faq">
        {FAQ_ITEMS.map((item) => (
          <details key={item.q} className="lp-faq-item">
            <summary className="lp-faq-q">
              <span>{item.q}</span>
              <span className="lp-faq-toggle" aria-hidden="true">
                <Icon name="arrow-right" size={16} />
              </span>
            </summary>
            <p className="lp-faq-a">{item.a}</p>
          </details>
        ))}
      </Reveal>
    </LandingSection>
  )
}
