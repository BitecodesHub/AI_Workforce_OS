// @find: how it works, three steps, interactive steps, choose assistants, ask in plain words, approve what matters, HowItWorks, #how
// @what: The three-step "How it works" section, where each step opens a small scene the visitor can use.
// @flow: Uses landingFacts AGENTS; styled by styles/landing/sky.css (.lp-steps-*)
import { useRef, useState } from 'react'
import type { KeyboardEvent, ReactElement } from 'react'
import { Icon } from '../shared/Icon'
import type { IconName } from '../shared/Icon'
import { AGENTS } from '../shared/landingFacts'
import type { AgentId } from '../shared/landingFacts'
import { LandingSection, Reveal, SectionHead } from '../shared/LandingSection'

/*
 * Three steps, each a tab. Choosing one opens a scene beside it: pick the assistants for the team,
 * send a request and see who picks it up, then approve or reject what it wants to send. Every
 * scene is an illustration, labelled as one, and sends nothing. The wording of what each
 * assistant does on its own and what waits for a person matches the assistants section and the
 * shipped templates.
 */

/** The three steps: choose, ask and approve. */
type StepId = 'choose' | 'ask' | 'approve'

/** The three steps with icon, title and body. */
const STEPS: ReadonlyArray<{ id: StepId; icon: IconName; title: string; body: string }> = [
  {
    id: 'choose',
    icon: 'check',
    title: 'Choose your assistants',
    body: 'Start with ready-made assistants for HR, customer support, research and engineering, or brief a new one the way you would brief a new hire.',
  },
  {
    id: 'ask',
    icon: 'route',
    title: 'Ask in plain words',
    body: 'Type or say what you need. The right assistant picks it up, and passes it to a colleague when the job needs more than one.',
  },
  {
    id: 'approve',
    icon: 'gate',
    title: 'Approve what matters',
    body: 'Emails, messages and deletions wait for your yes. Approve in one click, or let the request expire and nothing is sent.',
  },
]

/** Example requests for the ask scene: the text, the assistant that picks it up, what it does on its own and what waits for a person. The wording matches the assistants section and the shipped templates. */
const REQUESTS: ReadonlyArray<{ text: string; agent: AgentId; alone: string; waits: string }> = [
  {
    text: 'Draft the welcome email for our new starter',
    agent: 'hr',
    alone: 'Reads the offer and drafts the email.',
    waits: 'Sending it waits for your yes.',
  },
  {
    text: 'Sum up the open pull requests',
    agent: 'eng',
    alone: 'Reads the pull requests and writes the summary.',
    waits: 'Posting it to Slack waits for your yes.',
  },
  {
    text: 'What do our documents say about pricing?',
    agent: 'research',
    alone: 'Reads your files and names the source of each point.',
    waits: 'It writes a new document, and has no way to share it.',
  },
  {
    text: 'Reply to the overnight customer emails',
    agent: 'support',
    alone: 'Drafts each reply from your support handbook.',
    waits: 'Each reply waits for your yes before it is sent.',
  },
]

/** Up to two capital initials of a name, for the avatar. */
function initials(name: string): string {
  return name
    .split(' ')
    .map((word) => word[0])
    .join('')
    .slice(0, 2)
    .toUpperCase()
}

// @find: HowItWorks component, interactive three steps
/** Home page section "Up and running in three steps" (id how). Three steps are vertical ARIA tabs (arrow keys, Home and End move and select, roving tab index); the selected step opens one illustrative scene in the panel beside it, labelled "Illustration. Nothing is sent." State: the selected step, and each scene keeps its own state (a scene is remounted, so it starts fresh, when its tab is chosen). Static data only; no requests. Rendered by the Landing route. */
export function HowItWorks(): ReactElement {
  const [step, setStep] = useState<StepId>('choose')
  const tabs = useRef<Partial<Record<StepId, HTMLButtonElement | null>>>({})

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const last = STEPS.length - 1
    let next: number
    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowRight':
        next = index === last ? 0 : index + 1
        break
      case 'ArrowUp':
      case 'ArrowLeft':
        next = index === 0 ? last : index - 1
        break
      case 'Home':
        next = 0
        break
      case 'End':
        next = last
        break
      default:
        return
    }
    event.preventDefault()
    const target = STEPS[next]
    if (target) {
      setStep(target.id)
      tabs.current[target.id]?.focus()
    }
  }

  return (
    <LandingSection id="how" labelledBy="how-title">
      <SectionHead
        eyebrow="How it works"
        title="Up and running in three steps"
        titleId="how-title"
        lead="No code and no training course. If you can brief a colleague, you can brief an AI assistant. Pick a step to try it."
      />
      <Reveal className="lp-steps">
        <div role="tablist" aria-label="The three steps" aria-orientation="vertical" className="lp-steps-list">
          {STEPS.map((item, index) => {
            const selected = item.id === step
            return (
              <button
                key={item.id}
                ref={(node) => {
                  tabs.current[item.id] = node
                }}
                type="button"
                role="tab"
                id={`how-tab-${item.id}`}
                aria-controls={`how-panel-${item.id}`}
                aria-selected={selected}
                tabIndex={selected ? 0 : -1}
                className="lp-steps-tab"
                onClick={() => setStep(item.id)}
                onKeyDown={(event) => onKeyDown(event, index)}
              >
                <span className="lp-steps-number" aria-hidden="true">
                  {String(index + 1).padStart(2, '0')}
                </span>
                <span className="lp-steps-text">
                  <span className="lp-steps-title">
                    <Icon name={item.icon} size={16} />
                    {item.title}
                  </span>
                  <span className="lp-steps-body">{item.body}</span>
                </span>
              </button>
            )
          })}
        </div>

        <div
          role="tabpanel"
          id={`how-panel-${step}`}
          aria-labelledby={`how-tab-${step}`}
          className="lp-steps-scene"
          tabIndex={0}
        >
          <p className="lp-steps-badge">
            <span className="lp-dot lp-dot-green" aria-hidden="true" />
            Illustration. Nothing is sent.
          </p>
          {step === 'choose' && <ChooseScene key="choose" />}
          {step === 'ask' && <AskScene key="ask" />}
          {step === 'approve' && <ApproveScene key="approve" />}
        </div>
      </Reveal>
    </LandingSection>
  )
}

/** Scene for step one: toggle buttons for the four example assistants (aria-pressed, starting with HR on the team) and a polite note counting the team. Local state only. */
function ChooseScene(): ReactElement {
  const [team, setTeam] = useState<ReadonlySet<AgentId>>(() => new Set<AgentId>(['hr']))
  const toggle = (id: AgentId) =>
    setTeam((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  return (
    <div className="lp-scene lp-anim-fade">
      <h3 className="lp-scene-title">Who should join your team?</h3>
      <div className="lp-scene-agents">
        {AGENTS.map((agent) => {
          const added = team.has(agent.id)
          return (
            <button
              key={agent.id}
              type="button"
              className="lp-scene-agent"
              aria-pressed={added}
              onClick={() => toggle(agent.id)}
            >
              <span className="lp-scene-avatar" data-category={agent.category} aria-hidden="true">
                {initials(agent.name)}
              </span>
              <span className="lp-scene-agent-text">
                <span className="lp-scene-agent-name">{agent.name}</span>
                <span className="lp-scene-agent-role">{agent.title}</span>
              </span>
              <span className="lp-scene-agent-state" aria-hidden="true">
                <Icon name={added ? 'check' : 'dash'} size={14} />
              </span>
            </button>
          )
        })}
      </div>
      <p className="lp-scene-note" aria-live="polite">
        {team.size === 0
          ? 'Nobody on the team yet. Pick an assistant to add it.'
          : `${team.size} ${team.size === 1 ? 'assistant' : 'assistants'} on your team. Each one can be changed after it is added.`}
      </p>
    </div>
  )
}

/** Scene for step two: a radio group of four example requests; choosing one shows the request as a bubble and which assistant picks it up, with what it does on its own and what waits for a person. The thread is a polite live region and is re-keyed per choice so it fades in. */
function AskScene(): ReactElement {
  const [chosen, setChosen] = useState(0)
  const request = REQUESTS[chosen] ?? REQUESTS[0]!
  const agent = AGENTS.find((item) => item.id === request.agent) ?? AGENTS[0]!

  return (
    <div className="lp-scene lp-anim-fade">
      <h3 className="lp-scene-title">Try a request</h3>
      <div className="lp-scene-prompts" role="radiogroup" aria-label="Example requests">
        {REQUESTS.map((item, index) => (
          <button
            key={item.text}
            type="button"
            role="radio"
            aria-checked={index === chosen}
            className="lp-chip"
            onClick={() => setChosen(index)}
          >
            {item.text}
          </button>
        ))}
      </div>
      <div className="lp-scene-thread" aria-live="polite" aria-atomic="true">
        <p key={`ask-${chosen}`} className="lp-scene-bubble lp-anim-fade">
          {request.text}
        </p>
        <div key={`route-${chosen}`} className="lp-scene-route lp-anim-fade">
          <span className="lp-scene-avatar" data-category={agent.category} aria-hidden="true">
            {initials(agent.name)}
          </span>
          <div>
            <p className="lp-scene-route-head">
              <strong>{agent.name}</strong> picks this up
            </p>
            <p className="lp-scene-line">
              <Icon name="check" size={14} />
              {request.alone}
            </p>
            <p className="lp-scene-line lp-scene-line-wait">
              <Icon name="lock" size={14} />
              {request.waits}
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}

/** The approval scene's state: waiting, approved or rejected. */
type Decision = 'waiting' | 'approved' | 'rejected'

/** Scene for step three: an example email the HR assistant wants to send, with Approve and Reject buttons. A decision shows what would happen (sent, or nothing sent and the assistant stops) in a polite live region, with a Try again button. Nothing is sent. */
function ApproveScene(): ReactElement {
  const [decision, setDecision] = useState<Decision>('waiting')

  return (
    <div className="lp-scene lp-anim-fade">
      <h3 className="lp-scene-title">HR wants to send an email</h3>
      <dl className="lp-scene-mail">
        <div>
          <dt>To</dt>
          <dd>The new starter</dd>
        </div>
        <div>
          <dt>Subject</dt>
          <dd>Welcome to the team</dd>
        </div>
        <div>
          <dt>Message</dt>
          <dd>Hello, and welcome aboard. Your first day starts at 9am, and your manager will meet you at reception.</dd>
        </div>
      </dl>
      <div aria-live="polite" aria-atomic="true">
        {decision === 'waiting' ? (
          <div className="lp-scene-actions">
            <button type="button" className="lp-sky-button lp-sky-button-light" onClick={() => setDecision('approved')}>
              Approve
            </button>
            <button type="button" className="lp-sky-button lp-sky-button-glass" onClick={() => setDecision('rejected')}>
              Reject
            </button>
          </div>
        ) : (
          <div className="lp-scene-result lp-anim-fade" data-decision={decision}>
            <Icon name={decision === 'approved' ? 'check' : 'x'} size={16} />
            <span>
              {decision === 'approved'
                ? 'Approved. In the product the email is sent now, exactly as shown.'
                : 'Rejected. Nothing is sent, and the assistant stops.'}
            </span>
            <button type="button" className="lp-scene-reset" onClick={() => setDecision('waiting')}>
              Try again
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
