// @find: candidate model field, model picker, routing candidate, tool-capable models, provider models
// @what: Model field of one candidate in a routing policy.
// @flow: Used by PolicyEditor; uses ModelCombobox.
import React from 'react'
import { NOT_AVAILABLE, firstAvailable, mergeSaved } from '../../lib/modelCatalogue'
import type { CatalogueModel, ProviderCatalogue } from '../../lib/modelCatalogue'
import type { Model } from '../../lib/queries'
import { useProviderCatalogue } from '../../lib/queries'
import { ModelCombobox } from './ModelCombobox'

/*
 * The model field of one candidate in a routing policy: the searchable picker, and one line under
 * it that says where the list came from, with a way to fetch it again.
 *
 * Live, it offers every tool-capable model the provider lists, with the saved models folded in so
 * a model already in the policy is always there. Without a query client (live off), it offers
 * the saved models alone.
 */

export type CandidateModelFieldProps = {
  live: boolean
  label: string
  providerId: string
  providerName: string
  value: string
  /** The saved display name for the value, shown while the list loads. */
  savedName: string
  /** The provider's saved chat models. */
  savedModels: readonly Model[]
  onChange: (modelId: string) => void
  /**
   * True while the model was filled in for the person (a new row, or a provider just picked)
   * rather than chosen. Once the provider's list arrives, a filled-in model it does not offer to
   * this account is swapped for the first one it does.
   */
  autoPicked?: boolean
}

// @find: CandidateModelField, candidate model field, candidate model field, model picker, routing candidate, tool-capable models
export function CandidateModelField(props: CandidateModelFieldProps) {
  return props.live ? <LiveModelField {...props} /> : <SavedModelField {...props} />
}

function SavedModelField({ label, providerId, providerName, value, savedName, savedModels, onChange }: CandidateModelFieldProps) {
  const options = React.useMemo(() => mergeSaved(providerId, undefined, savedModels), [providerId, savedModels])
  return (
    <ModelFieldView
      label={label}
      providerName={providerName}
      value={value}
      savedName={savedName}
      options={options}
      onChange={onChange}
    />
  )
}

function LiveModelField({ label, providerId, providerName, value, savedName, savedModels, onChange, autoPicked }: CandidateModelFieldProps) {
  const catalogue = useProviderCatalogue(providerId)
  const data: ProviderCatalogue | undefined = catalogue.data
  const fromProvider = data !== undefined && data.source !== 'saved'
  const options = React.useMemo(
    () => mergeSaved(providerId, data?.models, savedModels, { fromProvider, keep: value }),
    [providerId, data?.models, savedModels, fromProvider, value],
  )
  const chosen = options.find((option) => option.id === value)
  const replacement = autoPicked && chosen?.unavailable ? firstAvailable(options) : undefined

  // A model filled in from the saved list that this account cannot use is replaced, not left to fail a run.
  React.useEffect(() => {
    if (replacement && replacement.id !== value) onChange(replacement.id)
  }, [replacement, value, onChange])

  const refreshFailed = catalogue.refresh.isError
  const notice = refreshFailed
    ? `Couldn't refresh the list from ${providerName}; showing saved models.`
    : catalogue.isError
      ? `Couldn't load the list from ${providerName}; showing saved models.`
      : chosen?.unavailable
        ? `${NOT_AVAILABLE}: ${providerName} does not offer this model to your key, so runs skip it. Choose another.`
        : (data?.message ?? null)

  const summary = data
    ? `${data.total} ${data.total === 1 ? 'model' : 'models'} that can use tools${data.freeCount > 0 ? `, ${data.freeCount} free` : ''}.`
    : null

  return (
    <ModelFieldView
      label={label}
      providerName={providerName}
      value={value}
      savedName={savedName}
      options={options}
      onChange={onChange}
      loading={catalogue.isPending && !catalogue.isError}
      notice={notice}
      summary={summary}
      onRefresh={() => catalogue.refresh.mutate()}
      refreshing={catalogue.refresh.isPending || (catalogue.isFetching && !catalogue.isPending)}
    />
  )
}

function ModelFieldView({
  label,
  providerName,
  value,
  savedName,
  options,
  onChange,
  loading = false,
  notice = null,
  summary = null,
  onRefresh,
  refreshing = false,
}: {
  label: string
  providerName: string
  value: string
  savedName: string
  options: CatalogueModel[]
  onChange: (modelId: string) => void
  loading?: boolean
  notice?: string | null
  summary?: string | null
  onRefresh?: () => void
  refreshing?: boolean
}) {
  const metaId = React.useId()
  const known = options.some((option) => option.id === value)
  const fallbackLabel = known || loading ? savedName : `${value || 'No model'} (not in the catalogue)`
  const hasMeta = Boolean(onRefresh || notice || summary || loading)

  return (
    <>
      <div className="policy-field">
        <ModelCombobox
          label={label}
          value={value}
          fallbackLabel={fallbackLabel}
          options={options}
          onChange={onChange}
          loading={loading && options.length === 0}
          providerName={providerName}
          describedBy={hasMeta ? metaId : undefined}
        />
      </div>
      {hasMeta && (
        <div id={metaId} className="candidate-meta caption" aria-live="polite">
          <span>
            {loading
              ? `Loading the full list from ${providerName}.`
              : refreshing
                ? `Refreshing the list from ${providerName}.`
                : (notice ?? summary)}
          </span>
          {onRefresh && (
            <button
              type="button"
              className="link-button"
              onClick={onRefresh}
              disabled={refreshing || loading}
              aria-label={`Refresh the model list from ${providerName}`}
            >
              Refresh list
            </button>
          )}
        </div>
      )}
    </>
  )
}
