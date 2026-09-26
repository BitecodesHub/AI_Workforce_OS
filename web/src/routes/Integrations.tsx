import React from 'react'
import { Button, Card, Dialog, Eyebrow, Notice, PageHeader, Tag } from '../components/ui'
import { QueryState } from '../components/ui/QueryState'
import { useIntegrations } from '../lib/queries'

const EFFECT_TONE = { READ: 'neutral', WRITE: 'blue', OUTBOUND: 'warning', DESTRUCTIVE: 'danger' } as const
const EFFECT_LABEL = {
  READ: 'Reads only',
  WRITE: 'Changes data',
  OUTBOUND: 'Leaves the workspace',
  DESTRUCTIVE: 'Removes data',
} as const

const STATUS_TONE = { connected: 'success', sandbox: 'neutral', reconnect_required: 'warning' } as const
const STATUS_LABEL = { connected: 'Connected', sandbox: 'Sandbox', reconnect_required: 'Reconnect needed' } as const

export function Integrations() {
  const { data, isLoading, error, refetch } = useIntegrations()
  const [connectDialogOpen, setConnectDialogOpen] = React.useState(false)

  return (
    <div className="page">
      <PageHeader
        eyebrow="What the workforce can reach"
        title="Integrations"
        description="Tool servers agents act through. An agent only sees the tools it has been granted."
      />

      <Notice tone="info">
        Every server is running against a sandbox with seeded data, so nothing an agent does leaves
        this machine. Connect an account to switch a server to live.
      </Notice>

      <QueryState
        query={{ data, isLoading, error, refetch }}
        permission="integration:read"
        what="integrations"
        isEmpty={(integrations) => integrations.length === 0}
        empty={
          <div style={{ marginTop: 'var(--space-7)' }}>
            <Card as="section">
              <Eyebrow>Integrations</Eyebrow>
              <p className="muted">No integrations configured.</p>
            </Card>
          </div>
        }
        rows={4}
      >
        {(integrations) => (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))',
              gap: 'var(--space-5)',
              marginTop: 'var(--space-7)',
            }}
          >
            {integrations.map((integration) => (
              <Card key={integration.server} as="article">
                <Eyebrow>{integration.server}</Eyebrow>

                <div
                  className="row"
                  style={{ justifyContent: 'space-between', gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}
                >
                  <h2 className="section-heading" style={{ fontSize: '15px' }}>
                    {integration.displayName}
                  </h2>
                  <Tag tone={STATUS_TONE[integration.status as keyof typeof STATUS_TONE] || 'neutral'}>
                    {STATUS_LABEL[integration.status as keyof typeof STATUS_LABEL] || integration.status}
                  </Tag>
                </div>

                <div className="stack" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-5)' }}>
                  <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)', fontSize: '13px' }}>
                    <span className="muted">Status</span>
                    <Tag tone={integration.sandbox ? 'neutral' : 'success'}>{integration.sandbox ? 'Sandbox' : 'Connected'}</Tag>
                  </div>
                  {integration.accountLabel && (
                    <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)', fontSize: '13px' }}>
                      <span className="muted">Account</span>
                      <span>{integration.accountLabel}</span>
                    </div>
                  )}
                  <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)', fontSize: '13px' }}>
                    <span className="muted">Granted scopes</span>
                    <span className="mono">{integration.grantedScopes.length > 0 ? integration.grantedScopes.join(', ') : '—'}</span>
                  </div>
                  {integration.missingScopes.length > 0 && (
                    <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)', fontSize: '13px' }}>
                      <span className="muted">Missing scopes</span>
                      <Tag tone="warning">{integration.missingScopes.join(', ')}</Tag>
                    </div>
                  )}
                  <div className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)', fontSize: '13px' }}>
                    <span className="muted">Connected</span>
                    <span className="muted">{integration.connectedAt ? timeAgo(integration.connectedAt) : 'Never'}</span>
                  </div>
                </div>

                <ul className="stack" style={{ gap: 'var(--space-3)', margin: 0, padding: 0, listStyle: 'none' }}>
                  {integration.tools.map((tool) => (
                    <li key={tool.name} className="row" style={{ justifyContent: 'space-between', gap: 'var(--space-3)' }}>
                      <div>
                        <span className="mono">{tool.name}</span>
                        <p className="caption" style={{ marginTop: '2px' }}>
                          {tool.description}
                        </p>
                      </div>
                      <div className="row" style={{ gap: 'var(--space-2)', alignItems: 'center' }}>
                        <Tag tone={EFFECT_TONE[tool.sideEffect]}>{EFFECT_LABEL[tool.sideEffect]}</Tag>
                        {tool.alwaysRequiresApproval && <Tag tone="warning" withDot>Approval required</Tag>}
                      </div>
                    </li>
                  ))}
                </ul>

                <div style={{ marginTop: 'var(--space-5)' }}>
                  <Button variant="outline" onClick={() => setConnectDialogOpen(true)}>
                    Connect an account
                  </Button>
                </div>
              </Card>
            ))}
          </div>
        )}
      </QueryState>

      <Dialog
        open={connectDialogOpen}
        onClose={() => setConnectDialogOpen(false)}
        eyebrow="Sandbox mode"
        title="Live connection not available"
        description="This server runs against a sandbox. Live connection via OAuth is not yet available."
        footer={
          <Button variant="primary" onClick={() => setConnectDialogOpen(false)}>
            Got it
          </Button>
        }
      >
        <p className="muted">When OAuth is implemented, this will redirect you to the provider to grant access.</p>
      </Dialog>
    </div>
  )
}

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