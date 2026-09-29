import { describe, expect, it } from 'vitest'
import {
  ANNOUNCE,
  approvalReducer,
  approveSteps,
  canDecide,
  expireSteps,
  initApprovalState,
  startSteps,
} from './approvalModel'
import type { ApprovalEvent, ApprovalState, Decider } from './approvalModel'
import type { SequenceStep } from '../../../hooks/useSequence'

/*
 * The reducer is driven through the same sequence builders the component plays, run in order of
 * time, so these tests cover the timelines as well as the guards.
 */

function runner(initial: ApprovalState) {
  let state = initial
  const dispatch = (event: ApprovalEvent) => {
    state = approvalReducer(state, event)
  }
  const play = (steps: SequenceStep[]) => {
    for (const step of [...steps].sort((a, b) => a.at - b.at)) step.run()
  }
  return { dispatch, play, get: () => state }
}

function parkedAs(decider: Decider): ApprovalState {
  return approvalReducer(initApprovalState(true), { type: 'SET_DECIDER', decider })
}

describe('initApprovalState', () => {
  it('starts parked at the gate when motion is reduced', () => {
    const state = initApprovalState(true)
    expect(state.phase).toBe('parked')
    expect(state.steps).toEqual({
      plan: 'done',
      draft: 'done',
      gate: 'waiting',
      send: 'ghost',
      summary: 'ghost',
    })
    expect(state.card).toBe('waiting')
    expect(state.autoplayed).toBe(true)
    expect(state.announcement).toBe('')
    expect(state.focus.target).toBe('none')
  })

  it('starts idle with every step ghosted when motion is on', () => {
    const state = initApprovalState(false)
    expect(state.phase).toBe('idle')
    expect(Object.values(state.steps).every((s) => s === 'ghost')).toBe(true)
    expect(state.card).toBe('hidden')
    expect(state.autoplayed).toBe(false)
  })
})

describe('approvalReducer paths', () => {
  it('runs to the gate, then approve resumes and ends completed', () => {
    const run = runner(initApprovalState(false))
    run.play(startSteps(run.dispatch, false))
    expect(run.get().phase).toBe('parked')
    expect(run.get().autoplayed).toBe(true)
    expect(run.get().announcement).toBe(ANNOUNCE.parked)
    // An autoplayed run never moves focus.
    expect(run.get().focus.target).toBe('none')

    run.play(approveSteps(run.dispatch))
    const end = run.get()
    expect(end.phase).toBe('completed')
    expect(end.card).toBe('approved')
    expect(end.steps).toEqual({
      plan: 'done',
      draft: 'done',
      gate: 'approved',
      send: 'done',
      summary: 'done',
    })
    expect(end.announcement).toBe('The run resumed and completed.')
    expect(end.focus.target).toBe('result')
  })

  it('reject cancels the run and skips the rest', () => {
    const end = approvalReducer(initApprovalState(true), { type: 'REJECT' })
    expect(end.phase).toBe('rejected')
    expect(end.card).toBe('rejected')
    expect(end.steps.gate).toBe('rejected')
    expect(end.steps.send).toBe('skipped')
    expect(end.steps.summary).toBe('skipped')
    expect(end.announcement).toBe('An approver rejected the action this run needed.')
    expect(end.focus.target).toBe('result')
  })

  it('letting the deadline pass ends expired, with nothing sent', () => {
    const run = runner(initApprovalState(true))
    run.play(expireSteps(run.dispatch))
    const end = run.get()
    expect(end.phase).toBe('expired')
    expect(end.card).toBe('expired')
    expect(end.steps.gate).toBe('expired')
    expect(end.steps.send).toBe('skipped')
    expect(end.steps.summary).toBe('skipped')
    expect(end.announcement).toBe(ANNOUNCE.expired)
    expect(end.focus.target).toBe('result')
  })

  it('passes through expiring before the deadline resolves', () => {
    const expiring = approvalReducer(initApprovalState(true), { type: 'LET_EXPIRE' })
    expect(expiring.phase).toBe('expiring')
    expect(expiring.announcement).toBe('Letting the deadline pass.')
    // Nobody can approve once the deadline is passing.
    expect(approvalReducer(expiring, { type: 'APPROVE' })).toBe(expiring)
  })
})

describe('role guard', () => {
  it('only the manager can decide', () => {
    expect(canDecide('manager')).toBe(true)
    expect(canDecide('employee')).toBe(false)
    expect(canDecide('viewer')).toBe(false)
  })

  it.each(['employee', 'viewer'] as const)('%s cannot approve or reject', (decider) => {
    const parked = parkedAs(decider)
    expect(approvalReducer(parked, { type: 'APPROVE' })).toBe(parked)
    expect(approvalReducer(parked, { type: 'REJECT' })).toBe(parked)
  })

  it.each(['manager', 'employee', 'viewer'] as const)('%s can let the deadline pass', (decider) => {
    const run = runner(parkedAs(decider))
    run.play(expireSteps(run.dispatch))
    expect(run.get().phase).toBe('expired')
  })

  it('announces each role when the decider changes, in any phase', () => {
    const idle = initApprovalState(false)
    const viewer = approvalReducer(idle, { type: 'SET_DECIDER', decider: 'viewer' })
    expect(viewer.decider).toBe('viewer')
    expect(viewer.phase).toBe('idle')
    expect(viewer.announcement).toBe('Deciding as a viewer. This role does not see the approval queue.')
    expect(approvalReducer(viewer, { type: 'SET_DECIDER', decider: 'employee' }).announcement).toBe(
      'Deciding as an employee. This role can see the approval but cannot decide it.',
    )
    expect(approvalReducer(viewer, { type: 'SET_DECIDER', decider: 'manager' }).announcement).toBe(
      'Deciding as a manager, who can approve this.',
    )
  })
})

describe('reset and focus', () => {
  it('a user run moves focus to the card when it parks', () => {
    const run = runner(initApprovalState(false))
    run.play(startSteps(run.dispatch, true))
    expect(run.get().phase).toBe('parked')
    expect(run.get().focus.target).toBe('card')
  })

  it('run it again returns to idle on the timeline, then parks with focus on the card', () => {
    const run = runner(approvalReducer(initApprovalState(true), { type: 'REJECT' }))
    run.dispatch({ type: 'RESET' })
    expect(run.get().phase).toBe('idle')
    expect(run.get().card).toBe('hidden')
    expect(run.get().focus.target).toBe('timeline')
    // RESET keeps autoplayed, so autoplay cannot take over the user's replay.
    expect(run.get().autoplayed).toBe(true)

    run.play(startSteps(run.dispatch, true))
    expect(run.get().phase).toBe('parked')
    expect(run.get().focus.target).toBe('card')
  })

  it('ignores sequence events that arrive in the wrong phase', () => {
    const parked = initApprovalState(true)
    expect(approvalReducer(parked, { type: 'STEP', step: 'send', state: 'active' })).toBe(parked)
    expect(approvalReducer(parked, { type: 'RESOLVE_APPROVED' })).toBe(parked)
    expect(approvalReducer(parked, { type: 'RESOLVE_EXPIRED' })).toBe(parked)
    expect(approvalReducer(parked, { type: 'START', byUser: true })).toBe(parked)
  })
})
