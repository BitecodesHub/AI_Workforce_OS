// @find: run answer, final answer, what the person asked, completed run answer, answer card, run detail
// @what: Shows what a completed run answered and what was asked.
// @flow: Used by RunDetail; uses traceModel.
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
 */
function askedText(instruction: string): string {
  let text = instruction
  const marker = /(?:^|\n)Request:\n/g
  for (let match = marker.exec(instruction); match; match = marker.exec(instruction)) {
    text = instruction.slice(match.index + match[0].length)
  }
  for (const heading of ["The person's request (verbatim):\n", 'Original request: ']) {
    if (!text.startsWith(heading)) continue
    text = text.slice(heading.length)
    const ends = ['\n\nWork already done for this request:\n', '\n\nYour part: ']
      .map((end) => text.indexOf(end))
      .filter((at) => at >= 0)
    if (ends.length > 0) text = text.slice(0, Math.min(...ends))
    break
  }
  return text.trim()
}

// @find: AnswerCard, answer card, run answer, final answer, what the person asked, completed run answer
/** What a completed run answered: the last model reply with text (see answerStep in traceModel). */
export function AnswerCard({ step, instruction }: { step: RunStep; instruction: string | null }) {
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
      <Markdown text={detailText(step.detail, 'content') ?? ''} />
      {sandbox && (
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          A placeholder answer from the offline model, not a real model's reply.
        </p>
      )}
    </Card>
  )
}
