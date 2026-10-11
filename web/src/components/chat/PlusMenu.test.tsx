// @find: tests for PlusMenu, plus menu keyboard, upward popover, knowledge picker, repository picker, skill chip, schedule this, composer context payload
// @what: Automated tests for the composer's "+" menu, its lists, the chips it adds and what the message then carries.
// @flow: Run with the web test runner; covers PlusMenu, ContextPickers, ContextChips and Composer.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { createRef } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Agent } from '../../lib/queries'
import { RouterProvider } from '../../lib/router'
import { Composer } from './Composer'
import { plusMenuPosition } from './PlusMenu'

/*
 * The "+" button replaces the paperclip: one popover, opened upward, reachable from the keyboard,
 * whose lists search as you type. What it attaches shows as chips with remove buttons and goes out
 * with the message as `context`; a skill alone is enough to send.
 */

const trees = vi.hoisted(() => ({
  byPath: {} as Record<string, { path: string; type: string }[]>,
}))

const repos = vi.hoisted(() => ({
  value: {
    data: { connected: true, message: null, repos: [] as unknown[] },
    isPending: false,
    isError: false,
    error: null,
  } as Record<string, unknown>,
}))

vi.mock('../../lib/skillQueries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/skillQueries')>()
  return {
    ...actual,
    useSkills: () => ({
      data: [
        {
          id: 's1',
          name: 'Weekly status report',
          description: 'What shipped and what is blocked',
          instructions: '1. List',
          defaultAgentId: 'a2',
          defaultAgentName: 'Support',
          requiredConnectors: [],
          builtIn: true,
          createdBy: null,
          createdAt: '',
          updatedAt: '',
        },
        {
          id: 's2',
          name: 'Code review a PR',
          description: 'Review a pull request',
          instructions: '1. Read',
          defaultAgentId: null,
          defaultAgentName: null,
          requiredConnectors: ['github'],
          builtIn: true,
          createdBy: null,
          createdAt: '',
          updatedAt: '',
        },
      ],
      isPending: false,
      isError: false,
    }),
  }
})

vi.mock('../../lib/chatContextQueries', () => ({
  useGitHubRepos: () => repos.value,
  useGitHubBranches: () => ({ data: { branches: ['main', 'dev'] } }),
  useGitHubTree: (_repo: string | null, _ref: string | null, path: string | null) => {
    const entries = trees.byPath[path ?? '']
    return { data: entries ? { available: true, entries } : { available: false, entries: [] }, isFetching: false, isError: false }
  },
}))

vi.mock('../../lib/queries', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../lib/queries')>()
  return {
    ...actual,
    useSources: () => ({
      data: [
        { id: 'src-1', name: 'Handbook', documentCount: 2, agentId: null },
        { id: 'src-2', name: 'Agent notes', documentCount: 1, agentId: 'a1' },
      ],
      isPending: false,
      isError: false,
    }),
    useSourceDocuments: () => ({
      data: [
        { id: 'doc-1', title: 'Refund policy', chunkCount: 4, removedAtSource: false },
        { id: 'doc-2', title: 'Leave policy', chunkCount: 3, removedAtSource: false },
      ],
      isPending: false,
      isError: false,
    }),
    useAgents: () => ({ data: [], isLoading: false }),
  }
})

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

const AGENTS: Agent[] = [
  { id: 'a1', key: 'hr', name: 'HR', category: 'people', status: 'active', revision: 1 },
  { id: 'a2', key: 'support', name: 'Support', category: 'support', status: 'active', revision: 1 },
] as Agent[]

type Reply = { questionId: string; agentName: string; agentId: string | null; auto: boolean }

function renderComposer(onSend = vi.fn(async () => true), replyTo: Reply | null = null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <RouterProvider>
      <QueryClientProvider client={client}>
        <Composer
          userId="user-1"
          conversationId={null}
          agents={AGENTS}
          onSend={onSend}
          sending={false}
          replyTo={replyTo}
          onClearReply={() => {}}
          lastUserText={null}
          inputRef={createRef()}
        />
      </QueryClientProvider>
    </RouterProvider>,
  )
  return onSend
}

const plus = () => screen.getByRole('button', { name: /Add to message/ })
const field = () => screen.getByRole('textbox', { name: 'Message the workforce' })
const send = () => screen.getByRole('button', { name: 'Send' })

beforeEach(() => {
  trees.byPath = {}
  vi.stubGlobal('fetch', () => Promise.reject(new Error('No network in tests')))
  repos.value = {
    data: {
      connected: true,
      message: null,
      repos: [
        { fullName: 'acme/website', owner: 'acme', name: 'website', private: false, description: 'Site', defaultBranch: 'main' },
        { fullName: 'acme/api', owner: 'acme', name: 'api', private: true, description: 'Backend', defaultBranch: 'main' },
      ],
    },
    isPending: false,
    isError: false,
    error: null,
  }
})

afterEach(() => {
  // Unmounted first: the + popover is portalled into the body, so clearing the body under a
  // mounted tree would pull its node out from under React.
  cleanup()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

describe('plusMenuPosition', () => {
  it('opens above the trigger when there is room, and below when there is not', () => {
    expect(plusMenuPosition({ top: 700, bottom: 730, left: 20 }, { width: 340, height: 300 }, { width: 1200, height: 800 }))
      .toEqual({ top: 394, left: 20, placement: 'above' })
    expect(plusMenuPosition({ top: 40, bottom: 70, left: 20 }, { width: 340, height: 300 }, { width: 1200, height: 800 }).placement)
      .toBe('below')
    // Clamped inside a phone-width window.
    expect(plusMenuPosition({ top: 700, bottom: 730, left: 300 }, { width: 359, height: 200 }, { width: 375, height: 800 }).left).toBe(8)
  })
})

describe('the + menu', () => {
  it('replaces the paperclip and the @ button with one + that keeps the mic beside it', () => {
    renderComposer()
    expect(plus().getAttribute('aria-haspopup')).toBe('menu')
    expect(screen.queryByRole('button', { name: 'Attach files' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Choose an agent' })).toBeNull()
  })

  it('opens with every item, each with a short description, focuses the first, and moves with the keyboard', () => {
    renderComposer()
    fireEvent.click(plus())
    const menu = screen.getByRole('menu', { name: 'Add to message' })
    const items = within(menu).getAllByRole('menuitem')
    expect(items.map((item) => item.textContent)).toEqual([
      'Upload filesDocuments and images, up to 10 files',
      'Add from KnowledgeGround this message in workspace documents',
      'Attach a repositoryWork on a GitHub repository, branch or folder',
      'Use a skillApply a saved playbook to this message',
      'Schedule thisRun this message on a timetable',
      'Choose an agentAddress someone in particular, or type @',
    ])
    expect(plus().getAttribute('aria-expanded')).toBe('true')
    expect(document.activeElement).toBe(items[0])

    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(items[1])
    fireEvent.keyDown(menu, { key: 'End' })
    expect(document.activeElement).toBe(items[5])
    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(items[0])
    fireEvent.keyDown(menu, { key: 's' })
    expect(document.activeElement).toBe(items[4])

    fireEvent.keyDown(menu, { key: 'Escape' })
    expect(screen.queryByRole('menu')).toBeNull()
    expect(document.activeElement).toBe(plus())
  })

  it('opens a list with Right, searches it, goes back with Escape, and closes on a press outside', () => {
    renderComposer()
    fireEvent.click(plus())
    const skillRow = screen.getByRole('menuitem', { name: /Use a skill/ })
    skillRow.focus()
    fireEvent.keyDown(skillRow, { key: 'ArrowRight' })

    const search = screen.getByRole('combobox', { name: 'Skills' })
    expect(document.activeElement).toBe(search)
    fireEvent.change(search, { target: { value: 'review' } })
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual([expect.stringContaining('Code review a PR')])

    fireEvent.keyDown(search, { key: 'Escape' })
    expect(screen.getByRole('menu', { name: 'Add to message' })).toBeTruthy()
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: /Use a skill/ }))

    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('applies a skill: chip, placeholder, send with the skill alone, and the payload carries it', async () => {
    const onSend = renderComposer()
    fireEvent.click(plus())
    fireEvent.click(screen.getByRole('menuitem', { name: /Use a skill/ }))
    const search = screen.getByRole('combobox', { name: 'Skills' })
    fireEvent.keyDown(search, { key: 'Enter' })
    // Focus goes to the text box, so the person can type the details straight away.
    await waitFor(() => expect(document.activeElement).toBe(field()))

    const chips = screen.getByRole('list', { name: 'Attached to this message' })
    expect(chips.textContent).toContain('Skill: Weekly status report')
    expect(field().getAttribute('placeholder')).toContain('Weekly status report')
    expect((send() as HTMLButtonElement).disabled).toBe(false)

    await act(async () => {
      fireEvent.click(send())
    })
    expect(onSend).toHaveBeenCalledWith('', [], [], {
      knowledge: [],
      repo: null,
      skill: { id: 's1', name: 'Weekly status report', defaultAgentId: 'a2', defaultAgentName: 'Support' },
    })
    expect(screen.queryByRole('list', { name: 'Attached to this message' })).toBeNull()
  })

  it('a chip can be removed, and a failed send puts the attachments back', async () => {
    const onSend = renderComposer(vi.fn(async () => false))
    fireEvent.click(plus())
    fireEvent.click(screen.getByRole('menuitem', { name: /Use a skill/ }))
    fireEvent.click(screen.getByRole('option', { name: /Code review a PR/ }))
    fireEvent.change(field(), { target: { value: 'Review #12' } })

    await act(async () => {
      fireEvent.click(send())
    })
    expect(onSend).toHaveBeenCalledTimes(1)
    await waitFor(() =>
      expect(screen.getByRole('list', { name: 'Attached to this message' }).textContent).toContain('Code review a PR'),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Remove skill Code review a PR' }))
    expect(screen.queryByRole('list', { name: 'Attached to this message' })).toBeNull()
  })

  it('grounds a message in a whole source and single documents, never offering an agent\'s own documents', async () => {
    const onSend = renderComposer()
    fireEvent.click(plus())
    fireEvent.click(screen.getByRole('menuitem', { name: /Add from Knowledge/ }))
    const list = screen.getByRole('listbox', { name: 'Workspace sources' })
    expect(list.getAttribute('aria-multiselectable')).toBe('true')
    expect(within(list).queryByText('Agent notes')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Choose documents in Handbook' }))
    fireEvent.click(screen.getByRole('option', { name: /Refund policy/ }))
    expect(screen.getByRole('option', { name: /Refund policy/ }).getAttribute('aria-selected')).toBe('true')
    fireEvent.keyDown(screen.getByRole('combobox', { name: 'Documents in Handbook' }), { key: 'Escape' })

    fireEvent.change(field(), { target: { value: 'How long do refunds take?' } })
    expect(screen.getByRole('list', { name: 'Attached to this message' }).textContent).toContain('Refund policy')
    await act(async () => {
      fireEvent.click(send())
    })
    expect(onSend).toHaveBeenCalledWith('How long do refunds take?', [], [], {
      knowledge: [{ sourceId: 'src-1', documentId: 'doc-1', title: 'Refund policy' }],
      repo: null,
      skill: null,
    })
  })

  it('attaches a repository with a branch and path, shown as owner/repo@branch/path', async () => {
    const onSend = renderComposer()
    fireEvent.click(plus())
    fireEvent.click(screen.getByRole('menuitem', { name: /Attach a repository/ }))
    const search = screen.getByRole('combobox', { name: 'GitHub repositories' })
    fireEvent.change(search, { target: { value: 'api' } })
    fireEvent.keyDown(search, { key: 'Enter' })

    fireEvent.change(screen.getByLabelText('Branch'), { target: { value: 'dev' } })
    fireEvent.change(screen.getByLabelText(/Folder or file/), { target: { value: '/src/app/' } })
    expect(screen.getByText('acme/api@dev/src/app')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Attach repository' }))

    expect(screen.getByRole('list', { name: 'Attached to this message' }).textContent).toContain('acme/api@dev/src/app')
    fireEvent.change(field(), { target: { value: 'Explain the app folder' } })
    await act(async () => {
      fireEvent.click(send())
    })
    expect(onSend).toHaveBeenCalledWith('Explain the app folder', [], [], {
      knowledge: [],
      repo: { owner: 'acme', name: 'api', branch: 'dev', path: 'src/app' },
      skill: null,
    })
  })

  it('explains when GitHub is not connected and points at Connectors', () => {
    repos.value = {
      data: { connected: false, message: 'GitHub is not connected. Connect it in Connectors to attach a repository.', repos: [] },
      isPending: false,
      isError: false,
      error: null,
    }
    renderComposer()
    fireEvent.click(plus())
    fireEvent.click(screen.getByRole('menuitem', { name: /Attach a repository/ }))
    expect(screen.getByText(/GitHub is not connected/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Open Connectors' })).toBeTruthy()
  })

  it('chooses an agent from the menu as a mention chip, and typing @ still works', () => {
    renderComposer()
    fireEvent.click(plus())
    fireEvent.click(screen.getByRole('menuitem', { name: /Choose an agent/ }))
    fireEvent.click(screen.getByRole('option', { name: /Support/ }))
    expect(screen.getByRole('button', { name: 'Remove Support' })).toBeTruthy()

    fireEvent.change(field(), { target: { value: '@H', selectionStart: 2 } })
    expect(screen.getByRole('listbox', { name: 'Mention an agent' }).textContent).toContain('HR')
  })

  it('Schedule this opens the schedule dialog with the message as its instruction', () => {
    renderComposer()
    fireEvent.change(field(), { target: { value: 'Summarise new support tickets' } })
    fireEvent.click(plus())
    fireEvent.click(screen.getByRole('menuitem', { name: /Schedule this/ }))
    expect(screen.queryByRole('menu')).toBeNull()
    expect((screen.getByLabelText('Instruction') as HTMLTextAreaElement).value).toBe('Summarise new support tickets')
    expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('Summarise new support tickets')
  })

  it('browses a large folder in full, filters it, and attaches a file inside it', async () => {
    trees.byPath = {
      '': [
        { path: 'docs', type: 'dir' },
        { path: 'web', type: 'dir' },
        { path: 'README.md', type: 'file' },
      ],
      web: [
        { path: 'web/src', type: 'dir' },
        ...Array.from({ length: 120 }, (_, i) => ({ path: `web/file${i}.ts`, type: 'file' })),
        { path: 'web/package.json', type: 'file' },
      ],
    }
    const onSend = renderComposer()
    fireEvent.click(plus())
    fireEvent.click(screen.getByRole('menuitem', { name: /Attach a repository/ }))
    fireEvent.keyDown(screen.getByRole('combobox', { name: 'GitHub repositories' }), { key: 'Enter' })

    fireEvent.click(screen.getByRole('button', { name: 'web/' }))
    const list = screen.getByRole('list', { name: 'Contents of web' })
    expect(within(list).getAllByRole('button')).toHaveLength(1 + 1 + 121) // up, src/, every file
    fireEvent.change(screen.getByRole('searchbox', { name: 'Filter this folder' }), { target: { value: 'package' } })
    expect(within(list).getAllByRole('button').map((b) => b.textContent)).toEqual(['.. up one folder', 'package.json'])

    fireEvent.click(screen.getByRole('button', { name: 'package.json' }))
    expect(screen.getByText('acme/website@main/web/package.json')).toBeTruthy()
    // Choosing a file keeps the folder listed rather than trying to open the file as one.
    expect(screen.getByRole('list', { name: 'Contents of web' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Attach repository' }))
    fireEvent.change(field(), { target: { value: 'Explain this file' } })
    await act(async () => {
      fireEvent.click(send())
    })
    expect(onSend).toHaveBeenCalledWith('Explain this file', [], [], {
      knowledge: [],
      repo: { owner: 'acme', name: 'website', branch: 'main', path: 'web/package.json' },
      skill: null,
    })
  })
})
