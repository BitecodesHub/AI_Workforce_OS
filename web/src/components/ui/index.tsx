import type { ReactNode, ButtonHTMLAttributes, InputHTMLAttributes, SelectHTMLAttributes } from 'react'
import React, { useEffect, useId, useRef } from 'react'
import { useRouter } from '../../lib/router'

/*
 * The primitives every screen is built from.
 *
 * None of them carries a colour, a size or a radius of its own: each maps to a class in
 * components.css, which draws from tokens.css. That is what keeps sixteen screens looking like
 * one product rather than sixteen negotiations.
 */

/* ---- Eyebrow -------------------------------------------------------------------------------- */

/**
 * The mono line that opens every page and every card section.
 *
 * It is a label for the kind of thing below it, not a heading: "YOUR WORKFORCE, IN FOCUS" above
 * "Command Map". Screens without one are caught by the design-system test.
 */
export function Eyebrow({ children }: { children: ReactNode }) {
  return <p className="eyebrow">{children}</p>
}

/* ---- Page header ---------------------------------------------------------------------------- */

export function PageHeader({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <header className="page-header">
      <div>
        <Eyebrow>{eyebrow}</Eyebrow>
        <h1 className="page-title">{title}</h1>
        {description && <p className="page-description">{description}</p>}
      </div>
      {action && <div className="row">{action}</div>}
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
 */
export function StatTile({
  label,
  value,
  unit,
  note,
  icon,
}: {
  label: string
  value: string | number
  unit?: string
  note?: string
  icon?: ReactNode
}) {
  return (
    <div className="stat-tile">
      <div className="stat-label">
        <span>{label}</span>
        {icon}
      </div>
      <div className="stat-value tabular">
        <span>{value}</span>
        {unit && <span className="stat-unit">{unit}</span>}
      </div>
      {note && <p className="stat-note">{note}</p>}
    </div>
  )
}

/* ---- Tag -------------------------------------------------------------------------------------- */

export type TagTone =
  | 'blue'
  | 'success'
  | 'warning'
  | 'danger'
  | 'neutral'
  | 'operations'
  | 'engineering'
  | 'growth'
  | 'support'

/**
 * A small label carrying a status or a category.
 *
 * `withDot` exists so a tone is never the only thing distinguishing two tags: the text always
 * says what the tag means, and colour reinforces it. That is the difference between a design
 * that meets WCAG and one that merely looks like it does.
 */
export function Tag({
  children,
  tone = 'neutral',
  withDot = false,
}: {
  children: ReactNode
  tone?: TagTone
  withDot?: boolean
}) {
  return (
    <span className={`tag tag-${tone}`}>
      {withDot && <span className="tag-dot" aria-hidden="true" />}
      {children}
    </span>
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
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { label: string; badge?: number }) {
  return (
    <button type="button" className="icon-button" aria-label={label} title={label} {...rest}>
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

/* ---- Fields ------------------------------------------------------------------------------------ */

/**
 * A labelled input.
 *
 * The error is text below the field, not a red border alone. Someone who cannot distinguish the
 * border colour still needs to know which field is wrong and why.
 */
export function Input({
  label,
  error,
  hint,
  ...rest
}: InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string; hint?: string }) {
  const id = useId()
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  return (
    <div className="field">
      <label className="field-label" htmlFor={id}>
        {label}
      </label>
      <input
        id={id}
        className="input"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        {...rest}
      />
      {hint && !error && (
        <p className="caption" id={`${id}-hint`} style={{ marginTop: 'var(--space-2)' }}>
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

export function Select({
  label,
  children,
  ...rest
}: SelectHTMLAttributes<HTMLSelectElement> & { label: string }) {
  const id = useId()
  return (
    <div className="field">
      <label className="field-label" htmlFor={id}>
        {label}
      </label>
      <select id={id} className="select" {...rest}>
        {children}
      </select>
    </div>
  )
}

/* ---- Notice -------------------------------------------------------------------------------------
 * One sentence, once per page. A caveat repeated beside every figure stops being read.
 */

export function Notice({
  tone = 'info',
  children,
}: {
  tone?: 'info' | 'warning' | 'success'
  children: ReactNode
}) {
  return (
    <div className={`notice notice-${tone}`} role={tone === 'warning' ? 'alert' : 'status'}>
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
}

export function DataTable<T>({
  columns,
  rows,
  getKey,
  caption,
  getRowHref,
}: {
  columns: Column<T>[]
  rows: T[]
  getKey: (row: T) => string
  caption: string
  /** When given, each row's first cell becomes a real link - openable in a new tab, copyable,
   * bookmarkable - and the rest of the row navigates the same place on click. */
  getRowHref?: (row: T) => string | undefined
}) {
  const { navigate } = useRouter()
  return (
    <table className="table">
      {/* Every figure carries its source. The caption is where a table states it, so the
          provenance travels with the numbers rather than living in a footnote elsewhere. */}
      <caption className="visually-hidden">{caption}</caption>
      <thead>
        <tr>
          {columns.map((column) => (
            <th key={column.key} scope="col" className={column.numeric ? 'table-numeric' : undefined}>
              {column.header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => {
          const href = getRowHref?.(row)
          return (
            <tr key={getKey(row)} data-href={href} onClick={href ? () => navigate(href) : undefined}>
              {columns.map((column, index) => (
                <td key={column.key} className={column.numeric ? 'table-numeric tabular' : undefined}>
                  {href && index === 0 ? (
                    <a href={href} className="table-row-link">
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
  )
}

/* ---- Empty state ---------------------------------------------------------------------------------- */

export function EmptyState({
  icon,
  title,
  body,
  action,
}: {
  icon: ReactNode
  title: string
  body: string
  action?: ReactNode
}) {
  return (
    <div className="empty-state">
      <div className="empty-icon" aria-hidden="true">
        {icon}
      </div>
      <h2 className="empty-title">{title}</h2>
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


/* ---- Textarea ------------------------------------------------------------------------------------ */

export function Textarea({
  label,
  error,
  hint,
  ...rest
}: React.TextareaHTMLAttributes<HTMLTextAreaElement> & { label: string; error?: string; hint?: string }) {
  const id = useId()
  return (
    <div className="field">
      <label className="field-label" htmlFor={id}>
        {label}
      </label>
      <textarea
        id={id}
        className="input textarea"
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined}
        {...rest}
      />
      {hint && !error && (
        <p className="caption" id={`${id}-hint`} style={{ marginTop: 'var(--space-2)' }}>
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

/* ---- Loading, error and permission states ----------------------------------------------------------
 * Every screen that loads data has four states, not one: loading, loaded, empty and failed. A
 * screen that only designs the loaded state shows a blank page while it waits and a blank page
 * when it fails, and a person cannot tell those apart from "there is nothing here".
 */

/** Placeholder shapes in the layout of what is coming, so the page does not jump when it arrives. */
export function LoadingState({ rows = 3, label = 'Loading' }: { rows?: number; label?: string }) {
  return (
    <div className="stack" style={{ gap: 'var(--space-3)' }} aria-busy="true" aria-label={label}>
      {Array.from({ length: rows }, (_, index) => (
        <div key={index} className="skeleton" style={{ width: `${92 - index * 9}%` }} />
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

/**
 * What a person sees when their role does not allow something.
 *
 * It says which permission is missing and who can grant it. "Access denied" on its own leaves a
 * person unsure whether the feature is broken, whether they did something wrong, or whom to ask.
 */
export function PermissionState({ permission, what }: { permission: string; what: string }) {
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
        Seeing {what} needs the <span className="mono">{permission}</span> permission. A workspace
        administrator or owner can add it to your role in Members and roles.
      </p>
    </div>
  )
}

/* ---- Dialog ---------------------------------------------------------------------------------------
 * The native <dialog> element, which handles focus trapping, the Escape key and the backdrop, and
 * returns focus to whatever opened it. Reimplementing those is how dialogs end up keyboard traps.
 */

export function Dialog({
  open,
  onClose,
  eyebrow,
  title,
  description,
  children,
  footer,
}: {
  open: boolean
  onClose: () => void
  eyebrow: string
  title: string
  description?: string
  children: ReactNode
  footer?: ReactNode
}) {
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])

  return (
    <dialog
      ref={ref}
      className="dialog"
      onClose={onClose}
      onClick={(event) => {
        // A click on the backdrop closes the dialog; a click inside it does not.
        if (event.target === ref.current) onClose()
      }}
      aria-labelledby="dialog-title"
    >
      <div className="dialog-body">
        <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <div>
            <Eyebrow>{eyebrow}</Eyebrow>
            <h2 id="dialog-title" className="dialog-title">
              {title}
            </h2>
            {description && <p className="muted" style={{ marginTop: 'var(--space-2)' }}>{description}</p>}
          </div>
          <IconButton label="Close" onClick={onClose}>
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
              <path d="M2 2l10 10M12 2L2 12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </IconButton>
        </div>
        <div style={{ marginTop: 'var(--space-6)' }}>{children}</div>
        {footer && <div className="dialog-footer">{footer}</div>}
      </div>
    </dialog>
  )
}

/* ---- Status tags ----------------------------------------------------------------------------------
 * One mapping from a status to its tone and its words, used on every screen, so "waiting for
 * approval" is the same colour and the same phrase wherever it appears.
 */

const STATUS: Record<string, { tone: TagTone; label: string }> = {
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
  ready_source: { tone: 'success', label: 'Ready' },
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

export function StatusTag({ status }: { status: string }) {
  const entry = STATUS[status] ?? { tone: 'neutral' as TagTone, label: status.replace(/_/g, ' ') }
  return <Tag tone={entry.tone}>{entry.label}</Tag>
}

export const CATEGORY_LABEL: Record<string, string> = {
  operations: 'Operations',
  engineering: 'Engineering',
  growth: 'Growth',
  support: 'Support',
}

export { TaskDialog } from './TaskDialog'
