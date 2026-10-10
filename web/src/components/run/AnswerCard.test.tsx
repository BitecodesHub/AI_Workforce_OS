// @find: tests for AnswerCard, run answer, final answer, what the person asked, completed run answer, answer card, run detail
// @what: Automated tests for AnswerCard.
// @flow: Run with the web test runner; covers AnswerCard.
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { RunStep } from '../../lib/queries'
import { AnswerCard } from './AnswerCard'

/*
 * The "Asked:" line over an answer is what the person asked, not the conversation and passages the
 * coordinator put ahead of it in the agent's instruction.
 */

const STEP: RunStep = {
  id: 'step-1',
  position: 3,
  kind: 'model_call',
  detail: { content: 'Here is the reply.' },
  provider: 'openrouter',
  model: 'some-model',
  promptTokens: 10,
  completionTokens: 5,
  durationMs: 100,
  occurredAt: '2026-10-04T00:00:00Z',
}

function asked(instruction: string | null): string | null {
  const { container } = render(<AnswerCard step={STEP} instruction={instruction} />)
  const line = Array.from(container.querySelectorAll('p')).find((p) => p.textContent?.startsWith('Asked:'))
  return line?.textContent ?? null
}

describe('AnswerCard asked line', () => {
  it('shows the instruction as it is when nothing was put ahead of it', () => {
    expect(asked('Draft a welcome email for Priya')).toBe('Asked: Draft a welcome email for Priya')
  })

  it('shows only the text after the last "Request:" line', () => {
    const instruction = [
      'Passages from the workspace\'s documents that bear on this request. Base your answer on them:',
      '[1] Refund policy, page 2',
      'Refunds are issued within five business days.',
      '',
      'Earlier in this conversation, for context (the request itself comes after this):',
      'The requester: Draft a welcome email',
      '',
      'Your last reply (verbatim):',
      'Subject: Welcome',
      '',
      'Request:',
      'now send it',
    ].join('\n')

    expect(asked(instruction)).toBe('Asked: now send it')
  })

  it('uses the last marker when the passages block has one of its own', () => {
    expect(asked('Request:\nfirst\n\nRequest:\nsecond one')).toBe('Asked: second one')
  })

  it('keeps a "Request:" that is part of a line, rather than a line of its own', () => {
    expect(asked('Please log a Request: for a laptop')).toBe('Asked: Please log a Request: for a laptop')
  })

  it('shows the person\'s request, not the labels, for the first step of a chain', () => {
    const instruction = [
      'Earlier in this conversation, for context (the request itself comes after this):',
      'The requester: hello',
      '',
      'Request:',
      'The person\'s request (verbatim):',
      'Research the suppliers, then draft the email.',
      '',
      'Your part: Research the suppliers',
    ].join('\n')

    expect(asked(instruction)).toBe('Asked: Research the suppliers, then draft the email.')
  })

  it('shows the person\'s request for a later step, ahead of the earlier work it was handed', () => {
    const instruction = [
      'The person\'s request (verbatim):',
      'Research the suppliers, then draft the email.',
      '',
      'Work already done for this request:',
      '- Research: Supplier A is cheapest.',
      '',
      'Your part: Draft the email',
    ].join('\n')

    expect(asked(instruction)).toBe('Asked: Research the suppliers, then draft the email.')
  })

  it('shows the original request for a step the coordinator did not word', () => {
    const instruction = 'Original request: Summarise the suppliers\n\nWork already done for this request:\n- Research: A.\n\nYour part:\nWrite it up'

    expect(asked(instruction)).toBe('Asked: Summarise the suppliers')
  })

  it('shows no line when there is no instruction, or nothing follows the marker', () => {
    expect(asked(null)).toBeNull()
    expect(asked('Request:\n')).toBeNull()
  })

  it('still shows the answer itself', () => {
    render(<AnswerCard step={STEP} instruction="Request:\nhello" />)
    expect(screen.getByText('Here is the reply.')).toBeInTheDocument()
  })
})
