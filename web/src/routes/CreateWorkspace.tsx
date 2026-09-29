import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Eyebrow, Input, Notice, PasswordInput, Select } from '../components/ui'
import { AuthShell } from '../components/auth/AuthShell'
import { Stepper } from '../components/auth/Stepper'
import { Icon } from '../components/landing/shared/Icon'
import { api, ApiError, describeApiError } from '../lib/api'
import { saveSession } from '../lib/session'
import { useRouter } from '../lib/router'

/*
 * Creating a workspace.
 *
 * Four steps, one card each. This is a real onboarding flow, not a preview of one: it registers
 * an account, creates the workspace, grants that account the owner role, and signs the person in
 * as it - the same four steps a person would otherwise need this platform's own API for. The last
 * step is deliberately an explanation rather than another form: a person setting this up needs to
 * know that it works with no credentials before they go looking for an API key.
 *
 * A retry picks up where the last attempt stopped. If the account was registered and a later step
 * failed, trying again must not register the same address a second time: the platform answers
 * that as it answers any taken address, and the person would be stuck.
 */

const STEPS = ['You', 'Workspace', 'Working hours', 'Models'] as const

/** Each step's heading and the one line under it, in the same order as STEPS. */
const STEP_COPY: ReadonlyArray<{ title: string; lead: string }> = [
  { title: 'Create your account', lead: 'You become the workspace owner, and can invite everyone else afterwards.' },
  { title: 'Name your workspace', lead: 'Everybody you invite sees this name.' },
  { title: 'Choose a timezone', lead: 'Schedules use it to decide when to run.' },
  { title: 'Ready to start', lead: 'Nothing needs configuring before your first agent runs.' },
]

const MIN_PASSWORD = 12

/** The field each server-side field name belongs to, with the step it is on. */
const FIELDS = {
  displayName: { id: 'workspace-owner-name', label: 'Your name', step: 0 },
  email: { id: 'workspace-owner-email', label: 'Email address', step: 0 },
  password: { id: 'workspace-owner-password', label: 'Password', step: 0 },
  name: { id: 'workspace-name', label: 'Workspace name', step: 1 },
  timezone: { id: 'workspace-timezone', label: 'Timezone', step: 2 },
} as const

type FieldName = keyof typeof FIELDS

const FIELD_ORDER = Object.keys(FIELDS) as FieldName[]

const FIELD_LABELS: Record<string, string> = Object.fromEntries(
  FIELD_ORDER.map((field) => [field, FIELDS[field].label]),
)

type SignInResponse = {
  accessToken: string
  userId: string
  workspaceId: string | null
  permissions: string[]
  displayName: string
  email: string
  role: string | null
}

export function CreateWorkspace() {
  const { navigate } = useRouter()
  const [step, setStep] = useState(0)
  /** Which way the last step change went, so the new step slides in from that side. */
  const [direction, setDirection] = useState<'forward' | 'back'>('forward')

  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [workspaceName, setWorkspaceName] = useState('')
  const [timezone, setTimezone] = useState('Australia/Melbourne')

  const [error, setError] = useState<string | null>(null)
  /** Set to the address when registration says it is taken, to offer signing in with it. */
  const [takenEmail, setTakenEmail] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldName, string>>>({})
  const [busy, setBusy] = useState(false)

  // What an earlier attempt already did, so a retry does not do it twice.
  const registered = useRef<{ email: string; password: string } | null>(null)
  const createdWorkspace = useRef<{ name: string; id: string } | null>(null)

  // A new step moves focus to its heading, so a screen reader announces it. When the step changed
  // because a field needs fixing, focus goes to that field instead.
  const headingRef = useRef<HTMLHeadingElement>(null)
  const shownStep = useRef(step)
  const focusAfterStep = useRef<string | null>(null)
  useEffect(() => {
    if (shownStep.current === step) return
    shownStep.current = step
    const fieldId = focusAfterStep.current
    focusAfterStep.current = null
    const target = (fieldId ? document.getElementById(fieldId) : null) ?? headingRef.current
    target?.focus()
  }, [step])

  const slug = workspaceName
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')

  function clearFieldError(field: FieldName) {
    setFieldErrors((current) => {
      if (!current[field]) return current
      const next = { ...current }
      delete next[field]
      return next
    })
  }

  /** Shows the problems and moves focus to the first one, on its own step if need be. */
  function showFieldErrors(errors: Partial<Record<FieldName, string>>) {
    setFieldErrors(errors)
    const first = FIELD_ORDER.find((field) => errors[field])
    if (!first) return
    const { id, step: fieldStep } = FIELDS[first]
    if (fieldStep === step) {
      document.getElementById(id)?.focus()
    } else {
      focusAfterStep.current = id
      setDirection(fieldStep < step ? 'back' : 'forward')
      setStep(fieldStep)
    }
  }

  /** What is missing or wrong on the current step, checked when the person tries to continue. */
  function validateStep(): Partial<Record<FieldName, string>> {
    const errors: Partial<Record<FieldName, string>> = {}
    if (step === 0) {
      if (!fullName.trim()) errors.displayName = 'Enter your name.'
      if (!email.trim()) errors.email = 'Enter your email address.'
      else if (!/^[^\s@]+@[^\s@]+$/.test(email.trim())) {
        errors.email = 'Enter a full email address, such as priya@example.com.'
      }
      if (password.length < MIN_PASSWORD) {
        errors.password = `Use at least ${MIN_PASSWORD} characters. This one has ${password.length}.`
      }
    }
    if (step === 1 && !workspaceName.trim()) errors.name = 'Enter a name for the workspace.'
    return errors
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setError(null)
    setTakenEmail(null)
    const errors = validateStep()
    if (Object.keys(errors).length > 0) {
      showFieldErrors(errors)
      return
    }
    setFieldErrors({})
    if (step < STEPS.length - 1) {
      setDirection('forward')
      setStep(step + 1)
      return
    }
    await finish()
  }

  async function finish() {
    setBusy(true)
    // True while registering, so a refused address is told apart from a failure in a later step.
    let registering = false
    // The address as checked, so a stray space typed or pasted around it cannot fail the request.
    const address = email.trim()
    try {
      // 1. Register the account, unless an earlier attempt already did with this address.
      const account = registered.current
      if (!account || account.email !== address) {
        registering = true
        await api('/api/auth/register', {
          method: 'POST',
          body: { email: address, displayName: fullName.trim(), password },
        })
        registering = false
        registered.current = { email: address, password }
        createdWorkspace.current = null
      } else if (account.password !== password) {
        // The account exists with the password typed first; a different one cannot sign in.
        showFieldErrors({
          password:
            'This account was created a moment ago with the password you entered first. Enter that password to carry on.',
        })
        return
      }

      // 2 and 3. Create the workspace, unless an earlier attempt already did. Signing in without
      // a workspace gives a token with no permissions yet - just enough identity to create one.
      let workspaceId =
        createdWorkspace.current && createdWorkspace.current.name === workspaceName
          ? createdWorkspace.current.id
          : null
      if (!workspaceId) {
        const bootstrap = await api<SignInResponse>('/api/auth/sign-in', {
          method: 'POST',
          body: { email: address, password },
        })
        saveSession(bootstrap.accessToken, {
          userId: bootstrap.userId,
          workspaceId: bootstrap.workspaceId,
          permissions: bootstrap.permissions,
          displayName: bootstrap.displayName,
          email: bootstrap.email,
          role: bootstrap.role,
        })

        // This also grants the new account the owner role.
        const workspace = await api<{ id: string }>('/api/workspaces', {
          method: 'POST',
          body: { name: workspaceName.trim(), timezone },
        })
        createdWorkspace.current = { name: workspaceName, id: workspace.id }
        workspaceId = workspace.id
      }

      // 4. Sign in again, naming the new workspace, so the token now carries owner permissions.
      const owner = await api<SignInResponse>('/api/auth/sign-in', {
        method: 'POST',
        body: { email: address, password, workspaceId },
      })
      saveSession(owner.accessToken, {
        userId: owner.userId,
        workspaceId: owner.workspaceId,
        permissions: owner.permissions,
        displayName: owner.displayName,
        email: owner.email,
        role: owner.role,
      })

      navigate('/')
    } catch (thrown) {
      if (thrown instanceof ApiError && thrown.code === 'already_exists' && registering) {
        // Registration refused the address. The platform does not say whether it is taken, so
        // neither does this; it offers both ways forward.
        setTakenEmail(address)
        focusAfterStep.current = FIELDS.email.id
        setDirection('back')
        setStep(0)
      } else if (thrown instanceof ApiError) {
        const serverFields = Object.fromEntries(
          Object.entries(thrown.fields).filter(([field]) => field in FIELDS),
        ) as Partial<Record<FieldName, string>>
        setError(describeApiError(thrown, FIELD_LABELS))
        // Only the fields that failed send the person back, and only to their own step: a
        // workspace failure must not make them retype a password that already worked.
        if (Object.keys(serverFields).length > 0) showFieldErrors(serverFields)
      } else {
        setError('Something went wrong creating the workspace. Try again.')
      }
    } finally {
      setBusy(false)
    }
  }

  const copy = STEP_COPY[step]!
  const last = step === STEPS.length - 1
  // A short running count while the password is too short, so the rule is met without a failed
  // attempt. Quiet on purpose: announcing every keystroke would drown out the typing.
  const passwordHint =
    password.length > 0 && password.length < MIN_PASSWORD
      ? `${password.length} of ${MIN_PASSWORD} characters`
      : 'At least 12 characters. A passphrase is easier to remember and harder to guess.'

  return (
    <AuthShell footer={<a href="/home">About the platform</a>}>
      <div className="auth-solo">
        <Stepper steps={STEPS} current={step} />

        <div className="auth-intro">
          <Eyebrow>
            Set up your workspace · step {step + 1} of {STEPS.length}
          </Eyebrow>
          <h1 ref={headingRef} tabIndex={-1} className="auth-title">
            {copy.title}
          </h1>
          <p className="auth-subtitle">{copy.lead}</p>
        </div>

        {error && (
          <Notice tone="warning" live>
            {error}
          </Notice>
        )}
        {takenEmail && (
          <Notice tone="warning" live>
            An account may already use this address.{' '}
            <a href={`/sign-in?email=${encodeURIComponent(takenEmail)}`}>Sign in instead</a>, or use a different
            address.
          </Notice>
        )}

        {/* One form around whichever step is showing, so Enter continues from any field. The
            step's fields are keyed by step, so each one slides in from the side it came from. */}
        <form noValidate onSubmit={handleSubmit} className="auth-wizard">
          <div key={step} className="auth-step-panel" data-direction={direction}>
            {step === 0 && (
              <>
                <Input
                  id={FIELDS.displayName.id}
                  label="Your name"
                  autoComplete="name"
                  required
                  value={fullName}
                  onChange={(event) => {
                    setFullName(event.target.value)
                    clearFieldError('displayName')
                  }}
                  placeholder="Priya Shah"
                  error={fieldErrors.displayName}
                />
                <Input
                  id={FIELDS.email.id}
                  label="Email address"
                  type="email"
                  inputMode="email"
                  autoComplete="email"
                  required
                  value={email}
                  onChange={(event) => {
                    setEmail(event.target.value)
                    clearFieldError('email')
                  }}
                  placeholder="priya@example.com"
                  error={fieldErrors.email}
                />
                <PasswordInput
                  id={FIELDS.password.id}
                  label="Password"
                  autoComplete="new-password"
                  required
                  value={password}
                  onChange={(event) => {
                    setPassword(event.target.value)
                    clearFieldError('password')
                  }}
                  hint={passwordHint}
                  error={fieldErrors.password}
                />
              </>
            )}

            {step === 1 && (
              <>
                <Input
                  id={FIELDS.name.id}
                  label="Workspace name"
                  autoComplete="organization"
                  required
                  value={workspaceName}
                  onChange={(event) => {
                    setWorkspaceName(event.target.value)
                    clearFieldError('name')
                  }}
                  placeholder="Sublime Care Australia"
                  hint="Shown to everybody you invite."
                  error={fieldErrors.name}
                />
                <Input
                  label="Web address"
                  value={slug || '—'}
                  readOnly
                  hint="Derived from the name. If it is already taken, a number is added."
                />
              </>
            )}

            {step === 2 && (
              <Select
                id={FIELDS.timezone.id}
                label="Timezone"
                value={timezone}
                onChange={(event) => {
                  setTimezone(event.target.value)
                  clearFieldError('timezone')
                }}
                hint="Stored with the workspace. It cannot yet be changed from the console."
                error={fieldErrors.timezone}
              >
                <option value="Australia/Melbourne">Australia/Melbourne</option>
                <option value="Australia/Sydney">Australia/Sydney</option>
                <option value="Australia/Perth">Australia/Perth</option>
                <option value="Asia/Kolkata">Asia/Kolkata</option>
                <option value="UTC">UTC</option>
              </Select>
            )}

            {step === 3 && (
              <ul className="auth-checklist">
                <li>
                  <span className="auth-checklist-mark">
                    <Icon name="check" />
                  </span>
                  <span>
                    Every agent answers on an offline model until you add a provider key, and the interface says so
                    wherever an answer comes from it.
                  </span>
                </li>
                <li>
                  <span className="auth-checklist-mark">
                    <Icon name="check" />
                  </span>
                  <span>
                    When you are ready, add a key for OpenRouter, Groq, NVIDIA, Gemini, Bedrock, Anthropic or OpenAI in
                    Model routing.
                  </span>
                </li>
                <li>
                  <span className="auth-checklist-mark">
                    <Icon name="check" />
                  </span>
                  <span>
                    Each agent can be given an ordered chain, so a provider being unavailable moves the work to the
                    next one rather than stopping it.
                  </span>
                </li>
              </ul>
            )}
          </div>

          <div className="auth-actions">
            {step > 0 ? (
              <Button
                variant="outline"
                type="button"
                onClick={() => {
                  setFieldErrors({})
                  setDirection('back')
                  setStep(step - 1)
                }}
                disabled={busy}
              >
                Back
              </Button>
            ) : (
              <span className="auth-actions-note">
                Have an account? <a href="/sign-in">Sign in</a>
              </span>
            )}
            <Button type="submit" loading={busy}>
              {last ? 'Create workspace' : 'Continue'}
            </Button>
          </div>
        </form>
      </div>
    </AuthShell>
  )
}
