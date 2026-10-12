// @find: chart card, bar chart, line chart, pie chart, svg chart in answer, chart with data table, accessible chart, ChartCard
// @what: Draws a bar, line or pie chart from an agent's answer in plain SVG, with a legend and the same figures in a table that anyone can open.
// @flow: Rendered by AnswerBlockCard for a chart block; scale and number wording from lib/answerBlocks; colours from the chart tokens.
import type { RefObject } from 'react'
import { useId, useLayoutEffect, useRef, useState } from 'react'
import type { ChartBlock } from '../../lib/answerBlocks'
import { chartScale, formatChartTick, formatChartValue, isSymbolUnit } from '../../lib/answerBlocks'
import { CardShell } from './CardShell'

/*
 * Plain SVG and the chart tokens, like the Analytics page: no chart library. The drawing is sized
 * to the box it sits in (measured, not scaled), so axis text stays readable on a phone instead of
 * shrinking with the picture. Every series and slice is told apart by more than colour - a dash
 * pattern on lines, a stripe or dot pattern on the second and third bars, a legend with the figures,
 * a tooltip on each mark - and every figure is in a table: hidden from sight but read by a screen
 * reader, and shown to anyone by "Show the figures" (a phone has no hover, and drops axis labels
 * that do not fit).
 */

const HEIGHT = 220
const RIGHT = 12
const TOP = 12
const BOTTOM = 30
/** Dash patterns for the second and third line, so lines differ without colour. */
const DASHES = [undefined, '6 4', '2 3'] as const

/** About how wide one character of axis text is, in pixels (11.5px Inter; digits are the widest common glyphs). */
const AXIS_CHAR = 7

/**
 * The left margin for the value axis: wide enough for its longest label (measured by characters,
 * since nothing is laid out yet when the drawing is planned), never narrower than 28px nor wider
 * than a third of the drawing.
 */
export function axisMargin(labels: readonly string[], width: number): number {
  const longest = Math.max(0, ...labels.map((label) => label.length))
  return Math.min(Math.round(width / 3), Math.max(28, Math.ceil(longest * AXIS_CHAR) + 12))
}

/** The bar pattern of series `index`: none for the first, stripes for the second, dots for the third. */
function patternOf(prefix: string, index: number): string | null {
  return index === 1 ? `${prefix}-stripes` : index === 2 ? `${prefix}-dots` : null
}

/** The stripe and dot patterns laid over the second and third bars, drawn in the surface colour so they show on any fill. */
function BarPatterns({ prefix }: { prefix: string }) {
  return (
    <defs>
      <pattern id={`${prefix}-stripes`} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <rect width="2" height="6" className="answer-chart-pattern" />
      </pattern>
      <pattern id={`${prefix}-dots`} width="6" height="6" patternUnits="userSpaceOnUse">
        <circle cx="3" cy="3" r="1.3" className="answer-chart-pattern" />
      </pattern>
    </defs>
  )
}

/** The fill or stroke colour of series or slice `index`, from the CSS variables answers.css sets on the chart. */
function tone(kind: 'series' | 'slice', index: number): string {
  return `var(--answer-${kind}-${index})`
}

/** A label short enough for the axis; the full text stays in the tooltip and the table. */
function shortLabel(label: string, max = 12): string {
  return label.length > max ? `${label.slice(0, max - 1)}…` : label
}

/**
 * The drawing's width: measured once before the first paint, then followed as the window or panel
 * changes size. 480 where nothing can be measured (a test, or a box not laid out yet).
 */
function useBoxWidth(): [RefObject<HTMLDivElement | null>, number] {
  const ref = useRef<HTMLDivElement | null>(null)
  const [width, setWidth] = useState(480)
  useLayoutEffect(() => {
    const node = ref.current
    if (!node) return
    const apply = (measured: number) => {
      if (measured > 0) setWidth(Math.max(240, Math.round(measured)))
    }
    apply(node.getBoundingClientRect().width)
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver((entries) => apply(entries[0]?.contentRect.width ?? 0))
    observer.observe(node)
    return () => observer.disconnect()
  }, [])
  return [ref, width]
}

/** The value axis of a bar or line chart: its scale, its labels and the left margin that fits them. */
type Axis = { low: number; high: number; ticks: number[]; labels: string[]; left: number }

/** Plans the value axis for these values: a round scale, short labels (formatChartTick) and a margin wide enough for the longest. */
function axisFor(block: ChartBlock, width: number): Axis {
  const scale = chartScale(block.series.flatMap((series) => series.values))
  const largest = Math.max(Math.abs(scale.low), Math.abs(scale.high))
  const labels = scale.ticks.map((tick) => formatChartTick(tick, block.unit, largest))
  return { ...scale, labels, left: axisMargin(labels, width) }
}

/** Gridlines and their value labels, shared by the bar and line charts; the zero line is drawn stronger. */
function Grid({ axis, y, width }: { axis: Axis; y: (value: number) => number; width: number }) {
  return (
    <g>
      {axis.ticks.map((tick, index) => (
        <g key={tick}>
          <line x1={axis.left} x2={width - RIGHT} y1={y(tick)} y2={y(tick)} className={tick === 0 ? 'answer-chart-zero' : 'answer-chart-grid'} />
          <text x={axis.left - 6} y={y(tick)} textAnchor="end" dominantBaseline="middle" className="answer-chart-axis">
            {axis.labels[index]}
          </text>
        </g>
      ))}
    </g>
  )
}

/** The labels under the bars or points: every one when they fit, otherwise every second or third. */
function XLabels({ labels, x, width, left }: { labels: string[]; x: (index: number) => number; width: number; left: number }) {
  const room = Math.max(1, Math.floor((width - left - RIGHT) / 64))
  const every = Math.max(1, Math.ceil(labels.length / room))
  return (
    <g>
      {labels.map((label, index) =>
        index % every === 0 ? (
          <text key={index} x={x(index)} y={HEIGHT - 10} textAnchor="middle" className="answer-chart-axis">
            {shortLabel(label)}
          </text>
        ) : null,
      )}
    </g>
  )
}

/** Grouped bars: one group per label, one bar per series, growing up from zero (or down, for a negative value). */
function Bars({ block, width, patterns }: { block: ChartBlock; width: number; patterns: string }) {
  const axis = axisFor(block, width)
  const inner = HEIGHT - TOP - BOTTOM
  const y = (value: number) => TOP + inner - ((value - axis.low) / (axis.high - axis.low)) * inner
  const slot = (width - axis.left - RIGHT) / block.labels.length
  const group = slot * 0.72
  const bar = group / block.series.length
  const x = (index: number) => axis.left + slot * index + slot / 2
  return (
    <>
      {block.series.length > 1 && <BarPatterns prefix={patterns} />}
      <Grid axis={axis} y={y} width={width} />
      {block.labels.map((label, index) => (
        <g key={index}>
          {block.series.map((series, seriesIndex) => {
            const value = series.values[index]!
            const top = Math.min(y(0), y(value))
            const pattern = patternOf(patterns, seriesIndex)
            const shape = {
              x: x(index) - group / 2 + bar * seriesIndex,
              y: top,
              width: Math.max(1, bar - 1),
              height: Math.max(value === 0 ? 0 : 1, Math.abs(y(value) - y(0))),
            }
            return (
              <g key={seriesIndex}>
                <rect {...shape} style={{ fill: tone('series', seriesIndex) }}>
                  <title>{`${label}, ${series.name}: ${formatChartValue(value, block.unit)}`}</title>
                </rect>
                {pattern && <rect {...shape} fill={`url(#${pattern})`} pointerEvents="none" aria-hidden="true" />}
              </g>
            )
          })}
        </g>
      ))}
      <XLabels labels={block.labels} x={x} width={width} left={axis.left} />
    </>
  )
}

/** One line per series through a point per label, each with its own dash pattern and a marked point with a tooltip. */
function Lines({ block, width }: { block: ChartBlock; width: number }) {
  const axis = axisFor(block, width)
  const inner = HEIGHT - TOP - BOTTOM
  const y = (value: number) => TOP + inner - ((value - axis.low) / (axis.high - axis.low)) * inner
  const slot = (width - axis.left - RIGHT) / block.labels.length
  const x = (index: number) => axis.left + slot * index + slot / 2
  return (
    <>
      <Grid axis={axis} y={y} width={width} />
      {block.series.map((series, seriesIndex) => (
        <g key={seriesIndex}>
          <polyline
            points={series.values.map((value, index) => `${x(index)},${y(value)}`).join(' ')}
            fill="none"
            strokeWidth={2}
            strokeLinejoin="round"
            strokeLinecap="round"
            strokeDasharray={DASHES[seriesIndex]}
            style={{ stroke: tone('series', seriesIndex) }}
          />
          {series.values.map((value, index) => (
            <circle key={index} cx={x(index)} cy={y(value)} r={3.5} className="answer-chart-point" style={{ fill: tone('series', seriesIndex) }}>
              <title>{`${block.labels[index]}, ${series.name}: ${formatChartValue(value, block.unit)}`}</title>
            </circle>
          ))}
        </g>
      ))}
      <XLabels labels={block.labels} x={x} width={width} left={axis.left} />
    </>
  )
}

/** The SVG path of one pie slice from angle `start` to `end` (radians, clockwise from twelve o'clock). */
function slicePath(cx: number, cy: number, radius: number, start: number, end: number): string {
  const point = (angle: number) => `${cx + radius * Math.sin(angle)} ${cy - radius * Math.cos(angle)}`
  const large = end - start > Math.PI ? 1 : 0
  return `M ${cx} ${cy} L ${point(start)} A ${radius} ${radius} 0 ${large} 1 ${point(end)} Z`
}

/** A pie of one series: a slice per label in proportion to its share, a whole circle when one label holds everything. */
function Pie({ block, width }: { block: ChartBlock; width: number }) {
  const values = block.series[0]!.values
  const total = values.reduce((sum, value) => sum + value, 0)
  const radius = Math.min(HEIGHT / 2 - 12, width / 2 - 12)
  const cx = width / 2
  const cy = HEIGHT / 2
  // Where each slice starts and ends, worked out before drawing: the running total of the shares before it.
  const ends = values.map((_, index) => (values.slice(0, index + 1).reduce((sum, value) => sum + value, 0) / total) * Math.PI * 2)
  return (
    <>
      {values.map((value, index) => {
        const start = index === 0 ? 0 : ends[index - 1]!
        const end = ends[index]!
        const share = value / total
        const title = `${block.labels[index]}: ${formatChartValue(value, block.unit)} (${Math.round(share * 100)}%)`
        if (value <= 0) return null
        return share >= 0.9999 ? (
          <circle key={index} cx={cx} cy={cy} r={radius} className="answer-chart-slice" style={{ fill: tone('slice', index) }}>
            <title>{title}</title>
          </circle>
        ) : (
          <path key={index} d={slicePath(cx, cy, radius, start, end)} className="answer-chart-slice" style={{ fill: tone('slice', index) }}>
            <title>{title}</title>
          </path>
        )
      })}
    </>
  )
}

/** The key under the chart: each series (or slice, with its share) beside its colour, and for lines its dash pattern too. */
function Legend({ block, patterns }: { block: ChartBlock; patterns: string }) {
  if (block.chartType === 'pie') {
    const values = block.series[0]!.values
    const total = values.reduce((sum, value) => sum + value, 0)
    return (
      <ul className="answer-chart-legend" aria-hidden="true">
        {block.labels.map((label, index) => (
          <li key={index}>
            <span className="answer-chart-swatch" style={{ background: tone('slice', index) }} />
            {label}: {formatChartValue(values[index]!, block.unit)} ({Math.round((values[index]! / total) * 100)}%)
          </li>
        ))}
      </ul>
    )
  }
  if (block.series.length < 2) return null
  return (
    <ul className="answer-chart-legend" aria-hidden="true">
      {block.series.map((series, index) => (
        <li key={index}>
          {block.chartType === 'line' ? (
            <svg width="22" height="8" viewBox="0 0 22 8">
              <line x1="1" x2="21" y1="4" y2="4" strokeWidth="2" strokeDasharray={DASHES[index]} style={{ stroke: tone('series', index) }} />
            </svg>
          ) : (
            <svg className="answer-chart-swatch" width="10" height="10" viewBox="0 0 10 10">
              <rect width="10" height="10" style={{ fill: tone('series', index) }} />
              {patternOf(patterns, index) && <rect width="10" height="10" fill={`url(#${patternOf(patterns, index)})`} />}
            </svg>
          )}
          {series.name}
        </li>
      ))}
    </ul>
  )
}

/** Every figure as a table: hidden from sight but read by a screen reader in place of the picture, or shown (`shown`) when the person opens it. */
function FiguresTable({ block, caption, shown, id }: { block: ChartBlock; caption: string; shown: boolean; id: string }) {
  const pie = block.chartType === 'pie'
  const total = pie ? block.series[0]!.values.reduce((sum, value) => sum + value, 0) : 0
  return (
    <table className={shown ? 'answer-chart-figures' : 'visually-hidden'} id={id}>
      <caption>{caption}</caption>
      <thead>
        <tr>
          <th scope="col">Label</th>
          {block.series.map((series, index) => (
            <th key={index} scope="col">
              {series.name}
            </th>
          ))}
          {pie && <th scope="col">Share</th>}
        </tr>
      </thead>
      <tbody>
        {block.labels.map((label, index) => (
          <tr key={index}>
            <th scope="row">{label}</th>
            {block.series.map((series, seriesIndex) => (
              <td key={seriesIndex}>{formatChartValue(series.values[index]!, block.unit)}</td>
            ))}
            {pie && <td>{Math.round((block.series[0]!.values[index]! / total) * 100)}%</td>}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

const TYPE_NAMES = { bar: 'Bar chart', line: 'Line chart', pie: 'Pie chart' } as const

// @find: ChartCard, svg chart card, accessible chart
/**
 * The chart: its title, the drawing (an image named by the title, with a sentence saying where the
 * figures are), a legend when there is more than one series or for a pie, a line naming a word
 * unit once ("Figures in hours", where the axis shows only numbers), and the figures as a table -
 * visually hidden until "Show the figures" opens it. Read-only; it sends nothing.
 */
export function ChartCard({ block }: { block: ChartBlock }) {
  const id = useId()
  const patterns = `${id.replace(/[^\w-]/g, '')}-pattern`
  const [box, width] = useBoxWidth()
  const [figuresShown, setFiguresShown] = useState(false)
  const name = block.title || TYPE_NAMES[block.chartType]
  const wordUnit = block.chartType !== 'pie' && block.unit && block.unit !== '%' && !isSymbolUnit(block.unit) ? block.unit : ''
  return (
    <CardShell kind={TYPE_NAMES[block.chartType]} title={block.title}>
      <figure className="answer-chart">
        <div ref={box} className="answer-chart-box">
          <svg
            width="100%"
            height={HEIGHT}
            viewBox={`0 0 ${width} ${HEIGHT}`}
            role="img"
            aria-labelledby={`${id}-title ${id}-desc`}
            style={{ display: 'block' }}
          >
            <title id={`${id}-title`}>{name}</title>
            <desc id={`${id}-desc`}>The figures are in the table that follows.</desc>
            {block.chartType === 'bar' && <Bars block={block} width={width} patterns={patterns} />}
            {block.chartType === 'line' && <Lines block={block} width={width} />}
            {block.chartType === 'pie' && <Pie block={block} width={width} />}
          </svg>
        </div>
        {wordUnit && <p className="answer-card-note">Figures in {wordUnit}.</p>}
        <Legend block={block} patterns={patterns} />
        <div className="answer-card-actions">
          <button
            type="button"
            className="button button-quiet button-sm"
            aria-expanded={figuresShown}
            aria-controls={`${id}-figures`}
            onClick={() => setFiguresShown((shown) => !shown)}
          >
            {figuresShown ? 'Hide the figures' : 'Show the figures'}
          </button>
        </div>
        <FiguresTable block={block} caption={name} shown={figuresShown} id={`${id}-figures`} />
      </figure>
    </CardShell>
  )
}
