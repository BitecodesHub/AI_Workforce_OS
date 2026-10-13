// @find: answer blocks, answer cards, interactive answer, email draft card, table card, chart card, checklist card, choices card, parse answer block, validate card json, card size limits, email-draft, csv export, resolveCards
// @what: Parses and validates the fenced answer blocks an agent may put in its Markdown answer (email-draft, table, chart, checklist, choices) into plain data for the answer cards.
// @flow: Called by components/ui/Markdown.tsx (resolveCards) and the cards in components/answers; the format is taught to chat agents by AgentRunner.ANSWER_BLOCKS_GUIDANCE.
import type { MdBlock } from './markdown'

/*
 * Answer blocks: a small, safe way for an agent to answer with something a person can act on,
 * not only read.
 *
 * The format. Inside its ordinary Markdown answer, an agent writes a fenced code block whose
 * language names the card and whose body is one JSON object:
 *
 *   ```email-draft
 *   {"to": "sam@example.com", "cc": "", "subject": "Welcome", "body": "Hello Sam, ..."}
 *   ```
 *
 *   email-draft  {"to": string | string[], "cc"?: string | string[], "subject": string, "body": string}
 *   table        {"title"?: string, "columns": string[], "rows": (string | number | boolean | null)[][]}
 *                or {"title"?: string, "csv": string}, or plain CSV with a header row instead of JSON
 *   chart        {"type": "bar" | "line" | "pie", "title"?: string, "unit"?: string, "labels": string[],
 *                 "series": [{"name": string, "values": number[]}]}  (pie: exactly one series)
 *   checklist    {"title"?: string, "items": [{"text": string, "done"?: boolean}] | string[]}
 *   choices      {"question": string, "options": string[]}  (two to four options)
 *
 * Safety. Nothing here produces HTML or a link: every value comes back as a plain string or number
 * that the cards render as React text, so "<script>" or "javascript:alert(1)" in any field is shown
 * as those characters and nothing more. Control characters and the invisible bidirectional
 * overrides that can disguise text are removed. The body must be valid JSON (except a table's plain
 * CSV), every field is checked for type, and every size is capped (LIMITS) so a runaway answer
 * cannot make the page slow. A block that fails any check is not shown as a card: the reader sees
 * the source as an ordinary code block with a short note, never a half-drawn card.
 *
 * Acting on a card. A card never sends, posts or changes anything itself. "Ask the agent to send
 * it", a ticked checklist and a choice are sent as the person's next chat message to the card's
 * agent, so the agent's run and the approval gate behind it decide what happens. The message has
 * two parts (CardRequest): a short sentence the person can read in the thread, and the card's
 * content as data, which the server gives the agent in a block it is told never to obey. Model-
 * written text in a draft therefore never reaches the agent as the person's own instruction. The
 * message also names its card (the answer and the card's place in it), so the server routes it to
 * that agent only, never as a schedule, and the card can show what was already sent from it.
 */

/** The card types, by the language name written after the opening fence. */
export const CARD_LANGUAGES = ['email-draft', 'table', 'chart', 'checklist', 'choices'] as const
export type CardLanguage = (typeof CARD_LANGUAGES)[number]

/** Every size a card may reach. Anything larger is refused rather than cut, so a card never silently drops content. */
export const LIMITS = {
  /** Characters inside one fence. */
  source: 20_000,
  /** Cards in one answer; later blocks show as code. */
  cardsPerAnswer: 8,
  /** A title, a question, an option, a checklist item, a column or series name. */
  text: 300,
  cell: 500,
  subject: 300,
  /** The message of an email draft; the server takes up to the same. */
  body: 10_000,
  /** The To or Cc field as one line of addresses, as the person may edit it; the server takes up to 6,000. */
  recipientsText: 6_000,
  recipients: 20,
  recipient: 254,
  columns: 12,
  rows: 200,
  chartLabels: 24,
  pieSlices: 8,
  series: 3,
  checklistItems: 30,
  minOptions: 2,
  maxOptions: 4,
} as const

export type EmailDraftBlock = { kind: 'email-draft'; to: string; cc: string; subject: string; body: string }
export type TableBlock = { kind: 'table'; title: string; columns: string[]; rows: string[][] }
export type ChartType = 'bar' | 'line' | 'pie'
export type ChartSeries = { name: string; values: number[] }
export type ChartBlock = { kind: 'chart'; chartType: ChartType; title: string; unit: string; labels: string[]; series: ChartSeries[] }
export type ChecklistItem = { text: string; done: boolean }
export type ChecklistBlock = { kind: 'checklist'; title: string; items: ChecklistItem[] }
export type ChoicesBlock = { kind: 'choices'; question: string; options: string[] }

/** One validated card, as plain data. */
export type AnswerBlock = EmailDraftBlock | TableBlock | ChartBlock | ChecklistBlock | ChoicesBlock

/** The outcome of reading one fence: a card, or the plain reason it cannot be shown as one. */
export type BlockResult = { ok: true; block: AnswerBlock } | { ok: false; reason: string }

/** A fence read in an answer (resolveCards): a card with its place among the answer's cards (from 0), or the reason it is not one. */
export type CardResult = { ok: true; block: AnswerBlock; index: number } | { ok: false; reason: string }

/** Thrown inside the readers below and turned into a refusal; never escapes this module. */
class Refusal extends Error {}

/** Refuses the block with a reason a person can read under the code block. */
function refuse(reason: string): never {
  throw new Refusal(reason)
}

/** Whether a fence's language names a card (case does not matter). */
// @find: isCardLanguage, card fence language
export function isCardLanguage(language: string | null | undefined): language is CardLanguage {
  return Boolean(language) && (CARD_LANGUAGES as readonly string[]).includes(language!.toLowerCase())
}

/** Control characters other than tab and newline, and the bidirectional overrides that can make text read differently from what it is. */
// Matching control characters is the point of this expression, so the lint rule against them does not apply.
// eslint-disable-next-line no-control-regex
const UNSAFE_CHARACTERS = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u202A-\u202E\u2066-\u2069]/g

/**
 * A string field, cleaned: unsafe characters removed, line endings made \n (or, for a one-line
 * field, made spaces), trimmed. Refuses a value that is not a string, or one longer than `max`.
 */
function text(value: unknown, field: string, max: number, options: { multiline?: boolean; required?: boolean } = {}): string {
  if (value === undefined || value === null) {
    if (options.required) refuse(`The ${field} is missing.`)
    return ''
  }
  if (typeof value !== 'string') refuse(`The ${field} must be text.`)
  let cleaned = value.replace(/\r\n?/g, '\n').replace(UNSAFE_CHARACTERS, '')
  if (!options.multiline) cleaned = cleaned.replace(/\s*\n\s*/g, ' ')
  cleaned = options.multiline ? cleaned.replace(/^\s*\n|\s+$/g, '') : cleaned.trim()
  if (cleaned.length > max) refuse(`The ${field} is longer than ${max.toLocaleString('en-AU')} characters.`)
  if (options.required && !cleaned) refuse(`The ${field} is empty.`)
  return cleaned
}

/** A list of addresses written as one string or a list of strings, joined with commas for one editable field. */
function recipients(value: unknown, field: string, required: boolean): string {
  if (value === undefined || value === null || value === '') {
    if (required) refuse(`The ${field} is missing.`)
    return ''
  }
  const list = Array.isArray(value) ? value : [value]
  const parts = list.flatMap((entry) => text(entry, field, LIMITS.recipient * LIMITS.recipients).split(/[,;]/))
  const addresses = parts.map((part) => part.trim()).filter(Boolean)
  if (required && addresses.length === 0) refuse(`The ${field} is empty.`)
  if (addresses.length > LIMITS.recipients) refuse(`The ${field} has more than ${LIMITS.recipients} addresses.`)
  for (const address of addresses) {
    if (address.length > LIMITS.recipient) refuse(`An address in the ${field} is too long.`)
  }
  return addresses.join(', ')
}

/** A character that may follow the closing quote of a JSON string: a separator or the end of an object or list. */
const AFTER_STRING = /[,:}\]]/

/**
 * The card's text with stray double quotes inside its values escaped, or null when it has none to
 * fix. Models quote titles inside a sentence and forget the backslash ("in French ("Notions sur
 * la machine analytique")"), which made a whole timeline table fall back to text on 13 Oct 2026.
 * Inside a string, a quote counts as the closing one only when what follows it (after spaces) is a
 * separator, the end of an object or list, or the end of the text; any other quote is part of the
 * value. Only tried after the text failed to parse as it is, and only kept when it then parses.
 */
export function repairStrayQuotes(source: string): string | null {
  let out = ''
  let inString = false
  let changed = false
  for (let i = 0; i < source.length; i++) {
    const char = source[i]!
    if (inString && char === '\\') {
      out += char + (source[i + 1] ?? '')
      i++
      continue
    }
    if (char === '"') {
      if (!inString) {
        inString = true
      } else {
        const rest = source.slice(i + 1).trimStart()
        if (rest === '' || AFTER_STRING.test(rest[0]!)) {
          inString = false
        } else {
          out += '\\"'
          changed = true
          continue
        }
      }
    }
    out += char
  }
  return changed ? out : null
}

/** A JSON object, or a refusal naming what was wrong with the body. A value with unescaped quotes is repaired first. */
function jsonObject(source: string): Record<string, unknown> {
  let parsed: unknown
  try {
    parsed = JSON.parse(source)
  } catch {
    const repaired = repairStrayQuotes(source)
    try {
      if (repaired === null) throw new Error('nothing to repair')
      parsed = JSON.parse(repaired)
    } catch {
      refuse("The card's text is not in the format a card needs.")
    }
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) refuse("The card's text is a list, not one card.")
  return parsed as Record<string, unknown>
}

/** An array field, or a refusal. */
function list(value: unknown, field: string): unknown[] {
  if (!Array.isArray(value)) refuse(`The ${field} must be a list.`)
  return value
}

/* ---- One reader per card type ---------------------------------------------------------------- */

/** email-draft: recipients, optional copy recipients, subject and body. */
function readEmailDraft(source: string): EmailDraftBlock {
  const json = jsonObject(source)
  return {
    kind: 'email-draft',
    to: recipients(json.to, 'recipient list', true),
    cc: recipients(json.cc, 'copy list', false),
    subject: text(json.subject, 'subject', LIMITS.subject, { required: true }),
    // Runs of blank lines become one, so nothing can sit out of sight below a stretch of empty lines.
    body: text(json.body, 'message', LIMITS.body, { multiline: true, required: true }).replace(/\n[ \t]*(?:\n[ \t]*)+\n/g, '\n\n'),
  }
}

/** A table cell: text, a number or a yes/no, written as text; null is an empty cell. */
function cell(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) refuse('A table cell holds a number that cannot be shown.')
    return String(value)
  }
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  return text(value, 'table cell', LIMITS.cell)
}

/** Checks a table's shape: one to twelve named columns, up to 200 rows, no row wider than the header. Short rows are padded. */
function tableOf(title: string, columns: string[], rows: string[][]): TableBlock {
  if (columns.length === 0) refuse('The table has no columns.')
  if (columns.length > LIMITS.columns) refuse(`The table has more than ${LIMITS.columns} columns.`)
  if (rows.length > LIMITS.rows) refuse(`The table has more than ${LIMITS.rows} rows.`)
  const padded = rows.map((row) => {
    if (row.length > columns.length) refuse('A table row has more cells than there are columns.')
    return [...row, ...Array<string>(columns.length - row.length).fill('')]
  })
  return { kind: 'table', title, columns, rows: padded }
}

/** table: JSON with columns and rows, JSON with a csv field, plain CSV, or a Markdown pipe table. */
function readTable(source: string): TableBlock {
  if (!source.trimStart().startsWith('{')) return tableFromText('', source)
  const json = jsonObject(source)
  const title = text(json.title, 'title', LIMITS.text)
  if (json.csv !== undefined) return tableFromText(title, text(json.csv, 'CSV', LIMITS.source, { multiline: true }))
  const columns = list(json.columns, 'column list').map((column) => text(column, 'column name', LIMITS.text, { required: true }))
  // A row is a list of cells in column order, or an object keyed by column name.
  const rows = list(json.rows, 'row list').map((row) =>
    typeof row === 'object' && row !== null && !Array.isArray(row)
      ? columns.map((column) => cell(Object.hasOwn(row, column) ? (row as Record<string, unknown>)[column] : null))
      : list(row, 'row').map(cell),
  )
  return tableOf(title, columns, rows)
}

/** A Markdown table's divider line: "|---|:---:|". */
const PIPE_DIVIDER = /^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)*\|?\s*$/

/** Whether text is a Markdown pipe table: its first line starts with a pipe, or its second line is a divider. */
function isPipeTable(source: string): boolean {
  const lines = source.split('\n').filter((line) => line.trim() !== '')
  return lines.length > 0 && (lines[0]!.trim().startsWith('|') || (lines.length > 1 && PIPE_DIVIDER.test(lines[1]!)))
}

/** The cells of one pipe-table line: split on pipes (a "\\|" is a pipe inside a cell), the outer pipes dropped. */
function pipeCells(line: string): string[] {
  const trimmed = line.trim().replace(/^\|/, '').replace(/(?<!\\)\|$/, '')
  return trimmed.split(/(?<!\\)\|/).map((cellText) => cellText.replace(/\\\|/g, '|').trim())
}

/**
 * A table from text: a Markdown pipe table (which an agent may write by habit) or CSV. Text that
 * reads as one column but is laid out in another way - pipes, tabs or aligned spaces - is refused,
 * so it shows as the code it is rather than as a broken one-column table.
 */
function tableFromText(title: string, source: string): TableBlock {
  if (isPipeTable(source)) {
    const lines = source.split('\n').filter((line) => line.trim() !== '' && !PIPE_DIVIDER.test(line))
    const [header, ...rows] = lines.map(pipeCells)
    if (!header) refuse('The table is empty.')
    const columns = header.map((column) => text(column, 'column name', LIMITS.text, { required: true }))
    return tableOf(
      title,
      columns,
      rows.map((row) => row.map(cell)),
    )
  }
  const records = parseCsv(source)
  if (records.length > 0 && records[0]!.length === 1 && source.split('\n').some((line) => /\||\t|\S {2,}\S/.test(line))) {
    refuse('The table is not written as comma-separated rows.')
  }
  return tableFromCsv(title, records)
}

/** A table from CSV records, the first row naming the columns. */
function tableFromCsv(title: string, records: string[][]): TableBlock {
  if (records.length === 0) refuse('The table is empty.')
  const [header, ...rows] = records
  const columns = header!.map((column) => text(column, 'column name', LIMITS.text, { required: true }))
  return tableOf(
    title,
    columns,
    rows.map((row) => row.map(cell)),
  )
}

/** chart: a bar, line or pie chart of one to three series over up to 24 labels. */
function readChart(source: string): ChartBlock {
  const json = jsonObject(source)
  const chartType = json.type
  if (chartType !== 'bar' && chartType !== 'line' && chartType !== 'pie') refuse('The chart type must be bar, line or pie.')
  const title = text(json.title, 'title', LIMITS.text)
  const unit = text(json.unit, 'unit', 20)
  const labels = list(json.labels, 'label list').map((label) =>
    typeof label === 'number' && Number.isFinite(label) ? String(label) : text(label, 'label', LIMITS.text, { required: true }),
  )
  if (labels.length === 0) refuse('The chart has no labels.')
  const maxLabels = chartType === 'pie' ? LIMITS.pieSlices : LIMITS.chartLabels
  if (labels.length > maxLabels) refuse(`A ${chartType} chart can show at most ${maxLabels} labels.`)

  // One series may be written as a lone object, or as "values" beside the labels.
  const rawSeries =
    json.series === undefined && json.values !== undefined
      ? [{ name: title || 'Value', values: json.values }]
      : Array.isArray(json.series)
        ? json.series
        : json.series !== undefined
          ? [json.series]
          : refuse('The chart has no series.')
  if (rawSeries.length === 0) refuse('The chart has no series.')
  const maxSeries = chartType === 'pie' ? 1 : LIMITS.series
  if (rawSeries.length > maxSeries) refuse(chartType === 'pie' ? 'A pie chart takes one series.' : `A chart can show at most ${LIMITS.series} series.`)
  const series = rawSeries.map((raw, index): ChartSeries => {
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) refuse('Each chart series must be an object.')
    const entry = raw as Record<string, unknown>
    const name = text(entry.name, 'series name', LIMITS.text) || `Series ${index + 1}`
    const values = list(entry.values, 'value list').map((value) => {
      const number = typeof value === 'string' && value.trim() !== '' ? Number(value) : value
      if (typeof number !== 'number' || !Number.isFinite(number)) refuse('Every chart value must be a number.')
      return number
    })
    if (values.length !== labels.length) refuse('Each series needs one value for every label.')
    return { name, values }
  })
  if (chartType === 'pie') {
    const values = series[0]!.values
    if (values.some((value) => value < 0)) refuse('A pie chart cannot show a negative value.')
    if (values.reduce((sum, value) => sum + value, 0) <= 0) refuse('A pie chart needs at least one value above zero.')
  }
  return { kind: 'chart', chartType, title, unit, labels, series }
}

/** checklist: up to 30 items, each text with an optional done flag (a plain string is an open item). */
function readChecklist(source: string): ChecklistBlock {
  const json = jsonObject(source)
  const title = text(json.title, 'title', LIMITS.text)
  const items = list(json.items, 'item list').map((raw): ChecklistItem => {
    if (typeof raw === 'string') return { text: text(raw, 'item', LIMITS.text, { required: true }), done: false }
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) refuse('Each checklist item must be text or an object.')
    const entry = raw as Record<string, unknown>
    if (entry.done !== undefined && typeof entry.done !== 'boolean') refuse('A checklist item is done only when "done" is true or false.')
    return { text: text(entry.text, 'item', LIMITS.text, { required: true }), done: entry.done === true }
  })
  if (items.length === 0) refuse('The checklist has no items.')
  if (items.length > LIMITS.checklistItems) refuse(`The checklist has more than ${LIMITS.checklistItems} items.`)
  return { kind: 'checklist', title, items }
}

/** choices: a question and two to four different options. */
function readChoices(source: string): ChoicesBlock {
  const json = jsonObject(source)
  const question = text(json.question, 'question', LIMITS.text, { required: true })
  const options = list(json.options, 'option list').map((raw) => {
    const label = typeof raw === 'object' && raw !== null && !Array.isArray(raw) ? (raw as Record<string, unknown>).label : raw
    return text(label, 'option', 120, { required: true })
  })
  if (options.length < LIMITS.minOptions || options.length > LIMITS.maxOptions) refuse('A choice needs two to four options.')
  if (new Set(options.map((option) => option.toLowerCase())).size !== options.length) refuse('Two options are the same.')
  return { kind: 'choices', question, options }
}

// @find: parseAnswerBlock, validate answer block, card json, card fallback reason
/**
 * Reads one fenced block as a card. Returns null when the language is not a card's (an ordinary
 * code block); otherwise the validated card, or the reason it cannot be shown as one: a body over
 * LIMITS.source, invalid JSON, a missing or mistyped field, or a size over its cap. Pure; never throws.
 */
export function parseAnswerBlock(language: string | null | undefined, source: string): BlockResult | null {
  if (!isCardLanguage(language)) return null
  if (source.length > LIMITS.source) return { ok: false, reason: 'The card is too large to show.' }
  try {
    switch (language.toLowerCase() as CardLanguage) {
      case 'email-draft':
        return { ok: true, block: readEmailDraft(source) }
      case 'table':
        return { ok: true, block: readTable(source) }
      case 'chart':
        return { ok: true, block: readChart(source) }
      case 'checklist':
        return { ok: true, block: readChecklist(source) }
      case 'choices':
        return { ok: true, block: readChoices(source) }
    }
  } catch (error) {
    if (error instanceof Refusal) return { ok: false, reason: error.message }
    return { ok: false, reason: 'The card could not be read.' }
  }
}

// @find: resolveCards, cards in a parsed answer, card limit per answer
/**
 * Finds every card fence in a parsed Markdown tree, in reading order (inside lists and quotes
 * too), and reads each one. Past LIMITS.cardsPerAnswer cards, later ones are refused, so one answer
 * cannot fill the screen with cards. Returns a map from the code block to its result, each card with
 * its place among the answer's cards (which names the card in a message sent from it); an ordinary
 * code block has no entry.
 */
export function resolveCards(blocks: readonly MdBlock[]): Map<MdBlock, CardResult> {
  const results = new Map<MdBlock, CardResult>()
  let shown = 0
  const visit = (nodes: readonly MdBlock[]) => {
    for (const node of nodes) {
      if (node.type === 'list') node.items.forEach((item) => visit(item.children))
      else if (node.type === 'blockquote') visit(node.children)
      else if (node.type === 'code') {
        const result = parseAnswerBlock(node.language, node.text)
        if (!result) continue
        if (result.ok && shown >= LIMITS.cardsPerAnswer) {
          results.set(node, { ok: false, reason: `An answer can show at most ${LIMITS.cardsPerAnswer} cards.` })
          continue
        }
        if (result.ok) results.set(node, { ...result, index: shown++ })
        else results.set(node, result)
      }
    }
  }
  visit(blocks)
  return results
}

/* ---- CSV ------------------------------------------------------------------------------------- */

// @find: parseCsv, read csv text, csv with quotes
/**
 * Reads CSV text into rows of cells: commas separate cells, double quotes wrap a cell that holds a
 * comma, a quote ("" inside quotes) or a line break. Blank lines are skipped.
 */
export function parseCsv(csv: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let current = ''
  let quoted = false
  const source = csv.replace(/\r\n?/g, '\n')
  const endRow = () => {
    row.push(current)
    if (row.length > 1 || row[0]!.trim() !== '') rows.push(row.map((value) => value.trim()))
    row = []
    current = ''
  }
  for (let i = 0; i < source.length; i++) {
    const char = source[i]!
    if (quoted) {
      if (char === '"' && source[i + 1] === '"') {
        current += '"'
        i++
      } else if (char === '"') quoted = false
      else current += char
    } else if (char === '"' && current.trim() === '') {
      quoted = true
      current = ''
    } else if (char === ',') {
      row.push(current)
      current = ''
    } else if (char === '\n') endRow()
    else current += char
  }
  if (current !== '' || row.length > 0) endRow()
  return rows
}

/**
 * The number a cell starts with, as a person writes one: "1,200", "$40", "A$1,200/mo", "12.5%",
 * "-3 days", "$2 (est)". Groups of three after commas are thousands.
 */
const LEADING_NUMBER = /^([-+]?)(?:[A-Z]{0,3}[$€£¥]\s?)?(-?)(\d{1,3}(?:,\d{3})+|\d+)(\.\d+)?/i

/** The number a cell holds at its start, ignoring a currency sign, thousands separators and what follows; null when it does not start with one, or starts with a date, a time or a spaced group of digits. */
// @find: cellNumber, numeric table cell, sort numbers in table
export function cellNumber(value: string): number | null {
  const trimmed = value.trim()
  const match = LEADING_NUMBER.exec(trimmed)
  if (!match) return null
  // "2026-10-12", "10:30", "1/2", "3.5.1" and "0412 345 678" are not one number.
  if (/^(?:[-/:.]\d|\s+\d)/.test(trimmed.slice(match[0].length))) return null
  const number = Number(`${match[3]!.replace(/,/g, '')}${match[4] ?? ''}`)
  if (!Number.isFinite(number)) return null
  return match[1] === '-' || match[2] === '-' ? -number : number
}

/**
 * Orders two cells, the same way whatever order they come in (a total order, so a sort is stable
 * and repeatable): filled cells before empty ones whichever way the column is sorted, cells that
 * start with a number before those that do not, numbers by value, and text in natural order
 * ("Item 2" before "Item 10").
 */
// @find: compareCells, sort table column, natural sort
export function compareCells(a: string, b: string, direction: 'asc' | 'desc'): number {
  const aEmpty = a.trim() === ''
  const bEmpty = b.trim() === ''
  if (aEmpty || bEmpty) return aEmpty === bEmpty ? 0 : aEmpty ? 1 : -1
  const sign = direction === 'asc' ? 1 : -1
  const aNumber = cellNumber(a)
  const bNumber = cellNumber(b)
  if (aNumber !== null && bNumber !== null) {
    const byValue = aNumber - bNumber
    return (byValue !== 0 ? byValue : a.localeCompare(b, 'en', { numeric: true, sensitivity: 'base' })) * sign
  }
  if (aNumber !== null || bNumber !== null) return (aNumber !== null ? -1 : 1) * sign
  return a.localeCompare(b, 'en', { numeric: true, sensitivity: 'base' }) * sign
}

/** A whole cell that is a number as a person writes one, optionally with a word or two after it: "-5%", "-$1,200", "+1.5", "-3 days". */
const PLAIN_NUMBER = /^[-+]?(?:[A-Z]{0,3}[$€£¥]\s?)?-?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?\s?%?(?:\s+[A-Za-z]+){0,2}$/i

/** A phone number: digits with spaces, brackets or hyphens, and an optional leading +. */
const PHONE_NUMBER = /^\+?\d[\d\s()-]{5,}$/

/**
 * A cell a spreadsheet would run as a formula: one starting =, +, -, @, a tab or a carriage return,
 * unless it is plainly a number ("-5%", "-$1,200", "-3 days") or a phone number ("+61 2 9999 9999").
 * Those cannot call a function, so they are kept as written.
 */
function looksLikeFormula(value: string): boolean {
  return /^[=+\-@\t\r]/.test(value) && !PLAIN_NUMBER.test(value) && !PHONE_NUMBER.test(value)
}

// @find: toCsv, table to csv, csv export, formula injection
/**
 * Writes a table as CSV (RFC 4180): a cell holding a comma, quote or line break is quoted, quotes
 * doubled, rows joined with CRLF. A cell a spreadsheet would treat as a formula is prefixed with an
 * apostrophe, so opening a downloaded file never runs something an agent wrote.
 */
export function toCsv(columns: readonly string[], rows: readonly (readonly string[])[]): string {
  const encode = (value: string) => {
    const safe = looksLikeFormula(value) ? `'${value}` : value
    return /[",\r\n]|^\s|\s$/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe
  }
  return [columns, ...rows].map((row) => row.map(encode).join(',')).join('\r\n')
}

/** A file name for a downloaded table: its title in lower case with hyphens, or "table". */
// @find: csvFileName, download file name
export function csvFileName(title: string): string {
  const slug = title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60)
  return `${slug || 'table'}.csv`
}

/* ---- Chart scale ------------------------------------------------------------------------------ */

/** A round step for about `parts` gridlines over a span: 1, 2 or 5 times a power of ten. */
function niceStep(span: number, parts: number): number {
  const raw = span / parts
  const power = 10 ** Math.floor(Math.log10(raw))
  for (const step of [1, 2, 5, 10]) {
    if (raw <= step * power) return step * power
  }
  return 10 * power
}

// @find: chartScale, chart axis, gridlines, nice scale
/**
 * The vertical scale of a bar or line chart: it always includes zero, ends on round numbers, and
 * has about four gridlines. A chart of zeros gets a scale of 0 to 1.
 */
export function chartScale(values: readonly number[]): { low: number; high: number; ticks: number[] } {
  const min = Math.min(0, ...values)
  const max = Math.max(0, ...values)
  if (max === min) return { low: 0, high: 1, ticks: [0, 0.5, 1] }
  const step = niceStep(max - min, 4)
  // Each tick is counted from the bottom and rounded to the step's decimals, never added up in
  // floating point, so the zero line is exactly 0 (not -2.8e-17, which reads as "-0").
  const decimals = Math.max(0, -Math.floor(Math.log10(step)))
  const round = (value: number) => Number(value.toFixed(decimals)) + 0
  const lowSteps = Math.floor(Number((min / step).toFixed(9)))
  const highSteps = Math.ceil(Number((max / step).toFixed(9)))
  const ticks: number[] = []
  for (let i = lowSteps; i <= highSteps; i++) ticks.push(round(i * step))
  return { low: round(lowSteps * step), high: round(highSteps * step), ticks }
}

/** A chart value as text: up to two decimals, grouped thousands, and the unit when there is one ("%" sits close, a word after a space). */
// @find: formatChartValue, chart number text
export function formatChartValue(value: number, unit = ''): string {
  const number = value.toLocaleString('en-AU', { maximumFractionDigits: 2 })
  if (!unit) return number
  if (unit === '%') return `${number}%`
  if (isSymbolUnit(unit)) return value < 0 ? `-${unit}${number.slice(1)}` : `${unit}${number}`
  return `${number} ${unit}`
}

/** Whether a unit is a currency sign, written before the number, rather than a word after it. */
export function isSymbolUnit(unit: string): boolean {
  return /^[$€£¥]$/.test(unit)
}

// @find: formatChartTick, short axis label, compact number on axis
/**
 * A value as the axis beside a bar or line chart shows it: short enough to fit the margin. From
 * 10,000 up it is abbreviated ("250K", "1.5M"); a currency sign or "%" stays on it, and a word unit
 * is left off, because the chart says it once under the drawing (ChartCard).
 */
export function formatChartTick(value: number, unit: string, largest: number): string {
  const number =
    Math.abs(largest) >= 10_000
      ? new Intl.NumberFormat('en-AU', { notation: 'compact', maximumFractionDigits: 1 }).format(value)
      : value.toLocaleString('en-AU', { maximumFractionDigits: 2 })
  if (unit === '%') return `${number}%`
  if (isSymbolUnit(unit)) return value < 0 ? `-${unit}${number.slice(1)}` : `${unit}${number}`
  return number
}

/* ---- Follow-up messages ---------------------------------------------------------------------- */

/** The edited draft as plain text: recipients, subject, then the message. Used by Copy. */
// @find: emailText, email draft as text
export function emailText(draft: Pick<EmailDraftBlock, 'to' | 'cc' | 'subject' | 'body'>): string {
  return [`To: ${draft.to}`, ...(draft.cc.trim() ? [`Cc: ${draft.cc}`] : []), `Subject: ${draft.subject}`, '', draft.body].join('\n')
}

/** The cards that can send a message: the ones a person acts on. A table or a chart only reads. */
export type ActingCardKind = 'email-draft' | 'checklist' | 'choices'

/**
 * What a card sends as the person's next message: `text`, a short sentence shown in the thread as
 * theirs, and `content`, the card's data as they left it, which the server hands the agent as data
 * in a block of its own (never as an instruction). The answer and the card's place in it are added
 * by the answer that holds the card (AnswerBubble).
 */
export type CardRequest = { kind: ActingCardKind; text: string; content: Record<string, unknown> }

/** Addresses written with commas, semicolons or line breaks, as one comma-separated line. */
export function recipientsLine(value: string): string {
  return value
    .split(/[,;\n]/)
    .map((part) => part.trim())
    .filter(Boolean)
    .join(', ')
}

// @find: emailSendRequest, ask the agent to send the email, follow-up message
/**
 * The request that asks the agent to send the draft as the person last edited it: a sentence naming
 * the subject and recipients, and the draft itself as data. The agent's run sends it, and sending
 * waits for a person's approval.
 */
export function emailSendRequest(draft: Pick<EmailDraftBlock, 'to' | 'cc' | 'subject' | 'body'>): CardRequest {
  const to = recipientsLine(draft.to)
  const subject = draft.subject.replace(/\s+/g, ' ').trim()
  return {
    kind: 'email-draft',
    text: `Please send my edited email "${subject}" to ${to}, exactly as written in the draft.`,
    content: { to, cc: recipientsLine(draft.cc), subject, body: draft.body },
  }
}

// @find: checklistUpdateRequest, tell the agent what is done
/** The request that tells the agent which checklist items the person has ticked: a sentence with the count, and every item as data. */
export function checklistUpdateRequest(title: string, items: readonly ChecklistItem[]): CardRequest {
  const done = items.filter((item) => item.done).length
  return {
    kind: 'checklist',
    text: `I have updated the checklist${title ? ` "${title}"` : ''}: ${done} of ${items.length} done.`,
    content: { title, items: items.map((item) => ({ text: item.text, done: item.done })) },
  }
}

// @find: choiceRequest, send a choice with its question
/** The request that answers a choice: the question with the option chosen, so it reads on its own whichever answer it came from. */
export function choiceRequest(question: string, option: string): CardRequest {
  return { kind: 'choices', text: `My choice for "${question}": ${option}`, content: { question, option } }
}

/* ---- What was already sent from a card ------------------------------------------------------- */

/** The card a sent or waiting message came from, as the server records it in its context. */
export type SentCard = { messageId: string; index: number; kind: string; content: Record<string, unknown> }

// @find: cardOf, card a message was sent from, detail.context.card
/** The card a message was sent from (`detail.context.card` on a sent message, `card` on a waiting one), read defensively; null for a typed message. */
export function sentCardOf(raw: unknown): SentCard | null {
  if (!raw || typeof raw !== 'object') return null
  const card = raw as Record<string, unknown>
  if (typeof card.messageId !== 'string' || typeof card.index !== 'number' || typeof card.kind !== 'string') return null
  const content = card.content && typeof card.content === 'object' ? (card.content as Record<string, unknown>) : {}
  return { messageId: card.messageId, index: card.index, kind: card.kind, content }
}

/** What was sent from one card: the content of the first message from it, whether the viewer sent it, and whether it is still waiting its turn. */
export type CardReply = { content: Record<string, unknown>; mine: boolean; queued: boolean }

/** A sent message, as far as cardRepliesFor reads it. */
type ThreadItem = { kind: string; authorKind: string; authorId: string | null; position: number; detail: unknown }
/** A waiting message, as far as cardRepliesFor reads it. */
type WaitingItem = { authorId: string | null; status: string; card?: unknown }

// @find: cardRepliesFor, card already used, choice already made, already asked to send
/**
 * What has been sent from each card of one answer, by the card's place: the person's messages in
 * the thread that name this answer and card, then messages still waiting their turn. For a choice
 * or a checklist the first one wins (one question, one answer); for an email draft the latest one
 * wins, because each request asks to send the draft as it was edited then, and the card shows that
 * version again after a reload. Matching on the card itself - never on words - means a "yes" typed
 * for something else, or a request from another draft, never marks a card as used. `me` is the
 * viewer, so a card can say whether they or someone else used it. Pure.
 */
export function cardRepliesFor(
  answerId: string,
  thread: readonly ThreadItem[],
  waiting: readonly WaitingItem[],
  me: string | null,
): Map<number, CardReply> {
  const replies = new Map<number, CardReply>()
  const sent = thread
    .filter((item) => item.kind === 'text' && item.authorKind === 'user')
    .sort((a, b) => a.position - b.position)
  // A later request replaces an earlier one only for an email draft.
  const keep = (card: SentCard) => !replies.has(card.index) || card.kind === 'email-draft'
  for (const item of sent) {
    const context = (item.detail as { context?: { card?: unknown } } | null)?.context
    const card = sentCardOf(context?.card)
    if (card && card.messageId === answerId && keep(card)) {
      replies.set(card.index, { content: card.content, mine: Boolean(me) && item.authorId === me, queued: false })
    }
  }
  for (const item of waiting) {
    const card = sentCardOf(item.card)
    if (item.status !== 'expired' && card && card.messageId === answerId && keep(card)) {
      replies.set(card.index, { content: card.content, mine: Boolean(me) && item.authorId === me, queued: true })
    }
  }
  return replies
}

/* ---- The answer as plain text ---------------------------------------------------------------- */

/** One card written out for copying (exact, line by line) or for reading aloud (a short spoken summary). */
function cardText(block: AnswerBlock, mode: 'copy' | 'speech'): string {
  switch (block.kind) {
    case 'email-draft':
      return mode === 'copy'
        ? emailText(block)
        : `An email draft to ${block.to}, with the subject ${block.subject}. ${block.body}`
    case 'table':
      return mode === 'copy'
        ? [block.title, [block.columns, ...block.rows].map((row) => row.join(' | ')).join('\n')].filter(Boolean).join('\n')
        : `A table${block.title ? `, ${block.title},` : ''} with ${block.rows.length} ${block.rows.length === 1 ? 'row' : 'rows'}. It is on screen.`
    case 'chart': {
      const lines = block.series.map(
        (series) =>
          `${block.series.length > 1 || !block.title ? `${series.name}: ` : ''}${block.labels
            .map((label, index) => `${label} ${formatChartValue(series.values[index]!, block.unit)}`)
            .join(', ')}`,
      )
      return mode === 'copy'
        ? [block.title, ...lines].filter(Boolean).join('\n')
        : `A ${block.chartType} chart${block.title ? ` of ${block.title}` : ''}. ${lines.join('. ')}.`
    }
    case 'checklist':
      return mode === 'copy'
        ? [block.title, ...block.items.map((item) => `[${item.done ? 'x' : ' '}] ${item.text}`)].filter(Boolean).join('\n')
        : `A checklist${block.title ? `, ${block.title}` : ''}: ${block.items.map((item) => item.text).join('; ')}.`
    case 'choices':
      return mode === 'copy'
        ? [block.question, ...block.options.map((option) => `- ${option}`)].join('\n')
        : `${block.question} The options are ${block.options.slice(0, -1).join(', ')} or ${block.options[block.options.length - 1]}.`
  }
}

/** The quote markers at the start of a line ("> ", "> > "), and how deep they go. */
function quoteDepth(line: string): number {
  const markers = /^\s*((?:>\s?)+)/.exec(line)
  return markers ? (markers[1]!.match(/>/g)?.length ?? 0) : 0
}

/** A line without its first `depth` quote markers. */
function unquote(line: string, depth: number): string {
  let rest = line
  for (let level = 0; level < depth; level++) rest = rest.replace(/^\s*>\s?/, '')
  return rest
}

// @find: answerPlainText, copy answer without json, read answer aloud, speech text for cards
/**
 * The answer with each card written out as text instead of its JSON, for "Copy answer" (`copy`)
 * and for reading aloud (`speech`). Cards inside a quote ("> ```choices") are found too, the same
 * as the renderer draws them, and written out inside the quote. A block that is not a valid card
 * is left exactly as written.
 */
export function answerPlainText(content: string, mode: 'copy' | 'speech'): string {
  if (!content.includes('```')) return content
  const lines = content.replace(/\r\n?/g, '\n').split('\n')
  const out: string[] = []
  let i = 0
  while (i < lines.length) {
    const depth = quoteDepth(lines[i]!)
    const open = /^```\s*([\w-]*)\s*$/.exec(unquote(lines[i]!, depth).trim())
    if (!open) {
      out.push(lines[i]!)
      i++
      continue
    }
    const start = i
    i++
    // The block runs to its closing fence, inside the same quote.
    while (i < lines.length && (depth === 0 || quoteDepth(lines[i]!) >= depth) && !/^```\s*$/.test(unquote(lines[i]!, depth).trim())) i++
    const body = lines
      .slice(start + 1, i)
      .map((line) => unquote(line, depth))
      .join('\n')
    const closed = i < lines.length && /^```\s*$/.test(unquote(lines[i]!, depth).trim())
    const fenceLines = lines.slice(start, closed ? i + 1 : i)
    if (closed) i++
    const result = parseAnswerBlock(open[1], body)
    const prefix = '> '.repeat(depth)
    if (result?.ok) out.push(...cardText(result.block, mode).split('\n').map((line) => `${prefix}${line}`.trimEnd() || (depth ? prefix.trimEnd() : '')))
    else out.push(...fenceLines)
  }
  return out.join('\n')
}
