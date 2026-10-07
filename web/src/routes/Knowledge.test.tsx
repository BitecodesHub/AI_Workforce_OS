import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { Knowledge } from './Knowledge'

/*
 * The Knowledge page: what it says about how documents are searched, the box that tries a search
 * without starting an agent, and what a person is told about who can search a new source. The
 * knowledge service is answered by a stub that records every call, so "no agent" is a fact about
 * the calls made rather than a hope.
 */

// jsdom has the <dialog> element but not its modal methods; the add-a-source dialog opens with one.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const POLICIES = {
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

const HR = { ...POLICIES, id: 's2', name: 'HR', restricted: true }

const MEANING = {
  ...POLICIES,
  id: 's3',
  name: 'Handbook',
  embeddingProvider: 'gemini',
  embeddingModel: 'text-embedding-004',
  embeddingDimension: 768,
  searchMode: 'keyword+meaning',
}

const PASSAGE = {
  chunkId: 'c1',
  documentId: 'd1',
  sourceId: 's1',
  documentTitle: 'Leave policy.pdf',
  pageNumber: 3,
  heading: 'Annual leave',
  content: 'Full time staff receive twenty five days of annual leave each year.',
  score: 0.9,
}

type Call = { method: string; url: string; body: unknown }
let calls: Call[] = []
let sources: object[] = []
let searchAnswer: object

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function signedIn(permissions: string[]) {
  saveSession('token', {
    userId: 'u1',
    workspaceId: 'w1',
    permissions,
    displayName: 'Eli Employee',
    email: 'eli@example.test',
    role: 'employee',
  })
}

beforeEach(() => {
  calls = []
  sources = [POLICIES]
  searchAnswer = { passages: [PASSAGE], grounded: true, degraded: false }
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      const body = typeof init.body === 'string' ? JSON.parse(init.body) : null
      calls.push({ method, url, body })
      if (method === 'GET' && url === '/api/sources') return json(200, sources)
      if (method === 'POST' && url === '/api/knowledge/search') return json(200, searchAnswer)
      if (method === 'POST' && url === '/api/sources') return json(200, { ...POLICIES, id: 's9', name: body.name, restricted: body.restricted })
      return json(404, { code: 'not_found', detail: 'Not here.' })
    }),
  )
  signedIn(['knowledge:read', 'knowledge:query'])
  window.history.pushState({}, '', '/knowledge')
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

async function renderKnowledge() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <Knowledge />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
  await screen.findByRole('heading', { name: 'Sources' })
  return view
}

const searchCalls = () => calls.filter((call) => call.url === '/api/knowledge/search')

async function trySearch(text: string) {
  fireEvent.change(screen.getByLabelText('What do you want to find?'), { target: { value: text } })
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
  })
}

describe('what the Knowledge page says about search', () => {
  it('says keyword search where it used to name an offline model and its size', async () => {
    await renderKnowledge()

    expect(screen.getByText('Keyword search. Add an embedding model for search by meaning.')).toBeInTheDocument()
    expect(screen.queryByText(/Offline sandbox/)).not.toBeInTheDocument()
    expect(screen.queryByText(/1,536 dimensions/)).not.toBeInTheDocument()
  })

  it('still names the model and its size when meaning-based search is real', async () => {
    sources = [MEANING]
    await renderKnowledge()

    expect(screen.getByText('text-embedding-004')).toBeInTheDocument()
    expect(screen.getByText(/, 768 dimensions$/)).toBeInTheDocument()
    expect(screen.queryByText(/Keyword search\. Add an embedding model/)).not.toBeInTheDocument()
  })

  it('says the sources differ when some are keyword only and some are not', async () => {
    sources = [POLICIES, MEANING]
    await renderKnowledge()

    expect(screen.getByText('Differs by source')).toBeInTheDocument()
  })

  it('marks a restricted source, which only a manager is ever sent', async () => {
    sources = [POLICIES, HR]
    await renderKnowledge()

    expect(screen.getByText('Restricted')).toBeInTheDocument()
  })
})

describe('Ask in Chat', () => {
  it('is named for what it does, and offered only with Chat access as well as search', async () => {
    signedIn(['knowledge:read', 'knowledge:query', 'chat:use'])
    await renderKnowledge()

    expect(screen.getByRole('link', { name: 'Ask in Chat' })).toHaveAttribute('href', '/chat')
    expect(screen.queryByText('Search these documents')).not.toBeInTheDocument()
  })

  it('is not offered to someone without Chat access, who can still try a search', async () => {
    await renderKnowledge()

    expect(screen.queryByRole('link', { name: 'Ask in Chat' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('What do you want to find?')).toBeInTheDocument()
  })
})

describe('Try a search', () => {
  it('needs knowledge:query and nothing else', async () => {
    signedIn(['knowledge:read'])
    await renderKnowledge()

    expect(screen.queryByText('Try a search')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('What do you want to find?')).not.toBeInTheDocument()
  })

  it('searches the documents directly and lists each passage with its document, page and heading', async () => {
    await renderKnowledge()

    await trySearch('annual leave')

    expect(await screen.findByText('[1] Leave policy.pdf')).toBeInTheDocument()
    expect(screen.getByText('About page 3')).toBeInTheDocument()
    expect(screen.getByText('Annual leave')).toBeInTheDocument()
    expect(screen.getByText(/twenty five days of annual leave/)).toBeInTheDocument()
    const passage = screen.getByText('[1] Leave policy.pdf').closest('li')!
    expect(within(passage).getByRole('link', { name: 'Policies' })).toHaveAttribute('href', '/knowledge/s1')
    expect(screen.getByText('1 passage from 1 document, best match first.')).toBeInTheDocument()

    // The service was asked once, for eight passages, across every source. Nothing else was
    // called: no run, no chat message, no task.
    expect(searchCalls()).toEqual([
      { method: 'POST', url: '/api/knowledge/search', body: { query: 'annual leave', limit: 8 } },
    ])
    expect(calls.filter((call) => call.method !== 'GET').map((call) => call.url)).toEqual(['/api/knowledge/search'])
  })

  it('does not search for an empty box', async () => {
    await renderKnowledge()

    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('What do you want to find?'), { target: { value: '   ' } })
    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled()
    expect(searchCalls()).toHaveLength(0)
  })

  it('says Keyword search only right now when the service could only do the keyword half', async () => {
    searchAnswer = { passages: [PASSAGE], grounded: true, degraded: true }
    await renderKnowledge()

    await trySearch('annual leave')

    expect(await screen.findByText('Keyword search only right now')).toBeInTheDocument()
    expect(screen.getByText('[1] Leave policy.pdf')).toBeInTheDocument()
  })

  it('does not say it when the whole search ran', async () => {
    await renderKnowledge()

    await trySearch('annual leave')

    await screen.findByText('[1] Leave policy.pdf')
    expect(screen.queryByText('Keyword search only right now')).not.toBeInTheDocument()
  })

  it('says plainly when nothing matched, and what to check', async () => {
    searchAnswer = { passages: [], grounded: false, degraded: false }
    await renderKnowledge()

    await trySearch('moon base')

    expect(await screen.findByText(/Nothing matched "moon base"\. Try other words, or check that the document/)).toBeInTheDocument()
  })

  it('limits the search to the source chosen, when there is more than one to choose from', async () => {
    sources = [POLICIES, MEANING]
    await renderKnowledge()

    fireEvent.change(screen.getByLabelText('Where to look'), { target: { value: 's3' } })
    await trySearch('annual leave')

    await screen.findByText('[1] Leave policy.pdf')
    expect(searchCalls()[0]?.body).toEqual({ query: 'annual leave', sourceIds: ['s3'], limit: 8 })
  })

  it('offers no choice of where to look when there is one source', async () => {
    await renderKnowledge()

    expect(screen.queryByLabelText('Where to look')).not.toBeInTheDocument()
  })

  it('reports a failed search instead of showing nothing', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init: RequestInit = {}) => {
        if ((init.method ?? 'GET') === 'GET' && url === '/api/sources') return json(200, sources)
        return json(502, { code: 'upstream_unavailable', detail: 'The knowledge base is unavailable.' })
      }),
    )
    await renderKnowledge()

    await trySearch('annual leave')

    expect(await screen.findByText('The knowledge base is unavailable.')).toBeInTheDocument()
  })
})

describe('adding a source', () => {
  const MANAGE = ['knowledge:read', 'knowledge:query', 'knowledge:source_manage']

  async function openDialog() {
    signedIn(MANAGE)
    await renderKnowledge()
    fireEvent.click(screen.getByRole('button', { name: 'Add a source' }))
    return screen.getByRole('dialog', { name: 'Add a source' })
  }

  it('says who can search it before it is created, and what changes when it is restricted', async () => {
    const dialog = await openDialog()

    expect(within(dialog).getByText('Everyone with Chat access in this workspace can search this source')).toBeInTheDocument()

    fireEvent.click(within(dialog).getByLabelText('Only people who manage knowledge can search this source'))

    expect(within(dialog).getByText(/^Only people who manage knowledge can search this source, and everyone else/)).toBeInTheDocument()
    expect(
      within(dialog).queryByText('Everyone with Chat access in this workspace can search this source'),
    ).not.toBeInTheDocument()
  })

  it('creates it open to the workspace unless the box is ticked, and says so afterwards', async () => {
    const dialog = await openDialog()

    fireEvent.change(within(dialog).getByLabelText(/^Name/), { target: { value: 'Travel' } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }))
    })

    await waitFor(() => expect(calls.some((call) => call.method === 'POST' && call.url === '/api/sources')).toBe(true))
    expect(calls.find((call) => call.method === 'POST' && call.url === '/api/sources')?.body).toEqual({
      name: 'Travel',
      kind: 'upload',
      restricted: false,
    })
    expect(
      await screen.findByText(
        'Source created. Upload a document to make it searchable. Everyone with Chat access in this workspace can search this source.',
      ),
    ).toBeInTheDocument()
  })

  it('creates it restricted from the start when asked, so the workspace never sees it first', async () => {
    const dialog = await openDialog()

    fireEvent.change(within(dialog).getByLabelText(/^Name/), { target: { value: 'Board minutes' } })
    fireEvent.click(within(dialog).getByLabelText('Only people who manage knowledge can search this source'))
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }))
    })

    await waitFor(() => expect(calls.some((call) => call.method === 'POST' && call.url === '/api/sources')).toBe(true))
    expect(calls.find((call) => call.method === 'POST' && call.url === '/api/sources')?.body).toEqual({
      name: 'Board minutes',
      kind: 'upload',
      restricted: true,
    })
    expect(await screen.findByText(/everyone else will not see that it exists\.$/)).toBeInTheDocument()
  })
})
