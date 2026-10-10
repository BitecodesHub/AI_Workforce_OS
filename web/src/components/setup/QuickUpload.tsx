// @find: quick upload, add documents during setup, upload files, company documents, create knowledge source, knowledge base setup, first shared source, QuickUpload, drag and drop files, setup wizard
// @what: Setup control that uploads files into the first shared knowledge source, creating one called Company documents if none exists.
// @flow: Used by the Setup page; uses useCreateKnowledgeSource and useUploadKnowledgeDocument; full management is on the Knowledge page.
import { useRef, useState } from 'react'
import { Button, Notice } from '../ui'
import { describeApiError } from '../../lib/api'
import { useCreateKnowledgeSource, useUploadKnowledgeDocument } from '../../lib/knowledgeQueries'
import { useSources } from '../../lib/queries'
import type { Source } from '../../lib/queries'

/*
 * Adding documents from setup: files go into the workspace's first shared source, or into a new
 * one called "Company documents" when there is none, with the same upload Knowledge uses. Picking
 * where things go, and everything else about sources, stays on Knowledge.
 */

export const DEFAULT_SOURCE_NAME = 'Company documents'

// @find: find shared knowledge source, default source for uploads
/** The shared source to add to: the first that is not one agent's own. */
export function sharedSource(sources: readonly Source[] | undefined): Source | undefined {
  return sources?.find((source) => !source.agentId)
}

// @find: quick upload documents, create Company documents source, upload files in setup
export function QuickUpload() {
  const sources = useSources()
  const create = useCreateKnowledgeSource()
  const [created, setCreated] = useState<Source | null>(null)
  const [error, setError] = useState<string | null>(null)
  const target = sharedSource(sources.data) ?? created ?? null

  const ensureSource = async (): Promise<Source | null> => {
    if (target) return target
    try {
      const made = await create.mutateAsync({ name: DEFAULT_SOURCE_NAME, kind: 'upload', restricted: false })
      setCreated(made)
      return made
    } catch (failure) {
      setError(describeApiError(failure))
      return null
    }
  }

  if (sources.isLoading) return <p className="muted">Loading your sources…</p>

  return (
    <div className="stack" style={{ gap: 'var(--space-3)' }}>
      {target ? (
        <Uploader source={target} />
      ) : (
        <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', alignItems: 'center' }}>
          <Button onClick={() => void ensureSource()} loading={create.isPending}>
            Create “{DEFAULT_SOURCE_NAME}”
          </Button>
          <span className="caption">A place for files everyone in the workspace can search. Then add files to it.</span>
        </div>
      )}
      {error && (
        <Notice tone="warning" live>
          {error}
        </Notice>
      )}
      <p className="caption" style={{ margin: 0 }}>
        <a className="link" href="/knowledge">
          Manage sources and documents on Knowledge
        </a>
      </p>
    </div>
  )
}

function Uploader({ source }: { source: Source }) {
  const upload = useUploadKnowledgeDocument(source.id)
  const input = useRef<HTMLInputElement>(null)
  const [results, setResults] = useState<string[]>([])
  const [busy, setBusy] = useState(false)

  async function send(files: File[]) {
    if (files.length === 0) return
    setBusy(true)
    const lines: string[] = []
    for (const file of files) {
      try {
        const outcome = await upload.mutateAsync({ file, mode: 'keep_both' })
        lines.push(
          outcome.status === 'failed'
            ? `${file.name}: could not be read.${outcome.detail ? ` ${outcome.detail}` : ''}`
            : outcome.status === 'skipped'
              ? `${file.name}: skipped.${outcome.detail ? ` ${outcome.detail}` : ''}`
              : `${file.name}: added, ${outcome.chunkCount} ${outcome.chunkCount === 1 ? 'passage' : 'passages'}.`,
        )
      } catch (failure) {
        lines.push(`${file.name}: ${describeApiError(failure)}`)
      }
    }
    setResults(lines)
    setBusy(false)
  }

  return (
    <div className="stack" style={{ gap: 'var(--space-3)' }}>
      <input
        ref={input}
        type="file"
        multiple
        className="dropzone-input"
        tabIndex={-1}
        aria-hidden="true"
        onChange={(event) => {
          const files = Array.from(event.target.files ?? [])
          event.target.value = ''
          void send(files)
        }}
      />
      <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', alignItems: 'center' }}>
        <Button onClick={() => input.current?.click()} loading={busy}>
          Upload files
        </Button>
        <span className="caption">
          Into “{source.name}”, which everyone can search. PDF, Word, text and Markdown files work best.
        </span>
      </div>
      {results.length > 0 && (
        <Notice tone="info" live>
          <ul style={{ margin: 0, paddingLeft: 'var(--space-5)' }}>
            {results.map((line, index) => (
              <li key={index}>{line}</li>
            ))}
          </ul>
        </Notice>
      )}
    </div>
  )
}
