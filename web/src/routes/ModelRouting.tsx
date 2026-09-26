import React from 'react'
import { Button, Card, DataTable, Dialog, Eyebrow, Input, Notice, PageHeader, Select, StatRow, StatTile, Tag } from '../components/ui'
import { QueryState } from '../components/ui/QueryState'
import { useToast } from '../lib/toast'
import { can } from '../lib/session'
import {
  useModelPolicy,
  useModels,
  useProviders,
  useSetModelPolicy,
  useStoreCredential,
  useToggleProvider,
} from '../lib/queries'
import type { Column } from '../components/ui'
import type { Provider, Model } from '../lib/queries'

const STATE_TONE = { closed: 'success', 'half-open': 'warning', open: 'danger' } as const
const STATE_LABEL = { closed: 'Healthy', 'half-open': 'Recovering', open: 'Paused' } as const
const CREDENTIAL_TONE = { stored: 'success', missing: 'neutral', rejected: 'danger' } as const
const CREDENTIAL_LABEL = { stored: 'Stored', missing: 'Not configured', rejected: 'Rejected' } as const

const PROVIDER_COLUMNS: Column<Provider>[] = [
  { key: 'displayName', header: 'Provider', render: (row) => row.displayName },
  { key: 'kind', header: 'Kind', render: (row) => <span className="mono">{row.kind}</span> },
  {
    key: 'enabled',
    header: 'Enabled',
    render: (row) => <Tag tone={row.enabled ? 'success' : 'neutral'}>{row.enabled ? 'Yes' : 'No'}</Tag>,
  },
  {
    key: 'credentialStatus',
    header: 'Credential',
    render: (row) => <Tag tone={CREDENTIAL_TONE[row.credentialStatus as keyof typeof CREDENTIAL_TONE] || 'neutral'}>
      {CREDENTIAL_LABEL[row.credentialStatus as keyof typeof CREDENTIAL_LABEL] || row.credentialStatus}
    </Tag>,
  },
  { key: 'credentialCheckedAt', header: 'Credential checked', render: (row) => <span className="muted">{row.credentialCheckedAt ? timeAgo(row.credentialCheckedAt) : 'Never'}</span> },
  {
    key: 'circuitState',
    header: 'Circuit',
    render: (row) => <Tag tone={STATE_TONE[row.circuitState as keyof typeof STATE_TONE] || 'neutral'}>
      {STATE_LABEL[row.circuitState as keyof typeof STATE_LABEL] || row.circuitState}
    </Tag>,
  },
  { key: 'modelCount', header: 'Models', numeric: true, render: (row) => row.modelCount },
  { key: 'regions', header: 'Regions', render: (row) => <span className="mono">{row.regions.join(', ') || '—'}</span> },
]

function timeAgo(iso: string | null | undefined): string {
  if (!iso) return '—'
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000)
  if (seconds < 45) return 'just now'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? '' : 's'} ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`
  const days = Math.round(hours / 24)
  return `${days} day${days === 1 ? '' : 's'} ago`
}

const MODEL_COLUMNS: Column<Model>[] = [
  { key: 'modelId', header: 'Model ID', render: (row) => <span className="mono">{row.modelId}</span> },
  { key: 'displayName', header: 'Display name', render: (row) => row.displayName },
  { key: 'contextWindow', header: 'Context window', numeric: true, render: (row) => `${Math.round(row.contextWindow / 1000)}k` },
  { key: 'maxOutputTokens', header: 'Max output', numeric: true, render: (row) => `${Math.round(row.maxOutputTokens / 1000)}k` },
  {
    key: 'supportsTools',
    header: 'Tools',
    render: (row) => <Tag tone={row.supportsTools ? 'success' : 'neutral'}>{row.supportsTools ? 'Yes' : 'No'}</Tag>,
  },
  {
    key: 'supportsJsonMode',
    header: 'JSON mode',
    render: (row) => <Tag tone={row.supportsJsonMode ? 'success' : 'neutral'}>{row.supportsJsonMode ? 'Yes' : 'No'}</Tag>,
  },
  {
    key: 'supportsStreaming',
    header: 'Streaming',
    render: (row) => <Tag tone={row.supportsStreaming ? 'success' : 'neutral'}>{row.supportsStreaming ? 'Yes' : 'No'}</Tag>,
  },
  { key: 'inputCostPerMillion', header: 'In / 1M', numeric: true, render: (row) => `${row.inputCostPerMillion.toFixed(2)} <span className="stat-unit">USD</span>` },
  { key: 'outputCostPerMillion', header: 'Out / 1M', numeric: true, render: (row) => `${row.outputCostPerMillion.toFixed(2)} <span className="stat-unit">USD</span>` },
  { key: 'enabled', header: 'Enabled', render: (row) => <Tag tone={row.enabled ? 'success' : 'neutral'}>{row.enabled ? 'Yes' : 'No'}</Tag> },
]

type DraftCandidate = { providerId: string; modelId: string }

/**
 * Enabling a provider and storing its key only makes it usable, not chosen - the router still
 * needs an ordered chain to try, or every run keeps resolving to the built-in sandbox default.
 * This edits the workspace-wide chain; an agent can still be given its own in Agent detail.
 */
function RoutingPolicyCard({ models, canManage }: { models: Model[] | undefined; canManage: boolean }) {
  const policyQuery = useModelPolicy()
  const setPolicy = useSetModelPolicy()
  const toast = useToast()
  const [draft, setDraft] = React.useState<DraftCandidate[] | null>(null)

  const policy = policyQuery.data
  const candidates = draft ?? policy?.candidates.map((c) => ({ providerId: c.providerId, modelId: c.modelId })) ?? []
  const dirty = draft !== null

  const options = models ?? []
  const firstOption = options[0]

  const addCandidate = () => {
    if (!firstOption) return
    setDraft([...candidates, { providerId: firstOption.providerId, modelId: firstOption.modelId }])
  }

  const updateCandidate = (index: number, next: DraftCandidate) => {
    const copy = candidates.slice()
    copy[index] = next
    setDraft(copy)
  }

  const removeCandidate = (index: number) => {
    setDraft(candidates.filter((_, i) => i !== index))
  }

  const move = (index: number, delta: number) => {
    const target = index + delta
    if (target < 0 || target >= candidates.length) return
    const copy = candidates.slice()
    const [item] = copy.splice(index, 1)
    copy.splice(target, 0, item!)
    setDraft(copy)
  }

  const handleSave = async () => {
    try {
      await setPolicy.mutateAsync({ candidates })
      toast.success('Routing policy saved')
      setDraft(null)
    } catch (e) {
      toast.error('Failed to save the routing policy')
    }
  }

  return (
    <section style={{ marginTop: 'var(--space-6)' }}>
      <Card as="section">
        <Eyebrow>Routing policy</Eyebrow>
        <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
          The order every agent tries by default, unless it has a chain of its own. With nothing
          set here, every run answers on the offline sandbox model regardless of what is enabled
          above - a deliberate default, so no workspace is billed before it chooses to be.
        </p>

        {policyQuery.isLoading ? (
          <p className="muted">Loading…</p>
        ) : (
          <>
            {candidates.length === 0 && (
              <p className="muted" style={{ marginBottom: 'var(--space-4)' }}>
                Nothing configured. Every run falls through to the sandbox.
              </p>
            )}
            <div className="stack" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}>
              {candidates.map((candidate, index) => {
                const providerModels = options.filter((m) => m.providerId === candidate.providerId)
                const providerIds = Array.from(new Set(options.map((m) => m.providerId)))
                return (
                  <div key={index} className="row" style={{ gap: 'var(--space-3)', alignItems: 'flex-end' }}>
                    <span className="mono muted" style={{ minWidth: '20px' }}>{index + 1}.</span>
                    <Select
                      label="Provider"
                      value={candidate.providerId}
                      disabled={!canManage}
                      onChange={(e) => {
                        const nextProviderId = e.target.value
                        const nextModel = options.find((m) => m.providerId === nextProviderId)
                        updateCandidate(index, { providerId: nextProviderId, modelId: nextModel?.modelId ?? '' })
                      }}
                    >
                      {providerIds.map((id) => (
                        <option key={id} value={id}>{id}</option>
                      ))}
                    </Select>
                    <Select
                      label="Model"
                      value={candidate.modelId}
                      disabled={!canManage}
                      onChange={(e) => updateCandidate(index, { ...candidate, modelId: e.target.value })}
                    >
                      {providerModels.map((m) => (
                        <option key={m.modelId} value={m.modelId}>{m.displayName}</option>
                      ))}
                    </Select>
                    {canManage && (
                      <>
                        <Button variant="outline" className="button-sm" onClick={() => move(index, -1)} disabled={index === 0}>
                          Up
                        </Button>
                        <Button variant="outline" className="button-sm" onClick={() => move(index, 1)} disabled={index === candidates.length - 1}>
                          Down
                        </Button>
                        <Button variant="outline" className="button-sm" onClick={() => removeCandidate(index)}>
                          Remove
                        </Button>
                      </>
                    )}
                  </div>
                )
              })}
            </div>

            {canManage && (
              <div className="row" style={{ gap: 'var(--space-3)' }}>
                <Button variant="outline" onClick={addCandidate} disabled={!firstOption || candidates.length >= 10}>
                  Add a candidate
                </Button>
                <Button onClick={handleSave} loading={setPolicy.isPending} disabled={!dirty}>
                  Save routing policy
                </Button>
              </div>
            )}
          </>
        )}
      </Card>
    </section>
  )
}

export function ModelRouting() {
  const { data: providers, isLoading: providersLoading, error: providersError, refetch: refetchProviders } = useProviders()
  const { data: models, isLoading: modelsLoading, error: modelsError, refetch: refetchModels } = useModels()
  const toggleProvider = useToggleProvider()
  const { error: toastError, info: toastInfo } = useToast()
  const [storeKeyDialogOpen, setStoreKeyDialogOpen] = React.useState(false)
  const [storeKeyProviderId, setStoreKeyProviderId] = React.useState<string | null>(null)
  const [enableDialogOpen, setEnableDialogOpen] = React.useState(false)
  const [enableDialogProvider, setEnableDialogProvider] = React.useState<Provider | null>(null)

  const hasProviderManage = can('provider:manage')
  const [credentialValue, setCredentialValue] = React.useState('')
  const storeCredential = useStoreCredential()

  const loading = providersLoading || modelsLoading
  const error = providersError || modelsError
  const refetch = () => { refetchProviders(); refetchModels(); }

  const handleToggleProvider = async (provider: Provider) => {
    if (!provider.enabled && provider.credentialStatus === 'missing') {
      setEnableDialogProvider(provider)
      setEnableDialogOpen(true)
      return
    }
    try {
      await toggleProvider.mutateAsync({ id: provider.id, enable: !provider.enabled })
    } catch (e) {
      toastError('Failed to update provider')
    }
  }

  const handleStoreKey = (providerId: string) => {
    setStoreKeyProviderId(providerId)
    setCredentialValue('')
    setStoreKeyDialogOpen(true)
  }

  const storeKeyProvider = providers?.find((p) => p.id === storeKeyProviderId) ?? null

  const handleConfirmStoreKey = async () => {
    if (!storeKeyProvider?.credentialRef || !credentialValue.trim()) return
    try {
      await storeCredential.mutateAsync({ ref: storeKeyProvider.credentialRef, kind: 'api_key', value: credentialValue })
      toastInfo(`Key stored for ${storeKeyProvider.displayName}. Enable it, then give it a place in the routing policy below.`)
      setStoreKeyDialogOpen(false)
      setCredentialValue('')
    } catch (e) {
      toastError('Failed to store the key')
    }
  }

  const handleConfirmEnable = async () => {
    if (!enableDialogProvider) return
    try {
      await toggleProvider.mutateAsync({ id: enableDialogProvider.id, enable: true })
      toastInfo('Provider enabled but not configured - router will skip it.')
      setEnableDialogOpen(false)
      setEnableDialogProvider(null)
    } catch (e) {
      toastError('Failed to enable provider')
    }
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Where the thinking happens"
        title="Model routing"
        description="Agents ask for an answer and state what they need. This chain decides which model provides it, and what happens when one cannot."
      />

      <Notice tone="warning">
        No provider credential is stored, so every request falls through to the offline sandbox
        model. Answers are placeholders until a key is added.
      </Notice>

      <QueryState
        query={{ data: providers, isLoading: loading, error, refetch }}
        permission="provider:read"
        what="providers"
        isEmpty={(providers) => providers.length === 0}
        empty={
          <div style={{ marginTop: 'var(--space-6)' }}>
            <Card as="section">
              <Eyebrow>Provider health</Eyebrow>
              <p className="muted">No providers configured.</p>
            </Card>
          </div>
        }
        rows={6}
      >
        {(providers) => (
          <>
            <div style={{ marginTop: 'var(--space-6)' }}>
              <StatRow>
                <StatTile label="Providers configured" value={providers.filter(p => p.enabled && p.credentialStatus === 'stored').length} unit={`of ${providers.length}`} />
                <StatTile label="Models available" value={models?.length ?? 0} />
                <StatTile label="Healthy providers" value={providers.filter(p => p.circuitState === 'closed').length} />
                <StatTile label="Paused providers" value={providers.filter(p => p.circuitState !== 'closed').length} />
              </StatRow>
              <p className="caption" style={{ marginTop: 'var(--space-3)' }}>
                Source: provider registry and circuit breaker state. Models fetched from each enabled provider.
              </p>
            </div>

            <section style={{ marginTop: 'var(--space-7)' }}>
              <Card as="section">
                <Eyebrow>Provider health</Eyebrow>
                <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
                  A provider is paused automatically after repeated failures and probed again once the
                  cool-down passes. One vendor being unwell never stops calls to the others.
                </p>
                <DataTable
                  columns={PROVIDER_COLUMNS}
                  rows={providers}
                  getKey={(row) => row.id}
                  caption="Circuit breaker state and credential status for each configured provider."
                />
              </Card>
            </section>

            <section style={{ marginTop: 'var(--space-6)' }}>
              <Card as="section">
                <Eyebrow>Models per provider</Eyebrow>
                <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
                  Models returned by each provider. Costs are per million tokens. Enable or disable a
                  provider to control whether its models enter the routing chain.
                </p>
                {providers.map((provider) => {
                  const providerModels = models?.filter(m => m.providerId === provider.id) ?? []
                  if (providerModels.length === 0) return null
                  return (
                    <div key={provider.id} style={{ marginBottom: 'var(--space-6)' }}>
                      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-3)' }}>
                        <h3 style={{ fontSize: '14px', fontWeight: 600 }}>{provider.displayName} ({provider.kind})</h3>
                        <div className="row" style={{ gap: 'var(--space-2)' }}>
                          {!hasProviderManage && (
                            <>
                              {provider.credentialStatus === 'missing' && (
                                <Button variant="outline" className="button-sm" onClick={() => handleStoreKey(provider.id)} disabled>
                                  Store key
                                </Button>
                              )}
                              <Button
                                variant={provider.enabled ? 'primary' : 'outline'}
                                className="button-sm"
                                onClick={() => handleToggleProvider(provider)}
                                disabled={!hasProviderManage}
                              >
                                {provider.enabled ? 'Disable' : 'Enable'}
                              </Button>
                            </>
                          )}
                          {hasProviderManage && (
                            <>
                              {provider.credentialStatus === 'missing' && (
                                <Button variant="outline" className="button-sm" onClick={() => handleStoreKey(provider.id)}>
                                  Store key
                                </Button>
                              )}
                              <Button
                                variant={provider.enabled ? 'primary' : 'outline'}
                                className="button-sm"
                                onClick={() => handleToggleProvider(provider)}
                              >
                                {provider.enabled ? 'Disable' : 'Enable'}
                              </Button>
                            </>
                          )}
                        </div>
                      </div>
                      <DataTable
                        columns={MODEL_COLUMNS}
                        rows={providerModels}
                        getKey={(row) => row.modelId}
                        caption={`Models available from ${provider.displayName}`}
                      />
                    </div>
                  )
                })}
              </Card>
            </section>

            <RoutingPolicyCard models={models} canManage={hasProviderManage} />
          </>
        )}
      </QueryState>

      <Dialog
        open={storeKeyDialogOpen}
        onClose={() => setStoreKeyDialogOpen(false)}
        eyebrow="Store a credential"
        title={`Add a key for ${storeKeyProvider?.displayName ?? storeKeyProviderId}`}
        description="Encrypted at rest and never returned by any read endpoint - only a fingerprint is shown once it is stored."
        footer={
          <div className="dialog-footer">
            <Button variant="outline" onClick={() => setStoreKeyDialogOpen(false)}>
              Cancel
            </Button>
            <Button variant="primary" onClick={handleConfirmStoreKey} loading={storeCredential.isPending} disabled={!credentialValue.trim()}>
              Store key
            </Button>
          </div>
        }
      >
        <Input
          label="API key"
          type="password"
          value={credentialValue}
          onChange={(e) => setCredentialValue(e.target.value)}
          placeholder="sk-..."
          autoFocus
        />
      </Dialog>

      <Dialog
        open={enableDialogOpen}
        onClose={() => { setEnableDialogOpen(false); setEnableDialogProvider(null); }}
        eyebrow="Missing credential"
        title="Enable provider without credential?"
        description="This provider does not have a stored credential. If enabled, the router will skip it for every request."
        footer={
          <div className="dialog-footer">
            <Button variant="outline" onClick={() => { setEnableDialogOpen(false); setEnableDialogProvider(null); }}>
              Cancel
            </Button>
            <Button variant="primary" onClick={handleConfirmEnable}>
              Enable anyway
            </Button>
          </div>
        }
      >
        <p className="muted">Add a credential first to make this provider usable in the routing chain.</p>
      </Dialog>
    </div>
  )
}