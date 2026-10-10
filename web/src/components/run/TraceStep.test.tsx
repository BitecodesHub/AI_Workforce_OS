// @find: tests for TraceStep, trace step, run step, step kind, tool call, model reply, search citations, approval result, step duration, run detail trace
// @what: Automated tests for TraceStep.
// @flow: Run with the web test runner; covers TraceStep.
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { RunStep } from '../../lib/queries'
import { TraceStep } from './TraceStep'

/*
 * A tool call says where its answer came from, so practice data is never read as a real result.
 */

function toolCall(detail: Record<string, unknown>): RunStep {
  return {
    id: 'step-1',
    position: 2,
    kind: 'tool_call',
    detail: { tool: 'slack.post_message', status: 'SUCCEEDED', summary: 'Posted to the channel.', ...detail },
    promptTokens: 0,
    completionTokens: 0,
    durationMs: 0,
    occurredAt: '2026-10-04T10:00:00Z',
  }
}

function renderStep(step: RunStep) {
  return render(
    <ol>
      <TraceStep step={step} />
    </ol>,
  )
}

describe('TraceStep mode badge', () => {
  it('marks a call answered from the sandbox as practice data', () => {
    renderStep(toolCall({ mode: 'sandbox', sideEffect: 'OUTBOUND' }))
    expect(screen.getByText('Practice data')).toBeInTheDocument()
    expect(screen.queryByText('Live')).toBeNull()
  })

  it('marks a call made to a connected service as live', () => {
    renderStep(toolCall({ mode: 'live', sideEffect: 'OUTBOUND' }))
    expect(screen.getByText('Live')).toBeInTheDocument()
    expect(screen.queryByText('Practice data')).toBeNull()
  })

  it('shows no badge on a step recorded before the mode was kept', () => {
    renderStep(toolCall({}))
    expect(screen.queryByText('Live')).toBeNull()
    expect(screen.queryByText('Practice data')).toBeNull()
  })
})

describe('TraceStep error heading', () => {
  it('reads a loop in words, not as a code', () => {
    renderStep({
      id: 'step-9',
      position: 9,
      kind: 'error',
      detail: { code: 'loop_detected', detail: 'The agent was repeating the same action.' },
      promptTokens: 0,
      completionTokens: 0,
      durationMs: 0,
      occurredAt: '2026-10-04T10:00:00Z',
    })
    expect(screen.getByRole('heading', { name: 'Stopped for repeating itself' })).toBeInTheDocument()
    expect(screen.getByText('The agent was repeating the same action.')).toBeInTheDocument()
    expect(screen.queryByText('loop_detected')).toBeNull()
  })
})
