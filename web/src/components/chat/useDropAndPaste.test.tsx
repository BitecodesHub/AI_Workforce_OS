import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { filesFromPaste, useFileDrop } from './useDropAndPaste'

function Panel({ onFiles, disabled = false }: { onFiles: (files: File[]) => void; disabled?: boolean }) {
  const { dragging, bind } = useFileDrop({ onFiles, disabled })
  return (
    <div data-testid="panel" {...bind}>
      {dragging ? 'dragging' : 'idle'}
    </div>
  )
}

const pdf = new File(['x'], 'report.pdf', { type: 'application/pdf' })

describe('useFileDrop', () => {
  it('shows the overlay state while files are dragged over and hands dropped files on', () => {
    const onFiles = vi.fn()
    render(<Panel onFiles={onFiles} />)
    const panel = screen.getByTestId('panel')
    fireEvent.dragEnter(panel, { dataTransfer: { types: ['Files'], files: [pdf] } })
    expect(panel).toHaveTextContent('dragging')
    fireEvent.drop(panel, { dataTransfer: { types: ['Files'], files: [pdf] } })
    expect(panel).toHaveTextContent('idle')
    expect(onFiles).toHaveBeenCalledWith([pdf])
  })

  it('ignores a drag of text', () => {
    const onFiles = vi.fn()
    render(<Panel onFiles={onFiles} />)
    const panel = screen.getByTestId('panel')
    fireEvent.dragEnter(panel, { dataTransfer: { types: ['text/plain'], files: [] } })
    expect(panel).toHaveTextContent('idle')
    fireEvent.drop(panel, { dataTransfer: { types: ['text/plain'], files: [] } })
    expect(onFiles).not.toHaveBeenCalled()
  })

  it('does nothing when disabled', () => {
    const onFiles = vi.fn()
    render(<Panel onFiles={onFiles} disabled />)
    const panel = screen.getByTestId('panel')
    fireEvent.dragEnter(panel, { dataTransfer: { types: ['Files'], files: [pdf] } })
    expect(panel).toHaveTextContent('idle')
    fireEvent.drop(panel, { dataTransfer: { types: ['Files'], files: [pdf] } })
    expect(onFiles).not.toHaveBeenCalled()
  })
})

describe('filesFromPaste', () => {
  it('names a pasted screenshot', () => {
    const shot = new File(['png'], '', { type: 'image/png' })
    const event = { clipboardData: { items: [{ kind: 'file', getAsFile: () => shot }], files: [] } } as unknown as ClipboardEvent
    const files = filesFromPaste(event)
    expect(files).toHaveLength(1)
    expect(files[0]!.name).toMatch(/^pasted-image-\d+\.png$/)
    expect(files[0]!.type).toBe('image/png')
  })

  it('keeps a named file as it is and returns nothing for text', () => {
    const named = { clipboardData: { items: [{ kind: 'file', getAsFile: () => pdf }], files: [] } } as unknown as ClipboardEvent
    expect(filesFromPaste(named)).toEqual([pdf])
    const text = { clipboardData: { items: [{ kind: 'string', getAsFile: () => null }], files: [] } } as unknown as ClipboardEvent
    expect(filesFromPaste(text)).toEqual([])
  })
})
