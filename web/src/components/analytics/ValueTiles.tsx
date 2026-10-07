import { StatRow, StatTile } from '../ui'
import { formatCount, formatMoney } from '../../lib/format'
import type { Insights, InsightsWindow } from '../../lib/insightsQueries'
import { VALUE_LABEL } from '../../lib/insightsQueries'
import { describeChange, formatHours, formatPercent, formatSeconds } from './figures'

/*
 * The six figures a manager opens Analytics for, over the window chosen: what got done, how often
 * it worked, what it cost, what a finished goal cost, how long people took to decide, and what the
 * work was worth in hours.
 *
 * Each tile says what it covers and how it moved, and a figure that is not known says so rather
 * than showing a zero: no finished work has no success rate, unpriced work has no cost, and hours
 * are an estimate that exists only once somebody has said what a task is worth.
 */

/** The unit line under a figure: where it covers more than the figure alone says. */
function join(parts: Array<string | false | null | undefined>): string {
  return parts.filter((part): part is string => Boolean(part)).join('. ')
}

export function ValueTiles({ insights, window }: { insights: Insights; window: InsightsWindow }) {
  const { goals, tasks, spend, approvals, value, deltas } = insights
  const finishedTasks = tasks.completed + tasks.failed

  const successRate = tasks.successRate
  const perGoal = spend.costPerCompletedGoal
  // What was left out of the cost per goal, so the figure is never read as covering all of them.
  const perGoalLeftOut = join([
    spend.unpricedGoals > 0 && `${formatCount(spend.unpricedGoals)} left out with no price`,
    spend.sandboxGoals > 0 && `${formatCount(spend.sandboxGoals)} left out on the offline sandbox`,
  ])

  return (
    <StatRow>
      <StatTile
        label="Work completed"
        value={formatCount(goals.completed)}
        unit="goals"
        note={join([
          describeChange(deltas.goalsCompleted, 'count', window),
          goals.failed > 0 && `${formatCount(goals.failed)} failed`,
        ])}
      />
      <StatTile
        label="Success rate"
        value={formatPercent(successRate)}
        note={
          successRate === null
            ? 'No task has finished in this window'
            : join([
                `Of ${formatCount(finishedTasks)} finished tasks`,
                tasks.withoutRetryRate !== null &&
                  `${formatPercent(tasks.withoutRetryRate)} completed without automatic retry`,
                describeChange(deltas.taskSuccessRate, 'points', window),
              ])
        }
      />
      <StatTile
        label="Spend"
        value={formatMoney(spend.total)}
        note={join([
          'Estimated at catalogue prices',
          describeChange(deltas.spend, 'money', window),
          spend.unpricedRuns > 0 &&
            `${formatCount(spend.unpricedRuns)} ${spend.unpricedRuns === 1 ? 'run was' : 'runs were'} unpriced, so this is a minimum`,
        ])}
        href="#budget"
      />
      <StatTile
        label="Cost per completed goal"
        value={perGoal === null ? 'Not enough priced goals' : formatMoney(perGoal)}
        note={
          perGoal === null
            ? join(['No completed goal has a priced run', perGoalLeftOut])
            : join([
                `Over ${formatCount(spend.costedGoals)} ${spend.costedGoals === 1 ? 'goal' : 'goals'}`,
                perGoalLeftOut,
                describeChange(deltas.costPerCompletedGoal, 'money', window),
              ])
        }
      />
      <StatTile
        label="Median approval wait"
        value={approvals.medianDecisionSeconds === null ? 'No decisions yet' : formatSeconds(approvals.medianDecisionSeconds)}
        note={
          approvals.medianDecisionSeconds === null
            ? join([approvals.raised > 0 && `${formatCount(approvals.raised)} raised, none decided`])
            : join([
                approvals.p90DecisionSeconds !== null && `Nine in ten were decided within ${formatSeconds(approvals.p90DecisionSeconds)}`,
                describeChange(deltas.medianApprovalSeconds, 'seconds', window),
              ])
        }
        href={approvals.pending > 0 ? '/approvals' : undefined}
      />
      <StatTile
        label="Hours returned"
        value={value === null || value.hoursReturned === null ? 'Not estimated' : formatHours(value.hoursReturned)}
        note={
          value === null || value.hoursReturned === null
            ? 'Say how long a person takes over one task to see this. Enter it under Value inputs below.'
            : join([VALUE_LABEL, describeChange(deltas.hoursReturned, 'hours', window)])
        }
        href={value === null || value.hoursReturned === null ? '#inputs' : undefined}
      />
    </StatRow>
  )
}
