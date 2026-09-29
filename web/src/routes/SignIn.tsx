import { useEffect, useState } from 'react'
import { Button, Eyebrow, Input, Notice, PasswordInput } from '../components/ui'
import { AuthShell } from '../components/auth/AuthShell'
import { AuthShowcase } from '../components/auth/AuthShowcase'
import { DemoRolePicker, type DemoAccount } from '../components/auth/DemoRolePicker'
import { saveSession } from '../lib/session'
import { useRouter } from '../lib/router'

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
 * One card, two equal halves: the form on the right and what this is on the left, both centred
 * in the card's height. The form comes first in the document, so the first Tab on a phone lands
 * in the email field rather than on links a screen below it; a wide screen still draws the
 * explanation on the left.
 */

/** 'form' while the form's own sign-in runs, or the demo address being signed in. */
type Pending = 'form' | string | null

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
  const [pending, setPending] = useState<Pending>(null)
  const [demo, setDemo] = useState<{ accounts: DemoAccount[]; password: string | null }>({
    accounts: [],
    password: null,
  })

  // Asked for rather than hard-coded, so the tiles only ever offer accounts that exist. A
  // deployed environment returns an empty list and the whole section disappears.
  useEffect(() => {
    fetch('/api/auth/demo-accounts')
      .then((response) => (response.ok ? response.json() : null))
      .then((body) => body && setDemo({ accounts: body.accounts ?? [], password: body.password ?? null }))
      .catch(() => {
        /* No demo accounts is a normal state, not an error worth showing anybody. */
      })
  }, [])

  async function signIn(withEmail: string, withPassword: string, via: Exclude<Pending, null>) {
    setPending(via)
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
      setPending(null)
    }
  }

  const busy = pending !== null

  return (
    <AuthShell
      footer={
        <>
          <a href="/home">About the platform</a>
          <a href="/home#demos">Try the demos without signing in</a>
        </>
      }
    >
      <div className="auth-duo">
        {/* The action, first in reading order. */}
        <section className="auth-pane auth-pane-form" aria-labelledby="sign-in-title">
          <div className="auth-intro">
            <Eyebrow>Sign in to continue</Eyebrow>
            <h1 id="sign-in-title" className="auth-title">
              Welcome back
            </h1>
            <p className="auth-subtitle">Use your workspace account, or explore with a demo role below.</p>
          </div>

          {error && (
            <Notice tone="warning" live>
              {error}
            </Notice>
          )}
          {!error && expired && (
            <Notice tone="info">Your session ended. Sign in again to carry on where you were.</Notice>
          )}
          {!error && !expired && signedOut && <Notice tone="success">You have signed out.</Notice>}

          <form
            className="auth-form"
            onSubmit={(event) => {
              event.preventDefault()
              if (!busy) signIn(email.trim(), password, 'form')
            }}
          >
            <Input
              label="Email address"
              type="email"
              inputMode="email"
              autoComplete="username"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
            <PasswordInput
              label="Password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
            <Button type="submit" className="auth-submit" loading={pending === 'form'} disabled={busy && pending !== 'form'}>
              {pending === 'form' ? 'Signing in' : 'Sign in'}
            </Button>
          </form>

          {demo.accounts.length > 0 && (
            <DemoRolePicker
              accounts={demo.accounts}
              password={demo.password}
              pending={pending !== 'form' ? pending : null}
              busy={busy}
              onPick={(account) => signIn(account.email, demo.password ?? '', account.email)}
            />
          )}

          <p className="auth-switch">
            New here? <a href="/create-workspace">Create a workspace</a>
          </p>
        </section>

        <AuthShowcase />
      </div>
    </AuthShell>
  )
}
