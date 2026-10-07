import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../lib/router'
import { clearSession, saveSession } from '../lib/session'
import { ToastProvider } from '../lib/toast'
import { AgentDetail } from './AgentDetail'

/*
 * An agent's own page: Pause or Resume in the header, how it has done over 30 days, every revision
 * of its configuration with a way to put an old one back, and its own model routing, which the
 * same editor as Model routing changes for people who may.
 */

// jsdom has the <dialog> element but not its modal methods; every dialog here opens with one.
if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

type Call = { method: string; url: string; body: unknown }

const LEGAL = {
  id: 'a-legal',
  key: 'legal',
  name: 'Legal',
  category: 'legal',
  status: 'active',
  revision: 3,
  summary: 'You review contracts.',
  tools: [],
  systemPrompt: 'You review contracts. Revision three.',
  goals: 'Keep every contract reviewed.',
  maxSteps: 12,
  sealed: false,
  grants: [],
}

const REVISIONS = [
  {
    id: 'v3',
    revision: 3,
    createdAt: '2026-10-04T10:00:00Z',
    createdBy: 'user-2',
    changedFields: ['systemPrompt'],
    initial: false,
    current: true,
    usedByRuns: false,
    systemPrompt: 'You review contracts. Revision three.',
    goals: 'Keep every contract reviewed.',
    maxSteps: 12,
  },
  {
    id: 'v2',
    revision: 2,
    createdAt: '2026-10-02T10:00:00Z',
    createdBy: 'user-2',
    changedFields: ['systemPrompt', 'maxSteps'],
    initial: false,
    current: false,
    usedByRuns: true,
    systemPrompt: 'You review contracts. Revision two.',
    goals: 'Review contracts.',
    temperature: 0.3,
    maxOutputTokens: 2000,
    maxSteps: 8,
  },
  {
    id: 'v1',
    revision: 1,
    createdAt: '2026-10-01T10:00:00Z',
    createdBy: 'system',
    changedFields: [],
    initial: true,
    current: false,
    usedByRuns: true,
    systemPrompt: 'You review contracts.',
    maxSteps: 12,
  },
]

const PROVIDERS = [
  {
    id: 'groq',
    displayName: 'Groq',
    kind: 'OPENAI_COMPATIBLE',
    enabled: true,
    platformEnabled: true,
    credentialRef: 'provider:groq',
    credentialStatus: 'unknown',
    circuitState: 'CLOSED',
    regions: [],
    modelCount: 1,
  },
]
const MODELS = [
  {
    providerId: 'groq',
    modelId: 'llama-fast',
    displayName: 'Llama Fast',
    contextWindow: 128000,
    maxOutputTokens: 4096,
    supportsTools: true,
    supportsJsonMode: true,
    supportsStreaming: true,
    inputCostPerMillion: 1,
    outputCostPerMillion: 1,
    enabled: true,
  },
]

const OWN_POLICY = {
  configured: true,
  exhaustedBehaviour: 'FAIL_CLOSED',
  maxAttemptsPerCandidate: 2,
  overallDeadlineSeconds: 300,
  compactOnOverflow: true,
  candidates: [{ position: 0, providerId: 'groq', modelId: 'llama-fast' }],
}
const NO_POLICY = { ...OWN_POLICY, configured: false, candidates: [] }

let calls: Call[]
let agent: Record<string, unknown>
let permissions: string[]
let outcomes: Array<Record<string, unknown>> | null
let policy: Record<string, unknown>

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  calls = []
  agent = LEGAL
  permissions = ['agent:read', 'run:read']
  outcomes = [{ agentId: 'a-legal', runs: 14, finishedRuns: 12, completed: 9, failed: 3, enoughRuns: true, successRate: 0.75, totalCost: 2.5 }]
  policy = NO_POLICY
  vi.stubGlobal('scrollTo', vi.fn())
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      const body = typeof init.body === 'string' ? JSON.parse(init.body) : null
      calls.push({ method, url, body })
      const path = url.split('?')[0]
      if (method === 'GET') {
        if (path === '/api/agents/a-legal') return json(200, agent)
        if (path === '/api/agents/a-legal/revisions') return json(200, REVISIONS)
        if (path === '/api/agents/a-legal/model-policy') return json(200, policy)
        if (path === '/api/orchestrator/insights/agents') {
          return outcomes ? json(200, { window: '30d', agents: outcomes }) : json(500, { code: 'internal', detail: 'Down.' })
        }
        if (path === '/api/providers') return json(200, PROVIDERS)
        if (path === '/api/providers/models') return json(200, MODELS)
        if (path === '/api/credentials') return json(200, [{ ref: 'provider:groq', kind: 'api_key', present: true }])
        if (path === '/api/users') return json(200, [{ userId: 'user-2', displayName: 'Maya Manager', email: 'maya@example.test', role: 'admin', status: 'active' }])
        if (path === '/api/runs') return json(200, [])
        return json(404, { code: 'not_found', detail: 'Not here.' })
      }
      if (method === 'POST' && path === '/api/agents/a-legal/pause') return json(200, { ...agent, status: 'paused' })
      if (method === 'POST' && path === '/api/agents/a-legal/resume') return json(200, { ...agent, status: 'active' })
      if (method === 'PUT' && path === '/api/agents/a-legal/configuration') return json(200, { ...agent, revision: 4 })
      if (method === 'PUT' && path === '/api/agents/a-legal/model-policy') {
        policy = { ...OWN_POLICY, candidates: (body as { candidates: unknown[] }).candidates.map((c, position) => ({ position, ...(c as object) })) }
        return json(200, policy)
      }
      if (method === 'DELETE' && path === '/api/agents/a-legal/model-policy') {
        policy = NO_POLICY
        return json(200, policy)
      }
      return json(404, { code: 'not_found', detail: 'Not here.' })
    }),
  )
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

async function open() {
  saveSession('token', {
    userId: 'user-1',
    workspaceId: 'ws-1',
    permissions,
    displayName: 'Olivia Owner',
    email: 'olivia@example.test',
    role: 'owner',
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <AgentDetail id="a-legal" />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
  await screen.findByRole('heading', { name: String(agent.name), level: 1 })
}

const writes = () => calls.filter((call) => call.method !== 'GET')

describe('pause and resume', () => {
  it('pauses the agent from its header', async () => {
    permissions = ['agent:read', 'agent:update']
    await open()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Pause Legal' }))
    })

    expect(writes()).toEqual([{ method: 'POST', url: '/api/agents/a-legal/pause', body: null }])
    expect(await screen.findByText('Legal paused')).toBeInTheDocument()
  })

  it('offers Resume on a paused agent', async () => {
    permissions = ['agent:read', 'agent:update']
    agent = { ...LEGAL, status: 'paused' }
    await open()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Resume Legal' }))
    })

    expect(writes().map((call) => call.url)).toEqual(['/api/agents/a-legal/resume'])
  })

  it('asks first for the General Employee, and says unmatched requests will ask people to choose', async () => {
    permissions = ['agent:read', 'agent:update']
    agent = { ...LEGAL, name: 'General Employee', fallback: true }
    await open()
    fireEvent.click(screen.getByRole('button', { name: 'Pause General Employee' }))

    const dialog = await screen.findByRole('dialog', { name: 'Pause General Employee?' })
    expect(within(dialog).getByText(/will ask people to choose an agent/)).toBeInTheDocument()
    expect(writes()).toEqual([])
  })

  it('offers neither for a retired agent, nor to somebody who cannot change agents', async () => {
    permissions = ['agent:read', 'agent:update']
    agent = { ...LEGAL, status: 'retired' }
    await open()
    expect(screen.queryByRole('button', { name: /^(Pause|Resume) Legal$/ })).not.toBeInTheDocument()
  })

  it('shows no control to a role that cannot change agents', async () => {
    await open()
    expect(screen.queryByRole('button', { name: /^(Pause|Resume) Legal$/ })).not.toBeInTheDocument()
  })
})

describe('outcome tiles', () => {
  it('show the success rate, cost and runs over 30 days beside the connectors', async () => {
    await open()

    expect(await screen.findByText('75%')).toBeInTheDocument()
    expect(screen.getByText('Success rate (30 days)')).toBeInTheDocument()
    expect(screen.getByText('Cost (30 days)')).toBeInTheDocument()
    expect(screen.getByText('US$2.50')).toBeInTheDocument()
    expect(screen.getByText('Runs (30 days)')).toBeInTheDocument()
    expect(screen.getByText('14')).toBeInTheDocument()
    // The connectors count stays among the tiles (the Connectors card has a heading of the same name).
    const tiles = document.querySelector('.stat-row') as HTMLElement
    expect(within(tiles).getByText('Connectors')).toBeInTheDocument()
    expect(calls.some((call) => call.url === '/api/orchestrator/insights/agents?window=30d')).toBe(true)
  })

  it('say there are not enough runs yet below five finished runs', async () => {
    outcomes = [{ agentId: 'a-legal', runs: 3, finishedRuns: 3, completed: 3, failed: 0, enoughRuns: false, totalCost: 0.2 }]
    await open()

    expect(await screen.findByText('Not enough runs yet')).toBeInTheDocument()
    expect(screen.queryByText('100%')).not.toBeInTheDocument()
  })

  it('say a cost is unpriced rather than US$0', async () => {
    outcomes = [{ agentId: 'a-legal', runs: 6, finishedRuns: 6, completed: 6, failed: 0, enoughRuns: true, successRate: 1, unpricedRuns: 6 }]
    await open()

    expect(await screen.findByText('Unpriced')).toBeInTheDocument()
  })

  it('are not asked for, and not shown, without run:read', async () => {
    permissions = ['agent:read']
    await open()

    expect(screen.queryByText('Success rate (30 days)')).not.toBeInTheDocument()
    expect(calls.some((call) => call.url.startsWith('/api/orchestrator/insights'))).toBe(false)
    expect(screen.getByText('Step limit')).toBeInTheDocument()
  })

  it('say they could not be loaded instead of showing zeros', async () => {
    outcomes = null
    await open()

    expect(await screen.findAllByText('Its outcomes could not be loaded.')).not.toHaveLength(0)
    expect(screen.queryByText('0%')).not.toBeInTheDocument()
  })
})

describe('revisions', () => {
  it('lists every revision, what changed, who saved it, and which are sealed', async () => {
    permissions = ['agent:read', 'member:read']
    await open()

    const card = (await screen.findByRole('heading', { name: 'Revisions' })).closest('section')!
    expect(await within(card).findByRole('heading', { name: 'Revision 3' })).toBeInTheDocument()
    expect(within(card).getByRole('heading', { name: 'Revision 2' })).toBeInTheDocument()
    expect(within(card).getByRole('heading', { name: 'Revision 1' })).toBeInTheDocument()
    expect(within(card).getByText('Current')).toBeInTheDocument()
    expect(within(card).getAllByText('Sealed, used by runs')).toHaveLength(2)
    expect(within(card).getByText(/Instructions and step limit changed/)).toBeInTheDocument()
    expect(within(card).getByText('First revision. Saved', { exact: false })).toBeInTheDocument()
    expect(within(card).getAllByText(/by Maya Manager/).length).toBeGreaterThan(0)
    expect(within(card).getByText(/by the platform/)).toBeInTheDocument()
  })

  it('offers Restore on a past revision only, and only to somebody who can change the agent', async () => {
    await open()
    const card = (await screen.findByRole('heading', { name: 'Revisions' })).closest('section')!
    await within(card).findByRole('heading', { name: 'Revision 2' })
    expect(within(card).queryByRole('button', { name: /^Restore revision/ })).not.toBeInTheDocument()
  })

  it('restores revision 2 after a confirm step, as a new revision with its instructions, goals and limits', async () => {
    permissions = ['agent:read', 'agent:update']
    await open()
    const card = (await screen.findByRole('heading', { name: 'Revisions' })).closest('section')!
    await within(card).findByRole('heading', { name: 'Revision 2' })

    // The current revision has nothing to restore.
    expect(within(card).getAllByRole('button', { name: /^Restore revision/ }).map((button) => button.getAttribute('aria-label'))).toEqual([
      'Restore revision 2',
      'Restore revision 1',
    ])

    fireEvent.click(within(card).getByRole('button', { name: 'Restore revision 2' }))
    const dialog = await screen.findByRole('dialog', { name: 'Restore revision 2?' })
    expect(within(dialog).getByText(/saved again as revision 4, on top of revision 3/)).toBeInTheDocument()
    expect(writes()).toEqual([])

    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Restore revision 2' }))
    })

    expect(writes()).toEqual([
      {
        method: 'PUT',
        url: '/api/agents/a-legal/configuration',
        body: {
          systemPrompt: 'You review contracts. Revision two.',
          goals: 'Review contracts.',
          temperature: 0.3,
          maxOutputTokens: 2000,
          maxSteps: 8,
        },
      },
    ])
    expect(await screen.findByText('Revision 2 was restored as revision 4')).toBeInTheDocument()
    // The list is read again, so the new revision appears.
    await waitFor(() =>
      expect(calls.filter((call) => call.method === 'GET' && call.url === '/api/agents/a-legal/revisions').length).toBeGreaterThan(1),
    )
  })

  it('lets anybody who can read the agent read an old revision', async () => {
    await open()
    const card = (await screen.findByRole('heading', { name: 'Revisions' })).closest('section')!
    await within(card).findByRole('heading', { name: 'Revision 2' })

    expect(within(card).getByText('Read revision 2')).toBeInTheDocument()
    expect(within(card).getByText('You review contracts. Revision two.')).toBeInTheDocument()
    expect(within(card).getByText('Step limit 8, temperature 0.3, output limit 2,000 tokens.')).toBeInTheDocument()
  })
})

describe('model routing', () => {
  it('is the shared editor for somebody who may set the agent\'s models and see the providers', async () => {
    permissions = ['agent:read', 'agent:set_model_policy', 'provider:read']
    policy = OWN_POLICY
    await open()

    expect(await screen.findByLabelText('Candidate 1 provider')).toHaveValue('groq')
    expect(screen.getByRole('button', { name: 'Save these models' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Use the workspace policy' })).toBeInTheDocument()
  })

  it('saves the agent\'s own chain with PUT', async () => {
    permissions = ['agent:read', 'agent:set_model_policy', 'provider:read']
    await open()

    fireEvent.click(await screen.findByRole('button', { name: 'Add a candidate' }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save these models' }))
    })

    expect(writes()).toEqual([
      {
        method: 'PUT',
        url: '/api/agents/a-legal/model-policy',
        body: { candidates: [{ providerId: 'groq', modelId: 'llama-fast', temperature: null, maxOutputTokens: null }] },
      },
    ])
    expect(await screen.findByText("Legal's models were saved.")).toBeInTheDocument()
  })

  it('hands the agent back to the workspace policy with DELETE', async () => {
    permissions = ['agent:read', 'agent:set_model_policy', 'provider:read']
    policy = OWN_POLICY
    await open()

    fireEvent.click(await screen.findByRole('button', { name: 'Use the workspace policy' }))
    const dialog = await screen.findByRole('dialog', { name: 'Use the workspace policy for Legal?' })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Use the workspace policy' }))
    })

    expect(writes()).toEqual([{ method: 'DELETE', url: '/api/agents/a-legal/model-policy', body: null }])
    expect(await screen.findByText('Legal now follows the workspace routing policy.')).toBeInTheDocument()
    // With nothing of its own the editor offers no way back to the workspace policy.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Use the workspace policy' })).not.toBeInTheDocument())
  })

  it('stays read-only without agent:set_model_policy', async () => {
    permissions = ['agent:read', 'provider:read']
    policy = OWN_POLICY
    await open()

    expect(await screen.findByText('This agent has its own routing. It tries these models in order:')).toBeInTheDocument()
    expect(screen.queryByLabelText('Candidate 1 provider')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save these models' })).not.toBeInTheDocument()
  })

  it('stays read-only, and says why, without provider:read, because the model pickers need it', async () => {
    permissions = ['agent:read', 'agent:set_model_policy']
    policy = OWN_POLICY
    await open()

    expect(await screen.findByText(/Choosing models also needs a role that can see the model providers/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Candidate 1 provider')).not.toBeInTheDocument()
    expect(calls.some((call) => call.url === '/api/providers')).toBe(false)
  })
})

describe('connectors on the agent', () => {
  it('moves focus to Add connector after a connector is removed, since its Remove button is gone', async () => {
    const linear = {
      server: 'linear',
      displayName: 'Linear',
      status: 'sandbox',
      sandbox: true,
      reconnectRequired: false,
      grantedScopes: [],
      missingScopes: [],
      tools: [{ name: 'list_issues', sideEffect: 'READ', alwaysRequiresApproval: false }],
      authType: 'token',
      liveAvailable: true,
    }
    agent = {
      ...LEGAL,
      tools: ['linear', 'drive'],
      grants: [
        { server: 'linear', tools: ['list_issues'], requireApproval: false, maxCallsPerRun: null },
        { server: 'drive', tools: [], requireApproval: false, maxCallsPerRun: null },
      ],
    }
    permissions = ['agent:read', 'agent:grant_tools', 'integration:read']
    const base = globalThis.fetch as unknown as (url: string, init?: RequestInit) => Promise<Response>
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init: RequestInit = {}) => {
        const method = init.method ?? 'GET'
        const path = url.split('?')[0]
        if (method === 'GET' && path === '/api/integrations') return json(200, [linear, { ...linear, server: 'drive', displayName: 'Google Drive' }])
        if (method === 'DELETE' && path === '/api/agents/a-legal/grants/linear') {
          calls.push({ method, url, body: null })
          agent = { ...agent, tools: ['drive'], grants: (agent.grants as Array<{ server: string }>).filter((g) => g.server !== 'linear') }
          return json(200, agent)
        }
        return base(url, init)
      }),
    )
    await open()
    fireEvent.click(await screen.findByRole('button', { name: 'Remove Linear' }))
    const dialog = screen.getByRole('dialog', { name: /Remove Linear from Legal/ })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Remove connector' }))
    })
    expect(writes()).toEqual([expect.objectContaining({ method: 'DELETE', url: '/api/agents/a-legal/grants/linear' })])
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Remove Linear' })).not.toBeInTheDocument())
    await waitFor(() => expect(screen.getByRole('button', { name: 'Add connector' })).toHaveFocus())
  })
})
