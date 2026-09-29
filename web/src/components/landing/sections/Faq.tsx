import type { ReactElement } from 'react'
import { Icon } from '../shared/Icon'
import { LandingSection, Reveal, SectionHead } from '../shared/LandingSection'
import { PROVIDERS, TOOL_LABEL, TOOL_SERVERS } from '../shared/landingFacts'

/*
 * The questions a buyer asks, answered plainly.
 *
 * This is where the page's limits live now: the offline model, the sandboxed tools, what happens
 * without documents and who may approve. Each answer is true of the platform as it ships; none
 * promises a live connection to an outside account, because none is made today.
 *
 * Native details elements, so each question opens with a click, Enter or Space, and a screen
 * reader announces it as expanded or collapsed without any script.
 */

function listOf(items: readonly string[]): string {
  if (items.length < 2) return items.join('')
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

const QUESTIONS: ReadonlyArray<{ q: string; a: string }> = [
  {
    q: 'Will it send anything without asking?',
    a: 'No. Sending an email, posting a message or deleting something always waits for a person who is allowed to approve it. If nobody answers before the deadline, the request expires and nothing is sent.',
  },
  {
    q: 'Do I need technical skills to use it?',
    a: 'No. You ask for work in plain words in Chat, typed or spoken. The one-off set-up, such as choosing which AI provider to use, is done once by whoever looks after your workspace.',
  },
  {
    q: 'What happens when it does not know something?',
    a: 'It tells you. Answers come from the documents you add, with the page each point came from. When nothing in your documents covers the question, it says it cannot answer rather than guessing.',
  },
  {
    q: 'Can we try it before connecting anything?',
    a: 'Yes. Out of the box it runs on a built-in sandbox model that gives practice answers, clearly labelled as such, and every tool works in a sandbox, so nothing real is sent. The demo accounts let you sign in as a manager, an employee or a viewer and see the difference.',
  },
  {
    q: 'Which AI does it use?',
    a: `The one you choose: ${listOf([...PROVIDERS])}. You set the order, and if one is busy or unavailable it moves on to the next.`,
  },
  {
    q: 'Does it work with our email, chat and calendar?',
    a: `It comes with connectors for ${listOf(TOOL_SERVERS.map((server) => TOOL_LABEL[server]))}. Today they run in a sandbox, so nothing real is sent while you try it.`,
  },
  {
    q: 'Who can see and approve things?',
    a: 'Each person gets a role. An employee can hand work to the AI team but cannot approve what it sends, and a viewer can look at reports but change nothing. Owners and admins can shape the roles to fit your team.',
  },
]

export function Faq(): ReactElement {
  return (
    <LandingSection id="faq" labelledBy="faq-title">
      <SectionHead eyebrow="Questions" title="What people ask before they start" titleId="faq-title" />
      <Reveal className="lp-faq">
        {QUESTIONS.map((item) => (
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
