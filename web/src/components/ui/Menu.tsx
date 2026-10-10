// @find: dropdown menu, menu button, actions menu, context menu, menu items, keyboard navigation, checkbox menu, radio menu, floating position, MenuButton
// @what: Dropdown of actions with keyboard support, checkbox and radio items, and floating placement.
// @flow: Used across screens for more-actions and filter menus.
import type { KeyboardEvent as ReactKeyboardEvent, ReactNode, RefObject } from 'react'
import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

/*
 * A dropdown of actions, opened from an icon, a link or a button.
 *
 * Two kinds of item share the list: a plain command that closes the menu once chosen, and a
 * checkbox or radio that stays open so several can be set in one visit (D17's fix keeps the panel
 * from closing the moment somebody starts picking).
 */

// @find: menu entry type, menu item definition
export type MenuEntry =
  | {
      id: string
      label: string
      note?: string
      /** A leading glyph (an agent's avatar, say), drawn before the label and note. */
      icon?: ReactNode
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

/** The gap between a floating panel and its trigger, and the least room kept to the window's edge. */
const FLOAT_GAP = 4
const VIEWPORT_MARGIN = 8

// @find: position floating menu, viewport edge
/**
 * Where a floating (portalled) panel sits: below its trigger, or above it when there is no room
 * below, aligned to the trigger's start or end edge and clamped inside the window.
 */
export function floatingMenuPosition(
  trigger: { top: number; bottom: number; left: number; right: number },
  panel: { width: number; height: number },
  viewport: { width: number; height: number },
  align: 'start' | 'end',
): { top: number; left: number; placement: 'below' | 'above' } {
  const roomBelow = viewport.height - trigger.bottom - FLOAT_GAP - VIEWPORT_MARGIN
  const roomAbove = trigger.top - FLOAT_GAP - VIEWPORT_MARGIN
  const placement = panel.height <= roomBelow || roomBelow >= roomAbove ? 'below' : 'above'
  let top = placement === 'below' ? trigger.bottom + FLOAT_GAP : trigger.top - FLOAT_GAP - panel.height
  top = Math.max(VIEWPORT_MARGIN, Math.min(top, viewport.height - VIEWPORT_MARGIN - panel.height))
  let left = align === 'end' ? trigger.right - panel.width : trigger.left
  left = Math.max(VIEWPORT_MARGIN, Math.min(left, viewport.width - VIEWPORT_MARGIN - panel.width))
  return { top, left, placement }
}

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
      className={`menu-item${entry.danger ? ' menu-item-danger' : ''}${entry.icon ? ' menu-item-with-icon' : ''}`}
      disabled={entry.disabled}
      aria-checked={entry.checked === undefined ? undefined : entry.checked}
      onClick={() => onSelect(entry)}
    >
      {entry.icon ? (
        <>
          <span className="menu-item-icon">{entry.icon}</span>
          <span className="menu-item-text">
            <span className="menu-item-label">{entry.label}</span>
            {entry.note && <span className="menu-item-note">{entry.note}</span>}
          </span>
        </>
      ) : (
        <>
          <span className="menu-item-label">{entry.label}</span>
          {entry.note && <span className="menu-item-note">{entry.note}</span>}
        </>
      )}
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

/** Where a floating panel is mounted: inside an open modal dialog (the phone's conversation sheet)
    when the trigger is in one, since a modal dialog sits in the top layer above everything else in
    the document, and on the document body otherwise. */
function portalHost(trigger: HTMLElement | null): HTMLElement {
  return trigger?.closest('dialog') ?? document.body
}

// @find: dropdown menu button, more actions
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
  portal = false,
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
  /** Renders the panel in a layer of its own, fixed beside the trigger, so a scrolling or clipped
      ancestor (the chat sidebar's list) can neither hide it nor paint over it. */
  portal?: boolean
}) {
  const [open, setOpen] = useState(false)
  const wrapperRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const panelId = useId()
  const [host, setHost] = useState<HTMLElement | null>(null)

  const openMenu = () => {
    if (portal) setHost(portalHost(triggerRef.current))
    setOpen(true)
  }

  const close = (focusTrigger: boolean) => {
    setOpen(false)
    if (focusTrigger) triggerRef.current?.focus()
  }

  useEffect(() => {
    if (!openRef) return
    openRef.current = openMenu
    return () => {
      openRef.current = null
    }
    // openMenu reads only refs and the `portal` prop, which a caller does not change while open.
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
      if (
        event.target instanceof Node &&
        (wrapperRef.current?.contains(event.target) || panelRef.current?.contains(event.target))
      )
        return
      setOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    return () => document.removeEventListener('pointerdown', onPointerDown)
  }, [open])

  // A floating panel is placed after it renders, once its own size is known, and follows its
  // trigger while anything around it scrolls or the window is resized.
  useLayoutEffect(() => {
    if (!open || !portal) return
    const place = () => {
      const trigger = triggerRef.current
      const panel = panelRef.current
      if (!trigger || !panel) return
      const rect = trigger.getBoundingClientRect()
      const position = floatingMenuPosition(
        rect,
        { width: panel.offsetWidth, height: panel.offsetHeight },
        { width: window.innerWidth, height: window.innerHeight },
        align,
      )
      panel.style.top = `${position.top}px`
      panel.style.left = `${position.left}px`
      panel.style.transformOrigin = `${position.placement === 'below' ? 'top' : 'bottom'} ${align === 'end' ? 'right' : 'left'}`
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [open, portal, align])

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
      // A floating panel sits at the end of the document, so Tab from it would land nowhere near
      // the trigger: close and hand focus back to the trigger instead.
      if (portal) {
        event.preventDefault()
        close(true)
      } else close(false)
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
    onClick: () => (open ? setOpen(false) : openMenu()),
  }

  const panel = (
    <div
      id={panelId}
      className={`menu-panel${portal ? ' menu-panel-floating' : ''}`}
      role="menu"
      ref={panelRef}
      style={
        portal
          ? { position: 'fixed', right: 'auto' }
          : align === 'start'
            ? { left: 0, right: 'auto' }
            : undefined
      }
      aria-label={label}
      onKeyDown={onPanelKeyDown}
    >
      <MenuItems items={items} onSelect={selectEntry} />
    </div>
  )

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
      {open && (portal ? createPortal(panel, host ?? document.body) : panel)}
    </div>
  )
}
