import type { TagTone } from '../ui'
import { formatDuration, sentenceCase } from '../../lib/format'
import { toolLabel } from '../../lib/labels'
import type { RunStep } from '../../lib/queries'

/*
 * Reading a run's trace: the pure rules for what one step means, shared by the full trace on
 * RunDetail and the compact one anywhere else a run's progress needs to show (Chat, Orchestrator).
 *
 * Nothing here touches the DOM. TraceStep.tsx and RunTraceCompact.tsx are the two places these
 * rules become markup.
 */

export function detailText(detail: Record<string, unknown>, key: string): string | null {
  const value = detail[key]
  return typeof value === 'string' && value.trim() ? value : null
}

export function detailStrings(detail: Record<string, unknown>, key: string): string[] {
  const value = detail[key]
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

export function detailNumber(detail: Record<string, unknown>, key: string): number | null {
  const value = detail[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/** The kinds of step a trace can hold (run_steps.kind), as a tag. */
export const STEP_KIND: Record<string, { tone: TagTone; label: string }> = {
  model_call: { tone: 'blue', label: 'Model' },
  tool_call: { tone: 'operations', label: 'Tool' },
  approval: { tone: 'warning', label: 'Approval' },
  question: { tone: 'warning', label: 'Question' },
  error: { tone: 'danger', label: 'Error' },
  handoff: { tone: 'neutral', label: 'Handoff' },
  knowledge_query: { tone: 'neutral', label: 'Knowledge search' },
  memory_read: { tone: 'neutral', label: 'Memory read' },
  memory_write: { tone: 'neutral', label: 'Memory write' },
  note: { tone: 'neutral', label: 'Note' },
}

/** One question item as a `question` step's detail carries it (AskPersonTool.Question, shaped like QuestionItem). */
export type TraceQuestionOption = { label: string; description: string; recommended?: boolean }
export type TraceQuestionItem = {
  id?: string
  header: string
  question: string
  multiSelect?: boolean
  options: TraceQuestionOption[]
}

function isTraceQuestionItem(value: unknown): value is TraceQuestionItem {
  if (!value || typeof value !== 'object') return false
  const item = value as Record<string, unknown>
  return typeof item.header === 'string' && typeof item.question === 'string'
}

/** The questions a `question` step's detail carries (QuestionService.askStepDetail's `questions`). */
export function questionItemsOf(detail: Record<string, unknown>): TraceQuestionItem[] {
  const raw = detail['questions']
  return Array.isArray(raw) ? raw.filter(isTraceQuestionItem) : []
}

/** The first step of every run since the platform began recording it: what the agent was asked. */
export const isInstruction = (step: RunStep) => step.kind === 'note' && step.detail.type === 'instruction'

export const isSandboxStep = (step: RunStep) => step.kind === 'model_call' && step.provider === 'sandbox'

/** The clip id a successful voice.create_voice_note tool call attached, if any. */
export function clipIdOf(step: RunStep): string | null {
  return step.kind === 'tool_call' ? detailText(step.detail, 'clipId') : null
}

export function stepHeading(step: RunStep): string | null {
  switch (step.kind) {
    case 'model_call':
      if (!step.provider) return 'Model call'
      return step.model ? `${step.provider} / ${step.model}` : step.provider
    case 'tool_call':
    case 'approval':
      return toolLabel(detailText(step.detail, 'tool'))
    case 'question': {
      const headers = questionItemsOf(step.detail).map((item) => item.header)
      return headers.length > 0 ? `Asked: ${headers.join(', ')}` : 'Asked a question'
    }
    case 'error':
      return sentenceCase(detailText(step.detail, 'code')) || 'The run stopped'
    case 'handoff': {
      const from = detailText(step.detail, 'fromAgentName')
      return from ? `Handed over from ${from}` : 'Handoff'
    }
    default: {
      const tool = detailText(step.detail, 'tool')
      if (tool) return toolLabel(tool)
      const type = sentenceCase(detailText(step.detail, 'type'))
      return type || null
    }
  }
}

export function stepDescription(step: RunStep): string | null {
  return (
    detailText(step.detail, 'summary') ??
    detailText(step.detail, 'reason') ??
    detailText(step.detail, 'detail') ??
    detailText(step.detail, 'content')
  )
}

/** The last model reply with any text: what a completed run answered. */
export function answerStep(steps: RunStep[]): RunStep | undefined {
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    const step = steps[index]!
    if (step.kind === 'model_call' && detailText(step.detail, 'content')) return step
  }
  return undefined
}

/** The approval the run is paused on, from the latest approval step. */
export function latestApprovalId(steps: RunStep[] | undefined): string | null {
  if (!steps) return null
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    const step = steps[index]!
    if (step.kind === 'approval') return detailText(step.detail, 'approvalId')
  }
  return null
}

/** The question the run is paused on, from the latest question step. */
export function latestQuestionId(steps: RunStep[] | undefined): string | null {
  if (!steps) return null
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    const step = steps[index]!
    if (step.kind === 'question') return detailText(step.detail, 'questionId')
  }
  return null
}

/**
 * One routing attempt as the platform wrote it ('openrouter/gpt-4o answered in 5234 ms'), with
 * its time in the same form as every other duration on the page.
 */
export const readableAttempt = (attempt: string) =>
  attempt.replace(/answered in (\d+) ms$/, (_, ms: string) => `answered in ${formatDuration(Number(ms))}`)

export const withoutFinalStop = (text: string) => text.trim().replace(/[.\s]+$/, '')

/** Runs cancelled before the reason was written for people carry the canceller's user id. */
export const CANCELLED_BY = /^Cancelled by (.+)$/
