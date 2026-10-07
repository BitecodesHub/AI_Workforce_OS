import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Dialog, Input, Notice, Select } from '../ui'
import { describeApiError } from '../../lib/api'
import {
  useCredentials,
  useModelPolicy,
  useModels,
  useProviders,
  useSetModelPolicy,
  useStoreCredential,
  useToggleProvider,
} from '../../lib/queries'
import type { Model, Provider } from '../../lib/queries'
import {
  connectableProviders,
  connectedSentence,
  planConnect,
  recommendModel,
  type ConnectOutcome,
  type ConnectPlan,
} from '../../lib/routing'
import { useTestProviderKey, type KeyTest } from '../../lib/settingsQueries'

/*
 * Connect your AI.
 *
 * Going live on a real model used to take three separate controls on Model routing - store a key,
 * turn the provider on, add a model to the routing policy - and a mistake in any of them showed
 * only later, as a run that answered with placeholder text. This does all three in one go, and
 * checks the key first: choose a provider, paste the key, and Verify. The key is tried with one
 * small request before anything is saved, so a mistyped one is refused here, in plain words, and
 * nothing is stored.
 *
 * Once the key works it is stored, the provider is turned on for this workspace only, and a
 * recommended model goes into the routing policy (lib/routing.ts: planConnect). A workspace with
 * no policy gets one. A workspace that already has one is asked first, because putting a model in
 * front of a chain somebody built is not this dialog's decision to make alone. The last sentence
 * always says whether the offline sandbox model is still a fallback.
 *
 * Bedrock signs in with AWS credentials and regions, not one key, so it is left to the full page.
 */

type Phase = 'form' | 'working' | 'ask' | 'failed' | 'done'

/** What the steps already did, so that trying again after a failure does not repeat them. */
type Progress = { stored: boolean; enabled: boolean }

type Props = {
  open: boolean
  onClose: () => void
  /** The provider to start on, when the person came from one (a row on Model routing). */
  initialProviderId?: string | undefined
}

/**
 * Each opening is a fresh dialog: nothing typed or decided last time carries over, and a key that
 * was pasted and then abandoned is gone. The inner dialog is keyed by how many times this one has
 * opened, so closing and reopening starts again from the form.
 */
export function ConnectModelDialog(props: Props) {
  const [openings, setOpenings] = useState(0)
  const [wasOpen, setWasOpen] = useState(props.open)
  if (props.open !== wasOpen) {
    setWasOpen(props.open)
    if (props.open) setOpenings((count) => count + 1)
  }
  return <ConnectModelDialogContent key={openings} {...props} />
}

function ConnectModelDialogContent({ open, onClose, initialProviderId }: Props) {
  const providersQuery = useProviders({ enabled: open })
  const modelsQuery = useModels({ enabled: open })
  const policyQuery = useModelPolicy({ enabled: open })
  const credentialsQuery = useCredentials({ enabled: open })
  const testKey = useTestProviderKey()
  const storeCredential = useStoreCredential()
  const toggleProvider = useToggleProvider()
  const setPolicy = useSetModelPolicy()

  const [providerId, setProviderId] = useState<string | null>(null)
  const [keyValue, setKeyValue] = useState('')
  const [phase, setPhase] = useState<Phase>('form')
  /** The check's own verdict when the key was not accepted, in the service's words. */
  const [verdict, setVerdict] = useState<KeyTest | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [plan, setPlan] = useState<{ plan: ConnectPlan; model: Model } | null>(null)
  const [sentence, setSentence] = useState('')
  const progress = useRef<Progress>({ stored: false, enabled: false })
  const statusRef = useRef<HTMLDivElement>(null)

  const choices = connectableProviders(providersQuery.data ?? [])
  const chosen: Provider | undefined =
    choices.find((provider) => provider.id === providerId) ??
    choices.find((provider) => provider.id === initialProviderId) ??
    choices.find((provider) => provider.id === 'openrouter') ??
    choices[0]

  // A phase that replaces the form takes focus, so a screen reader announces it and a keyboard
  // user is not left on a button that is no longer there.
  useEffect(() => {
    if (phase !== 'form') statusRef.current?.focus()
  }, [phase])

  const busy = phase === 'working'
  const key = keyValue.trim()

  async function verifyAndConnect(event?: FormEvent) {
    event?.preventDefault()
    if (!chosen || !key || busy) return
    setError(null)
    setVerdict(null)
    setPhase('working')
    let result: KeyTest
    try {
      result = await testKey.mutateAsync({ providerId: chosen.id, value: key })
    } catch (failure) {
      setPhase('form')
      setError(describeApiError(failure, { value: 'API key' }))
      return
    }
    if (result.result !== 'valid') {
      setVerdict(result)
      setPhase('form')
      return
    }
    await connect(chosen)
  }

  /** Stores the key, turns the provider on and settles the policy. Each step is done once, however often this runs. */
  async function connect(provider: Provider) {
    setPhase('working')
    setError(null)
    try {
      if (!progress.current.stored) {
        if (!provider.credentialRef) throw new Error('No credential reference for this provider.')
        await storeCredential.mutateAsync({ ref: provider.credentialRef, kind: 'api_key', value: key })
        progress.current.stored = true
        // Stored: the key is no longer needed here, and should not sit in the page any longer.
        setKeyValue('')
      }
      if (!provider.enabled && !progress.current.enabled) {
        await toggleProvider.mutateAsync({ id: provider.id, enable: true })
        progress.current.enabled = true
      }

      // Read afresh: the policy and the model list may have changed since the dialog opened.
      const [policy, providers, models] = await Promise.all([
        policyQuery.refetch(),
        providersQuery.refetch(),
        modelsQuery.refetch(),
      ])
      const model = recommendModel(provider.id, models.data ?? [])
      if (!model) {
        finish(provider, undefined, 'no_model', false)
        return
      }
      const decided = planConnect({
        policy: policy.data,
        providers: providers.data ?? [],
        providerId: provider.id,
        modelId: model.modelId,
      })
      if (decided.kind === 'create') {
        await setPolicy.mutateAsync(decided.input)
        finish(provider, model, 'created', false)
      } else if (decided.kind === 'ask') {
        setPlan({ plan: decided, model })
        setPhase('ask')
      } else {
        finish(provider, model, decided.kind === 'already' ? 'already' : 'unchanged', decided.fallsBackToSandbox)
      }
    } catch (failure) {
      setError(describeApiError(failure, { value: 'API key' }))
      setPhase('failed')
    }
  }

  function finish(provider: Provider, model: Model | undefined, outcome: ConnectOutcome, fallsBackToSandbox: boolean) {
    setSentence(
      connectedSentence({
        providerName: provider.displayName,
        ...(model ? { modelName: model.displayName } : {}),
        outcome,
        fallsBackToSandbox,
      }),
    )
    setPhase('done')
  }

  async function answerAsk(addToPolicy: boolean) {
    if (!chosen || !plan || plan.plan.kind !== 'ask') return
    if (!addToPolicy) {
      finish(chosen, plan.model, 'unchanged', plan.plan.fallsBackToSandbox)
      return
    }
    setPhase('working')
    setError(null)
    try {
      await setPolicy.mutateAsync(plan.plan.input)
      finish(chosen, plan.model, 'added', plan.plan.fallsBackToSandbox)
    } catch (failure) {
      setError(describeApiError(failure))
      setPhase('failed')
    }
  }

  const loading = providersQuery.isLoading || modelsQuery.isLoading
  const unavailable = !loading && choices.length === 0

  const askingPlan = plan?.plan.kind === 'ask' ? plan.plan : null
  const askText =
    chosen && plan && askingPlan
      ? `This workspace already has a routing policy. Add ${plan.model.displayName} from ${chosen.displayName} to it? It would be tried after the live models already listed${
          askingPlan.position < (policyQuery.data?.candidates.length ?? 0)
            ? ' and before the offline sandbox model'
            : ''
        }.`
      : ''

  return (
    <Dialog
      open={open}
      onClose={onClose}
      eyebrow="Connect your AI"
      title={phase === 'done' ? 'Your AI is connected' : 'Connect a live AI model'}
      description={
        phase === 'form'
          ? 'Choose a provider and paste your key. It is checked with one small request before anything is saved, so a mistyped key is refused and nothing is stored.'
          : undefined
      }
      dismissible={!busy}
      error={phase === 'failed' ? null : error}
      footer={
        phase === 'form' ? (
          <>
            <Button variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" form="connect-model-form" disabled={!chosen || !key || unavailable}>
              Verify and connect
            </Button>
          </>
        ) : phase === 'ask' ? (
          <>
            <Button variant="outline" onClick={() => void answerAsk(false)}>
              Leave the policy as it is
            </Button>
            <Button onClick={() => void answerAsk(true)}>Add it to the routing policy</Button>
          </>
        ) : phase === 'failed' ? (
          <>
            <Button variant="outline" onClick={onClose}>
              Close
            </Button>
            <Button onClick={() => chosen && void connect(chosen)}>Try again</Button>
          </>
        ) : phase === 'done' ? (
          <>
            <a className="button button-outline" href="/routing">
              Open Model routing
            </a>
            <Button onClick={onClose}>Done</Button>
          </>
        ) : undefined
      }
    >
      {phase === 'form' && (
        <form
          id="connect-model-form"
          onSubmit={(event) => void verifyAndConnect(event)}
          className="stack"
          style={{ gap: 'var(--space-4)' }}
          noValidate
        >
          {unavailable ? (
            <Notice tone="warning">
              No provider can be connected from here yet. Owners and admins can set one up on the Model routing page.
            </Notice>
          ) : (
            <>
              <Select
                label="Provider"
                value={chosen?.id ?? ''}
                onChange={(event) => {
                  setProviderId(event.target.value)
                  setVerdict(null)
                  setError(null)
                }}
                disabled={loading || credentialsQuery.isLoading}
              >
                {choices.map((provider) => (
                  <option key={provider.id} value={provider.id}>
                    {provider.displayName}
                  </option>
                ))}
              </Select>
              <Input
                label="API key"
                type="password"
                value={keyValue}
                onChange={(event) => {
                  setKeyValue(event.target.value)
                  setVerdict(null)
                }}
                autoComplete="off"
                spellCheck={false}
                required
                data-autofocus
                hint={
                  chosen
                    ? `From your ${chosen.displayName} account. It is stored encrypted and never shown again.`
                    : undefined
                }
              />
              {verdict && (
                <Notice tone="warning" live>
                  {verdict.message}
                </Notice>
              )}
            </>
          )}
        </form>
      )}

      {/* The phases that replace the form: one region that takes focus, so the change is announced. */}
      {phase !== 'form' && (
        <div ref={statusRef} tabIndex={-1} role="status" className="stack" aria-live="polite">
          {phase === 'working' && <p className="muted">Checking the key and setting things up. This takes a few seconds.</p>}
          {phase === 'ask' && <p>{askText}</p>}
          {phase === 'failed' && (
            <Notice tone="warning" live>
              {error ?? 'Something went wrong.'} Nothing you already did is repeated when you try again.
            </Notice>
          )}
          {phase === 'done' && <Notice tone="success">{sentence}</Notice>}
        </div>
      )}
    </Dialog>
  )
}
