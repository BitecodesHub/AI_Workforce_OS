// @find: embedding model, embeddings settings, search by meaning, vector search, choose embedding, re-index, knowledge settings, Knowledge page
// @what: Card where a workspace chooses the embedding model for document search.
// @flow: Used by the Knowledge page.
import { useState } from 'react'
import { Button, Card, Eyebrow, Notice, Select } from '../ui'
import { describeApiError } from '../../lib/api'
import {
  KEYWORD_ONLY_VALUE,
  groupByProvider,
  isReindexing,
  optionValue,
  parseOptionValue,
  progressShare,
  useChooseEmbedding,
  useEmbeddingModels,
  useEmbeddingStatus,
  useResumeReindex,
} from '../../lib/embeddingQueries'
import type { EmbeddingStatus } from '../../lib/embeddingQueries'
import { formatCount } from '../../lib/format'
import { embeddingProviderLabel } from '../../lib/labels'
import { can } from '../../lib/session'
import { useToast } from '../../lib/toast'

// @find: currentSearchSentence, current search sentence, embedding model, embeddings settings, search by meaning, vector search
/** The sentence that says how documents are searched now. */
export function currentSearchSentence(status: EmbeddingStatus): string {
  if (status.searchMode === 'keyword') {
    return 'Documents are searched by keyword only. Choose an embedding model to also find passages that mean the same thing in other words.'
  }
  return `Documents are searched by keyword and by meaning, with ${status.model} from ${embeddingProviderLabel(status.provider)} (${formatCount(status.dimension)} dimensions).`
}

// @find: EmbeddingSettingsCard, embedding settings card, embedding model, embeddings settings, search by meaning, vector search
/**
 * Where a workspace chooses the embedding model its documents are searched by meaning with. The
 * models offered are read live from each provider that has a key stored; choosing one tries it
 * first, then embeds every stored passage again in the background while keyword search goes on.
 */
export function EmbeddingSettingsCard() {
  const statusQuery = useEmbeddingStatus()
  const canManage = can('knowledge:source_manage')
  const canListModels = can('provider:read')
  const modelsQuery = useEmbeddingModels(canManage && canListModels)
  const choose = useChooseEmbedding()
  const resume = useResumeReindex()
  const { success } = useToast()
  // What the person picked; until they pick, the picker shows the current model.
  const [picked, setPicked] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const status = statusQuery.data
  const current = status ? optionValue(status.provider, status.model) : ''
  const selected = picked ?? current

  if (!status) return null
  const running = isReindexing(status)
  const progress = status.progress

  async function apply() {
    const { providerId, modelId } = parseOptionValue(selected)
    setError(null)
    try {
      await choose.mutateAsync({ providerId, modelId })
      success(
        providerId === KEYWORD_ONLY_VALUE
          ? 'Documents will be searched by keyword only.'
          : 'The model works. Passages are being embedded again; keyword search carries on meanwhile.',
      )
    } catch (err) {
      setError(describeApiError(err))
    }
  }

  async function retry() {
    setError(null)
    try {
      await resume.mutateAsync()
    } catch (err) {
      setError(describeApiError(err))
    }
  }

  const groups = groupByProvider(modelsQuery.data?.models ?? [])
  const listedCurrent =
    current === KEYWORD_ONLY_VALUE || groups.some((group) => group.models.some((m) => optionValue(m.providerId, m.id) === current))

  return (
    <Card as="section">
      <Eyebrow as="h2">Search by meaning</Eyebrow>
      <p style={{ marginTop: 'var(--space-2)' }}>{currentSearchSentence(status)}</p>

      {running && (
        <div className="stack" style={{ gap: 'var(--space-2)', marginTop: 'var(--space-3)' }}>
          <progress
            value={progressShare(progress)}
            max={1}
            aria-label="Passages embedded again so far"
            style={{ width: '100%', height: 10, accentColor: 'var(--chart-primary)' }}
          />
          <p className="caption" style={{ margin: 0 }} role="status">
            {progress.message ?? 'Re-indexing.'}
          </p>
        </div>
      )}
      {!running && progress.state === 'done' && progress.message && (
        <p className="caption" style={{ marginTop: 'var(--space-2)' }}>
          {progress.message}
        </p>
      )}
      {!running && (progress.state === 'failed' || progress.state === 'interrupted') && (
        <div style={{ marginTop: 'var(--space-3)' }}>
          <Notice tone="warning">{progress.message ?? 'Re-indexing did not finish. Keyword search still works.'}</Notice>
          {canManage && (
            <Button variant="outline" onClick={retry} loading={resume.isPending} style={{ marginTop: 'var(--space-3)' }}>
              Re-index again
            </Button>
          )}
        </div>
      )}

      {canManage && canListModels && (
        <form
          className="stack"
          style={{ gap: 'var(--space-3)', marginTop: 'var(--space-4)' }}
          onSubmit={(event) => {
            event.preventDefault()
            void apply()
          }}
        >
          <Select
            label="Embedding model"
            value={selected}
            onChange={(event) => {
              setPicked(event.target.value)
              setError(null)
            }}
            disabled={running || modelsQuery.isLoading}
            hint={
              modelsQuery.isLoading
                ? 'Reading the embedding models your providers offer.'
                : 'Only providers with a key stored in Model routing are listed. Changing the model embeds every passage again.'
            }
            error={error}
          >
            <option value={KEYWORD_ONLY_VALUE}>Keyword search only (no embedding model)</option>
            {!listedCurrent && current && <option value={current}>{status.model} (current)</option>}
            {groups.map((group) => (
              <optgroup key={group.providerName} label={group.providerName}>
                {group.models.map((model) => (
                  <option key={`${model.providerId}/${model.id}`} value={optionValue(model.providerId, model.id)}>
                    {model.displayName}
                    {model.free ? ' (free)' : ''}
                  </option>
                ))}
              </optgroup>
            ))}
          </Select>
          {(modelsQuery.data?.notes ?? []).map((note) => (
            <p key={note} className="caption" style={{ margin: 0 }}>
              {note}
            </p>
          ))}
          {modelsQuery.isError && (
            <p className="caption" style={{ margin: 0 }}>
              The embedding models could not be read just now.
            </p>
          )}
          <div>
            <Button type="submit" disabled={running || !selected || selected === current} loading={choose.isPending}>
              Use this model
            </Button>
          </div>
        </form>
      )}
      {canManage && !canListModels && (
        <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
          Someone who can see Model routing can change the embedding model.
        </p>
      )}
    </Card>
  )
}
