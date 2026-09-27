import type {
  ButtonHTMLAttributes,
  InputHTMLAttributes,
  MouseEvent as ReactMouseEvent,
  ReactNode,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react'
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useRouter } from '../../lib/router'
import { LOCALE, formatDateTime, formatRelative, sentenceCase } from '../../lib/format'
import { statusLabel, type StatusKind, type TagTone } from '../../lib/labels'
import { usePermissionCatalogue } from '../../lib/queries'
import { useNow } from '../../lib/useNow'

/*
 * The primitives every screen is built from.
 *
 * None of them carries a colour, a size or a radius of its own: each maps to a class in
 * components.css, which draws from tokens.css. That is what keeps sixteen screens looking like
 * one product rather than sixteen negotiations.
 */

// Status words and category names live in lib/labels.ts, which has no React in it; they are
// re-exported here so screens keep importing them from the UI kit.
export type { StatusKind, TagTone } from '../../lib/labels'
export { CATEGORY_LABEL } from '../../lib/labels'

/* ---- Eyebrow -------------------------------------------------------------------------------- */

/**
 * The mono line that opens every page and every card section.
 *
 * On a page it labels the kind of thing below it: "YOUR WORKFORCE, IN FOCUS" above "Command Map".
 * Where it is the only title a section has, render it `as="h2"` (or h3) so a screen reader can
 * move to the section by heading. Screens without one are caught by the design-system test.
 */
export function Eyebrow({
  children,
  as: Element = 'p',
  id,
}: {
  children: ReactNode
  as?: 'p' | 'h2' | 'h3'
  id?: string | undefined
}) {
  return (
    <Element className="eyebrow" id={id}>
      {children}
    </Element>
  )
}

/* ---- Page header ---------------------------------------------------------------------------- */

export function PageHeader({
  eyebrow,
  title,
  description,
  action,
  meta,
}: {
  eyebrow: string
  title: string
  description?: ReactNode
  /** One or more buttons. They wrap below the title on a narrow screen rather than overflow it. */
  action?: ReactNode
  /** A line of facts under the description: tags, a timestamp, an owner. */
  meta?: ReactNode
}) {
  return (
    <header className="page-header">
      <div className="page-header-text">
        <Eyebrow>{eyebrow}</Eyebrow>
        {/* Focusable from script only, so the shell can move focus here after a route change and
            a screen reader announces the new screen. It is not in the tab order. */}
        <h1 className="page-title" tabIndex={-1}>
          {title}
        </h1>
        {description && <p className="page-description">{description}</p>}
        {meta && <div className="page-header-meta">{meta}</div>}
      </div>
      {action && <div className="page-header-actions">{action}</div>}
    </header>
  )
}

export function SectionHeading({ children, note }: { children: ReactNode; note?: string }) {
  return (
    <div className="row" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-4)' }}>
      <h2 className="section-heading">{children}</h2>
      {note && <span className="section-note">{note}</span>}
    </div>
  )
}

/* ---- Card ------------------------------------------------------------------------------------ */

/**
 * A card, optionally clickable.
 *
 * When `href` or `onClick` is given the card renders as a real link or button rather than a div
 * with a handler, so it is reachable by keyboard and announced correctly. The hover state is not
 * decoration: a card that responds to the pointer but cannot be clicked is a broken promise, and
 * one that can be clicked but does not respond is invisible.
 */
export function Card({
  children,
  href,
  onClick,
  as: Element = 'div',
  className = '',
  ...rest
}: {
  children: ReactNode
  href?: string
  onClick?: () => void
  as?: 'div' | 'section' | 'article'
  className?: string
}) {
  const interactive = Boolean(href || onClick)
  const classes = `card ${interactive ? 'card-interactive' : ''} ${className}`.trim()

  if (href) {
    return (
      <a className={classes} href={href} {...rest}>
        {children}
      </a>
    )
  }
  if (onClick) {
    return (
      <button type="button" className={classes} onClick={onClick} {...rest}>
        {children}
      </button>
    )
  }
  return (
    <Element className={classes} {...rest}>
      {children}
    </Element>
  )
}

/* ---- Stat tiles ------------------------------------------------------------------------------ */

export function StatRow({ children }: { children: ReactNode }) {
  return <div className="stat-row">{children}</div>
}

/**
 * One figure in a stat row.
 *
 * The unit is a separate element, deliberately. A number with its unit fused into it cannot be
 * copied, cannot be sorted, and reads as a word rather than a quantity.
 *
 * With `href` the tile is a link to the list it counts. Its accessible name is everything on the
 * tile, so a screen reader hears "Failed 3" rather than a label without its figure.
 */
export function StatTile({
  label,
  value,
  unit,
  note,
  icon,
  href,
}: {
  label: string
  value: string | number
  unit?: string
  note?: string
  icon?: ReactNode
  href?: string | undefined
}) {
  const content = (
    <>
      <div className="stat-label">
        <span>{label}</span>
        {icon}
      </div>
      <div className="stat-value tabular">
        <span>{value}</span>
        {unit && <span className="stat-unit">{unit}</span>}
      </div>
      {note && <p className="stat-note">{note}</p>}
    </>
  )
  if (href) {
    return (
      <a className="stat-tile stat-tile-link" href={href}>
        {content}
      </a>
    )
  }
  return <div className="stat-tile">{content}</div>
}

/* ---- Tag -------------------------------------------------------------------------------------- */

/**
 * A small label carrying a status or a category.
 *
 * `withDot` exists so a tone is never the only thing distinguishing two tags: the text always
 * says what the tag means, and colour reinforces it. That is the difference between a design
 * that meets WCAG and one that merely looks like it does.
 *
 * Inside a table cell a long tag is cut short with an ellipsis; pass the full text as `title` so
 * it can still be read.
 */
export function Tag({
  children,
  tone = 'neutral',
  withDot = false,
  title,
}: {
  children: ReactNode
  tone?: TagTone
  withDot?: boolean
  title?: string | undefined
}) {
  return (
    <span className={`tag tag-${tone}`} title={title}>
      {withDot && <span className="tag-dot" aria-hidden="true" />}
      {/* Its own element, because an ellipsis applies to a block of text, not to the loose text
          of a flex container. */}
      <span className="tag-text">{children}</span>
    </span>
  )
}

/* ---- Time ------------------------------------------------------------------------------------- */

/**
 * A moment, as a <time> element.
 *
 * Relative by default ("5 minutes ago", "in 3 days"), kept current by one shared clock, with the
 * exact date and time in the title so "3 hours ago" can always be pinned down. `absolute` shows
 * the date and time itself. A missing or unreadable timestamp shows an em dash.
 */
export function Time({
  iso,
  mode = 'relative',
  className,
}: {
  iso?: string | null | undefined
  mode?: 'relative' | 'absolute'
  className?: string | undefined
}) {
  if (!iso || Number.isNaN(Date.parse(iso))) return <span className={className}>—</span>
  if (mode === 'absolute') {
    return (
      <time className={className} dateTime={iso}>
        {formatDateTime(iso)}
      </time>
    )
  }
  return <RelativeTime iso={iso} className={className} />
}

/** Split out so only relative times subscribe to the clock. */
function RelativeTime({ iso, className }: { iso: string; className: string | undefined }) {
  // Thirty seconds keeps "just now" from outliving its 45-second window by more than half a tick.
  const now = useNow(30_000)
  return (
    <time className={className} dateTime={iso} title={formatDateTime(iso)}>
      {formatRelative(iso, now)}
    </time>
  )
}

/* ---- Buttons ---------------------------------------------------------------------------------- */

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'outline' | 'quiet' | 'danger'
  icon?: ReactNode
  /** Disables the button and shows a spinner, so a slow action cannot be submitted twice. */
  loading?: boolean
}

export function Button({
  variant = 'primary',
  icon,
  loading = false,
  disabled,
  children,
  className = '',
  ...rest
}: ButtonProps) {
  return (
    <button
      type="button"
      className={`button button-${variant} ${className}`.trim()}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <Spinner /> : icon}
      {children}
    </button>
  )
}

export function Spinner({ size = 14 }: { size?: number }) {
  return (
    <svg className="spinner" width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="8" cy="8" r="6.5" stroke="currentColor" strokeOpacity="0.25" strokeWidth="2" />
      <path d="M14.5 8A6.5 6.5 0 0 0 8 1.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  )
}

export function IconButton({
  label,
  badge,
  children,
  className,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { label: string; badge?: number }) {
  return (
    <button
      type="button"
      // A caller's class is added to icon-button, never swapped for it: replacing it is how the
      // phone menu button once lost its size and hit area.
      className={`icon-button ${className ?? ''}`.trim()}
      aria-label={label}
      title={label}
      {...rest}
    >
      {children}
      {/* The count is announced as part of the label, so a screen reader hears "Alerts, 3
          unread" rather than an unexplained number. */}
      {badge !== undefined && badge > 0 && (
        <span className="bell-badge" aria-hidden="true">
          {badge > 99 ? '99+' : badge}
        </span>
      )}
      {badge !== undefined && badge > 0 && (
        <span className="visually-hidden">{badge} unread</span>
      )}
    </button>
  )
}

/* ---- Fields ------------------------------------------------------------------------------------
 * The error is text below the field, not a red border alone. Someone who cannot distinguish the
 * border colour still needs to know which field is wrong and why.
 */

type FieldProps = {
  label: string
  hint?: ReactNode
  error?: string | null | undefined
  /**
   * Adds "(optional)" to the label. Opt-in on purpose: many required fields carry no `required`
   * attribute, so marking every field without one as optional would be untrue.
   */
  optional?: boolean | undefined
}

/** The control's classes: the base class always, plus any the caller adds (never instead of it). */
const controlClass = (base: string, extra: string | undefined) => (extra ? `${base} ${extra}` : base)

/** The field's id (the caller's, if it passed one) and what its control is described by. */
function useFieldIds(idProp: string | undefined, hint: ReactNode, error: string | null | undefined) {
  const generated = useId()
  const id = idProp ?? generated
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  return { id, describedBy, invalid: error ? true : undefined }
}

function Field({ id, label, optional, hint, error, children }: FieldProps & { id: string; children: ReactNode }) {
  return (
    <div className="field">
      <label className="field-label" htmlFor={id}>
        {label}
        {optional && <span className="field-optional"> (optional)</span>}
      </label>
      {children}
      {hint && !error && (
        <p className="caption field-hint" id={`${id}-hint`}>
          {hint}
        </p>
      )}
      {error && (
        <p className="field-error" id={`${id}-error`}>
          {error}
        </p>
      )}
    </div>
  )
}

/** A labelled input. */
export function Input({
  label,
  error,
  hint,
  optional,
  id: idProp,
  className,
  ...rest
}: InputHTMLAttributes<HTMLInputElement> & FieldProps) {
  const { id, describedBy, invalid } = useFieldIds(idProp, hint, error)
  return (
    <Field id={id} label={label} optional={optional} hint={hint} error={error}>
      <input
        id={id}
        className={controlClass('input', className)}
        aria-invalid={invalid}
        aria-describedby={describedBy}
        {...rest}
      />
    </Field>
  )
}

export function Select({
  label,
  error,
  hint,
  optional,
  id: idProp,
  className,
  children,
  ...rest
}: SelectHTMLAttributes<HTMLSelectElement> & FieldProps) {
  const { id, describedBy, invalid } = useFieldIds(idProp, hint, error)
  return (
    <Field id={id} label={label} optional={optional} hint={hint} error={error}>
      <select id={id} className={controlClass('select', className)} aria-invalid={invalid} aria-describedby={describedBy} {...rest}>
        {children}
      </select>
    </Field>
  )
}

export function Textarea({
  label,
  error,
  hint,
  optional,
  id: idProp,
  className,
  ...rest
}: TextareaHTMLAttributes<HTMLTextAreaElement> & FieldProps) {
  const { id, describedBy, invalid } = useFieldIds(idProp, hint, error)
  return (
    <Field id={id} label={label} optional={optional} hint={hint} error={error}>
      <textarea
        id={id}
        className={controlClass('input textarea', className)}
        aria-invalid={invalid}
        aria-describedby={describedBy}
        {...rest}
      />
    </Field>
  )
}

/* ---- Notice -------------------------------------------------------------------------------------
 * One sentence, once per page. A caveat repeated beside every figure stops being read.
 */

export function Notice({
  tone = 'info',
  live = false,
  children,
}: {
  tone?: 'info' | 'warning' | 'success'
  /**
   * Announce the notice when it appears: pass it for a message that shows up in response to
   * something the person did, such as a failed submit. A banner that is simply part of the page
   * stays quiet, or a screen reader interrupts with it on every visit.
   */
  live?: boolean | undefined
  children: ReactNode
}) {
  const role = live ? (tone === 'warning' ? 'alert' : 'status') : undefined
  return (
    <div className={`notice notice-${tone}`} role={role}>
      {children}
    </div>
  )
}

/* ---- Table -------------------------------------------------------------------------------------- */

export type Column<T> = {
  key: string
  header: string
  numeric?: boolean
  render: (row: T) => ReactNode
  /**
   * Makes the column sortable by this value. Rows with no value sort last in either direction,
   * and rows with equal values keep their original order.
   */
  sortValue?: (row: T) => string | number | null | undefined
}

type SortState = { key: string; direction: 'ascending' | 'descending' } | null

/** Elements that act on their own when clicked, so a click on one is not a click on the row. */
const ROW_CLICK_EXEMPT = 'a, button, input, select, textarea, label, summary'

/**
 * Whether a click on a linked row should open the row. Not when a modifier asks the browser for a
 * new tab or window, not when the click landed on a control of its own, and not when it ended a
 * text selection - somebody copying an id out of a row has not asked to leave the page.
 */
function isPlainRowClick(event: ReactMouseEvent<HTMLTableRowElement>): boolean {
  if (event.defaultPrevented || event.button !== 0) return false
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return false
  const target = event.target
  if (target instanceof Element) {
    const control = target.closest(ROW_CLICK_EXEMPT)
    if (control && event.currentTarget.contains(control)) return false
  }
  const selection = window.getSelection()
  if (selection && !selection.isCollapsed && selection.toString().trim() !== '') return false
  return true
}

const collator = new Intl.Collator(LOCALE, { numeric: true, sensitivity: 'base' })

type SortKey = string | number | null | undefined

const isMissing = (value: SortKey) =>
  value === null || value === undefined || value === '' || (typeof value === 'number' && !Number.isFinite(value))

function compareKeys(a: string | number, b: string | number): number {
  if (typeof a === 'number' && typeof b === 'number') return a - b
  return collator.compare(String(a), String(b))
}

function sortRows<T>(rows: T[], value: (row: T) => SortKey, direction: 'ascending' | 'descending'): T[] {
  const factor = direction === 'ascending' ? 1 : -1
  return rows
    .map((row, index) => ({ row, index, key: value(row) }))
    .sort((a, b) => {
      const aMissing = isMissing(a.key)
      const bMissing = isMissing(b.key)
      if (aMissing || bMissing) return aMissing === bMissing ? a.index - b.index : aMissing ? 1 : -1
      return compareKeys(a.key as string | number, b.key as string | number) * factor || a.index - b.index
    })
    .map((entry) => entry.row)
}

function SortIcon({ direction }: { direction: 'ascending' | 'descending' | 'none' }) {
  return (
    <svg className="table-sort-icon" width="8" height="10" viewBox="0 0 8 10" fill="none" aria-hidden="true">
      {direction !== 'descending' && (
        <path d="M1 4l3-3 3 3" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
      )}
      {direction !== 'ascending' && (
        <path d="M1 6l3 3 3-3" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
      )}
    </svg>
  )
}

export function DataTable<T>({
  columns,
  rows,
  getKey,
  caption,
  getRowHref,
  getRowLabel,
}: {
  columns: Column<T>[]
  rows: T[]
  getKey: (row: T) => string
  /** Names the table for assistive technology, and the scrolling region that holds it. */
  caption: string
  /** When given, each row's first cell becomes a real link - openable in a new tab, copyable,
   * bookmarkable - and the rest of the row navigates the same place on click. */
  getRowHref?: (row: T) => string | undefined
  /** The row link's accessible name, when the first cell alone does not say which row it is. */
  getRowLabel?: ((row: T) => string) | undefined
}) {
  const { navigate } = useRouter()
  // Nothing is sorted until somebody asks: the order the screen passes in (usually newest first)
  // is the default, and a third click on a header returns to it.
  const [sort, setSort] = useState<SortState>(null)

  const sortBy = sort ? columns.find((column) => column.key === sort.key)?.sortValue : undefined
  const visibleRows = useMemo(
    () => (sort && sortBy ? sortRows(rows, sortBy, sort.direction) : rows),
    [rows, sort, sortBy],
  )

  const toggleSort = (key: string) =>
    setSort((current) => {
      if (!current || current.key !== key) return { key, direction: 'ascending' }
      if (current.direction === 'ascending') return { key, direction: 'descending' }
      return null
    })

  return (
    // A wide table scrolls inside its own region instead of stretching the page. The region is
    // focusable so it can be scrolled from the keyboard.
    <div className="table-scroll" role="region" aria-label={caption} tabIndex={0}>
      <table className="table">
        {/* Every figure carries its source. The caption is where a table states it, so the
            provenance travels with the numbers rather than living in a footnote elsewhere. */}
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            {columns.map((column) => {
              const direction = sort?.key === column.key ? sort.direction : 'none'
              return (
                <th
                  key={column.key}
                  scope="col"
                  className={column.numeric ? 'table-numeric' : undefined}
                  aria-sort={column.sortValue ? direction : undefined}
                >
                  {column.sortValue ? (
                    <button type="button" className="table-sort" onClick={() => toggleSort(column.key)}>
                      {column.header}
                      <SortIcon direction={direction} />
                    </button>
                  ) : (
                    column.header
                  )}
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {visibleRows.map((row) => {
            const href = getRowHref?.(row)
            return (
              <tr
                key={getKey(row)}
                data-href={href}
                onClick={
                  href
                    ? (event) => {
                        // The first-cell link navigates through the router's own click handling;
                        // navigating here as well pushed a second history entry.
                        if (isPlainRowClick(event)) navigate(href)
                      }
                    : undefined
                }
              >
                {columns.map((column, index) => (
                  <td key={column.key} className={column.numeric ? 'table-numeric tabular' : undefined}>
                    {href && index === 0 ? (
                      <a href={href} className="table-row-link" aria-label={getRowLabel?.(row)}>
                        {column.render(row)}
                      </a>
                    ) : (
                      column.render(row)
                    )}
                  </td>
                ))}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

/* ---- Empty state ---------------------------------------------------------------------------------- */

export function EmptyState({
  icon,
  title,
  body,
  action,
  titleAs: Title = 'h2',
}: {
  icon: ReactNode
  title: string
  body: ReactNode
  action?: ReactNode
  /** h1 when the empty state is the whole page (a missing page, say); h3 inside a titled section. */
  titleAs?: 'h1' | 'h2' | 'h3'
}) {
  return (
    <div className="empty-state">
      <div className="empty-icon" aria-hidden="true">
        {icon}
      </div>
      <Title className="empty-title">{title}</Title>
      <p className="empty-body">{body}</p>
      {action}
    </div>
  )
}

/* ---- Assistant launcher ----------------------------------------------------------------------------- */

export function AssistantLauncher({ onClick, panelOpen = false }: { onClick: () => void; panelOpen?: boolean }) {
  return (
    <button type="button" className="assistant-launcher" onClick={onClick} data-panel-open={panelOpen}>
      <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path
          d="M8 1.5 L9.4 5.6 L13.5 7 L9.4 8.4 L8 12.5 L6.6 8.4 L2.5 7 L6.6 5.6 Z"
          fill="currentColor"
        />
        <path d="M13 11 L13.6 12.6 L15 13 L13.6 13.4 L13 15 L12.4 13.4 L11 13 L12.4 12.6 Z" fill="currentColor" />
      </svg>
      Ask the assistant
    </button>
  )
}

/* ---- Loading, error and permission states ----------------------------------------------------------
 * Every screen that loads data has four states, not one: loading, loaded, empty and failed. A
 * screen that only designs the loaded state shows a blank page while it waits and a blank page
 * when it fails, and a person cannot tell those apart from "there is nothing here".
 */

/** Placeholder shapes in the layout of what is coming, so the page does not jump when it arrives. */
export function LoadingState({ rows = 3, label = 'Loading' }: { rows?: number; label?: string }) {
  return (
    // A status region with a real label: a screen reader hears "Loading the goals list" rather
    // than nothing, and the placeholder shapes themselves are hidden from it.
    <div className="stack" style={{ gap: 'var(--space-3)' }} role="status" aria-busy="true">
      <span className="visually-hidden">{label}</span>
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="skeleton" aria-hidden="true" style={{ width: `${92 - index * 9}%` }} />
      ))}
    </div>
  )
}

/**
 * A failure, explained, with a way forward.
 *
 * The retry button appears only when retrying could help. Offering it for a validation error or
 * a missing record invites a person to press it repeatedly for a result that will never change.
 */
export function ErrorState({
  title = 'This could not be loaded',
  message,
  onRetry,
  retryable = true,
}: {
  title?: string
  message: string
  onRetry?: () => void
  retryable?: boolean
}) {
  return (
    <div className="state-panel" role="alert">
      <div className="empty-icon state-icon-danger" aria-hidden="true">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none">
          <path d="M12 8v5M12 16.5v.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.6" />
        </svg>
      </div>
      <h2 className="empty-title">{title}</h2>
      <p className="empty-body">{message}</p>
      {onRetry && retryable && (
        <Button variant="outline" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  )
}

const withoutFinalStop = (text: string) => text.trim().replace(/[.\s]+$/, '')

const capitalised = (text: string) => (text ? text.charAt(0).toUpperCase() + text.slice(1) : text)

/** Words for a permission code while its catalogue description is unavailable: 'Approval: read'. */
function permissionWords(code: string): string {
  const [resource = '', action = ''] = code.split(':')
  if (!action) return sentenceCase(resource) || 'A permission'
  return `${sentenceCase(resource)}: ${sentenceCase(action).toLowerCase()}`
}

/**
 * What a person sees when their role does not allow something.
 *
 * It says, in words, what the missing permission allows, and who can grant it. "Access denied" on
 * its own leaves a person unsure whether the feature is broken, whether they did something wrong,
 * or whom to ask. The permission code stays available in a tooltip for whoever administers roles.
 *
 * `what` is the subject of the sentence: "the approvals queue" reads "The approvals queue needs:
 * See the approvals queue."
 */
export function PermissionState({ permission, what }: { permission: string; what: string }) {
  // The catalogue needs only workspace:read, which every role holds.
  const catalogue = usePermissionCatalogue()
  const described = catalogue.data?.find((entry) => entry.code === permission)?.description
  const need = described ? withoutFinalStop(described) : permissionWords(permission)
  return (
    <div className="state-panel">
      <div className="empty-icon" aria-hidden="true">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none">
          <rect x="5" y="10.5" width="14" height="10" rx="2" stroke="currentColor" strokeWidth="1.6" />
          <path d="M8.5 10.5V7.5a3.5 3.5 0 0 1 7 0v3" stroke="currentColor" strokeWidth="1.6" />
        </svg>
      </div>
      <h2 className="empty-title">Your role does not include this</h2>
      <p className="empty-body">
        {capitalised(what.trim())} needs: <span title={permission}>{need}</span>. An owner or admin can
        give you a role that includes it, in Members and roles.
      </p>
      <a className="link" href="/profile">
        See what your role allows
      </a>
    </div>
  )
}

/* ---- Dialog ---------------------------------------------------------------------------------------
 * The native <dialog> element, which handles focus trapping, the Escape key and the backdrop, and
 * returns focus to whatever opened it. Reimplementing those is how dialogs end up keyboard traps.
 */

const FIRST_FIELD = 'input:not([type="hidden"]):not(:disabled), select:not(:disabled), textarea:not(:disabled)'

/**
 * Where focus starts: an element marked data-autofocus, else the first field in the content, else
 * the dialog itself. Never the Close button, which the browser would pick, so Enter on opening
 * never throws away the dialog. (React's autoFocus fires while the dialog is still closed, so it
 * cannot do this.)
 */
function initialFocusTarget(dialog: HTMLDialogElement): HTMLElement {
  const marked = dialog.querySelector<HTMLElement>('[data-autofocus]')
  if (marked && !marked.matches(':disabled')) return marked
  return dialog.querySelector('.dialog-content')?.querySelector<HTMLElement>(FIRST_FIELD) ?? dialog
}

export function Dialog({
  open,
  onClose,
  eyebrow,
  title,
  description,
  children,
  footer,
  dismissible = true,
  closeOnBackdrop = true,
  error,
}: {
  open: boolean
  onClose: () => void
  eyebrow: string
  title: string
  description?: ReactNode
  children?: ReactNode
  /** Buttons for the foot of the dialog. A submit button outside the form uses form={formId}. */
  footer?: ReactNode
  /**
   * False while something is in progress that closing would orphan, such as a submit: Escape,
   * the backdrop and the Close button then do nothing.
   */
  dismissible?: boolean | undefined
  /**
   * False for a dialog holding something that cannot be shown again, such as a one-time link: a
   * stray click beside it then does nothing, while Escape and Close still work.
   */
  closeOnBackdrop?: boolean | undefined
  /** A failure to show inside the dialog, where a toast would sit hidden under the backdrop. */
  error?: string | null | undefined
}) {
  const ref = useRef<HTMLDialogElement>(null)
  // Several dialogs can share a page, so every id is the dialog's own.
  const titleId = useId()
  const descriptionId = useId()
  // Whether the parent still wants the dialog open, so a close the parent asked for is not
  // reported back to it as though the person had dismissed the dialog.
  const openRef = useRef(open)
  // Where the current press began. Dragging a text selection out of a field and releasing over the
  // backdrop produces a click on the backdrop, and must not throw away what was typed.
  const pressStartedOnBackdrop = useRef(false)

  useEffect(() => {
    openRef.current = open
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) {
      dialog.showModal()
      initialFocusTarget(dialog).focus()
    }
    if (!open && dialog.open) dialog.close()
  }, [open])

  return (
    <dialog
      ref={ref}
      className="dialog"
      tabIndex={-1}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      onClose={() => {
        if (openRef.current) onClose()
      }}
      onCancel={(event) => {
        if (!dismissible) event.preventDefault()
      }}
      onKeyDown={(event) => {
        // Chrome closes a dialog on a second Escape even when the cancel event was refused;
        // refusing the key itself stops that.
        if (event.key === 'Escape' && !dismissible) event.preventDefault()
      }}
      onPointerDown={(event) => {
        pressStartedOnBackdrop.current = event.target === event.currentTarget
      }}
      onClick={(event) => {
        // The dialog box has no padding of its own, so a click whose target is the dialog element
        // landed on the backdrop.
        const onBackdrop = pressStartedOnBackdrop.current && event.target === event.currentTarget
        pressStartedOnBackdrop.current = false
        if (onBackdrop && dismissible && closeOnBackdrop) onClose()
      }}
    >
      <div className="dialog-body">
        <div className="dialog-header">
          <div className="dialog-heading">
            <Eyebrow>{eyebrow}</Eyebrow>
            <h2 id={titleId} className="dialog-title">
              {title}
            </h2>
            {description && (
              <p id={descriptionId} className="dialog-description muted">
                {description}
              </p>
            )}
          </div>
          <IconButton label="Close" onClick={onClose} disabled={!dismissible}>
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
              <path d="M2 2l10 10M12 2L2 12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </IconButton>
        </div>
        {error && (
          <div className="dialog-error">
            <Notice tone="warning" live>
              {error}
            </Notice>
          </div>
        )}
        {children !== undefined && children !== null && <div className="dialog-content">{children}</div>}
        {footer && <div className="dialog-footer">{footer}</div>}
      </div>
    </dialog>
  )
}

/**
 * A question before an action that cannot be undone, or that changes something for other people.
 *
 * The caller owns the action: it passes `loading` while the request runs and `error` if it failed,
 * so the dialog stays open with the reason rather than closing on a failure. A dangerous action
 * starts with focus on the button that keeps things as they are.
 */
export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  eyebrow,
  title,
  description,
  confirmLabel,
  cancelLabel = 'Keep it',
  tone,
  loading = false,
  error,
  children,
}: {
  open: boolean
  onClose: () => void
  onConfirm: () => void | Promise<unknown>
  eyebrow: string
  title: string
  description?: ReactNode
  confirmLabel: string
  cancelLabel?: string
  tone: 'danger' | 'primary'
  loading?: boolean | undefined
  error?: string | null | undefined
  /** Anything more the decision needs, such as a field for a reason. */
  children?: ReactNode
}) {
  const danger = tone === 'danger'
  return (
    <Dialog
      open={open}
      onClose={onClose}
      eyebrow={eyebrow}
      title={title}
      description={description}
      dismissible={!loading}
      error={error}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={loading} data-autofocus={danger || undefined}>
            {cancelLabel}
          </Button>
          <Button
            variant={danger ? 'danger' : 'primary'}
            onClick={() => void onConfirm()}
            loading={loading}
            data-autofocus={danger ? undefined : true}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      {children}
    </Dialog>
  )
}

/* ---- Status tags ----------------------------------------------------------------------------------
 * One mapping from a status to its tone and its words, so "waiting for approval" is the same colour
 * and the same phrase wherever it appears. Pass `kind`: the same word means different things for
 * different records ("pending" is a queued document, an undecided approval and a task whose turn
 * has not come), and lib/labels.ts keeps a map for each.
 */

/** The single shared map used before statuses carried a kind. Kept until every caller passes one. */
const LEGACY_STATUS: Record<string, { tone: TagTone; label: string }> = {
  running: { tone: 'blue', label: 'Running' },
  planning: { tone: 'blue', label: 'Planning' },
  pending: { tone: 'neutral', label: 'Waiting to start' },
  ready: { tone: 'neutral', label: 'Ready' },
  waiting_approval: { tone: 'warning', label: 'Waiting for approval' },
  waiting: { tone: 'warning', label: 'Waiting' },
  completed: { tone: 'success', label: 'Completed' },
  approved: { tone: 'success', label: 'Approved' },
  indexed: { tone: 'success', label: 'Indexed' },
  active: { tone: 'success', label: 'Active' },
  failed: { tone: 'danger', label: 'Failed' },
  rejected: { tone: 'danger', label: 'Rejected' },
  cancelled: { tone: 'neutral', label: 'Cancelled' },
  abandoned: { tone: 'neutral', label: 'Abandoned' },
  skipped: { tone: 'warning', label: 'Not indexable' },
  expired: { tone: 'neutral', label: 'Expired' },
  paused: { tone: 'neutral', label: 'Paused' },
  idle: { tone: 'neutral', label: 'Not indexed yet' },
  reconnect_required: { tone: 'warning', label: 'Reconnect needed' },
  sandbox: { tone: 'neutral', label: 'Sandbox' },
  connected: { tone: 'success', label: 'Connected' },
}

function legacyStatus(status: string | null | undefined): { tone: TagTone; label: string } {
  if (!status || !status.trim()) return { tone: 'neutral', label: 'Unknown' }
  return LEGACY_STATUS[status] ?? { tone: 'neutral', label: sentenceCase(status) }
}

export function StatusTag({
  kind,
  status,
  withDot = false,
}: {
  kind?: StatusKind | undefined
  status: string | null | undefined
  withDot?: boolean
}) {
  const entry = kind ? statusLabel(kind, status) : legacyStatus(status)
  return (
    <Tag tone={entry.tone} withDot={withDot}>
      {entry.label}
    </Tag>
  )
}

export { FilterBar, FilterEmpty } from './FilterBar'
export type { FilterFacet, FilterOption, FilterSelect } from './FilterBar'
export { TaskDialog } from './TaskDialog'
