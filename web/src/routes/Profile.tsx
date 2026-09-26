import { Button, Card, Eyebrow, PageHeader, Tag } from '../components/ui'
import { profile, initials } from '../lib/session'
import { usePermissionCatalogue } from '../lib/queries'

/*
 * Who you are signed in as, and exactly what that allows.
 *
 * The permission list is the useful part. When something in the interface is missing, a person's
 * first question is whether it is broken or whether their role simply does not include it, and
 * this page answers that without them having to ask an administrator.
 */
export function Profile() {
  const me = profile()
  const catalogue = usePermissionCatalogue()
  const held = new Set(me?.permissions ?? [])

  const grouped = new Map<string, Array<{ code: string; description: string; administrative: boolean }>>()
  for (const permission of catalogue.data ?? []) {
    if (!held.has(permission.code)) continue
    const list = grouped.get(permission.resource) ?? []
    list.push(permission)
    grouped.set(permission.resource, list)
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Your account"
        title={me?.displayName ?? 'Your profile'}
        description="Your role, and every action it allows in this workspace."
      />

      <div className="detail-grid">
        <Card as="section">
          <Eyebrow>Signed in as</Eyebrow>
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
              <dd>{me?.role ? <Tag tone="blue">{me.role}</Tag> : '—'}</dd>
            </div>
            <div>
              <dt>Permissions</dt>
              <dd className="tabular">{held.size}</dd>
            </div>
          </dl>
          <p className="caption" style={{ marginTop: 'var(--space-5)' }}>
            A role change takes effect the next time you sign in.
          </p>
        </Card>

        <Card as="section">
          <Eyebrow>What your role allows</Eyebrow>
          {catalogue.isLoading ? (
            <p className="muted">Loading your permissions.</p>
          ) : (
            <div className="stack" style={{ gap: 'var(--space-5)' }}>
              {[...grouped.entries()].map(([resource, permissions]) => (
                <div key={resource}>
                  <p className="section-heading" style={{ textTransform: 'capitalize', marginBottom: 'var(--space-3)' }}>
                    {resource.replace(/_/g, ' ')}
                  </p>
                  <ul className="stack" style={{ gap: 'var(--space-2)', margin: 0, padding: 0, listStyle: 'none' }}>
                    {permissions.map((permission) => (
                      <li key={permission.code} className="row" style={{ gap: 'var(--space-3)' }}>
                        <span className="muted" style={{ flex: 1 }}>
                          {permission.description}
                        </span>
                        {permission.administrative && <Tag tone="warning">Administrative</Tag>}
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>

      <div style={{ marginTop: 'var(--space-6)' }}>
        <Button variant="outline" onClick={() => window.history.back()}>
          Back
        </Button>
      </div>
    </div>
  )
}
