import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SentAttachment } from '../../lib/attachments'
import { clearSession, saveSession } from '../../lib/session'
import { AttachmentCards } from './AttachmentCards'

const ATTACHMENTS: SentAttachment[] = [
  { id: 'a1', name: 'report.pdf', mimeType: 'application/pdf', size: 2048, kind: 'pdf', pageCount: 12 },
  { id: 'a2', name: 'photo.png', mimeType: 'image/png', size: 4096, kind: 'image', pageCount: null },
]

function signIn(permissions: string[]) {
  saveSession('token-1', { userId: 'u1', workspaceId: 'w1', permissions, displayName: 'Maya', email: 'maya@demo.test', role: 'manager' })
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('png-bytes', { status: 200 }))))
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:thumb'), revokeObjectURL: vi.fn() }))
  signIn([])
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
})

describe('AttachmentCards', () => {
  it('shows each file with its size and pages', async () => {
    render(<AttachmentCards attachments={ATTACHMENTS} />)
    expect(screen.getByRole('list', { name: 'Attached files' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open report.pdf' })).toHaveTextContent('2 KB · 12 pages')
    expect(screen.queryByRole('button', { name: 'Save to knowledge' })).toBeNull()
    await waitFor(() => expect(document.querySelector('img.chat-attach-card-thumb')).toHaveAttribute('src', 'blob:thumb'))
  })

  it('opens an image in a lightbox that Escape closes, returning focus', async () => {
    render(<AttachmentCards attachments={ATTACHMENTS} />)
    await waitFor(() => expect(document.querySelector('img.chat-attach-card-thumb')).not.toBeNull())
    const open = screen.getByRole('button', { name: 'Open photo.png' })
    open.focus()
    fireEvent.click(open)
    const dialog = screen.getByRole('dialog', { name: 'photo.png' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Download' })).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(open).toHaveFocus()
  })

  it('offers to save to knowledge when allowed, and says when it is saved', async () => {
    signIn(['knowledge:source_manage'])
    const fetchMock = vi.fn((path: string) =>
      Promise.resolve(
        path.endsWith('/knowledge')
          ? new Response(JSON.stringify({ documentId: 'd1', sourceName: 'Chat files' }), { status: 200 })
          : new Response('png-bytes', { status: 200 }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    render(<AttachmentCards attachments={[ATTACHMENTS[0]!]} />)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save to knowledge' }))
    })
    expect(await screen.findByText('Saved to knowledge')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/conversations/attachments/a1/knowledge', expect.objectContaining({ method: 'POST' }))
  })

  it('renders nothing without attachments', () => {
    const { container } = render(<AttachmentCards attachments={[]} />)
    expect(container).toBeEmptyDOMElement()
  })
})
