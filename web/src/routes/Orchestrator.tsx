import { useMemo, useRef, useState } from 'react'
import { Button, IconButton, PageHeader } from '../components/ui'
import { Collapsible, useCollapsed } from '../components/ui/Collapsible'
import { QueryState } from '../components/ui/QueryState'
import { ShortcutsDialog } from '../components/ui/ShortcutsDialog'
import { TaskDialog } from '../components/ui/TaskDialog'
import { AgentsStrip } from '../components/orchestrator/AgentsStrip'
import { OrchestratorBoard } from '../components/orchestrator/Board'
import { diffCards, countChanged } from '../components/orchestrator/boardChanges'
import { FlowMap, nodeStatus } from '../components/orchestrator/FlowMap'
import { Freshness } from '../components/orchestrator/Freshness'
import { GoalSheet } from '../components/orchestrator/GoalSheet'
import { buildBoardCards, columnTitle } from '../components/orchestrator/layout'
import type { BoardColumn, CardStatusKey } from '../components/orchestrator/layout'
import { buildNeedsYou } from '../components/orchestrator/needsYou'
import { NeedsYouInbox } from '../components/orchestrator/NeedsYouInbox'
import { RunSheet } from '../components/orchestrator/RunSheet'
import { StopEverythingDialog } from '../components/orchestrator/StopEverythingDialog'
import { SummaryStrip } from '../components/orchestrator/SummaryStrip'
import { Swimlanes } from '../components/orchestrator/Swimlanes'
import { formatCount } from '../lib/format'
import { formatHotkey, useHotkeys } from '../lib/hotkeys'
import { readStored, writeStored } from '../lib/persist'
import { useBoard } from '../lib/queries'
import type { Board, BoardWindow } from '../lib/queries'
import { useDocumentTitle, useRouter } from '../lib/router'
import { can, profile } from '../lib/session'
import { useNow } from '../lib/useNow'

/*
 * Live coordination (B2): which agents are running, for whom, and what needs a person right now.
 * A summary row of filters, an inbox of what is waiting on somebody, a board of every goal, the
 * workforce as a system, and its timeline, all reading from one poll so nothing on the page ever
 * disagrees with anything else on it.
 */

const WINDOW_TO_API: Record<string, BoardWindow> = { '1h': 'PT1H', '2h': 'PT2H', '6h': 'PT6H', '24h': 'PT24H', today: 'TODAY' }

const isBoolean = (value: unknown): value is boolean => typeof value === 'boolean'

const BUSY_NODE = new Set(['running', 'asking', 'waiting'])

function readParam(search: URLSearchParams, key: string): string | null {
  return search.get(key)
}

/** Rewrites the current URL's query string, keeping every other parameter as it is. */
function withParams(base: URLSearchParams, changes: Record<string, string | null>): string {
  const next = new URLSearchParams(base)
  for (const [key, value] of Object.entries(changes)) {
    if (value === null) next.delete(key)
    else next.set(key, value)
  }
  const qs = next.toString()
  return `${window.location.pathname}${qs ? `?${qs}` : ''}`
}

export function Orchestrator() {
  const { search } = useRouter()
  const windowToken = readParam(search, 'window') ?? '2h'
  const apiWindow = WINDOW_TO_API[windowToken] ?? 'PT2H'

  const [live, setLiveState] = useState<boolean>(() => readStored('orc.live', true, isBoolean))
  const [frozen, setFrozen] = useState<Board | null>(null)
  const boardQuery = useBoard({ window: apiWindow, paused: !live })
  const latest = boardQuery.data
  const board = frozen ?? latest

  const setLive = (next: boolean) => {
    setLiveState(next)
    writeStored('orc.live', next)
    if (!next) setFrozen(latest ?? null)
    else setFrozen(null)
  }

  return (
    <div className="page orc-page" data-sheet-open={search.get('goal') || search.get('run') ? true : undefined}>
      <QueryState query={boardQuery} permission="run:read" what="the orchestrator board" keepData rows={6}>
        {() =>
          board ? (
            <OrchestratorBody
              board={board}
              live={live}
              onSetLive={setLive}
              latest={latest}
              frozen={frozen}
              onShowLatest={() => setFrozen(latest ?? null)}
              apiWindowToken={windowToken}
            />
          ) : null
        }
      </QueryState>
    </div>
  )
}

function OrchestratorBody({
  board,
  live,
  onSetLive,
  latest,
  frozen,
  onShowLatest,
  apiWindowToken,
}: {
  board: Board
  live: boolean
  onSetLive: (next: boolean) => void
  latest: Board | undefined
  frozen: Board | null
  onShowLatest: () => void
  apiWindowToken: string
}) {
  const { search, navigate } = useRouter()
  const now = useNow(1_000)
  const me = profile()?.userId ?? null
  const canStopAll = can('run:cancel')
  const canCreate = can('task:create')

  const goalsSectionRef = useRef<HTMLDivElement>(null)
  const [taskDialogOpen, setTaskDialogOpen] = useState(false)
  const [stopOpen, setStopOpen] = useState(false)
  const [shortcutsOpen, setShortcutsOpen] = useState(false)
  const [liveMessage, setLiveMessage] = useState('')
  const [preTodayWindow, setPreTodayWindow] = useState('2h')

  const goalId = search.get('goal')
  const runId = !goalId ? search.get('run') : null
  const focusParam = search.get('focus')
  const focus: 'question' | 'approval' | null = focusParam === 'question' || focusParam === 'approval' ? focusParam : null

  const view: 'board' | 'list' = (() => {
    const explicit = search.get('view')
    if (explicit === 'board' || explicit === 'list') return explicit
    return typeof window !== 'undefined' && window.innerWidth < 768 ? 'list' : 'board'
  })()
  const group = (search.get('group') as 'none' | 'agent' | 'requester' | null) ?? 'none'
  const selectedAgentId = search.get('agent')

  const statusParam = search.get('status')
  const status = useMemo<ReadonlySet<CardStatusKey>>(
    () => new Set((statusParam ? statusParam.split(',') : []) as CardStatusKey[]),
    [statusParam],
  )

  const cards = useMemo(() => buildBoardCards(board), [board])

  // "Previous value during render" (0.2): the highlight set is recomputed only when a fresh live
  // board arrives, never from an effect. There is no highlight at all while updates are paused.
  const [lastLiveBoard, setLastLiveBoard] = useState<Board | null>(null)
  const [liveChangedIds, setLiveChangedIds] = useState<ReadonlySet<string>>(new Set())
  if (live && latest && latest !== lastLiveBoard) {
    setLiveChangedIds(lastLiveBoard ? diffCards(lastLiveBoard, latest) : new Set())
    setLastLiveBoard(latest)
  }
  const changedIds = live ? liveChangedIds : new Set<string>()

  // New "Needs you" items get announced once each, by id, the same way a card's column change
  // does - compared against the previous live board rather than tracked with an effect.
  const [lastNeedsIds, setLastNeedsIds] = useState<ReadonlySet<string>>(new Set())
  if (latest && latest !== lastLiveBoard) {
    const everyone = buildNeedsYou(latest, { me, scope: 'everyone' })
    const ids = new Set(everyone.map((item) => `${item.kind}-${item.id}`))
    if (lastNeedsIds.size > 0) {
      const fresh = everyone.find(
        (item): item is Extract<typeof item, { kind: 'question' | 'approval' }> =>
          (item.kind === 'question' || item.kind === 'approval') && !lastNeedsIds.has(`${item.kind}-${item.id}`),
      )
      if (fresh) {
        setLiveMessage(fresh.kind === 'question' ? `New question from ${fresh.title}.` : `New approval waiting: ${fresh.summary}.`)
      }
    }
    setLastNeedsIds(ids)
  }

  const pendingChanges = frozen && latest ? countChanged(frozen, latest) : 0

  const questionCount = board.questions.filter((question) => question.status === 'pending').length
  const approvalCount = board.approvals.length
  const forMeCount =
    board.questions.filter((question) => question.status === 'pending' && me != null && question.requestedBy === me).length +
    board.approvals.filter((approval) => approval.canDecide).length
  useDocumentTitle(forMeCount > 0 ? `(${forMeCount}) Orchestrator` : 'Orchestrator')

  const setWindowToken = (token: string) => navigate(withParams(search, { window: token === '2h' ? null : token }), { replace: true, scroll: false })
  const setSelectedAgentId = (agentId: string | null) => navigate(withParams(search, { agent: agentId }), { replace: true, scroll: false })
  const toggleStatus = (value: CardStatusKey) => {
    const next = new Set(status)
    if (next.has(value)) next.delete(value)
    else next.add(value)
    navigate(withParams(search, { status: next.size > 0 ? [...next].join(',') : null }), { replace: true, scroll: false })
  }
  const isToday = apiWindowToken === 'today'
  const toggleTodayTile = (key: CardStatusKey) => {
    if (isToday && status.has(key)) {
      navigate(withParams(search, { window: preTodayWindow === '2h' ? null : preTodayWindow, status: null }), { replace: true, scroll: false })
    } else {
      if (!isToday) setPreTodayWindow(apiWindowToken)
      navigate(withParams(search, { window: 'today', status: key }), { replace: true, scroll: false })
    }
  }

  const openGoal = (id: string, focusNext?: 'question' | 'approval') =>
    navigate(withParams(search, { goal: id, run: null, focus: focusNext ?? null }), { scroll: false })
  const openRun = (id: string, focusNext?: 'question' | 'approval') =>
    navigate(withParams(search, { run: id, goal: null, focus: focusNext ?? null }), { scroll: false })
  const closeSheet = () => navigate(withParams(search, { goal: null, run: null, focus: null }), { replace: true, scroll: false })

  const onCardMoved = (_cardId: string, column: string) => {
    setLiveMessage(`Moved to ${columnTitle(column as BoardColumn, board.window, board.windowMinutes)}.`)
  }

  const focusSearch = () => {
    goalsSectionRef.current?.querySelector<HTMLInputElement>('input[type="search"]')?.focus()
  }

  useHotkeys(
    [
      { key: 'k', mod: true, allowInInput: true, handler: focusSearch },
      { key: '/', mod: true, allowInInput: true, handler: () => setShortcutsOpen(true) },
      {
        key: 'Escape',
        allowInInput: true,
        handler: (event) => {
          // An Escape already handled by an open menu (it closes the menu) is not also a request
          // to clear the agent filter that menu may have just set.
          if (event.defaultPrevented) return
          if (!goalId && !runId && selectedAgentId) setSelectedAgentId(null)
        },
      },
    ],
    true,
  )

  const [workforceOpen, setWorkforceOpen] = useCollapsed('orc.sections.workforce', true)
  const [timelineOpen, setTimelineOpen] = useCollapsed('orc.sections.timeline', true)
  const busyAgents = board.agents.filter((agent) => BUSY_NODE.has(nodeStatus(agent))).length
  const workforceSummary = `${formatCount(board.agents.length)} agents · ${busyAgents > 0 ? `${formatCount(busyAgents)} busy` : 'all idle'}`

  const nothingActive = board.stats.running + board.stats.queued + board.stats.waitingApproval + board.stats.waitingInput === 0

  return (
    <>
      <PageHeader
        eyebrow="Live coordination"
        title="Orchestrator"
        description="Every agent at work right now, who asked for it, and what needs you."
        action={
          <>
            <Button variant="outline" aria-pressed={live} onClick={() => onSetLive(!live)}>
              {live && <span className="orc-live-dot" aria-hidden="true" />}
              Live updates
            </Button>
            {canCreate && (
              <Button
                onClick={() => setTaskDialogOpen(true)}
              >
                New goal
              </Button>
            )}
            <a className="button button-outline" href="/schedules">
              Schedules
            </a>
            {canStopAll && (
              <Button variant="danger" onClick={() => setStopOpen(true)} disabled={nothingActive}>
                Stop everything
              </Button>
            )}
            <IconButton label="Keyboard shortcuts" onClick={() => setShortcutsOpen(true)}>
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <rect x="1.5" y="4" width="13" height="8" rx="1.5" stroke="currentColor" strokeWidth="1.3" />
                <path d="M4 7h.01M6.5 7h.01M9 7h.01M11.5 7h.01M4.5 9.5h7" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
              </svg>
            </IconButton>
          </>
        }
        meta={<Freshness live={live} generatedAt={board.generatedAt} pendingChanges={pendingChanges} onShowLatest={onShowLatest} />}
      />

      <p className="visually-hidden" role="status" aria-live="polite">
        {liveMessage}
      </p>

      <div className="orc-sections">
        <SummaryStrip
          cards={cards}
          stats={board.stats}
          questionCount={questionCount}
          approvalCount={approvalCount}
          status={status}
          onToggleStatus={toggleStatus}
          isToday={isToday}
          onToggleDoneToday={() => toggleTodayTile('finished')}
          onToggleFailedToday={() => toggleTodayTile('failed')}
        />

        <NeedsYouInbox board={latest ?? board} onOpenGoal={openGoal} onOpenRun={openRun} />

        <section aria-labelledby="orc-goals-heading" ref={goalsSectionRef} className="orc-section">
          <h2 id="orc-goals-heading" className="section-heading orc-section-title">
            Goals
          </h2>
          <OrchestratorBoard
            board={board}
            cards={cards}
            view={view}
            group={group}
            selectedAgentId={selectedAgentId}
            onSelectAgent={setSelectedAgentId}
            onOpenGoal={openGoal}
            changedIds={changedIds}
            onCardMoved={onCardMoved}
            onNewGoal={canCreate ? () => setTaskDialogOpen(true) : undefined}
          />
        </section>

        <Collapsible title="Workforce" summary={workforceSummary} open={workforceOpen} onToggle={setWorkforceOpen} className="orc-collapsible">
          <div className="orc-workforce">
            <div className="orc-panel orc-panel-map">
              <div className="orc-panel-head">
                <h3 className="orc-panel-title">Live map</h3>
                <span className="caption muted">Select an agent to show only its goals.</span>
              </div>
              <FlowMap board={board} selectedAgentId={selectedAgentId} onSelectAgent={setSelectedAgentId} onOpenGoal={openGoal} />
            </div>
            <div className="orc-panel orc-panel-agents">
              <AgentsStrip board={board} onOpenGoal={openGoal} />
            </div>
          </div>
        </Collapsible>

        <Collapsible
          title="Timeline"
          open={timelineOpen}
          onToggle={setTimelineOpen}
          className="orc-collapsible"
          actions={
            <label className="orc-range">
              <span className="visually-hidden">Timeline range</span>
              <select className="select orc-range-select" value={apiWindowToken} onChange={(event) => setWindowToken(event.target.value)}>
                <option value="1h">Last hour</option>
                <option value="2h">Last 2 hours</option>
                <option value="6h">Last 6 hours</option>
                <option value="24h">Last 24 hours</option>
                <option value="today">Today</option>
              </select>
            </label>
          }
        >
          <div className="orc-panel orc-panel-timeline">
            <Swimlanes board={board} now={now} windowMinutes={board.windowMinutes} timezone={board.timezone} onOpenGoal={openGoal} />
          </div>
        </Collapsible>
      </div>

      {goalId && (
        <GoalSheet
          goalId={goalId}
          board={latest ?? board}
          orderedGoalIds={cards.map((card) => card.id)}
          onClose={closeSheet}
          onNavigate={(id) => navigate(withParams(search, { goal: id, focus: null }), { replace: true, scroll: false })}
          focus={focus}
        />
      )}
      {runId && <RunSheet runId={runId} board={latest ?? board} onClose={closeSheet} />}

      {canStopAll && <StopEverythingDialog open={stopOpen} onClose={() => setStopOpen(false)} board={board} />}
      {canCreate && (
        <TaskDialog
          open={taskDialogOpen}
          onClose={() => setTaskDialogOpen(false)}
          onSuccess={(_runId, started) => {
            setTaskDialogOpen(false)
            if (started.goalId) openGoal(started.goalId)
            else navigate(started.href)
          }}
        />
      )}
      <ShortcutsDialog open={shortcutsOpen} onClose={() => setShortcutsOpen(false)} groups={shortcutGroups()} />
    </>
  )
}

function shortcutGroups() {
  return [
    {
      title: 'Orchestrator',
      items: [
        { keys: formatHotkey({ key: 'k', mod: true, handler: () => {} }), description: 'Focus the board search' },
        { keys: formatHotkey({ key: '/', mod: true, handler: () => {} }), description: 'Keyboard shortcuts' },
        { keys: ['Esc'], description: 'Close the sheet, otherwise clear the agent selection' },
        { keys: ['j', 'k', '↑', '↓'], description: 'Move between cards, while focus is in the board' },
        { keys: ['Home', 'End'], description: 'Jump to the first or last card' },
        { keys: ['↑', '↓', 'j', 'k'], description: 'Previous or next goal, while the sheet is open' },
        { keys: ['Enter', 'Space'], description: 'Open the focused card' },
      ],
    },
  ]
}
