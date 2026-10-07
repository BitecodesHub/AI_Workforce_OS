import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AttachmentChips, AttachmentDropOverlay } from './AttachmentChips'
import type { DraftAttachment } from './useAttachments'

const file = new File(['x'], 'report.pdf', { type: 'application/pdf' })
const ITEMS: DraftAttachment[] = [
  { localId: 'l1', file, name: 'report.pdf', size: 2048, kind: 'pdf', status: 'uploading', progress: 0.4 },
  { localId: 'l2', file, name: 'notes.txt', size: 900, kind: 'text', status: 'ready', progress: 1 },
  { localId: 'l3', file, name: 'scan.pdf', size: 10, kind: 'pdf', status: 'error', progress: 1, error: 'This PDF has no text to read.' },
]

describe('AttachmentChips', () => {
  it('lists each file with its size, upload progress and any problem', () => {
    render(<AttachmentChips items={ITEMS} onRemove={() => {}} statusMessage="Uploading report.pdf" />)
    const list = screen.getByRole('list', { name: 'Attached files' })
    expect(list.querySelectorAll('li')).toHaveLength(3)
    expect(screen.getByTitle('report.pdf')).toHaveTextContent('report.pdf')
    expect(screen.getByText('900 bytes')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Uploading report.pdf' })).toHaveAttribute('aria-valuenow', '40')
    expect(screen.getByText('This PDF has no text to read.')).toBeInTheDocument()
    expect(screen.getByText('Uploading report.pdf')).toHaveAttribute('aria-live', 'polite')
  })

  it('removes a file from an accessible button', () => {
    const onRemove = vi.fn()
    render(<AttachmentChips items={ITEMS} onRemove={onRemove} statusMessage="" />)
    fireEvent.click(screen.getByRole('button', { name: 'Remove notes.txt' }))
    expect(onRemove).toHaveBeenCalledWith('l2')
  })

  it('keeps the live region even with no files', () => {
    const { container } = render(<AttachmentChips items={[]} onRemove={() => {}} statusMessage="report.pdf removed" notice="Too many" />)
    expect(screen.queryByRole('list')).toBeNull()
    expect(container.querySelector('[aria-live="polite"]')).toHaveTextContent('report.pdf removed')
    expect(screen.getByText('Too many')).toBeInTheDocument()
  })

  it('shows the drop overlay only while dragging', () => {
    const { rerender } = render(<AttachmentDropOverlay visible={false} />)
    expect(screen.queryByText('Drop files to attach them')).toBeNull()
    rerender(<AttachmentDropOverlay visible />)
    expect(screen.getByText('Drop files to attach them')).toBeInTheDocument()
  })
})
