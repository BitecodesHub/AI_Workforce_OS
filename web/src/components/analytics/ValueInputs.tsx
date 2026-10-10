// @find: value settings, hourly rate, minutes saved per run, time saved, value estimate, ROI inputs, save value settings, ValueInputs, parseRate, parseMinutes
// @what: Form for the hourly rate and minutes-saved assumptions behind the value estimate.
// @flow: Rendered on Analytics; uses useValueSettings and useSaveValueSettings
import { useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Card, Eyebrow, Input, Notice } from '../ui'
import { QueryState } from '../ui/QueryState'
import { describeApiError } from '../../lib/api'
import { formatCount, formatMoney } from '../../lib/format'
import type { Insights, ValueSettings } from '../../lib/insightsQueries'
import { VALUE_LABEL, useSaveValueSettings, useValueSettings } from '../../lib/insightsQueries'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'
import { NOT_ESTIMATED, formatHours } from './figures'

/*
 * The two things only the workspace knows, which turn "tasks completed" into "hours returned":
 * what an hour of a person's time costs, and how long a person would take over one of each agent's
 * tasks. Both are estimates somebody typed, never measurements, and every figure worked out from
 * them says so.
 *
 * Anyone who can see Analytics sees the inputs beside the figures they produce; changing them
 * needs budget:manage, since they decide what the workforce is said to have saved. An agent with
 * no minutes shows as not estimated and adds nothing, never a zero.
 */

// @find: MAX_MINUTES, limit for minutes saved
export const MAX_MINUTES = 2_400
export const MAX_HOURLY_RATE = 10_000

/** An hourly cost typed in: null for empty, a number in range, or NaN for anything else. */
// @find: parseRate, parse hourly rate input
export function parseRate(entered: string): number | null {
  const trimmed = entered.trim().replace(/^US?\$/i, '').replace(/,/g, '')
  if (trimmed === '') return null
  const value = Number(trimmed)
  return Number.isFinite(value) && value >= 0 && value <= MAX_HOURLY_RATE ? value : Number.NaN
}

/** Minutes typed in: null for empty, a whole number from 1 to a working day, or NaN for anything else. */
// @find: parseMinutes, parse minutes saved input
export function parseMinutes(entered: string): number | null {
  const trimmed = entered.trim()
  if (trimmed === '') return null
  const value = Number(trimmed)
  return Number.isInteger(value) && value >= 1 && value <= MAX_MINUTES ? value : Number.NaN
}

function Estimate({ value }: { value: Insights['value'] }) {
  if (value === null) {
    return (
      <p className="muted" style={{ margin: 0 }}>
        {NOT_ESTIMATED}. Enter the minutes a person would take over one task below, and the hours returned appear
        above.
      </p>
    )
  }
  return (
    <div className="stack" style={{ gap: 'var(--space-2)' }}>
      <p style={{ margin: 0 }}>
        {formatHours(value.hoursReturned)} returned over {formatCount(value.completedTasks)} finished{' '}
        {value.completedTasks === 1 ? 'task' : 'tasks'}.
        {value.humanEquivalentCost !== null && ` A person would have cost about ${formatMoney(value.humanEquivalentCost)}.`}
        {value.netValue !== null && ` After what the agents cost, that is about ${formatMoney(value.netValue)}.`}
      </p>
      <p className="caption" style={{ margin: 0 }}>
        {VALUE_LABEL}.
        {value.agentsNotEstimated > 0 &&
          ` ${formatCount(value.agentsNotEstimated)} ${value.agentsNotEstimated === 1 ? 'agent has' : 'agents have'} finished tasks with no estimate, and ${value.agentsNotEstimated === 1 ? 'adds' : 'add'} nothing here.`}
        {value.hourlyRate === null && ' With no hourly cost, only hours are shown.'}
      </p>
    </div>
  )
}

function InputsForm({ settings }: { settings: ValueSettings }) {
  const save = useSaveValueSettings()
  const toast = useToast()
  const [rate, setRate] = useState(settings.hourlyRate === null ? '' : String(settings.hourlyRate))
  const [minutes, setMinutes] = useState<Record<string, string>>(
    Object.fromEntries(settings.agents.map((agent) => [agent.agentId, agent.minutesPerTask === null ? '' : String(agent.minutesPerTask)])),
  )
  const [problems, setProblems] = useState<Record<string, string>>({})
  const [failure, setFailure] = useState<string | null>(null)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const found: Record<string, string> = {}
    const parsedRate = parseRate(rate)
    if (Number.isNaN(parsedRate)) found.rate = `Enter an amount in dollars between 0 and ${formatCount(MAX_HOURLY_RATE)}, or leave it empty.`
    const perAgent: Record<string, number> = {}
    for (const agent of settings.agents) {
      const parsed = parseMinutes(minutes[agent.agentId] ?? '')
      if (Number.isNaN(parsed)) found[agent.agentId] = `Enter whole minutes from 1 to ${formatCount(MAX_MINUTES)}, or leave it empty.`
      else if (parsed !== null) perAgent[agent.agentId] = parsed
    }
    setProblems(found)
    setFailure(null)
    if (Object.keys(found).length > 0) return
    try {
      await save.mutateAsync({ hourlyRate: parsedRate, minutesPerTask: perAgent })
      toast.success('Value inputs saved.')
    } catch (error) {
      setFailure(describeApiError(error))
    }
  }

  return (
    <form onSubmit={submit} noValidate aria-label="Value inputs" className="stack" style={{ gap: 'var(--space-4)' }}>
      <Input
        label="Loaded hourly cost of a person (US$)"
        inputMode="decimal"
        value={rate}
        onChange={(event) => setRate(event.target.value)}
        hint="What an hour of staff time costs you, with on-costs. Empty means hours only."
        error={problems.rate}
        autoComplete="off"
      />
      {settings.agents.length === 0 ? (
        <p className="muted" style={{ margin: 0 }}>
          There is no agent to estimate yet.
        </p>
      ) : (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 240px), 1fr))',
            gap: 'var(--space-4)',
          }}
        >
          {settings.agents.map((agent) => (
            <Input
              key={agent.agentId}
              label={`Minutes a person takes over one ${agent.name} task`}
              inputMode="numeric"
              value={minutes[agent.agentId] ?? ''}
              onChange={(event) => setMinutes((current) => ({ ...current, [agent.agentId]: event.target.value }))}
              hint="Empty means not estimated"
              error={problems[agent.agentId]}
              autoComplete="off"
            />
          ))}
        </div>
      )}
      {failure && <Notice tone="warning" live>{failure}</Notice>}
      <div>
        <Button type="submit" loading={save.isPending}>
          Save inputs
        </Button>
      </div>
    </form>
  )
}

function ReadOnlyInputs({ settings }: { settings: ValueSettings }) {
  const estimated = settings.agents.filter((agent) => agent.minutesPerTask !== null)
  return (
    <div className="stack" style={{ gap: 'var(--space-2)' }}>
      <p style={{ margin: 0 }}>
        Hourly cost of a person:{' '}
        {settings.hourlyRate === null ? NOT_ESTIMATED.toLowerCase() : formatMoney(settings.hourlyRate)}.
      </p>
      {estimated.length === 0 ? (
        <p className="muted" style={{ margin: 0 }}>
          No agent has a minutes-per-task estimate.
        </p>
      ) : (
        <ul style={{ margin: 0, paddingLeft: 'var(--space-5)' }}>
          {estimated.map((agent) => (
            <li key={agent.agentId}>
              {agent.name}: {agent.minutesPerTask} minutes per task
            </li>
          ))}
        </ul>
      )}
      <p className="caption" style={{ margin: 0 }}>
        Only an administrator can change these.
      </p>
    </div>
  )
}

// @find: ValueInputs, edit hourly rate and minutes saved, save value settings
export function ValueInputs({ value }: { value: Insights['value'] }) {
  const settings = useValueSettings()
  const canManage = can('budget:manage')
  return (
    <section id="inputs" aria-labelledby="inputs-heading" style={{ scrollMarginTop: 'var(--space-8)' }}>
      <Card>
        <Eyebrow as="h2" id="inputs-heading">
          Value inputs
        </Eyebrow>
        <div className="stack" style={{ gap: 'var(--space-5)' }}>
          <p className="caption" style={{ margin: 0 }}>
            Two numbers you enter, used to estimate the hours the agents returned. They are not measured, so every
            figure worked out from them is labelled as an estimate.
          </p>
          <Estimate value={value} />
          <QueryState query={settings} permission="analytics:read" what="the value inputs" rows={2}>
            {(loaded) =>
              !loaded ? (
                <p className="muted">The value inputs could not be read.</p>
              ) : canManage ? (
                // Keyed on what is stored, so a save made elsewhere replaces the form's text.
                <InputsForm
                  key={JSON.stringify([loaded.hourlyRate, loaded.agents.map((agent) => [agent.agentId, agent.minutesPerTask])])}
                  settings={loaded}
                />
              ) : (
                <ReadOnlyInputs settings={loaded} />
              )
            }
          </QueryState>
        </div>
      </Card>
    </section>
  )
}
