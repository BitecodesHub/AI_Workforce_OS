// @find: knowledge base, knowledge, sources, documents, create source, update source, delete source, delete document, upload file, upload document, ingest, keep both, replace, name clash, try search, search documents, passages, chunks, restricted source, embeddings, POST /api/sources, Knowledge page
// @what: Hooks and helpers that change a knowledge source: create, update, delete, upload documents with a clash choice, try a search, and read a document's passages.
// @flow: Called by Knowledge, SourceDetail, QuickUpload, KnowledgeSearch and DocumentPassagesSheet; calls api() against /api/sources and /api/knowledge/search; reading sources stays in queries.ts.
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { IngestResult, Passage, Source, SourceDocument } from './queries'

/*
 * Changing what a knowledge source holds: uploading with a choice about names already in use,
 * and erasing a document or a whole source. Also trying a search without an agent, reading what a
 * document was cut into, and choosing who may search a source.
 *
 * Reading sources and their documents stays in queries.ts. Every hook here refreshes the source
 * list and the one source it touched (its documents sit under the same key), so the counts on
 * the page never disagree with the list beneath them.
 */

/* ---- How a source is searched, and who may search it ------------------------------------------- */

/** How the service says a source is searched: by words alone, or by words and by meaning. */
export type SearchMode = 'keyword' | 'keyword+meaning'

/** What a source carries that the shared Source type predates: who may search it, and how. */
export type SourceAccess = { restricted?: boolean; searchMode?: SearchMode }

/** Whether only people who manage knowledge can see and search this source. */
export function isRestricted(source: Source): boolean {
  return (source as Source & SourceAccess).restricted === true
}

/**
 * How a source is searched. The service says so; a service that does not yet is read from the
 * embedding provider, because the offline sandbox's vectors carry no meaning.
 */
export function searchModeOf(source: Source): SearchMode {
  const said = (source as Source & SourceAccess).searchMode
  if (said === 'keyword' || said === 'keyword+meaning') return said
  return source.embeddingProvider.trim().toLowerCase() === 'sandbox' ? 'keyword' : 'keyword+meaning'
}

/** What the Knowledge page says where it used to name an offline model and its dimensions. */
export const KEYWORD_SEARCH_NOTE = 'Keyword search. Add an embedding model for search by meaning.'

/**
 * The access line shown when a source is created or uploaded into, in the words the product uses.
 * One sentence with no full stop, so it reads the same as a notice and inside a longer message.
 */
export function sourceAccessNotice(restricted: boolean): string {
  return restricted
    ? 'Only people who manage knowledge can search this source, and everyone else will not see that it exists'
    : 'Everyone with Chat access in this workspace can search this source'
}

/** Said beside a search whose meaning-based half could not run, so fewer passages may have come back. */
export const KEYWORD_ONLY_NOW = 'Keyword search only right now'

// @find: create knowledge source, new source; route: POST /api/sources; used by: Knowledge page, setup QuickUpload
/** Creates a source, choosing at once who may search it, so it is never open to the workspace first. */
export function useCreateKnowledgeSource() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { name: string; kind: string; restricted: boolean }) =>
      api<Source>('/api/sources', { method: 'POST', body: input }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['sources'] }),
  })
}

// @find: update source, rename source, restrict source, who may search; route: PATCH /api/sources/{id}; used by: Source detail page
/**
 * Renames a source, or changes who may search it (knowledge:source_manage). Either field may be
 * left out. Restricting takes effect on the very next search, for everyone.
 */
export function useUpdateSource(sourceId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (input: { name?: string; restricted?: boolean }) =>
      api<Source>(`/api/sources/${sourceId}`, { method: 'PATCH', body: input }),
    onSuccess: (updated) => {
      // Shown at once, so a switch that was just flipped does not flick back while the source is read again.
      client.setQueryData<Source>(['sources', sourceId], (previous) => (previous ? { ...previous, ...updated } : previous))
      client.invalidateQueries({ queryKey: ['sources'] })
    },
  })
}

/* ---- Trying a search ------------------------------------------------------------------------------ */

/** A passage a search found. The shared Passage type predates sourceId, which says where it lives. */
export type FoundPassage = Passage & { sourceId: string | null }

export type SearchOutcome = {
  passages: FoundPassage[]
  /** False when nothing matched, which is an answer rather than a failure. */
  grounded: boolean
  /** True when only keyword search ran, so fewer passages may have come back than usual. */
  degraded: boolean
}

/** How many passages a person trying a search is shown. */
export const TRY_SEARCH_LIMIT = 8

// @find: try search, search documents without an agent; route: POST /api/knowledge/search; used by: Knowledge page (KnowledgeSearch)
/**
 * Searches the documents directly (knowledge:query). No agent runs and no model is paid for, so a
 * person can see what a question finds, and whether the document they expect is findable at all.
 * `sourceIds` limits it to those sources; left out it searches every source the person may read.
 */
export function useKnowledgeSearch() {
  return useMutation({
    mutationFn: async ({ query, sourceIds }: { query: string; sourceIds?: readonly string[] }): Promise<SearchOutcome> => {
      const result = await api<{ passages: FoundPassage[]; grounded: boolean; degraded?: boolean }>(
        '/api/knowledge/search',
        {
          method: 'POST',
          body: { query, ...(sourceIds && sourceIds.length > 0 ? { sourceIds } : {}), limit: TRY_SEARCH_LIMIT },
        },
      )
      return {
        // Null where the service leaves a field out, as the screens compare these with `=== null`.
        passages: result.passages.map((passage) => ({
          ...passage,
          sourceId: passage.sourceId ?? null,
          uri: passage.uri ?? null,
          pageNumber: passage.pageNumber ?? null,
          heading: passage.heading ?? null,
        })),
        grounded: result.grounded,
        degraded: result.degraded === true,
      }
    },
  })
}

/* ---- What a document was cut into ------------------------------------------------------------------ */

/** One passage of a document, in reading order. */
export type DocumentPassage = {
  id: string
  /** Its place in the document, from 0. */
  position: number
  pageNumber?: number | null
  heading?: string | null
  content: string
}

/** One page of a document's passages. */
export type DocumentPassagesPage = {
  documentId: string
  title: string
  /** How many passages the document has in all. */
  total: number
  page: number
  size: number
  passages: DocumentPassage[]
}

// @find: document passages, chunks of a document; route: GET /api/sources/{id}/documents/{docId}/chunks; used by: Source detail page (DocumentPassagesSheet)
/**
 * A document's passages, a page at a time, in reading order (knowledge:read). Nothing is fetched
 * until a document is chosen. The pages sit under the source's key, so an upload or a deletion
 * that refreshes the source refreshes what is open here too.
 */
export function useDocumentPassages(sourceId: string, documentId: string | null) {
  return useInfiniteQuery({
    queryKey: ['sources', sourceId, 'documents', documentId, 'passages'],
    enabled: documentId !== null,
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      api<DocumentPassagesPage>(`/api/sources/${sourceId}/documents/${documentId}/chunks?page=${pageParam}`),
    getNextPageParam: (last) => ((last.page + 1) * last.size < last.total ? last.page + 1 : undefined),
  })
}

/* ---- Uploading and erasing ------------------------------------------------------------------------ */

/**
 * What an upload does with a file whose name the source already holds. `replace` makes it the
 * new version; `keep_both` keeps the old one and the service stores the new one as "name (2).ext".
 */
export type UploadMode = 'replace' | 'keep_both'

/** What one upload returns. Declared here because the shared IngestResult predates 'replaced'. */
export type UploadOutcome = Omit<IngestResult, 'status'> & {
  status: IngestResult['status'] | 'replaced'
  /** The name it is stored under, which differs from the file's when both were kept. */
  title?: string | null
  /** Something worth knowing about it, such as only the first part of a long file being indexed. */
  notice?: string | null
  /** Set when meaning-based search could not be written; the document is findable by keyword. */
  vectorWarning?: string | null
  /** When the version this one replaced was indexed. Only on 'replaced', and on a 'skipped' replacement. */
  replacedIndexedAt?: string | null
}

/** A document row with its notice, which the service sends and the shared type does not yet name. */
export type DocumentRow = SourceDocument & { notice?: string | null }

/** The document's notice, or null. */
export function documentNotice(document: SourceDocument): string | null {
  const notice = (document as DocumentRow).notice
  return typeof notice === 'string' && notice.trim() ? notice : null
}

/**
 * The name the service gives a kept-both upload: "Leave policy (2).pdf". The number goes before
 * the extension; a name with none, or only a leading dot, takes it at the end.
 */
export function numberedName(name: string, n: number): string {
  const dot = name.lastIndexOf('.')
  if (dot <= 0 || dot === name.length - 1) return `${name} (${n})`
  return `${name.slice(0, dot)} (${n})${name.slice(dot)}`
}

/**
 * The name a kept-both upload of `name` will actually get: the first numbered name not
 * already in use, starting at (2), as the service picks it.
 */
export function nextFreeName(name: string, existingTitles: readonly string[]): string {
  const taken = new Set(existingTitles)
  let n = 2
  while (taken.has(numberedName(name, n))) n += 1
  return numberedName(name, n)
}

/** How to treat files whose names are already taken: as new versions, beside the old, or not at all. */
export type ClashChoice = 'replace' | 'keep_both' | 'skip'

/**
 * Names in a batch that would land on a document already in the source, or on another file in the
 * same batch. Compared exactly, as the service compares them. Each name is listed once, in the
 * order first met.
 */
export function findClashes(files: readonly { name: string }[], existingTitles: readonly string[]): string[] {
  const existing = new Set(existingTitles)
  const seen = new Set<string>()
  const clashes: string[] = []
  for (const file of files) {
    if ((existing.has(file.name) || seen.has(file.name)) && !clashes.includes(file.name)) clashes.push(file.name)
    seen.add(file.name)
  }
  return clashes
}

export type PlannedUpload<F> = { file: F; mode: UploadMode }

/**
 * Which files to send, and how, once the person has chosen.
 *
 * - Replace: every file is sent as a new version; two files of one name in a batch end as the later.
 * - Keep both: a file whose name is taken is sent as keep_both, so the service numbers it.
 * - Skip: a file whose name the source already holds is left out, and of several files sharing a
 *   new name only the first is sent.
 */
export function planUploads<F extends { name: string }>(
  files: readonly F[],
  existingTitles: readonly string[],
  choice: ClashChoice,
): { uploads: PlannedUpload<F>[]; skipped: F[] } {
  const existing = new Set(existingTitles)
  const seen = new Set<string>()
  const uploads: PlannedUpload<F>[] = []
  const skipped: F[] = []
  for (const file of files) {
    const taken = existing.has(file.name) || seen.has(file.name)
    seen.add(file.name)
    if (!taken) uploads.push({ file, mode: 'replace' })
    else if (choice === 'skip') skipped.push(file)
    else uploads.push({ file, mode: choice })
  }
  return { uploads, skipped }
}

// @find: upload document, add file to source, replace or keep both; route: POST /api/sources/{id}/documents; used by: Source detail page, setup QuickUpload
export function useUploadKnowledgeDocument(sourceId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ file, mode = 'replace' }: { file: File; mode?: UploadMode }) => {
      const form = new FormData()
      form.append('file', file)
      form.append('mode', mode)
      return api<UploadOutcome>(`/api/sources/${sourceId}/documents`, { method: 'POST', form })
    },
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['sources'] })
      client.invalidateQueries({ queryKey: ['sources', sourceId] })
    },
  })
}

// @find: delete document from source; route: DELETE /api/sources/{id}/documents/{docId}; used by: Source detail page
/** Erases one document: its passages, its vectors and the record of it (knowledge:source_manage). */
export function useDeleteDocument(sourceId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (documentId: string) =>
      api<void>(`/api/sources/${sourceId}/documents/${documentId}`, { method: 'DELETE' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['sources'] })
      client.invalidateQueries({ queryKey: ['sources', sourceId] })
    },
  })
}

// @find: delete knowledge source; route: DELETE /api/sources/{id}; used by: Source detail page
/**
 * Erases a source and every document in it (knowledge:source_manage). The deleted source's own
 * queries are dropped rather than refetched, so the page it was on does not flash "not found"
 * while it navigates away.
 */
export function useDeleteSource() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (sourceId: string) => api<void>(`/api/sources/${sourceId}`, { method: 'DELETE' }),
    onSuccess: (_result, sourceId) => {
      client.removeQueries({ queryKey: ['sources', sourceId] })
      client.invalidateQueries({ queryKey: ['sources'] })
    },
  })
}
