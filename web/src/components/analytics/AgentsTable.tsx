// @find: agents table, agent performance, per agent success rate, per agent cost, runs per agent, analytics agents, AgentsTable
// @what: Analytics table comparing each agent by runs, success rate, cost and time.
// @flow: Rendered on the Analytics page from Insights data
import { useMemo } from 'react'
import { Card, DataTable, EmptyState, Eyebrow, Tag, Time } from '../ui'
import type { Column } from '../ui'
import { EmptyIcon } from '../ui/QueryState'
import { formatCount, formatMoney } from '../../lib/format'
import { categoryTone } from '../../lib/labels'
import type { AgentInsight, InsightsWindow } from '../../lib/insightsQueries'
import { WINDOW_LABEL, VALUE_LABEL } from '../../lib/insightsQueries'
import { can } from '../../lib/session'
import { NOT_ESTIMATED, NOT_ENOUGH_RUNS, costText, formatPercent, hoursText, satisfactionText, successText } from './figures'

/*
 * Every agent side by side over the window: how often its runs finished well, what they cost, how
 * often a person turned its actions down, how people rated its answers and how many hours it
 * returned. Sortable by any column, so the agent that fails most or costs most is one click away.
 *
 * Agents with no runs in the window still have a row: an agent that has done nothing is a finding
 * too. A figure that is not known says so in words and sorts last, never as a zero.
 */

// @find: AgentsTable, agent performance table, success rate and cost per agent
export function AgentsTable({
  agents,
  window,
  hourlyRateSet,
}: {
  agents: AgentInsight[]
  window: InsightsWindow
  hourlyRateSet: boolean
}) {
  const canOpenAgent = can('agent:read')

  const columns = useMemo<Column<AgentInsight>[]>(
    () => [
      {
        key: 'agent',
        header: 'Agent',
        sortValue: (agent) => agent.name,
        render: (agent) => {
          const name = (
            <span className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap' }}>
              <Tag tone={categoryTone(agent.category ?? undefined)} withDot title={agent.name}>
                {agent.name}
              </Tag>
              {agent.status === 'paused' && <Tag tone="neutral">Paused</Tag>}
              {agent.status === 'retired' && <Tag tone="neutral">Retired</Tag>}
            </span>
          )
          return canOpenAgent ? (
            <a className="link" href={`/agents/${agent.agentId}`} style={{ textDecoration: 'none' }}>
              {name}
            </a>
          ) : (
            name
          )
        },
      },
      {
        key: 'runs',
        header: 'Runs',
        numeric: true,
        sortValue: (agent) => agent.runs,
        render: (agent) => formatCount(agent.runs),
      },
      {
        key: 'success',
        header: 'Success rate',
        numeric: true,
        // Agents without enough runs have no rate and sort last either way round.
        sortValue: (agent) => (agent.enoughRuns ? agent.successRate : null),
        render: (agent) =>
          agent.enoughRuns && agent.successRate !== null ? (
            <span
              title={`${formatCount(agent.completed)} completed of ${formatCount(agent.finishedRuns)} finished`}
            >
              {successText(agent)}
            </span>
          ) : (
            <span className="muted" title={`${formatCount(agent.finishedRuns)} finished so far; a rate needs five`}>
              {NOT_ENOUGH_RUNS}
            </span>
          ),
      },
      {
        key: 'failed',
        header: 'Failed',
        numeric: true,
        sortValue: (agent) => agent.failed,
        render: (agent) => formatCount(agent.failed),
      },
      {
        key: 'cost',
        header: 'Cost',
        numeric: true,
        sortValue: (agent) => agent.totalCost,
        render: (agent) => {
          const text = costText(agent)
          return agent.totalCost === null || agent.unpricedRuns > 0 ? (
            <span
              title={`${formatCount(agent.unpricedRuns)} ${agent.unpricedRuns === 1 ? 'run' : 'runs'} on a model with no price on file, so the true cost is higher`}
            >
              {text}
            </span>
          ) : (
            text
          )
        },
      },
      {
        key: 'average',
        header: 'Per completed run',
        numeric: true,
        sortValue: (agent) => agent.avgCostPerCompleted,
        render: (agent) =>
          agent.avgCostPerCompleted === null ? <span className="muted">—</span> : formatMoney(agent.avgCostPerCompleted),
      },
      {
        key: 'rejected',
        header: 'Actions turned down',
        numeric: true,
        sortValue: (agent) => agent.rejectedApprovals,
        render: (agent) => formatCount(agent.rejectedApprovals),
      },
      {
        key: 'satisfaction',
        header: 'Ratings',
        sortValue: (agent) => agent.satisfactionRate,
        render: (agent) => {
          const text = satisfactionText(agent)
          return agent.ratings === 0 ? (
            <span className="muted">{text}</span>
          ) : (
            // The short form is for the eye; a screen reader hears the sentence once, not both.
            <span style={{ whiteSpace: 'nowrap' }}>
              <span aria-hidden="true">
                {formatPercent(agent.satisfactionRate)}{' '}
                <span className="caption muted">
                  of {formatCount(agent.ratings)}
                  {agent.thumbsDown > 0 ? `, ${formatCount(agent.thumbsDown)} thumbs down` : ''}
                </span>
              </span>
              <span className="visually-hidden">{text}</span>
            </span>
          )
        },
      },
      {
        key: 'hours',
        header: 'Hours returned',
        numeric: true,
        sortValue: (agent) => (agent.minutesPerTask === null ? null : agent.hoursReturned),
        render: (agent) =>
          agent.minutesPerTask === null ? (
            <span className="muted" title="Set the minutes a person takes over one task under Value inputs">
              {NOT_ESTIMATED}
            </span>
          ) : (
            <span title={`${VALUE_LABEL}: ${formatCount(agent.completedTasks)} tasks at ${agent.minutesPerTask} minutes`}>
              {hoursText(agent)}
            </span>
          ),
      },
      {
        key: 'last',
        header: 'Last active',
        sortValue: (agent) => (agent.lastActive ? Date.parse(agent.lastActive) : null),
        render: (agent) =>
          agent.lastActive ? <Time iso={agent.lastActive} /> : <span className="muted">Never</span>,
      },
    ],
    [canOpenAgent],
  )

  return (
    <Card as="section">
      <Eyebrow as="h2">Agent by agent</Eyebrow>
      {agents.length === 0 ? (
        <EmptyState
          icon={<EmptyIcon kind="agent" />}
          title="No agents yet"
          body="Once an agent is added and given work, how it is doing will show here."
          titleAs="h3"
        />
      ) : (
        <>
          <DataTable
            columns={columns}
            rows={agents}
            getKey={(agent) => agent.agentId}
            caption={`How each agent did over the ${WINDOW_LABEL[window].toLowerCase()}, from the orchestrator's run and approval records.`}
          />
          <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
            Rates count finished runs only, and need five of them. Cost is estimated at catalogue prices.
            {hourlyRateSet ? ' Hours returned is an estimate from your inputs.' : ''}
          </p>
        </>
      )}
    </Card>
  )
}
