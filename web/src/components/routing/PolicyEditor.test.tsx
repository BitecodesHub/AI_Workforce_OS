// @find: tests for PolicyEditor, routing policy, model policy, candidate chain, fallback models, model order, provider keys, enable provider, save policy, clear policy, Routing page, agent routing
// @what: Automated tests for PolicyEditor.
// @flow: Run with the web test runner; covers PolicyEditor.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CredentialView, Model, ModelPolicy, Provider } from '../../lib/queries'
import { ToastProvider } from '../../lib/toast'
import { PolicyEditor, type PolicyEditorProps } from './PolicyEditor'

/*
 * The candidate-chain editor, as it is used in both of its places: the workspace's policy on Model
 * routing, and one agent's own chain on its page. The same chain is arranged and saved the same
 * way; what differs is whose chain it is, the words around it, and that an agent's can be handed
 * back to the workspace's.
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

const provider = (id: string, displayName: string): Provider => ({
  id,
  displayName,
  kind: 'OPENAI_COMPATIBLE',
  enabled: true,
  platformEnabled: true,
  credentialRef: `provider:${id}`,
  credentialStatus: 'unknown',
  circuitState: 'CLOSED',
  regions: [],
  modelCount: 2,
})

const model = (providerId: string, modelId: string, displayName: string): Model => ({
  providerId,
  modelId,
  displayName,
  contextWindow: 128_000,
  maxOutputTokens: 4096,
  supportsTools: true,
  supportsJsonMode: true,
  supportsStreaming: true,
  inputCostPerMillion: 1,
  outputCostPerMillion: 2,
  enabled: true,
})

const PROVIDERS = [provider('groq', 'Groq'), provider('openrouter', 'OpenRouter')]
const MODELS = [
  model('groq', 'llama-fast', 'Llama Fast'),
  model('groq', 'llama-big', 'Llama Big'),
  model('openrouter', 'mixtral', 'Mixtral'),
]
const CREDENTIALS: CredentialView[] = [
  { ref: 'provider:groq', kind: 'api_key', present: true },
  { ref: 'provider:openrouter', kind: 'api_key', present: true },
]

const UNSET: ModelPolicy = {
  configured: false,
  exhaustedBehaviour: 'FAIL_CLOSED',
  maxAttemptsPerCandidate: 2,
  overallDeadlineSeconds: 300,
  compactOnOverflow: true,
  candidates: [],
}

const OWN: ModelPolicy = {
  ...UNSET,
  configured: true,
  candidates: [
    { position: 0, providerId: 'groq', modelId: 'llama-fast' },
    { position: 1, providerId: 'openrouter', modelId: 'mixtral' },
  ],
}

const onSave = vi.fn(async () => undefined)
const onClear = vi.fn(async () => undefined)

beforeEach(() => {
  onSave.mockClear()
  onClear.mockClear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

function editor(props: Partial<PolicyEditorProps> = {}) {
  return render(
    <ToastProvider>
      <PolicyEditor
        scope="workspace"
        policy={OWN}
        providers={PROVIDERS}
        models={MODELS}
        credentials={CREDENTIALS}
        canManage
        now={Date.now()}
        onSave={onSave}
        {...props}
      />
    </ToastProvider>,
  )
}

describe('in the workspace policy', () => {
  it('is the routing policy card, with the chain in order', () => {
    editor()

    expect(screen.getByRole('heading', { name: 'Routing policy' })).toBeInTheDocument()
    expect(screen.getByLabelText('Candidate 1 provider')).toHaveValue('groq')
    expect(screen.getByLabelText('Candidate 1 model')).toHaveValue('Llama Fast')
    expect(screen.getByLabelText('Candidate 2 provider')).toHaveValue('openrouter')
    expect(screen.getByRole('button', { name: 'Save routing policy' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: /workspace default/ })).not.toBeInTheDocument()
  })

  it('offers removal from all routing for each model in the chain', () => {
    const onRemoveEverywhere = vi.fn()
    editor({ onRemoveEverywhere })

    fireEvent.click(screen.getByRole('button', { name: 'Remove Mixtral from all routing' }))
    expect(onRemoveEverywhere).toHaveBeenCalledWith({ providerId: 'openrouter', modelId: 'mixtral', name: 'Mixtral' })
  })

  it('never shows a provider as paused after failures, and treats a refused key as information', () => {
    editor({
      providers: [{ ...PROVIDERS[0]!, circuitState: 'OPEN', credentialStatus: 'rejected' }, PROVIDERS[1]!],
    })

    expect(screen.queryByText(/Paused/)).not.toBeInTheDocument()
    expect(screen.getAllByText('Ready')).toHaveLength(2)
    expect(screen.getByText('Key refused last time, still tried')).toBeInTheDocument()
  })

  it('saves the chain as arranged', async () => {
    editor()

    fireEvent.click(screen.getByRole('button', { name: 'Move candidate 2 up' }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save routing policy' }))
    })

    expect(onSave).toHaveBeenCalledWith({
      candidates: [
        { providerId: 'openrouter', modelId: 'mixtral', temperature: null, maxOutputTokens: null },
        { providerId: 'groq', modelId: 'llama-fast', temperature: null, maxOutputTokens: null },
      ],
    })
    expect(await screen.findByText('Routing policy saved.')).toBeInTheDocument()
  })

  it('confirms before saving an empty policy', async () => {
    editor()

    fireEvent.click(screen.getByRole('button', { name: 'Remove candidate 2' }))
    fireEvent.click(screen.getByRole('button', { name: 'Remove candidate 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save routing policy' }))

    const dialog = await screen.findByRole('dialog', { name: 'Save an empty routing policy?' })
    expect(onSave).not.toHaveBeenCalled()
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Save empty policy' }))
    })
    expect(onSave).toHaveBeenCalledWith({ candidates: [] })
  })

  it('shows the chain read-only, in the same order, to somebody who cannot change it', () => {
    editor({ canManage: false })

    expect(screen.queryByLabelText('Candidate 1 provider')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save routing policy' })).not.toBeInTheDocument()
    const items = screen.getAllByRole('listitem').map((item) => item.textContent)
    expect(items[0]).toContain('Groq')
    expect(items[1]).toContain('OpenRouter')
    // And says why it cannot be changed here.
    expect(screen.getByText(/Changing the routing policy needs a role that can manage model providers/)).toBeInTheDocument()
  })
})

describe('on an agent', () => {
  it("is that agent's model routing, and offers the workspace default", () => {
    editor({ scope: 'agent', subject: 'Legal', onClear })

    expect(screen.getByRole('heading', { name: 'Model routing' })).toBeInTheDocument()
    expect(screen.getByText(/Which language models answer for Legal/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save these models' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Use the workspace default for Legal' })).toBeEnabled()
  })

  it('offers the workspace default at any time, also to an agent with no chain of its own', () => {
    editor({ scope: 'agent', subject: 'Legal', policy: UNSET, onClear })

    expect(screen.getByText('Legal has no models of its own, so it uses the workspace default.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Use the workspace default for Legal' })).toBeEnabled()
  })

  it('gives an agent its first chain', async () => {
    editor({ scope: 'agent', subject: 'Legal', policy: UNSET, onClear })

    fireEvent.click(screen.getByRole('button', { name: 'Add a candidate' }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save these models' }))
    })

    expect(onSave).toHaveBeenCalledTimes(1)
    const [input] = onSave.mock.calls[0] as unknown as [{ candidates: Array<{ providerId: string }> }]
    expect(input.candidates).toHaveLength(1)
    expect(await screen.findByText("Legal's models were saved.")).toBeInTheDocument()
  })

  it('hands the agent back to the workspace default after a confirm step, and shows the warning', async () => {
    onClear.mockResolvedValueOnce({
      ...UNSET,
      warning: 'No AI model is set for this agent or the workspace — add one in Model routing',
    } as never)
    editor({ scope: 'agent', subject: 'Legal', onClear })

    fireEvent.click(screen.getByRole('button', { name: 'Use the workspace default for Legal' }))
    const dialog = await screen.findByRole('dialog', { name: 'Use the workspace default for Legal?' })
    expect(onClear).not.toHaveBeenCalled()
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Use workspace default' }))
    })

    expect(onClear).toHaveBeenCalledTimes(1)
    expect(onSave).not.toHaveBeenCalled()
    expect(await screen.findByText('Legal now uses the workspace default.')).toBeInTheDocument()
    expect(
      screen.getByText('No AI model is set for this agent or the workspace — add one in Model routing'),
    ).toBeInTheDocument()
  })

  it('treats saving an empty chain as handing the agent back, not as saving no models', async () => {
    editor({ scope: 'agent', subject: 'Legal', onClear })

    fireEvent.click(screen.getByRole('button', { name: "Remove Mixtral from Legal's routing" }))
    fireEvent.click(screen.getByRole('button', { name: "Remove Llama Fast from Legal's routing" }))
    fireEvent.click(screen.getByRole('button', { name: 'Save these models' }))

    const dialog = await screen.findByRole('dialog', { name: 'Use the workspace default for Legal?' })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Use workspace default' }))
    })
    expect(onClear).toHaveBeenCalledTimes(1)
    expect(onSave).not.toHaveBeenCalled()
  })

  it('removes a saved model at once, without a save, and shows the service warning', async () => {
    const onRemoveCandidate = vi.fn(async () => ({
      ...OWN,
      candidates: [OWN.candidates[0]],
      warning: 'Every provider this agent lists is turned off, so its runs will fail until one is turned on.',
    }))
    editor({ scope: 'agent', subject: 'Legal', onClear, onRemoveCandidate })

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: "Remove Mixtral from Legal's routing" }))
    })

    expect(onRemoveCandidate).toHaveBeenCalledWith({ providerId: 'openrouter', modelId: 'mixtral' })
    expect(onSave).not.toHaveBeenCalled()
    expect(await screen.findByText("Mixtral was removed from Legal's routing.")).toBeInTheDocument()
    expect(
      screen.getByText('Every provider this agent lists is turned off, so its runs will fail until one is turned on.'),
    ).toBeInTheDocument()
  })

  it('says the agent is back on the workspace default when the last model is removed', async () => {
    const onRemoveCandidate = vi.fn(async () => ({ ...UNSET, warning: 'This agent will use the workspace default: Groq · Llama Fast.' }))
    editor({ scope: 'agent', subject: 'Legal', onClear, onRemoveCandidate })

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: "Remove Llama Fast from Legal's routing" }))
    })
    expect(await screen.findByText('Llama Fast was removed. Legal now uses the workspace default.')).toBeInTheDocument()
    expect(screen.getByText('This agent will use the workspace default: Groq · Llama Fast.')).toBeInTheDocument()
  })

  it('only takes the row out of the draft while other changes wait to be saved', () => {
    const onRemoveCandidate = vi.fn(async () => OWN)
    editor({ scope: 'agent', subject: 'Legal', onClear, onRemoveCandidate })

    fireEvent.click(screen.getByRole('button', { name: 'Move candidate 2 up' }))
    fireEvent.click(screen.getByRole('button', { name: "Remove Llama Fast from Legal's routing" }))

    expect(onRemoveCandidate).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('Candidate 2 provider')).not.toBeInTheDocument()
  })

  it('shows the warning the service sends with the chain when it is read', () => {
    editor({ scope: 'agent', subject: 'Legal', onClear, policy: { ...OWN, warning: 'Every provider this agent lists is turned off.' } })

    expect(screen.getByText('Every provider this agent lists is turned off.')).toBeInTheDocument()
  })

  it('saves a chain whose providers are all off, and shows the warning instead of refusing', async () => {
    onSave.mockResolvedValueOnce({ ...OWN, warning: 'Every provider this agent lists is turned off.' } as never)
    editor({
      scope: 'agent',
      subject: 'Legal',
      onClear,
      providers: PROVIDERS.map((entry) => ({ ...entry, enabled: false })),
    })

    fireEvent.click(screen.getByRole('button', { name: 'Move candidate 2 up' }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save these models' }))
    })
    expect(onSave).toHaveBeenCalledTimes(1)
    expect(await screen.findByText('Every provider this agent lists is turned off.')).toBeInTheDocument()
  })

  it('keeps what was arranged when a save fails', async () => {
    onSave.mockRejectedValueOnce(new Error('boom'))
    editor({ scope: 'agent', subject: 'Legal', policy: UNSET, onClear })

    fireEvent.click(screen.getByRole('button', { name: 'Add a candidate' }))
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save these models' }))
    })

    // A failed save is a toast, and the draft is kept so nothing the person arranged is lost.
    await waitFor(() => expect(screen.getByLabelText('Candidate 1 provider')).toBeInTheDocument())
    expect(screen.queryByText("Legal's models were saved.")).not.toBeInTheDocument()
  })
})
