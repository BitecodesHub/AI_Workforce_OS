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
  knowledge_query: { tone: 'neutral', label: 'Searched documents' },
  memory_read: { tone: 'neutral', label: 'Recalled memory' },
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

/* ---- Searching the workspace's documents ---------------------------------------------------------------
 * An agent searches the documents two ways: the platform searches for it when a run begins (a
 * `knowledge_query` step, which also becomes the reference material at the end of its first message),
 * and the agent asks for a search itself (a `tool_call` step for the knowledge.search tool). Both read
 * the same way to a person: what was searched for, and which passages came back.
 */

/** What a document search is called wherever a step shows. */
export const SEARCHED_DOCUMENTS = 'Searched documents'

/** The document search's name, in the dotted form the platform records and the form providers are sent. */
const KNOWLEDGE_TOOLS = new Set(['knowledge.search', 'knowledge__search'])

export function isKnowledgeSearch(step: RunStep): boolean {
  if (step.kind === 'knowledge_query') return true
  return step.kind === 'tool_call' && KNOWLEDGE_TOOLS.has(detailText(step.detail, 'tool') ?? '')
}

/** The tag a step wears: its kind's, except that a call to the document search is not "Tool". */
export function stepKind(step: RunStep): { tone: TagTone; label: string } {
  if (isKnowledgeSearch(step)) return { tone: 'neutral', label: SEARCHED_DOCUMENTS }
  if (isMemoryStep(step)) return { tone: 'neutral', label: memoryStepLabel(step) }
  return STEP_KIND[step.kind] ?? { tone: 'neutral', label: sentenceCase(step.kind) }
}

/* ---- The agent's own memory ---------------------------------------------------------------------------
 * A run starts with the notes that fit its request (a `memory_read` step), and the agent can keep
 * a note (memory.remember) or look one up (memory.recall) while it works. Each shows as what it was
 * in words, with the notes themselves under it.
 */

const MEMORY_TOOLS = new Set(['memory.remember', 'memory__remember', 'memory.recall', 'memory__recall'])

export function isMemoryStep(step: RunStep): boolean {
  if (step.kind === 'memory_read') return true
  return step.kind === 'tool_call' && MEMORY_TOOLS.has(detailText(step.detail, 'tool') ?? '')
}

/** What a memory step is called: Remembered something, or Recalled memory. */
export function memoryStepLabel(step: RunStep): string {
  const tool = detailText(step.detail, 'tool') ?? ''
  return tool.endsWith('remember') ? 'Remembered something' : 'Recalled memory'
}

/** The notes a memory step recalled, as the trace shows them. */
export function memoriesOf(step: RunStep): Array<{ kind: string; content: string }> {
  const raw = step.detail['memories']
  if (!Array.isArray(raw)) return []
  return raw
    .filter((entry): entry is Record<string, unknown> => Boolean(entry) && typeof entry === 'object')
    .map((entry) => ({ kind: String(entry['kind'] ?? 'fact'), content: String(entry['content'] ?? '') }))
    .filter((entry) => entry.content !== '')
}

/** One passage a search cited: where it came from, with a short excerpt unless its source is restricted. */
export type TraceCitation = {
  number: number
  title: string
  page: number | null
  heading: string | null
  sourceId: string | null
  uri: string | null
  excerpt: string | null
  restricted: boolean
}

/** What was searched for, as the step recorded it. */
export const searchQueryOf = (step: RunStep): string | null => (isKnowledgeSearch(step) ? detailText(step.detail, 'query') : null)

/** The passages a search cited, in the order the agent read them; none for a step that is not a search. */
export function citationsOf(step: RunStep): TraceCitation[] {
  if (!isKnowledgeSearch(step)) return []
  const raw = step.detail['citations']
  if (!Array.isArray(raw)) return []
  const found: TraceCitation[] = []
  raw.forEach((item, index) => {
    if (!item || typeof item !== 'object') return
    const entry = item as Record<string, unknown>
    const title = detailText(entry, 'documentTitle')
    if (!title) return
    found.push({
      number: detailNumber(entry, 'n') ?? index + 1,
      title,
      page: detailNumber(entry, 'pageNumber'),
      heading: detailText(entry, 'heading'),
      sourceId: detailText(entry, 'sourceId'),
      uri: detailText(entry, 'uri'),
      excerpt: detailText(entry, 'excerpt'),
      restricted: entry['restricted'] === true,
    })
  })
  return found
}

/** The first step of every run since the platform began recording it: what the agent was asked. */
export const isInstruction = (step: RunStep) => step.kind === 'note' && step.detail.type === 'instruction'

export const isSandboxStep = (step: RunStep) => step.kind === 'model_call' && step.provider === 'sandbox'

/** Where a tool call's answer came from: a connected service, or the workspace's practice data. */
export type StepMode = 'live' | 'sandbox'

/**
 * `live` when the call went to a connected service, `sandbox` when it was answered from practice
 * data (the platform writes `mode` on every tool call). A step recorded before it was written, and
 * any step that is not a tool call, has none.
 */
export function stepMode(step: RunStep): StepMode | null {
  if (step.kind !== 'tool_call') return null
  const mode = detailText(step.detail, 'mode')
  return mode === 'live' || mode === 'sandbox' ? mode : null
}

/** What the badge on a tool call says about where its answer came from. */
export const MODE_LABEL: Record<StepMode, string> = { live: 'Live', sandbox: 'Practice data' }

/** The clip id a successful voice.create_voice_note tool call attached, if any. */
export function clipIdOf(step: RunStep): string | null {
  return step.kind === 'tool_call' ? detailText(step.detail, 'clipId') : null
}

/** The codes the run loop writes itself, in words a person reads; any other code is shown sentence-cased. */
const ERROR_HEADING: Record<string, string> = {
  loop_detected: 'Stopped for repeating itself',
  step_limit: 'Stopped at its step limit',
  output_limit: 'Answer cut short',
}

export function stepHeading(step: RunStep): string | null {
  if (isKnowledgeSearch(step)) return SEARCHED_DOCUMENTS
  if (isMemoryStep(step)) return memoryStepLabel(step)
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
    case 'error': {
      const code = detailText(step.detail, 'code')
      return (code && ERROR_HEADING[code]) || sentenceCase(code) || 'The run stopped'
    }
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

/**
 * What a completed run answered, as the step AnswerCard shows.
 *
 * <p>An answer the output limit cut off is continued over several model steps, and each step keeps
 * only its own part, shortened. The task keeps the whole text, so that is what is shown, in the
 * last step's place. Every other answer is the last reply, as before.
 */
export function completedAnswer(steps: RunStep[], taskResult?: string | null): RunStep | undefined {
  const step = answerStep(steps)
  if (!step) return undefined
  const continued = steps.some((candidate) => candidate.kind === 'model_call' && candidate.detail.next === 'continue')
  if (continued && taskResult && taskResult.trim()) return { ...step, detail: { ...step.detail, content: taskResult } }
  return step
}

/** The codes of runs that end with whatever answer they had written: the step limit and the output limit. */
const PARTIAL_ANSWER_CODES = new Set(['step_limit', 'output_limit'])

/**
 * What a failed run had written when it stopped, to show as an incomplete answer; null when there
 * is nothing to show.
 *
 * <p>The task's own result is preferred, because it holds the whole text - a long answer is
 * continued across several model steps, and each step keeps only its own part. Without it, a run
 * that stopped at its step limit or its output limit has its last reply with text. Any other
 * failed run has no answer: the text before an error is the agent thinking aloud, not an answer.
 */
export function incompleteAnswerText(steps: RunStep[] | undefined, taskResult?: string | null): string | null {
  if (taskResult && taskResult.trim()) return taskResult
  if (!steps) return null
  let code: string | null = null
  for (const step of steps) {
    if (step.kind === 'error') code = detailText(step.detail, 'code')
  }
  if (!code || !PARTIAL_ANSWER_CODES.has(code)) return null
  const reply = answerStep(steps)
  return reply ? detailText(reply.detail, 'content') : null
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
