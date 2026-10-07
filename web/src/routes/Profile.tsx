import { useState, type FormEvent } from 'react'
import {
  Button,
  Card,
  ConfirmDialog,
  Eyebrow,
  LoadingState,
  Notice,
  PageHeader,
  PasswordInput,
  Tag,
  Time,
} from '../components/ui'
import { ApiError, describeApiError } from '../lib/api'
import {
  NETWORK_ERROR_COPY,
  describeDevice,
  signOut,
  useAccountSessions,
  useChangePassword,
  useEndSession,
  type AccountSession,
} from '../lib/accountQueries'
import { formatCount, sentenceCase } from '../lib/format'
import { roleLabel } from '../lib/labels'
import { useRouter } from '../lib/router'
import { can, profile, initials, ROLE_CHANGE_DELAY_COPY } from '../lib/session'
import { usePermissionCatalogue, type PermissionInfo } from '../lib/queries'

/*
 * Who you are signed in as, exactly what that allows, and how your account is kept safe.
 *
 * The permission list is the useful part. When something in the interface is missing, a person's
 * first question is whether it is broken or whether their role simply does not include it, and
 * this page answers that without them having to ask an administrator: what the role allows, and,
 * folded away below it, what it does not.
 *
 * Security sits below it: changing the password, which signs out every other device, and the list
 * of devices that are signed in, each of which can be signed out on its own or all at once. That
 * is what a person does when they think somebody else has their password.
 */

const MINIMUM_PASSWORD_LENGTH = 12

/** The sentence for any failure on this page, with the network case in plain words. */
function failure(error: unknown): string {
  return error instanceof ApiError && error.status === 0 ? NETWORK_ERROR_COPY : describeApiError(error)
}

function ChangePassword() {
  const change = useChangePassword()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [again, setAgain] = useState('')
  const [fieldErrors, setFieldErrors] = useState<{ current?: string; next?: string; again?: string }>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [changed, setChanged] = useState(false)

  function submit(event: FormEvent) {
    event.preventDefault()
    if (change.isPending) return
    const problems: { current?: string; next?: string; again?: string } = {}
    if (!current) problems.current = 'Enter your current password.'
    if (next.length < MINIMUM_PASSWORD_LENGTH) problems.next = `Use at least ${MINIMUM_PASSWORD_LENGTH} characters.`
    else if (next !== again) problems.again = 'The two new passwords do not match.'
    setFieldErrors(problems)
    setFormError(null)
    setChanged(false)
    if (problems.current || problems.next || problems.again) return

    change.mutate(
      { currentPassword: current, newPassword: next },
      {
        onSuccess: () => {
          setChanged(true)
          setCurrent('')
          setNext('')
          setAgain('')
        },
        onError: (error) => {
          const fields = error instanceof ApiError ? error.fields : {}
          if (fields.currentPassword || fields.newPassword) {
            // The service names the problem as the rest of a sentence about the field.
            setFieldErrors({
              ...(fields.currentPassword ? { current: `Your current password ${fields.currentPassword}.` } : {}),
              ...(fields.newPassword ? { next: `The new password ${fields.newPassword}.` } : {}),
            })
          } else {
            setFormError(failure(error))
          }
        },
      },
    )
  }

  return (
    <form className="stack" style={{ gap: 'var(--space-4)', maxWidth: '420px' }} onSubmit={submit} noValidate>
      <h3 className="section-heading">Password</h3>
      {changed && (
        <Notice tone="success" live>
          Your password has been changed. Every other device has been signed out.
        </Notice>
      )}
      {formError && (
        <Notice tone="warning" live>
          {formError}
        </Notice>
      )}
      <PasswordInput
        label="Current password"
        autoComplete="current-password"
        required
        value={current}
        error={fieldErrors.current}
        onChange={(event) => setCurrent(event.target.value)}
      />
      <PasswordInput
        label="New password"
        autoComplete="new-password"
        required
        minLength={MINIMUM_PASSWORD_LENGTH}
        hint={`At least ${MINIMUM_PASSWORD_LENGTH} characters. A short sentence works well.`}
        value={next}
        error={fieldErrors.next}
        onChange={(event) => setNext(event.target.value)}
      />
      <PasswordInput
        label="Type the new password again"
        autoComplete="new-password"
        required
        value={again}
        error={fieldErrors.again}
        onChange={(event) => setAgain(event.target.value)}
      />
      <div>
        <Button type="submit" loading={change.isPending}>
          {change.isPending ? 'Changing the password' : 'Change password'}
        </Button>
      </div>
    </form>
  )
}

function DeviceRow({ session, onEnd, ending }: { session: AccountSession; onEnd: () => void; ending: boolean }) {
  const device = describeDevice(session.userAgent)
  return (
    <li
      className="row"
      style={{
        gap: 'var(--space-4)',
        justifyContent: 'space-between',
        padding: 'var(--space-4) 0',
        borderTop: '1px solid var(--line)',
      }}
    >
      <div className="stack" style={{ gap: 'var(--space-1)', minWidth: 0 }}>
        <span style={{ fontWeight: 'var(--weight-emphasis)' }}>{device}</span>
        <span className="caption">
          {session.ipAddress ? `${session.ipAddress}, ` : ''}signed in <Time iso={session.signedInAt ?? session.issuedAt} />,
          last active <Time iso={session.issuedAt} />
        </span>
      </div>
      {session.current ? (
        <Tag tone="blue">This device</Tag>
      ) : (
        <Button variant="outline" aria-label={`Sign out ${device}`} loading={ending} onClick={onEnd}>
          Sign out
        </Button>
      )}
    </li>
  )
}

function Devices() {
  const { navigate } = useRouter()
  const sessions = useAccountSessions()
  const end = useEndSession()
  const [confirming, setConfirming] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const [everywhereError, setEverywhereError] = useState<string | null>(null)

  async function signOutEverywhere() {
    setLeaving(true)
    setEverywhereError(null)
    try {
      await signOut(true)
      navigate('/sign-in?signedOut=1')
    } catch (error) {
      setEverywhereError(failure(error))
      setLeaving(false)
    }
  }

  return (
    <div className="stack" style={{ gap: 'var(--space-4)' }}>
      <h3 className="section-heading">Where you are signed in</h3>
      {end.error && (
        <Notice tone="warning" live>
          {failure(end.error)}
        </Notice>
      )}
      {sessions.isLoading ? (
        <LoadingState rows={2} label="Loading the devices you are signed in on" />
      ) : sessions.error ? (
        <p className="muted">Your devices could not be loaded. Try reloading the page.</p>
      ) : (sessions.data ?? []).length === 0 ? (
        <p className="muted">No signed-in devices were found. Sign out and in again if this looks wrong.</p>
      ) : (
        <ul style={{ listStyle: 'none', margin: 0, padding: 0 }} aria-label="Signed-in devices">
          {(sessions.data ?? []).map((session) => (
            <DeviceRow
              key={session.familyId}
              session={session}
              ending={end.isPending && end.variables === session.familyId}
              onEnd={() => end.mutate(session.familyId)}
            />
          ))}
        </ul>
      )}
      <div>
        <Button
          variant="outline"
          onClick={() => {
            setEverywhereError(null)
            setConfirming(true)
          }}
        >
          Sign out everywhere
        </Button>
      </div>
      <ConfirmDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        onConfirm={signOutEverywhere}
        eyebrow="Security"
        title="Sign out everywhere"
        description="Every device signed in to your account is signed out, this one included. You will need your password to sign in again."
        confirmLabel="Sign out everywhere"
        cancelLabel="Stay signed in"
        tone="danger"
        loading={leaving}
        error={everywhereError}
      />
    </div>
  )
}

function Security() {
  return (
    <Card as="section" className="stack">
      <Eyebrow as="h2">Security</Eyebrow>
      <div className="page-sections">
        <ChangePassword />
        <Devices />
      </div>
    </Card>
  )
}

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
  integration: 'Connectors',
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
        description="Your role, every action it allows in this workspace, and how you sign in."
        action={
          // The workspace's own name, time zone and notifications: for the people who may change them.
          can('workspace:update') ? (
            <a className="button button-outline" href="/settings">
              Workspace settings
            </a>
          ) : undefined
        }
      />

      <div className="detail-grid">
        <Card as="section">
          <Eyebrow as="h2">Signed in as</Eyebrow>
          <div className="row" style={{ gap: 'var(--space-4)' }}>
            <span className="avatar avatar-large" aria-hidden="true">
              {initials(me?.displayName)}
            </span>
            <div>
              <p className="section-heading">{me?.displayName}</p>
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

      <div style={{ marginTop: 'var(--space-5)' }}>
        <Security />
      </div>
    </div>
  )
}
