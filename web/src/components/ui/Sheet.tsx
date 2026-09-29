import type { ReactNode } from 'react'
import { useEffect, useId, useRef } from 'react'
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
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  // Whether the parent still wants the sheet open, so a close it asked for (setting `open` to
  // false) is not reported back to it a second time as though the person had dismissed it.
  const openRef = useRef(open)
  const previousFocus = useRef<HTMLElement | null>(null)
  const pressStartedOnBackdrop = useRef(false)

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
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [modal, open, onClose])

  return (
    <dialog
      ref={ref}
      className="sheet"
      data-side={side}
      data-width={width}
      aria-labelledby={titleId}
      onClose={() => {
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
        if (onBackdrop && modal) onClose()
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
          <IconButton label="Close" onClick={onClose}>
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
