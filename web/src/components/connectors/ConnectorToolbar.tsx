// @find: connector filters, search connectors, category filter, status filter, live summary, connected, disconnected, filter chips, Connectors page toolbar
// @what: Filters and search for the Connectors page, plus the live summary.
// @flow: Used by the Connectors page; state kept in URL by useListFilter.
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, ReactNode, RefObject } from 'react'
import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { formatCount } from '../../lib/format'
import type { ConnectorState, ConnectorSummary } from '../../lib/connectors'
import { floatingMenuPosition } from '../ui/Menu'

/*
 * The Connectors page's filters on one line: a search that widens while it is used, a Category and
 * a Status dropdown, and on the right a small pill saying how many connectors are live. The
 * sentence about practice data that used to fill a banner sits behind that pill.
 *
 * The state itself lives in the URL through useListFilter; this only draws the controls. Each
 * dropdown is a button that opens a single-select listbox in a floating layer of its own, placed
 * below the button or above it when there is no room, so the connector grid never clips it.
 */

/* ---- Floating placement ------------------------------------------------------------------------- */

/** Places an open panel beside its trigger and keeps it there while the page scrolls or resizes. */
function useFloating(
  open: boolean,
  triggerRef: RefObject<HTMLElement | null>,
  panelRef: RefObject<HTMLElement | null>,
  align: 'start' | 'end',
): CSSProperties {
  const [style, setStyle] = useState<CSSProperties>({ position: 'fixed', top: 0, left: 0 })
  useLayoutEffect(() => {
    if (!open) return
    const place = () => {
      const trigger = triggerRef.current
      const panel = panelRef.current
      if (!trigger || !panel) return
      const position = floatingMenuPosition(
        trigger.getBoundingClientRect(),
        { width: panel.offsetWidth, height: panel.offsetHeight },
        { width: window.innerWidth, height: window.innerHeight },
        align,
      )
      setStyle({
        position: 'fixed',
        top: position.top,
        left: position.left,
        transformOrigin: `${position.placement === 'below' ? 'top' : 'bottom'} ${align === 'end' ? 'right' : 'left'}`,
      })
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [open, triggerRef, panelRef, align])
  return style
}

/** Closes an open panel on a press anywhere outside it and its trigger. */
function useOutsidePress(open: boolean, refs: Array<RefObject<HTMLElement | null>>, onOutside: () => void) {
  const latest = useRef({ refs, onOutside })
  useLayoutEffect(() => {
    latest.current = { refs, onOutside }
  })
  useEffect(() => {
    if (!open) return
    const onPointerDown = (event: PointerEvent) => {
      if (!(event.target instanceof Node)) return
      const target = event.target
      if (latest.current.refs.some((ref) => ref.current?.contains(target))) return
      latest.current.onOutside()
    }
    document.addEventListener('pointerdown', onPointerDown)
    return () => document.removeEventListener('pointerdown', onPointerDown)
  }, [open])
}

/* ---- Glyphs ------------------------------------------------------------------------------------- */

function Glyph({ children, size = 14 }: { children: ReactNode; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  )
}

function SearchGlyph() {
  return (
    <Glyph>
      <circle cx="7" cy="7" r="4.5" />
      <path d="M10.5 10.5L14 14" />
    </Glyph>
  )
}

function CaretGlyph() {
  return (
    <Glyph size={12}>
      <path d="M4 6l4 4 4-4" />
    </Glyph>
  )
}

function CheckGlyph() {
  return (
    <Glyph>
      <path d="M3.5 8.5l3 3 6-7" />
    </Glyph>
  )
}

function InfoGlyph() {
  return (
    <Glyph>
      <circle cx="8" cy="8" r="6" />
      <path d="M8 7.5v3.5M8 5h.01" />
    </Glyph>
  )
}

function CloseGlyph() {
  return (
    <Glyph size={10}>
      <path d="M4 4l8 8M12 4l-8 8" />
    </Glyph>
  )
}

// @find: CategoryGlyph, category glyph, connector filters, search connectors, category filter, status filter
/** A small line drawing per connector category; anything unknown gets the generic one. */
export function CategoryGlyph({ category }: { category: string }) {
  switch (category) {
    case 'communication':
      return (
        <Glyph>
          <path d="M2.5 4.5a2 2 0 012-2h7a2 2 0 012 2v4.5a2 2 0 01-2 2H7l-3 2.5v-2.5h0a1.5 1.5 0 01-1.5-1.5z" />
        </Glyph>
      )
    case 'productivity':
      return (
        <Glyph>
          <rect x="2.5" y="2.5" width="11" height="11" rx="2" />
          <path d="M5.5 8l1.8 1.8L10.5 6" />
        </Glyph>
      )
    case 'engineering':
      return (
        <Glyph>
          <path d="M5.5 4.5L2 8l3.5 3.5M10.5 4.5L14 8l-3.5 3.5" />
        </Glyph>
      )
    case 'sales':
      return (
        <Glyph>
          <path d="M2.5 12.5l4-4 2.5 2.5 4.5-5" />
          <path d="M10 6h3.5v3.5" />
        </Glyph>
      )
    case 'support':
      return (
        <Glyph>
          <circle cx="8" cy="8" r="5.5" />
          <circle cx="8" cy="8" r="2.2" />
          <path d="M4.1 4.1l2.3 2.3M11.9 4.1L9.6 6.4M4.1 11.9l2.3-2.3M11.9 11.9L9.6 9.6" />
        </Glyph>
      )
    case 'files':
      return (
        <Glyph>
          <path d="M4 2.5h5l3 3v8H4z" />
          <path d="M9 2.5v3h3M6 9h4M6 11h4" />
        </Glyph>
      )
    case 'finance':
      return (
        <Glyph>
          <rect x="2" y="4" width="12" height="8" rx="1.5" />
          <circle cx="8" cy="8" r="1.8" />
        </Glyph>
      )
    case 'automation':
      return (
        <Glyph>
          <path d="M9 2L4 9h4l-1 5 5-7H8z" />
        </Glyph>
      )
    case 'voice':
      return (
        <Glyph>
          <rect x="6" y="2" width="4" height="7.5" rx="2" />
          <path d="M3.5 8a4.5 4.5 0 009 0M8 12.5V14" />
        </Glyph>
      )
    case 'all':
      return (
        <Glyph>
          <path d="M8 2.5l5.5 3L8 8.5l-5.5-3z" />
          <path d="M2.5 8.5L8 11.5l5.5-3M2.5 11L8 14l5.5-3" />
        </Glyph>
      )
    default:
      return (
        <Glyph>
          <rect x="2.5" y="2.5" width="4.5" height="4.5" rx="1" />
          <rect x="9" y="2.5" width="4.5" height="4.5" rx="1" />
          <rect x="2.5" y="9" width="4.5" height="4.5" rx="1" />
          <rect x="9" y="9" width="4.5" height="4.5" rx="1" />
        </Glyph>
      )
  }
}

// @find: StatusDot, status dot, connector filters, search connectors, category filter, status filter
/** The coloured dot beside a status: green for live, grey for practice, amber for a problem. */
export function StatusDot({ state }: { state: ConnectorState | 'disconnected' | 'all' }) {
  return <span className={`cx-dot cx-dot-${state}`} aria-hidden="true" />
}

/* ---- One dropdown ------------------------------------------------------------------------------- */

export type DropdownOption = {
  value: string
  label: string
  count: number
  icon?: ReactNode
}

// @find: FilterDropdown, filter dropdown, connector filters, search connectors, category filter, status filter
/**
 * A single choice from a short list, as a button that opens a listbox. The empty value is the
 * "all" choice at the top. Arrow keys, Home and End move, Enter or Space chooses, Escape and Tab
 * close, and typing a letter jumps to the next option starting with it.
 */
export function FilterDropdown({
  label,
  allLabel,
  allIcon,
  allCount,
  options,
  value,
  onChange,
}: {
  /** What is being filtered, such as "Category"; part of the button's name. */
  label: string
  /** The option that clears this filter, such as "All categories". */
  allLabel: string
  allIcon?: ReactNode
  /** How many rows "all" shows; defaults to the options' counts added up. */
  allCount?: number | undefined
  options: DropdownOption[]
  /** The chosen value, or '' for all. */
  value: string
  onChange: (value: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const baseId = useId()
  const listId = `${baseId}-list`
  const optionId = (index: number) => `${baseId}-option-${index}`

  const items: DropdownOption[] = [
    { value: '', label: allLabel, count: allCount ?? options.reduce((sum, option) => sum + option.count, 0), icon: allIcon },
    ...options,
  ]
  const selectedIndex = Math.max(
    0,
    items.findIndex((item) => item.value === value),
  )
  const selected = items[selectedIndex]!
  const floatStyle = useFloating(open, triggerRef, panelRef, 'start')

  const show = (index = selectedIndex) => {
    setActive(index)
    setOpen(true)
  }
  const close = (focusTrigger: boolean) => {
    setOpen(false)
    if (focusTrigger) triggerRef.current?.focus()
  }
  const choose = (index: number) => {
    const item = items[index]
    if (item && item.value !== value) onChange(item.value)
    close(true)
  }

  useOutsidePress(open, [triggerRef, panelRef], () => setOpen(false))

  useEffect(() => {
    if (open) listRef.current?.focus()
  }, [open])

  useEffect(() => {
    if (!open) return
    document.getElementById(optionId(active))?.scrollIntoView?.({ block: 'nearest' })
    // optionId only depends on baseId, which never changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, active])

  const onTriggerKeyDown = (event: ReactKeyboardEvent<HTMLButtonElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      show(event.key === 'ArrowUp' && selectedIndex === 0 ? items.length - 1 : selectedIndex)
    }
  }

  const onListKeyDown = (event: ReactKeyboardEvent<HTMLUListElement>) => {
    const last = items.length - 1
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        setActive((current) => Math.min(last, current + 1))
        return
      case 'ArrowUp':
        event.preventDefault()
        setActive((current) => Math.max(0, current - 1))
        return
      case 'Home':
        event.preventDefault()
        setActive(0)
        return
      case 'End':
        event.preventDefault()
        setActive(last)
        return
      case 'Enter':
      case ' ':
        event.preventDefault()
        choose(active)
        return
      case 'Escape':
        event.preventDefault()
        event.stopPropagation()
        close(true)
        return
      case 'Tab':
        // The panel sits at the end of the document; Tab from it returns to the button.
        event.preventDefault()
        close(true)
        return
    }
    if (event.key.length === 1 && /\S/.test(event.key) && !event.ctrlKey && !event.metaKey && !event.altKey) {
      const letter = event.key.toLowerCase()
      for (let step = 1; step <= items.length; step += 1) {
        const index = (active + step) % items.length
        if (items[index]!.label.toLowerCase().startsWith(letter)) {
          setActive(index)
          return
        }
      }
    }
  }

  const filtering = value !== ''

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className="cx-dropdown"
        data-active={filtering || undefined}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-label={`${label}: ${selected.label}, ${formatCount(selected.count)}`}
        onClick={() => (open ? close(false) : show())}
        onKeyDown={onTriggerKeyDown}
      >
        {selected.icon && <span className="cx-dropdown-icon">{selected.icon}</span>}
        <span className="cx-dropdown-label">{selected.label}</span>
        <span className="cx-count tabular">{formatCount(selected.count)}</span>
        <span className="cx-dropdown-caret">
          <CaretGlyph />
        </span>
      </button>
      {open &&
        createPortal(
          <div ref={panelRef} className="cx-popover" style={floatStyle}>
            <ul
              ref={listRef}
              id={listId}
              role="listbox"
              tabIndex={-1}
              aria-label={label}
              aria-activedescendant={optionId(active)}
              className="cx-listbox"
              onKeyDown={onListKeyDown}
            >
              {items.map((item, index) => (
                <li
                  key={item.value || '__all'}
                  id={optionId(index)}
                  role="option"
                  aria-selected={index === selectedIndex}
                  data-active={index === active || undefined}
                  data-empty={item.count === 0 || undefined}
                  className={`cx-option${index === 0 ? ' cx-option-all' : ''}`}
                  onPointerMove={() => setActive(index)}
                  onClick={() => choose(index)}
                >
                  {item.icon && <span className="cx-option-icon">{item.icon}</span>}
                  <span className="cx-option-label">{item.label}</span>
                  <span className="cx-count tabular">{formatCount(item.count)}</span>
                  <span className="cx-option-check">{index === selectedIndex && <CheckGlyph />}</span>
                </li>
              ))}
            </ul>
          </div>,
          document.body,
        )}
    </>
  )
}

/* ---- Live summary ------------------------------------------------------------------------------- */

// @find: LiveSummary, live summary, connector filters, search connectors, category filter, status filter
/** "1 live · 19 practice", with the whole sentence about practice data one press away. */
export function LiveSummary({ summary }: { summary: ConnectorSummary }) {
  const [open, setOpen] = useState(false)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const panelId = useId()
  const floatStyle = useFloating(open, triggerRef, panelRef, 'end')
  useOutsidePress(open, [triggerRef, panelRef], () => setOpen(false))

  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setOpen(false)
      triggerRef.current?.focus()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open])

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className="cx-pill cx-summary"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-describedby={open ? panelId : undefined}
        onClick={() => setOpen((current) => !current)}
      >
        <span className={`cx-dot ${summary.live > 0 ? 'cx-dot-connected cx-dot-pulse' : 'cx-dot-sandbox'}`} aria-hidden="true" />
        <span>{summary.short}</span>
        <span className="cx-pill-icon">
          <InfoGlyph />
        </span>
        <span className="visually-hidden">, about practice data</span>
      </button>
      {open &&
        createPortal(
          <div ref={panelRef} id={panelId} role="note" className="cx-popover cx-note" style={floatStyle}>
            <p className="cx-note-title">Live and practice connectors</p>
            <p className="cx-note-body">{summary.sentence}</p>
          </div>,
          document.body,
        )}
    </>
  )
}

/* ---- The toolbar -------------------------------------------------------------------------------- */

export type FilterChoice = {
  value: string
  allCount: number
  options: DropdownOption[]
  onChange: (value: string) => void
}

export type ActiveChip = { key: string; label: string; onRemove: () => void }

// @find: ConnectorToolbar, connector toolbar, connector filters, search connectors, category filter, status filter
export function ConnectorToolbar({
  query,
  onQueryChange,
  category,
  status,
  summary,
  attentionActive,
  onShowAttention,
  chips,
  shown,
  total,
  active,
  onClear,
}: {
  query: string
  onQueryChange: (query: string) => void
  category: FilterChoice
  status: FilterChoice
  summary: ConnectorSummary
  /** True while the list is filtered to the connectors that need attention. */
  attentionActive: boolean
  onShowAttention: () => void
  chips: ActiveChip[]
  shown: number
  total: number
  active: boolean
  onClear: () => void
}) {
  const searchId = useId()
  return (
    <div className="cx-toolbar" role="search" aria-label="Filter connectors">
      <div className="cx-toolbar-row">
        <div className="cx-search" data-filled={query ? true : undefined}>
          <label htmlFor={searchId} className="visually-hidden">
            Search connectors
          </label>
          <span className="cx-search-icon">
            <SearchGlyph />
          </span>
          <input
            id={searchId}
            type="search"
            className="cx-search-input"
            placeholder="Search connectors"
            value={query}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => onQueryChange(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Escape' && query) {
                event.preventDefault()
                onQueryChange('')
              }
            }}
          />
        </div>

        <div className="cx-dropdowns">
          <FilterDropdown
            label="Category"
            allLabel="All categories"
            allIcon={<CategoryGlyph category="all" />}
            allCount={category.allCount}
            options={category.options}
            value={category.value}
            onChange={category.onChange}
          />
          <FilterDropdown
            label="Status"
            allLabel="All statuses"
            allIcon={<StatusDot state="all" />}
            allCount={status.allCount}
            options={status.options}
            value={status.value}
            onChange={status.onChange}
          />
        </div>

        <div className="cx-toolbar-end">
          {summary.attention > 0 && (
            <button
              type="button"
              className="cx-pill cx-attention"
              aria-pressed={attentionActive}
              onClick={onShowAttention}
            >
              <span className="cx-dot cx-dot-attention" aria-hidden="true" />
              {formatCount(summary.attention)} {summary.attention === 1 ? 'needs' : 'need'} attention
            </button>
          )}
          <LiveSummary summary={summary} />
        </div>
      </div>

      <div className="cx-toolbar-meta">
        <p role="status" className="caption cx-results">
          Showing {formatCount(shown)} of {formatCount(total)}
        </p>
        {chips.length > 0 && (
          <ul className="cx-chips" aria-label="Active filters">
            {chips.map((chip) => (
              <li key={chip.key}>
                <button
                  type="button"
                  className="cx-chip"
                  aria-label={`Remove filter: ${chip.label}`}
                  onClick={chip.onRemove}
                >
                  <span>{chip.label}</span>
                  <CloseGlyph />
                </button>
              </li>
            ))}
          </ul>
        )}
        {active && (
          <button type="button" className="cx-clear" onClick={onClear}>
            Clear filters
          </button>
        )}
      </div>
    </div>
  )
}
