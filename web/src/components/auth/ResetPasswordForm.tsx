// @find: reset password, forgot password, new password, set password from link, reset token, password reset form, confirm password, POST /api/auth/password-reset, ResetPasswordForm
// @what: Form that sets a new password using the token from an emailed reset link.
// @flow: Shown by the sign in page when a reset token is in the URL
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Button, Eyebrow, Notice, PasswordInput } from '../ui'
import { ApiError } from '../../lib/api'
import { NETWORK_ERROR_COPY, resetPassword } from '../../lib/accountQueries'

/*
 * Choosing a new password from a reset link.
 *
 * An administrator creates the link and passes it on; there is no email to send it. The link
 * works once and for thirty minutes, so every way it can fail ends in the same advice: ask for a
 * new one. A password that is too short is caught here first, so the link is not spent on it.
 *
 * Takes the form half of the sign-in card, with the heading focused on arrival so a screen reader
 * starts at the task rather than at the top of a page it has just been sent to.
 */

const MINIMUM_LENGTH = 12

type FieldErrors = { password?: string; confirmation?: string }

/** The errors with one field's message gone, once the person starts retyping that field. */
function without(errors: FieldErrors, field: keyof FieldErrors): FieldErrors {
  if (!errors[field]) return errors
  const next = { ...errors }
  delete next[field]
  return next
}

const PASSWORD_ID = 'reset-password'
const CONFIRMATION_ID = 'reset-password-again'

// @find: ResetPasswordForm, reset password, POST /api/auth/password-reset
export function ResetPasswordForm({ token, onDone }: { token: string; onDone: () => void }) {
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [fieldErrors, setFieldErrors] = useState<{ password?: string; confirmation?: string }>({})
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const heading = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    heading.current?.focus()
  }, [])

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (pending) return
    const problems: { password?: string; confirmation?: string } = {}
    if (password.length < MINIMUM_LENGTH) problems.password = `Use at least ${MINIMUM_LENGTH} characters.`
    else if (password !== confirmation) problems.confirmation = 'The two passwords do not match.'
    setFieldErrors(problems)
    // The field that needs fixing takes focus, so its message is read and it can be retyped.
    if (problems.password) document.getElementById(PASSWORD_ID)?.focus()
    else if (problems.confirmation) document.getElementById(CONFIRMATION_ID)?.focus()
    if (problems.password || problems.confirmation) return

    setPending(true)
    setError(null)
    try {
      await resetPassword(token, password)
      onDone()
    } catch (caught) {
      setError(caught instanceof ApiError && caught.status !== 0 ? caught.message : NETWORK_ERROR_COPY)
    } finally {
      setPending(false)
    }
  }

  return (
    <section className="auth-pane auth-pane-form" aria-labelledby="reset-title">
      <div className="auth-intro">
        <Eyebrow>Password reset</Eyebrow>
        <h1 id="reset-title" className="auth-title" tabIndex={-1} ref={heading}>
          Choose a new password
        </h1>
        <p className="auth-subtitle">
          Use at least {MINIMUM_LENGTH} characters. A short sentence is easier to remember than a jumble of symbols.
        </p>
      </div>

      {error && (
        <Notice tone="warning" live>
          {error}
        </Notice>
      )}

      <form className="auth-form" onSubmit={submit} noValidate>
        <PasswordInput
          id={PASSWORD_ID}
          label="New password"
          autoComplete="new-password"
          required
          minLength={MINIMUM_LENGTH}
          value={password}
          error={fieldErrors.password}
          onChange={(event) => {
            setPassword(event.target.value)
            setFieldErrors((current) => without(current, 'password'))
          }}
        />
        <PasswordInput
          id={CONFIRMATION_ID}
          label="Type the new password again"
          autoComplete="new-password"
          required
          value={confirmation}
          error={fieldErrors.confirmation}
          onChange={(event) => {
            setConfirmation(event.target.value)
            setFieldErrors((current) => without(current, 'confirmation'))
          }}
        />
        <Button type="submit" className="auth-submit" loading={pending}>
          {pending ? 'Saving the new password' : 'Save the new password'}
        </Button>
      </form>

      <p className="auth-switch">
        Changed your mind? <a href="/sign-in">Back to sign in</a>
      </p>
    </section>
  )
}
