import React from 'react'
import {
  Button,
  Card,
  ConfirmDialog,
  DataTable,
  Dialog,
  Eyebrow,
  Input,
  Notice,
  PageHeader,
  Select,
  StatRow,
  StatTile,
  Tag,
  Time,
} from '../components/ui'
import type { Column, TagTone } from '../components/ui'
import { QueryState } from '../components/ui/QueryState'
import { describeApiError } from '../lib/api'
import { formatCompactTokens, formatCount, formatMoney } from '../lib/format'
import { providerKindLabel, statusLabel } from '../lib/labels'
import {
  credentialState,
  isEmbeddingModel,
  liveRouting,
  providerReadiness,
  routingSummary,
  type ReadinessReason,
} from '../lib/routing'
import { useToast } from '../lib/toast'
import { useNow } from '../lib/useNow'
import { can } from '../lib/session'
import {
  useCredentials,
  useModelPolicy,
  useModels,
  useProviders,
  useSetModelPolicy,
  useStoreCredential,
  useToggleProvider,
} from '../lib/queries'
import type { CredentialView, Model, ModelPolicy, Provider } from '../lib/queries'

/*
 * Model routing.
 *
 * Which model answers a run, worked out from the same facts the router uses (lib/routing.ts, which
 * the Command Map's banner shares): the workspace routing policy, whether each provider is on,
 * whether it holds a usable key, and whether its circuit breaker has paused it. The page states
 * the outcome in one sentence at the top, then shows each of those facts where it can be changed.
 */

const MAX_CANDIDATES = 10

const isSandbox = (provider: Provider) => provider.kind.toUpperCase() === 'SANDBOX'

const circuitOf = (provider: Provider) => (provider.circuitState ?? '').toUpperCase()

/** Why the router would skip a candidate, as the tag beside it in the policy editor. */
const READINESS_TAG: Record<ReadinessReason, { tone: TagTone; label: string }> = {
  ready: { tone: 'success', label: 'Ready' },
  disabled: { tone: 'neutral', label: 'Provider disabled, will be skipped' },
  no_key: { tone: 'warning', label: 'No key stored, will be skipped' },
  rejected: { tone: 'warning', label: 'Key refused, will be skipped' },
  expired: { tone: 'warning', label: 'Key expired, will be skipped' },
  paused: { tone: 'warning', label: 'Paused after failures' },
}

/** The same reasons, short, after a provider's name in a list of choices. */
const READINESS_SUFFIX: Record<ReadinessReason, string> = {
  ready: '',
  disabled: ' (off)',
  no_key: ' (no key)',
  rejected: ' (key refused)',
  expired: ' (key expired)',
  paused: ' (paused)',
}

/** For the sentence naming live candidates the router passes over before the one it uses. */
const SKIP_REASON: Record<string, string> = {
  disabled: 'turned off',
  no_key: 'no key stored',
  rejected: 'the provider refused its key',
  expired: 'its key has expired',
  paused: 'paused after failures',
  unavailable: 'not available in this workspace',
}

const EXHAUSTED_COPY: Record<string, string> = {
  FAIL_CLOSED: 'If every candidate fails, runs stop with an error.',
  DEGRADE_TO_SANDBOX: 'If every candidate fails, runs fall back to the offline sandbox model.',
}

function storedCredential(provider: Provider, credentials: readonly CredentialView[]): CredentialView | undefined {
  return credentials.find((credential) => credential.ref === provider.credentialRef && credential.present)
}

/** A catalogue price with its exact figure in a title, because a price such as 0.075 rounds on screen. */
function Price({ amount }: { amount: number }) {
  return <span title={`US$${amount} per million tokens`}>{formatMoney(amount)}</span>
}

function YesNo({ value }: { value: boolean }) {
  return <Tag tone={value ? 'success' : 'neutral'}>{value ? 'Yes' : 'No'}</Tag>
}

function Availability({ model, now }: { model: Model; now: number }) {
  const until = model.unavailableUntil ? new Date(model.unavailableUntil).getTime() : Number.NaN
  if (Number.isNaN(until) || until <= now) return <span className="muted">Available</span>
  return (
    <div>
      <Tag tone="warning">Unavailable</Tag>
      <p className="caption">
        Back <Time iso={model.unavailableUntil} />
      </p>
      {model.unavailableReason && <p className="caption">{model.unavailableReason}</p>}
    </div>
  )
}

function modelColumns(embedding: boolean, now: number): Column<Model>[] {
  const dash = <span className="muted">—</span>
  const columns: Column<Model>[] = [
    {
      key: 'model',
      header: 'Model',
      render: (row) => (
        <div>
          <span>{row.displayName}</span>
          <p className="caption mono">{row.modelId}</p>
        </div>
      ),
    },
    {
      key: 'contextWindow',
      header: 'Context window',
      numeric: true,
      render: (row) => <span title={`${formatCount(row.contextWindow)} tokens`}>{formatCompactTokens(row.contextWindow)}</span>,
    },
    {
      key: 'maxOutputTokens',
      header: 'Max output',
      numeric: true,
      render: (row) =>
        embedding ? dash : <span title={`${formatCount(row.maxOutputTokens)} tokens`}>{formatCompactTokens(row.maxOutputTokens)}</span>,
    },
  ]
  if (!embedding) {
    columns.push(
      { key: 'supportsTools', header: 'Tools', render: (row) => <YesNo value={row.supportsTools} /> },
      { key: 'supportsJsonMode', header: 'JSON mode', render: (row) => <YesNo value={row.supportsJsonMode} /> },
      { key: 'supportsStreaming', header: 'Streaming', render: (row) => <YesNo value={row.supportsStreaming} /> },
    )
  }
  columns.push(
    {
      key: 'inputCostPerMillion',
      header: 'Input (per 1M tokens)',
      numeric: true,
      render: (row) => <Price amount={row.inputCostPerMillion} />,
    },
    {
      key: 'outputCostPerMillion',
      header: 'Output (per 1M tokens)',
      numeric: true,
      render: (row) => (embedding ? dash : <Price amount={row.outputCostPerMillion} />),
    },
    { key: 'availability', header: 'Availability', render: (row) => <Availability model={row} now={now} /> },
  )
  return columns
}

/** The state of a provider's key, with the stored key's fingerprint and when it was last used. */
function KeyCell({
  provider,
  credentials,
  failed,
  now,
}: {
  provider: Provider
  credentials: CredentialView[] | undefined
  failed: boolean
  now: number
}) {
  if (!provider.credentialRef) return <span className="muted">Not needed</span>
  if (!credentials) return <span className="muted">{failed ? 'Unknown' : 'Checking…'}</span>
  const state = credentialState(provider, credentials, now)
  if (state === 'not_stored') return <Tag tone="neutral">No key</Tag>
  const stored = storedCredential(provider, credentials)
  return (
    <div>
      <Tag tone={state === 'stored' ? 'success' : 'warning'}>{state === 'expired' ? 'Expired' : 'Key stored'}</Tag>
      {stored?.fingerprint && (
        <p className="caption mono" title="Fingerprint of the stored key">
          {stored.fingerprint}
        </p>
      )}
      <p className="caption">
        {stored?.lastUsedAt ? (
          <>
            Last used <Time iso={stored.lastUsedAt} />
          </>
        ) : (
          'Not used yet'
        )}
      </p>
      {state === 'expired' && stored?.expiresAt && (
        <p className="caption">
          Expired <Time iso={stored.expiresAt} />
        </p>
      )}
      {state === 'rejected' && (
        <p className="caption" style={{ color: 'var(--warning-ink)' }}>
          Rejected by the provider <Time iso={provider.credentialCheckedAt} />
        </p>
      )}
    </div>
  )
}

function RoutingBanner({
  policy,
  providers,
  credentials,
  canManage,
  now,
}: {
  policy: ModelPolicy
  providers: Provider[]
  credentials: CredentialView[]
  canManage: boolean
  now: number
}) {
  const result = liveRouting(policy, providers, credentials, now)
  const summary = routingSummary(result, { canManage })
  // routingSummary names skipped providers only when nothing is ready. When a later candidate is
  // used, this page also says which earlier ones are passed over, and why.
  const skipped = result.live
    ? result.blocked.map((entry) => `${entry.providerName} is skipped: ${SKIP_REASON[entry.reason] ?? 'not available'}.`).join(' ')
    : ''
  // Nothing ready is fixed on a provider (a key, turning it on); anything else in the policy.
  const target = result.reason === 'none_ready' ? { href: '#providers', label: 'Go to the providers' } : { href: '#routing-policy', label: 'Go to the routing policy' }
  return (
    <Notice tone={summary.tone}>
      <span>
        {summary.text}
        {skipped && ` ${skipped}`}
        {summary.linkToRouting && (
          <>
            {' '}
            <a className="link" href={target.href}>
              {target.label}
            </a>
          </>
        )}
      </span>
    </Notice>
  )
}

type DraftCandidate = {
  /** Stable across edits and moves, so a row keeps its fields (and focus) while the list changes. */
  key: string
  providerId: string
  modelId: string
  temperature: number | null
  maxOutputTokens: number | null
}

type MoveFocus = { key: string; direction: 'up' | 'down' }

/**
 * Enabling a provider and storing its key only makes it usable, not chosen - the router still
 * needs an ordered chain to try, or every run keeps resolving to the built-in sandbox default.
 * This edits the workspace-wide chain; an agent can still be given its own in Agent detail.
 */
function RoutingPolicyCard({
  policy,
  providers,
  models,
  credentials,
  canManage,
  now,
}: {
  policy: ModelPolicy
  providers: Provider[]
  models: Model[]
  credentials: CredentialView[] | undefined
  canManage: boolean
  now: number
}) {
  const setPolicy = useSetModelPolicy()
  const toast = useToast()
  const [draft, setDraft] = React.useState<DraftCandidate[] | null>(null)
  const [confirmEmpty, setConfirmEmpty] = React.useState(false)
  const [emptyError, setEmptyError] = React.useState<string | null>(null)
  const newRowCount = React.useRef(0)
  const pendingFocus = React.useRef<MoveFocus | null>(null)
  const listRef = React.useRef<HTMLDivElement>(null)

  const saved = React.useMemo<DraftCandidate[]>(
    () =>
      [...policy.candidates]
        .sort((a, b) => a.position - b.position)
        .map((candidate, index) => ({
          key: `saved-${index}`,
          providerId: candidate.providerId,
          modelId: candidate.modelId,
          temperature: candidate.temperature ?? null,
          maxOutputTokens: candidate.maxOutputTokens ?? null,
        })),
    [policy.candidates],
  )
  const candidates = draft ?? saved
  const dirty = draft !== null

  const byId = React.useMemo(() => new Map(providers.map((provider) => [provider.id, provider])), [providers])
  const chatModels = React.useMemo(() => models.filter((model) => !isEmbeddingModel(model)), [models])
  // Only providers with a model that can answer a run are offered.
  const choosable = React.useMemo(
    () => providers.filter((provider) => chatModels.some((model) => model.providerId === provider.id)),
    [providers, chatModels],
  )

  // After Up or Down, focus stays on the same button in the row that moved, or on its other
  // button when the move has taken the row to the top or bottom.
  React.useEffect(() => {
    const focus = pendingFocus.current
    pendingFocus.current = null
    if (!focus || !listRef.current) return
    const find = (direction: 'up' | 'down') =>
      listRef.current?.querySelector<HTMLButtonElement>(`[data-candidate="${focus.key}"][data-move="${direction}"]`)
    const same = find(focus.direction)
    const target = same && !same.disabled ? same : find(focus.direction === 'up' ? 'down' : 'up')
    target?.focus()
  }, [draft])

  const readinessOf = (providerId: string): ReadinessReason | 'unavailable' | null => {
    const provider = byId.get(providerId)
    if (!provider) return 'unavailable'
    if (!credentials) return null
    return providerReadiness(provider, credentials, now).reason
  }

  const addCandidate = () => {
    const used = new Set(candidates.map((candidate) => `${candidate.providerId}/${candidate.modelId}`))
    const fresh = chatModels.filter((model) => !used.has(`${model.providerId}/${model.modelId}`))
    // A ready live model first, then any model not yet in the chain.
    const pick =
      fresh.find((model) => {
        const provider = byId.get(model.providerId)
        return provider !== undefined && !isSandbox(provider) && readinessOf(model.providerId) === 'ready'
      }) ??
      fresh[0] ??
      chatModels[0]
    if (!pick) return
    newRowCount.current += 1
    setDraft([
      ...candidates,
      { key: `new-${newRowCount.current}`, providerId: pick.providerId, modelId: pick.modelId, temperature: null, maxOutputTokens: null },
    ])
  }

  const updateCandidate = (index: number, change: Partial<DraftCandidate>) =>
    setDraft(candidates.map((candidate, i) => (i === index ? { ...candidate, ...change } : candidate)))

  const removeCandidate = (index: number) => setDraft(candidates.filter((_, i) => i !== index))

  const move = (index: number, direction: 'up' | 'down') => {
    const target = direction === 'up' ? index - 1 : index + 1
    if (target < 0 || target >= candidates.length) return
    const copy = candidates.slice()
    const [item] = copy.splice(index, 1)
    if (!item) return
    copy.splice(target, 0, item)
    pendingFocus.current = { key: item.key, direction }
    setDraft(copy)
  }

  const fieldLabels = Object.fromEntries(
    candidates.flatMap((_, index) => [
      [`candidates[${index}]`, `Candidate ${index + 1}`],
      [`candidates[${index}].providerId`, `Candidate ${index + 1} provider`],
      [`candidates[${index}].modelId`, `Candidate ${index + 1} model`],
    ]),
  )

  /** Saves the chain; resolves to a sentence explaining the failure, or null. */
  const save = async (): Promise<string | null> => {
    try {
      await setPolicy.mutateAsync({
        candidates: candidates.map(({ providerId, modelId, temperature, maxOutputTokens }) => ({
          providerId,
          modelId,
          temperature,
          maxOutputTokens,
        })),
      })
      toast.success('Routing policy saved.')
      setDraft(null)
      return null
    } catch (err) {
      return describeApiError(err, fieldLabels)
    }
  }

  const handleSave = async () => {
    if (candidates.length === 0) {
      setEmptyError(null)
      setConfirmEmpty(true)
      return
    }
    const failure = await save()
    if (failure) toast.error(failure)
  }

  const confirmEmptySave = async () => {
    const failure = await save()
    if (failure) setEmptyError(failure)
    else setConfirmEmpty(false)
  }

  const providerName = (providerId: string) => byId.get(providerId)?.displayName ?? providerId
  const modelName = (candidate: DraftCandidate) =>
    models.find((model) => model.providerId === candidate.providerId && model.modelId === candidate.modelId)?.displayName ??
    candidate.modelId

  const readinessTag = (providerId: string) => {
    const reason = readinessOf(providerId)
    if (reason === null) return null
    if (reason === 'unavailable') return <Tag tone="warning">Not available, will be skipped</Tag>
    const tag = READINESS_TAG[reason]
    return (
      <Tag tone={tag.tone} withDot title={tag.label}>
        {tag.label}
      </Tag>
    )
  }

  const exhausted = EXHAUSTED_COPY[(policy.exhaustedBehaviour ?? '').toUpperCase()] ?? EXHAUSTED_COPY.FAIL_CLOSED

  return (
    <section id="routing-policy" style={{ scrollMarginTop: 'var(--space-7)' }}>
      <Card as="section">
        <Eyebrow as="h2">Routing policy</Eyebrow>
        <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
          The order every agent tries by default, unless it has a chain of its own. With nothing set
          here, those agents answer on the offline sandbox model whatever is enabled above: a
          deliberate default, so no workspace is billed before it chooses to be.
        </p>

        {candidates.length === 0 && (
          <p className="muted" style={{ marginBottom: 'var(--space-4)' }}>
            {dirty
              ? 'No candidates. Saving this sends every run of an agent without its own routing to the offline sandbox model.'
              : policy.configured
                ? 'The saved policy has no candidates, so agents without routing of their own answer on the offline sandbox model.'
                : 'Nothing configured. Agents without routing of their own answer on the offline sandbox model.'}
          </p>
        )}

        {canManage ? (
          <div ref={listRef} className="stack" style={{ gap: 'var(--space-4)', marginBottom: 'var(--space-5)' }}>
            {candidates.map((candidate, index) => {
              const number = index + 1
              const providerModels = chatModels.filter((model) => model.providerId === candidate.providerId)
              const knownProvider = choosable.some((provider) => provider.id === candidate.providerId)
              const knownModel = providerModels.some((model) => model.modelId === candidate.modelId)
              return (
                <div key={candidate.key} className="row candidate-row">
                  <span className="candidate-index mono muted" aria-hidden="true">
                    {number}.
                  </span>
                  <div className="policy-field">
                    <Select
                      label={`Candidate ${number} provider`}
                      value={candidate.providerId}
                      onChange={(event) => {
                        const providerId = event.target.value
                        const first = chatModels.find((model) => model.providerId === providerId)
                        updateCandidate(index, { providerId, modelId: first?.modelId ?? '', maxOutputTokens: null })
                      }}
                    >
                      {!knownProvider && <option value={candidate.providerId}>{`${candidate.providerId} (not available)`}</option>}
                      {choosable.map((provider) => {
                        const reason = credentials ? providerReadiness(provider, credentials, now).reason : 'ready'
                        return (
                          <option key={provider.id} value={provider.id}>
                            {`${provider.displayName}${READINESS_SUFFIX[reason]}`}
                          </option>
                        )
                      })}
                    </Select>
                  </div>
                  <div className="policy-field">
                    <Select
                      label={`Candidate ${number} model`}
                      value={candidate.modelId}
                      onChange={(event) => updateCandidate(index, { modelId: event.target.value, maxOutputTokens: null })}
                    >
                      {!knownModel && <option value={candidate.modelId}>{`${candidate.modelId || 'No model'} (not in the catalogue)`}</option>}
                      {providerModels.map((model) => (
                        <option key={model.modelId} value={model.modelId}>
                          {model.displayName}
                        </option>
                      ))}
                    </Select>
                  </div>
                  <div className="row action-group" style={{ flexWrap: 'wrap', paddingBottom: '4px' }}>
                    {readinessTag(candidate.providerId)}
                    <Button
                      variant="outline"
                      className="button-sm"
                      aria-label={`Move candidate ${number} up`}
                      data-candidate={candidate.key}
                      data-move="up"
                      onClick={() => move(index, 'up')}
                      disabled={index === 0}
                    >
                      Up
                    </Button>
                    <Button
                      variant="outline"
                      className="button-sm"
                      aria-label={`Move candidate ${number} down`}
                      data-candidate={candidate.key}
                      data-move="down"
                      onClick={() => move(index, 'down')}
                      disabled={index === candidates.length - 1}
                    >
                      Down
                    </Button>
                    <Button
                      variant="outline"
                      className="button-sm"
                      aria-label={`Remove candidate ${number}`}
                      onClick={() => removeCandidate(index)}
                    >
                      Remove
                    </Button>
                  </div>
                </div>
              )
            })}
          </div>
        ) : (
          candidates.length > 0 && (
            <ol className="stack" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}>
              {candidates.map((candidate, index) => (
                <li key={candidate.key} className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap' }}>
                  <span className="mono muted" aria-hidden="true">
                    {index + 1}.
                  </span>
                  <span>
                    {providerName(candidate.providerId)} · {modelName(candidate)}
                  </span>
                  {readinessTag(candidate.providerId)}
                </li>
              ))}
            </ol>
          )
        )}

        {candidates.length > 0 && (
          <p className="caption" style={{ marginBottom: 'var(--space-5)' }}>
            {exhausted}
          </p>
        )}

        {canManage && (
          <div className="row" style={{ gap: 'var(--space-3)', flexWrap: 'wrap' }}>
            <Button
              variant="outline"
              onClick={addCandidate}
              disabled={chatModels.length === 0 || candidates.length >= MAX_CANDIDATES}
            >
              Add a candidate
            </Button>
            <Button onClick={() => void handleSave()} loading={setPolicy.isPending && !confirmEmpty} disabled={!dirty}>
              Save routing policy
            </Button>
            {dirty && (
              <Button variant="quiet" onClick={() => setDraft(null)} disabled={setPolicy.isPending}>
                Discard changes
              </Button>
            )}
            {candidates.length >= MAX_CANDIDATES && <span className="caption">A policy holds at most 10 candidates.</span>}
          </div>
        )}
      </Card>

      <ConfirmDialog
        open={confirmEmpty}
        onClose={() => setConfirmEmpty(false)}
        onConfirm={confirmEmptySave}
        eyebrow="Routing policy"
        title="Save an empty routing policy?"
        description="Every run of an agent without routing of its own will answer on the offline sandbox model."
        confirmLabel="Save empty policy"
        cancelLabel="Keep editing"
        tone="primary"
        loading={setPolicy.isPending}
        error={emptyError}
      />
    </section>
  )
}

export function ModelRouting() {
  const providersQuery = useProviders()
  const modelsQuery = useModels()
  const policyQuery = useModelPolicy()
  const credentialsQuery = useCredentials()
  const toggleProvider = useToggleProvider()
  const storeCredential = useStoreCredential()
  const toast = useToast()
  const now = useNow()
  const canManage = can('provider:manage')

  const providers = providersQuery.data
  const models = modelsQuery.data
  const credentials = credentialsQuery.data
  const policy = policyQuery.data

  const [keyTarget, setKeyTarget] = React.useState<Provider | null>(null)
  const [keyValue, setKeyValue] = React.useState('')
  const [keyError, setKeyError] = React.useState<string | null>(null)
  const [enableTarget, setEnableTarget] = React.useState<Provider | null>(null)
  const [enableError, setEnableError] = React.useState<string | null>(null)

  const catalogue = providers && models ? { providers, models } : undefined

  const inPolicy = (provider: Provider) => policy?.candidates.some((candidate) => candidate.providerId === provider.id) ?? false

  const hasStoredKey = (provider: Provider) => {
    if (!credentials) return false
    const state = credentialState(provider, credentials, now)
    return state === 'stored' || state === 'rejected' || state === 'expired'
  }

  const openStoreKey = (provider: Provider) => {
    setKeyTarget(provider)
    setKeyValue('')
    setKeyError(null)
  }

  const closeStoreKey = () => {
    setKeyTarget(null)
    setKeyValue('')
    setKeyError(null)
  }

  const closeEnable = () => {
    setEnableTarget(null)
    setEnableError(null)
  }

  /** Turns a provider on or off; resolves to a sentence explaining the failure, or null. */
  const toggle = async (provider: Provider, enable: boolean, keyMissing = false): Promise<string | null> => {
    try {
      await toggleProvider.mutateAsync({ id: provider.id, enable })
      if (!enable) toast.success(`${provider.displayName} disabled. The router no longer tries it.`)
      else if (keyMissing) toast.success(`${provider.displayName} enabled. The router skips it until a usable key is stored.`)
      else if (!inPolicy(provider)) toast.success(`${provider.displayName} enabled. Add it to the routing policy to use it.`)
      else toast.success(`${provider.displayName} enabled.`)
      return null
    } catch (err) {
      return describeApiError(err)
    }
  }

  const handleToggle = async (provider: Provider) => {
    if (!provider.enabled && credentials) {
      const state = credentialState(provider, credentials, now)
      if (state === 'not_stored' || state === 'expired') {
        setEnableError(null)
        setEnableTarget(provider)
        return
      }
    }
    const failure = await toggle(provider, !provider.enabled)
    if (failure) toast.error(failure)
  }

  const confirmEnable = async () => {
    if (!enableTarget) return
    const failure = await toggle(enableTarget, true, true)
    if (failure) setEnableError(failure)
    else closeEnable()
  }

  const handleStoreKey = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!keyTarget?.credentialRef || !keyValue.trim()) return
    setKeyError(null)
    try {
      const stored = await storeCredential.mutateAsync({ ref: keyTarget.credentialRef, kind: 'api_key', value: keyValue })
      const listed = inPolicy(keyTarget)
      const next = !keyTarget.enabled
        ? listed
          ? ' Enable it so runs can use it.'
          : ' Enable it, then add it to the routing policy below.'
        : listed
          ? ''
          : ' Add it to the routing policy below to use it.'
      const fingerprint = stored.fingerprint ? ` (fingerprint ${stored.fingerprint})` : ''
      toast.success(`Key stored for ${keyTarget.displayName}${fingerprint}.${next}`)
      closeStoreKey()
    } catch (err) {
      setKeyError(describeApiError(err, { value: 'API key', kind: 'Key type' }))
    }
  }

  const providerColumns: Column<Provider>[] = [
    {
      key: 'displayName',
      header: 'Provider',
      render: (row) => (
        <div>
          <span>{row.displayName}</span>
          {row.regions.length > 0 && <p className="caption">Regions, in fallback order: {row.regions.join(', ')}</p>}
        </div>
      ),
    },
    { key: 'kind', header: 'Kind', render: (row) => providerKindLabel(row.kind) },
    {
      key: 'enabled',
      header: 'Status',
      render: (row) => <Tag tone={row.enabled ? 'success' : 'neutral'}>{row.enabled ? 'On' : 'Off'}</Tag>,
    },
    {
      key: 'key',
      header: 'Key',
      render: (row) => <KeyCell provider={row} credentials={credentials} failed={Boolean(credentialsQuery.error)} now={now} />,
    },
    {
      key: 'circuitState',
      header: 'Circuit',
      render: (row) => {
        if (!row.enabled) return <Tag tone="neutral">Off</Tag>
        const circuit = statusLabel('circuit', row.circuitState)
        return <Tag tone={circuit.tone} title={circuit.label}>{circuit.label}</Tag>
      },
    },
    { key: 'modelCount', header: 'Models', numeric: true, render: (row) => formatCount(row.modelCount) },
  ]
  if (canManage) {
    providerColumns.push({
      key: 'actions',
      header: 'Actions',
      render: (row) => {
        const replacing = hasStoredKey(row)
        const toggling = toggleProvider.isPending && toggleProvider.variables?.id === row.id
        return (
          <div className="row action-group">
            {row.credentialRef && (
              <Button
                variant="outline"
                className="button-sm"
                aria-label={`${replacing ? 'Replace key' : 'Store key'} for ${row.displayName}`}
                onClick={() => openStoreKey(row)}
              >
                {replacing ? 'Replace key' : 'Store key'}
              </Button>
            )}
            <Button
              variant="outline"
              className="button-sm"
              aria-label={`${row.enabled ? 'Disable' : 'Enable'} ${row.displayName}`}
              loading={toggling}
              disabled={toggleProvider.isPending && !toggling}
              onClick={() => void handleToggle(row)}
            >
              {row.enabled ? 'Disable' : 'Enable'}
            </Button>
          </div>
        )
      },
    })
  }

  const enableState = enableTarget && credentials ? credentialState(enableTarget, credentials, now) : null
  const replacingKey = keyTarget ? hasStoredKey(keyTarget) : false

  return (
    <div className="page admin-model-routing">
      <PageHeader
        eyebrow="Where the thinking happens"
        title="Model routing"
        description="Agents ask for an answer and state what they need. This chain decides which model provides it, and what happens when one cannot."
      />

      {/* The one-sentence answer to "which model do runs use". It is left out until every fact it
          depends on has loaded; a guess here would be wrong exactly when someone checks it. */}
      {policy && providers && credentials && (
        <RoutingBanner policy={policy} providers={providers} credentials={credentials} canManage={canManage} now={now} />
      )}

      <QueryState
        query={{
          data: catalogue,
          isLoading: providersQuery.isLoading || modelsQuery.isLoading,
          error: providersQuery.error ?? modelsQuery.error,
          refetch: () => {
            void providersQuery.refetch()
            void modelsQuery.refetch()
          },
        }}
        permission="provider:read"
        what="the model providers"
        isEmpty={(data) => data.providers.length === 0}
        empty={
          <div style={{ marginTop: 'var(--space-6)' }}>
            <Card as="section">
              <Eyebrow as="h2">Providers</Eyebrow>
              <p className="muted">No providers are configured for this workspace.</p>
            </Card>
          </div>
        }
        rows={6}
      >
        {({ providers: allProviders, models: allModels }) => {
          const enabled = allProviders.filter((provider) => provider.enabled)
          const enabledIds = new Set(enabled.map((provider) => provider.id))
          const liveProviders = allProviders.filter((provider) => !isSandbox(provider))
          const liveReady = credentials
            ? liveProviders.filter((provider) => providerReadiness(provider, credentials, now).ready).length
            : null
          const healthy = enabled.filter((provider) => circuitOf(provider) === 'CLOSED').length
          const paused = enabled.filter((provider) => ['OPEN', 'FORCED_OPEN'].includes(circuitOf(provider))).length
          const chatModelCount = allModels.filter((model) => !isEmbeddingModel(model) && enabledIds.has(model.providerId)).length

          return (
            <>
              <div style={{ marginTop: 'var(--space-6)' }}>
                <StatRow>
                  <StatTile
                    label="Live providers ready"
                    value={liveReady === null ? '—' : formatCount(liveReady)}
                    unit={`of ${formatCount(liveProviders.length)}`}
                  />
                  <StatTile label="Models available" value={formatCount(chatModelCount)} unit="chat models" />
                  <StatTile label="Healthy" value={formatCount(healthy)} unit={`of ${formatCount(enabled.length)} enabled`} />
                  <StatTile label="Paused" value={formatCount(paused)} unit="after failures" />
                </StatRow>
                <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                  A live provider is ready when it is on, holds a usable key and is not paused. Healthy
                  and paused count providers that are on. Source: the provider registry, stored keys
                  and circuit breaker state.
                </p>
              </div>

              <section id="providers" style={{ marginTop: 'var(--space-7)', scrollMarginTop: 'var(--space-7)' }}>
                <Card as="section">
                  <Eyebrow as="h2">Providers</Eyebrow>
                  <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
                    A provider is paused automatically after repeated failures and probed again once the
                    cool-down passes. One vendor being unwell never stops calls to the others.
                  </p>
                  {credentialsQuery.error ? (
                    <div className="stack" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}>
                      <Notice tone="warning">
                        Stored keys could not be checked, so key status and readiness are not shown.{' '}
                        {describeApiError(credentialsQuery.error)}
                      </Notice>
                      <div>
                        <Button variant="outline" className="button-sm" onClick={() => void credentialsQuery.refetch()}>
                          Try again
                        </Button>
                      </div>
                    </div>
                  ) : null}
                  <DataTable
                    columns={providerColumns}
                    rows={allProviders}
                    getKey={(row) => row.id}
                    caption="Each provider: whether it is on, the state of its key and its circuit breaker."
                  />
                  {!canManage && (
                    <p className="caption" style={{ marginTop: 'var(--space-4)' }}>
                      Turning providers on and storing keys needs a role that can manage model providers.
                      Owners and admins can.
                    </p>
                  )}
                </Card>
              </section>

              {/* A failed policy request shows the failure and a retry here, never "Nothing configured". */}
              <div style={{ marginTop: 'var(--space-6)' }}>
                <QueryState query={policyQuery} permission="provider:read" what="the routing policy" rows={3}>
                  {(loadedPolicy) => (
                    <RoutingPolicyCard
                      policy={loadedPolicy}
                      providers={allProviders}
                      models={allModels}
                      credentials={credentials}
                      canManage={canManage}
                      now={now}
                    />
                  )}
                </QueryState>
              </div>

              <section style={{ marginTop: 'var(--space-6)' }}>
                <Card as="section">
                  <Eyebrow as="h2">Model catalogue</Eyebrow>
                  <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
                    Models in the platform catalogue for each provider. Costs are per million tokens, in USD.
                  </p>
                  {allProviders.map((provider) => {
                    const providerModels = allModels.filter((model) => model.providerId === provider.id)
                    if (providerModels.length === 0) return null
                    const chat = providerModels.filter((model) => !isEmbeddingModel(model))
                    const embedding = providerModels.filter((model) => isEmbeddingModel(model))
                    return (
                      <div key={provider.id} style={{ marginBottom: 'var(--space-6)' }}>
                        <h3 style={{ fontSize: 'var(--text-body)', fontWeight: 'var(--weight-strong)', marginBottom: 'var(--space-3)' }}>
                          {provider.displayName}{' '}
                          <span className="caption" style={{ fontWeight: 'var(--weight-regular)' }}>
                            {providerKindLabel(provider.kind)}
                            {provider.enabled ? '' : ', turned off'}
                          </span>
                        </h3>
                        {chat.length > 0 && (
                          <DataTable
                            columns={modelColumns(false, now)}
                            rows={chat}
                            getKey={(row) => row.modelId}
                            caption={`Chat models from ${provider.displayName}`}
                          />
                        )}
                        {embedding.length > 0 && (
                          <>
                            <h4 className="caption" style={{ margin: 'var(--space-4) 0 var(--space-2)' }}>
                              Embedding models, used for document search rather than for answering runs
                            </h4>
                            <DataTable
                              columns={modelColumns(true, now)}
                              rows={embedding}
                              getKey={(row) => row.modelId}
                              caption={`Embedding models from ${provider.displayName}`}
                            />
                          </>
                        )}
                      </div>
                    )
                  })}
                </Card>
              </section>
            </>
          )
        }}
      </QueryState>

      {/* Before the key dialog, so that when "Store a key first" swaps one for the other, this one
          has closed before the key dialog opens and takes focus. */}
      <ConfirmDialog
        open={enableTarget !== null}
        onClose={closeEnable}
        onConfirm={confirmEnable}
        eyebrow="No usable key"
        title={`Enable ${enableTarget?.displayName ?? 'this provider'} without a usable key?`}
        description={
          enableState === 'expired'
            ? 'Its key has expired, so the router will skip it until the key is replaced.'
            : 'No key is stored for it, so the router will skip it until one is added.'
        }
        confirmLabel="Enable anyway"
        cancelLabel="Cancel"
        tone="primary"
        loading={toggleProvider.isPending}
        error={enableError}
      >
        {enableTarget?.credentialRef ? (
          <Button
            variant="quiet"
            className="button-sm"
            onClick={() => {
              const provider = enableTarget
              closeEnable()
              openStoreKey(provider)
            }}
          >
            Store a key first
          </Button>
        ) : null}
      </ConfirmDialog>

      <Dialog
        open={keyTarget !== null}
        onClose={closeStoreKey}
        eyebrow={replacingKey ? 'Replace a key' : 'Store a key'}
        title={`${replacingKey ? 'Replace the key' : 'Add a key'} for ${keyTarget?.displayName ?? 'this provider'}`}
        description="The key is encrypted at rest and never shown again. A short fingerprint is kept so you can tell keys apart."
        dismissible={!storeCredential.isPending}
        error={keyError}
        footer={
          <>
            <Button variant="outline" onClick={closeStoreKey} disabled={storeCredential.isPending}>
              Cancel
            </Button>
            <Button
              variant="primary"
              type="submit"
              form="store-key-form"
              loading={storeCredential.isPending}
              disabled={!keyValue.trim()}
            >
              Store key
            </Button>
          </>
        }
      >
        <form id="store-key-form" onSubmit={handleStoreKey}>
          <Input
            label="API key"
            type="password"
            value={keyValue}
            onChange={(event) => setKeyValue(event.target.value)}
            autoComplete="off"
            spellCheck={false}
            required
            data-autofocus
            hint={replacingKey ? 'The new key replaces the stored one as soon as you store it.' : undefined}
          />
        </form>
      </Dialog>
    </div>
  )
}
