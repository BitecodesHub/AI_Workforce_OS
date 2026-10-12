// @find: sheet, side panel, drawer, slide over, detail panel, goal sheet, run sheet, modal non-modal, Sheet
// @what: Edge-anchored panel built on native dialog for records kept open beside a list.
// @flow: Used by Orchestrator, Tasks and the Agent desk (AgentDesk, which guards closing while a person has control); sibling of Dialog in ui/index.
import type { ReactNode } from 'react'
import { useCallback, useEffect, useId, useRef } from 'react'
import { Eyebrow, IconButton } from './index'

/*
 * A panel anchored to one edge of the screen, for a record that stays open beside a list (a goal,
 * a run) rather than replacing the whole page. Built on the native <dialog>, the same as Dialog,
 * so Escape, focus and the backdrop come from the browser rather than a reimplementation of them.
 *
 * The difference from Dialog is that a Sheet can also be non-modal: wide enough screens keep the
 * board underneath reachable while the sheet is open, and `.show()` is what makes that so - a
 * modal dialog always owns the whole page until it closes.
 */

// @find: side sheet panel, drawer
/**
 * Edge-anchored panel for a record kept open beside a list (a goal, a run), built on the native dialog element.
 * Props: open and onClose (controlled), side, modal, eyebrow and title (the title is the labelled heading and takes
 * focus on open unless an element marked data-autofocus exists), width (sm, md or full), children, footer and
 * returnFocusTo (where focus goes on close; default the element focused before opening).
 * Modal sheets use showModal (native Escape and backdrop). Non-modal ones use show, so a wide screen keeps the
 * page behind reachable, and then Escape is handled by a document key listener because only modal dialogs get
 * it natively. A backdrop click closes only a modal sheet, and only when the press also started on the backdrop
 * (so a drag that ends there does not close it). A close the parent asked for through open=false is not reported
 * back to it again. beforeClose, when given, is asked first on every close the person starts (Escape, the Close
 * button, the backdrop); answering false keeps the sheet open, so the caller can ask something first. Used by
 * the Orchestrator sheets, Tasks and the Agent desk. A dialog opened inside the sheet closing never closes the sheet.
 */
export function Sheet({
  open,
  onClose,
  side = 'right',
  modal = true,
  eyebrow,
  title,
  width = 'md',
  children,
  footer,
  returnFocusTo,
  beforeClose,
}: {
  open: boolean
  onClose: () => void
  side?: 'left' | 'right'
  modal?: boolean
  eyebrow: string
  title: string
  width?: 'sm' | 'md' | 'full'
  children?: ReactNode
  footer?: ReactNode
  /** Where focus goes on close. Defaults to whatever had focus before the sheet opened. */
  returnFocusTo?: HTMLElement | null
  /** Asked before a close the person starts; false keeps the sheet open. */
  beforeClose?: (() => boolean) | undefined
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  // Whether the parent still wants the sheet open, so a close it asked for (setting `open` to
  // false) is not reported back to it a second time as though the person had dismissed it.
  const openRef = useRef(open)
  const previousFocus = useRef<HTMLElement | null>(null)
  const pressStartedOnBackdrop = useRef(false)
  // The newest guard, read by the listeners below without adding them again on every render.
  const guard = useRef(beforeClose)
  useEffect(() => {
    guard.current = beforeClose
  })
  /** A close the person started: reported unless the guard keeps the sheet open. */
  const requestClose = useCallback(() => {
    if (guard.current && !guard.current()) return
    onClose()
  }, [onClose])

  useEffect(() => {
    openRef.current = open
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) {
      previousFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
      if (modal) dialog.showModal()
      else dialog.show()
      const marked = dialog.querySelector<HTMLElement>('[data-autofocus]')
      const target = marked && !marked.matches(':disabled') ? marked : document.getElementById(titleId)
      target?.focus()
    }
    if (!open && dialog.open) dialog.close()
  }, [open, modal, titleId])

  // A non-modal dialog (shown with `.show()`) gets no native Escape handling of its own - only a
  // modal one does. Mirrors the backdrop click below: it reports the close to the caller and lets
  // the `open` round trip close the element, rather than calling `.close()` here directly.
  useEffect(() => {
    if (modal || !open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') requestClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [modal, open, requestClose])

  return (
    <dialog
      ref={ref}
      className="sheet"
      data-side={side}
      data-width={width}
      aria-labelledby={titleId}
      onCancel={(event) => {
        // Escape on a modal sheet: the browser closes it unless the guard says to keep it open.
        // A dialog opened inside the sheet handles its own Escape.
        if (event.target !== event.currentTarget) return
        if (guard.current && !guard.current()) event.preventDefault()
      }}
      onKeyDown={(event) => {
        // Chrome closes a modal dialog on a second Escape even when the cancel event was refused;
        // refusing the key itself stops that. Only for the sheet's own content, never for a
        // dialog opened inside it, and only when there is a guard to ask.
        if (event.key !== 'Escape' || !modal || !guard.current) return
        if ((event.target as HTMLElement).closest('dialog') !== event.currentTarget) return
        if (!guard.current()) event.preventDefault()
      }}
      onClose={(event) => {
        // React passes a dialog's close on to every dialog around it; a confirm opened inside the
        // sheet closing is not the sheet closing.
        if (event.target !== event.currentTarget) return
        const wasOpen = openRef.current
        const focusTarget = returnFocusTo ?? previousFocus.current
        if (wasOpen) onClose()
        focusTarget?.focus()
      }}
      onPointerDown={(event) => {
        pressStartedOnBackdrop.current = event.target === event.currentTarget
      }}
      onClick={(event) => {
        const onBackdrop = pressStartedOnBackdrop.current && event.target === event.currentTarget
        pressStartedOnBackdrop.current = false
        if (onBackdrop && modal) requestClose()
      }}
    >
      <div className="sheet-body">
        <div className="sheet-header">
          <div className="sheet-heading">
            <Eyebrow>{eyebrow}</Eyebrow>
            <h2 id={titleId} className="sheet-title" tabIndex={-1}>
              {title}
            </h2>
          </div>
          <IconButton label="Close" onClick={() => requestClose()}>
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
              <path d="M2 2l10 10M12 2L2 12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </IconButton>
        </div>
        {children !== undefined && children !== null && <div className="sheet-content">{children}</div>}
        {footer && <div className="sheet-footer">{footer}</div>}
      </div>
    </dialog>
  )
}
