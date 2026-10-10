// @find: tests for searching inside a knowledge source, search passages, read-only, vitest, SourceDetail component tests, Source page
// @what: Automated tests that check the searching inside a knowledge source screen (/knowledge/:id) behaves as users expect.
// @flow: Renders SourceDetail from SourceDetail.tsx inside a QueryClientProvider and RouterProvider with mocked API calls
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { SourceDetail } from './SourceDetail'

/*
 * A source's own search box, the passages of one document, and who can search the source: what a
 * manager can change, what a reader cannot, and what each action sends to the service.
 */

// jsdom has the <dialog> element but not its modal methods; dialogs and the passages panel use them.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.show = function show(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const MANAGE = ['knowledge:read', 'knowledge:query', 'knowledge:source_manage', 'chat:use']
const READ = ['knowledge:read', 'knowledge:query']

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
  restricted: false,
  searchMode: 'keyword',
}

const LEAVE = {
  id: 'd-leave',
  title: 'Leave policy.txt',
  mediaType: 'text/plain',
  status: 'indexed',
  chunkCount: 3,
  indexedAt: '2026-10-01T09:00:00Z',
  removedAtSource: false,
}

const SCAN = {
  id: 'd-scan',
  title: 'Scan.pdf',
  mediaType: 'application/pdf',
  status: 'skipped',
  skipReason: 'No text could be read from it.',
  chunkCount: 0,
  indexedAt: null,
  removedAtSource: false,
}

const FOUND = {
  chunkId: 'c1',
  documentId: 'd-leave',
  sourceId: 's1',
  documentTitle: 'Leave policy.txt',
  pageNumber: 2,
  heading: 'Annual leave',
  content: 'Full time staff receive twenty five days of annual leave each year.',
  score: 0.9,
}

const passage = (position: number, extra: object = {}) => ({
  id: `p${position}`,
  position,
  content: `Passage text number ${position}.`,
  ...extra,
})

type Call = { method: string; url: string; body: unknown }
let calls: Call[] = []
let source: Record<string, unknown>
let patchAnswer: (body: Record<string, unknown>) => Response

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function answer(method: string, url: string, body: Record<string, unknown> | null): Response {
  if (method === 'GET' && url === '/api/sources/s1') return json(200, source)
  if (method === 'GET' && url === '/api/sources/s1/documents') return json(200, [LEAVE, SCAN])
  if (method === 'POST' && url === '/api/knowledge/search') {
    return json(200, { passages: [FOUND], grounded: true, degraded: false })
  }
  if (method === 'GET' && url === '/api/sources/s1/documents/d-leave/chunks?page=0') {
    return json(200, {
      documentId: 'd-leave',
      title: 'Leave policy.txt',
      total: 3,
      page: 0,
      size: 2,
      passages: [passage(0, { pageNumber: 1, heading: 'Annual leave' }), passage(1)],
    })
  }
  if (method === 'GET' && url === '/api/sources/s1/documents/d-leave/chunks?page=1') {
    return json(200, { documentId: 'd-leave', title: 'Leave policy.txt', total: 3, page: 1, size: 2, passages: [passage(2)] })
  }
  if (method === 'PATCH' && url === '/api/sources/s1' && body) return patchAnswer(body)
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
  source = { ...SOURCE }
  patchAnswer = (body) => {
    source = { ...source, ...body }
    return json(200, source)
  }
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      const body = typeof init.body === 'string' ? (JSON.parse(init.body) as Record<string, unknown>) : null
      calls.push({ method, url, body })
      return answer(method, url, body)
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
  await screen.findByText('Leave policy.txt')
  return view
}

const writes = () => calls.filter((call) => call.method !== 'GET')

describe('searching inside one source', () => {
  it('searches only this source, with eight passages asked for, and shows where each came from', async () => {
    await renderSource()

    fireEvent.change(screen.getByLabelText('What do you want to find?'), { target: { value: 'annual leave' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    })

    const result = (await screen.findByText('[1] Leave policy.txt')).closest('li')!
    expect(within(result).getByText('About page 2')).toBeInTheDocument()
    expect(within(result).getByText('Annual leave')).toBeInTheDocument()
    expect(within(result).getByText(/twenty five days/)).toBeInTheDocument()
    // The source is the page, so the result does not link back to it.
    expect(within(result).queryByRole('link')).not.toBeInTheDocument()
    expect(calls.find((call) => call.url === '/api/knowledge/search')?.body).toEqual({
      query: 'annual leave',
      sourceIds: ['s1'],
      limit: 8,
    })
    expect(writes().map((call) => call.url)).toEqual(['/api/knowledge/search'])
  })

  it('is there for someone who can search but not manage, without any way to change the source', async () => {
    signedIn(READ)
    await renderSource()

    expect(screen.getByLabelText('What do you want to find?')).toBeInTheDocument()
    expect(screen.queryByText('Who can search')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Rename' })).not.toBeInTheDocument()
  })

  it('is not there for someone who cannot search', async () => {
    signedIn(['knowledge:read'])
    await renderSource()

    expect(screen.queryByLabelText('What do you want to find?')).not.toBeInTheDocument()
  })

  it('names Ask in Chat for what it is', async () => {
    await renderSource()

    expect(screen.getByRole('link', { name: 'Ask in Chat' })).toHaveAttribute('href', '/chat')
    expect(screen.queryByText('Search your documents')).not.toBeInTheDocument()
  })
})

describe('the passages of one document', () => {
  it('opens beside the list in reading order, with page and heading, and leaves a scan without a button', async () => {
    signedIn(READ)
    await renderSource()
    expect(screen.queryByRole('button', { name: 'View passages in Scan.pdf' })).not.toBeInTheDocument()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'View passages in Leave policy.txt' }))
    })

    const panel = await screen.findByRole('dialog', { name: 'Leave policy.txt' })
    expect(await within(panel).findByText('Passage 1')).toBeInTheDocument()
    expect(within(panel).getByText('About page 1')).toBeInTheDocument()
    expect(within(panel).getByText('Annual leave')).toBeInTheDocument()
    expect(within(panel).getByText('Passage text number 1.')).toBeInTheDocument()
    expect(within(panel).getByText('Showing 2 of 3 passages, in the order they appear in the document.')).toBeInTheDocument()
    expect(calls.map((call) => call.url)).toContain('/api/sources/s1/documents/d-leave/chunks?page=0')
  })

  it('shows the rest a page at a time', async () => {
    await renderSource()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'View passages in Leave policy.txt' }))
    })
    const panel = await screen.findByRole('dialog', { name: 'Leave policy.txt' })
    await within(panel).findByText('Passage 2')

    await act(async () => {
      fireEvent.click(within(panel).getByRole('button', { name: 'Show more passages' }))
    })

    expect(await within(panel).findByText('Passage 3')).toBeInTheDocument()
    expect(within(panel).getByText('Showing 3 of 3 passages, in the order they appear in the document.')).toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'Show more passages' })).not.toBeInTheDocument()
  })

  it('says so when a document cannot be read, rather than showing nothing', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init: RequestInit = {}) => {
        const method = init.method ?? 'GET'
        if (url.includes('/chunks')) return json(404, { code: 'not_found', detail: 'That document is not here.' })
        return answer(method, url, null)
      }),
    )
    await renderSource()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'View passages in Leave policy.txt' }))
    })

    const panel = await screen.findByRole('dialog', { name: 'Leave policy.txt' })
    expect(await within(panel).findByText('That document is not here.')).toBeInTheDocument()
  })
})

describe('who can search a source', () => {
  it('tells a manager who can search before they upload, in the words the product uses', async () => {
    await renderSource()

    expect(screen.getByText('Everyone with Chat access in this workspace can search this source.')).toBeInTheDocument()
  })

  it('restricts the source when the box is ticked, and says what that means', async () => {
    await renderSource()
    const box = screen.getByLabelText('Only people who manage knowledge can search this source')
    expect(box).not.toBeChecked()

    await act(async () => {
      fireEvent.click(box)
    })

    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0]).toEqual({ method: 'PATCH', url: '/api/sources/s1', body: { restricted: true } })
    expect(
      await screen.findByText('Restricted. Only people who manage knowledge can search this source now.'),
    ).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Only people who manage knowledge can search this source')).toBeChecked())
    expect(screen.getAllByText('Restricted').length).toBeGreaterThan(0)
  })

  it('opens a restricted source to the workspace again when the box is cleared', async () => {
    source = { ...SOURCE, restricted: true }
    await renderSource()
    const box = screen.getByLabelText('Only people who manage knowledge can search this source')
    expect(box).toBeChecked()

    // Letting everyone search widens access, so it asks first, starting on the choice that keeps
    // it restricted: Enter pressed out of habit must not share the documents.
    await act(async () => {
      fireEvent.click(box)
    })
    expect(await screen.findByRole('button', { name: 'Keep it restricted' })).toHaveFocus()
    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: 'Let everyone search' }))
    })

    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0]?.body).toEqual({ restricted: false })
    expect(
      await screen.findByText('Opened. Everyone with Chat access in this workspace can search this source now.'),
    ).toBeInTheDocument()
  })

  it('says when a change could not be made, and leaves the box as the service has it', async () => {
    patchAnswer = () => json(403, { code: 'forbidden', detail: 'You do not have permission to do that.' })
    await renderSource()

    await act(async () => {
      fireEvent.click(screen.getByLabelText('Only people who manage knowledge can search this source'))
    })

    expect(await screen.findByText('You do not have permission to do that.')).toBeInTheDocument()
    expect(screen.getByLabelText('Only people who manage knowledge can search this source')).not.toBeChecked()
  })
})

describe('renaming a source', () => {
  async function openRename() {
    await renderSource()
    fireEvent.click(screen.getByRole('button', { name: 'Rename' }))
    return screen.getByRole('dialog', { name: 'Rename Policies' })
  }

  it('sends the new name, trimmed, and says it is done', async () => {
    const dialog = await openRename()

    fireEvent.change(within(dialog).getByLabelText(/^Name/), { target: { value: '  People policies ' } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Rename' }))
    })

    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0]).toEqual({ method: 'PATCH', url: '/api/sources/s1', body: { name: 'People policies' } })
    expect(await screen.findByText('Renamed to People policies.')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 1, name: 'People policies' })).toBeInTheDocument()
  })

  it('shows a name already in use beside the field, and keeps the dialog open', async () => {
    patchAnswer = () => json(409, { code: 'already_exists', detail: 'A source with that name already exists in this workspace.' })
    const dialog = await openRename()

    fireEvent.change(within(dialog).getByLabelText(/^Name/), { target: { value: 'Travel' } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Rename' }))
    })

    expect(await within(dialog).findByText('A source with that name already exists in this workspace.')).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: 'Rename Policies' })).toBeInTheDocument()
  })

  it('sends nothing when the name has not changed', async () => {
    const dialog = await openRename()

    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Rename' }))
    })

    expect(writes()).toHaveLength(0)
  })
})

describe('how the source is searched', () => {
  it('says keyword for a source with no embedding model, rather than naming the offline one', async () => {
    await renderSource()

    expect(screen.getByText('Add an embedding model for search by meaning.')).toBeInTheDocument()
    expect(screen.queryByText('sandbox-embed-1')).not.toBeInTheDocument()
    expect(screen.queryByText(/1,536 dimensions/)).not.toBeInTheDocument()
  })

  it('names the model and its size for a source searched by meaning', async () => {
    source = {
      ...SOURCE,
      embeddingProvider: 'gemini',
      embeddingModel: 'text-embedding-004',
      embeddingDimension: 768,
      searchMode: 'keyword+meaning',
    }
    await renderSource()

    expect(screen.getByText('text-embedding-004')).toBeInTheDocument()
    expect(screen.getByText(/, 768 dimensions$/)).toBeInTheDocument()
  })
})
