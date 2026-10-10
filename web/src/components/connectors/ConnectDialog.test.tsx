// @find: tests for ConnectDialog, connect connector, add connector, connect account, token, api key, sign in, oauth, credentials, Connect dialog, Integrations page, live account
// @what: Automated tests for ConnectDialog.
// @flow: Run with the web test runner; covers ConnectDialog.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../../lib/toast'
import type { Integration } from '../../lib/queries'
import { ConnectDialog } from './ConnectDialog'

const goToProvider = vi.fn()
vi.mock('../../lib/redirect', () => ({ goToProvider: (url: string) => goToProvider(url) }))

// jsdom has the <dialog> element but not its modal methods.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

type Call = { path: string; method: string; body: unknown }
let calls: Call[]
let startUrl = 'https://accounts.example.test/authorize?state=abc'
/** What PUT /connection and PUT /oauth/app answer; null for the default success. */
let connectionReply: Response | null = null
let appReply: Response | null = null
let appView = { configured: false, provider: 'google', clientId: null, clientSecretStored: false, settings: {}, redirectUri: 'https://app.example.test/callback' }

beforeEach(() => {
  calls = []
  connectionReply = null
  appReply = null
  goToProvider.mockClear()
  startUrl = 'https://accounts.example.test/authorize?state=abc'
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const path = url.split('?')[0]!
      const method = init?.method ?? 'GET'
      calls.push({ path, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      if (path.endsWith('/oauth/app') && method === 'PUT' && appReply) return appReply
      if (path.endsWith('/connection') && connectionReply) return connectionReply
      if (path.endsWith('/oauth/app') && method === 'PUT') return json(200, { ...appView, configured: true, clientSecretStored: true })
      if (path.endsWith('/oauth/app')) return json(200, appView)
      if (path.endsWith('/oauth/start')) return json(200, { authorizeUrl: startUrl })
      if (path.endsWith('/connection')) return json(200, { server: 'x', accountLabel: 'Test account' })
      return json(200, [])
    }),
  )
})

afterEach(() => vi.unstubAllGlobals())

const base: Integration = {
  server: 'gmail',
  displayName: 'Gmail',
  status: 'sandbox',
  sandbox: true,
  reconnectRequired: false,
  grantedScopes: [],
  missingScopes: [],
  tools: [],
  authType: 'token',
  liveAvailable: true,
  tokenLabel: 'Access token',
}

const oauth = {
  provider: 'google',
  providerLabel: 'Google',
  appFields: [{ key: 'tenant', label: 'Tenant', secret: false }],
  scopes: ['Read your email', 'Send email on your behalf'],
  appSteps: ['Open the Google Cloud console.', 'Create an OAuth app.'],
  appDocsUrl: 'https://docs.example.test/oauth',
  redirectUri: 'https://app.example.test/callback',
}

function show(integration: Integration) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <ConnectDialog open integration={integration} onClose={() => {}} />
      </ToastProvider>
    </QueryClientProvider>,
  )
}

const type = (label: string | RegExp, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } })

describe('ConnectDialog: token', () => {
  it('asks for every credential field and sends them as fields', async () => {
    show({
      ...base,
      server: 'zendesk',
      displayName: 'Zendesk',
      credentialFields: [
        { key: 'subdomain', label: 'Subdomain', secret: false, help: 'The first part of your Zendesk address.' },
        { key: 'email', label: 'Agent email', secret: false },
        { key: 'apiToken', label: 'API token', secret: true },
      ],
    })
    const submit = screen.getByRole('button', { name: 'Connect' })
    expect(submit).toBeDisabled()
    expect(screen.getByText('The first part of your Zendesk address.')).toBeInTheDocument()
    type('Subdomain', 'acme')
    type('Agent email', 'a@example.test')
    expect(submit).toBeDisabled()
    type('API token', 'secret-value')
    expect(submit).toBeEnabled()
    await act(async () => {
      fireEvent.click(submit)
    })
    const put = calls.find((c) => c.method === 'PUT')!
    expect(put.path).toBe('/api/integrations/zendesk/connection')
    expect(put.body).toEqual({ fields: { subdomain: 'acme', email: 'a@example.test', apiToken: 'secret-value' } })
  })

  it('keeps the single token box and sends a token', async () => {
    show({ ...base, server: 'github', displayName: 'GitHub', tokenLabel: 'Personal access token' })
    type('Personal access token', ' ghp_x ')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    })
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ token: 'ghp_x' })
  })

  it('takes an address for a webhook', async () => {
    show({ ...base, server: 'webhook', displayName: 'Webhook', authType: 'url', tokenLabel: 'Webhook address' })
    type('Webhook address', 'https://hooks.example.test/a')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    })
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ token: 'https://hooks.example.test/a' })
  })
})

describe('ConnectDialog: sign in', () => {
  const gmail: Integration = { ...base, authType: 'oauth', oauth, oauthAppConfigured: false }

  it('starts on the app step, saves the app, then moves to connect', async () => {
    show(gmail)
    expect(screen.getByRole('heading', { name: 'Step 1 of 2: Set up the app' })).toBeInTheDocument()
    expect(screen.getByLabelText('Redirect address to register')).toHaveValue('https://app.example.test/callback')
    expect(screen.getByText('Read your email')).toBeInTheDocument()
    expect(screen.getByText('Create an OAuth app.')).toBeInTheDocument()
    const save = screen.getByRole('button', { name: 'Save app' })
    expect(save).toBeDisabled()
    type('Client ID', 'client-1')
    type('Client secret', 'shh')
    type('Tenant', 'contoso')
    expect(save).toBeEnabled()
    await act(async () => {
      fireEvent.click(save)
    })
    const put = calls.find((c) => c.method === 'PUT')!
    expect(put.path).toBe('/api/integrations/gmail/oauth/app')
    expect(put.body).toEqual({ clientId: 'client-1', clientSecret: 'shh', settings: { tenant: 'contoso' } })
    expect(await screen.findByRole('heading', { name: 'Step 2 of 2: Connect' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Connect to Google' })).toBeInTheDocument()
  })

  it('opens on connect when the app is configured, and sends the browser to an https address', async () => {
    appView = { ...appView, configured: true, clientSecretStored: true }
    show({ ...gmail, oauthAppConfigured: true })
    expect(screen.getByRole('heading', { name: 'Step 2 of 2: Connect' })).toBeInTheDocument()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Connect to Google' }))
    })
    await waitFor(() => expect(goToProvider).toHaveBeenCalledWith('https://accounts.example.test/authorize?state=abc'))
  })

  it('refuses an address that is not https', async () => {
    show({ ...gmail, oauthAppConfigured: true })
    startUrl = 'http://accounts.example.test/authorize'
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Connect to Google' }))
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('did not give a secure sign-in address')
    expect(goToProvider).not.toHaveBeenCalled()
  })

  it('says Reconnect when permissions are missing and lets the admin change the app', async () => {
    appView = { ...appView, configured: true, clientSecretStored: true }
    show({ ...gmail, sandbox: false, status: 'connected', oauthAppConfigured: true, missingScopes: ['send'] })
    expect(screen.getByRole('button', { name: 'Reconnect to Google' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Change the app settings' }))
    expect(screen.getByRole('heading', { name: 'Step 1 of 2: Set up the app' })).toBeInTheDocument()
    expect(await screen.findByText('A secret is already stored. Leave blank to keep it.')).toBeInTheDocument()
  })
})

const problem = (code: string, detail: string, errors: Record<string, string>) =>
  json(422, { status: 422, code, detail, errors, retryable: false })

describe('ConnectDialog: problems shown next to the field', () => {
  const jira: Integration = {
    ...base,
    server: 'jira',
    displayName: 'Jira',
    credentialFields: [
      { key: 'site', label: 'Atlassian site', secret: false },
      { key: 'email', label: 'Email', secret: false },
      { key: 'token', label: 'API token', secret: true },
    ],
  }

  it('refuses a malformed webhook address under the field, without sending it', async () => {
    show({ ...base, server: 'webhook', displayName: 'Webhook', authType: 'url', tokenLabel: 'Webhook address' })
    type('Webhook address', 'hooks example')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    })
    const field = screen.getByLabelText('Webhook address')
    expect(field).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText(/That is not a valid web address/)).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    await waitFor(() => expect(field).toHaveFocus())
    // Typing again takes the problem away.
    type('Webhook address', 'https://hooks.example.test/a')
    expect(field).not.toHaveAttribute('aria-invalid')
  })

  it('shows a failed token check under the one token field', async () => {
    connectionReply = problem('connector_check_failed', 'GitHub did not accept this token.', { server: 'github' })
    show({ ...base, server: 'github', displayName: 'GitHub', tokenLabel: 'Personal access token' })
    type('Personal access token', 'not-a-real-token')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    })
    const field = screen.getByLabelText('Personal access token')
    await waitFor(() => expect(field).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByText('GitHub did not accept this token.')).toBeInTheDocument()
    expect(document.querySelector('.dialog-error')).toBeNull()
  })

  it('puts a 422 naming one of several fields under that field', async () => {
    connectionReply = problem('validation_failed', 'Some of the values supplied are not valid.', {
      field: 'site',
      problem: 'must not be empty',
    })
    show(jira)
    type('Atlassian site', 'acme')
    type('Email', 'a@example.test')
    type('API token', 'x')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    })
    await waitFor(() => expect(screen.getByLabelText('Atlassian site')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByText('Atlassian site must not be empty.')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).not.toHaveAttribute('aria-invalid')
  })

  it('keeps a check that names no field for the whole dialog when there are several', async () => {
    connectionReply = problem('connector_check_failed', 'Jira answered the check with status 404.', { server: 'jira' })
    show(jira)
    type('Atlassian site', 'https://acme.atlassian.net')
    type('Email', 'a@example.test')
    type('API token', 'x')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('Jira answered the check with status 404.')
    expect(screen.getByLabelText('Atlassian site')).not.toHaveAttribute('aria-invalid')
  })

  it('refuses a client ID with a space in it under the field, without saving', async () => {
    show({ ...base, authType: 'oauth', oauth, oauthAppConfigured: false })
    type('Client ID', 'not a real id')
    type('Client secret', 'shh')
    type('Tenant', 'contoso')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save app' }))
    })
    expect(screen.getByLabelText('Client ID')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText(/A client ID has no spaces/)).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'PUT')).toBe(false)
  })

  it('puts a refused app setting under that setting, not under "Settings"', async () => {
    appReply = problem('validation_failed', 'Some of the values supplied are not valid.', {
      field: 'settings',
      problem: 'The tenant must be a directory (tenant) id, a domain such as acme.onmicrosoft.com, or common.',
    })
    show({ ...base, authType: 'oauth', oauth, oauthAppConfigured: false })
    type('Client ID', 'client-1')
    type('Client secret', 'shh')
    type('Tenant', 'a b')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save app' }))
    })
    await waitFor(() => expect(screen.getByLabelText('Tenant')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByText(/The tenant must be a directory/)).toBeInTheDocument()
    expect(document.querySelector('.dialog-error')).toBeNull()
    expect(screen.getByRole('heading', { name: 'Step 1 of 2: Set up the app' })).toBeInTheDocument()
  })
})
