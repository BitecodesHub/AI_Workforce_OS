// @find: approval demo, approval gate, approvals queue, approve action, reject action, human in the loop, gmail.send_message, approval:decide, manager employee viewer, simulated agent run, landing demo, ApprovalDemo, #approval, deadline expire
// @what: The landing page demo that shows an agent run parking at an approval gate until a person with approval:decide answers.
// @flow: Used by DemoStage and the home page; state from approvalModel, payload shown by PayloadView, frame from DemoFrame
import { useEffect, useReducer, useRef } from 'react'
import type { CSSProperties, ReactElement, ReactNode, RefObject } from 'react'
import { Button, Eyebrow, Spinner, Tag } from '../../ui'
import type { TagTone } from '../../ui'
import { DemoFrame } from '../shared/DemoFrame'
import type { DemoVoice } from '../shared/voice'
import { SegmentedControl } from '../shared/SegmentedControl'
import { Icon } from '../shared/Icon'
import type { IconName } from '../shared/Icon'
import { useLandingMotion } from '../shared/LandingRoot'
import { useSequence } from '../../../hooks/useSequence'
import { useInView } from '../../../hooks/useInView'
import {
  PAYLOAD,
  STEP_COPY,
  STEP_ORDER,
  approvalReducer,
  approveSteps,
  canDecide,
  expireSteps,
  initApprovalState,
  startSteps,
} from './approvalModel'
import type { ApprovalState, CardStatus, Decider, Phase, StepId, StepState } from './approvalModel'
import { PayloadView } from './PayloadView'

/*
 * Demo 01: the approval gate.
 *
 * A simulated HR agent run drafts freely, then parks before gmail.send_message and shows exactly
 * what would be sent. The visitor decides as a manager, sees the approval without the power to
 * decide as an employee, or does not see the queue at all as a viewer. Letting the deadline pass
 * needs no permission, and it cancels the run: nothing is sent by default.
 */

const LEAD =
  'Reads and drafts run freely. Anything outbound or destructive, such as gmail.send_message, slack.post_message or calendar.delete_event, parks the run until a person with approval:decide answers.'

const DECIDERS: ReadonlyArray<{ value: Decider; label: string }> = [
  { value: 'manager', label: 'Manager' },
  { value: 'employee', label: 'Employee' },
  { value: 'viewer', label: 'Viewer' },
]

type TagView = { tone: TagTone; label: string }

const STEP_VIEW: Record<StepState, { glyph: IconName | 'spinner' | null; tag: TagView | null }> = {
  ghost: { glyph: null, tag: null },
  active: { glyph: 'spinner', tag: { tone: 'blue', label: 'Running' } },
  done: { glyph: 'check', tag: { tone: 'success', label: 'Done' } },
  waiting: { glyph: 'pause', tag: { tone: 'warning', label: 'Waiting for approval' } },
  approved: { glyph: 'check', tag: { tone: 'success', label: 'Approved' } },
  rejected: { glyph: 'x', tag: { tone: 'danger', label: 'Rejected' } },
  expired: { glyph: 'clock', tag: { tone: 'neutral', label: 'Expired' } },
  skipped: { glyph: 'dash', tag: { tone: 'neutral', label: 'Not run' } },
}

const RUN_STATUS: Record<Phase, TagView> = {
  idle: { tone: 'neutral', label: 'Not started' },
  running: { tone: 'blue', label: 'Running' },
  parked: { tone: 'warning', label: 'Waiting for approval' },
  expiring: { tone: 'warning', label: 'Waiting for approval' },
  approving: { tone: 'blue', label: 'Resuming' },
  completed: { tone: 'success', label: 'Completed' },
  rejected: { tone: 'danger', label: 'Cancelled' },
  expired: { tone: 'neutral', label: 'Cancelled' },
}

const CARD_STATUS: Record<Exclude<CardStatus, 'hidden'>, TagView> = {
  waiting: { tone: 'warning', label: 'Waiting for approval' },
  approved: { tone: 'success', label: 'Approved' },
  rejected: { tone: 'danger', label: 'Rejected' },
  expired: { tone: 'neutral', label: 'Expired' },
}

type Outcome = 'completed' | 'rejected' | 'expired'

const RESULT: Record<Outcome, { icon: IconName; title: string; body: string }> = {
  completed: {
    icon: 'check',
    title: 'Sent, then completed',
    body: 'Approved by you, as a manager. The run resumed and completed. The email went to the sandbox gmail driver, so nothing left your browser.',
  },
  rejected: {
    icon: 'x',
    title: 'Run cancelled',
    body: 'An approver rejected the action this run needed. Nothing was sent.',
  },
  expired: {
    icon: 'clock',
    title: 'Run cancelled',
    body: 'Nobody decided in time, so the approval was rejected by default. Nothing is sent because a deadline passed.',
  },
}

/* ---- Plain voice -------------------------------------------------------------------------
 * The home page speaks to people choosing a product, not to engineers: the same run and the same
 * decisions, described without tool names or permission codes, and the email shown as an email.
 * The technical voice, kept for the page for IT teams, shows exactly what the platform logs.
 */

const PLAIN_LEAD =
  'Reading and drafting happen on their own. Sending an email, posting a message or deleting something stops here and waits for a person to approve it.'

const PLAIN_STEP_COPY: Record<StepId, { label: string; detail: string }> = {
  plan: { label: 'Plans the email', detail: 'Reads the new starter’s details and works out what to say.' },
  draft: { label: 'Writes a draft', detail: 'Drafting happens on its own.' },
  gate: { label: 'Asks before sending', detail: 'Sending leaves the business, so it waits here for a person.' },
  send: { label: 'Sends the email', detail: 'In this demo it goes to a practice mailbox. Nothing leaves your browser.' },
  summary: { label: 'Wraps up', detail: 'Writes a short note of what it did.' },
}

const PLAIN_RESULT: Record<Outcome, { icon: IconName; title: string; body: string }> = {
  completed: {
    icon: 'check',
    title: 'Approved and sent',
    body: 'You approved it as a manager, so the email went out and the job finished. In this demo it went to a practice mailbox, so nothing left your browser.',
  },
  rejected: { icon: 'x', title: 'Stopped. Nothing was sent', body: 'The request was rejected, so the email was never sent.' },
  expired: {
    icon: 'clock',
    title: 'Stopped. Nothing was sent',
    body: 'Nobody answered in time, so the request expired and the email was never sent.',
  },
}

const PLAIN_EMPLOYEE_NOTE = 'An employee can see this request but cannot approve it. Managers, admins and owners can.'

const PLAIN_VIEWER_NOTE =
  'A viewer does not see approval requests at all. The email waits until someone who can approve it answers.'

const PLAIN_STEPS = [
  'Pick who is deciding: a manager, an employee or a viewer.',
  'Approve or reject the email, or let the deadline pass.',
  'See what happens next: the email is sent, or safely stopped.',
] as const

const TECHNICAL_STEPS = [
  'Choose who is deciding: Manager, Employee or Viewer.',
  'Approve or reject the email, or let the deadline pass.',
  'Watch the run carry on, or stop, because of that decision.',
] as const

type Copy = {
  name: string
  title: string
  lead: string
  footnote: string
  steps: readonly string[]
  legend: string
  start: string
  runHeading: string
  cardEyebrow: string
  stepCopy: Record<StepId, { label: string; detail: string }>
  result: Record<Outcome, { icon: IconName; title: string; body: string }>
  employeeNote: string
  viewerNote: string
}

const EMPLOYEE_NOTE =
  'Your role can see this approval but cannot decide it. Deciding needs approval:decide, which the manager, admin and owner roles hold.'

const VIEWER_NOTE =
  'A viewer does not see the approval queue, because the role lacks approval:read. The run stays parked until someone who holds approval:decide answers.'

const COPY: Record<DemoVoice, Copy> = {
  technical: {
    name: 'Approval gate',
    title: 'It asks before it acts',
    lead: LEAD,
    footnote: 'Time is compressed. A real approval waits until its deadline.',
    steps: TECHNICAL_STEPS,
    legend: 'Deciding as',
    start: 'Start the run',
    runHeading: 'Run · HR agent',
    cardEyebrow: 'Approval requested · gmail.send_message',
    stepCopy: STEP_COPY,
    result: RESULT,
    employeeNote: EMPLOYEE_NOTE,
    viewerNote: VIEWER_NOTE,
  },
  plain: {
    name: 'Approvals',
    title: 'It asks before it sends',
    lead: PLAIN_LEAD,
    footnote: 'Time is sped up here. A real request waits until its deadline.',
    steps: PLAIN_STEPS,
    legend: 'Who is deciding',
    start: 'Start the demo',
    runHeading: 'HR assistant at work',
    cardEyebrow: 'Approval needed · outgoing email',
    stepCopy: PLAIN_STEP_COPY,
    result: PLAIN_RESULT,
    employeeNote: PLAIN_EMPLOYEE_NOTE,
    viewerNote: PLAIN_VIEWER_NOTE,
  },
}

const BAR_STYLE = { '--lp-bar-ms': '1200ms' } as CSSProperties

function outcomeOf(phase: Phase): Outcome | null {
  return phase === 'completed' || phase === 'rejected' || phase === 'expired' ? phase : null
}

function deadlineLabel(state: ApprovalState): string {
  if (state.phase === 'expiring') return 'Deadline passing'
  if (state.card === 'expired') return 'Deadline passed'
  return 'Real deadline: 24 h, sped up here'
}

function deadlineRun(state: ApprovalState): 'full' | 'drain' | 'empty' {
  if (state.phase === 'expiring') return 'drain'
  if (state.card === 'expired') return 'empty'
  return 'full'
}

function StepRow({
  id,
  stepState,
  stepCopy,
}: {
  id: StepId
  stepState: StepState
  stepCopy: Copy['stepCopy']
}): ReactElement {
  const copy = stepCopy[id]
  const view = STEP_VIEW[stepState]
  let glyph: ReactNode = null
  if (view.glyph === 'spinner') glyph = <Spinner size={12} />
  else if (view.glyph) glyph = <Icon name={view.glyph} size={14} />
  return (
    <li className="lp-run-step" data-state={stepState}>
      <span className="lp-run-marker" aria-hidden="true">
        {glyph && (
          <span key={stepState} className="lp-run-glyph lp-anim-tick">
            {glyph}
          </span>
        )}
      </span>
      <div className="lp-run-step-main">
        <div className="lp-run-step-top">
          <span className="lp-run-step-label">{copy.label}</span>
          {view.tag ? (
            <span className="lp-run-step-tag">
              <Tag tone={view.tag.tone}>{view.tag.label}</Tag>
            </span>
          ) : (
            <span className="visually-hidden">Not started</span>
          )}
        </div>
        <p className="lp-run-step-detail">{copy.detail}</p>
      </div>
    </li>
  )
}

function RunResult({
  outcome,
  headingRef,
  onReset,
  result,
}: {
  outcome: Outcome
  headingRef: RefObject<HTMLHeadingElement | null>
  onReset: () => void
  result: Copy['result']
}): ReactElement {
  const copy = result[outcome]
  return (
    <div className="lp-run-result lp-anim-fade" data-outcome={outcome}>
      <h4 ref={headingRef} tabIndex={-1} className="lp-run-result-title">
        <Icon name={copy.icon} size={16} />
        {copy.title}
      </h4>
      <p className="lp-run-result-body">{copy.body}</p>
      <div className="lp-demo-actions">
        <Button variant="outline" icon={<Icon name="reset" />} onClick={onReset}>
          Run it again
        </Button>
      </div>
    </div>
  )
}

/** The email the approval would release, set out as an email rather than as data. */
function EmailPreview({ payload }: { payload: typeof PAYLOAD }): ReactElement {
  return (
    <dl className="lp-email" aria-label="Email that will be sent">
      <div>
        <dt>To</dt>
        <dd>{payload.to}</dd>
      </div>
      <div>
        <dt>Subject</dt>
        <dd>{payload.subject}</dd>
      </div>
      <div>
        <dt>Message</dt>
        <dd>{payload.body}</dd>
      </div>
    </dl>
  )
}

export type ApprovalDemoProps = { voice?: DemoVoice }

// @find: ApprovalDemo component, approval gate demo
export function ApprovalDemo({ voice = 'technical' }: ApprovalDemoProps = {}): ReactElement {
  const copy = COPY[voice]
  const { reduced } = useLandingMotion()
  const [state, dispatch] = useReducer(approvalReducer, reduced, initApprovalState)
  const { play, cancel } = useSequence()
  const { ref: stageRef, inView } = useInView<HTMLDivElement>({ threshold: 0.45 })

  const timelineHeadingRef = useRef<HTMLHeadingElement>(null)
  const cardHeadingRef = useRef<HTMLHeadingElement>(null)
  const resultHeadingRef = useRef<HTMLHeadingElement>(null)

  // Autoplay once the stage is mostly on screen. Guarded by reducer state, never a ref, so a
  // StrictMode replay that cleared the first timers simply plays again.
  useEffect(() => {
    if (inView && state.phase === 'idle' && !state.autoplayed) play(startSteps(dispatch, false))
  }, [inView, state.phase, state.autoplayed, play])

  // Focus moves only when the reducer asks, and the reducer asks only after a user action.
  const { target: focusTarget, nonce: focusNonce } = state.focus
  useEffect(() => {
    if (focusNonce === 0 || focusTarget === 'none') return
    const node =
      focusTarget === 'timeline'
        ? timelineHeadingRef.current
        : focusTarget === 'card'
          ? cardHeadingRef.current
          : resultHeadingRef.current
    // preventScroll matters here specifically: this fires 1.2-2s after the action that requested
    // it (the sequence's own pacing), long enough for a visitor to have scrolled on to the next
    // demo. A plain focus() would drag the page back to a card they have already left.
    node?.focus({ preventScroll: true })
  }, [focusTarget, focusNonce])

  const { phase, decider, card } = state
  const outcome = outcomeOf(phase)
  const busy = phase === 'running' || phase === 'approving' || phase === 'expiring'
  // While a decision is being carried out the triggers stay in place, focusable but inert.
  const locked = phase !== 'parked'
  const lockProps = locked ? ({ 'aria-disabled': true } as const) : {}

  const onStart = () => {
    if (phase !== 'idle') return
    play(startSteps(dispatch, true))
  }
  const onApprove = () => {
    if (phase !== 'parked' || !canDecide(decider)) return
    play(approveSteps(dispatch))
  }
  const onReject = () => {
    if (phase !== 'parked' || !canDecide(decider)) return
    cancel()
    dispatch({ type: 'REJECT' })
  }
  const onLetExpire = () => {
    if (phase !== 'parked') return
    play(expireSteps(dispatch))
  }
  const onReset = () => {
    dispatch({ type: 'RESET' })
    play(startSteps(dispatch, true))
  }

  const runStatus = RUN_STATUS[phase]
  const letPass = (
    <Button variant="quiet" icon={<Icon name="clock" />} onClick={onLetExpire} {...lockProps}>
      Let the deadline pass
    </Button>
  )
  const result = outcome ? (
    <RunResult outcome={outcome} headingRef={resultHeadingRef} onReset={onReset} result={copy.result} />
  ) : null

  // A viewer lacks approval:read, so before a decision exists the card must not reveal what it
  // is an approval for - showing the exact request while saying "you cannot see this" contradicts
  // itself. Once it is decided, the outcome is an ordinary fact anyone can read.
  const viewerBlind = card !== 'hidden' && decider === 'viewer' && !outcome

  let cardBody: ReactNode = null
  if (card !== 'hidden') {
    const cardStatus = CARD_STATUS[card]
    if (decider === 'viewer') {
      cardBody = result ?? (
        <>
          <p className="lp-approval-note">
            <Icon name="lock" />
            <span>{copy.viewerNote}</span>
          </p>
          <div className="lp-demo-actions lp-approval-actions">{letPass}</div>
        </>
      )
    } else {
      let actions: ReactNode
      if (result) {
        actions = result
      } else if (canDecide(decider)) {
        actions = (
          <div className="lp-demo-actions lp-approval-actions">
            <Button variant="primary" icon={<Icon name="check" />} onClick={onApprove} {...lockProps}>
              Approve
            </Button>
            <Button variant="outline" onClick={onReject} {...lockProps}>
              Reject
            </Button>
            {letPass}
          </div>
        )
      } else {
        actions = (
          <>
            <p className="lp-approval-note">
              <Icon name="lock" />
              <span>{copy.employeeNote}</span>
            </p>
            <div className="lp-demo-actions lp-approval-actions">{letPass}</div>
          </>
        )
      }
      cardBody = (
        <>
          <div className="lp-approval-meta">
            <Tag tone="operations" withDot>
              HR
            </Tag>
            <span className="lp-mono">Requested just now</span>
            <span className="lp-mono">{deadlineLabel(state)}</span>
            <Tag tone={cardStatus.tone}>{cardStatus.label}</Tag>
          </div>
          <div className="lp-bar lp-approval-deadline" aria-hidden="true">
            <span className="lp-bar-fill" data-run={deadlineRun(state)} style={BAR_STYLE} />
          </div>
          <div className="lp-approval-payload">
            <p className="lp-micro">Exactly what will be sent</p>
            {voice === 'plain' ? (
              <EmailPreview payload={PAYLOAD} />
            ) : (
              <PayloadView payload={PAYLOAD} label="Email that will be sent" />
            )}
          </div>
          {actions}
        </>
      )
    }
  }

  return (
    <DemoFrame
      area="approval"
      index="01"
      name={copy.name}
      title={copy.title}
      lead={copy.lead}
      status={state.announcement}
      footnote={copy.footnote}
      steps={copy.steps}
      showIndex={voice === 'technical'}
      busy={busy}
    >
      <div className="lp-run-controls">
        <div className="lp-run-decider">
          <SegmentedControl
            legend={copy.legend}
            value={decider}
            options={DECIDERS}
            onChange={(next) => dispatch({ type: 'SET_DECIDER', decider: next })}
            fullWidth
          />
        </div>
        {phase === 'idle' && !state.autoplayed && (
          <Button variant="primary" className="lp-run-start" icon={<Icon name="play" />} onClick={onStart}>
            {copy.start}
          </Button>
        )}
      </div>

      <div ref={stageRef} className="lp-run-stage">
        <section className="lp-run-timeline lp-inset" aria-labelledby="run-timeline-title">
          <div className="lp-run-timeline-head">
            <h4 id="run-timeline-title" ref={timelineHeadingRef} tabIndex={-1} className="lp-mono">
              {copy.runHeading}
            </h4>
            <Tag tone={runStatus.tone}>{runStatus.label}</Tag>
          </div>
          <ol className="lp-run-steps">
            {STEP_ORDER.map((id) => (
              <StepRow key={id} id={id} stepState={state.steps[id]} stepCopy={copy.stepCopy} />
            ))}
          </ol>
        </section>

        <div className="lp-run-card-slot">
          {card === 'hidden' ? (
            <p className="lp-run-placeholder">
              The approval will appear here when the run reaches gmail.send_message.
            </p>
          ) : (
            <article
              className="lp-approval lp-anim-rise"
              data-status={card}
              aria-labelledby="approval-card-title"
            >
              <Eyebrow>{viewerBlind ? 'Approval pending' : copy.cardEyebrow}</Eyebrow>
              <h4
                id="approval-card-title"
                ref={cardHeadingRef}
                tabIndex={-1}
                className="lp-approval-title"
              >
                {viewerBlind ? 'Waiting on someone else’s screen' : 'Send the welcome email to the new hire'}
              </h4>
              {cardBody}
            </article>
          )}
        </div>
      </div>
    </DemoFrame>
  )
}
