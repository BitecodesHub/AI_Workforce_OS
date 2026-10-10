// @find: model routing, models, providers, connect your AI, API key, routing policy, fallback, failover, test now, prices, catalogue, NVIDIA NIM, OpenAI, Bedrock, workspace model chain, /routing, Model routing page
// @what: The Model routing page: connect AI providers, see their models and prices, test them and set the order models are tried in.
// @flow: Routed from App.tsx at /routing; opens with ?connect=1 to show the Connect your AI dialog
import React from 'react'
import {
  Button,
  Card,
  ConfirmDialog,
  DataTable,
  Dialog,
  Eyebrow,
  Notice,
  PageHeader,
  PasswordInput,
  StatRow,
  StatTile,
  Tag,
  Time,
} from '../components/ui'
import type { Column } from '../components/ui'
import { ConnectModelDialog } from '../components/onboarding/ConnectModelDialog'
import { isBedrock } from '../lib/bedrock'
import { cleanKey, keyMismatch } from '../lib/keyFormats'
import { PolicyEditor } from '../components/routing/PolicyEditor'
import { TestNow } from '../components/routing/TestNow'
import { QueryState } from '../components/ui/QueryState'
import { describeApiError } from '../lib/api'
import { formatCompactTokens, formatCount, formatMoney } from '../lib/format'
import { providerKindLabel } from '../lib/labels'
import {
  credentialState,
  isEmbeddingModel,
  liveRouting,
  offForPlatform,
  providerReadiness,
  routingSummary,
  toggleCopy,
  toggledMessage,
  toggleRefusal,
  type ToggleRefusal,
} from '../lib/routing'
import { useRouter } from '../lib/router'
import { fetchRoutingUsage, useRemoveFromAllRouting, usageSentence, warningOf } from '../lib/routingActions'
import type { RoutingUsage } from '../lib/routingActions'
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
 * the Command Map's banner shares): the workspace routing policy, whether each provider is on and
 * whether it holds a usable key. Nothing is ever set aside or paused after a failure: every run
 * tries every listed model again, and "Test now" finds out at once instead of on the next run.
 * The page states the outcome in one sentence at the top, then shows each fact where it can be
 * changed.
 *
 * All of it is this workspace's own. Turning a provider on or off and a refused key stay in this
 * workspace; the copy says so wherever a change is made.
 *
 * "Connect your AI" is the short way through for somebody who just wants a live model: it checks a
 * key, stores it, turns the provider on and puts a model in the policy in one go. It opens from
 * the header button and from /routing?connect=1, which the getting-started guide, the Command Map
 * and the end of workspace setup link to. Everything it does can still be done piece by piece below.
 */

const isSandbox = (provider: Provider) => provider.kind.toUpperCase() === 'SANDBOX'

/** For the sentence naming live candidates the router passes over before the one it uses. */
const SKIP_REASON: Record<string, string> = {
  disabled: 'turned off for this workspace',
  platform_off: 'not offered on this installation',
  no_key: 'no key stored',
  expired: 'its key has expired',
  unavailable: 'not available in this workspace',
}

function storedCredential(provider: Provider, credentials: readonly CredentialView[]): CredentialView | undefined {
  return credentials.find((credential) => credential.ref === provider.credentialRef && credential.present)
}

/** A catalogue price with its exact figure in a title, because a price such as 0.075 rounds on screen. */
// @find: model price display
function Price({ amount }: { amount: number }) {
  return <span title={`US$${amount} per million tokens`}>{formatMoney(amount)}</span>
}

// @find: yes or no cell
function YesNo({ value }: { value: boolean }) {
  return <Tag tone={value ? 'success' : 'neutral'}>{value ? 'Yes' : 'No'}</Tag>
}

function modelColumns(embedding: boolean, canManage: boolean): Column<Model>[] {
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
  )
  // Every model is tried on every run; a test only tells you now how one is doing.
  if (canManage && !embedding) {
    columns.push({
      key: 'test',
      header: 'Check',
      render: (row) => <TestNow providerId={row.providerId} modelId={row.modelId} name={row.displayName} />,
    })
  }
  return columns
}

/** The state of a provider's key, with the stored key's fingerprint and when it was last used. */
// @find: provider key cell, API key status, replace key
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
          This workspace's key was refused by the provider <Time iso={provider.credentialCheckedAt} />. It is
          still tried on every run.
        </p>
      )}
    </div>
  )
}

// @find: routing banner, sandbox mode warning, connect your AI prompt
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

/**
 * The workspace-wide chain: what every agent without one of its own tries, in order. The editor is
 * shared with an agent's own page (components/routing/PolicyEditor.tsx); this is where it is saved.
 */
// @find: routing policy card, workspace model order, PUT /api/model-policy
function RoutingPolicyCard({
  policy,
  providers,
  models,
  credentials,
  canManage,
  now,
  onRemoveEverywhere,
}: {
  policy: ModelPolicy
  providers: Provider[]
  models: Model[]
  credentials: CredentialView[] | undefined
  canManage: boolean
  now: number
  onRemoveEverywhere: (target: { providerId: string; modelId: string; name: string }) => void
}) {
  const setPolicy = useSetModelPolicy()
  return (
    <PolicyEditor
      scope="workspace"
      policy={policy}
      providers={providers}
      models={models}
      credentials={credentials}
      canManage={canManage}
      now={now}
      onSave={(input) => setPolicy.mutateAsync(input)}
      liveCatalogue
      testable={canManage}
      onRemoveEverywhere={canManage ? onRemoveEverywhere : undefined}
    />
  )
}

// @find: ModelRouting component, model routing page, connect provider, test now, routing policy, /routing
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
  const { search, navigate } = useRouter()

  // /routing?connect=1 opens the Connect your AI dialog. The parameter is taken out of the
  // address once acted on, so a reload or Back does not open it again. Anyone who cannot manage
  // providers sees the page as it always was.
  const wantsConnect = search.get('connect') === '1' && canManage
  const [connectOpen, setConnectOpen] = React.useState(wantsConnect)
  /** The provider the Connect dialog opens on, when a row's own button opened it (Bedrock's fields live there). */
  const [connectProviderId, setConnectProviderId] = React.useState<string | undefined>(undefined)
  // Arriving at the same page again with ?connect=1 (a link inside the page) opens it again too.
  const [couldConnect, setCouldConnect] = React.useState(wantsConnect)
  if (wantsConnect !== couldConnect) {
    setCouldConnect(wantsConnect)
    if (wantsConnect) setConnectOpen(true)
  }
  React.useEffect(() => {
    if (!wantsConnect) return
    const rest = new URLSearchParams(search)
    rest.delete('connect')
    const query = rest.toString()
    navigate(`/routing${query ? `?${query}` : ''}${window.location.hash}`, { replace: true })
  }, [wantsConnect, search, navigate])

  const providers = providersQuery.data
  const models = modelsQuery.data
  const credentials = credentialsQuery.data
  const policy = policyQuery.data

  const [keyTarget, setKeyTarget] = React.useState<Provider | null>(null)
  const [keyValue, setKeyValue] = React.useState('')
  const [keyError, setKeyError] = React.useState<string | null>(null)
  const [enableTarget, setEnableTarget] = React.useState<Provider | null>(null)
  const [enableError, setEnableError] = React.useState<string | null>(null)
  // A 409 from the toggle: the service explains why, and the page shows it beside the providers.
  const [refusal, setRefusal] = React.useState<(ToggleRefusal & { providerId: string }) | null>(null)

  // The service's sentence after turning a provider off: the chains now left with nothing on.
  const [offWarning, setOffWarning] = React.useState<string | null>(null)

  // "Remove from all routing": who lists it is read first, then the person confirms.
  const removeEverywhere = useRemoveFromAllRouting()
  const [removeTarget, setRemoveTarget] = React.useState<{
    providerId: string
    modelId?: string
    name: string
    usage: RoutingUsage
  } | null>(null)
  const [removeError, setRemoveError] = React.useState<string | null>(null)
  const [checkingUsage, setCheckingUsage] = React.useState<string | null>(null)
  const [removalWarning, setRemovalWarning] = React.useState<string | null>(null)

  const askRemoveEverywhere = async (target: { providerId: string; modelId?: string; name: string }) => {
    const key = `${target.providerId}/${target.modelId ?? ''}`
    setCheckingUsage(key)
    try {
      const usage = await fetchRoutingUsage(target.providerId, target.modelId)
      setRemoveError(null)
      setRemoveTarget({ ...target, usage })
    } catch (err) {
      toast.error(describeApiError(err))
    } finally {
      setCheckingUsage(null)
    }
  }

  const confirmRemoveEverywhere = async () => {
    if (!removeTarget) return
    setRemoveError(null)
    try {
      const result = await removeEverywhere.mutateAsync({
        providerId: removeTarget.providerId,
        ...(removeTarget.modelId ? { modelId: removeTarget.modelId } : {}),
      })
      setRemovalWarning(warningOf(result))
      toast.success(`${removeTarget.name} was removed from all routing.`)
      setRemoveTarget(null)
    } catch (err) {
      setRemoveError(describeApiError(err))
    }
  }

  /** Closes the refusal and returns focus to the button that raised it, rather than to the page. */
  const dismissRefusal = () => {
    const providerId = refusal?.providerId
    setRefusal(null)
    if (providerId) {
      requestAnimationFrame(() =>
        document.querySelector<HTMLButtonElement>(`[data-provider-toggle="${CSS.escape(providerId)}"]`)?.focus(),
      )
    }
  }

  const catalogue = providers && models ? { providers, models } : undefined

  const inPolicy = (provider: Provider) => policy?.candidates.some((candidate) => candidate.providerId === provider.id) ?? false

  const hasStoredKey = (provider: Provider) => {
    if (!credentials) return false
    const state = credentialState(provider, credentials, now)
    return state === 'stored' || state === 'rejected' || state === 'expired'
  }

  const openStoreKey = (provider: Provider) => {
    // Bedrock is several fields and a region, which the Connect dialog asks for and checks.
    if (isBedrock(provider)) {
      setConnectProviderId(provider.id)
      setConnectOpen(true)
      return
    }
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

  /**
   * Turns a provider on or off for this workspace only; resolves to the error, or null. Other
   * workspaces keep their own setting, so the confirmation says the change stays here.
   */
  const toggle = async (provider: Provider, enable: boolean, keyMissing = false): Promise<unknown> => {
    try {
      const result = await toggleProvider.mutateAsync({ id: provider.id, enable })
      setRefusal(null)
      // Turning a provider off is never refused; the service says which chains it leaves empty.
      setOffWarning(enable ? null : warningOf(result))
      toast.success(toggledMessage(provider, enable, { keyMissing, inPolicy: inPolicy(provider) }))
      return null
    } catch (err) {
      return err
    }
  }

  const handleToggle = async (provider: Provider) => {
    const enabling = !provider.enabled
    if (enabling && credentials) {
      const state = credentialState(provider, credentials, now)
      if (state === 'not_stored' || state === 'expired') {
        setEnableError(null)
        setEnableTarget(provider)
        return
      }
    }
    const failure = await toggle(provider, enabling)
    if (!failure) return
    // A refusal (409) says what to change first, so it stays on the page instead of a toast that
    // disappears before it has been read.
    const refused = toggleRefusal(failure, enabling)
    if (refused) setRefusal({ ...refused, providerId: provider.id })
    else toast.error(describeApiError(failure))
  }

  const confirmEnable = async () => {
    if (!enableTarget) return
    const failure = await toggle(enableTarget, true, true)
    if (failure) setEnableError(describeApiError(failure))
    else closeEnable()
  }

  const handleStoreKey = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!keyTarget?.credentialRef || !keyValue.trim()) return
    setKeyError(null)
    try {
      const stored = await storeCredential.mutateAsync({ ref: keyTarget.credentialRef, kind: 'api_key', value: keyValue })
      const listed = inPolicy(keyTarget)
      const next = offForPlatform(keyTarget)
        ? ' It is not available yet, so runs cannot use it. Contact support to have it offered.'
        : !keyTarget.enabled
          ? listed
            ? ' Turn it on for this workspace so runs can use it.'
            : ' Turn it on for this workspace, then add it to the routing policy below.'
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
          {isBedrock(row) ? (
            <p className="caption">The AWS region is chosen with its credentials.</p>
          ) : (
            row.regions.length > 0 && <p className="caption">Regions, in fallback order: {row.regions.join(', ')}</p>
          )}
        </div>
      ),
    },
    { key: 'kind', header: 'Kind', render: (row) => providerKindLabel(row.kind) },
    {
      key: 'enabled',
      header: 'In this workspace',
      render: (row) => (
        <div>
          <Tag tone={row.enabled ? 'success' : 'neutral'}>{row.enabled ? 'On' : 'Off'}</Tag>
          {offForPlatform(row) && <p className="caption">Not available yet</p>}
        </div>
      ),
    },
    {
      key: 'key',
      header: "This workspace's key",
      render: (row) => <KeyCell provider={row} credentials={credentials} failed={Boolean(credentialsQuery.error)} now={now} />,
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
        const copy = toggleCopy(row)
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
            {offForPlatform(row) ? (
              <span className="caption">Contact support to have it offered</span>
            ) : (
              <Button
                variant="outline"
                className="button-sm"
                aria-label={copy.ariaLabel}
                title={copy.ariaLabel}
                data-provider-toggle={row.id}
                loading={toggling}
                disabled={toggleProvider.isPending && !toggling}
                onClick={() => void handleToggle(row)}
              >
                {copy.label}
              </Button>
            )}
            <TestNow providerId={row.id} name={row.displayName} />
            <Button
              variant="quiet"
              className="button-sm"
              aria-label={`Remove every ${row.displayName} model from all routing`}
              loading={checkingUsage === `${row.id}/`}
              onClick={() => void askRemoveEverywhere({ providerId: row.id, name: row.displayName })}
            >
              Remove from all routing
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
        action={
          canManage ? (
            <Button
              onClick={() => {
                setConnectProviderId(undefined)
                setConnectOpen(true)
              }}
            >
              Connect your AI
            </Button>
          ) : undefined
        }
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
                  <StatTile label="Providers on" value={formatCount(enabled.length)} unit={`of ${formatCount(allProviders.length)}`} />
                </StatRow>
                <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                  A live provider is ready when it is on and holds a usable key. Every listed model is
                  tried on every run, even one that failed last time. Source: the provider registry and
                  stored keys.
                </p>
              </div>

              <section id="providers" style={{ marginTop: 'var(--space-7)', scrollMarginTop: 'var(--space-7)' }}>
                <Card as="section">
                  <Eyebrow as="h2">Providers</Eyebrow>
                  <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
                    Turning a provider on or off, and the keys stored here, apply to this workspace only.
                    A failure never takes a provider out of use: the next run tries it again. Use Test
                    now to check one straight away.
                  </p>
                  {offWarning && (
                    <div className="stack" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}>
                      <Notice tone="warning" live>
                        {offWarning}
                      </Notice>
                      <div>
                        <Button variant="quiet" className="button-sm" onClick={() => setOffWarning(null)}>
                          Dismiss
                        </Button>
                      </div>
                    </div>
                  )}
                  {refusal && (
                    <div className="stack" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}>
                      <Notice tone="warning" live>
                        <span>
                          {refusal.text}
                          {refusal.fixIn === 'routing-policy' && (
                            <>
                              {' '}
                              <a className="link" href="#routing-policy">
                                Go to the routing policy
                              </a>
                            </>
                          )}
                        </span>
                      </Notice>
                      <div>
                        <Button variant="quiet" className="button-sm" onClick={dismissRefusal}>
                          Dismiss
                        </Button>
                      </div>
                    </div>
                  )}
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
                    caption="Each provider: whether it is on in this workspace and the state of this workspace's key."
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
                {removalWarning && (
                  <div className="stack" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}>
                    <Notice tone="warning" live>
                      {removalWarning}
                    </Notice>
                    <div>
                      <Button variant="quiet" className="button-sm" onClick={() => setRemovalWarning(null)}>
                        Dismiss
                      </Button>
                    </div>
                  </div>
                )}
                <QueryState query={policyQuery} permission="provider:read" what="the routing policy" rows={3}>
                  {(loadedPolicy) => (
                    <RoutingPolicyCard
                      policy={loadedPolicy}
                      providers={allProviders}
                      models={allModels}
                      credentials={credentials}
                      canManage={canManage}
                      now={now}
                      onRemoveEverywhere={(target) => void askRemoveEverywhere(target)}
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
                            {provider.enabled ? '' : offForPlatform(provider) ? ', not available yet' : ', turned off for this workspace'}
                          </span>
                        </h3>
                        {chat.length > 0 && (
                          <DataTable
                            columns={modelColumns(false, canManage)}
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
                              columns={modelColumns(true, canManage)}
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

      {canManage && (
        <ConnectModelDialog
          open={connectOpen}
          onClose={() => setConnectOpen(false)}
          initialProviderId={connectProviderId}
        />
      )}

      {/* Before the key dialog, so that when "Store a key first" swaps one for the other, this one
          has closed before the key dialog opens and takes focus. */}
      <ConfirmDialog
        open={enableTarget !== null}
        onClose={closeEnable}
        onConfirm={confirmEnable}
        eyebrow="No usable key"
        title={`Turn on ${enableTarget?.displayName ?? 'this provider'} for this workspace without a usable key?`}
        description={
          enableState === 'expired'
            ? 'Its key has expired, so the router will skip it until the key is replaced.'
            : 'No key is stored for it, so the router will skip it until one is added.'
        }
        confirmLabel="Turn on anyway"
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

      <ConfirmDialog
        open={removeTarget !== null}
        onClose={() => setRemoveTarget(null)}
        onConfirm={confirmRemoveEverywhere}
        eyebrow="Remove from all routing"
        title={`Remove ${removeTarget?.name ?? 'this'} from all routing?`}
        description={
          removeTarget
            ? `${usageSentence(removeTarget.usage, removeTarget.modelId ? 'this model' : `${removeTarget.name} models`)} Work already running keeps the models it started with.`
            : undefined
        }
        confirmLabel="Remove from all routing"
        cancelLabel="Keep it"
        tone="danger"
        loading={removeEverywhere.isPending}
        error={removeError}
      />

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
          <PasswordInput
            label="API key"
            value={keyValue}
            onChange={(event) => setKeyValue(cleanKey(event.target.value))}
            autoComplete="off"
            spellCheck={false}
            required
            data-autofocus
            error={keyTarget && keyValue ? (keyMismatch(keyTarget.id, keyTarget.displayName, keyValue) ?? undefined) : undefined}
            hint={
              replacingKey
                ? 'The new key replaces the stored one as soon as you store it. Use Test now on the row afterwards to check it.'
                : 'Use Connect your AI instead to check the key before it is stored.'
            }
          />
        </form>
      </Dialog>
    </div>
  )
}
