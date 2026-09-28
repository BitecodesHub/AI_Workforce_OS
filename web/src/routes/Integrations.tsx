import { useState } from 'react'
import type { FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Button, Card, Dialog, Eyebrow, Input, Notice, PageHeader, StatusTag, Tag, Time } from '../components/ui'
import type { TagTone } from '../components/ui'
import { QueryState } from '../components/ui/QueryState'
import { describeApiError } from '../lib/api'
import { formatCount } from '../lib/format'
import { actionClassLabel, serverLabel } from '../lib/labels'
import { useIntegrations, useStoreCredential, useVoiceStatus } from '../lib/queries'
import type { Integration, Tool } from '../lib/queries'
import { can } from '../lib/session'
import { useToast } from '../lib/toast'

const EFFECT_TONE: Record<Tool['sideEffect'], TagTone> = {
  READ: 'neutral',
  WRITE: 'blue',
  OUTBOUND: 'warning',
  DESTRUCTIVE: 'danger',
}

/*
 * Tool servers and what each one offers.
 *
 * Connecting an account (OAuth) is not built in this version, so the page offers no button for it
 * and says so, rather than a button that opens a dialog explaining it does nothing.
 */

function sandboxNotice(integrations: Integration[]): string {
  const sandboxed = integrations.filter((integration) => integration.sandbox).length
  if (sandboxed === integrations.length) {
    return 'These servers run against a sandbox with seeded data, so nothing an agent does leaves this machine. Live account connections are not available in this version.'
  }
  if (sandboxed > 0) {
    return 'Servers marked Sandbox run against seeded data, so nothing an agent does through them leaves this machine. Connecting another account from the console is not available in this version.'
  }
  return 'Connecting another account from the console is not available in this version.'
}

/** Words for the quota ElevenLabs reports, when a key is stored and the account has one. */
function quotaNote(charactersUsed?: number | null, characterLimit?: number | null): string | null {
  if (charactersUsed == null || characterLimit == null) return null
  return `${formatCount(charactersUsed)} of ${formatCount(characterLimit)} characters used this period.`
}

/**
 * Voice, first among the integration cards: it is not a tool server an agent is granted, so it
 * does not come back from GET /api/integrations, and it is shown whether or not that list loads.
 */
function VoiceIntegrationCard() {
  const status = useVoiceStatus()
  const canManageKey = can('provider:manage')
  const [dialogOpen, setDialogOpen] = useState(false)
  const [keyValue, setKeyValue] = useState('')
  const [error, setError] = useState<string | null>(null)
  const toast = useToast()
  const storeCredential = useStoreCredential()
  const client = useQueryClient()

  const keyStored = status.data?.keyStored ?? false
  const replacing = keyStored

  const open = () => {
    setKeyValue('')
    setError(null)
    setDialogOpen(true)
  }
  const close = () => {
    if (storeCredential.isPending) return
    setDialogOpen(false)
    storeCredential.reset()
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (!keyValue.trim() || storeCredential.isPending) return
    setError(null)
    try {
      await storeCredential.mutateAsync({ ref: 'elevenlabs', kind: 'api_key', value: keyValue })
      // Store credential refreshes its own queries; voice status and the voice list read this
      // same key and are not among them.
      client.invalidateQueries({ queryKey: ['voice'] })
      toast.success(replacing ? 'The ElevenLabs key was replaced.' : 'The ElevenLabs key was stored.')
      setDialogOpen(false)
    } catch (err) {
      setError(describeApiError(err, { value: 'API key', kind: 'Key type' }))
    }
  }

  return (
    <>
      <Card as="article">
        <Eyebrow>Voice</Eyebrow>
        <div
          className="row"
          style={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}
        >
          <h2 className="section-heading" style={{ fontSize: '15px' }}>
            Voice — ElevenLabs
          </h2>
          {status.data && (
            <StatusTag kind="integration" status={keyStored ? 'connected' : 'disconnected'} />
          )}
        </div>

        <div className="stack" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-5)', fontSize: '13px' }}>
          {status.isLoading ? (
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
          <p className="caption">
            A stored key powers speaking to Chat by voice, hearing an agent's replies aloud, and the voice-note
            tool an agent can use to leave a spoken note. Without one, the browser's own voices are used instead.
          </p>
        </div>

        {canManageKey && (
          <Button variant="outline" className="button-sm" onClick={open}>
            {replacing ? 'Replace key' : 'Store key'}
          </Button>
        )}
      </Card>

      <Dialog
        open={dialogOpen}
        onClose={close}
        eyebrow={replacing ? 'Replace a key' : 'Store a key'}
        title={`${replacing ? 'Replace' : 'Add'} the ElevenLabs key`}
        description="The key is encrypted at rest and never shown again."
        dismissible={!storeCredential.isPending}
        error={error}
        footer={
          <>
            <Button variant="outline" onClick={close} disabled={storeCredential.isPending}>
              Cancel
            </Button>
            <Button
              variant="primary"
              type="submit"
              form="elevenlabs-key-form"
              loading={storeCredential.isPending}
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
            onChange={(event) => setKeyValue(event.target.value)}
            autoComplete="off"
            spellCheck={false}
            required
            data-autofocus
            hint={replacing ? 'The new key replaces the stored one as soon as you store it.' : undefined}
          />
        </form>
      </Dialog>
    </>
  )
}

export function Integrations() {
  const integrationsQuery = useIntegrations()

  return (
    <div className="page">
      <PageHeader
        eyebrow="What the workforce can reach"
        title="Integrations"
        description="Tool servers agents act through. An agent only sees the tools it has been granted."
      />

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(min(340px, 100%), 1fr))',
          gap: 'var(--space-5)',
          marginBottom: 'var(--space-7)',
        }}
      >
        <VoiceIntegrationCard />
      </div>

      <QueryState
        query={integrationsQuery}
        permission="integration:read"
        what="the integrations list"
        isEmpty={(integrations) => integrations.length === 0}
        empty={
          <Card as="section">
            <Eyebrow>Integrations</Eyebrow>
            <p className="muted">No tool servers are available in this workspace.</p>
          </Card>
        }
        rows={4}
      >
        {(integrations) => (
          <>
            <Notice tone="info">{sandboxNotice(integrations)}</Notice>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(min(340px, 100%), 1fr))',
                gap: 'var(--space-5)',
                marginTop: 'var(--space-7)',
              }}
            >
              {integrations.map((integration) => (
                <IntegrationCard key={integration.server} integration={integration} />
              ))}
            </div>
          </>
        )}
      </QueryState>
    </div>
  )
}

function IntegrationCard({ integration }: { integration: Integration }) {
  // Reconnect is a flag beside the stored status, not a status of its own, and it is the one
  // thing about a connection somebody has to act on. The sandbox flag decides what the rest of
  // the card and the notice say, so the tag follows it too rather than a stored status that
  // could read "Connected" for a server that only reaches seeded data.
  const status = integration.reconnectRequired
    ? 'reconnect_required'
    : integration.sandbox
      ? 'sandbox'
      : integration.status
  const toolCount = integration.tools.length

  return (
    <Card as="article">
      <Eyebrow>{toolCount === 1 ? '1 tool' : `${formatCount(toolCount)} tools`}</Eyebrow>

      <div
        className="row"
        style={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}
      >
        <h2 className="section-heading" style={{ fontSize: '15px' }}>
          {serverLabel(integration.server, integration.displayName)}
        </h2>
        <StatusTag kind="integration" status={status} />
      </div>

      {/* A sandbox server has no account, no granted permissions and was never connected, so
          those rows would only ever say "none". */}
      {!integration.sandbox && (
        <div className="stack" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-5)', fontSize: '13px' }}>
          {integration.accountLabel && (
            <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)' }}>
              <span className="muted">Account</span>
              <span>{integration.accountLabel}</span>
            </div>
          )}
          <div className="stack" style={{ gap: 'var(--space-1)' }}>
            <span className="caption">Permissions granted</span>
            <span className="mono" style={{ overflowWrap: 'anywhere' }}>
              {integration.grantedScopes.length > 0 ? integration.grantedScopes.join(', ') : 'None'}
            </span>
          </div>
          {integration.missingScopes.length > 0 && (
            <div className="stack" style={{ gap: 'var(--space-1)' }}>
              <span className="caption">Permissions missing</span>
              <div className="row" style={{ flexWrap: 'wrap', gap: 'var(--space-2)' }}>
                {integration.missingScopes.map((scope) => (
                  <Tag key={scope} tone="warning">
                    {scope}
                  </Tag>
                ))}
              </div>
            </div>
          )}
          <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)' }}>
            <span className="muted">Connected</span>
            {integration.connectedAt ? (
              <Time iso={integration.connectedAt} className="muted" />
            ) : (
              <span className="muted">Never</span>
            )}
          </div>
        </div>
      )}

      <ul className="stack" style={{ gap: 'var(--space-4)', margin: 0, padding: 0, listStyle: 'none' }}>
        {integration.tools.map((tool) => (
          <li key={tool.name} className="stack" style={{ gap: 'var(--space-1)' }}>
            <span className="mono" title={tool.qualifiedName}>
              {tool.name}
            </span>
            {tool.description && <p className="caption">{tool.description}</p>}
            <div className="row" style={{ flexWrap: 'wrap', gap: 'var(--space-2)', marginTop: 'var(--space-1)' }}>
              <Tag tone={EFFECT_TONE[tool.sideEffect]}>{actionClassLabel(tool.sideEffect)}</Tag>
              {tool.alwaysRequiresApproval && (
                <Tag tone="warning" withDot>
                  Approval required
                </Tag>
              )}
            </div>
          </li>
        ))}
      </ul>
    </Card>
  )
}
