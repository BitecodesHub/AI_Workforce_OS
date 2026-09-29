import { useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Eyebrow, Input, Notice, PasswordInput } from '../components/ui'
import { AuthShell } from '../components/auth/AuthShell'
import { Icon } from '../components/landing/shared/Icon'
import { ApiError, describeApiError } from '../lib/api'
import { useAcceptInvitation } from '../lib/queries'
import { useRouter } from '../lib/router'

/*
 * Accepting an invitation.
 *
 * The link an administrator copies from Members and roles lands here with a token in the query
 * string. There is no email in this platform to have delivered it, so the token itself is the
 * whole credential - accepting registers the account and grants the invited role in one step,
 * then sends the person to sign in with the password they just chose.
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

type FieldName = keyof typeof FIELD_IDS

export function AcceptInvite() {
  const { search, navigate } = useRouter()
  const token = search.get('token') ?? ''

  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldName, string>>>({})
  const [done, setDone] = useState<{ email: string } | null>(null)

  const accept = useAcceptInvitation()

  function setFieldError(field: FieldName, message: string | null) {
    setFieldErrors((current) => {
      if ((current[field] ?? null) === message) return current
      const next = { ...current }
      if (message) next[field] = message
      else delete next[field]
      return next
    })
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
      // A mistyped, truncated or already used link is a 404; say what to do about it. The
      // platform's own words for an expired or revoked invitation are kept as they are.
      if (thrown instanceof ApiError && thrown.status === 404) setError(INVALID_LINK)
      else if (thrown instanceof ApiError) setError(describeApiError(thrown, { displayName: 'Your name', password: 'Password' }))
      else setError('Something went wrong accepting the invitation. Try again.')
    }
  }

  // A running count while the password is short, so the rule is met without a failed attempt.
  // Quiet on purpose: announcing every keystroke would drown out the typing.
  const passwordHint =
    password.length > 0 && password.length < MIN_PASSWORD
      ? `${password.length} of ${MIN_PASSWORD} characters`
      : PASSWORD_HINT

  return (
    <AuthShell footer={<a href="/home">About the platform</a>}>
      <div className="auth-solo">
        <div className="auth-intro">
          {token && done && (
            <span className="auth-success-mark" aria-hidden="true">
              <Icon name="check" size={18} />
            </span>
          )}
          <Eyebrow>You have been invited</Eyebrow>
          <h1 className="auth-title">{token && done ? 'You are in' : 'Join the workspace'}</h1>
          <p className="auth-subtitle">
            {token && done
              ? 'Sign in with the password you just chose to open the workspace.'
              : 'Choose the name people will see and a password. Joining grants the role you were invited with.'}
          </p>
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

        {token && !done && (
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

        <p className="auth-switch">
          Already have an account? <a href="/sign-in">Sign in</a>
        </p>
      </div>
    </AuthShell>
  )
}
