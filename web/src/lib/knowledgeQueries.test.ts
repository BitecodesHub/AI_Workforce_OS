// @find: tests for knowledge queries, upload plan, name clashes, keep both, replace, skip, document notice, delete document, delete source, search mode, restricted source
// @what: Unit and hook tests for upload planning, clash handling, notices and the knowledge source mutations.
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  documentNotice,
  findClashes,
  isRestricted,
  nextFreeName,
  numberedName,
  planUploads,
  searchModeOf,
  sourceAccessNotice,
  useCreateKnowledgeSource,
  useDeleteDocument,
  useDeleteSource,
  useDocumentPassages,
  useKnowledgeSearch,
  useUpdateSource,
  useUploadKnowledgeDocument,
} from './knowledgeQueries'
import type { Source, SourceDocument } from './queries'
import { clearSession, saveSession } from './session'

/*
 * Uploading into a source when names are already taken, and erasing documents and sources: which
 * files are sent and how, what the service is asked, and which cached lists are refreshed after.
 */

const file = (name: string) => ({ name })

describe('findClashes', () => {
  it('finds names already in the source and names repeated within the batch, each once', () => {
    const files = [file('Leave.pdf'), file('New.pdf'), file('Contract.pdf'), file('Contract.pdf'), file('Contract.pdf')]
    expect(findClashes(files, ['Leave.pdf', 'Other.pdf'])).toEqual(['Leave.pdf', 'Contract.pdf'])
  })

  it('compares names exactly, as the service does', () => {
    expect(findClashes([file('leave.pdf')], ['Leave.pdf'])).toEqual([])
    expect(findClashes([file('Fresh.txt')], [])).toEqual([])
  })
})

describe('planUploads', () => {
  const files = [file('Leave.pdf'), file('New.pdf'), file('Contract.pdf'), file('Contract.pdf')]
  const existing = ['Leave.pdf']

  it('replace sends every file as a new version', () => {
    const { uploads, skipped } = planUploads(files, existing, 'replace')
    expect(uploads.map((upload) => upload.mode)).toEqual(['replace', 'replace', 'replace', 'replace'])
    expect(skipped).toEqual([])
  })

  it('keep both asks the service to number every file whose name is taken', () => {
    const { uploads } = planUploads(files, existing, 'keep_both')
    expect(uploads.map((upload) => [upload.file.name, upload.mode])).toEqual([
      ['Leave.pdf', 'keep_both'],
      ['New.pdf', 'replace'],
      ['Contract.pdf', 'replace'],
      ['Contract.pdf', 'keep_both'],
    ])
  })

  it('skip leaves out names the source holds, and all but the first of a repeated new name', () => {
    const { uploads, skipped } = planUploads(files, existing, 'skip')
    expect(uploads.map((upload) => upload.file.name)).toEqual(['New.pdf', 'Contract.pdf'])
    expect(skipped.map((skippedFile) => skippedFile.name)).toEqual(['Leave.pdf', 'Contract.pdf'])
  })
})

describe('nextFreeName', () => {
  it('names the first numbered name not already taken, as the service picks it', () => {
    expect(nextFreeName('Leave policy.pdf', ['Leave policy.pdf'])).toBe('Leave policy (2).pdf')
    expect(nextFreeName('Leave policy.pdf', ['Leave policy.pdf', 'Leave policy (2).pdf'])).toBe('Leave policy (3).pdf')
  })
})

describe('numberedName', () => {
  it('numbers before the extension, as the service does', () => {
    expect(numberedName('Leave policy.pdf', 2)).toBe('Leave policy (2).pdf')
    expect(numberedName('README', 2)).toBe('README (2)')
    expect(numberedName('.env', 3)).toBe('.env (3)')
  })
})

describe('documentNotice', () => {
  const row = (extra: object): SourceDocument =>
    ({
      id: 'd1',
      title: 'Handbook.docx',
      mediaType: 'application/msword',
      status: 'indexed',
      skipReason: null,
      chunkCount: 4,
      indexedAt: null,
      removedAtSource: false,
      ...extra,
    }) as SourceDocument

  it('reads the notice the service sends, and nothing when there is none', () => {
    expect(documentNotice(row({ notice: 'Only the first 5,000,000 characters were indexed.' }))).toBe(
      'Only the first 5,000,000 characters were indexed.',
    )
    expect(documentNotice(row({}))).toBeNull()
    expect(documentNotice(row({ notice: '  ' }))).toBeNull()
  })
})

describe('the hooks', () => {
  let client: QueryClient
  let requests: { url: string; method: string; body: BodyInit | null | undefined }[]

  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children)

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    requests = []
    saveSession('token', {
      userId: 'u1',
      workspaceId: 'w1',
      permissions: ['knowledge:source_manage'],
      displayName: 'Maya',
      email: 'maya@example.test',
      role: 'manager',
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init: RequestInit = {}) => {
        requests.push({ url, method: init.method ?? 'GET', body: init.body })
        if (init.method === 'DELETE') return new Response(null, { status: 204 })
        return new Response(
          JSON.stringify({ documentId: 'd2', status: 'indexed', chunkCount: 1, searchable: true, title: 'Leave (2).pdf' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        )
      }),
    )
  })

  afterEach(() => {
    clearSession()
    vi.unstubAllGlobals()
  })

  it('uploads with the chosen mode, and refreshes the sources', async () => {
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useUploadKnowledgeDocument('s1'), { wrapper })

    let outcome: Awaited<ReturnType<typeof result.current.mutateAsync>> | undefined
    await act(async () => {
      outcome = await result.current.mutateAsync({ file: new File(['text'], 'Leave.pdf'), mode: 'keep_both' })
    })

    expect(outcome?.title).toBe('Leave (2).pdf')
    const [request] = requests
    expect(request?.url).toBe('/api/sources/s1/documents')
    expect(request?.method).toBe('POST')
    const form = request?.body as FormData
    expect(form.get('mode')).toBe('keep_both')
    expect((form.get('file') as File).name).toBe('Leave.pdf')
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sources'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sources', 's1'] })
  })

  it('an upload with no mode is sent as replace', async () => {
    const { result } = renderHook(() => useUploadKnowledgeDocument('s1'), { wrapper })
    await act(async () => {
      await result.current.mutateAsync({ file: new File(['text'], 'Leave.pdf') })
    })
    expect((requests[0]?.body as FormData).get('mode')).toBe('replace')
  })

  it('deletes one document through its source, and refreshes the source and the list', async () => {
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useDeleteDocument('s1'), { wrapper })

    await act(async () => {
      await result.current.mutateAsync('d1')
    })

    expect(requests).toEqual([{ url: '/api/sources/s1/documents/d1', method: 'DELETE', body: null }])
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sources'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sources', 's1'] })
  })

  it('deletes a source, dropping its own cached data rather than asking for it again', async () => {
    client.setQueryData(['sources', 's1'], { id: 's1' })
    client.setQueryData(['sources', 's1', 'documents'], [])
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useDeleteSource(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync('s1')
    })

    expect(requests).toEqual([{ url: '/api/sources/s1', method: 'DELETE', body: null }])
    expect(client.getQueryData(['sources', 's1'])).toBeUndefined()
    expect(client.getQueryData(['sources', 's1', 'documents'])).toBeUndefined()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sources'] })
  })
})

const source = (extra: object = {}) =>
  ({
    id: 's1',
    name: 'Policies',
    kind: 'upload',
    status: 'ready',
    documentCount: 1,
    chunkCount: 2,
    embeddingProvider: 'sandbox',
    embeddingModel: 'sandbox-embed-1',
    embeddingDimension: 1536,
    lastIngestedAt: null,
    lastError: null,
    ...extra,
  }) as Source

describe('how a source is searched, and who may search it', () => {
  it('reads the mode the service sends, and the provider when it sends none', () => {
    expect(searchModeOf(source({ searchMode: 'keyword+meaning' }))).toBe('keyword+meaning')
    expect(searchModeOf(source({ searchMode: 'keyword' }))).toBe('keyword')
    // An older service sends no mode. The sandbox's vectors carry no meaning; any other provider's do.
    expect(searchModeOf(source())).toBe('keyword')
    expect(searchModeOf(source({ embeddingProvider: 'Sandbox' }))).toBe('keyword')
    expect(searchModeOf(source({ embeddingProvider: 'gemini' }))).toBe('keyword+meaning')
  })

  it('treats a source as open to the workspace unless the service says it is restricted', () => {
    expect(isRestricted(source())).toBe(false)
    expect(isRestricted(source({ restricted: false }))).toBe(false)
    expect(isRestricted(source({ restricted: true }))).toBe(true)
  })

  it('words the access line the same way everywhere it is shown', () => {
    expect(sourceAccessNotice(false)).toBe('Everyone with Chat access in this workspace can search this source')
    expect(sourceAccessNotice(true)).toMatch(/^Only people who manage knowledge can search this source/)
  })
})

describe('the search and passages hooks', () => {
  let client: QueryClient
  let requests: { url: string; method: string; body: unknown }[]
  let respond: (url: string, method: string) => Response

  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children)
  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

  beforeEach(() => {
    client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    requests = []
    respond = () => json({})
    saveSession('token', {
      userId: 'u1',
      workspaceId: 'w1',
      permissions: ['knowledge:query', 'knowledge:read', 'knowledge:source_manage'],
      displayName: 'Maya',
      email: 'maya@example.test',
      role: 'manager',
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init: RequestInit = {}) => {
        const method = init.method ?? 'GET'
        requests.push({ url, method, body: typeof init.body === 'string' ? JSON.parse(init.body) : null })
        return respond(url, method)
      }),
    )
  })

  afterEach(() => {
    clearSession()
    vi.unstubAllGlobals()
  })

  it('searches for eight passages across every source, sending no source filter', async () => {
    respond = () => json({ passages: [], grounded: false })
    const { result } = renderHook(() => useKnowledgeSearch(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ query: 'annual leave' })
    })

    expect(requests).toEqual([
      { url: '/api/knowledge/search', method: 'POST', body: { query: 'annual leave', limit: 8 } },
    ])
  })

  it('limits the search to the sources it is given', async () => {
    respond = () => json({ passages: [], grounded: false })
    const { result } = renderHook(() => useKnowledgeSearch(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ query: 'annual leave', sourceIds: ['s1', 's2'] })
    })

    expect(requests[0]?.body).toEqual({ query: 'annual leave', sourceIds: ['s1', 's2'], limit: 8 })
  })

  it('reads degraded and each passage source, with null where the service leaves a field out', async () => {
    respond = () =>
      json({
        passages: [
          { chunkId: 'c1', documentId: 'd1', sourceId: 's1', documentTitle: 'Leave.pdf', content: 'Text.', score: 1 },
          { chunkId: 'c2', documentId: 'd2', documentTitle: 'Old.pdf', content: 'More.', score: 0.5, pageNumber: 4 },
        ],
        grounded: true,
        degraded: true,
      })
    const { result } = renderHook(() => useKnowledgeSearch(), { wrapper })

    let outcome: Awaited<ReturnType<typeof result.current.mutateAsync>> | undefined
    await act(async () => {
      outcome = await result.current.mutateAsync({ query: 'leave' })
    })

    expect(outcome?.degraded).toBe(true)
    expect(outcome?.grounded).toBe(true)
    expect(outcome?.passages[0]).toMatchObject({ sourceId: 's1', pageNumber: null, heading: null, uri: null })
    expect(outcome?.passages[1]).toMatchObject({ sourceId: null, pageNumber: 4 })
  })

  it('is not degraded when the service says nothing about it', async () => {
    respond = () => json({ passages: [], grounded: false })
    const { result } = renderHook(() => useKnowledgeSearch(), { wrapper })

    let outcome: Awaited<ReturnType<typeof result.current.mutateAsync>> | undefined
    await act(async () => {
      outcome = await result.current.mutateAsync({ query: 'leave' })
    })

    expect(outcome?.degraded).toBe(false)
  })

  it('creates a source with its access chosen at once', async () => {
    respond = () => json(source({ restricted: true }))
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useCreateKnowledgeSource(), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ name: 'HR', kind: 'upload', restricted: true })
    })

    expect(requests).toEqual([
      { url: '/api/sources', method: 'POST', body: { name: 'HR', kind: 'upload', restricted: true } },
    ])
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sources'] })
  })

  it('renames or restricts through PATCH, and shows the change on the cached source at once', async () => {
    client.setQueryData(['sources', 's1'], source({ lastIngestedAt: '2026-10-01T09:00:00Z' }))
    respond = () => json({ id: 's1', name: 'Policies', restricted: true })
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const { result } = renderHook(() => useUpdateSource('s1'), { wrapper })

    await act(async () => {
      await result.current.mutateAsync({ restricted: true })
    })

    expect(requests).toEqual([{ url: '/api/sources/s1', method: 'PATCH', body: { restricted: true } }])
    // Merged over what was cached, so a field the service leaves out is kept.
    expect(client.getQueryData(['sources', 's1'])).toMatchObject({
      restricted: true,
      lastIngestedAt: '2026-10-01T09:00:00Z',
      documentCount: 1,
    })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sources'] })
  })

  it('asks for nothing until a document is chosen', async () => {
    renderHook(() => useDocumentPassages('s1', null), { wrapper })

    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(requests).toEqual([])
  })

  it('reads a document a page at a time, and knows when there are no more', async () => {
    respond = (url) =>
      url.endsWith('page=0')
        ? json({ documentId: 'd1', title: 'Leave.pdf', total: 3, page: 0, size: 2, passages: [{ id: 'p0' }, { id: 'p1' }] })
        : json({ documentId: 'd1', title: 'Leave.pdf', total: 3, page: 1, size: 2, passages: [{ id: 'p2' }] })
    const { result } = renderHook(() => useDocumentPassages('s1', 'd1'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(requests.map((request) => request.url)).toEqual(['/api/sources/s1/documents/d1/chunks?page=0'])
    expect(result.current.hasNextPage).toBe(true)

    await act(async () => {
      await result.current.fetchNextPage()
    })

    expect(requests.map((request) => request.url)).toEqual([
      '/api/sources/s1/documents/d1/chunks?page=0',
      '/api/sources/s1/documents/d1/chunks?page=1',
    ])
    await waitFor(() => expect(result.current.hasNextPage).toBe(false))
    expect(result.current.data?.pages.flatMap((page) => page.passages.map((passage) => passage.id))).toEqual([
      'p0',
      'p1',
      'p2',
    ])
  })
})
