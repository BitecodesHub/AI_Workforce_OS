import type { ReactElement } from 'react'
import { Icon } from '../shared/Icon'
import type { IconName } from '../shared/Icon'
import { LandingSection, Reveal, SectionHead } from '../shared/LandingSection'
import { PROVIDERS } from '../shared/landingFacts'
import { ROLE_LABEL, ROLE_ORDER, holdsAll } from '../roles/roleData'
import type { RoleId } from '../roles/roleData'

/*
 * Why it is safe to switch on, for someone who will never read a permission code.
 *
 * Six promises, each one the platform keeps: approvals on every send and delete, roles, the
 * tamper-evident record, answers that point to their source, provider fallback, and schedules.
 * Then who can do what, as a small table. Its ticks are computed from roleData.ts, the same
 * source the page for IT teams draws its permission map from, so the two can never disagree.
 * Every row is something a person can actually do in the console today.
 */

const PROMISES: ReadonlyArray<{ icon: IconName; title: string; body: string }> = [
  {
    icon: 'gate',
    title: 'Nothing is sent without a yes',
    body: 'Sending an email, posting a message or deleting something waits for a person who is allowed to approve it.',
  },
  {
    icon: 'lock',
    title: 'Everyone sees only their part',
    body: 'Five ready-made roles, from owner to viewer, decide who can ask, who can approve and who can only look. You can make your own.',
  },
  {
    icon: 'link',
    title: 'A record you can trust',
    body: 'Every approval decision and every finished job is written down against the person responsible, in a record built so that a changed entry can be detected.',
  },
  {
    icon: 'document',
    title: 'Answers you can check',
    body: 'Answers point to the page they came from. When your documents do not cover a question, it says so instead of guessing.',
  },
  {
    icon: 'route',
    title: 'Never stuck on one AI provider',
    body: `Works with ${PROVIDERS.length} AI providers, including OpenAI, Anthropic and Google Gemini. If one is busy, it moves on to the next.`,
  },
  {
    icon: 'clock',
    title: 'Routine work on a schedule',
    body: 'Set a timetable in plain words, such as every weekday at 9am, and pause it whenever you like.',
  },
]

/** What a person can do, in the words a manager would use; each row needs every listed code. */
const WHO_CAN: ReadonlyArray<{ label: string; codes: readonly string[] }> = [
  { label: 'Ask the AI team for work', codes: ['chat:use', 'task:create'] },
  { label: 'See the work as it happens', codes: ['run:read'] },
  { label: 'Approve what gets sent', codes: ['approval:decide'] },
  { label: 'Change how the assistants work', codes: ['agent:update'] },
  { label: 'Invite people and set their roles', codes: ['member:invite', 'role:update'] },
]

function Mark({ held, role, label }: { held: boolean; role: RoleId; label: string }): ReactElement {
  const action = label.charAt(0).toLowerCase() + label.slice(1)
  return (
    <td className="lp-who-cell" data-held={held ? 'true' : 'false'}>
      <span className="lp-who-mark" aria-hidden="true">
        <Icon name={held ? 'check' : 'dash'} size={14} />
      </span>
      <span className="visually-hidden">
        {held ? `${ROLE_LABEL[role]} can ${action}` : `${ROLE_LABEL[role]} cannot ${action}`}
      </span>
    </td>
  )
}

export function SafetySection(): ReactElement {
  return (
    <LandingSection id="safety" labelledBy="safety-title">
      <SectionHead
        eyebrow="Safe by design"
        title="Built so you stay in charge"
        titleId="safety-title"
        lead="The AI team does the work. People keep the decisions, and there is a record of every one."
      />

      <ul className="lp-promises">
        {PROMISES.map((promise, index) => (
          <Reveal key={promise.title} as="li" index={index} className="lp-promise lp-frost">
            <span className="lp-promise-icon">
              <Icon name={promise.icon} size={18} />
            </span>
            <h3 className="lp-h3">{promise.title}</h3>
            <p className="lp-promise-body">{promise.body}</p>
          </Reveal>
        ))}
      </ul>

      <Reveal className="lp-who lp-frost" labelledBy="who-title">
        <div className="lp-who-head">
          <h3 id="who-title" className="lp-h3">
            Who can do what
          </h3>
          <p className="lp-who-lead">The five roles every workspace starts with. Owners and admins can adjust them.</p>
        </div>
        {/* Focusable, so a keyboard can scroll the table on a phone, where it is wider than the screen. */}
        <div className="lp-who-scroll" role="region" aria-labelledby="who-title" tabIndex={0}>
          <table className="lp-who-table">
            <thead>
              <tr>
                <th scope="col">
                  <span className="visually-hidden">What a person can do</span>
                </th>
                {ROLE_ORDER.map((role) => (
                  <th key={role} scope="col">
                    {ROLE_LABEL[role]}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {WHO_CAN.map((row) => (
                <tr key={row.label}>
                  <th scope="row">{row.label}</th>
                  {ROLE_ORDER.map((role) => (
                    <Mark key={role} role={role} label={row.label} held={holdsAll(role, row.codes)} />
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Reveal>
    </LandingSection>
  )
}
