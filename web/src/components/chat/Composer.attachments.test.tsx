import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { createRef } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AttachmentView } from '../../lib/attachments'
import type { Agent } from '../../lib/queries'
import { Composer } from './Composer'

/*
 * Files in the composer: the paperclip, a drop on the chat panel and a paste each add chips; Send
 * waits for uploads, then works with files alone; a file Chat cannot read shows its reason and is
 * never sent; and answering a question turns attaching off.
 */

type Pending = { resolve: (view: AttachmentView) => void; reject: (error: unknown) => void; file: File }
const pending: Pending[] = []

vi.mock('../../lib/attachments', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/attachments')>()
  return {
    ...actual,
    uploadAttachment: vi.fn(
      (file: File) => new Promise<AttachmentView>((resolve, reject) => pending.push({ resolve, reject, file })),
    ),
    deleteAttachment: vi.fn(() => Promise.resolve()),
  }
})

const AGENTS: Agent[] = [{ id: 'a1', key: 'hr', name: 'HR', category: 'people', status: 'active', revision: 1 }] as Agent[]

function view(id: string, name: string, extra: Partial<AttachmentView> = {}): AttachmentView {
  return {
    id,
    conversationId: null,
    name,
    mimeType: 'application/pdf',
    size: 2048,
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

type ReplyTarget = { questionId: string; agentName: string; agentId: string | null; auto: boolean }

function renderComposer(
  onSend: (text: string, agentIds: string[], attachmentIds: string[]) => Promise<boolean>,
  replyTo: ReplyTarget | null = null,
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const zone = document.createElement('main')
  document.body.appendChild(zone)
  const zoneRef = { current: zone }
  const utils = render(
    <QueryClientProvider client={client}>
      <Composer
        userId="user-1"
        conversationId={null}
        agents={AGENTS}
        onSend={onSend}
        sending={false}
        replyTo={replyTo}
        onClearReply={() => {}}
        lastUserText={null}
        inputRef={createRef()}
        dropZoneRef={zoneRef}
      />
    </QueryClientProvider>,
  )
  return { ...utils, zone }
}

const pdf = (name: string) => new File(['%PDF'], name, { type: 'application/pdf' })
const sendButton = () => screen.getByRole('button', { name: 'Send' })

beforeEach(() => {
  pending.length = 0
  vi.stubGlobal('fetch', () => Promise.reject(new Error('No network in tests')))
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:preview'), revokeObjectURL: vi.fn() }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

describe('Composer attachments', () => {
  it('attaches with the paperclip, waits for the upload, then sends files alone', async () => {
    const onSend = vi.fn(async () => true)
    renderComposer(onSend)

    expect(screen.getByRole('button', { name: 'Attach files' })).toBeTruthy()
    fireEvent.change(screen.getByTestId('chat-attach-input'), { target: { files: [pdf('q3.pdf')] } })

    expect(screen.getByRole('list', { name: 'Attached files' }).textContent).toContain('q3.pdf')
    expect(screen.getByRole('progressbar', { name: 'Uploading q3.pdf' })).toBeTruthy()
    expect((sendButton() as HTMLButtonElement).disabled).toBe(true)

    await act(async () => pending[0]!.resolve(view('att-1', 'q3.pdf')))
    await waitFor(() => expect((sendButton() as HTMLButtonElement).disabled).toBe(false))

    await act(async () => {
      fireEvent.click(sendButton())
    })
    expect(onSend).toHaveBeenCalledWith('', [], ['att-1'])
    expect(screen.queryByRole('list', { name: 'Attached files' })).toBeNull()
  })

  it('takes files dropped anywhere on the chat panel, and shows the overlay while dragging', async () => {
    const { zone } = renderComposer(vi.fn(async () => true))
    const dataTransfer = { types: ['Files'], files: [pdf('dropped.pdf')], dropEffect: 'none' }

    act(() => {
      zone.dispatchEvent(Object.assign(new Event('dragenter', { bubbles: true, cancelable: true }), { dataTransfer }))
    })
    expect(zone.textContent).toContain('Drop files to attach them')

    act(() => {
      zone.dispatchEvent(Object.assign(new Event('drop', { bubbles: true, cancelable: true }), { dataTransfer }))
    })
    expect(zone.textContent).not.toContain('Drop files to attach them')
    expect(pending.map((p) => p.file.name)).toEqual(['dropped.pdf'])
  })

  it('attaches a pasted screenshot under a readable name, and leaves pasted text alone', () => {
    renderComposer(vi.fn(async () => true))
    const field = screen.getByRole('textbox', { name: 'Message the workforce' })
    const shot = new File(['png'], 'image.png', { type: 'image/png' })

    fireEvent.paste(field, { clipboardData: { items: [{ kind: 'file', getAsFile: () => shot }], files: [shot] } })
    expect(pending).toHaveLength(1)
    expect(pending[0]!.file.name).toMatch(/^pasted-image-\d+\.png$/)

    fireEvent.paste(field, { clipboardData: { items: [{ kind: 'string', getAsFile: () => null }], files: [] } })
    expect(pending).toHaveLength(1)
  })

  it('shows why a file was refused and never sends it', async () => {
    const onSend = vi.fn(async () => true)
    renderComposer(onSend)

    fireEvent.change(screen.getByTestId('chat-attach-input'), {
      target: { files: [new File(['x'], 'tool.exe', { type: 'application/x-msdownload' })] },
    })
    expect(screen.getByRole('list', { name: 'Attached files' }).textContent).toContain('is not a file type Chat can read')
    expect(pending).toHaveLength(0)
    expect((sendButton() as HTMLButtonElement).disabled).toBe(true)

    fireEvent.change(screen.getByTestId('chat-attach-input'), { target: { files: [pdf('scan.pdf')] } })
    await act(async () =>
      pending[0]!.resolve(view('att-2', 'scan.pdf', { status: 'unreadable', problem: 'The PDF has no text layer.' })),
    )
    await waitFor(() =>
      expect(screen.getByRole('list', { name: 'Attached files' }).textContent).toContain('The PDF has no text layer.'),
    )
    expect((sendButton() as HTMLButtonElement).disabled).toBe(true)
  })

  it('turns attaching off while the box answers a question', () => {
    renderComposer(vi.fn(async () => true), { questionId: 'q1', agentName: 'HR', agentId: 'a1', auto: false })
    expect((screen.getByRole('button', { name: 'Attach files' }) as HTMLButtonElement).disabled).toBe(true)
  })
})
