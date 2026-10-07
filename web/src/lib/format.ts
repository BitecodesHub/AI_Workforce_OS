/*
 * Every date, time, duration, count and amount the console shows, formatted one way.
 *
 * Pure functions only. Anything that depends on the current time takes `now` as an argument, so
 * the same instant always produces the same words and every rule can be tested. Components get a
 * ticking `now` from useNow (./useNow.ts) rather than calling Date.now() while they render.
 *
 * Dates are composed from their parts rather than handed to Intl.DateTimeFormat: ICU versions
 * disagree on "Sep" against "Sept" and on the space before "pm", and a label that changes with the
 * browser build cannot be tested or relied on. Times are shown in the viewer's own time zone.
 */

export const LOCALE = 'en-AU'

const EMPTY = '—'

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

const SECOND = 1000

/**
 * A timestamp up to this far ahead of the browser's clock still reads "just now". Server and
 * browser clocks drift by a few seconds, and a run that has only just started must not read as if
 * it starts "in less than a minute".
 */
const CLOCK_SKEW_MS = 10 * SECOND

function parse(iso: string | null | undefined): Date | null {
  if (!iso) return null
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? null : date
}

const pad2 = (value: number) => String(value).padStart(2, '0')

function countedUnit(count: number, unit: string): string {
  return `${count} ${unit}${count === 1 ? '' : 's'}`
}

/** '1 run', '3 runs': `one` for exactly one, `many` otherwise. */
export function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`
}

/* ---- Dates and times ------------------------------------------------------------------------- */

function datePart(date: Date): string {
  return `${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}`
}

function timePart(date: Date): string {
  const hours = date.getHours()
  const hour12 = hours % 12 === 0 ? 12 : hours % 12
  return `${hour12}:${pad2(date.getMinutes())} ${hours < 12 ? 'am' : 'pm'}`
}

/** '27 Sep 2026, 3:04 pm', or an em dash when there is no time to show. */
export function formatDateTime(iso?: string | null): string {
  const date = parse(iso)
  return date ? `${datePart(date)}, ${timePart(date)}` : EMPTY
}

/**
 * '29 Sep 2026, 9:00 am' as the clock reads in `timeZone` (an IANA name such as
 * 'Australia/Melbourne'), not in the browser's own zone.
 *
 * A schedule set for "9 am" in the workspace timezone must show 9 am, even to someone reading it
 * in another country; the browser-local form would show 4:30 am and look wrong. Falls back to the
 * browser's zone when the name is unknown.
 */
export function formatDateTimeIn(iso: string | null | undefined, timeZone: string | null | undefined): string {
  const date = parse(iso)
  if (!date) return EMPTY
  if (!timeZone) return formatDateTime(iso)
  try {
    const parts = new Intl.DateTimeFormat('en-GB', {
      timeZone,
      day: 'numeric',
      month: 'numeric',
      year: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
      hourCycle: 'h23',
    }).formatToParts(date)
    const get = (type: string) => Number(parts.find((part) => part.type === type)?.value ?? '0')
    const hours = get('hour')
    const hour12 = hours % 12 === 0 ? 12 : hours % 12
    return `${get('day')} ${MONTHS[get('month') - 1]} ${get('year')}, ${hour12}:${pad2(get('minute'))} ${hours < 12 ? 'am' : 'pm'}`
  } catch {
    return formatDateTime(iso)
  }
}

/**
 * A moment as a 24-hour clock reading, '09:00', in `timeZone` when it is given, else the browser's
 * own zone. For a schedule's next-run chip, where the date is shown separately (see
 * formatDateTimeIn for the same timezone rule).
 */
export function formatTimeIn(ms: number, timeZone?: string | null): string {
  const date = new Date(ms)
  if (Number.isNaN(date.getTime())) return EMPTY
  try {
    const parts = new Intl.DateTimeFormat('en-GB', {
      timeZone: timeZone ?? undefined,
      hour: 'numeric',
      minute: '2-digit',
      hourCycle: 'h23',
    }).formatToParts(date)
    const get = (type: string) => Number(parts.find((part) => part.type === type)?.value ?? '0')
    return `${pad2(get('hour'))}:${pad2(get('minute'))}`
  } catch {
    return `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
  }
}

/** '27 Sep 2026', or an em dash. */
export function formatDate(iso?: string | null): string {
  const date = parse(iso)
  return date ? datePart(date) : EMPTY
}

/**
 * How long ago, or how long until, in words.
 *
 * Past: 'just now' (under 45 seconds), '5 minutes ago', '3 hours ago', '2 days ago' up to six
 * days, then the date. Future: 'in less than a minute', 'in 5 minutes', 'in 3 hours', 'in 6 days',
 * then 'on 14 Oct 2026'. Pair it with the absolute time in a title (see <Time> in the UI kit), so
 * "3 hours ago" can always be pinned to a moment.
 */
export function formatRelative(iso?: string | null, now: number = Date.now()): string {
  const date = parse(iso)
  if (!date) return EMPTY
  const diff = now - date.getTime()
  const future = diff < -CLOCK_SKEW_MS
  const seconds = Math.round(Math.abs(diff) / SECOND)

  if (seconds < 45) return future ? 'in less than a minute' : 'just now'

  // Each unit is rounded from the one below, so 59 minutes 40 seconds reads '1 hour', never
  // '60 minutes'.
  const minutes = Math.max(1, Math.round(seconds / 60))
  const hours = Math.round(minutes / 60)
  const days = Math.round(hours / 24)

  let amount: string
  if (minutes < 60) amount = countedUnit(minutes, 'minute')
  else if (hours < 24) amount = countedUnit(hours, 'hour')
  else if (days <= 6) amount = countedUnit(days, 'day')
  else return future ? `on ${datePart(date)}` : datePart(date)

  return future ? `in ${amount}` : `${amount} ago`
}

/**
 * formatRelative against a shared clock that ticks every `tickMs`.
 *
 * That clock can lag up to one tick behind the real time, so a timestamp the server wrote a
 * moment ago can look like it lies in the future and read 'in less than a minute'. A moment no
 * more than one tick ahead of the clock is treated as the present.
 */
export function formatRelativeTicked(iso: string | null | undefined, now: number, tickMs: number): string {
  const at = iso ? Date.parse(iso) : Number.NaN
  const reference = Number.isFinite(at) && at > now && at - now <= tickMs ? at : now
  return formatRelative(iso, reference)
}

/**
 * The compact time a list row shows beside its title: 'Now' under a minute, then '26m', '3h' for
 * earlier today, 'Yesterday', '3 Oct' this year and '3 Oct 2025' before it. A moment slightly ahead
 * of `now` (clock drift) reads 'Now'. Pair it with the full time in a `title`.
 */
export function formatShortTime(iso: string | null | undefined, now: number): string {
  const date = parse(iso)
  if (!date) return EMPTY
  const diff = Math.max(0, now - date.getTime())
  const minutes = Math.floor(diff / (60 * SECOND))
  if (minutes < 1) return 'Now'
  if (minutes < 60) return `${minutes}m`
  const today = new Date(now)
  const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime()
  if (date.getTime() >= startOfToday) return `${Math.floor(minutes / 60)}h`
  const startOfYesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1).getTime()
  if (date.getTime() >= startOfYesterday) return 'Yesterday'
  const dayMonth = `${date.getDate()} ${MONTHS[date.getMonth()]}`
  return date.getFullYear() === today.getFullYear() ? dayMonth : `${dayMonth} ${date.getFullYear()}`
}

/**
 * A short elapsed time, from milliseconds rather than an instant: 'just now' under two seconds,
 * then '4 s ago', '2 min ago', '3 h ago'. For a caption that already has the moment in hand (a
 * question's "Asked 2 min ago"), where formatRelative's own parsing would be redundant.
 */
export function formatAgo(ms: number): string {
  if (!Number.isFinite(ms) || ms < 2 * SECOND) return 'just now'
  const seconds = Math.floor(ms / SECOND)
  if (seconds < 60) return `${seconds} s ago`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.floor(minutes / 60)
  return `${hours} h ago`
}

/* ---- Durations ------------------------------------------------------------------------------- */

/**
 * A length of time: '850 ms', '5.2 s', '12 s', '4 min 05 s', '2 h 05 min', '3 d 4 h'.
 *
 * Each figure is truncated rather than rounded, so a duration never reads as the next unit up
 * before it has reached it (59.9 seconds is '59 s', not '60 s'). Negative lengths read as zero.
 */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '0 ms'
  if (ms < SECOND) return `${Math.floor(ms)} ms`
  // Below ten seconds a tenth of a second is still worth reading: it is the difference between
  // two model calls in a trace.
  if (ms < 10 * SECOND) return `${(Math.floor(ms / 100) / 10).toFixed(1)} s`

  const totalSeconds = Math.floor(ms / SECOND)
  if (totalSeconds < 60) return `${totalSeconds} s`

  const totalMinutes = Math.floor(totalSeconds / 60)
  if (totalMinutes < 60) return `${totalMinutes} min ${pad2(totalSeconds % 60)} s`

  const totalHours = Math.floor(totalMinutes / 60)
  if (totalHours < 24) return `${totalHours} h ${pad2(totalMinutes % 60)} min`

  return `${Math.floor(totalHours / 24)} d ${totalHours % 24} h`
}

/** The time between two instants; `to` defaults to now. An em dash when there is no start. */
export function formatElapsed(fromIso?: string | null, toIso?: string | null, now: number = Date.now()): string {
  const from = parse(fromIso)
  if (!from) return EMPTY
  const to = parse(toIso)
  return formatDuration((to ? to.getTime() : now) - from.getTime())
}

/**
 * A run's duration, saying what it is still doing: 'Running 2 min 10 s', 'Waiting 23 h 05 min'
 * (for a run held for an approval), otherwise how long it took.
 */
export function formatRunElapsed(
  run: { status: string; startedAt: string; completedAt?: string | null },
  now: number = Date.now(),
): string {
  const status = run.status.toLowerCase()
  if (status === 'running') return `Running ${formatElapsed(run.startedAt, null, now)}`
  if (status === 'waiting_approval' || status === 'waiting_input') return `Waiting ${formatElapsed(run.startedAt, null, now)}`
  // A finished run without an end time cannot say how long it took; measuring to now would keep
  // a finished run's clock ticking.
  if (!run.completedAt) return EMPTY
  return formatElapsed(run.startedAt, run.completedAt, now)
}

/* ---- Numbers --------------------------------------------------------------------------------- */

const COUNT = new Intl.NumberFormat(LOCALE)

/** '12,480'. An em dash when there is no number. */
export function formatCount(n?: number | null): string {
  if (n == null || !Number.isFinite(n)) return EMPTY
  return COUNT.format(n)
}

/** Token counts at a glance: '950', '12.5K', '131K', '1.05M'. Exact figures belong in a title. */
export function formatCompactTokens(n?: number | null): string {
  if (n == null || !Number.isFinite(n)) return EMPTY
  const sign = n < 0 ? '-' : ''
  let value = Math.abs(n)
  if (value < 1000) return `${sign}${Math.round(value)}`
  const units = ['K', 'M', 'B']
  let unit = 0
  value /= 1000
  // Three significant figures, carried to the next unit when rounding reaches 1000 (999,999 is
  // '1M', not '1000K').
  while (Number(value.toPrecision(3)) >= 1000 && unit < units.length - 1) {
    value /= 1000
    unit += 1
  }
  return `${sign}${Number(value.toPrecision(3))}${units[unit]}`
}

function groupThousands(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/**
 * An amount in US dollars: 'US$3.00', 'US$1,204.50'. Amounts under a cent keep their significant
 * digits ('US$0.0042') because a single model call often costs less than a cent, and 'US$0.00'
 * would say it was free. Amounts under a dollar keep up to four decimal places ('US$0.075', a
 * catalogue price per million tokens), so rounding to cents never changes a price. Composed by
 * hand, not with Intl, whose currency prefix for USD varies between 'US$', 'USD' and '$' across
 * locales and ICU builds.
 */
export function formatMoney(amount?: number | null, currency: 'USD' = 'USD'): string {
  if (amount == null || !Number.isFinite(amount)) return EMPTY
  const prefix = currency === 'USD' ? 'US$' : `${currency} `
  if (amount > 0 && amount < 0.0001) return `Under ${prefix}0.0001`
  const sign = amount < 0 ? '-' : ''
  const magnitude = Math.abs(amount)
  if (magnitude > 0 && magnitude < 1) {
    // Four places, trailing zeros dropped, but never fewer than the two a price is read in.
    const fixed = magnitude.toFixed(4).replace(/0{1,2}$/, '')
    return `${sign}${prefix}${fixed}`
  }
  const [whole = '0', cents = '00'] = magnitude.toFixed(2).split('.')
  return `${sign}${prefix}${groupThousands(whole)}.${cents}`
}

/* ---- Text ------------------------------------------------------------------------------------ */

/**
 * A code as words: 'waiting_approval' reads 'Waiting approval', 'HALF_OPEN' 'Half open',
 * 'run.fail' 'Run fail'. The fallback for any code without a label of its own, so the console
 * never shows a raw identifier.
 */
export function sentenceCase(code?: string | null): string {
  if (!code) return ''
  const words = code
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_\-.\s]+/g, ' ')
    .trim()
    .toLowerCase()
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : ''
}

/** Shortens text to at most `max` characters, at a word boundary, ending with an ellipsis. */
export function truncateWords(text?: string | null, max = 80): string {
  if (!text) return ''
  const clean = text.replace(/\s+/g, ' ').trim()
  if (clean.length <= max) return clean
  const slice = clean.slice(0, max)
  const lastSpace = slice.lastIndexOf(' ')
  const cut = lastSpace > 0 ? slice.slice(0, lastSpace) : slice
  return `${cut.replace(/[\s,;:.\-–—]+$/, '')}…`
}

/**
 * Names as one phrase: 'A', 'A and B', 'A, B and C', and past `max` 'A, B, C and 2 more', so a
 * long list never becomes a paragraph.
 */
export function nameList(names: readonly string[], max = 3): string {
  const shown = names.slice(0, Math.max(1, max))
  const rest = names.length - shown.length
  if (rest > 0) return `${shown.join(', ')} and ${rest} more`
  if (shown.length < 2) return shown.join('')
  return `${shown.slice(0, -1).join(', ')} and ${shown[shown.length - 1]}`
}

/** The first eight characters of an identifier, enough to tell two apart on one screen. */
export function shortId(id?: string | null): string {
  return id ? id.slice(0, 8) : ''
}
