// @find: outcomes, goal outcomes, completed, failed, waiting approval, success rate, results breakdown, analytics outcomes, OutcomesCard
// @what: Analytics card summarising how goals ended (completed, failed, waiting).
// @flow: Rendered on the Analytics page from Insights
import type { ReactNode } from 'react'
import { Card, Eyebrow, StatusTag } from '../ui'
import { formatCount } from '../../lib/format'
import type { Insights } from '../../lib/insightsQueries'
import { formatPercent, formatSeconds } from './figures'

/*
 * How the work ended, and what held it up: runs by the way they finished, the approvals people were
 * asked for and how they went, the questions agents asked, and the reasons runs failed.
 *
 * Counted over finished work only, so a run still going is never a failure here; it is listed
 * apart, as still going.
 */

/** Each row's whole label: "Goals from given directly" is what a prefix made of the third one. */
const GOAL_SOURCE: Record<string, string> = {
  chat: 'Goals from chat',
  schedule: 'Goals from schedules',
  manual: 'Goals given directly',
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <h3 className="section-heading" style={{ marginBottom: 'var(--space-3)' }}>
        {title}
      </h3>
      {children}
    </div>
  )
}

function Row({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div
      className="row"
      style={{ justifyContent: 'space-between', gap: 'var(--space-4)', padding: 'var(--space-1) 0' }}
    >
      <span>
        {label}
        {note && <span className="caption muted"> {note}</span>}
      </span>
      <span className="tabular">{value}</span>
    </div>
  )
}

// @find: OutcomesCard, goal outcomes summary
export function OutcomesCard({ insights }: { insights: Insights }) {
  const { runs, approvals, questions, failureReasons, goals } = insights
  const decided = approvals.approved + approvals.rejected

  return (
    <Card as="section">
      <Eyebrow as="h2">How the work ended</Eyebrow>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 260px), 1fr))',
          gap: 'var(--space-6)',
        }}
      >
        <Section title="Runs">
          {runs.total === 0 ? (
            <p className="muted">No run started in this window.</p>
          ) : (
            <>
              <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap' }}>
                {Object.entries(runs.byStatus)
                  .filter(([, n]) => n > 0)
                  .map(([status, n]) => (
                    <span key={status} className="row" style={{ gap: 'var(--space-2)' }}>
                      <StatusTag kind="run" status={status} withDot />
                      <span className="tabular">{formatCount(n)}</span>
                    </span>
                  ))}
              </div>
              <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                {runs.active > 0 ? `${formatCount(runs.active)} still going or waiting, not counted above. ` : ''}
                {runs.unpriced > 0
                  ? `${formatCount(runs.unpriced)} used a model with no price on file. `
                  : ''}
                {runs.sandboxOnly > 0 ? `${formatCount(runs.sandboxOnly)} were answered by the offline sandbox. ` : ''}
              </p>
            </>
          )}
          {goals.bySource.length > 0 && (
            <div style={{ marginTop: 'var(--space-3)' }}>
              {goals.bySource.map((source) => (
                <Row
                  key={source.source}
                  label={GOAL_SOURCE[source.source] ?? `Goals from ${source.source}`}
                  value={`${formatCount(source.completed)} done`}
                  {...(source.failed > 0 ? { note: `${formatCount(source.failed)} failed` } : {})}
                />
              ))}
            </div>
          )}
        </Section>

        <Section title="Approvals">
          {approvals.raised === 0 ? (
            <p className="muted">No approval was asked for in this window.</p>
          ) : (
            <>
              <Row label="Raised" value={formatCount(approvals.raised)} />
              <Row label="Approved" value={formatCount(approvals.approved)} />
              <Row label="Rejected" value={formatCount(approvals.rejected)} />
              <Row
                label="Expired without a decision"
                value={formatCount(approvals.expired)}
                {...(approvals.raised > 0 ? { note: formatPercent(approvals.expired / approvals.raised) } : {})}
              />
              {approvals.pending > 0 && (
                <Row label="Still waiting" value={formatCount(approvals.pending)} />
              )}
              <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                {decided === 0
                  ? 'Nobody has decided one yet.'
                  : `Decided in ${formatSeconds(approvals.medianDecisionSeconds)} at the median, ${formatSeconds(approvals.p90DecisionSeconds)} at the 90th percentile.`}
              </p>
            </>
          )}
        </Section>

        <Section title="Questions">
          {questions.asked === 0 ? (
            <p className="muted">No agent asked a question in this window.</p>
          ) : (
            <>
              <Row label="Asked" value={formatCount(questions.asked)} />
              <Row label="Answered" value={formatCount(questions.answered)} />
              <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                {questions.per100Runs !== null ? `${questions.per100Runs} for every 100 runs. ` : ''}
                {questions.medianAnswerSeconds !== null
                  ? `A person took ${formatSeconds(questions.medianAnswerSeconds)} to answer, at the median.`
                  : 'None has been answered yet.'}
              </p>
            </>
          )}
        </Section>

        <Section title="Why runs failed">
          {failureReasons.length === 0 ? (
            <p className="muted">No run failed in this window.</p>
          ) : (
            <ol className="stack" style={{ gap: 'var(--space-2)', margin: 0, paddingLeft: 'var(--space-5)' }}>
              {failureReasons.map((row) => (
                <li key={row.reason} style={{ overflowWrap: 'anywhere' }}>
                  {row.reason} <span className="caption muted">{formatCount(row.count)}</span>
                </li>
              ))}
            </ol>
          )}
        </Section>
      </div>
    </Card>
  )
}
