import { Card, EmptyState, Eyebrow, Notice, PageHeader, StatRow, StatTile, StatusTag, Tag, Button } from '../components/ui'
import type { TagTone } from '../components/ui'
import { BackLink, EmptyIcon, QueryState } from '../components/ui/QueryState'
import { formatDuration } from '../lib/api'
import { useRun, useRunSteps, useCancelRun } from '../lib/queries'
import { useToast } from '../lib/toast'
import type { RunStep } from '../lib/queries'

const KIND_TONE: Record<string, TagTone> = {
  model_call: 'blue',
  tool_call: 'operations',
  approval: 'warning',
  error: 'danger',
}

const KIND_LABEL: Record<string, string> = {
  model_call: 'Model',
  tool_call: 'Tool',
  approval: 'Approval',
  error: 'Error',
}

const DATE_TIME = new Intl.DateTimeFormat('en-AU', { dateStyle: 'medium', timeStyle: 'short' })

function detailText(detail: Record<string, unknown>, key: string): string | null {
  const value = detail[key]
  return typeof value === 'string' && value.trim() ? value : null
}

function detailStrings(detail: Record<string, unknown>, key: string): string[] {
  const value = detail[key]
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

function humanize(value: string): string {
  return value.replace(/_/g, ' ')
}

function formatTimestamp(value: string): string {
  return DATE_TIME.format(new Date(value))
}

function stepTitle(step: RunStep): string {
  return detailText(step.detail, 'tool') ?? humanize(step.kind)
}

function stepDescription(step: RunStep): string | null {
  return (
    detailText(step.detail, 'summary') ??
    detailText(step.detail, 'reason') ??
    detailText(step.detail, 'detail') ??
    detailText(step.detail, 'content')
  )
}

function stepAttempts(step: RunStep): string[] {
  return detailStrings(step.detail, 'attempts')
}

const CANCELLABLE_STATUSES = ['running', 'planning', 'pending', 'waiting_approval']

export function RunDetail({ id }: { id: string }) {
  const toast = useToast()
  const runQuery = useRun(id)
  const stepsQuery = useRunSteps(id)
  const cancelRun = useCancelRun()

  const handleCancel = async (runId: string) => {
    if (!window.confirm('Cancel this run? Any in-flight steps will be stopped.')) return
    try {
      await cancelRun.mutateAsync(runId)
      toast.success('Run cancelled')
      runQuery.refetch()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to cancel run'
      toast.error(message)
    }
  }

  const getBackLink = (trigger: string) => {
    if (trigger.startsWith('goal:')) return { href: '/tasks', label: 'Back to tasks' }
    return { href: '/', label: 'Back to Command Map' }
  }

  return (
    <div className="page">
      <QueryState query={runQuery} permission="run:read" what="this run" rows={4}>
        {(run) => {
          const backLink = getBackLink(run.trigger)
          return (
            <>
              <BackLink href={backLink.href} label={backLink.label} />
              <QueryState query={runQuery} permission="run:read" what="this run" rows={4}>
                {(run) => (
                  <>
                    <PageHeader
                      eyebrow={run.id}
                      title="Run trace"
                      description={`${humanize(run.trigger)}, started ${formatTimestamp(run.startedAt)}.`}
                      action={
                        <>
                          <StatusTag status={run.status} />
                          {CANCELLABLE_STATUSES.includes(run.status) && (
                            <Button
                              variant="outline"
                              style={{ marginLeft: 'var(--space-3)' }}
                              onClick={() => handleCancel(run.id)}
                            >
                              Cancel run
                            </Button>
                          )}
                        </>
                      }
                    />

                    {run.failureReason && <Notice tone="warning">{run.failureReason}</Notice>}

                    <StatRow>
                      <StatTile label="Steps" value={run.stepCount.toLocaleString('en-AU')} />
                      <StatTile
                        label="Duration"
                        value={formatDuration(run.startedAt, run.completedAt)}
                        note={run.completedAt ? 'From run start to finish' : 'From run start to now'}
                      />
                      <StatTile
                        label="Tokens"
                        value={(run.promptTokens + run.completionTokens).toLocaleString('en-AU')}
                        note={`${run.promptTokens.toLocaleString('en-AU')} prompt and ${run.completionTokens.toLocaleString('en-AU')} completion`}
                      />
                      <StatTile label="Cost" value={run.cost.toFixed(4)} unit="AUD" />
                    </StatRow>
                    <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                      Source: run step records written by the orchestrator as the run proceeded.
                    </p>

                    <section style={{ marginTop: 'var(--space-7)' }}>
                      <QueryState query={stepsQuery} permission="run:read" what="this run trace" rows={Math.max(4, Math.min(run.stepCount, 12))}>
                        {(steps) =>
                          steps.length > 0 ? (
                            <Card as="section">
                              <Eyebrow>What happened</Eyebrow>
                              <ol
                                className="stack"
                                style={{ gap: 'var(--space-5)', listStyle: 'none', margin: 0, padding: 0 }}
                              >
                                {steps.map((step) => {
                                  const description = stepDescription(step)
                                  const attempts = stepAttempts(step)
                                  const toolCalls = detailStrings(step.detail, 'toolCalls')
                                  return (
                                    <li
                                      key={step.id}
                                      style={{
                                        borderLeft: '1px solid var(--line)',
                                        paddingLeft: 'var(--space-5)',
                                        paddingBottom: 'var(--space-2)',
                                      }}
                                    >
                                      <div className="row" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-2)' }}>
                                        <Tag tone={KIND_TONE[step.kind] ?? 'neutral'}>
                                          {KIND_LABEL[step.kind] ?? humanize(step.kind)}
                                        </Tag>
                                        <span className="section-heading">{stepTitle(step)}</span>
                                        {step.durationMs > 0 && (
                                          <span className="caption tabular">
                                            {step.durationMs} <span className="stat-unit">ms</span>
                                          </span>
                                        )}
                                      </div>

                                      {description && <p className="muted" style={{ marginBottom: 'var(--space-3)' }}>{description}</p>}

                                      {step.provider && (
                                        <p className="mono muted" style={{ marginBottom: 'var(--space-2)' }}>
                                          {step.provider}
                                          {step.model ? `/${step.model}` : ''}
                                        </p>
                                      )}

                                      {toolCalls.length > 0 && (
                                        <p className="caption muted" style={{ marginBottom: 'var(--space-2)' }}>
                                          Tools requested: {toolCalls.join(', ')}
                                        </p>
                                      )}

                                      {attempts.length > 0 && (
                                        <ul className="stack" style={{ gap: 'var(--space-1)', margin: 0, paddingLeft: 'var(--space-5)' }}>
                                          {attempts.map((attempt, index) => (
                                            <li key={`${step.id}-attempt-${index}`} className="caption">
                                              {attempt}
                                            </li>
                                          ))}
                                        </ul>
                                      )}

                                      <p className="caption muted" style={{ marginTop: 'var(--space-2)', marginBottom: 0 }}>
                                        {formatTimestamp(step.occurredAt)}
                                      </p>
                                    </li>
                                  )
                                })}
                              </ol>
                            </Card>
                          ) : (
                            <Card as="section">
                              <EmptyState
                                icon={<EmptyIcon kind="task" />}
                                title="No steps recorded"
                                body="The orchestrator has not written any steps for this run."
                              />
                            </Card>
                          )
                        }
                      </QueryState>
                    </section>
                  </>
                )}
              </QueryState>
            </>
          )
        }}
      </QueryState>
    </div>
  )
}