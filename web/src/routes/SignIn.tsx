import { useEffect, useState } from 'react'
import { Button, Eyebrow, Input, Notice, PasswordInput } from '../components/ui'
import { AuthShell } from '../components/auth/AuthShell'
import { AuthShowcase } from '../components/auth/AuthShowcase'
import { DemoRolePicker, type DemoAccount } from '../components/auth/DemoRolePicker'
import { ResetPasswordForm } from '../components/auth/ResetPasswordForm'
import { WorkspacePicker } from '../components/auth/WorkspacePicker'
import { ApiError, describeApiError } from '../lib/api'
import {
  NETWORK_ERROR_COPY,
  enterWorkspace,
  fetchWorkspaces,
  signOut as endSession,
  storeSession,
  type SessionPayload,
  type WorkspaceChoice,
} from '../lib/accountQueries'
import { useRouter } from '../lib/router'

/*
 * Sign in.
 *
 * The one screen reached without a session, so it carries no navigation.
 *
 * Two deliberate choices. The failure message is identical whether the address is unknown, the
 * password is wrong or the account is locked, because a message that distinguishes them turns
 * this form into a way of discovering who has an account. And the demo accounts are offered
 * outright where they exist: somebody evaluating a permission model needs to sign in as a manager
 * and approve an agent's action, then as an employee and find they can hand work to agents but
 * not approve it, and asking them to build a workspace first means they never will.
 *
 * Two more steps share the card. With ?reset=<token> it shows the form for choosing a new
 * password from an administrator's reset link. And after signing in, an account in several
 * workspaces, or in none, is asked which one to open, or shown how to get one, instead of landing
 * in a console where every screen is empty.
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
  const passwordReset = search.get('passwordReset') === '1'
  const resetToken = search.get('reset')
  const [email, setEmail] = useState(search.get('email') ?? '')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState<Pending>(null)
  const [demo, setDemo] = useState<{ accounts: DemoAccount[]; password: string | null }>({
    accounts: [],
    password: null,
  })
  // Set once signed in without a workspace: the workspaces to choose from, possibly none.
  const [choices, setChoices] = useState<WorkspaceChoice[] | null>(null)
  const [opening, setOpening] = useState<string | null>(null)
  const [choiceError, setChoiceError] = useState<string | null>(null)

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

  function finish() {
    // Replaces the sign-in entry, so Back from the first screen does not reopen this form.
    navigate(next, { replace: true, scroll: true })
  }

  /** Re-issues the session for one workspace, then carries on to where the person was going. */
  async function openWorkspace(orgId: string, offered: WorkspaceChoice[]) {
    setOpening(orgId)
    setChoiceError(null)
    try {
      await enterWorkspace(orgId)
      finish()
    } catch (caught) {
      // Kept on the picker with the reason, so the person can try again or choose another.
      setChoices(offered)
      setChoiceError(caught instanceof ApiError && caught.status === 0 ? NETWORK_ERROR_COPY : describeApiError(caught))
    } finally {
      setOpening(null)
    }
  }

  /**
   * A session without a workspace carries no permissions. With exactly one workspace it opens
   * that one; with several it asks; with none it says how to get one. If the list cannot be read
   * the console opens as it did before this step existed, rather than stranding the person here.
   */
  async function chooseWorkspace() {
    let workspaces: WorkspaceChoice[]
    try {
      workspaces = await fetchWorkspaces()
    } catch {
      finish()
      return
    }
    if (workspaces.length === 1) {
      await openWorkspace(workspaces[0]!.orgId, workspaces)
      return
    }
    // Someone with no workspace who signed in to accept an invitation goes straight back to it:
    // accepting is what gives them a workspace.
    if (workspaces.length === 0 && next.startsWith('/accept-invite')) {
      finish()
      return
    }
    setChoices(workspaces)
  }

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
        setError(problem?.detail ?? 'Those details did not work, or there have been too many attempts. Try again later.')
        return
      }
      const session = (await response.json()) as SessionPayload
      storeSession(session, withEmail)
      if (session.workspaceId) {
        finish()
        return
      }
      await chooseWorkspace()
    } catch {
      setError(NETWORK_ERROR_COPY)
    } finally {
      setPending(null)
    }
  }

  /** Back to the form as somebody else, ending the session that has no workspace to open. */
  async function switchAccount() {
    await endSession()
    setChoices(null)
    setChoiceError(null)
    setPassword('')
  }

  const busy = pending !== null

  const footer = (
    <>
      <a href="/home">About the platform</a>
      <a href="/home#demos">Try the demos without signing in</a>
    </>
  )

  if (resetToken) {
    return (
      <AuthShell footer={footer}>
        <div className="auth-duo">
          <ResetPasswordForm
            token={resetToken}
            onDone={() => navigate('/sign-in?passwordReset=1', { replace: true, scroll: true })}
          />
          <AuthShowcase />
        </div>
      </AuthShell>
    )
  }

  if (choices) {
    return (
      <AuthShell footer={footer}>
        <div className="auth-duo">
          <WorkspacePicker
            workspaces={choices}
            pending={opening}
            error={choiceError}
            onPick={(orgId) => void openWorkspace(orgId, choices)}
            onUseAnotherAccount={() => void switchAccount()}
          />
          <AuthShowcase />
        </div>
      </AuthShell>
    )
  }

  return (
    <AuthShell footer={footer}>
      <div className="auth-duo">
        {/* The action, first in reading order. */}
        <section className="auth-pane auth-pane-form" aria-labelledby="sign-in-title">
          <div className="auth-intro">
            <Eyebrow>Sign in to continue</Eyebrow>
            <h1 id="sign-in-title" className="auth-title">
              Welcome back
            </h1>
            <p className="auth-subtitle">
              {demo.accounts.length > 0
                ? 'Use your workspace account, or explore with a demo role below.'
                : 'Use your workspace account.'}
            </p>
          </div>

          {error && (
            <Notice tone="warning" live>
              {error}
            </Notice>
          )}
          {!error && passwordReset && (
            <Notice tone="success">Your password has been changed. Sign in with the new one.</Notice>
          )}
          {!error && !passwordReset && expired && (
            <Notice tone="info">Your session ended. Sign in again to carry on where you were.</Notice>
          )}
          {!error && !passwordReset && !expired && signedOut && <Notice tone="success">You have signed out.</Notice>}

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

          <p className="auth-switch">Forgot your password? Ask your workspace administrator for a reset link.</p>

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
