import { useEffect, useState, type CSSProperties } from 'react'
import { Button, Eyebrow, Input, Notice, Tag } from '../components/ui'
import { roleLabel } from '../lib/labels'
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
 * evaluating a permission model needs to sign in as a manager and approve an agent's action,
 * then as an employee and find they can hand work to agents but not approve it, and asking them
 * to build a workspace first means they never will.
 *
 * The form comes first in the document, so the first Tab on a phone lands in the email field
 * rather than on links a screen below it; on a wide screen components.css still draws the
 * explanation on the left.
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

const ROLE_COLOUR: Record<'blue' | 'success' | 'warning' | 'neutral', string> = {
  blue: 'var(--blue)',
  success: 'var(--green)',
  warning: 'var(--warning-ink)',
  neutral: 'var(--muted)',
}

function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return ''
  if (words.length === 1) return words[0]!.slice(0, 2).toUpperCase()
  return (words[0]![0]! + words[words.length - 1]![0]!).toUpperCase()
}

export function SignIn() {
  const { search, navigate } = useRouter()
  // Where to go afterwards. Only same-site paths are honoured, so a crafted link cannot bounce a
  // person to another site straight after they have entered their password.
  const requested = search.get('next')
  const next = requested && requested.startsWith('/') && !requested.startsWith('//') ? requested : '/'
  const expired = search.get('expired') === '1'
  const signedOut = search.get('signedOut') === '1'
  const [email, setEmail] = useState(search.get('email') ?? '')
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
      // Replaces the sign-in entry, so Back from the first screen does not reopen this form.
      navigate(next, { replace: true, scroll: true })
    } catch {
      setError('The service could not be reached. Check that the platform is running, then try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main id="main" className="auth-page">
      <div className="auth-atmosphere" aria-hidden="true" />
      <div className="auth-grid" aria-hidden="true" />

      {/* Shown above the split at every width, so the screen reads as this product's from the
          first paint - not only once a wide screen draws the pitch on the left, or a phone
          scrolls past the form to reach it. */}
      <div className="auth-header">
        <Brand />
      </div>

      <div className="auth-split">
        {/* The action, first in reading order. */}
        <div className="auth-panel">
          <Eyebrow>Sign in to continue</Eyebrow>
          <h1 className="page-title" style={{ fontSize: '28px', marginBottom: 'var(--space-6)' }}>
            Welcome back
          </h1>

          {error && (
            <div style={{ marginBottom: 'var(--space-5)' }}>
              <Notice tone="warning" live>
                {error}
              </Notice>
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
              signIn(email.trim(), password)
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
            />
            <Button type="submit" disabled={busy}>
              {busy ? 'Signing in' : 'Sign in'}
            </Button>
          </form>

          <p className="caption" style={{ marginTop: 'var(--space-5)', marginBottom: 'var(--space-6)' }}>
            New here? <a href="/create-workspace">Create a workspace</a>.
          </p>

          {demo.accounts.length > 0 && (
            <section style={{ marginTop: 'var(--space-6)', paddingTop: 'var(--space-6)', borderTop: '1px solid var(--line)' }}>
              <Eyebrow>Try it without signing up</Eyebrow>
              <h2 className="section-heading" style={{ fontSize: '15px', marginBottom: 'var(--space-3)' }}>
                Demo accounts
              </h2>
              <p className="muted" style={{ marginBottom: 'var(--space-5)' }}>
                One per role, in a shared demo workspace. Sign in as each to see what the
                permission model actually allows: a manager can approve an agent&apos;s action, an
                employee can hand work to agents but not approve it, and a viewer can look but
                cannot start anything.
              </p>

              <div className="stack" style={{ gap: 'var(--space-3)' }}>
                {demo.accounts.map((account) => {
                  const tone = ROLE_TONE[account.role] ?? 'neutral'
                  return (
                    <button
                      key={account.email}
                      type="button"
                      className="demo-account"
                      disabled={busy}
                      onClick={() => signIn(account.email, demo.password ?? '')}
                    >
                      <span
                        className="demo-account-avatar"
                        aria-hidden="true"
                        style={{ '--avatar-colour': ROLE_COLOUR[tone] } as CSSProperties}
                      >
                        {initialsOf(account.displayName)}
                      </span>
                      <span className="row" style={{ gap: 'var(--space-3)', justifyContent: 'space-between' }}>
                        <span className="menu-item-label">{account.displayName}</span>
                        <Tag tone={tone}>{roleLabel(account.role)}</Tag>
                      </span>
                      <span className="menu-item-note">{account.describes}</span>
                      <span className="mono muted" style={{ fontSize: '10px' }}>
                        {account.email}
                      </span>
                    </button>
                  )
                })}
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
            </section>
          )}
        </div>

        {/* What this is. Somebody arriving at a login screen cold needs to know what they are
            signing in to before the form is useful to them. Drawn on the left on a wide screen.
            The brand mark itself is shown once, above this split, at every width. */}
        <aside className="auth-aside">
          <Eyebrow>A governed AI workforce</Eyebrow>
          <h2 className="page-title" style={{ fontSize: '30px', maxWidth: '18ch', marginBottom: 'var(--space-4)' }}>
            Agents that do the work, and stop before the part you would want to check
          </h2>
          {/* Each claim here is one the evaluator can check after signing in. Agents do not read
              the knowledge base (only Chat searches it), and the bundled tool servers run
              against a sandbox, so neither is promised. */}
          <p className="page-description" style={{ marginBottom: 'var(--space-6)' }}>
            Configure agents for the roles you already have. They act through the tool servers
            you grant them, and wait for a person before anything leaves the workspace.
          </p>

          <ul className="stack auth-points" style={{ gap: 'var(--space-4)' }}>
            <li>
              <span className="menu-item-label">Every action is permission-checked</span>
              <span className="menu-item-note">
                Roles are composed from individual permissions and edited in the console.
              </span>
            </li>
            <li>
              <span className="menu-item-label">Document search shows its sources</span>
              <span className="menu-item-note">
                Chat returns the passages that match a question, each with the document it came from.
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
      </div>
    </main>
  )
}
