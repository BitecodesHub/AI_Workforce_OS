// @find: connectors, integrations, connect app, add connector, disconnect, OAuth, connect Gmail, Slack, Google, tools, API key, voice card, search connectors, filter connectors, /connectors, Connectors page
// @what: The Connectors page: shows the apps assistants can use, lets people connect or disconnect them, and shows voice status.
// @flow: Routed from App.tsx at /connectors; talks to the integrations service through lib/queries; reads ?connected and ?error after OAuth redirect
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import {
  Button,
  Card,
  ConfirmDialog,
  Dialog,
  Eyebrow,
  Input,
  Notice,
  PageHeader,
  StatusTag,
  Tag,
  Time,
} from '../components/ui'
import { QueryState } from '../components/ui/QueryState'
// Imported from its own module, not the ../components/ui barrel: this screen is lazy-loaded (see
// Agents.tsx for the circular chunk the barrel caused).
import { FilterEmpty } from '../components/ui/FilterBar'
import { CapabilityList } from '../components/connectors/CapabilityList'
import { ConnectDialog } from '../components/connectors/ConnectDialog'
import { CategoryGlyph, ConnectorToolbar, StatusDot } from '../components/connectors/ConnectorToolbar'
import type { ActiveChip } from '../components/connectors/ConnectorToolbar'
import { ApiError, api, describeApiError } from '../lib/api'
import {
  agentsUsing,
  capabilityLabel,
  connectorCategory,
  connectorState,
  connectorSummary,
  parseOAuthReturn,
  secretNoun,
  usesSignIn,
  withoutOAuthReturn,
} from '../lib/connectors'
import type { ConnectorState } from '../lib/connectors'
import { formatCount } from '../lib/format'
import {
  CONNECTOR_CATEGORY_ORDER,
  connectorCategoryLabel,
  connectorStateLabel,
  serverLabel,
} from '../lib/labels'
import {
  useAgents,
  useDisconnectIntegration,
  useIntegrations,
  useStoreCredential,
  useTestIntegration,
  useVoiceStatus,
} from '../lib/queries'
import type { Agent, Integration } from '../lib/queries'
import { can } from '../lib/session'
import { useToast } from '../lib/toast'
import { useRouter } from '../lib/router'
import { useListFilter } from '../lib/useListFilter'

/*
 * The apps and services agents act through.
 *
 * Each connector is honest about where it stands: Sandbox (practice data, nothing leaves the
 * workspace), Connected (a live token is stored and passed its check) or Needs attention (the
 * stored token failed its last check). A connector with no live version says so instead of
 * offering a Connect button that could not work.
 *
 * Voice is listed with the connectors. It is not a server an agent is granted, so it does not come
 * back from GET /api/integrations; it is shown whether or not that list loads.
 */

const GRID_STYLE = {
  display: 'grid',
  // min() lets a card shrink below 340px on the narrowest phones instead of pushing the page sideways.
  gridTemplateColumns: 'repeat(auto-fill, minmax(min(340px, 100%), 1fr))',
  gap: 'var(--space-5)',
} as const

/**
 * The voice card's state for the status filter: connected with a key, or not connected (the
 * browser's own voice is used). It has no practice version, so it is never counted as Sandbox.
 */
type VoiceState = 'connected' | 'disconnected'

type Row =
  | { kind: 'voice'; state: VoiceState }
  | { kind: 'connector'; integration: Integration; state: ConnectorState; category: string }

/**
 * Every state a card can be in, so the options always add up to "All statuses". Not connected is
 * only the voice card without a key, and is listed only while something is in it.
 */
const STATUS_OPTIONS: ReadonlyArray<{ value: ConnectorState | 'disconnected'; label: string; always: boolean }> = [
  { value: 'connected', label: 'Connected', always: true },
  { value: 'sandbox', label: 'Sandbox', always: true },
  { value: 'attention', label: 'Needs attention', always: true },
  { value: 'builtin', label: 'Built in', always: false },
  { value: 'disconnected', label: 'Not connected', always: false },
]

const withFullStop = (text: string) => (/[.!?]$/.test(text.trim()) ? text.trim() : `${text.trim()}.`)


function connectorName(integration: Pick<Integration, 'server' | 'displayName'>): string {
  return serverLabel(integration.server, integration.displayName)
}

const rowCategory = (row: Row) => (row.kind === 'voice' ? 'voice' : row.category)
const rowName = (row: Row) => (row.kind === 'voice' ? 'ElevenLabs' : connectorName(row.integration))

function rowText(row: Row, agents: Agent[] | undefined): string {
  if (row.kind === 'voice') return 'Voice ElevenLabs speech spoken replies voice notes'
  const { integration } = row
  return [
    connectorName(integration),
    integration.server,
    connectorCategoryLabel(row.category),
    integration.description ?? '',
    ...integration.tools.map((tool) => capabilityLabel(tool)),
    ...agentsUsing(integration.server, agents).map((agent) => agent.name),
  ].join(' ')
}

/* ---- Page ------------------------------------------------------------------------------------- */

// @find: Connectors component, connectors page, connect app, add connector dialog, OAuth redirect, /connectors
export function Connectors() {
  const query = useIntegrations()
  const canSeeAgents = can('agent:read')
  const agents = useAgents({ enabled: canSeeAgents })
  // Voice status is a chat:use read (VoiceController); a role without it is not asked, so it gets no 403.
  const voice = useVoiceStatus({ enabled: can('chat:use') })
  const voiceState: VoiceState = voice.data?.keyStored ? 'connected' : 'disconnected'

  // A new ConnectDialog per opening (the key), so each starts with an empty field; the same one
  // closes, so focus goes back to the button that opened it.
  const [connecting, setConnecting] = useState<{ session: number; integration: Integration | null; open: boolean }>({
    session: 0,
    integration: null,
    open: false,
  })
  const [disconnecting, setDisconnecting] = useState<Integration | null>(null)
  // After a disconnect the button that opened the dialog is gone; focus goes to that card instead.
  const [focusServer, setFocusServer] = useState<string | null>(null)
  const focusTaken = useCallback(() => setFocusServer(null), [])

  // The provider sends the browser back here with ?connected=<server> or ?error=<message>. Say what
  // happened once, then take both out of the address so a refresh does not repeat it.
  const { search, navigate, path } = useRouter()
  const client = useQueryClient()
  const toast = useToast()
  const returned = useRef<string | null>(null)
  useEffect(() => {
    const { connected, error } = parseOAuthReturn(search)
    if (!connected && !error) return
    const key = `${connected ?? ''}|${error ?? ''}`
    if (returned.current === key) return
    if (connected && !query.data) return // wait for the list, so the name is a known connector
    returned.current = key
    if (error) {
      toast.error(error)
    } else if (connected) {
      const known = query.data?.find((item) => item.server === connected)
      if (known) toast.success(`${connectorName(known)} is connected.`)
    }
    void client.invalidateQueries({ queryKey: ['integrations'] })
    navigate(`${path}${withoutOAuthReturn(search)}`, { replace: true })
  }, [search, query.data, client, toast, navigate, path])

  // Grouped by category in the catalog's order, then by name. The ElevenLabs card sits with the
  // other voice connector rather than apart from everything.
  const rows = useMemo<Row[] | undefined>(() => {
    if (!query.data) return undefined
    const all: Row[] = [
      { kind: 'voice', state: voiceState },
      ...query.data.map((integration) => ({
        kind: 'connector' as const,
        integration,
        state: connectorState(integration),
        category: connectorCategory(integration),
      })),
    ]
    const rank = (category: string) => {
      const index = CONNECTOR_CATEGORY_ORDER.indexOf(category)
      return index === -1 ? CONNECTOR_CATEGORY_ORDER.length : index
    }
    return all.sort((a, b) => rank(rowCategory(a)) - rank(rowCategory(b)) || rowName(a).localeCompare(rowName(b)))
  }, [query.data, voiceState])

  const filter = useListFilter<Row>({
    rows,
    text: (row) => rowText(row, agents.data),
    facets: {
      category: rowCategory,
      status: (row) => row.state,
    },
  })

  const categoryOptions = useMemo(() => {
    const present = new Set((rows ?? []).map(rowCategory))
    const known = CONNECTOR_CATEGORY_ORDER.filter((category) => present.has(category))
    const unknown = [...present].filter((category) => !CONNECTOR_CATEGORY_ORDER.includes(category)).sort()
    return [...known, ...unknown].map((category) => ({
      value: category,
      label: connectorCategoryLabel(category),
      count: filter.counts.category?.[category] ?? 0,
      icon: <CategoryGlyph category={category} />,
    }))
  }, [rows, filter.counts])

  const sumOf = (tally: Record<string, number> | undefined) =>
    Object.values(tally ?? {}).reduce((sum, count) => sum + count, 0)

  const openConnect = (integration: Integration) =>
    setConnecting((current) => ({ session: current.session + 1, integration, open: true }))

  return (
    <div className="page">
      <PageHeader
        eyebrow="What the workforce can reach"
        title="Connectors"
        description="The apps and services agents act through. An agent only uses the connectors it has been given, and only the actions chosen for it."
      />

      {/* The list failed or is loading: voice does not depend on it, so it still shows. */}
      {!query.data && !query.isLoading && (
        <div style={{ ...GRID_STYLE, marginBottom: 'var(--space-7)' }}>
          <VoiceCard />
        </div>
      )}

      <QueryState query={query} permission="integration:read" what="the connectors list" rows={4}>
        {(integrations) => {
          const note = connectorSummary(integrations)
          const shown = filter.filtered
          const categoryValue = filter.selected.category?.[0] ?? ''
          const statusValue = filter.selected.status?.[0] ?? ''
          const chips: ActiveChip[] = [
            ...(filter.query.trim()
              ? [{ key: 'q', label: `"${filter.query.trim()}"`, onRemove: () => filter.setQuery('') }]
              : []),
            ...(filter.selected.category ?? []).map((value) => ({
              key: `category-${value}`,
              label: connectorCategoryLabel(value),
              onRemove: () => filter.toggle('category', value),
            })),
            ...(filter.selected.status ?? []).map((value) => ({
              key: `status-${value}`,
              label: connectorStateLabel(value).label,
              onRemove: () => filter.toggle('status', value),
            })),
          ]
          return (
            <>
              {integrations.length === 0 && (
                <div style={{ marginBottom: 'var(--space-5)' }}>
                  <Notice tone="info">No connectors are available in this workspace yet.</Notice>
                </div>
              )}

              <ConnectorToolbar
                query={filter.query}
                onQueryChange={filter.setQuery}
                category={{
                  value: categoryValue,
                  allCount: sumOf(filter.counts.category),
                  options: categoryOptions,
                  onChange: (value) => filter.setOnly('category', value || null),
                }}
                status={{
                  value: statusValue,
                  allCount: sumOf(filter.counts.status),
                  options: STATUS_OPTIONS.filter(
                    (option) => option.always || (filter.counts.status?.[option.value] ?? 0) > 0,
                  ).map((option) => ({
                    value: option.value,
                    label: option.label,
                    count: filter.counts.status?.[option.value] ?? 0,
                    icon: <StatusDot state={option.value} />,
                  })),
                  onChange: (value) => filter.setOnly('status', value || null),
                }}
                summary={note}
                attentionActive={statusValue === 'attention'}
                onShowAttention={() => filter.setOnly('status', statusValue === 'attention' ? null : 'attention')}
                chips={chips}
                shown={shown.length}
                total={filter.total}
                active={filter.active}
                onClear={filter.clear}
              />

              {shown.length === 0 ? (
                <Card>
                  <FilterEmpty onClear={filter.clear} what="connectors" />
                </Card>
              ) : (
                <div style={GRID_STYLE}>
                  {shown.map((row) =>
                    row.kind === 'voice' ? (
                      <VoiceCard key="elevenlabs-voice-key" />
                    ) : (
                      <ConnectorCard
                        key={`server-${row.integration.server}`}
                        integration={row.integration}
                        state={row.state}
                        category={row.category}
                        agents={canSeeAgents ? agents.data : undefined}
                        onConnect={() => openConnect(row.integration)}
                        onDisconnect={() => setDisconnecting(row.integration)}
                        takeFocus={focusServer === row.integration.server}
                        onFocusTaken={focusTaken}
                      />
                    ),
                  )}
                </div>
              )}
            </>
          )
        }}
      </QueryState>

      {connecting.integration && (
        <ConnectDialog
          key={connecting.session}
          open={connecting.open}
          integration={connecting.integration}
          onClose={() => setConnecting((current) => ({ ...current, open: false }))}
        />
      )}

      <DisconnectDialog
        integration={disconnecting}
        onClose={() => setDisconnecting(null)}
        onDisconnected={setFocusServer}
      />
    </div>
  )
}

/* ---- One connector ------------------------------------------------------------------------------ */

// @find: connector card, connect, disconnect, status, credentials form, POST /api/integrations/connect
function ConnectorCard({
  integration,
  state,
  category,
  agents,
  onConnect,
  onDisconnect,
  takeFocus = false,
  onFocusTaken,
}: {
  integration: Integration
  state: ConnectorState
  category: string
  /** The agents list, or undefined for a role that cannot read it (the row is then left out). */
  agents: Agent[] | undefined
  onConnect: () => void
  onDisconnect: () => void
  /** True once, after this connector was disconnected: focus its first action, else its name. */
  takeFocus?: boolean
  onFocusTaken?: () => void
}) {
  const headingRef = useRef<HTMLHeadingElement>(null)
  const actionsRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!takeFocus) return
    // After the dialog has closed and handed focus back, which it cannot do to a removed button.
    const timer = window.setTimeout(() => {
      const target = actionsRef.current?.querySelector<HTMLElement>('button:not(:disabled)') ?? headingRef.current
      target?.focus()
      onFocusTaken?.()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [takeFocus, onFocusTaken])

  const toast = useToast()
  const test = useTestIntegration()
  const name = connectorName(integration)
  const stateLabel = connectorStateLabel(state)
  const live = integration.liveAvailable === true
  // Built into the platform (Voice notes): there is no account to connect, sandbox or not.
  const nothingToConnect = integration.authType === 'none'
  const canConnect = can('integration:connect')
  const signIn = usesSignIn(integration)
  const missing = integration.sandbox ? [] : (integration.missingScopes ?? []).filter((scope) => scope.trim())
  const canDisconnect = can('integration:disconnect')
  const usedBy = agents ? agentsUsing(integration.server, agents) : null

  const handleTest = async () => {
    try {
      const result = await test.mutateAsync(integration.server)
      const message = result.message?.trim()
      if (result.ok) toast.success(message ? `The ${name} connection works. ${withFullStop(message)}` : `The ${name} connection works.`)
      else toast.error(message ? `The ${name} check failed. ${withFullStop(message)}` : `The ${name} check failed.`)
    } catch (error) {
      toast.error(describeApiError(error))
    }
  }

  return (
    <Card as="article">
      <div className="stack" style={{ gap: 'var(--space-5)', height: '100%' }}>
        <div>
          <Eyebrow>{connectorCategoryLabel(category)}</Eyebrow>
          <div
            className="row"
            style={{ justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 'var(--space-2) var(--space-3)' }}
          >
            <h2 ref={headingRef} tabIndex={-1} className="section-heading" style={{ overflowWrap: 'anywhere' }}>
              {name}
            </h2>
            <Tag tone={stateLabel.tone} withDot>
              {stateLabel.label}
            </Tag>
          </div>
          {integration.description && (
            <p className="muted" style={{ marginTop: 'var(--space-2)' }}>
              {integration.description}
            </p>
          )}
        </div>

        {state === 'attention' && (
          <Notice tone="warning">
            {integration.lastError
              ? `Its last check failed: ${withFullStop(integration.lastError)}`
              : 'It needs to be connected again before agents can use the live account.'}
          </Notice>
        )}

        {missing.length > 0 && (
          <Notice tone="warning">
            <strong>Permissions missing</strong>
            <span>
              {' '}
              {name} was connected without: {missing.join(', ')}.
              {canConnect
                ? signIn
                  ? ' Reconnect to grant them again.'
                  : ` Replace the ${secretNoun(integration)} to grant them again.`
                : ' An owner or admin needs to reconnect it.'}
            </span>
          </Notice>
        )}

        {/* A sandbox connector has no account and was never checked, so those rows would only
            ever say "none". */}
        {!integration.sandbox && (
          <dl className="stack" style={{ gap: 'var(--space-2)', margin: 0, fontSize: 'var(--text-caption)' }}>
            {integration.accountLabel && (
              <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)' }}>
                <dt className="muted">Account</dt>
                <dd style={{ margin: 0, overflowWrap: 'anywhere', textAlign: 'right' }}>{integration.accountLabel}</dd>
              </div>
            )}
            <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)' }}>
              <dt className="muted">Connected</dt>
              <dd style={{ margin: 0 }}>
                {integration.connectedAt ? <Time iso={integration.connectedAt} /> : <span className="muted">Not recorded</span>}
              </dd>
            </div>
            {integration.lastCheckedAt && (
              <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)' }}>
                <dt className="muted">Last checked</dt>
                <dd style={{ margin: 0 }}>
                  <Time iso={integration.lastCheckedAt} />
                </dd>
              </div>
            )}
          </dl>
        )}

        <CapabilityList tools={integration.tools} />

        {usedBy && (
          <p className="caption">
            {usedBy.length === 0 ? (
              'No agent uses it yet.'
            ) : (
              <>
                Used by{' '}
                {usedBy.map((agent, index) => (
                  <span key={agent.id}>
                    {index > 0 && (index === usedBy.length - 1 ? ' and ' : ', ')}
                    <a className="link" href={`/agents/${agent.id}`}>
                      {agent.name}
                    </a>
                  </span>
                ))}
                .
              </>
            )}
          </p>
        )}

        <div className="stack" style={{ gap: 'var(--space-3)', marginTop: 'auto' }}>
          {!live && nothingToConnect && <p className="caption">Nothing to connect: it works without an account.</p>}
          {!live && !nothingToConnect && <p className="caption">A live connection is not available for this connector.</p>}
          {live && !canConnect && integration.sandbox && (
            <p className="caption">An owner or admin can connect a live account.</p>
          )}
          {((live && canConnect) || !integration.sandbox) && (
            <div ref={actionsRef} className="row" style={{ gap: 'var(--space-2)', flexWrap: 'wrap' }}>
              {live && canConnect && (
                <Button
                  variant={state === 'sandbox' ? 'primary' : 'outline'}
                  className="button-sm"
                  aria-label={
                    state === 'sandbox'
                      ? `Connect ${name}`
                      : signIn
                        ? `Reconnect ${name}`
                        : `Replace the ${name} ${secretNoun(integration)}`
                  }
                  onClick={onConnect}
                >
                  {state === 'sandbox' ? 'Connect' : signIn ? 'Reconnect' : `Replace ${secretNoun(integration)}`}
                </Button>
              )}
              {!integration.sandbox && (
                <Button
                  variant="outline"
                  className="button-sm"
                  aria-label={`Test the ${name} connection`}
                  onClick={() => void handleTest()}
                  loading={test.isPending}
                >
                  Test connection
                </Button>
              )}
              {!integration.sandbox && canDisconnect && (
                <Button variant="danger" className="button-sm" aria-label={`Disconnect ${name}`} onClick={onDisconnect}>
                  Disconnect
                </Button>
              )}
            </div>
          )}
        </div>
      </div>
    </Card>
  )
}

/* ---- Disconnect --------------------------------------------------------------------------------- */

// @find: disconnect connector dialog, remove connection, DELETE /api/integrations
function DisconnectDialog({
  integration,
  onClose,
  onDisconnected,
}: {
  integration: Integration | null
  onClose: () => void
  onDisconnected: (server: string) => void
}) {
  const toast = useToast()
  const disconnect = useDisconnectIntegration()
  const [error, setError] = useState<string | null>(null)
  const name = integration ? connectorName(integration) : ''

  const close = () => {
    if (disconnect.isPending) return
    setError(null)
    onClose()
  }

  const handleConfirm = async () => {
    if (!integration) return
    setError(null)
    try {
      await disconnect.mutateAsync(integration.server)
      toast.success(`${name} is disconnected. It is back on practice data.`)
      onDisconnected(integration.server)
      onClose()
    } catch (err) {
      setError(describeApiError(err))
    }
  }

  return (
    <ConfirmDialog
      open={integration !== null}
      onClose={close}
      onConfirm={handleConfirm}
      eyebrow="Disconnect"
      title={integration ? `Disconnect ${name}?` : 'Disconnect this connector?'}
      description={
        integration
          ? `The stored ${secretNoun(integration)} is deleted. Agents that use ${name} go back to practice data in the sandbox, so nothing they do reaches the live account until it is connected again.`
          : undefined
      }
      confirmLabel="Disconnect"
      cancelLabel="Keep it connected"
      tone="danger"
      loading={disconnect.isPending}
      error={error}
    />
  )
}

/* ---- Voice -------------------------------------------------------------------------------------- */

/** Words for the quota ElevenLabs reports, when a key is stored and the account has one. */
function quotaNote(charactersUsed?: number | null, characterLimit?: number | null): string | null {
  if (charactersUsed == null || characterLimit == null) return null
  return `${formatCount(charactersUsed)} of ${formatCount(characterLimit)} characters used this period.`
}

// @find: voice card, voice status, text to speech, speech to text, chat:use
function VoiceCard() {
  const canReadStatus = can('chat:use')
  const status = useVoiceStatus({ enabled: canReadStatus })
  const canManageKey = can('provider:manage')
  const [dialogOpen, setDialogOpen] = useState(false)
  const [keyValue, setKeyValue] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [keyError, setKeyError] = useState<string | null>(null)
  const [checking, setChecking] = useState(false)
  const toast = useToast()
  const storeCredential = useStoreCredential()
  const busy = checking || storeCredential.isPending
  const client = useQueryClient()

  const keyStored = status.data?.keyStored ?? false
  const replacing = keyStored

  const open = () => {
    setKeyValue('')
    setError(null)
    setKeyError(null)
    setDialogOpen(true)
  }
  const close = () => {
    if (busy) return
    setDialogOpen(false)
    storeCredential.reset()
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (!keyValue.trim() || busy) return
    setError(null)
    setKeyError(null)
    try {
      // Asked of ElevenLabs first: a mistyped key used to be stored and the card said Connected
      // while every clip failed.
      setChecking(true)
      let note: string | null
      try {
        const check = await api<{ verified: boolean; message: string | null }>('/api/voice/key/check', {
          method: 'POST',
          body: { key: keyValue.trim() },
        })
        note = check.verified ? null : check.message
      } finally {
        setChecking(false)
      }
      await storeCredential.mutateAsync({ ref: 'elevenlabs', kind: 'api_key', value: keyValue.trim() })
      // Store credential refreshes its own queries; voice status and the voice list read this
      // same key and are not among them.
      client.invalidateQueries({ queryKey: ['voice'] })
      const done = replacing ? 'The ElevenLabs key was replaced.' : 'The ElevenLabs key was stored.'
      toast.success(note ? `${done} ${note}` : done)
      setDialogOpen(false)
    } catch (err) {
      const fieldProblem = err instanceof ApiError ? (err.fields.value ?? err.fields.key) : undefined
      if (fieldProblem) setKeyError(fieldProblem)
      else setError(describeApiError(err, { value: 'API key', kind: 'Key type' }))
    }
  }

  return (
    <>
      <Card as="article">
        <div className="stack" style={{ gap: 'var(--space-5)', height: '100%' }}>
          <div>
            <Eyebrow>{connectorCategoryLabel('voice')}</Eyebrow>
            <div
              className="row"
              style={{ justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 'var(--space-2) var(--space-3)' }}
            >
              <h2 className="section-heading">ElevenLabs</h2>
              {status.data && <StatusTag kind="integration" status={keyStored ? 'connected' : 'disconnected'} />}
            </div>
            <p className="muted" style={{ marginTop: 'var(--space-2)' }}>
              Lets people speak to Chat, read agents' replies aloud and record the voice notes an agent can leave.
            </p>
          </div>

          <div className="stack" style={{ gap: 'var(--space-3)', fontSize: 'var(--text-caption)' }}>
            {!canReadStatus ? (
              <p className="muted">Voice is used in Chat. Its status is shown to people whose role can use Chat.</p>
            ) : status.isLoading ? (
              <p className="muted">Reading its status.</p>
            ) : status.error ? (
              <Notice tone="warning">Its status could not be loaded. {describeApiError(status.error)}</Notice>
            ) : keyStored ? (
              <p className="muted">
                {quotaNote(status.data?.charactersUsed, status.data?.characterLimit) ??
                  'A key is stored. Its quota is not reported by this account.'}
              </p>
            ) : (
              <p className="muted">Using your browser's built-in voice until a key is stored.</p>
            )}
          </div>

          {canManageKey && (
            <div style={{ marginTop: 'auto' }}>
              <Button variant="outline" className="button-sm" onClick={open}>
                {replacing ? 'Replace key' : 'Store key'}
              </Button>
            </div>
          )}
        </div>
      </Card>

      <Dialog
        open={dialogOpen}
        onClose={close}
        eyebrow={replacing ? 'Replace a key' : 'Store a key'}
        title={`${replacing ? 'Replace' : 'Add'} the ElevenLabs key`}
        description="The key is encrypted at rest and never shown again."
        dismissible={!busy}
        error={error}
        footer={
          <>
            <Button variant="outline" onClick={close} disabled={busy}>
              Cancel
            </Button>
            <Button
              variant="primary"
              type="submit"
              form="elevenlabs-key-form"
              loading={busy}
              disabled={!keyValue.trim()}
            >
              Store key
            </Button>
          </>
        }
      >
        <form id="elevenlabs-key-form" onSubmit={handleSubmit}>
          <Input
            label="API key"
            type="password"
            value={keyValue}
            onChange={(event) => {
              setKeyValue(event.target.value)
              setKeyError(null)
            }}
            autoComplete="off"
            spellCheck={false}
            required
            data-autofocus
            error={keyError}
            hint={replacing ? 'The new key replaces the stored one as soon as you store it.' : undefined}
          />
        </form>
      </Dialog>
    </>
  )
}
