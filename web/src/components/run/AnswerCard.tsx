import { Card, Eyebrow, Tag } from '../ui'
import { truncateWords } from '../../lib/format'
import type { RunStep } from '../../lib/queries'
import { detailText, isSandboxStep } from './traceModel'

/** What a completed run answered: the last model reply with text (see answerStep in traceModel). */
export function AnswerCard({ step, instruction }: { step: RunStep; instruction: string | null }) {
  const sandbox = isSandboxStep(step)
  return (
    <Card as="section">
      <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', alignItems: 'baseline' }}>
        <Eyebrow as="h2">Answer</Eyebrow>
        {sandbox && <Tag tone="neutral">Offline sandbox</Tag>}
      </div>
      {instruction && (
        <p className="caption" style={{ marginBottom: 'var(--space-3)', overflowWrap: 'anywhere' }}>
          Asked: {truncateWords(instruction, 200)}
        </p>
      )}
      <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{detailText(step.detail, 'content')}</p>
      {sandbox && (
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          A placeholder answer from the offline model, not a real model's reply.
        </p>
      )}
    </Card>
  )
}
