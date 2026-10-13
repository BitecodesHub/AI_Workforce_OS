// @find: tests for sheet, side panel close guard, beforeClose, nested dialog close does not close sheet, Escape held back
// @what: Component tests for Sheet: the Close button and Escape ask beforeClose first, and a dialog opened inside the sheet closing never closes the sheet.
import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmDialog } from './index'
import { Sheet } from './Sheet'

// jsdom has the <dialog> element but not its modal methods.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

/** A sheet with a confirm inside it, as the Agent desk has. */
function SheetWithConfirm({ onClose, beforeClose }: { onClose: () => void; beforeClose?: () => boolean }) {
  const [asking, setAsking] = useState(false)
  return (
    <Sheet open onClose={onClose} beforeClose={beforeClose} eyebrow="Desk" title="A sheet">
      <button type="button" onClick={() => setAsking(true)}>
        Ask
      </button>
      <ConfirmDialog
        open={asking}
        onClose={() => setAsking(false)}
        onConfirm={() => setAsking(false)}
        eyebrow="Question"
        title="Are you sure?"
        confirmLabel="Yes"
        cancelLabel="No"
        tone="primary"
      />
    </Sheet>
  )
}

describe('Sheet', () => {
  it('asks beforeClose on Close and on Escape, and stays open when it says no', () => {
    const onClose = vi.fn()
    const beforeClose = vi.fn(() => false)
    render(<SheetWithConfirm onClose={onClose} beforeClose={beforeClose} />)

    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    const escape = new Event('cancel', { cancelable: true })
    ;(document.querySelector('dialog.sheet') as HTMLDialogElement).dispatchEvent(escape)

    expect(beforeClose).toHaveBeenCalledTimes(2)
    expect(escape.defaultPrevented).toBe(true)
    expect(onClose).not.toHaveBeenCalled()
  })

  it('closes when beforeClose says yes, or when there is none', () => {
    const onClose = vi.fn()
    render(<SheetWithConfirm onClose={onClose} beforeClose={() => true} />)

    fireEvent.click(screen.getByRole('button', { name: 'Close' }))

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('is not closed by a dialog inside it closing', async () => {
    const onClose = vi.fn()
    render(<SheetWithConfirm onClose={onClose} />)

    fireEvent.click(screen.getByRole('button', { name: 'Ask' }))
    expect(await screen.findByRole('dialog', { name: 'Are you sure?' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'No' }))

    expect(screen.queryByRole('dialog', { name: 'Are you sure?' })).not.toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog', { name: 'A sheet' })).toBeInTheDocument()
  })

  // Seen live in the Team room: closing an agent's sheet with Escape or Close left focus on the page
  // body, because the page unmounts the sheet instead of closing it.
  it('gives focus back to what opened it when its parent removes it while open', async () => {
    function Opener() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>
            Open the sheet
          </button>
          {open && (
            <Sheet open onClose={() => setOpen(false)} eyebrow="Agent" title="What they are doing">
              <p>Busy</p>
            </Sheet>
          )}
        </>
      )
    }
    render(<Opener />)
    const opener = screen.getByRole('button', { name: 'Open the sheet' })
    opener.focus()
    fireEvent.click(opener)
    expect(screen.getByRole('heading', { name: 'What they are doing' })).toHaveFocus()

    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    await Promise.resolve()

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
  })

  it('leaves focus alone when the person has already moved it elsewhere', async () => {
    function Elsewhere() {
      const [open, setOpen] = useState(true)
      return (
        <>
          <button type="button">Somewhere else</button>
          {open && (
            <Sheet open onClose={() => setOpen(false)} eyebrow="Agent" title="A sheet">
              <button
                type="button"
                onClick={() => {
                  screen.getByRole('button', { name: 'Somewhere else' }).focus()
                  setOpen(false)
                }}
              >
                Go elsewhere
              </button>
            </Sheet>
          )}
        </>
      )
    }
    const before = document.createElement('button')
    document.body.append(before)
    before.focus()
    render(<Elsewhere />)
    fireEvent.click(screen.getByRole('button', { name: 'Go elsewhere' }))
    await Promise.resolve()

    expect(screen.getByRole('button', { name: 'Somewhere else' })).toHaveFocus()
    before.remove()
  })
})
