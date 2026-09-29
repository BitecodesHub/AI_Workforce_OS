import type { KeyboardEvent as ReactKeyboardEvent, ReactNode, RefObject } from 'react'
import { useEffect, useId, useRef, useState } from 'react'

/*
 * A dropdown of actions, opened from an icon, a link or a button.
 *
 * Two kinds of item share the list: a plain command that closes the menu once chosen, and a
 * checkbox or radio that stays open so several can be set in one visit (D17's fix keeps the panel
 * from closing the moment somebody starts picking).
 */

export type MenuEntry =
  | {
      id: string
      label: string
      note?: string
      danger?: boolean
      disabled?: boolean
      /** Present (even `false`) turns the item into a menuitemcheckbox, or a menuitemradio when `group` is also set. */
      checked?: boolean
      /** The id of the `groupLabel` entry this radio belongs to. Only meaningful with `checked`. */
      group?: string
      onSelect: () => void
    }
  | { id: string; separator: true }
  | { id: string; groupLabel: string }

type ActionEntry = Extract<MenuEntry, { onSelect: () => void }>

const isActionEntry = (entry: MenuEntry): entry is ActionEntry => 'onSelect' in entry

const MENU_ITEM_SELECTOR = '[role^="menuitem"]:not(:disabled)'

function CaretIcon() {
  return (
    <svg width="10" height="10" viewBox="0 0 10 10" fill="none" aria-hidden="true">
      <path d="M2.5 4l2.5 2.5L7.5 4" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function MenuItemRow({ entry, onSelect }: { entry: ActionEntry; onSelect: (entry: ActionEntry) => void }) {
  const role = entry.checked === undefined ? 'menuitem' : entry.group ? 'menuitemradio' : 'menuitemcheckbox'
  return (
    <button
      type="button"
      role={role}
      className={`menu-item${entry.danger ? ' menu-item-danger' : ''}`}
      disabled={entry.disabled}
      aria-checked={entry.checked === undefined ? undefined : entry.checked}
      onClick={() => onSelect(entry)}
    >
      <span className="menu-item-label">{entry.label}</span>
      {entry.note && <span className="menu-item-note">{entry.note}</span>}
    </button>
  )
}

/** Renders the list, folding a `groupLabel` entry and the checked entries naming it as their
    `group` into one `role="group"` region, the way a radio group needs (B4.3). */
function MenuItems({ items, onSelect }: { items: MenuEntry[]; onSelect: (entry: ActionEntry) => void }) {
  const nodes: ReactNode[] = []
  let index = 0
  while (index < items.length) {
    const entry = items[index]!
    if ('separator' in entry) {
      nodes.push(<div key={entry.id} className="menu-separator" role="separator" />)
      index += 1
      continue
    }
    if ('groupLabel' in entry) {
      const groupId = entry.id
      const members: ActionEntry[] = []
      let cursor = index + 1
      while (cursor < items.length) {
        const candidate = items[cursor]!
        if (isActionEntry(candidate) && candidate.checked !== undefined && candidate.group === groupId) {
          members.push(candidate)
          cursor += 1
        } else break
      }
      nodes.push(
        <div key={entry.id} className="menu-group" role="group" aria-label={entry.groupLabel}>
          <p className="menu-heading">{entry.groupLabel}</p>
          {members.map((member) => (
            <MenuItemRow key={member.id} entry={member} onSelect={onSelect} />
          ))}
        </div>,
      )
      index = cursor
      continue
    }
    nodes.push(<MenuItemRow key={entry.id} entry={entry} onSelect={onSelect} />)
    index += 1
  }
  return <>{nodes}</>
}

export function MenuButton({
  label,
  items,
  trigger = 'button',
  text,
  icon,
  align = 'start',
  className,
  triggerTabIndex,
  openRef,
}: {
  label: string
  items: MenuEntry[]
  trigger?: 'icon' | 'link' | 'button'
  /** Visible text for the 'link' and 'button' triggers. Falls back to `label`. */
  text?: string
  /** The glyph for the 'icon' trigger. */
  icon?: ReactNode
  align?: 'start' | 'end'
  className?: string
  /** Keeps the trigger out of the Tab order (0 to include it, -1 to skip it): a row's own menu,
      opened instead from a keyboard shortcut such as Shift+F10 (B1.3). */
  triggerTabIndex?: 0 | -1
  /** Lets a caller open this menu without a click, for that same shortcut. */
  openRef?: RefObject<(() => void) | null>
}) {
  const [open, setOpen] = useState(false)
  const wrapperRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const panelId = useId()

  const close = (focusTrigger: boolean) => {
    setOpen(false)
    if (focusTrigger) triggerRef.current?.focus()
  }

  useEffect(() => {
    if (!openRef) return
    openRef.current = () => setOpen(true)
    return () => {
      openRef.current = null
    }
  }, [openRef])

  useEffect(() => {
    if (!open) return
    panelRef.current?.querySelector<HTMLButtonElement>(MENU_ITEM_SELECTOR)?.focus()
  }, [open])

  useEffect(() => {
    if (!open) return
    // A press outside the menu closes it, including one that starts a drag or a text selection
    // elsewhere on the page - `pointerdown` fires before any of that, `click` fires after (D17).
    // Closes without moving focus to the trigger: the press already moved it somewhere else.
    const onPointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && wrapperRef.current?.contains(event.target)) return
      setOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    return () => document.removeEventListener('pointerdown', onPointerDown)
  }, [open])

  const moveFocus = (from: HTMLElement, step: 1 | -1 | 'home' | 'end') => {
    const enabled = Array.from(panelRef.current?.querySelectorAll<HTMLButtonElement>(MENU_ITEM_SELECTOR) ?? [])
    if (enabled.length === 0) return
    if (step === 'home') {
      enabled[0]?.focus()
      return
    }
    if (step === 'end') {
      enabled[enabled.length - 1]?.focus()
      return
    }
    const current = enabled.indexOf(from as HTMLButtonElement)
    const next = current === -1 ? 0 : (current + step + enabled.length) % enabled.length
    enabled[next]?.focus()
  }

  const onPanelKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      close(true)
      return
    }
    if (event.key === 'Tab') {
      close(false)
      return
    }
    if (!(event.target instanceof HTMLElement)) return
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      moveFocus(event.target, 1)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      moveFocus(event.target, -1)
    } else if (event.key === 'Home') {
      event.preventDefault()
      moveFocus(event.target, 'home')
    } else if (event.key === 'End') {
      event.preventDefault()
      moveFocus(event.target, 'end')
    }
  }

  const selectEntry = (entry: ActionEntry) => {
    entry.onSelect()
    // A checkbox or radio item stays open, so several can be set in one visit.
    if (entry.checked === undefined) close(true)
  }

  const triggerProps = {
    ref: triggerRef,
    type: 'button' as const,
    'aria-haspopup': 'menu' as const,
    'aria-expanded': open,
    'aria-controls': panelId,
    tabIndex: triggerTabIndex,
    onClick: () => setOpen((current) => !current),
  }

  return (
    <div className={`more-menu ${className ?? ''}`.trim()} ref={wrapperRef}>
      {trigger === 'icon' ? (
        <button {...triggerProps} className="icon-button" aria-label={label} title={label}>
          {icon}
        </button>
      ) : trigger === 'link' ? (
        <button {...triggerProps} className="menu-trigger-link">
          {text ?? label}
        </button>
      ) : (
        <button {...triggerProps} className="button button-outline">
          {text ?? label}
          <CaretIcon />
        </button>
      )}
      {open && (
        <div
          id={panelId}
          className="menu-panel"
          role="menu"
          ref={panelRef}
          style={align === 'start' ? { left: 0, right: 'auto' } : undefined}
          aria-label={label}
          onKeyDown={onPanelKeyDown}
        >
          <MenuItems items={items} onSelect={selectEntry} />
        </div>
      )}
    </div>
  )
}
