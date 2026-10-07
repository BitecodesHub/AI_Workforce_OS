import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Eyebrow, Input, Notice, PasswordInput } from '../components/ui'
import { AuthShell } from '../components/auth/AuthShell'
import { Icon } from '../components/landing/shared/Icon'
import { ApiError, describeApiError } from '../lib/api'
import { signOut } from '../lib/accountQueries'
import { isAccountExists, signInToAcceptPath, useAcceptInvitationSignedIn } from '../lib/memberQueries'
import { useAcceptInvitation } from '../lib/queries'
import { useRouter } from '../lib/router'
import { isSignedIn, profile } from '../lib/session'

/*
 * Accepting an invitation.
 *
 * The link an administrator copies from Members and roles lands here with a token in the query
 * string. There is no email in this platform to have delivered it, so the token itself is the
 * whole credential.
 *
 * Two ways in. Someone new chooses a name and a password: accepting registers the account and
 * grants the invited role in one step, then sends them to sign in. Someone whose address already
 * has an account - removed and asked back, or already in another workspace - signs in first and
 * accepts as themselves. Registering tells them when that is the case and offers "Sign in to
 * accept", which comes back here with accept=1 and finishes on its own: the invitation is
 * accepted, the session moves to the workspace, and the console opens.
 *
 * Join is never disabled without a reason: pressing it checks the form and says, beside the
 * field, what needs fixing.
 */

const MIN_PASSWORD = 12

const PASSWORD_HINT = 'At least 12 characters. A passphrase is easier to remember and harder to guess.'

const MISMATCH = 'The passwords do not match.'

const INVALID_LINK =
  'This invitation link is not valid. Check that the whole link was copied, or ask whoever invited you for a new one.'

const FIELD_IDS = {
  displayName: 'invite-name',
  password: 'invite-password',
  confirm: 'invite-confirm-password',
} as const

const SIGN_IN_ID = 'invite-sign-in'

type FieldName = keyof typeof FIELD_IDS

/** A failure in words the invitee can act on. The platform's own sentence is kept where it has one. */
function acceptErrorMessage(thrown: unknown): string {
  // A mistyped, truncated or already used link is a 404; say what to do about it.
  if (thrown instanceof ApiError && thrown.status === 404) return INVALID_LINK
  if (thrown instanceof ApiError) return describeApiError(thrown, { displayName: 'Your name', password: 'Password' })
  return 'Something went wrong accepting the invitation. Try again.'
}

/**
 * Remembers, in this tab only, that the person pressed "Sign in to accept" for this invitation.
 * The way back carries accept=1, but a link anyone can craft carries it too: without this mark a
 * signed-in person would join whatever workspace such a link pointed at without being asked.
 */
const ACCEPT_INTENT_KEY = 'aiwos.acceptInvite'

function rememberAcceptIntent(token: string) {
  try {
    sessionStorage.setItem(ACCEPT_INTENT_KEY, token)
  } catch {
    // Blocked storage: the person confirms with "Accept the invitation" on the way back instead.
  }
}

function takeAcceptIntent(token: string): boolean {
  try {
    const remembered = sessionStorage.getItem(ACCEPT_INTENT_KEY)
    sessionStorage.removeItem(ACCEPT_INTENT_KEY)
    return remembered === token
  } catch {
    return false
  }
}

export function AcceptInvite() {
  const { search, navigate } = useRouter()
  const token = search.get('token') ?? ''
  // Set on the way back from "Sign in to accept": the person already chose to accept.
  const continuing = search.get('accept') === '1'
  const signInPath = token ? signInToAcceptPath(token) : '/sign-in'

  const [signedIn, setSignedIn] = useState(() => isSignedIn())
  const account = signedIn ? profile() : null

  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldName, string>>>({})
  const [done, setDone] = useState<{ email: string } | null>(null)
  // The address already has an account, so the next step is signing in, not registering.
  const [needsSignIn, setNeedsSignIn] = useState(false)
  // Joined as the signed-in account, but the session could not be moved to the workspace.
  const [joinedElsewhere, setJoinedElsewhere] = useState(false)

  const accept = useAcceptInvitation()
  const acceptSignedIn = useAcceptInvitationSignedIn()
  const started = useRef(false)

  function setFieldError(field: FieldName, message: string | null) {
    setFieldErrors((current) => {
      if ((current[field] ?? null) === message) return current
      const next = { ...current }
      if (message) next[field] = message
      else delete next[field]
      return next
    })
  }

  async function join() {
    setError(null)
    try {
      const result = await acceptSignedIn.mutateAsync(token)
      if (result.entered) navigate('/', { replace: true, scroll: true })
      else setJoinedElsewhere(true)
    } catch (thrown) {
      setError(acceptErrorMessage(thrown))
    }
  }

  // Back from signing in to accept: finish without asking a second time. Once per visit, so a
  // refused attempt shows its reason instead of being retried in a loop. Only when this tab began
  // the round trip; otherwise the page asks, as it does for any link that is simply opened.
  useEffect(() => {
    if (!token || !signedIn || !continuing || started.current) return
    started.current = true
    if (!takeAcceptIntent(token)) return
    // After this effect rather than inside it, so join's state updates are not made mid-effect.
    void Promise.resolve().then(join)
    // join reads only the token, which is part of the condition above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, signedIn, continuing])

  // When registering turns out to be the wrong step, the way forward takes focus.
  useEffect(() => {
    if (needsSignIn) document.getElementById(SIGN_IN_ID)?.focus()
  }, [needsSignIn])

  async function switchAccount() {
    await signOut()
    setSignedIn(false)
    rememberAcceptIntent(token)
    navigate(signInPath)
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)

    const errors: Partial<Record<FieldName, string>> = {}
    if (!displayName.trim()) errors.displayName = 'Enter your name.'
    if (password.length < MIN_PASSWORD) {
      errors.password = `Use at least ${MIN_PASSWORD} characters. This one has ${password.length}.`
    }
    if (confirmPassword !== password) errors.confirm = MISMATCH
    setFieldErrors(errors)
    const first = (Object.keys(FIELD_IDS) as FieldName[]).find((field) => errors[field])
    if (first) {
      document.getElementById(FIELD_IDS[first])?.focus()
      return
    }

    try {
      const result = await accept.mutateAsync({ token, displayName, password })
      setDone({ email: result.email })
    } catch (thrown) {
      if (isAccountExists(thrown)) setNeedsSignIn(true)
      else setError(acceptErrorMessage(thrown))
    }
  }

  // A running count while the password is short, so the rule is met without a failed attempt.
  // Quiet on purpose: announcing every keystroke would drown out the typing.
  const passwordHint =
    password.length > 0 && password.length < MIN_PASSWORD
      ? `${password.length} of ${MIN_PASSWORD} characters`
      : PASSWORD_HINT

  const success = Boolean(token && (done || joinedElsewhere))
  const asAccount = Boolean(token && account && !done && !joinedElsewhere)
  const subtitle = success
    ? joinedElsewhere
      ? 'You have joined the workspace. Sign in again to open it.'
      : 'Sign in with the password you just chose to open the workspace.'
    : asAccount
      ? `You are signed in as ${account?.email ?? 'your account'}. Accepting adds this account to the workspace.`
      : needsSignIn
        ? 'This email address already has an account. Sign in with it to accept the invitation.'
        : 'Choose the name people will see and a password. Joining grants the role you were invited with.'

  return (
    <AuthShell footer={<a href="/home">About the platform</a>}>
      <div className="auth-solo">
        <div className="auth-intro">
          {success && (
            <span className="auth-success-mark" aria-hidden="true">
              <Icon name="check" size={18} />
            </span>
          )}
          <Eyebrow>You have been invited</Eyebrow>
          <h1 className="auth-title">{success ? 'You are in' : 'Join the workspace'}</h1>
          <p className="auth-subtitle">{subtitle}</p>
        </div>

        {!token && (
          <Notice tone="warning">
            This link is missing its invitation token. Ask whoever invited you for the link again.
          </Notice>
        )}

        {token && done && (
          <>
            <Notice tone="success" live>
              Your account is set up and you have been added to the workspace as {done.email}.
            </Notice>
            <Button
              className="auth-submit"
              onClick={() => navigate(`/sign-in?email=${encodeURIComponent(done.email)}`)}
            >
              Continue to sign in
            </Button>
          </>
        )}

        {token && joinedElsewhere && (
          <>
            <Notice tone="success" live>
              You are now a member of the workspace.
            </Notice>
            <Button className="auth-submit" onClick={() => navigate('/sign-in')}>
              Continue to sign in
            </Button>
          </>
        )}

        {asAccount && (
          <div className="auth-form">
            {error && (
              <Notice tone="warning" live>
                {error}
              </Notice>
            )}
            <Button className="auth-submit" loading={acceptSignedIn.isPending} onClick={() => void join()}>
              {acceptSignedIn.isPending ? 'Joining the workspace' : 'Accept the invitation'}
            </Button>
            <p className="auth-switch">
              Not {account?.displayName || account?.email}?{' '}
              <a
                href={signInPath}
                onClick={(event) => {
                  event.preventDefault()
                  void switchAccount()
                }}
              >
                Sign in with a different account
              </a>
            </p>
          </div>
        )}

        {token && !account && !done && needsSignIn && (
          <div className="auth-form">
            <Notice tone="info" live>
              An account for this address already exists. Sign in with it, and the invitation is
              accepted as soon as you are back.
            </Notice>
            <Button
              id={SIGN_IN_ID}
              className="auth-submit"
              onClick={() => {
                rememberAcceptIntent(token)
                navigate(signInPath)
              }}
            >
              Sign in to accept
            </Button>
          </div>
        )}

        {token && !account && !done && !needsSignIn && (
          <form noValidate onSubmit={handleSubmit} className="auth-form">
            {error && (
              <Notice tone="warning" live>
                {error}
              </Notice>
            )}
            <Input
              id={FIELD_IDS.displayName}
              label="Your name"
              autoComplete="name"
              required
              value={displayName}
              onChange={(event) => {
                setDisplayName(event.target.value)
                setFieldError('displayName', null)
              }}
              placeholder="Priya Shah"
              error={fieldErrors.displayName}
            />
            <PasswordInput
              id={FIELD_IDS.password}
              label="Choose a password"
              autoComplete="new-password"
              required
              value={password}
              onChange={(event) => {
                setPassword(event.target.value)
                setFieldError('password', null)
              }}
              hint={passwordHint}
              error={fieldErrors.password}
            />
            <PasswordInput
              id={FIELD_IDS.confirm}
              label="Confirm password"
              autoComplete="new-password"
              required
              value={confirmPassword}
              onChange={(event) => {
                setConfirmPassword(event.target.value)
                setFieldError('confirm', null)
              }}
              // Said as soon as the person leaves the field, not only after pressing Join.
              onBlur={() => {
                if (confirmPassword && confirmPassword !== password) setFieldError('confirm', MISMATCH)
              }}
              error={fieldErrors.confirm}
            />
            <Button type="submit" className="auth-submit" loading={accept.isPending}>
              Join the workspace
            </Button>
          </form>
        )}

        {!account && !done && !needsSignIn && (
          <p className="auth-switch">
            Already have an account?{' '}
            <a href={signInPath} onClick={() => rememberAcceptIntent(token)}>
              Sign in to accept
            </a>
          </p>
        )}
      </div>
    </AuthShell>
  )
}
