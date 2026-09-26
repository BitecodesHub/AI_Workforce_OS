import { useState } from 'react'
import { Button, Card, Eyebrow, Input, Notice, Select, Tag } from '../components/ui'
import { Brand } from '../components/layout/Brand'
import { api, ApiError } from '../lib/api'
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
 */

const STEPS = ['You', 'Workspace', 'Working hours', 'Models'] as const

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

  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [workspaceName, setWorkspaceName] = useState('')
  const [timezone, setTimezone] = useState('Australia/Melbourne')

  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  const slug = workspaceName
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')

  function canContinue(): boolean {
    if (step === 0) return fullName.trim().length > 0 && email.trim().length > 0 && password.length >= 12
    if (step === 1) return workspaceName.trim().length > 0
    return true
  }

  async function handlePrimary() {
    setError(null)
    setFieldErrors({})
    if (step < STEPS.length - 1) {
      setStep(step + 1)
      return
    }
    await finish()
  }

  async function finish() {
    setBusy(true)
    try {
      // 1. Register the account. A duplicate address is reported the same way sign-in reports a
      // wrong password - it does not confirm which addresses already exist.
      await api('/api/auth/register', {
        method: 'POST',
        body: { email, displayName: fullName, password },
      })

      // 2. Sign in. A brand new account belongs to no workspace, so this token carries no
      // permissions yet - just enough identity to create one.
      const bootstrap = await api<SignInResponse>('/api/auth/sign-in', {
        method: 'POST',
        body: { email, password },
      })
      saveSession(bootstrap.accessToken, {
        userId: bootstrap.userId,
        workspaceId: bootstrap.workspaceId,
        permissions: bootstrap.permissions,
        displayName: bootstrap.displayName,
        email: bootstrap.email,
        role: bootstrap.role,
      })

      // 3. Create the workspace. This also grants the new account the owner role.
      const workspace = await api<{ id: string }>('/api/workspaces', {
        method: 'POST',
        body: { name: workspaceName, timezone },
      })

      // 4. Sign in again, naming the new workspace, so the token now carries owner permissions.
      const owner = await api<SignInResponse>('/api/auth/sign-in', {
        method: 'POST',
        body: { email, password, workspaceId: workspace.id },
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
      if (thrown instanceof ApiError) {
        setFieldErrors(thrown.fields)
        setError(thrown.message)
        // A failure past step 0 most likely means the account exists but the workspace step
        // failed; sending the person back to the account step would make them retype a password
        // that already worked, so only genuine account failures return there.
        if (Object.keys(thrown.fields).some((f) => f === 'email' || f === 'password')) setStep(0)
      } else {
        setError('Something went wrong creating the workspace. Try again.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={{ minHeight: '100vh', padding: 'var(--space-8) var(--space-6)' }}>
      <div style={{ maxWidth: '560px', margin: '0 auto' }}>
        <div style={{ marginBottom: 'var(--space-8)' }}>
          <Brand />
        </div>

        <Eyebrow>Set up your workspace</Eyebrow>
        <h1 className="page-title" style={{ fontSize: '30px', marginBottom: 'var(--space-6)' }}>
          {STEPS[step]}
        </h1>

        <div className="row" style={{ gap: 'var(--space-3)', marginBottom: 'var(--space-6)' }}>
          {STEPS.map((label, index) => (
            <Tag key={label} tone={index === step ? 'blue' : index < step ? 'success' : 'neutral'}>
              {label}
            </Tag>
          ))}
        </div>

        {error && (
          <div style={{ marginBottom: 'var(--space-5)' }}>
            <Notice tone="warning">{error}</Notice>
          </div>
        )}

        {step === 0 && (
          <Card>
            <div className="stack" style={{ gap: 'var(--space-5)' }}>
              <Input
                label="Your name"
                value={fullName}
                onChange={(event) => setFullName(event.target.value)}
                placeholder="Priya Shah"
              />
              <Input
                label="Email address"
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="priya@example.com"
                {...(fieldErrors.email ? { error: fieldErrors.email } : {})}
              />
              <Input
                label="Password"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                hint="At least 12 characters. A passphrase is easier to remember and harder to guess."
                {...(fieldErrors.password ? { error: fieldErrors.password } : {})}
              />
            </div>
          </Card>
        )}

        {step === 1 && (
          <Card>
            <div className="stack" style={{ gap: 'var(--space-5)' }}>
              <Input
                label="Workspace name"
                value={workspaceName}
                onChange={(event) => setWorkspaceName(event.target.value)}
                placeholder="Sublime Care Australia"
                hint="Shown to everybody you invite."
              />
              <Input
                label="Web address"
                value={slug || '—'}
                readOnly
                hint="Derived from the name. If it is already taken, a number is added."
              />
            </div>
          </Card>
        )}

        {step === 2 && (
          <Card>
            <div className="stack" style={{ gap: 'var(--space-5)' }}>
              <Select label="Timezone" value={timezone} onChange={(event) => setTimezone(event.target.value)}>
                <option value="Australia/Melbourne">Australia/Melbourne</option>
                <option value="Australia/Sydney">Australia/Sydney</option>
                <option value="Australia/Perth">Australia/Perth</option>
                <option value="Asia/Kolkata">Asia/Kolkata</option>
                <option value="UTC">UTC</option>
              </Select>
              <p className="caption">
                Agents use this to decide whether an outbound message should wait until the
                workspace is open. It can be changed later in Settings.
              </p>
            </div>
          </Card>
        )}

        {step === 3 && (
          <Card>
            <div className="stack" style={{ gap: 'var(--space-5)' }}>
              <Notice tone="info">
                Nothing needs configuring to start. Every agent answers on an offline model until
                you add a provider key, and the interface says so wherever an answer comes from it.
              </Notice>
              <p className="muted">
                When you are ready, add a key for OpenRouter, Groq, NVIDIA, Gemini, Bedrock,
                Anthropic or OpenAI in Model routing. Each agent can then be given an ordered
                chain, so a provider being unavailable moves the work to the next one rather than
                stopping it.
              </p>
            </div>
          </Card>
        )}

        <div className="row" style={{ gap: 'var(--space-3)', marginTop: 'var(--space-6)' }}>
          {step > 0 && (
            <Button variant="outline" onClick={() => setStep(step - 1)} disabled={busy}>
              Back
            </Button>
          )}
          <Button onClick={handlePrimary} disabled={!canContinue()} loading={busy}>
            {step < STEPS.length - 1 ? 'Continue' : 'Create workspace'}
          </Button>
        </div>

        <p className="caption" style={{ marginTop: 'var(--space-6)' }}>
          Already have an account? <a href="/sign-in">Sign in</a>.
        </p>
      </div>
    </div>
  )
}
