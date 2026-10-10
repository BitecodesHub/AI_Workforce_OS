// @find: tests for InlineApproval, inline approval, approve in chat, decide approval in thread, run waiting for approval, approve reject button, orchestrator approval
// @what: Automated tests for InlineApproval.
// @flow: Run with the web test runner; covers InlineApproval.
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ApprovalItem } from '../../lib/approvalQueries'
import { InlineApproval } from './InlineApproval'

const approval = {
  id: 'ap1',
  runId: 'r1',
  agentId: 'a1',
  tool: 'gmail.send_message',
  actionClass: 'external_send',
  summary: 'Send a message with Gmail',
  status: 'pending',
  payload: JSON.stringify({ to: 'jane@example.com', subject: 'Refund', body: 'Done.' }),
  requestedAt: '2026-10-08T00:00:00Z',
  expiresAt: '2026-10-09T00:00:00Z',
  decidedBy: null,
  decidedAt: null,
  decisionNote: null,
  goalId: 'g1',
  goalTitle: 'Email Jane',
  requestedBy: 'someone-else',
  taskInstruction: 'Now send it.',
  canDecide: true,
} as unknown as ApprovalItem

vi.mock('../../lib/approvalQueries', () => ({
  useRunApproval: () => ({ data: approval, isFetching: false, isLoading: false, refetch: vi.fn() }),
  useMemberNamer: () => ({ me: 'me', nameOf: () => 'Ava' }),
}))
vi.mock('../../lib/queries', () => ({ useDecideApproval: () => ({ mutateAsync: vi.fn() }) }))
vi.mock('../../lib/session', () => ({ can: () => true }))
vi.mock('../../lib/toast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }) }))

describe('InlineApproval', () => {
  it('moves focus to the feedback box when "Send back with feedback" opens it', () => {
    render(<InlineApproval runId="r1" agentName="Customer Support" />)
    fireEvent.click(screen.getByRole('button', { name: 'Send back with feedback' }))
    expect(screen.getByLabelText(/What should change/)).toHaveFocus()
  })

  it('moves focus to the reason box when "Reject and stop" opens it', () => {
    render(<InlineApproval runId="r1" agentName="Customer Support" />)
    fireEvent.click(screen.getByRole('button', { name: 'Reject and stop' }))
    expect(screen.getByLabelText(/Reason/)).toHaveFocus()
  })
})
