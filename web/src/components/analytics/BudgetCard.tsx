import { useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Card, Eyebrow, Input, Notice, Select } from '../ui'
import { QueryState } from '../ui/QueryState'
import { describeApiError } from '../../lib/api'
import { formatDate, formatMoney } from '../../lib/format'
import type { Budget, BudgetInput } from '../../lib/insightsQueries'
import { CAP_WARNING_AT, capUsed, useBudget, useSaveBudget } from '../../lib/insightsQueries'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'
import { formatPercent } from './figures'

/*
 * What the workspace may spend on language models this month, what it has spent and where it is
 * heading, with the caps an administrator can set.
 *
 * Reading needs budget:read and changing needs budget:manage. The anchor is on the card from the
 * first paint, even while it loads, because the Orchestrator's "Spend today" tile links to
 * /analytics#budget and the router looks for the target only for a couple of seconds.
 *
 * Amounts are estimates in US dollars at catalogue prices, not what a provider's invoice says.
 */

/** The cap as the form shows it: plain digits, empty for no cap. */
function capText(cap: number | null): string {
  return cap === null ? '' : String(Number(cap.toFixed(4)))
}

/** A cap typed into the form: null for empty, or a number not below zero; NaN for anything else. */
export function parseCap(entered: string): number | null {
  const trimmed = entered.trim().replace(/^US?\$/i, '').replace(/,/g, '')
  if (trimmed === '') return null
  const value = Number(trimmed)
  return Number.isFinite(value) && value >= 0 ? value : Number.NaN
}

/** What the month looks like against its cap, in a sentence, or null when there is no cap to compare with. */
export function capSummary(budget: Budget): string | null {
  const used = capUsed(budget)
  if (used === null || budget.monthlyCap === null) return null
  return `${formatMoney(budget.spentThisMonth)} of ${formatMoney(budget.monthlyCap)} used this month (${formatPercent(Math.min(used, 9.99))})`
}

function Warning({ budget }: { budget: Budget }) {
  const used = capUsed(budget)
  if (used === null || used < CAP_WARNING_AT) return null
  const sandbox = budget.onExhausted === 'sandbox'
  if (used >= 1) {
    return (
      <Notice tone="warning">
        {`The monthly budget of ${formatMoney(budget.monthlyCap)} has been reached. `}
        {sandbox
          ? 'Runs that need a model now answer on the offline sandbox until the cap is raised or the month ends.'
          : 'Runs that need a model are stopped until the cap is raised or the month ends.'}
      </Notice>
    )
  }
  return (
    <Notice tone="warning">
      {`${formatPercent(used)} of the monthly budget is used. `}
      {sandbox
        ? 'At the cap, runs that need a model will answer on the offline sandbox.'
        : 'At the cap, runs that need a model will stop.'}
    </Notice>
  )
}

function Usage({ budget }: { budget: Budget }) {
  const used = capUsed(budget)
  const summary = capSummary(budget)
  return (
    <div className="stack" style={{ gap: 'var(--space-3)' }}>
      {summary && used !== null ? (
        <>
          <p style={{ margin: 0 }}>{summary}</p>
          <progress
            value={Math.min(used, 1)}
            max={1}
            aria-label="Share of the monthly budget used"
            style={{ width: '100%', height: 10, accentColor: 'var(--chart-primary)' }}
          />
          <p className="caption" style={{ margin: 0 }}>
            {budget.remaining !== null ? `${formatMoney(budget.remaining)} left. ` : ''}
            {budget.projectedMonthEnd !== null
              ? `At this pace the month comes to ${formatMoney(budget.projectedMonthEnd)}. `
              : ''}
            Counting from {formatDate(budget.periodStart)}.
          </p>
        </>
      ) : (
        <p style={{ margin: 0 }}>
          {formatMoney(budget.spentThisMonth)} spent this month, with no monthly cap set.
          {budget.projectedMonthEnd !== null
            ? ` At this pace the month comes to ${formatMoney(budget.projectedMonthEnd)}.`
            : ''}
        </p>
      )}
      {(budget.perRunCap !== null || budget.perAgentDailyCap !== null) && (
        <p className="caption" style={{ margin: 0 }}>
          {budget.perRunCap !== null ? `Each run is capped at ${formatMoney(budget.perRunCap)}. ` : ''}
          {budget.perAgentDailyCap !== null
            ? `Each agent is capped at ${formatMoney(budget.perAgentDailyCap)} a day.`
            : ''}
        </p>
      )}
    </div>
  )
}

function CapsForm({ budget }: { budget: Budget }) {
  const save = useSaveBudget()
  const toast = useToast()
  const [monthly, setMonthly] = useState(capText(budget.monthlyCap))
  const [perRun, setPerRun] = useState(capText(budget.perRunCap))
  const [perAgent, setPerAgent] = useState(capText(budget.perAgentDailyCap))
  const [onExhausted, setOnExhausted] = useState<BudgetInput['onExhausted']>(budget.onExhausted)
  const [problems, setProblems] = useState<Record<string, string>>({})
  const [failure, setFailure] = useState<string | null>(null)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const entered = { monthlyCap: monthly, perRunCap: perRun, perAgentDailyCap: perAgent }
    const next: Record<string, string> = {}
    const values: Record<string, number | null> = {}
    for (const [field, text] of Object.entries(entered)) {
      const value = parseCap(text)
      if (Number.isNaN(value)) next[field] = 'Enter an amount in dollars, such as 50, or leave it empty for no cap.'
      else values[field] = value
    }
    setProblems(next)
    setFailure(null)
    if (Object.keys(next).length > 0) return
    try {
      await save.mutateAsync({
        monthlyCap: values.monthlyCap ?? null,
        perRunCap: values.perRunCap ?? null,
        perAgentDailyCap: values.perAgentDailyCap ?? null,
        onExhausted,
      })
      toast.success('Spending caps saved.')
    } catch (error) {
      setFailure(describeApiError(error))
    }
  }

  return (
    <form onSubmit={submit} noValidate aria-label="Spending caps" className="stack" style={{ gap: 'var(--space-4)' }}>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 200px), 1fr))',
          gap: 'var(--space-4)',
        }}
      >
        <Input
          label="Monthly cap (US$)"
          inputMode="decimal"
          value={monthly}
          onChange={(event) => setMonthly(event.target.value)}
          hint="Empty means no cap"
          error={problems.monthlyCap}
          autoComplete="off"
        />
        <Input
          label="Cap per run (US$)"
          inputMode="decimal"
          value={perRun}
          onChange={(event) => setPerRun(event.target.value)}
          hint="Empty means no cap"
          error={problems.perRunCap}
          autoComplete="off"
        />
        <Input
          label="Cap per agent, per day (US$)"
          inputMode="decimal"
          value={perAgent}
          onChange={(event) => setPerAgent(event.target.value)}
          hint="Empty means no cap"
          error={problems.perAgentDailyCap}
          autoComplete="off"
        />
      </div>
      <Select
        label="When a cap is reached"
        value={onExhausted}
        onChange={(event) => setOnExhausted(event.target.value === 'sandbox' ? 'sandbox' : 'stop')}
        hint="Stopping is the safe choice. The offline sandbox answers with practice data, not a real model."
      >
        <option value="stop">Stop the work</option>
        <option value="sandbox">Answer on the offline sandbox</option>
      </Select>
      {failure && <Notice tone="warning" live>{failure}</Notice>}
      <div>
        <Button type="submit" loading={save.isPending}>
          Save caps
        </Button>
      </div>
    </form>
  )
}

function BudgetBody({ budget }: { budget: Budget }) {
  const canManage = can('budget:manage')
  return (
    <div className="stack" style={{ gap: 'var(--space-5)' }}>
      <Warning budget={budget} />
      <Usage budget={budget} />
      {canManage ? (
        // Keyed on the caps, so a save made in another tab is not overwritten by a stale form.
        <CapsForm
          key={[budget.monthlyCap, budget.perRunCap, budget.perAgentDailyCap, budget.onExhausted].join('|')}
          budget={budget}
        />
      ) : (
        <p className="caption" style={{ margin: 0 }}>
          Only an administrator can change the caps. Amounts are estimates at catalogue prices, not the provider&apos;s
          invoice.
        </p>
      )}
    </div>
  )
}

export function BudgetCard() {
  const canRead = can('budget:read')
  const budget = useBudget({ enabled: canRead })
  return (
    <section id="budget" aria-labelledby="budget-heading" style={{ scrollMarginTop: 'var(--space-8)' }}>
      <Card>
        <Eyebrow as="h2" id="budget-heading">
          Budget
        </Eyebrow>
        {canRead ? (
          <QueryState query={budget} permission="budget:read" what="the budget" rows={3}>
            {(loaded) => (loaded ? <BudgetBody budget={loaded} /> : <p className="muted">The budget could not be read.</p>)}
          </QueryState>
        ) : (
          <p className="muted">Spending caps are shown to people who can view the budget. Ask an administrator.</p>
        )}
      </Card>
    </section>
  )
}
