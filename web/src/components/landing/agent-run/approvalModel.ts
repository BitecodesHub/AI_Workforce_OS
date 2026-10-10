// @find: approval model, approval state machine, approvalReducer, canDecide, approval:decide, approve reject expire, step timeline, plan draft gate send summary, who can approve, nothing sent by default
// @what: Pure state machine for the approval-gate demo, including the guard that only a manager may decide.
// @flow: Used by ApprovalDemo; timelines played through useSequence
import type { SequenceStep } from '../../../hooks/useSequence'

/*
 * The approval-gate demo as a pure state machine.
 *
 * A simulated HR agent run drafts an email freely, then parks at the approval gate for
 * gmail.send_message until a person with approval:decide answers, or the deadline passes. Every
 * guard lives here, so the component cannot approve on behalf of a role that lacks the permission
 * even if a button were somehow pressed.
 */

export type Decider = 'manager' | 'employee' | 'viewer'
export type StepId = 'plan' | 'draft' | 'gate' | 'send' | 'summary'
export type StepState =
  | 'ghost'
  | 'active'
  | 'done'
  | 'waiting'
  | 'approved'
  | 'rejected'
  | 'expired'
  | 'skipped'
export type Phase =
  | 'idle'
  | 'running'
  | 'parked'
  | 'approving'
  | 'completed'
  | 'rejected'
  | 'expiring'
  | 'expired'
export type CardStatus = 'hidden' | 'waiting' | 'approved' | 'rejected' | 'expired'
export type FocusTarget = 'none' | 'timeline' | 'card' | 'result'

export type ApprovalState = {
  phase: Phase
  steps: Record<StepId, StepState>
  card: CardStatus
  decider: Decider
  autoplayed: boolean
  byUser: boolean
  announcement: string
  focus: { target: FocusTarget; nonce: number }
}

export type ApprovalEvent =
  | { type: 'START'; byUser: boolean }
  | { type: 'STEP'; step: StepId; state: StepState }
  | { type: 'PARK' }
  | { type: 'APPROVE' }
  | { type: 'RESOLVE_APPROVED' }
  | { type: 'REJECT' }
  | { type: 'LET_EXPIRE' }
  | { type: 'RESOLVE_EXPIRED' }
  | { type: 'SET_DECIDER'; decider: Decider }
  | { type: 'RESET' }

export type ApprovalDispatch = (event: ApprovalEvent) => void

/** Only the manager role, of the three offered here, holds approval:decide. */
export const canDecide = (d: Decider): boolean => d === 'manager'

export const STEP_ORDER: ReadonlyArray<StepId> = ['plan', 'draft', 'gate', 'send', 'summary']

export const STEP_COPY: Record<StepId, { label: string; detail: string }> = {
  plan: {
    label: 'Model call · sandbox model',
    detail: 'Reads the new hire record and plans the email',
  },
  draft: {
    label: 'Tool · gmail.draft_message',
    detail: 'Draft written. Reads and drafts run freely.',
  },
  gate: {
    label: 'Approval · gmail.send_message',
    detail: 'Sending leaves the workspace, so the run parks here.',
  },
  send: {
    label: 'Tool · gmail.send_message',
    detail: 'Sent through the sandbox gmail driver. Nothing left your browser.',
  },
  summary: {
    label: 'Model call · resume',
    detail: 'Resolves routing again and writes the run summary.',
  },
}

export const PAYLOAD: Readonly<{ to: string; subject: string; body: string }> = {
  to: 'newhire@example.com',
  subject: 'Welcome to the team',
  body: 'Hello, welcome to the team.',
}

export const ANNOUNCE = {
  start: 'Running. Drafting the email.',
  parked: 'The run is parked, waiting for approval.',
  approving: 'Approved. Resuming the run.',
  completed: 'The run resumed and completed.',
  rejected: 'An approver rejected the action this run needed.',
  expiring: 'Letting the deadline pass.',
  expired:
    'Nobody decided in time, so the approval was rejected by default. Nothing is sent because a deadline passed.',
} as const

export const DECIDER_ANNOUNCEMENT: Record<Decider, string> = {
  manager: 'Deciding as a manager, who can approve this.',
  employee: 'Deciding as an employee. This role can see the approval but cannot decide it.',
  viewer: 'Deciding as a viewer. This role does not see the approval queue.',
}

const GHOST_STEPS: Readonly<Record<StepId, StepState>> = {
  plan: 'ghost',
  draft: 'ghost',
  gate: 'ghost',
  send: 'ghost',
  summary: 'ghost',
}

const PARKED_STEPS: Readonly<Record<StepId, StepState>> = {
  plan: 'done',
  draft: 'done',
  gate: 'waiting',
  send: 'ghost',
  summary: 'ghost',
}

/**
 * The first render. With reduced motion the run is already parked at the gate, which is the
 * state worth seeing; otherwise nothing has happened yet and autoplay starts the run.
 */
// @find: initApprovalState, approval initial state
export function initApprovalState(reduced: boolean): ApprovalState {
  const base = {
    decider: 'manager' as const,
    byUser: false,
    announcement: '',
    focus: { target: 'none' as const, nonce: 0 },
  }
  if (reduced) {
    return { ...base, phase: 'parked', steps: { ...PARKED_STEPS }, card: 'waiting', autoplayed: true }
  }
  return { ...base, phase: 'idle', steps: { ...GHOST_STEPS }, card: 'hidden', autoplayed: false }
}

function focusOn(s: ApprovalState, target: FocusTarget): ApprovalState['focus'] {
  return { target, nonce: s.focus.nonce + 1 }
}

// @find: approvalReducer, approve, reject, expire transitions
export function approvalReducer(s: ApprovalState, e: ApprovalEvent): ApprovalState {
  switch (e.type) {
    case 'START': {
      if (s.phase !== 'idle') return s
      // The Start button disappears once the run starts, so a user start sends focus to the
      // timeline rather than leaving it on nothing. After RESET focus is already there.
      const moveFocus = e.byUser && s.focus.target !== 'timeline'
      return {
        ...s,
        phase: 'running',
        steps: { ...GHOST_STEPS, plan: 'active' },
        card: 'hidden',
        autoplayed: true,
        byUser: e.byUser,
        announcement: ANNOUNCE.start,
        focus: moveFocus ? focusOn(s, 'timeline') : s.focus,
      }
    }
    case 'STEP': {
      if (s.phase !== 'running' && s.phase !== 'approving') return s
      return { ...s, steps: { ...s.steps, [e.step]: e.state } }
    }
    case 'PARK': {
      if (s.phase !== 'running') return s
      return {
        ...s,
        phase: 'parked',
        steps: { ...s.steps, plan: 'done', draft: 'done', gate: 'waiting' },
        card: 'waiting',
        announcement: ANNOUNCE.parked,
        focus: s.byUser ? focusOn(s, 'card') : s.focus,
      }
    }
    case 'APPROVE': {
      if (s.phase !== 'parked' || !canDecide(s.decider)) return s
      return {
        ...s,
        phase: 'approving',
        steps: { ...s.steps, gate: 'approved' },
        card: 'approved',
        announcement: ANNOUNCE.approving,
      }
    }
    case 'RESOLVE_APPROVED': {
      if (s.phase !== 'approving') return s
      return {
        ...s,
        phase: 'completed',
        steps: { ...s.steps, send: 'done', summary: 'done' },
        announcement: ANNOUNCE.completed,
        focus: focusOn(s, 'result'),
      }
    }
    case 'REJECT': {
      if (s.phase !== 'parked' || !canDecide(s.decider)) return s
      return {
        ...s,
        phase: 'rejected',
        steps: { ...s.steps, gate: 'rejected', send: 'skipped', summary: 'skipped' },
        card: 'rejected',
        announcement: ANNOUNCE.rejected,
        focus: focusOn(s, 'result'),
      }
    }
    case 'LET_EXPIRE': {
      // Any decider may let the deadline pass: doing nothing needs no permission.
      if (s.phase !== 'parked') return s
      return { ...s, phase: 'expiring', announcement: ANNOUNCE.expiring }
    }
    case 'RESOLVE_EXPIRED': {
      if (s.phase !== 'expiring') return s
      return {
        ...s,
        phase: 'expired',
        steps: { ...s.steps, gate: 'expired', send: 'skipped', summary: 'skipped' },
        card: 'expired',
        announcement: ANNOUNCE.expired,
        focus: focusOn(s, 'result'),
      }
    }
    case 'SET_DECIDER': {
      return { ...s, decider: e.decider, announcement: DECIDER_ANNOUNCEMENT[e.decider] }
    }
    case 'RESET': {
      return {
        ...s,
        phase: 'idle',
        steps: { ...GHOST_STEPS },
        card: 'hidden',
        byUser: true,
        announcement: '',
        focus: focusOn(s, 'timeline'),
      }
    }
  }
}

/* ---- Sequences, played with useSequence (times in ms) --------------------------------------- */

// @find: startSteps, run starts, plan and draft timeline
export function startSteps(dispatch: ApprovalDispatch, byUser: boolean): SequenceStep[] {
  return [
    { at: 0, run: () => dispatch({ type: 'START', byUser }) },
    {
      at: 900,
      run: () => {
        dispatch({ type: 'STEP', step: 'plan', state: 'done' })
        dispatch({ type: 'STEP', step: 'draft', state: 'active' })
      },
    },
    { at: 1700, run: () => dispatch({ type: 'STEP', step: 'draft', state: 'done' }) },
    {
      at: 2000,
      run: () => {
        dispatch({ type: 'STEP', step: 'gate', state: 'waiting' })
        dispatch({ type: 'PARK' })
      },
    },
  ]
}

// @find: approveSteps, approval timeline, send email after approve
export function approveSteps(dispatch: ApprovalDispatch): SequenceStep[] {
  return [
    { at: 0, run: () => dispatch({ type: 'APPROVE' }) },
    { at: 600, run: () => dispatch({ type: 'STEP', step: 'send', state: 'active' }) },
    {
      at: 1300,
      run: () => {
        dispatch({ type: 'STEP', step: 'send', state: 'done' })
        dispatch({ type: 'STEP', step: 'summary', state: 'active' })
      },
    },
    {
      at: 2000,
      run: () => {
        dispatch({ type: 'STEP', step: 'summary', state: 'done' })
        dispatch({ type: 'RESOLVE_APPROVED' })
      },
    },
  ]
}

// @find: expireSteps, deadline passes, run cancelled
export function expireSteps(dispatch: ApprovalDispatch): SequenceStep[] {
  return [
    { at: 0, run: () => dispatch({ type: 'LET_EXPIRE' }) },
    { at: 1200, run: () => dispatch({ type: 'RESOLVE_EXPIRED' }) },
  ]
}
