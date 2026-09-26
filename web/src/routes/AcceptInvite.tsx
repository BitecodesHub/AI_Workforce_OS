import { useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Card, Eyebrow, Input, Notice } from '../components/ui'
import { Brand } from '../components/layout/Brand'
import { ApiError } from '../lib/api'
import { useAcceptInvitation } from '../lib/queries'
import { useRouter } from '../lib/router'

/*
 * Accepting an invitation.
 *
 * The link an administrator copies from Members and roles lands here with a token in the query
 * string. There is no email in this platform to have delivered it, so the token itself is the
 * whole credential - accepting registers the account and grants the invited role in one step,
 * then sends the person to sign in with the password they just chose.
 */

export function AcceptInvite() {
  const { search, navigate } = useRouter()
  const token = search.get('token') ?? ''

  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<{ email: string } | null>(null)

  const accept = useAcceptInvitation()

  const canSubmit =
    token.length > 0 && displayName.trim().length > 0 && password.length >= 12 && password === confirmPassword

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (password !== confirmPassword) {
      setError('The passwords do not match.')
      return
    }
    try {
      const result = await accept.mutateAsync({ token, displayName, password })
      setDone({ email: result.email })
    } catch (thrown) {
      if (thrown instanceof ApiError) setError(thrown.message)
      else setError('Something went wrong accepting the invitation. Try again.')
    }
  }

  return (
    <div style={{ minHeight: '100vh', padding: 'var(--space-8) var(--space-6)' }}>
      <div style={{ maxWidth: '480px', margin: '0 auto' }}>
        <div style={{ marginBottom: 'var(--space-8)' }}>
          <Brand />
        </div>

        <Eyebrow>You have been invited</Eyebrow>
        <h1 className="page-title" style={{ fontSize: '30px', marginBottom: 'var(--space-6)' }}>
          Join the workspace
        </h1>

        {!token && (
          <Notice tone="warning">
            This link is missing its invitation token. Ask whoever invited you for the link again.
          </Notice>
        )}

        {token && done && (
          <Card>
            <div className="stack" style={{ gap: 'var(--space-5)' }}>
              <Notice tone="success">
                Your account is set up and you have been added to the workspace as {done.email}.
              </Notice>
              <Button onClick={() => navigate(`/sign-in?email=${encodeURIComponent(done.email)}`)}>
                Continue to sign in
              </Button>
            </div>
          </Card>
        )}

        {token && !done && (
          <Card>
            <form onSubmit={handleSubmit} className="stack" style={{ gap: 'var(--space-5)' }}>
              {error && <Notice tone="warning">{error}</Notice>}
              <Input
                label="Your name"
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                placeholder="Priya Shah"
              />
              <Input
                label="Choose a password"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                hint="At least 12 characters. A passphrase is easier to remember and harder to guess."
              />
              <Input
                label="Confirm password"
                type="password"
                value={confirmPassword}
                onChange={(event) => setConfirmPassword(event.target.value)}
              />
              <Button type="submit" disabled={!canSubmit} loading={accept.isPending}>
                Join the workspace
              </Button>
            </form>
          </Card>
        )}

        <p className="caption" style={{ marginTop: 'var(--space-6)' }}>
          Already have an account? <a href="/sign-in">Sign in</a>.
        </p>
      </div>
    </div>
  )
}
