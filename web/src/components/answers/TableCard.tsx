// @find: table card, sortable table, filter table rows, copy as csv, download csv, comparison table in answer, TableCard
// @what: A table from an agent's answer that the person can sort, filter, copy as CSV and download as a CSV file.
// @flow: Rendered by AnswerBlockCard for a table block; sorting, CSV and file names come from lib/answerBlocks.
import { useId, useMemo, useState } from 'react'
import { CopyButton } from '../ui/CopyButton'
import type { TableBlock } from '../../lib/answerBlocks'
import { compareCells, csvFileName, toCsv } from '../../lib/answerBlocks'
import { CardShell } from './CardShell'

type Sort = { column: number; direction: 'asc' | 'desc' } | null

/** A filter box is offered only above this many rows; a short table is read at a glance. */
const FILTER_FROM = 3

/** The next sort after a click on a column's heading: ascending, then descending, then the agent's own order. */
function nextSort(current: Sort, column: number): Sort {
  if (!current || current.column !== column) return { column, direction: 'asc' }
  return current.direction === 'asc' ? { column, direction: 'desc' } : null
}

// @find: downloadCsv, save csv file
/**
 * Saves CSV text as a file through a temporary link, with a byte order mark so a spreadsheet reads
 * accented letters correctly. Side effects: creates and revokes an object URL; nothing leaves the browser.
 */
export function downloadCsv(csv: string, fileName: string): void {
  const blob = new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  link.remove()
  // Kept a little while, so a browser that starts the download after this tick still finds the file.
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

/** The small arrow beside a column heading: up for ascending, down for descending, both when unsorted. Decorative; the heading's aria-sort says the state. */
function SortMark({ direction }: { direction: 'asc' | 'desc' | null }) {
  return (
    <svg className="answer-sort-mark" width="10" height="12" viewBox="0 0 10 12" fill="none" aria-hidden="true" data-direction={direction ?? 'none'}>
      {direction !== 'desc' && <path d="M5 1.5 8.5 5h-7z" fill="currentColor" />}
      {direction !== 'asc' && <path d="M5 10.5 1.5 7h7z" fill="currentColor" />}
    </svg>
  )
}

// @find: TableCard, sortable filterable table card
/**
 * The table, with a filter box (rows containing the words, in any column), headings that sort on
 * click (numbers as numbers, text in natural order, empty cells last), and Copy as CSV and Download
 * CSV for the rows as shown. The table scrolls sideways inside its own box on a narrow screen.
 * Sorting and filtering change only this view. Like the app's DataTable, a heading's sort state is
 * said by aria-sort alone (no hidden words repeated before every cell), and the table is named by a
 * caption: its title, or "Table".
 */
export function TableCard({ block }: { block: TableBlock }) {
  const filterId = useId()
  const [filter, setFilter] = useState('')
  const [sort, setSort] = useState<Sort>(null)

  const visible = useMemo(() => {
    const words = filter.trim().toLowerCase().split(/\s+/).filter(Boolean)
    const rows = words.length
      ? block.rows.filter((row) => {
          const line = row.join(' ').toLowerCase()
          return words.every((word) => line.includes(word))
        })
      : [...block.rows]
    if (sort) rows.sort((a, b) => compareCells(a[sort.column] ?? '', b[sort.column] ?? '', sort.direction))
    return rows
  }, [block.rows, filter, sort])

  const csv = () => toCsv(block.columns, visible)
  const filtered = visible.length !== block.rows.length

  return (
    <CardShell kind="Table" title={block.title}>
      {block.rows.length > FILTER_FROM && (
        <div className="answer-table-filter">
          <label className="field-label" htmlFor={filterId}>
            Filter rows
          </label>
          <input
            id={filterId}
            className="input"
            type="search"
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
            placeholder="Type to show matching rows"
            autoComplete="off"
          />
        </div>
      )}
      <div className="md-table-scroll answer-table-scroll" tabIndex={0} role="group" aria-label={block.title ? `${block.title}, scrollable table` : 'Scrollable table'}>
        <table>
          <caption className="visually-hidden">{block.title || 'Table'}</caption>
          <thead>
            <tr>
              {block.columns.map((column, index) => {
                const sorted = sort?.column === index ? sort.direction : null
                return (
                  <th key={index} scope="col" aria-sort={sorted === 'asc' ? 'ascending' : sorted === 'desc' ? 'descending' : 'none'}>
                    <button type="button" className="answer-sort" onClick={() => setSort((current) => nextSort(current, index))}>
                      {column}
                      <SortMark direction={sorted} />
                    </button>
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody>
            {visible.map((row, rowIndex) => (
              <tr key={rowIndex}>
                {row.map((value, cellIndex) => (
                  <td key={cellIndex}>{value}</td>
                ))}
              </tr>
            ))}
            {visible.length === 0 && (
              <tr>
                <td colSpan={block.columns.length} className="answer-card-note">
                  No rows match this filter.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="answer-card-note" role="status">
        {filtered
          ? `Showing ${visible.length} of ${block.rows.length} rows. Copy and download use the rows shown.`
          : `${block.rows.length} ${block.rows.length === 1 ? 'row' : 'rows'}. ${block.rows.length > FILTER_FROM ? 'Sorting and filtering change' : 'Sorting changes'} only this view.`}
      </p>
      <div className="answer-card-actions">
        <CopyButton text={csv} label="Copy as CSV" copiedLabel="CSV copied" variant="text" />
        <button type="button" className="button button-quiet button-sm" onClick={() => downloadCsv(csv(), csvFileName(block.title))}>
          Download CSV
        </button>
      </div>
    </CardShell>
  )
}
