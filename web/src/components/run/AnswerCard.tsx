// @find: run answer, final answer, what the person asked, completed run answer, answer card, run detail, answer cards on run page
// @what: Shows what a completed run answered (with its cards) and what was asked.
// @flow: Used by RunDetail and RunTraceCompact; uses traceModel; cards link back to the conversation through chatHref.
import { Card, Eyebrow, Tag } from '../ui'
import { Markdown } from '../ui/Markdown'
import { truncateWords } from '../../lib/format'
import type { RunStep } from '../../lib/queries'
import { detailText, isSandboxStep } from './traceModel'

/**
 * What the person asked, out of the instruction the agent was given. The coordinator puts the
 * conversation so far and any document passages ahead of the request, ending with a line that says
 * "Request:", so the question is what follows the last one. A step of a chain is given the request
 * under a heading of its own, ahead of the earlier work and its own part, which are left off here.
 *
 * A later step of a workflow is the exception: its "original request" is only the workflow's
 * description, ending in the line the server adds ('Started from the workflow "Name".'), which
 * nobody asked of that step. It shows its own part instead, as the workflow's first step shows its
 * own instruction; on 13 Oct 2026 a step's trace read "Asked: Started from the workflow ...".
 */
function askedText(instruction: string): string {
  let text = instruction
  const marker = /(?:^|\n)Request:\n/g
  for (let match = marker.exec(instruction); match; match = marker.exec(instruction)) {
    text = instruction.slice(match.index + match[0].length)
  }
  for (const heading of ["The person's request (verbatim):\n", 'Original request: ']) {
    if (!text.startsWith(heading)) continue
    const whole = text
    text = text.slice(heading.length)
    const ends = ['\n\nWork already done for this request:\n', '\n\nYour part: ']
      .map((end) => text.indexOf(end))
      .filter((at) => at >= 0)
    if (ends.length > 0) text = text.slice(0, Math.min(...ends))
    if (heading === 'Original request: ' && WORKFLOW_REQUEST.test(text.trim())) {
      const part = ownPart(whole)
      if (part) return part
    }
    break
  }
  return text.trim()
}

/** The line WorkflowService ends a workflow goal's description with. */
const WORKFLOW_REQUEST = /(?:^|\n)Started from the workflow ".*"\.$/

/** A chain step's own part: what follows its last "Your part:" heading, on that line or the next. */
function ownPart(instruction: string): string | null {
  const heading = /\n\nYour part:[ \n]/g
  let part: string | null = null
  for (let match = heading.exec(instruction); match; match = heading.exec(instruction)) {
    part = instruction.slice(match.index + match[0].length)
  }
  return part?.trim() || null
}

// @find: AnswerCard, answer card, run answer, final answer, what the person asked, completed run answer
/**
 * What a completed run answered: the last model reply with text (see answerStep in traceModel).
 * Cards in the answer read and copy here; a card that would act (send an email, make a choice)
 * points to the conversation instead, through `chatHref` when the run came from one, or says it is
 * read-only for a role that cannot use Chat.
 */
export function AnswerCard({
  step,
  instruction,
  chatHref,
  readOnly = false,
}: {
  step: RunStep
  instruction: string | null
  /** The Chat conversation the run answered, when there is one the person may open. */
  chatHref?: string | undefined
  /** The person's role cannot use Chat, so cards say they are there to read and copy. */
  readOnly?: boolean | undefined
}) {
  const sandbox = isSandboxStep(step)
  const asked = instruction ? askedText(instruction) : ''
  return (
    <Card as="section">
      <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', alignItems: 'baseline' }}>
        <Eyebrow as="h2">Answer</Eyebrow>
        {sandbox && <Tag tone="neutral">Offline sandbox</Tag>}
      </div>
      {asked && (
        <p className="caption" style={{ marginBottom: 'var(--space-3)', overflowWrap: 'anywhere' }}>
          Asked: {truncateWords(asked, 200)}
        </p>
      )}
      <Markdown
        text={detailText(step.detail, 'content') ?? ''}
        actions={readOnly ? { readOnly: true } : chatHref ? { chatHref } : undefined}
      />
      {sandbox && (
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          A placeholder answer from the offline model, not a real model's reply.
        </p>
      )}
    </Card>
  )
}
