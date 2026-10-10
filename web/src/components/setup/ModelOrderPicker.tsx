// @find: model order, first choice and backup model, primary model, fallback model, choose model, model routing simple, set model policy, provider ready, ModelOrderPicker, setup wizard, save model order
// @what: Simple setup control to pick a first-choice and backup model and save them at the front of the workspace model chain.
// @flow: Used by the Setup page; writes through useSetModelPolicy; the full editor is the Model routing page.
import { useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Notice, Select } from '../ui'
import { CandidateModelField } from '../routing/CandidateModelField'
import { describeApiError } from '../../lib/api'
import { useCredentials, useModelPolicy, useModels, useProviders, useSetModelPolicy } from '../../lib/queries'
import type { ModelPolicy, Provider } from '../../lib/queries'
import { isEmbeddingModel, providerReadiness, recommendModel } from '../../lib/routing'
import { useToast } from '../../lib/toast'

/*
 * The simple way to set the model order: a first choice and a backup, each a provider that is
 * ready and a model from the same searchable picker Model routing uses. Saving puts those two at
 * the front of the workspace chain; the rest of the chain keeps its order behind them.
 * Longer chains, and every other setting, stay on Model routing.
 */

const isSandbox = (provider: Provider) => provider.kind.toUpperCase() === 'SANDBOX'

type Choice = { providerId: string; modelId: string }

// @find: initial model choices, current first and backup model from policy
/** The two choices the policy already starts with, among the providers offered. */
export function initialChoices(policy: ModelPolicy | undefined, offered: readonly Provider[]): [Choice, Choice] {
  const live = (policy?.candidates ?? []).filter((candidate) => offered.some((provider) => provider.id === candidate.providerId))
  const pick = (index: number): Choice =>
    live[index] ? { providerId: live[index].providerId, modelId: live[index].modelId } : { providerId: '', modelId: '' }
  return [pick(0), pick(1)]
}

// @find: build model chain to save, order candidates, sandbox last, model policy candidates
/**
 * The chain to save: the two choices first, then the rest of the old chain in its order, without
 * the chosen models again. The offline sandbox, if it was there, stays last.
 */
export function orderedCandidates(policy: ModelPolicy | undefined, choices: readonly Choice[], sandboxIds: ReadonlySet<string>) {
  const chosen = choices.filter((choice) => choice.providerId && choice.modelId)
  const rest = (policy?.candidates ?? [])
    .filter((candidate) => !chosen.some((choice) => choice.providerId === candidate.providerId && choice.modelId === candidate.modelId))
    .map((candidate) => ({ providerId: candidate.providerId, modelId: candidate.modelId }))
  const live = rest.filter((candidate) => !sandboxIds.has(candidate.providerId))
  const sandbox = rest.filter((candidate) => sandboxIds.has(candidate.providerId))
  return [...chosen, ...live, ...sandbox]
}

// @find: model order picker, pick first and backup model, save model order, setup step
export function ModelOrderPicker() {
  const providersQuery = useProviders()
  const modelsQuery = useModels()
  const policyQuery = useModelPolicy()
  const credentialsQuery = useCredentials()
  const setPolicy = useSetModelPolicy()
  const toast = useToast()

  const providers = providersQuery.data ?? []
  const credentials = credentialsQuery.data ?? []
  const offered = providers.filter((provider) => !isSandbox(provider) && providerReadiness(provider, credentials).ready)
  const sandboxIds = new Set(providers.filter(isSandbox).map((provider) => provider.id))
  const savedModels = (modelsQuery.data ?? []).filter((model) => !isEmbeddingModel(model))

  const [edited, setEdited] = useState<[Choice, Choice] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const choices = edited ?? initialChoices(policyQuery.data, offered)

  if (providersQuery.isLoading || credentialsQuery.isLoading || policyQuery.isLoading) {
    return <p className="muted">Loading the connected models…</p>
  }
  if (offered.length === 0) {
    return <p className="muted">Connect a model first. The models you connect can then be put in order here.</p>
  }

  const update = (index: 0 | 1, next: Partial<Choice>) => {
    setError(null)
    const copy: [Choice, Choice] = [{ ...choices[0] }, { ...choices[1] }]
    copy[index] = { ...copy[index], ...next }
    setEdited(copy)
  }

  const same = choices[0].providerId !== '' && choices[0].providerId === choices[1].providerId && choices[0].modelId === choices[1].modelId

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!choices[0].providerId || !choices[0].modelId) {
      setError('Choose the first choice’s model.')
      return
    }
    if (same) {
      setError('The backup is the same model as the first choice. Pick another, or leave the backup empty.')
      return
    }
    try {
      await setPolicy.mutateAsync({ candidates: orderedCandidates(policyQuery.data, choices, sandboxIds) })
      setEdited(null)
      toast.success('The model order is saved. New runs use it straight away.')
    } catch (failure) {
      setError(describeApiError(failure))
    }
  }

  const row = (index: 0 | 1, title: string) => {
    const choice = choices[index]
    const provider = offered.find((candidate) => candidate.id === choice.providerId)
    return (
      <fieldset className="setup-order-row">
        <legend className="field-label">{title}</legend>
        <Select
          label="Provider"
          value={choice.providerId}
          onChange={(event) =>
            update(index, {
              providerId: event.target.value,
              modelId: event.target.value ? (recommendModel(event.target.value, savedModels)?.modelId ?? '') : '',
            })
          }
        >
          <option value="">{index === 0 ? 'Choose a provider' : 'No backup'}</option>
          {offered.map((candidate) => (
            <option key={candidate.id} value={candidate.id}>
              {candidate.displayName}
            </option>
          ))}
        </Select>
        {provider && (
          <CandidateModelField
            live
            label="Model"
            providerId={provider.id}
            providerName={provider.displayName}
            value={choice.modelId}
            savedName={savedModels.find((model) => model.providerId === provider.id && model.modelId === choice.modelId)?.displayName ?? choice.modelId}
            savedModels={savedModels.filter((model) => model.providerId === provider.id)}
            onChange={(modelId) => update(index, { modelId })}
          />
        )}
      </fieldset>
    )
  }

  return (
    <form className="stack" style={{ gap: 'var(--space-4)' }} onSubmit={(event) => void save(event)} noValidate>
      <div className="setup-order-grid">
        {row(0, 'First choice')}
        {row(1, 'Backup, used when the first choice fails')}
      </div>
      {error && (
        <Notice tone="warning" live>
          {error}
        </Notice>
      )}
      <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap', alignItems: 'center' }}>
        <Button type="submit" loading={setPolicy.isPending} disabled={!edited}>
          Save the order
        </Button>
        <span className="caption">
          These two go first; the rest of the order stays behind them.{' '}
          <a className="link" href="/routing#routing-policy">
            Advanced: Model routing
          </a>
        </span>
      </div>
    </form>
  )
}
