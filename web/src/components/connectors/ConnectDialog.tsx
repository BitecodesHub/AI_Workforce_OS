import { useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Button, Dialog, Input, Notice, PasswordInput } from '../ui'
import { CopyButton } from '../ui/CopyButton'
import { ApiError, describeApiError } from '../../lib/api'
import { isHttpsUrl, placeFieldProblems, safeDocsUrl, secretNoun, usesSignIn, webAddressProblem } from '../../lib/connectors'
import { serverLabel } from '../../lib/labels'
import { goToProvider } from '../../lib/redirect'
import { useConnectIntegration, useOAuthApp, useSaveOAuthApp, useStartOAuth } from '../../lib/queries'
import type { CredentialField, Integration, OAuthSetup } from '../../lib/queries'
import { useToast } from '../../lib/toast'

/*
 * Connect a connector to a live account, in one of three ways chosen by the catalog:
 *  - token or address: one box per credential field (or the single secret box), checked before saving;
 *  - sign in: two steps in one dialog. Set up the app once per workspace and provider, then go to the
 *    provider to approve. The secret values are never shown again once saved.
 */

const STACK = { gap: 'var(--space-5)' } as const

/**
 * Where a refused save is shown: under the fields it names, and for the whole dialog only what
 * belongs to none of them. Focus goes to the first field with a problem.
 */
function useFieldProblems(formRef: React.RefObject<HTMLFormElement | null>) {
  const [inline, setInline] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)

  const focusFirst = () =>
    window.setTimeout(() => formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus(), 0)

  return {
    inline,
    error,
    clear: () => {
      setInline({})
      setError(null)
    },
    /** A field changed: its own problem no longer applies. */
    edited: (key: string) =>
      setInline((current) => {
        if (!(key in current)) return current
        const next = { ...current }
        delete next[key]
        return next
      }),
    /** A failure for the whole dialog. */
    fail: (message: string) => {
      setInline({})
      setError(message)
    },
    /** Problems found before sending. */
    local: (problems: Record<string, string>) => {
      setInline(problems)
      setError(null)
      focusFirst()
    },
    failed: (
      err: unknown,
      fields: ReadonlyArray<{ key: string; label: string }>,
      aliases: Record<string, string>,
      labels: Record<string, string>,
    ) => {
      const placed = err instanceof ApiError ? placeFieldProblems(err, fields, aliases) : { inline: {}, unplaced: true }
      setInline(placed.inline)
      setError(placed.unplaced ? describeApiError(err, labels) : null)
      if (Object.keys(placed.inline).length > 0) focusFirst()
    },
  }
}

export function ConnectDialog({
  open,
  integration,
  onClose,
}: {
  open: boolean
  integration: Integration
  onClose: () => void
}) {
  return usesSignIn(integration) ? (
    <SignInDialog open={open} integration={integration} oauth={integration.oauth as OAuthSetup} onClose={onClose} />
  ) : (
    <TokenDialog open={open} integration={integration} onClose={onClose} />
  )
}

function DocsLink({ url, name }: { url: string | null; name: string }) {
  if (!url) return null
  return (
    <p>
      <a className="link" href={url} target="_blank" rel="noopener noreferrer">
        Read {name}'s own instructions
        <span className="visually-hidden"> (opens in a new tab)</span>
      </a>
    </p>
  )
}

function OrderedSteps({ heading, steps }: { heading: string; steps: string[] }) {
  if (steps.length === 0) return null
  return (
    <div>
      <h3 className="field-label">{heading}</h3>
      <ol
        className="stack"
        style={{ gap: 'var(--space-2)', margin: 'var(--space-2) 0 0', paddingLeft: 'var(--space-6)', listStyle: 'decimal' }}
      >
        {steps.map((step, index) => (
          <li key={index}>{step}</li>
        ))}
      </ol>
    </div>
  )
}

/* ---- Token, address ----------------------------------------------------------------------------- */

function TokenDialog({ open, integration, onClose }: { open: boolean; integration: Integration; onClose: () => void }) {
  const toast = useToast()
  const connect = useConnectIntegration()
  const name = serverLabel(integration.server, integration.displayName)
  const replacing = !integration.sandbox
  const noun = secretNoun(integration)
  const tokenLabel = integration.tokenLabel?.trim() || 'Access token'
  const declared = integration.credentialFields ?? []
  // No fields declared: the single box labelled by tokenLabel, kept under its own key.
  const fields: CredentialField[] =
    declared.length > 0 ? declared : [{ key: 'token', label: tokenLabel, secret: integration.authType !== 'url' }]
  const multi = declared.length >= 2
  const [values, setValues] = useState<Record<string, string>>({})
  const formRef = useRef<HTMLFormElement>(null)
  const problems = useFieldProblems(formRef)
  const steps = (integration.setupSteps ?? []).filter((step) => step.trim())
  const docsUrl = safeDocsUrl(integration.docsUrl)
  const formId = `connect-${integration.server}`
  const filled = fields.every((field) => (values[field.key] ?? '').trim() !== '')

  const close = () => {
    if (connect.isPending) return
    onClose()
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (!filled || connect.isPending) return
    const trimmed = Object.fromEntries(fields.map((field) => [field.key, (values[field.key] ?? '').trim()]))
    if (integration.authType === 'url') {
      const found = Object.fromEntries(
        fields.flatMap((field) => {
          const problem = webAddressProblem(trimmed[field.key] ?? '')
          return problem ? [[field.key, problem]] : []
        }),
      )
      if (Object.keys(found).length > 0) {
        problems.local(found)
        return
      }
    }
    problems.clear()
    try {
      const saved = await connect.mutateAsync(
        multi
          ? { server: integration.server, fields: trimmed }
          : { server: integration.server, token: trimmed[fields[0]!.key] ?? '' },
      )
      const account = saved?.accountLabel ? ` as ${saved.accountLabel}` : ''
      toast.success(replacing ? `The ${name} ${noun} was replaced${account}` : `${name} is connected${account}`)
      onClose()
    } catch (err) {
      const labels = Object.fromEntries(fields.map((field) => [field.key, field.label]))
      // A single box is sent as `token`, whatever its own key.
      problems.failed(err, fields, multi ? {} : { token: fields[0]!.key }, labels)
    }
  }

  const hintFor = (field: CredentialField, first: boolean) => {
    if (field.help?.trim()) return field.help.trim()
    if (!first) return undefined
    return replacing
      ? `The new ${noun} replaces the stored one once it passes the check.`
      : 'Agents use the live account from the moment it is saved. Until then they use practice data.'
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      eyebrow={replacing ? `Replace the ${noun}` : 'Connect a live account'}
      title={replacing ? `Replace the ${name} ${noun}` : `Connect ${name}`}
      description={
        noun === 'address'
          ? 'The address is checked before it is saved. It is stored encrypted and never shown again.'
          : `The ${multi ? 'details are' : 'token is'} checked with ${name} before ${multi ? 'they are' : 'it is'} saved. ${multi ? 'They are' : 'It is'} stored encrypted and never shown again.`
      }
      dismissible={!connect.isPending}
      error={problems.error}
      footer={
        <>
          <Button variant="outline" onClick={close} disabled={connect.isPending}>
            Cancel
          </Button>
          <Button type="submit" form={formId} loading={connect.isPending} disabled={!filled}>
            {replacing ? `Replace ${noun}` : 'Connect'}
          </Button>
        </>
      }
    >
      <form ref={formRef} id={formId} onSubmit={handleSubmit} className="stack" style={STACK} noValidate>
        <OrderedSteps heading="Where to find it" steps={steps} />
        <DocsLink url={docsUrl} name={name} />
        {fields.map((field, index) => {
          const common = {
            label: field.label,
            value: values[field.key] ?? '',
            onChange: (event: { target: { value: string } }) => {
              problems.edited(field.key)
              setValues((current) => ({ ...current, [field.key]: event.target.value }))
            },
            autoComplete: 'off',
            required: true,
            placeholder: field.placeholder ?? undefined,
            hint: hintFor(field, index === 0),
            error: problems.inline[field.key] ?? null,
            ...(index === 0 ? { 'data-autofocus': true } : {}),
          }
          return field.secret ? (
            <PasswordInput key={field.key} {...common} />
          ) : (
            <Input key={field.key} {...common} spellCheck={false} />
          )
        })}
      </form>
    </Dialog>
  )
}

/* ---- Sign in through a provider ---------------------------------------------------------------- */

function SignInDialog({
  open,
  integration,
  oauth,
  onClose,
}: {
  open: boolean
  integration: Integration
  oauth: OAuthSetup
  onClose: () => void
}) {
  const toast = useToast()
  const name = serverLabel(integration.server, integration.displayName)
  const provider = oauth.providerLabel
  const app = useOAuthApp(integration.server, { enabled: open })
  const saveApp = useSaveOAuthApp()
  const start = useStartOAuth()
  const configured = integration.oauthAppConfigured === true
  const [step, setStep] = useState<'app' | 'connect'>(configured ? 'connect' : 'app')
  const [draft, setDraft] = useState<{ clientId?: string; clientSecret?: string; settings: Record<string, string> }>({
    settings: {},
  })
  const formRef = useRef<HTMLFormElement>(null)
  const problems = useFieldProblems(formRef)
  const [redirecting, setRedirecting] = useState(false)
  const reconnecting = !integration.sandbox || integration.reconnectRequired || (integration.missingScopes ?? []).length > 0
  const docsUrl = safeDocsUrl(oauth.appDocsUrl)
  const redirectUri = app.data?.redirectUri || oauth.redirectUri
  const formId = `app-${integration.server}`
  const busy = saveApp.isPending || start.isPending || redirecting

  const clientId = draft.clientId ?? app.data?.clientId ?? ''
  const secretStored = app.data?.clientSecretStored === true || (configured && app.data === undefined)
  const clientSecret = draft.clientSecret ?? ''
  const settingValue = (key: string) => draft.settings[key] ?? app.data?.settings?.[key] ?? ''
  const canSave =
    clientId.trim() !== '' &&
    (clientSecret.trim() !== '' || secretStored) &&
    oauth.appFields.every((field) => settingValue(field.key).trim() !== '')

  const close = () => {
    if (busy) return
    onClose()
  }

  const handleSave = async (event: FormEvent) => {
    event.preventDefault()
    if (!canSave || busy) return
    problems.clear()
    try {
      const settings = Object.fromEntries(
        oauth.appFields.map((field) => [field.key, settingValue(field.key).trim()]).filter(([, value]) => value),
      )
      await saveApp.mutateAsync({
        server: integration.server,
        clientId: clientId.trim(),
        ...(clientSecret.trim() ? { clientSecret: clientSecret.trim() } : {}),
        settings,
      })
      toast.success(`The ${provider} app settings were saved.`)
      setDraft((current) => ({ ...current, clientSecret: '' }))
      setStep('connect')
    } catch (err) {
      const fields = [
        { key: 'clientId', label: 'Client ID' },
        { key: 'clientSecret', label: 'Client secret' },
        ...oauth.appFields.map((field) => ({ key: `setting:${field.key}`, label: field.label })),
      ]
      // The service reports every provider setting as `settings`; with one setting it is that one.
      const only = oauth.appFields.length === 1 ? oauth.appFields[0]! : null
      problems.failed(err, fields, only ? { settings: `setting:${only.key}` } : {}, {
        clientId: 'Client ID',
        clientSecret: 'Client secret',
        settings: 'App settings',
      })
    }
  }

  const handleConnect = async () => {
    if (busy) return
    problems.clear()
    try {
      const { authorizeUrl } = await start.mutateAsync(integration.server)
      if (!isHttpsUrl(authorizeUrl)) {
        problems.fail(
          `${provider} did not give a secure sign-in address, so nothing was opened. Check the app settings and try again.`,
        )
        return
        return
      }
      setRedirecting(true)
      goToProvider(authorizeUrl)
    } catch (err) {
      problems.fail(describeApiError(err))
    }
  }

  const scopes = (
    <div>
      <h3 className="field-label">Permissions this connector asks for</h3>
      <ul
        className="stack"
        style={{ gap: 'var(--space-2)', margin: 'var(--space-2) 0 0', paddingLeft: 'var(--space-6)', listStyle: 'disc', overflowWrap: 'anywhere' }}
      >
        {oauth.scopes.map((scope) => (
          <li key={scope}>{scope}</li>
        ))}
      </ul>
    </div>
  )

  const onApp = step === 'app'

  return (
    <Dialog
      open={open}
      onClose={close}
      eyebrow={reconnecting ? 'Reconnect a live account' : 'Connect a live account'}
      title={reconnecting ? `Reconnect ${name}` : `Connect ${name}`}
      description={`${name} signs in through ${provider}. Nothing is stored in this workspace except the app settings and the permission ${provider} grants.`}
      dismissible={!busy}
      error={problems.error}
      footer={
        <>
          <Button variant="outline" onClick={close} disabled={busy}>
            Cancel
          </Button>
          {onApp ? (
            <Button type="submit" form={formId} loading={saveApp.isPending} disabled={!canSave}>
              Save app
            </Button>
          ) : (
            <Button onClick={() => void handleConnect()} loading={start.isPending || redirecting}>
              {reconnecting ? `Reconnect to ${provider}` : `Connect to ${provider}`}
            </Button>
          )}
        </>
      }
    >
      <div className="stack" style={STACK}>
        <h3 className="section-heading" data-autofocus tabIndex={-1}>
          {onApp ? 'Step 1 of 2: Set up the app' : 'Step 2 of 2: Connect'}
        </h3>

        {onApp ? (
          <form ref={formRef} id={formId} onSubmit={handleSave} className="stack" style={STACK} noValidate>
            <p className="muted">
              Register an app with {provider} once for this workspace. {provider} gives it a client ID and a client secret.
            </p>
            <OrderedSteps heading={`Set it up at ${provider}`} steps={oauth.appSteps.filter((item) => item.trim())} />
            <DocsLink url={docsUrl} name={provider} />
            <div>
              <Input label="Redirect address to register" value={redirectUri} readOnly onFocus={(event) => event.currentTarget.select()} hint={`Paste this exact address into the ${provider} app. It must match.`} trailing={<CopyButton text={redirectUri} label="Copy redirect address" />} />
            </div>
            {scopes}
            <Input
              label="Client ID"
              value={clientId}
              onChange={(event) => {
                problems.edited('clientId')
                setDraft((current) => ({ ...current, clientId: event.target.value }))
              }}
              autoComplete="off"
              spellCheck={false}
              required
              error={problems.inline.clientId ?? null}
            />
            <PasswordInput
              label="Client secret"
              value={clientSecret}
              onChange={(event) => {
                problems.edited('clientSecret')
                setDraft((current) => ({ ...current, clientSecret: event.target.value }))
              }}
              autoComplete="off"
              required={!secretStored}
              error={problems.inline.clientSecret ?? null}
              hint={secretStored ? 'A secret is already stored. Leave blank to keep it.' : 'It is stored encrypted and never shown again.'}
            />
            {oauth.appFields.map((field) => {
              const common = {
                label: field.label,
                value: settingValue(field.key),
                onChange: (event: { target: { value: string } }) => {
                  problems.edited(`setting:${field.key}`)
                  setDraft((current) => ({ ...current, settings: { ...current.settings, [field.key]: event.target.value } }))
                },
                autoComplete: 'off',
                required: true,
                placeholder: field.placeholder ?? undefined,
                hint: field.help?.trim() || undefined,
                error: problems.inline[`setting:${field.key}`] ?? null,
              }
              return field.secret ? (
                <PasswordInput key={field.key} {...common} />
              ) : (
                <Input key={field.key} {...common} spellCheck={false} />
              )
            })}
          </form>
        ) : (
          <>
            <p>
              You will go to {provider} to approve access, then come back here. {name} stays on practice data until you do.
            </p>
            {(integration.missingScopes ?? []).length > 0 && (
              <Notice tone="warning">Some permissions are missing. Reconnecting asks {provider} for them again.</Notice>
            )}
            {scopes}
            <div>
              <Button variant="quiet" className="button-sm" onClick={() => { problems.clear(); setStep('app') }} disabled={busy}>
                Change the app settings
              </Button>
            </div>
          </>
        )}
      </div>
    </Dialog>
  )
}
