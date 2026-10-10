// @find: how it works, three steps, assistants, chat, approvals, agent templates, HowItWorks, #how
// @what: Three-step explanation of how the product works for buyers.
// @flow: Used by the home page
import type { ReactElement } from 'react'
import { Icon } from '../shared/Icon'
import type { IconName } from '../shared/Icon'
import { LandingSection, Reveal, SectionHead } from '../shared/LandingSection'

/*
 * How it works, in three steps a person choosing the product can repeat to a colleague.
 *
 * Each step names something the product really does: ready-made assistants and new ones briefed
 * in plain words; Chat, typed or spoken, that routes work to the right assistant and hands it on;
 * and the approval every send, post and delete waits for. The four ready-made assistants are in
 * every workspace's catalogue (AgentTemplates.java, and lib/templates.ts for the signup step), so
 * the first step may say so.
 */

const STEPS: ReadonlyArray<{ icon: IconName; title: string; body: string }> = [
  {
    icon: 'check',
    title: 'Choose your assistants',
    body: 'Start with ready-made assistants for HR, customer support, research and engineering, or brief a new one the way you would brief a new hire.',
  },
  {
    icon: 'route',
    title: 'Ask in plain words',
    body: 'Type or say what you need. The right assistant picks it up, and passes it to a colleague when the job needs more than one.',
  },
  {
    icon: 'gate',
    title: 'Approve what matters',
    body: 'Emails, messages and deletions wait for your yes. Approve in one click, or let the request expire and nothing is sent.',
  },
]

// @find: HowItWorks component, how it works section
export function HowItWorks(): ReactElement {
  return (
    <LandingSection id="how" labelledBy="how-title">
      <SectionHead
        eyebrow="How it works"
        title="Up and running in three steps"
        titleId="how-title"
        lead="No code and no training course. If you can brief a colleague, you can brief an AI assistant."
      />
      <ol className="lp-how">
        {STEPS.map((step, index) => (
          <Reveal key={step.title} as="li" index={index} className="lp-how-step lp-frost">
            <span className="lp-how-top">
              <span className="lp-how-number" aria-hidden="true">
                {index + 1}
              </span>
              <span className="lp-how-icon">
                <Icon name={step.icon} size={18} />
              </span>
            </span>
            <h3 className="lp-h3 lp-how-title">{step.title}</h3>
            <p className="lp-how-body">{step.body}</p>
          </Reveal>
        ))}
      </ol>
    </LandingSection>
  )
}
