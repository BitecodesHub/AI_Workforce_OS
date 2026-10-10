// @find: tests for useAttachments, attachments hook, upload attachment, attach files, upload progress, cancel upload, remove attachment, draft attachments, composer files
// @what: Automated tests for useAttachments.
// @flow: Run with the web test runner; covers useAttachments.
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../lib/api'
import type { AttachmentView } from '../../lib/attachments'
import { useAttachments } from './useAttachments'

type Pending = { resolve: (view: AttachmentView) => void; reject: (error: unknown) => void; file: File; conversationId: string | null }
const pending: Pending[] = []
const deleted: string[] = []

vi.mock('../../lib/attachments', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/attachments')>()
  return {
    ...actual,
    uploadAttachment: vi.fn(
      (file: File, conversationId: string | null) =>
        new Promise<AttachmentView>((resolve, reject) => pending.push({ resolve, reject, file, conversationId })),
    ),
    deleteAttachment: vi.fn((id: string) => {
      deleted.push(id)
      return Promise.resolve()
    }),
  }
})

function view(id: string, name: string, extra: Partial<AttachmentView> = {}): AttachmentView {
  return {
    id,
    conversationId: null,
    name,
    mimeType: 'application/pdf',
    size: 10,
    kind: 'pdf',
    status: 'ready',
    pageCount: 1,
    problem: null,
    notice: null,
    imageReadable: true,
    savedToKnowledge: false,
    createdAt: '2026-10-06T00:00:00Z',
    ...extra,
  }
}

const pdf = (name: string) => new File(['x'], name, { type: 'application/pdf' })

beforeEach(() => {
  pending.length = 0
  deleted.length = 0
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:preview'), revokeObjectURL: vi.fn() }))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useAttachments', () => {
  it('uploads a valid file and lists an invalid one as an error', async () => {
    const { result } = renderHook(() => useAttachments(null))
    act(() => {
      result.current.add([pdf('report.pdf'), new File(['x'], 'archive.zip', { type: 'application/zip' })])
    })
    expect(result.current.items).toHaveLength(2)
    expect(result.current.items[0]).toMatchObject({ name: 'report.pdf', status: 'uploading' })
    expect(result.current.items[1]).toMatchObject({ name: 'archive.zip', status: 'error' })
    expect(result.current.items[1]!.error).toContain('not a file type Chat can read')
    expect(result.current.uploading).toBe(true)
    expect(result.current.hasErrors).toBe(true)
    expect(result.current.statusMessage).toBe('Uploading report.pdf')

    await act(async () => pending[0]!.resolve(view('srv-1', 'report.pdf')))
    expect(result.current.items[0]).toMatchObject({ status: 'ready', progress: 1 })
    expect(result.current.readyIds).toEqual(['srv-1'])
    expect(result.current.uploading).toBe(false)
    expect(result.current.statusMessage).toBe('report.pdf attached')
  })

  it('shows an unreadable file as an error and does not send it', async () => {
    const { result } = renderHook(() => useAttachments('conv-1'))
    act(() => {
      result.current.add([pdf('scan.pdf')])
    })
    expect(pending[0]!.conversationId).toBe('conv-1')
    await act(async () => pending[0]!.resolve(view('srv-2', 'scan.pdf', { status: 'unreadable', problem: 'This PDF has no text to read.' })))
    expect(result.current.items[0]).toMatchObject({ status: 'error', error: 'This PDF has no text to read.' })
    expect(result.current.readyIds).toEqual([])
  })

  it('shows the server sentence when an upload fails', async () => {
    const { result } = renderHook(() => useAttachments(null))
    act(() => {
      result.current.add([pdf('big.pdf')])
    })
    await act(async () => pending[0]!.reject(new ApiError(413, 'attachment_too_large', 'big.pdf is larger than 25 MB.', false, {})))
    expect(result.current.items[0]).toMatchObject({ status: 'error', error: 'big.pdf is larger than 25 MB.' })
    expect(result.current.statusMessage).toBe('big.pdf could not be attached: big.pdf is larger than 25 MB.')
  })

  it('attaches at most ten files and says why the rest were left out', () => {
    const { result } = renderHook(() => useAttachments(null))
    let message: string | null = null
    act(() => {
      message = result.current.add(Array.from({ length: 12 }, (_, i) => pdf(`file-${i + 1}.pdf`)))
    })
    expect(result.current.items).toHaveLength(10)
    expect(pending).toHaveLength(10)
    expect(message).toBe('You can attach up to 10 files to one message, so 2 files were not added.')
    expect(result.current.notice).toBe(message)
  })

  it('deletes an uploaded draft on remove, and forgets without deleting on clear', async () => {
    const { result } = renderHook(() => useAttachments(null))
    act(() => {
      result.current.add([pdf('a.pdf'), pdf('b.pdf')])
    })
    await act(async () => {
      pending[0]!.resolve(view('srv-a', 'a.pdf'))
      pending[1]!.resolve(view('srv-b', 'b.pdf'))
    })
    act(() => result.current.remove(result.current.items[0]!.localId))
    expect(deleted).toEqual(['srv-a'])
    expect(result.current.readyIds).toEqual(['srv-b'])
    act(() => result.current.clear())
    expect(result.current.items).toEqual([])
    expect(deleted).toEqual(['srv-a'])
  })

  it('keeps drafts when a new chat gets its id, and drops them when moving to another chat', () => {
    const { result, rerender } = renderHook(({ id }) => useAttachments(id), { initialProps: { id: null as string | null } })
    act(() => {
      result.current.add([pdf('a.pdf')])
    })
    rerender({ id: 'conv-new' })
    expect(result.current.items).toHaveLength(1)
    rerender({ id: 'conv-other' })
    expect(result.current.items).toHaveLength(0)
  })
})
