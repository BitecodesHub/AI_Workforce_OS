// @find: tests for attachments, checkFile, kindOf, upload attachment, formatBytes, save to knowledge
// @what: Unit tests for chat attachment validation and upload helpers.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './api'
import {
  ACCEPT,
  MAX_ATTACHMENT_BYTES,
  checkFile,
  fetchAttachmentBlob,
  formatBytes,
  kindOf,
  sentAttachmentsOf,
  uploadAttachment,
} from './attachments'
import { clearSession, saveSession } from './session'

class FakeXhr {
  static instances: FakeXhr[] = []
  upload: { onprogress: ((event: { lengthComputable: boolean; loaded: number; total: number }) => void) | null } = { onprogress: null }
  status = 0
  responseText = ''
  withCredentials = false
  method = ''
  url = ''
  headers: Record<string, string> = {}
  body: unknown = null
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null
  open(method: string, url: string) {
    this.method = method
    this.url = url
  }
  setRequestHeader(name: string, value: string) {
    this.headers[name] = value
  }
  getResponseHeader() {
    return null
  }
  send(body: unknown) {
    this.body = body
    FakeXhr.instances.push(this)
  }
  abort() {
    this.onabort?.()
  }
  respond(status: number, body: unknown) {
    this.status = status
    this.responseText = typeof body === 'string' ? body : JSON.stringify(body)
    this.onload?.()
  }
}

const VIEW = {
  id: 'att-1',
  conversationId: null,
  name: 'report.pdf',
  mimeType: 'application/pdf',
  size: 2048,
  kind: 'pdf',
  status: 'ready',
  pageCount: 3,
  problem: null,
  notice: null,
  imageReadable: true,
  savedToKnowledge: false,
  createdAt: '2026-10-06T00:00:00Z',
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

beforeEach(() => {
  FakeXhr.instances = []
  vi.stubGlobal('XMLHttpRequest', FakeXhr)
  saveSession('token-1', { userId: 'u1', workspaceId: 'w1', permissions: [], displayName: 'Maya', email: 'maya@demo.test', role: 'manager' })
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
})

describe('checkFile', () => {
  it('accepts the documents and images Chat reads', () => {
    expect(checkFile({ name: 'report.pdf', type: 'application/pdf', size: 10 })).toBeNull()
    expect(checkFile({ name: 'Photo.HEIC', type: '', size: 10 })).toBeNull()
    expect(checkFile({ name: 'notes', type: 'text/plain', size: 10 })).toBeNull()
  })

  it('names a file type Chat cannot read', () => {
    expect(checkFile({ name: 'report.zip', type: 'application/zip', size: 10 })).toBe(
      'report.zip is not a file type Chat can read. Attach a PDF, Word, PowerPoint, Excel or CSV file, text, or an image.',
    )
  })

  it('names a file over 25 MB', () => {
    expect(checkFile({ name: 'big.pdf', type: 'application/pdf', size: MAX_ATTACHMENT_BYTES + 1 })).toBe('big.pdf is larger than 25 MB.')
  })

  it('lists every accepted extension for the picker', () => {
    for (const ext of ['.pdf', '.docx', '.pptx', '.xlsx', '.csv', '.md', '.heic', '.webp']) expect(ACCEPT).toContain(ext)
  })

  it('works out the kind from the extension, then the type', () => {
    expect(kindOf('a.xlsx', '')).toBe('spreadsheet')
    expect(kindOf('scan', 'image/png')).toBe('image')
    expect(kindOf('a.exe', 'application/octet-stream')).toBeNull()
  })
})

describe('formatBytes', () => {
  it('writes sizes in bytes, KB and MB', () => {
    expect(formatBytes(820)).toBe('820 bytes')
    expect(formatBytes(14 * 1024)).toBe('14 KB')
    expect(formatBytes(3.2 * 1024 * 1024)).toBe('3.2 MB')
    expect(formatBytes(25 * 1024 * 1024)).toBe('25 MB')
  })
})

describe('sentAttachmentsOf', () => {
  it('reads the attachments a message carries and skips damaged entries', () => {
    const detail = {
      attachments: [
        { id: 'a', name: 'report.pdf', mimeType: 'application/pdf', size: 100, kind: 'pdf', pageCount: 2 },
        { id: 'b', name: 'photo.png', mimeType: 'image/png', size: 5 },
        { name: 'no-id.pdf' },
        'nonsense',
      ],
    }
    expect(sentAttachmentsOf({ detail } as never)).toEqual([
      { id: 'a', name: 'report.pdf', mimeType: 'application/pdf', size: 100, kind: 'pdf', pageCount: 2 },
      { id: 'b', name: 'photo.png', mimeType: 'image/png', size: 5, kind: 'image', pageCount: null },
    ])
  })

  it('returns nothing for a message without attachments', () => {
    expect(sentAttachmentsOf({ detail: {} })).toEqual([])
    expect(sentAttachmentsOf({ detail: null } as never)).toEqual([])
  })
})

describe('uploadAttachment', () => {
  const file = new File(['hello'], 'report.pdf', { type: 'application/pdf' })

  it('posts the file with the bearer token and reports progress', async () => {
    const progress: number[] = []
    const promise = uploadAttachment(file, 'conv-1', { onProgress: (fraction) => progress.push(fraction) })
    const xhr = FakeXhr.instances[0]!
    expect(xhr.method).toBe('POST')
    expect(xhr.url).toBe('/api/conversations/attachments?conversationId=conv-1')
    expect(xhr.headers.Authorization).toBe('Bearer token-1')
    expect(xhr.withCredentials).toBe(true)
    expect((xhr.body as FormData).get('file')).toBeInstanceOf(File)
    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 50, total: 100 })
    xhr.respond(201, VIEW)
    await expect(promise).resolves.toMatchObject({ id: 'att-1', kind: 'pdf' })
    expect(progress).toEqual([0.5])
  })

  it('posts a draft without a conversation id', () => {
    void uploadAttachment(file, null).catch(() => {})
    expect(FakeXhr.instances[0]!.url).toBe('/api/conversations/attachments')
  })

  it("throws the server's own sentence", async () => {
    const promise = uploadAttachment(file, null)
    FakeXhr.instances[0]!.respond(422, { code: 'attachment_type_unsupported', detail: 'This file is not really a PDF.' })
    const error = (await promise.catch((e: unknown) => e)) as ApiError
    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe('attachment_type_unsupported')
    expect(error.message).toBe('This file is not really a PDF.')
  })

  it('falls back to a plain sentence for a 413 without a body', async () => {
    const promise = uploadAttachment(file, null)
    FakeXhr.instances[0]!.respond(413, '')
    await expect(promise).rejects.toThrow('report.pdf is larger than 25 MB.')
  })

  it('reports a network failure', async () => {
    const promise = uploadAttachment(file, null)
    FakeXhr.instances[0]!.onerror?.()
    const error = (await promise.catch((e: unknown) => e)) as ApiError
    expect(error.code).toBe('network_error')
  })

  it('retries once after renewing an expired session', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(new Response(JSON.stringify({ accessToken: 'token-2', userId: 'u1', permissions: [] }), { status: 200 })),
      ),
    )
    const promise = uploadAttachment(file, null)
    FakeXhr.instances[0]!.respond(401, { code: 'token_expired' })
    await flush()
    await flush()
    const retry = FakeXhr.instances[1]!
    expect(retry.headers.Authorization).toBe('Bearer token-2')
    retry.respond(201, VIEW)
    await expect(promise).resolves.toMatchObject({ id: 'att-1' })
  })

  it('rejects with an AbortError when cancelled', async () => {
    const controller = new AbortController()
    const promise = uploadAttachment(file, null, { signal: controller.signal })
    controller.abort()
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' })
  })
})

describe('fetchAttachmentBlob', () => {
  it('fetches the bytes with the bearer token', async () => {
    const fetchMock = vi.fn<(path: string, init?: RequestInit) => Promise<Response>>(() => Promise.resolve(new Response('bytes', { status: 200 })))
    vi.stubGlobal('fetch', fetchMock)
    const blob = await fetchAttachmentBlob('att-1')
    expect(blob.size).toBe(5)
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/conversations/attachments/att-1/content')
    expect((fetchMock.mock.calls[0]![1]!.headers as Record<string, string>).Authorization).toBe('Bearer token-1')
  })

  it("throws the server's sentence on failure", async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({ code: 'not_found', detail: 'That file is gone.' }), { status: 404 }))))
    await expect(fetchAttachmentBlob('att-1')).rejects.toThrow('That file is gone.')
  })
})
