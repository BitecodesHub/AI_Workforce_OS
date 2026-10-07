import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { SourceDetail } from './SourceDetail'

/*
 * A knowledge source as a manager and as a reader: deleting a document or the whole source, the
 * choice offered when an upload's name is already taken, and what the page says after a file
 * replaces an older version. The knowledge service is answered by a stub that records each call.
 */

// jsdom has the <dialog> element but not its modal methods; every dialog here opens with one.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const MANAGE = ['knowledge:read', 'knowledge:query', 'knowledge:source_manage', 'chat:use']

const SOURCE = {
  id: 's1',
  name: 'Policies',
  kind: 'upload',
  status: 'ready',
  documentCount: 2,
  chunkCount: 5,
  embeddingProvider: 'sandbox',
  embeddingModel: 'sandbox-embed-1',
  embeddingDimension: 1536,
  lastIngestedAt: '2026-10-01T09:00:00Z',
}

const LEAVE = {
  id: 'd-leave',
  title: 'Leave policy.txt',
  mediaType: 'text/plain',
  status: 'indexed',
  chunkCount: 2,
  indexedAt: '2026-10-01T09:00:00Z',
  removedAtSource: false,
}

const HANDBOOK = {
  id: 'd-handbook',
  title: 'Handbook.docx',
  mediaType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  status: 'indexed',
  notice: 'Only the first 5,000,000 characters (about 1,666 pages) were indexed. Split the file into smaller ones to index the rest.',
  chunkCount: 3,
  indexedAt: '2026-10-01T09:00:00Z',
  removedAtSource: false,
}

type Call = { method: string; url: string; mode: string | null; file: string | null }
let calls: Call[] = []
let documents: object[] = []
/** What the next upload answers, given the name and mode it was sent with. */
let uploadAnswer: (name: string, mode: string) => object

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function answer(method: string, url: string): Response {
  if (method === 'GET' && url === '/api/sources/s1') return json(200, SOURCE)
  if (method === 'GET' && url === '/api/sources/s1/documents') return json(200, documents)
  if (method === 'DELETE' && url.startsWith('/api/sources/s1/documents/')) {
    const id = url.slice('/api/sources/s1/documents/'.length)
    documents = documents.filter((document) => (document as { id: string }).id !== id)
    return new Response(null, { status: 204 })
  }
  if (method === 'DELETE' && url === '/api/sources/s1') return new Response(null, { status: 204 })
  return json(404, { code: 'not_found', detail: 'Not here.' })
}

function signedIn(permissions: string[]) {
  saveSession('token', {
    userId: 'u1',
    workspaceId: 'w1',
    permissions,
    displayName: 'Maya Manager',
    email: 'maya@example.test',
    role: 'manager',
  })
}

beforeEach(() => {
  calls = []
  documents = [LEAVE, HANDBOOK]
  uploadAnswer = (name) => ({ documentId: 'd-new', status: 'indexed', chunkCount: 1, searchable: true, title: name })
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      const form = init.body instanceof FormData ? init.body : null
      const file = form ? (form.get('file') as File).name : null
      const mode = form ? String(form.get('mode')) : null
      calls.push({ method, url, mode, file })
      if (method === 'POST' && url === '/api/sources/s1/documents' && file && mode) return json(200, uploadAnswer(file, mode))
      return answer(method, url)
    }),
  )
  signedIn(MANAGE)
  window.history.pushState({}, '', '/knowledge/s1')
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

async function renderSource() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <SourceDetail id="s1" />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
  await screen.findByText('Handbook.docx')
  return view
}

function choose(container: HTMLElement, ...names: string[]) {
  const input = container.querySelector<HTMLInputElement>('input[type="file"]')
  if (!input) throw new Error('No file input')
  const files = names.map((name) => new File(['Some text about leave.'], name, { type: 'text/plain' }))
  fireEvent.change(input, { target: { files } })
}

const uploads = () => calls.filter((call) => call.method === 'POST')

const openDialog = (name: string | RegExp) => screen.getByRole('dialog', { name })

describe('SourceDetail for someone who manages knowledge', () => {
  it('opens under its eyebrow="Uploaded files", the kind of source it is', async () => {
    await renderSource()
    expect(screen.getByText('Uploaded files')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Policies' })).toBeInTheDocument()
  })

  it('shows a document notice on its row', async () => {
    await renderSource()
    expect(screen.getByText(/Only the first 5,000,000 characters/)).toBeInTheDocument()
  })

  it('deletes a document only after asking, then says it is gone', async () => {
    await renderSource()

    fireEvent.click(screen.getByRole('button', { name: 'Delete Handbook.docx' }))
    const dialog = openDialog('Delete Handbook.docx?')
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false)
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Delete document' }))
    })

    expect(calls).toContainEqual({ method: 'DELETE', url: '/api/sources/s1/documents/d-handbook', mode: null, file: null })
    expect(await screen.findByText('Deleted Handbook.docx. It can no longer be found in search.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Handbook.docx')).not.toBeInTheDocument())
  })

  it('uploads a file with a new name without asking', async () => {
    const { container } = await renderSource()

    await act(async () => choose(container, 'Travel.txt'))

    await waitFor(() => expect(uploads()).toHaveLength(1))
    expect(uploads()[0]).toMatchObject({ file: 'Travel.txt', mode: 'replace' })
    expect(await screen.findByText('Travel.txt was indexed into 1 passage.')).toBeInTheDocument()
  })

  it('asks before uploading over a name already here, and keeps both when asked to', async () => {
    const { container } = await renderSource()
    uploadAnswer = () => ({ documentId: 'd-new', status: 'indexed', chunkCount: 1, searchable: true, title: 'Leave policy (2).txt' })

    await act(async () => choose(container, 'Leave policy.txt'))

    const dialog = openDialog('Leave policy.txt is already in use')
    expect(uploads()).toHaveLength(0)
    expect(within(dialog).getByText(/as Leave policy \(2\)\.txt/)).toBeInTheDocument()
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Keep both' }))
    })

    await waitFor(() => expect(uploads()).toHaveLength(1))
    expect(uploads()[0]).toMatchObject({ file: 'Leave policy.txt', mode: 'keep_both' })
    expect(
      await screen.findByText(
        'Leave policy.txt was kept beside the existing file as Leave policy (2).txt, and indexed into 1 passage.',
      ),
    ).toBeInTheDocument()
  })

  it('says Replaced, with the date of the version it replaced, when the upload replaces one', async () => {
    const { container } = await renderSource()
    uploadAnswer = () => ({
      documentId: 'd-leave',
      status: 'replaced',
      chunkCount: 3,
      searchable: true,
      title: 'Leave policy.txt',
      detail: 'Replaced the version indexed on 1 October 2026.',
      replacedIndexedAt: '2026-10-01T09:00:00Z',
    })

    await act(async () => choose(container, 'Leave policy.txt'))
    await act(async () => {
      fireEvent.click(within(openDialog('Leave policy.txt is already in use')).getByRole('button', { name: 'Replace' }))
    })

    expect(uploads()[0]).toMatchObject({ file: 'Leave policy.txt', mode: 'replace' })
    expect(
      await screen.findByText(
        /^Replaced Leave policy\.txt: the new version was indexed into 3 passages, and the version indexed on 1 Oct 2026 is no longer searchable\.$/,
      ),
    ).toBeInTheDocument()
  })

  it('skips only the files whose names are taken, and repeats within one batch count as taken', async () => {
    const { container } = await renderSource()

    await act(async () => choose(container, 'Leave policy.txt', 'Travel.txt', 'Travel.txt'))
    const dialog = openDialog('2 file names are already in use')
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Skip' }))
    })

    await waitFor(() => expect(uploads()).toHaveLength(1))
    expect(uploads()[0]).toMatchObject({ file: 'Travel.txt', mode: 'replace' })
    expect(await screen.findByText(/2 skipped \(Leave policy\.txt and Travel\.txt\)/)).toBeInTheDocument()
  })

  it('deletes the source only once its name is typed, then returns to the knowledge page', async () => {
    await renderSource()

    fireEvent.click(screen.getByRole('button', { name: 'Delete source' }))
    const dialog = openDialog('Delete Policies?')
    const confirm = within(dialog).getByRole('button', { name: 'Delete source' })
    expect(confirm).toBeDisabled()

    fireEvent.change(within(dialog).getByLabelText('Type the source name, Policies, to confirm'), {
      target: { value: 'Polic' },
    })
    expect(confirm).toBeDisabled()
    fireEvent.change(within(dialog).getByLabelText('Type the source name, Policies, to confirm'), {
      target: { value: 'Policies' },
    })
    expect(confirm).toBeEnabled()
    await act(async () => {
      fireEvent.click(confirm)
    })

    expect(calls).toContainEqual({ method: 'DELETE', url: '/api/sources/s1', mode: null, file: null })
    await waitFor(() => expect(window.location.pathname).toBe('/knowledge'))
  })
})

describe('SourceDetail for someone who can only read', () => {
  it('offers no way to delete or upload', async () => {
    signedIn(['knowledge:read'])
    await renderSource()

    expect(screen.queryByRole('button', { name: /^Delete/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Upload documents' })).not.toBeInTheDocument()
  })
})
