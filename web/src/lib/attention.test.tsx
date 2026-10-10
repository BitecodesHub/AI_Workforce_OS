// @find: tests for attention, needs you, approvals count, question count, browser notifications, polling
// @what: Unit tests for the attention counter and notifications.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  attentionItems,
  decidable,
  enableBrowserNotifications,
  getBrowserNotifications,
  setBrowserNotifications,
  unseenItems,
  useAttention,
  type AttentionApproval,
  type AttentionQuestion,
} from './attention'
import { RouterProvider, setAttentionCount, setPageTitle } from './router'
import { clearSession, saveSession } from './session'

/*
 * What is waiting on a person, and telling them while they are away: the count only covers what
 * they can act on, a notification fires once for each new item and only when the tab is hidden and
 * they have asked for it, and the choice is kept without ever throwing when storage is blocked.
 */

const approval = (id: string, extra: Partial<AttentionApproval> = {}): AttentionApproval => ({
  id,
  tool: 'gmail.send_message',
  summary: 'Send something outside the workspace using gmail.send_message',
  canDecide: true,
  ...extra,
})

const question = (id: string, extra: Partial<AttentionQuestion> = {}): AttentionQuestion => ({
  id,
  runId: `run-${id}`,
  goalTitle: 'Draft the welcome email',
  conversationId: null,
  ...extra,
})

describe('what is waiting', () => {
  it('counts only the approvals this person can decide', () => {
    const list: AttentionApproval[] = [
      approval('a'),
      approval('b', { canDecide: false }),
      { id: 'c', summary: 'From a service that does not say yet' },
    ]
    // One that predates the field reads as decidable; one marked false is somebody else's.
    expect(decidable(list).map((entry) => entry.id)).toEqual(['a', 'c'])
  })

  it('links each item to where it is dealt with, and reads the tool in words', () => {
    const items = attentionItems(
      [approval('a1'), approval('a2', { canDecide: false })],
      [question('q1', { conversationId: 'chat-9' }), question('q2')],
    )

    expect(items.map((item) => item.url)).toEqual([
      '/approvals#approval-a1',
      '/chat?c=chat-9#question-q1',
      '/runs/run-q2',
    ])
    expect(items[0]?.tag).toBe('approval-a1')
    expect(items[0]?.body).toContain('Gmail')
    expect(items[0]?.body).not.toContain('gmail.send_message')
  })
})

describe('unseenItems', () => {
  const items = (...ids: string[]) => attentionItems(ids.map((id) => approval(id)), [])

  it('remembers what was already waiting at the first look and reports none of it', () => {
    const first = unseenItems(items('a', 'b'), new Set(), false)
    expect(first.fresh).toEqual([])
    expect([...first.seen]).toEqual(['a', 'b'])
  })

  it('reports an id once, however many times it is fetched', () => {
    const first = unseenItems(items('a'), new Set(), false)
    const second = unseenItems(items('a', 'b'), first.seen, true)
    expect(second.fresh.map((item) => item.id)).toEqual(['b'])
    const third = unseenItems(items('a', 'b'), second.seen, true)
    expect(third.fresh).toEqual([])
  })

  it('does not report an item again after it has gone and come back under the same id', () => {
    const first = unseenItems(items('a'), new Set(), false)
    const gone = unseenItems([], first.seen, true)
    const back = unseenItems(items('a'), gone.seen, true)
    expect(back.fresh).toEqual([])
  })
})

/* ---- The preference -------------------------------------------------------------------------- */

function memoryStorage(): Storage {
  const stored = new Map<string, string>()
  return {
    get length() {
      return stored.size
    },
    clear: () => stored.clear(),
    getItem: (key) => stored.get(key) ?? null,
    key: (index) => [...stored.keys()][index] ?? null,
    removeItem: (key) => void stored.delete(key),
    setItem: (key, value) => void stored.set(key, String(value)),
  }
}

function installStorage(storage: Storage) {
  Object.defineProperty(window, 'localStorage', { configurable: true, value: storage })
}

const originalStorage = Object.getOwnPropertyDescriptor(window, 'localStorage')

/** A stand-in for the browser's Notification, recording what was shown. */
class FakeNotification {
  static permission: NotificationPermission = 'granted'
  static requested = 0
  static shown: Array<{ title: string; options: NotificationOptions | undefined; instance: FakeNotification }> = []
  static requestPermission = vi.fn(async () => {
    FakeNotification.requested += 1
    return FakeNotification.permission
  })
  onclick: (() => void) | null = null
  constructor(
    public title: string,
    options?: NotificationOptions,
  ) {
    FakeNotification.shown.push({ title, options, instance: this })
  }
  close = vi.fn()
}

function hidden(value: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => value })
}

beforeEach(() => {
  installStorage(memoryStorage())
  FakeNotification.permission = 'granted'
  FakeNotification.requested = 0
  FakeNotification.shown = []
  FakeNotification.requestPermission.mockClear()
  vi.stubGlobal('Notification', FakeNotification)
})

afterEach(() => {
  clearSession()
  sessionStorage.clear()
  setAttentionCount(0)
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  if (originalStorage) Object.defineProperty(window, 'localStorage', originalStorage)
  Reflect.deleteProperty(document, 'hidden')
})

describe('the browser-notification preference', () => {
  it('is off until somebody turns it on, and is kept per person', () => {
    expect(getBrowserNotifications('user-1')).toBe(false)
    expect(setBrowserNotifications('user-1', true)).toBe(true)
    expect(getBrowserNotifications('user-1')).toBe(true)
    expect(getBrowserNotifications('user-2')).toBe(false)
    setBrowserNotifications('user-1', false)
    expect(getBrowserNotifications('user-1')).toBe(false)
  })

  it('never throws when storage is blocked, and says the choice will not last', () => {
    installStorage({
      ...memoryStorage(),
      getItem: () => {
        throw new Error('blocked')
      },
      setItem: () => {
        throw new Error('blocked')
      },
      removeItem: () => {
        throw new Error('blocked')
      },
    })
    expect(getBrowserNotifications('user-1')).toBe(false)
    expect(setBrowserNotifications('user-1', true)).toBe(false)
  })

  it('asks the browser for permission only when it is turned on, and turns on only if it is granted', async () => {
    FakeNotification.permission = 'default'
    FakeNotification.requestPermission.mockImplementationOnce(async () => {
      FakeNotification.permission = 'granted'
      return 'granted'
    })
    expect(FakeNotification.requested).toBe(0)

    await expect(enableBrowserNotifications('user-1')).resolves.toBe('on')
    expect(FakeNotification.requestPermission).toHaveBeenCalledTimes(1)
    expect(getBrowserNotifications('user-1')).toBe(true)
  })

  it('leaves the setting off when the browser refuses, or cannot show notifications', async () => {
    FakeNotification.permission = 'denied'
    await expect(enableBrowserNotifications('user-1')).resolves.toBe('denied')
    expect(getBrowserNotifications('user-1')).toBe(false)

    vi.stubGlobal('Notification', undefined)
    await expect(enableBrowserNotifications('user-1')).resolves.toBe('unsupported')
  })
})

/* ---- The hook -------------------------------------------------------------------------------- */

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

let approvals: AttentionApproval[]
let questions: AttentionQuestion[]
let counts: { approvals: number; questions: number }

function Probe() {
  const attention = useAttention()
  return <output data-testid="count">{attention.count}</output>
}

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider>
        <Probe />
      </RouterProvider>
    </QueryClientProvider>,
  )
  return { client, ...view }
}

/** Both queries have answered once and the hook has looked at what they said. */
async function firstLook(client: QueryClient) {
  await waitFor(() => {
    expect(client.getQueryState(['attention', 'approvals'])?.status).toBe('success')
    expect(client.getQueryState(['attention', 'questions'])?.status).toBe('success')
  })
  await act(async () => {})
}

async function refetch(client: QueryClient) {
  await act(async () => {
    await client.invalidateQueries({ queryKey: ['attention'] })
  })
}

describe('useAttention', () => {
  beforeEach(() => {
    approvals = []
    questions = []
    counts = { approvals: 0, questions: 0 }
    saveSession('token', {
      userId: 'user-1',
      workspaceId: 'workspace-1',
      permissions: ['approval:read', 'run:read'],
      displayName: 'Maya Manager',
      email: 'maya@example.test',
      role: 'manager',
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url.startsWith('/api/approvals')) {
          counts.approvals += 1
          return json(approvals)
        }
        if (url.startsWith('/api/orchestrator/questions')) {
          counts.questions += 1
          return json(questions)
        }
        return json([])
      }),
    )
    setPageTitle('Agents · AI Workforce OS')
    hidden(false)
  })

  it('puts the count of what this person can act on in front of the tab title, on any screen', async () => {
    approvals = [approval('a1'), approval('a2', { canDecide: false })]
    questions = [question('q1')]
    const { getByTestId } = mount()

    await waitFor(() => expect(getByTestId('count')).toHaveTextContent('2'))
    expect(document.title).toBe('(2) Agents · AI Workforce OS')
  })

  it('asks for the questions that are the person’s own, and keeps polling while the tab is hidden', async () => {
    const { client } = mount()
    await waitFor(() => expect(counts.approvals).toBe(1))

    const fetchMock = vi.mocked(fetch)
    expect(fetchMock.mock.calls.map(([url]) => url)).toContain('/api/orchestrator/questions?status=pending&mine=true')
    for (const key of [['attention', 'approvals'], ['attention', 'questions']]) {
      const observer = client.getQueryCache().find({ queryKey: key })?.observers[0]
      expect(observer?.options.refetchInterval).toBe(30_000)
      expect(observer?.options.refetchIntervalInBackground).toBe(true)
    }
  })

  it('takes the count off the title again when nothing is waiting', async () => {
    approvals = [approval('a1')]
    const { client } = mount()
    await waitFor(() => expect(document.title).toBe('(1) Agents · AI Workforce OS'))

    approvals = []
    await refetch(client)

    await waitFor(() => expect(document.title).toBe('Agents · AI Workforce OS'))
  })

  it('keeps a count a screen wrote into its own title from showing twice', async () => {
    approvals = [approval('a1')]
    mount()
    await waitFor(() => expect(document.title).toBe('(1) Agents · AI Workforce OS'))

    act(() => setPageTitle('(3) Orchestrator · AI Workforce OS'))

    expect(document.title).toBe('(1) Orchestrator · AI Workforce OS')
  })

  it('notifies once for each new item, and not for what was already waiting when the console opened', async () => {
    setBrowserNotifications('user-1', true)
    hidden(true)
    approvals = [approval('already-here')]
    const { client } = mount()
    await waitFor(() => expect(document.title).toContain('(1)'))
    expect(FakeNotification.shown).toHaveLength(0)

    approvals = [approval('already-here'), approval('new-one')]
    await refetch(client)

    await waitFor(() => expect(FakeNotification.shown).toHaveLength(1))
    expect(FakeNotification.shown[0]?.options?.tag).toBe('approval-new-one')
    expect(FakeNotification.shown[0]?.title).toBe('An agent needs your approval')

    // The same item arriving on the next poll is not news again.
    await refetch(client)
    await refetch(client)
    expect(FakeNotification.shown).toHaveLength(1)
  })

  it('opens the item when its notification is clicked', async () => {
    setBrowserNotifications('user-1', true)
    hidden(true)
    const { client } = mount()
    await firstLook(client)
    window.focus = vi.fn()

    approvals = [approval('new-one')]
    await refetch(client)
    await waitFor(() => expect(FakeNotification.shown).toHaveLength(1))
    act(() => FakeNotification.shown[0]?.instance.onclick?.())

    expect(window.location.pathname + window.location.hash).toBe('/approvals#approval-new-one')
    expect(FakeNotification.shown[0]?.instance.close).toHaveBeenCalled()
  })

  it('says nothing while the tab is in front, or while the person has not asked for notifications', async () => {
    approvals = []
    const { client } = mount()
    await firstLook(client)

    // In front, and opted in: the title and the badge are enough.
    act(() => void setBrowserNotifications('user-1', true))
    approvals = [approval('seen-in-front')]
    await refetch(client)
    await waitFor(() => expect(document.title).toContain('(1)'))
    expect(FakeNotification.shown).toHaveLength(0)

    // Hidden, but never opted in.
    act(() => void setBrowserNotifications('user-1', false))
    hidden(true)
    approvals = [approval('seen-in-front'), approval('while-away')]
    await refetch(client)
    await waitFor(() => expect(document.title).toContain('(2)'))
    expect(FakeNotification.shown).toHaveLength(0)
  })

  it('does not ask for what the role cannot read', async () => {
    clearSession()
    saveSession('token', {
      userId: 'user-2',
      workspaceId: 'workspace-1',
      permissions: [],
      displayName: 'Vic Viewer',
      email: 'vic@example.test',
      role: 'viewer',
    })
    mount()
    await act(async () => {})

    expect(counts).toEqual({ approvals: 0, questions: 0 })
    expect(document.title).toBe('Agents · AI Workforce OS')
  })
})
