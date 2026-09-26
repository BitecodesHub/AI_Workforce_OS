import { useEffect, useState } from 'react'
import { Button, Card, Eyebrow, Input, Notice, Tag } from '../components/ui'
import { saveSession } from '../lib/session'
import { useRouter } from '../lib/router'
import { Brand } from '../components/layout/Brand'

/*
 * Sign in.
 *
 * The one screen reached without a session, so it carries no navigation.
 *
 * Two deliberate choices. The failure message is identical whether the address is unknown or the
 * password is wrong, because a message that distinguishes them turns this form into a way of
 * discovering who has an account. And the demo accounts are offered outright: somebody
 * evaluating a permission model needs to sign in as an employee and find that Members is not
 * there, and asking them to build a workspace first means they never will.
 */

type DemoAccount = {
  email: string
  displayName: string
  role: string
  describes: string
}

const ROLE_TONE: Record<string, 'blue' | 'success' | 'warning' | 'neutral'> = {
  owner: 'blue',
  admin: 'blue',
  manager: 'success',
  employee: 'neutral',
  viewer: 'neutral',
}

export function SignIn() {
  const { search, navigate } = useRouter()
  // Where to go afterwards. Only same-site paths are honoured, so a crafted link cannot bounce a
  // person to another site straight after they have entered their password.
  const requested = search.get('next')
  const next = requested && requested.startsWith('/') && !requested.startsWith('//') ? requested : '/'
  const expired = search.get('expired') === '1'
  const signedOut = search.get('signedOut') === '1'
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [demo, setDemo] = useState<{ accounts: DemoAccount[]; password: string | null }>({
    accounts: [],
    password: null,
  })

  // Asked for rather than hard-coded, so the buttons only ever offer accounts that exist. A
  // deployed environment returns an empty list and the whole section disappears.
  useEffect(() => {
    fetch('/api/auth/demo-accounts')
      .then((response) => (response.ok ? response.json() : null))
      .then((body) => body && setDemo({ accounts: body.accounts ?? [], password: body.password ?? null }))
      .catch(() => {
        /* No demo accounts is a normal state, not an error worth showing anybody. */
      })
  }, [])

  async function signIn(withEmail: string, withPassword: string) {
    setBusy(true)
    setError(null)
    try {
      const response = await fetch('/api/auth/sign-in', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email: withEmail, password: withPassword }),
      })
      if (!response.ok) {
        const problem = await response.json().catch(() => null)
        setError(problem?.detail ?? 'The email address or password is incorrect.')
        return
      }
      const session = await response.json()
      saveSession(session.accessToken, {
        userId: session.userId,
        workspaceId: session.workspaceId ?? null,
        permissions: session.permissions ?? [],
        displayName: session.displayName ?? withEmail,
        email: session.email ?? withEmail,
        role: session.role ?? null,
      })
      navigate(next)
    } catch {
      setError('The service could not be reached. Check that the platform is running, then try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={{ minHeight: '100vh' }}>
      <div className="auth-split">
        {/* Left: what this is. Somebody arriving at a login screen cold needs to know what they
            are signing in to before the form is useful to them. */}
        <aside className="auth-aside">
          <div style={{ marginBottom: 'var(--space-7)' }}>
            <Brand />
          </div>

          <Eyebrow>A governed AI workforce</Eyebrow>
          <h1 className="page-title" style={{ fontSize: '30px', maxWidth: '18ch', marginBottom: 'var(--space-4)' }}>
            Agents that do the work, and stop before the part you would want to check
          </h1>
          <p className="page-description" style={{ marginBottom: 'var(--space-6)' }}>
            Configure agents for the roles you already have. They answer from your own documents,
            act through your real tools, and wait for a person before anything leaves the
            workspace.
          </p>

          <ul className="stack auth-points" style={{ gap: 'var(--space-4)' }}>
            <li>
              <span className="menu-item-label">Every action is permission-checked</span>
              <span className="menu-item-note">
                Roles are composed from individual permissions and edited in the console.
              </span>
            </li>
            <li>
              <span className="menu-item-label">Every answer carries its sources</span>
              <span className="menu-item-note">
                When no document supports an answer, the agent says so rather than guessing.
              </span>
            </li>
            <li>
              <span className="menu-item-label">Seven model providers, in a chain</span>
              <span className="menu-item-note">
                A provider that is throttled or down is skipped, and the trace records why.
              </span>
            </li>
            <li>
              <span className="menu-item-label">Nothing to configure to start</span>
              <span className="menu-item-note">
                It runs on an offline model until you add a provider key.
              </span>
            </li>
          </ul>

          <p className="caption" style={{ marginTop: 'var(--space-6)' }}>
            <a href="/home">Read more about what it does</a>
          </p>
        </aside>

        {/* Right: the action. */}
        <div>
          <Eyebrow>Sign in to continue</Eyebrow>
          <h2 className="page-title" style={{ fontSize: '24px', marginBottom: 'var(--space-6)' }}>
            Welcome back
          </h2>

          {error && (
            <div style={{ marginBottom: 'var(--space-5)' }}>
              <Notice tone="warning">{error}</Notice>
            </div>
          )}
          {!error && expired && (
            <div style={{ marginBottom: 'var(--space-5)' }}>
              <Notice tone="info">Your session ended. Sign in again to carry on where you were.</Notice>
            </div>
          )}
          {!error && !expired && signedOut && (
            <div style={{ marginBottom: 'var(--space-5)' }}>
              <Notice tone="success">You have signed out.</Notice>
            </div>
          )}

          <form
            className="stack"
            style={{ gap: 'var(--space-5)' }}
            onSubmit={(event) => {
              event.preventDefault()
              signIn(email, password)
            }}
          >
            <Input
              label="Email address"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
            <Input
              label="Password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              hint="At least 12 characters. A passphrase is easier to remember and harder to guess."
            />
            <Button type="submit" disabled={busy}>
              {busy ? 'Signing in' : 'Sign in'}
            </Button>
          </form>

          <p className="caption" style={{ marginTop: 'var(--space-5)', marginBottom: 'var(--space-6)' }}>
            New here? <a href="/create-workspace">Create a workspace</a>.
          </p>

        {demo.accounts.length > 0 && (
          <Card as="section">
            <Eyebrow>Try it without signing up</Eyebrow>
            <h2 className="section-heading" style={{ fontSize: '15px', marginBottom: 'var(--space-3)' }}>
              Demo accounts
            </h2>
            <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
              One per role, in a shared demo workspace. Sign in as each to see what the
              permission model actually allows: a manager can approve an agent action, an
              employee cannot see Members at all.
            </p>

            <div className="stack" style={{ gap: 'var(--space-3)' }}>
              {demo.accounts.map((account) => (
                <button
                  key={account.email}
                  type="button"
                  className="demo-account"
                  disabled={busy}
                  onClick={() => signIn(account.email, demo.password ?? '')}
                >
                  <span className="row" style={{ gap: 'var(--space-3)', justifyContent: 'space-between' }}>
                    <span className="menu-item-label">{account.displayName}</span>
                    <Tag tone={ROLE_TONE[account.role] ?? 'neutral'}>{account.role}</Tag>
                  </span>
                  <span className="menu-item-note">{account.describes}</span>
                  <span className="mono muted" style={{ fontSize: '10px' }}>
                    {account.email}
                  </span>
                </button>
              ))}
            </div>

            {demo.password && (
              <div style={{ marginTop: 'var(--space-5)' }}>
                {/* Stated outright. A shared demo password that everybody knows, described as
                    though it were a secret, is worse than one nobody pretends about. */}
                <Notice tone="info">
                  These accounts share the password <span className="mono">{demo.password}</span>.
                  They exist only in local and test environments.
                </Notice>
              </div>
            )}
          </Card>
        )}
        </div>
      </div>
    </div>
  )
}
