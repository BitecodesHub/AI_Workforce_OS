import { useId } from 'react'
import { Card, Eyebrow } from '../ui'
import { formatCount, formatMoney } from '../../lib/format'
import type { Insights } from '../../lib/insightsQueries'
import { dayLabel, niceMax } from './figures'

/*
 * The days of a window, drawn: how many goals finished each day, and what each day cost.
 *
 * Plain SVG, with every colour taken from the chart tokens (one accent and one supporting tone) and
 * no chart library. A chart is a picture of numbers a person may need to read, so each one carries
 * its figures in a table for assistive technology and in a tooltip on every bar.
 */

export type DayPoint = { day: string; completed: number; failed: number; cost: number }

/** The goal counts and the spend of each day on one list, oldest first. A day with neither is a zero. */
export function dayPoints(insights: Pick<Insights, 'goals' | 'spend'>): DayPoint[] {
  const points = new Map<string, DayPoint>()
  for (const row of insights.goals.byDay) {
    points.set(row.day, { day: row.day, completed: row.completed, failed: row.failed, cost: 0 })
  }
  for (const row of insights.spend.byDay) {
    const point = points.get(row.day) ?? { day: row.day, completed: 0, failed: 0, cost: 0 }
    point.cost = row.cost
    points.set(row.day, point)
  }
  return [...points.values()].sort((a, b) => a.day.localeCompare(b.day))
}

const WIDTH = 480
const HEIGHT = 190
const LEFT = 44
const RIGHT = 8
const TOP = 10
const BOTTOM = 26

type Series = { label: string; style: { fill: string }; value: (point: DayPoint) => number }

function BarChart({
  title,
  points,
  series,
  format,
  describe,
}: {
  title: string
  points: DayPoint[]
  /** Drawn bottom to top. */
  series: Series[]
  format: (value: number) => string
  /** The tooltip and the table row for one day. */
  describe: (point: DayPoint) => string
}) {
  const id = useId()
  const top = niceMax(Math.max(...points.map((point) => series.reduce((sum, one) => sum + one.value(point), 0)), 0))
  const innerWidth = WIDTH - LEFT - RIGHT
  const innerHeight = HEIGHT - TOP - BOTTOM
  const slot = innerWidth / Math.max(points.length, 1)
  const barWidth = Math.max(2, slot * 0.68)
  const every = Math.max(1, Math.ceil(points.length / 6))
  const y = (value: number) => TOP + innerHeight - (value / top) * innerHeight

  return (
    <figure style={{ margin: 0 }}>
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-labelledby={`${id}-title`}
        style={{ width: '100%', height: 'auto', display: 'block' }}
      >
        <title id={`${id}-title`}>{title}</title>
        {[0, 0.5, 1].map((fraction) => {
          const value = top * fraction
          return (
            <g key={fraction}>
              <line
                x1={LEFT}
                x2={WIDTH - RIGHT}
                y1={y(value)}
                y2={y(value)}
                style={{ stroke: 'var(--chart-grid)' }}
                strokeWidth={1}
              />
              <text
                x={LEFT - 6}
                y={y(value)}
                textAnchor="end"
                dominantBaseline="middle"
                style={{ fill: 'var(--muted)', fontFamily: 'var(--font-body)', fontSize: 11 }}
              >
                {format(value)}
              </text>
            </g>
          )
        })}
        {points.map((point, index) => {
          let floor = 0
          const x = LEFT + index * slot + (slot - barWidth) / 2
          return (
            <g key={point.day}>
              <title>{describe(point)}</title>
              {series.map((one) => {
                const value = one.value(point)
                const rect = (
                  <rect
                    key={one.label}
                    x={x}
                    y={y(floor + value)}
                    width={barWidth}
                    height={Math.max(0, y(floor) - y(floor + value))}
                    style={one.style}
                  />
                )
                floor += value
                return value > 0 ? rect : null
              })}
              {index % every === 0 && (
                <text
                  x={x + barWidth / 2}
                  y={HEIGHT - 8}
                  textAnchor="middle"
                  style={{ fill: 'var(--muted)', fontFamily: 'var(--font-body)', fontSize: 11 }}
                >
                  {dayLabel(point.day)}
                </text>
              )}
            </g>
          )
        })}
      </svg>
      {/* The same figures as text, for a screen reader and for anyone who wants the exact number. */}
      <table className="visually-hidden">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">Day</th>
            <th scope="col">Figures</th>
          </tr>
        </thead>
        <tbody>
          {points.map((point) => (
            <tr key={point.day}>
              <th scope="row">{dayLabel(point.day)}</th>
              <td>{describe(point)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  )
}

function Legend({ items }: { items: { label: string; style: { fill: string } }[] }) {
  return (
    <ul
      className="caption"
      style={{ display: 'flex', gap: 'var(--space-5)', listStyle: 'none', margin: 'var(--space-3) 0 0', padding: 0 }}
    >
      {items.map((item) => (
        <li key={item.label} style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)' }}>
          <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
            <rect width="10" height="10" style={item.style} />
          </svg>
          {item.label}
        </li>
      ))}
    </ul>
  )
}

const COMPLETED: Series = { label: 'Completed', style: { fill: 'var(--chart-primary)' }, value: (point) => point.completed }
const FAILED: Series = { label: 'Failed', style: { fill: 'var(--chart-secondary)' }, value: (point) => point.failed }
const SPEND: Series = { label: 'Spend', style: { fill: 'var(--chart-primary)' }, value: (point) => point.cost }

/** Goals finished each day, and what each day cost. */
export function DailyTrend({ insights }: { insights: Insights }) {
  const points = dayPoints(insights)
  const anyGoals = points.some((point) => point.completed + point.failed > 0)
  const anySpend = points.some((point) => point.cost > 0)

  return (
    <Card as="section">
      <Eyebrow as="h2">Day by day</Eyebrow>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 300px), 1fr))',
          gap: 'var(--space-6)',
        }}
      >
        <div>
          <h3 className="section-heading" style={{ marginBottom: 'var(--space-3)' }}>
            Goals finished
          </h3>
          {anyGoals ? (
            <>
              <BarChart
                title="Goals finished each day, completed and failed"
                points={points}
                series={[COMPLETED, FAILED]}
                format={(value) => formatCount(Math.round(value))}
                describe={(point) => `${point.completed} completed, ${point.failed} failed`}
              />
              <Legend items={[COMPLETED, FAILED]} />
            </>
          ) : (
            <p className="muted">No goal finished in this window.</p>
          )}
        </div>
        <div>
          <h3 className="section-heading" style={{ marginBottom: 'var(--space-3)' }}>
            Spend
          </h3>
          {anySpend ? (
            <>
              <BarChart
                title="Spend each day, at catalogue prices"
                points={points}
                series={[SPEND]}
                format={formatMoney}
                describe={(point) => formatMoney(point.cost)}
              />
              <Legend items={[SPEND]} />
            </>
          ) : (
            <p className="muted">Nothing was spent in this window.</p>
          )}
        </div>
      </div>
    </Card>
  )
}
