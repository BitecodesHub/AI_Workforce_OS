import { Card, Eyebrow, PageHeader, Tag } from '../components/ui'
import { formatCount, sentenceCase } from '../lib/format'
import { roleLabel } from '../lib/labels'
import { profile, initials, ROLE_CHANGE_DELAY_COPY } from '../lib/session'
import { usePermissionCatalogue, type PermissionInfo } from '../lib/queries'

/*
 * Who you are signed in as, and exactly what that allows.
 *
 * The permission list is the useful part. When something in the interface is missing, a person's
 * first question is whether it is broken or whether their role simply does not include it, and
 * this page answers that without them having to ask an administrator: what the role allows, and,
 * folded away below it, what it does not.
 */

/** Group headings in words; sentenceCase alone would print 'api_key' as 'Api key'. */
const RESOURCE_LABEL: Record<string, string> = {
  workspace: 'Workspace',
  member: 'Members',
  role: 'Roles',
  api_key: 'API keys',
  agent: 'Agents',
  task: 'Tasks and goals',
  run: 'Runs',
  chat: 'Chat',
  approval: 'Approvals',
  knowledge: 'Knowledge',
  integration: 'Integrations',
  provider: 'Model providers',
  budget: 'Budgets',
  audit: 'Audit log',
  analytics: 'Analytics',
  settings: 'Settings',
  memory: 'Agent memory',
}

/** Catalogue entries grouped by the resource they act on, in catalogue order. */
function byResource(entries: PermissionInfo[]): Array<[string, PermissionInfo[]]> {
  const grouped = new Map<string, PermissionInfo[]>()
  for (const entry of entries) {
    const list = grouped.get(entry.resource) ?? []
    list.push(entry)
    grouped.set(entry.resource, list)
  }
  return [...grouped.entries()]
}

function PermissionGroups({ groups, muted = false }: { groups: Array<[string, PermissionInfo[]]>; muted?: boolean }) {
  return (
    <div className="stack" style={{ gap: 'var(--space-5)' }}>
      {groups.map(([resource, permissions]) => (
        <div key={resource}>
          <h3 className="section-heading" style={{ marginBottom: 'var(--space-3)' }}>
            {RESOURCE_LABEL[resource] ?? sentenceCase(resource)}
          </h3>
          <ul className="stack" style={{ gap: 'var(--space-2)', margin: 0, padding: 0, listStyle: 'none' }}>
            {permissions.map((permission) => (
              <li key={permission.code} className="row" style={{ gap: 'var(--space-3)' }}>
                <span className={muted ? 'muted' : undefined} style={{ flex: 1 }} title={permission.code}>
                  {permission.description}
                </span>
                {permission.administrative && <Tag tone="warning">Administrative</Tag>}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}

export function Profile() {
  const me = profile()
  const catalogue = usePermissionCatalogue()
  const held = new Set(me?.permissions ?? [])
  const entries = catalogue.data ?? []
  const allowed = byResource(entries.filter((permission) => held.has(permission.code)))
  const missing = entries.filter((permission) => !held.has(permission.code))

  return (
    <div className="page">
      <PageHeader
        eyebrow="Your account"
        title={me?.displayName ?? 'Your profile'}
        description="Your role, and every action it allows in this workspace."
      />

      <div className="detail-grid">
        <Card as="section">
          <Eyebrow as="h2">Signed in as</Eyebrow>
          <div className="row" style={{ gap: 'var(--space-4)' }}>
            <span className="avatar avatar-large" aria-hidden="true">
              {initials(me?.displayName)}
            </span>
            <div>
              <p className="section-heading" style={{ fontSize: '15px' }}>
                {me?.displayName}
              </p>
              <p className="muted">{me?.email}</p>
            </div>
          </div>
          <dl className="facts">
            <div>
              <dt>Role</dt>
              <dd>{me?.role ? <Tag tone="blue">{roleLabel(me.role)}</Tag> : '—'}</dd>
            </div>
            <div>
              <dt>Permissions</dt>
              <dd className="tabular">{formatCount(held.size)}</dd>
            </div>
          </dl>
          <p className="caption" style={{ marginTop: 'var(--space-5)' }}>
            {ROLE_CHANGE_DELAY_COPY}
          </p>
        </Card>

        <Card as="section">
          <Eyebrow as="h2">What your role allows</Eyebrow>
          {catalogue.isLoading ? (
            <p className="muted">Loading your permissions.</p>
          ) : catalogue.error ? (
            <p className="muted">Your permissions could not be loaded. Try reloading the page.</p>
          ) : (
            <>
              {allowed.length === 0 ? (
                <p className="muted">Your role does not carry any permissions yet.</p>
              ) : (
                <PermissionGroups groups={allowed} />
              )}

              {/* Folded away, because most visits are about what the role can do. It is here for
                  the other question: is this missing, or am I simply not allowed? */}
              {missing.length > 0 && (
                <details style={{ marginTop: 'var(--space-6)' }}>
                  <summary className="section-heading" style={{ cursor: 'pointer' }}>
                    Not included in your role ({formatCount(missing.length)})
                  </summary>
                  <p className="caption" style={{ margin: 'var(--space-3) 0 var(--space-5)' }}>
                    An owner or admin can give you a role that includes these, in Members and roles.
                  </p>
                  <PermissionGroups groups={byResource(missing)} muted />
                </details>
              )}
            </>
          )}
        </Card>
      </div>
    </div>
  )
}
