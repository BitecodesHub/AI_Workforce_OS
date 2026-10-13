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

  it('shows a later workflow step its own part, not the workflow description the server put ahead of it', () => {
    // As GoalService words a workflow step after others (seen live on 13 Oct 2026).
    const described = [
      'Original request: Checks a fork and join.',
      '',
      'Started from the workflow "QA fork join check".',
      '',
      'Work already done for this request:',
      '- Customer Support: Our support hours stay 9 am to 5 pm.',
      '- HR: Flexible hours help small businesses.',
      '',
      'Your part:',
      'Write a two sentence summary for the manager.',
    ].join('\n')
    expect(asked(described)).toBe('Asked: Write a two sentence summary for the manager.')

    const undescribed = 'Original request: Started from the workflow "Weekly report".\n\nWork already done for this request:\n- Research: A.\n\nYour part:\nWrite it up'
    expect(asked(undescribed)).toBe('Asked: Write it up')
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

describe('AnswerCard cards', () => {
  const CARD_STEP: RunStep = {
    ...STEP,
    detail: {
      content: ['Draft:', '```email-draft', JSON.stringify({ to: 'sam@example.com', subject: 'Welcome', body: 'Hello.' }), '```'].join('\n'),
    },
  }

  it('draws the card without a send button, linking to the conversation the run answered', () => {
    render(<AnswerCard step={CARD_STEP} instruction={null} chatHref="/chat?c=c1" />)
    expect(screen.getByRole('group', { name: 'Email draft: Welcome' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /send it/ })).toBeNull()
    expect(screen.getByRole('link', { name: 'Open the conversation' })).toHaveAttribute('href', '/chat?c=c1')
  })

  it('says to ask in Chat, with no link and no talk of a conversation, when the run came from none', () => {
    render(<AnswerCard step={CARD_STEP} instruction={null} />)
    expect(screen.getByText('To have this email sent, ask an agent in Chat.')).toBeInTheDocument()
    expect(screen.queryByText(/its conversation/)).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
  })

  it('says the card is read-only for a role that cannot start work', () => {
    render(<AnswerCard step={CARD_STEP} instruction={null} readOnly />)
    expect(screen.getByText(/cannot ask agents to act on it/)).toBeInTheDocument()
    expect(screen.queryByRole('link')).toBeNull()
  })
})
