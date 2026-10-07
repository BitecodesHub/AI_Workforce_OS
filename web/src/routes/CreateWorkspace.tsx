import { useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Eyebrow, Input, Notice, PasswordInput } from '../components/ui'
import { AuthShell } from '../components/auth/AuthShell'
import { Stepper } from '../components/auth/Stepper'
import { Icon } from '../components/landing/shared/Icon'
import { TimeZoneField } from '../components/onboarding/TimeZoneField'
import { enterWorkspace, signOut } from '../lib/accountQueries'
import { api, ApiError, describeApiError, restoreSession } from '../lib/api'
import { nameList } from '../lib/format'
import { serverLabel } from '../lib/labels'
import { useRouter } from '../lib/router'
import { isSignedIn, profile, saveSession } from '../lib/session'
import { browserTimeZone, isKnownTimeZone, timeZoneChoices } from '../lib/settingsQueries'
import { createAssistants, type AssistantsAdded } from '../lib/templateQueries'
import { TEMPLATES, templateFor, type TemplateKey } from '../lib/templates'

/*
 * Creating a workspace.
 *
 * One card per step. This is a real onboarding flow, not a preview of one: it registers an
 * account, creates the workspace, grants that account the owner role, signs the person in as it,
 * and adds the ready-made assistants they ticked. The last card is deliberately an offer rather
 * than another form: assistants answer on an offline practice model until a real AI is connected,
 * so the card leads to Connect your AI, and a person can also skip it and look around first.
 *
 * Somebody who is already signed in - an account with no workspace yet (the workspace picker's
 * "Create a workspace"), or one starting another - skips the account step: registering again
 * would be refused, and they have an account. The workspace is created for that account, and the
 * session is re-issued for it (the same refresh the workspace picker uses). Whether this is that
 * kind of visit is decided when the screen opens and does not change as the flow signs the person
 * in.
 *
 * A retry picks up where the last attempt stopped. If the account was registered and a later step
 * failed, trying again must not register the same address a second time: the platform answers
 * that as it answers any taken address, and the person would be stuck. Adding the assistants
 * comes last and never undoes what came before it: the workspace exists, and an assistant that
 * could not be added is something to add later from the Agents page.
 */

type StepId = 'account' | 'workspace' | 'timezone' | 'assistants' | 'ready'

const FLOW: readonly StepId[] = ['account', 'workspace', 'timezone', 'assistants', 'ready']

/** The steps without the account step, for somebody who already has one. */
const SIGNED_IN_FLOW: readonly StepId[] = FLOW.slice(1)

/** The progress rail has room for four steps. */
const RAIL_STEPS = 4

const STEP_LABEL: Record<StepId, string> = {
  account: 'You',
  workspace: 'Workspace',
  timezone: 'Time zone',
  assistants: 'Assistants',
  ready: 'Connect AI',
}

/** Each step's heading and the one line under it. */
const STEP_COPY: Record<StepId, { title: string; lead: string }> = {
  account: { title: 'Create your account', lead: 'You become the workspace owner, and can invite everyone else afterwards.' },
  workspace: { title: 'Name your workspace', lead: 'Everybody you invite sees this name.' },
  timezone: {
    title: 'Choose a time zone',
    lead: 'Schedules use it to decide when to run. You can change it later in Workspace settings.',
  },
  assistants: {
    title: 'Which assistants do you want?',
    lead: 'Each starts from a ready-made brief that you can change afterwards. You can add or remove assistants at any time.',
  },
  ready: { title: 'Your workspace is ready', lead: 'One thing is left before the assistants answer with a real AI.' },
}

const MIN_PASSWORD = 12

/** The field each server-side field name belongs to, with the step it is on. */
const FIELDS = {
  displayName: { id: 'workspace-owner-name', label: 'Your name', step: 'account' },
  email: { id: 'workspace-owner-email', label: 'Email address', step: 'account' },
  password: { id: 'workspace-owner-password', label: 'Password', step: 'account' },
  name: { id: 'workspace-name', label: 'Workspace name', step: 'workspace' },
  timezone: { id: 'workspace-timezone', label: 'Time zone', step: 'timezone' },
} as const satisfies Record<string, { id: string; label: string; step: StepId }>

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

type Mode = 'register' | 'signed-in'

/** Whether this visit is by somebody who already has an account: ?signedIn=1, or a session in this tab. */
function startingMode(search: URLSearchParams): Mode {
  return search.get('signedIn') === '1' || isSignedIn() ? 'signed-in' : 'register'
}

export function CreateWorkspace() {
  const { search } = useRouter()
  const [mode, setMode] = useState<Mode>(() => startingMode(search))
  // A new tab at ?signedIn=1 has no token yet; the refresh cookie may still give it one.
  const [restoring, setRestoring] = useState(() => startingMode(search) === 'signed-in' && !isSignedIn())

  const flow = mode === 'register' ? FLOW : SIGNED_IN_FLOW
  const [step, setStep] = useState<StepId>(flow[0]!)
  /** Which way the last step change went, so the new step slides in from that side. */
  const [direction, setDirection] = useState<'forward' | 'back'>('forward')

  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [workspaceName, setWorkspaceName] = useState('')
  const zones = useMemo(() => timeZoneChoices(browserTimeZone()), [])
  const [timezone, setTimezone] = useState(browserTimeZone)
  // All four ticked: most workspaces want them, and clearing a tick is easier than finding them later.
  const [picked, setPicked] = useState<ReadonlySet<TemplateKey>>(() => new Set(TEMPLATES.map((template) => template.key)))
  const [added, setAdded] = useState<AssistantsAdded | null>(null)

  const [error, setError] = useState<string | null>(null)
  /** Set to the address when registration says it is taken, to offer signing in with it. */
  const [takenEmail, setTakenEmail] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldName, string>>>({})
  const [busy, setBusy] = useState(false)

  // What an earlier attempt already did, so a retry does not do it twice.
  const registered = useRef<{ email: string; password: string } | null>(null)
  const createdWorkspace = useRef<{ name: string; id: string } | null>(null)

  useEffect(() => {
    if (!restoring) return
    let cancelled = false
    void restoreSession().then((outcome) => {
      if (cancelled) return
      // No session to carry on with: the ordinary flow, which registers one.
      if (outcome !== 'restored') {
        setMode('register')
        setStep('account')
      }
      setRestoring(false)
    })
    return () => {
      cancelled = true
    }
  }, [restoring])

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

  const position = flow.indexOf(step)
  // The rail fits four steps. With the account step there are five, so the last card is not a step
  // on it: it shows all four done, and the heading says the setup is finished.
  const railFlow = flow.length > RAIL_STEPS ? flow.filter((id) => id !== 'ready') : flow

  function goTo(next: StepId) {
    setDirection(flow.indexOf(next) < flow.indexOf(step) ? 'back' : 'forward')
    setStep(next)
  }

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
    if (fieldStep === step || !flow.includes(fieldStep)) {
      document.getElementById(id)?.focus()
    } else {
      focusAfterStep.current = id
      goTo(fieldStep)
    }
  }

  /** What is missing or wrong on the current step, checked when the person tries to continue. */
  function validateStep(): Partial<Record<FieldName, string>> {
    const errors: Partial<Record<FieldName, string>> = {}
    if (step === 'account') {
      if (!fullName.trim()) errors.displayName = 'Enter your name.'
      if (!email.trim()) errors.email = 'Enter your email address.'
      else if (!/^[^\s@]+@[^\s@]+$/.test(email.trim())) {
        errors.email = 'Enter a full email address, such as priya@example.com.'
      }
      if (password.length < MIN_PASSWORD) {
        errors.password = `Use at least ${MIN_PASSWORD} characters. This one has ${password.length}.`
      }
    }
    if (step === 'workspace' && !workspaceName.trim()) errors.name = 'Enter a name for the workspace.'
    if (step === 'timezone' && !isKnownTimeZone(timezone, zones)) {
      errors.timezone = 'Choose a time zone from the list, such as Australia/Melbourne.'
    }
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
    if (step !== 'assistants') {
      goTo(flow[position + 1]!)
      return
    }
    await finish()
  }

  /** Registers the account if needed, makes the workspace, signs in as its owner, and returns the workspace's id. */
  async function createAndSignIn(): Promise<string | null> {
    const existing =
      createdWorkspace.current && createdWorkspace.current.name === workspaceName ? createdWorkspace.current.id : null

    if (mode === 'signed-in') {
      // The account already exists and so does its session; the workspace is created for it, and
      // the session is then re-issued for the workspace so the token carries owner permissions.
      let workspaceId = existing
      if (!workspaceId) {
        const workspace = await api<{ id: string }>('/api/workspaces', {
          method: 'POST',
          body: { name: workspaceName.trim(), timezone },
        })
        createdWorkspace.current = { name: workspaceName, id: workspace.id }
        workspaceId = workspace.id
      }
      await enterWorkspace(workspaceId)
      return workspaceId
    }

    // The address as checked, so a stray space typed or pasted around it cannot fail the request.
    const address = email.trim()

    // 1. Register the account, unless an earlier attempt already did with this address.
    const account = registered.current
    if (!account || account.email !== address) {
      registering.current = true
      await api('/api/auth/register', {
        method: 'POST',
        body: { email: address, displayName: fullName.trim(), password },
      })
      registering.current = false
      registered.current = { email: address, password }
      createdWorkspace.current = null
    } else if (account.password !== password) {
      // The account exists with the password typed first; a different one cannot sign in.
      showFieldErrors({
        password:
          'This account was created a moment ago with the password you entered first. Enter that password to carry on.',
      })
      return null
    }

    // 2 and 3. Create the workspace, unless an earlier attempt already did. Signing in without
    // a workspace gives a token with no permissions yet - just enough identity to create one.
    let workspaceId =
      createdWorkspace.current && createdWorkspace.current.name === workspaceName ? createdWorkspace.current.id : null
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
    return workspaceId
  }

  /** True while registering, so a refused address is told apart from a failure in a later step. */
  const registering = useRef(false)

  async function finish() {
    setBusy(true)
    registering.current = false
    try {
      const workspaceId = await createAndSignIn()
      if (!workspaceId) return

      // The workspace and the session exist now. Nothing below may undo them: createAssistants
      // never throws, and says which assistants it could not add.
      const result = await createAssistants([...picked])
      setAdded(result)
      goTo('ready')
    } catch (thrown) {
      if (thrown instanceof ApiError && thrown.code === 'already_exists' && registering.current) {
        // Registration refused the address. The platform does not say whether it is taken, so
        // neither does this; it offers both ways forward.
        setTakenEmail(email.trim())
        focusAfterStep.current = FIELDS.email.id
        goTo('account')
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

  async function switchAccount() {
    await signOut().catch(() => undefined)
    registered.current = null
    createdWorkspace.current = null
    setMode('register')
    setStep('account')
  }

  function togglePick(key: TemplateKey) {
    setPicked((current) => {
      const next = new Set(current)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  if (restoring) {
    return (
      <AuthShell footer={<a href="/home">About the platform</a>}>
        <div className="auth-solo" aria-busy="true">
          <p role="status" className="muted">
            Restoring your session
          </p>
        </div>
      </AuthShell>
    )
  }

  const copy = STEP_COPY[step]
  const last = step === 'assistants'
  const me = profile()
  // A short running count while the password is too short, so the rule is met without a failed
  // attempt. Quiet on purpose: announcing every keystroke would drown out the typing.
  const passwordHint =
    password.length > 0 && password.length < MIN_PASSWORD
      ? `${password.length} of ${MIN_PASSWORD} characters`
      : 'At least 12 characters. A passphrase is easier to remember and harder to guess.'

  return (
    <AuthShell footer={<a href="/home">About the platform</a>}>
      <div className="auth-solo">
        <Stepper steps={railFlow.map((id) => STEP_LABEL[id])} current={position} />

        <div className="auth-intro">
          <Eyebrow>
            {position >= railFlow.length
              ? 'Set up your workspace · done'
              : `Set up your workspace · step ${position + 1} of ${railFlow.length}`}
          </Eyebrow>
          <h1 ref={headingRef} tabIndex={-1} className="auth-title">
            {copy.title}
          </h1>
          <p className="auth-subtitle">{copy.lead}</p>
        </div>

        {mode === 'signed-in' && step !== 'ready' && (
          <Notice tone="info">
            You are signed in{me?.email ? ` as ${me.email}` : ''}. The new workspace will belong to this account.{' '}
            <button type="button" className="link" onClick={() => void switchAccount()}>
              Use a different account
            </button>
          </Notice>
        )}
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

        {step === 'ready' ? (
          <div key={step} className="auth-wizard">
            <div className="auth-step-panel" data-direction={direction}>
              <ul className="auth-checklist">
                <li>
                  <span className="auth-checklist-mark">
                    <Icon name="check" />
                  </span>
                  <span>Your workspace is created, and you are its owner.</span>
                </li>
                <AssistantsResult added={added} />
                <li>
                  <span className="auth-checklist-mark">
                    <Icon name="check" />
                  </span>
                  <span>
                    Until you connect an AI, every assistant answers on an offline practice model, and the interface
                    says so wherever an answer comes from it. Connecting one takes a minute: you paste a key, and we
                    check it before anything is saved.
                  </span>
                </li>
              </ul>
            </div>
            <div className="auth-actions">
              <a className="button button-outline" href="/">
                Skip for now
              </a>
              <a className="button button-primary" href="/routing?connect=1">
                Connect your AI
              </a>
            </div>
          </div>
        ) : (
          /* One form around whichever step is showing, so Enter continues from any field. The
             step's fields are keyed by step, so each one slides in from the side it came from. */
          <form noValidate onSubmit={handleSubmit} className="auth-wizard">
            <div key={step} className="auth-step-panel" data-direction={direction}>
              {step === 'account' && (
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

              {step === 'workspace' && (
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
                  placeholder="Acme Operations"
                  hint="Shown to everybody you invite."
                  error={fieldErrors.name}
                />
              )}

              {step === 'timezone' && (
                <TimeZoneField
                  id={FIELDS.timezone.id}
                  value={timezone}
                  onChange={(zone) => {
                    setTimezone(zone)
                    clearFieldError('timezone')
                  }}
                  choices={zones}
                  hint="Filled in from your browser. Start typing a city or region to change it."
                  error={fieldErrors.timezone}
                />
              )}

              {step === 'assistants' && (
                <fieldset className="question-block">
                  <legend>Assistants to add</legend>
                  {TEMPLATES.map((template) => (
                    <label key={template.key} className="question-option">
                      <input
                        type="checkbox"
                        checked={picked.has(template.key)}
                        onChange={() => togglePick(template.key)}
                      />
                      <span className="question-option-label">
                        {template.name}
                        <span className="question-option-description">{template.description}</span>
                      </span>
                    </label>
                  ))}
                </fieldset>
              )}
            </div>

            <div className="auth-actions">
              {position > 0 ? (
                <Button
                  variant="outline"
                  type="button"
                  onClick={() => {
                    setFieldErrors({})
                    goTo(flow[position - 1]!)
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
        )}
      </div>
    </AuthShell>
  )
}

/** What happened to the assistants that were ticked, as one or two lines of the final card. */
function AssistantsResult({ added }: { added: AssistantsAdded | null }) {
  if (!added || (added.created.length === 0 && added.failed.length === 0)) return null
  const names = (keys: readonly string[]) => nameList(keys.map((key) => templateFor(key)?.name ?? key), 6)
  const connectors = [...new Set(added.created.flatMap((key) => templateFor(key)?.suggestedConnectors ?? []))]
  return (
    <>
      {added.created.length > 0 && (
        <li>
          <span className="auth-checklist-mark">
            <Icon name="check" />
          </span>
          <span>
            {added.created.length === 1 ? 'Added the assistant' : `Added ${added.created.length} assistants`}:{' '}
            {names(added.created)}. They start with no access to your tools. To let them act, connect{' '}
            {nameList(connectors.map((connector) => serverLabel(connector)), 8)} on the Connectors page.
          </span>
        </li>
      )}
      {added.failed.length > 0 && (
        <li>
          <span className="auth-checklist-mark">
            <Icon name="check" />
          </span>
          <span>
            {names(added.failed)} could not be added just now. Your workspace is not affected: add{' '}
            {added.failed.length === 1 ? 'it' : 'them'} from the Agents page.
          </span>
        </li>
      )}
    </>
  )
}
