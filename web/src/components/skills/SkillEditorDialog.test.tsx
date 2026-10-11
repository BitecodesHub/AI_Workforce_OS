// @find: tests for skill editor dialog, prefill skill draft, initial values, field errors, name taken 409, POST /api/skills
// @what: Tests that the skill editor prefills from a draft and shows the API's field problems beside their fields.
// @flow: Renders components/skills/SkillEditorDialog with a mocked fetch.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RouterProvider } from '../../lib/router'
import { clearSession, saveSession } from '../../lib/session'
import { ToastProvider } from '../../lib/toast'
import { SkillEditorDialog } from './SkillEditorDialog'

if (typeof HTMLDialogElement !== 'undefined' && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

let postResponse: () => Response

beforeEach(() => {
  postResponse = () => json({}, 201)
  saveSession('test-token', {
    userId: 'user-1',
    workspaceId: 'workspace-1',
    permissions: ['chat:use', 'agent:update', 'integration:read'],
    displayName: 'Maya Manager',
    email: 'maya@demo.test',
    role: 'manager',
  })
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/skills' && init?.method === 'POST') return postResponse()
      if (url === '/api/agents') {
        return json([{ id: 'a-eng', key: 'eng', name: 'Engineer', category: 'engineering', status: 'active', revision: 1, tools: [] }])
      }
      if (url === '/api/integrations') {
        return json([
          { server: 'github', displayName: 'GitHub', status: 'connected', sandbox: false, reconnectRequired: false, grantedScopes: [], missingScopes: [], tools: [] },
        ])
      }
      return new Response(null, { status: 404 })
    }),
  )
})

afterEach(() => {
  clearSession()
  vi.unstubAllGlobals()
})

function renderDialog(initial: Parameters<typeof SkillEditorDialog>[0]['initial']) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <ToastProvider>
          <SkillEditorDialog open onClose={() => {}} initial={initial} />
        </ToastProvider>
      </RouterProvider>
    </QueryClientProvider>,
  )
}

describe('SkillEditorDialog', () => {
  it('prefills a new skill from the draft it is given', async () => {
    renderDialog({
      name: 'From this thread',
      description: 'What we just did',
      instructions: 'Step one.\nStep two.',
      defaultAgentId: 'a-eng',
      requiredConnectors: ['github'],
    })
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: 'Write a skill' })).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Name')).toHaveValue('From this thread')
    expect(within(dialog).getByLabelText(/Description/)).toHaveValue('What we just did')
    expect(within(dialog).getByLabelText('Instructions')).toHaveValue('Step one.\nStep two.')
    expect(within(dialog).getByText(/19 of 20,000 characters/)).toBeInTheDocument()
    await within(dialog).findByRole('option', { name: 'Engineer' })
    expect(within(dialog).getByLabelText('Default agent')).toHaveValue('a-eng')
    expect(await within(dialog).findByRole('button', { name: 'GitHub' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('shows field problems from the API beside their fields', async () => {
    postResponse = () =>
      json(
        {
          code: 'validation_failed',
          detail: 'Some of the values supplied are not valid.',
          errors: { instructions: 'must not be blank', description: 'size must be at most 300' },
        },
        400,
      )
    renderDialog({ name: 'A skill', instructions: 'Something' })
    fireEvent.click(screen.getByRole('button', { name: 'Create skill' }))
    expect(await screen.findByText('must not be blank')).toBeInTheDocument()
    expect(screen.getByText('size must be at most 300')).toBeInTheDocument()
    expect(screen.getByLabelText('Instructions')).toHaveAttribute('aria-invalid', 'true')
  })

  it('says beside the name when another skill already has it', async () => {
    postResponse = () => json({ code: 'conflict', detail: 'A skill with this name already exists.' }, 409)
    renderDialog({ name: 'Weekly status', instructions: 'Something' })
    fireEvent.click(screen.getByRole('button', { name: 'Create skill' }))
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.getByText('Another skill in this workspace already has this name.')).toBeInTheDocument()
  })
})
