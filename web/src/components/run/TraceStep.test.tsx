// @find: tests for TraceStep, browser screenshot, live view, trace step, run step, step kind, tool call, model reply, search citations, approval result, step duration, run detail trace
// @what: Automated tests for TraceStep.
// @flow: Run with the web test runner; covers TraceStep.
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
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

// jsdom has no modal dialogs; the screenshot opens full size in one.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

describe('TraceStep web browser screenshot', () => {
  it('shows the page address and a screenshot named after the page, opening full size on click', async () => {
    const fetchMock = vi.fn(async () => new Response(new Blob(['jpeg'], { type: 'image/jpeg' }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:shot', revokeObjectURL: () => {} }))
    renderStep(
      toolCall({
        tool: 'browser.open_page',
        summary: 'Opened Example Domain (https://example.com/).',
        mode: 'live',
        shotId: 'shot-1',
        url: 'https://example.com/',
        pageTitle: 'Example Domain',
      }),
    )
    expect(screen.getByText('https://example.com/')).toBeInTheDocument()
    const image = await screen.findByRole('img', { name: 'Screenshot of Example Domain' })
    expect(image).toHaveAttribute('src', 'blob:shot')
    expect(image).toHaveAttribute('loading', 'lazy')
    expect(fetchMock).toHaveBeenCalledWith('/api/browser/shots/shot-1', expect.objectContaining({ method: 'GET' }))
    fireEvent.click(screen.getByRole('button', { name: 'Screenshot of Example Domain, open full size' }))
    expect(screen.getByRole('heading', { name: 'Example Domain' })).toBeInTheDocument()
    vi.unstubAllGlobals()
  })

  it('shows no screenshot on a tool call without one', () => {
    renderStep(toolCall({ mode: 'live' }))
    expect(screen.queryByRole('img')).toBeNull()
  })
})
