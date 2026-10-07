import { describe, expect, it } from 'vitest'
import type { RunStep } from '../../lib/queries'
import { completedAnswer, incompleteAnswerText, stepHeading, stepMode } from './traceModel'

/*
 * What a trace says about where a tool's answer came from, how a run that stopped itself reads,
 * and what a run that stopped at a limit had written.
 */

let position = 0
function step(kind: string, detail: Record<string, unknown>, extra: Partial<RunStep> = {}): RunStep {
  position += 1
  return {
    id: `step-${position}`,
    position,
    kind,
    detail,
    promptTokens: 0,
    completionTokens: 0,
    durationMs: 0,
    occurredAt: '2026-10-04T10:00:00Z',
    ...extra,
  }
}

describe('stepMode', () => {
  it('reads live and sandbox from a tool call', () => {
    expect(stepMode(step('tool_call', { mode: 'live' }))).toBe('live')
    expect(stepMode(step('tool_call', { mode: 'sandbox' }))).toBe('sandbox')
  })

  it('says nothing for a step written before the mode was kept, or one that is not a tool call', () => {
    expect(stepMode(step('tool_call', { tool: 'slack.post_message' }))).toBeNull()
    expect(stepMode(step('tool_call', { mode: 'something-else' }))).toBeNull()
    expect(stepMode(step('model_call', { mode: 'live' }))).toBeNull()
  })
})

describe('stepHeading for the errors the run loop writes itself', () => {
  it('names a loop, the step limit and the output limit in words', () => {
    expect(stepHeading(step('error', { code: 'loop_detected' }))).toBe('Stopped for repeating itself')
    expect(stepHeading(step('error', { code: 'step_limit' }))).toBe('Stopped at its step limit')
    expect(stepHeading(step('error', { code: 'output_limit' }))).toBe('Answer cut short')
  })

  it('falls back to the code in sentence case, or to a plain line when there is none', () => {
    expect(stepHeading(step('error', { code: 'no_model_available' }))).toBe('No model available')
    expect(stepHeading(step('error', {}))).toBe('The run stopped')
  })
})

describe('incompleteAnswerText', () => {
  const partialReply = step('model_call', { content: 'Three of the five suppliers are covered so far.' })

  it('prefers the whole text the task kept', () => {
    const steps = [partialReply, step('error', { code: 'output_limit' })]
    expect(incompleteAnswerText(steps, 'The whole text, joined across the continuations.')).toBe(
      'The whole text, joined across the continuations.',
    )
  })

  it('uses the last reply of a run that stopped at its step limit or output limit', () => {
    expect(incompleteAnswerText([partialReply, step('error', { code: 'step_limit' })])).toBe(
      'Three of the five suppliers are covered so far.',
    )
    expect(incompleteAnswerText([partialReply, step('error', { code: 'output_limit' })])).toBe(
      'Three of the five suppliers are covered so far.',
    )
  })

  it('has nothing for a run that failed for any other reason', () => {
    expect(incompleteAnswerText([partialReply, step('error', { code: 'no_model_available' })])).toBeNull()
    expect(incompleteAnswerText([partialReply])).toBeNull()
    expect(incompleteAnswerText(undefined)).toBeNull()
  })

  it('has nothing when the run stopped at a limit without having written anything', () => {
    expect(incompleteAnswerText([step('model_call', { content: '' }), step('error', { code: 'step_limit' })])).toBeNull()
  })
})

describe('completedAnswer', () => {
  it('is the last reply of a run that was not continued, whatever the task kept', () => {
    const last = step('model_call', { content: 'The final reply.' })
    expect(completedAnswer([step('model_call', { content: 'Looking.' }), last], 'Something else')).toBe(last)
  })

  it('shows the whole text the task kept when the answer was continued over several steps', () => {
    const steps = [
      step('model_call', { content: 'Part one, ', truncated: true, next: 'continue' }),
      step('model_call', { content: 'and part two.' }, { provider: 'openrouter' }),
    ]
    const shown = completedAnswer(steps, 'Part one, and part two.')
    expect(shown?.detail.content).toBe('Part one, and part two.')
    expect(shown?.provider).toBe('openrouter')
  })

  it('falls back to the last reply when a continued answer has no saved text, and to nothing when there is no reply', () => {
    const steps = [
      step('model_call', { content: 'Part one, ', truncated: true, next: 'continue' }),
      step('model_call', { content: 'and part two.' }),
    ]
    expect(completedAnswer(steps, null)?.detail.content).toBe('and part two.')
    expect(completedAnswer([step('model_call', { content: '' })], 'Text')).toBeUndefined()
  })
})
