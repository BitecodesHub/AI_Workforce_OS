import { useCallback, useMemo, useRef, useState } from 'react'
import type { ComponentProps } from 'react'
import { Button, PageHeader } from '../components/ui'
import { Collapsible, useCollapsed } from '../components/ui/Collapsible'
import { MenuButton } from '../components/ui/Menu'
import type { MenuEntry } from '../components/ui/Menu'
import { QueryState } from '../components/ui/QueryState'
import { ShortcutsDialog } from '../components/ui/ShortcutsDialog'
import { TaskDialog } from '../components/ui/TaskDialog'
import { AgentsStrip, useAgentBulk } from '../components/orchestrator/AgentsStrip'
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
import { withoutFinalStop } from '../components/run/traceModel'
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
 * The top of the page answers "what needs me and what is running": a one-line summary of filters
 * and the inbox of what is waiting on somebody. Then the board of every goal, and the workforce
 * (agents, map and timeline) folded behind one heading. Everything reads from one poll, so nothing
 * on the page ever disagrees with anything else on it.
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
  // Opened with Live updates saved as off: hold the first board that arrives, as switching it off
  // during the visit would, rather than letting the polls (which carry on for Needs you) move it.
  if (!live && frozen === null && latest) setFrozen(latest)
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
  // does - compared against the previous board rather than tracked with an effect. This keeps a
  // watermark of its own: the live one stands still while updates are paused, so comparing with
  // it would set state on every render, and React gives up with "Too many re-renders". The inbox
  // reads the latest board even while the rest is paused, so its announcements carry on too.
  const [lastNeedsBoard, setLastNeedsBoard] = useState<Board | null>(null)
  const [lastNeedsIds, setLastNeedsIds] = useState<ReadonlySet<string>>(new Set())
  if (latest && latest !== lastNeedsBoard) {
    const everyone = buildNeedsYou(latest, { me, scope: 'everyone' })
    const ids = new Set(everyone.map((item) => `${item.kind}-${item.id}`))
    if (lastNeedsIds.size > 0) {
      const fresh = everyone.find(
        (item): item is Extract<typeof item, { kind: 'question' | 'approval' }> =>
          (item.kind === 'question' || item.kind === 'approval') && !lastNeedsIds.has(`${item.kind}-${item.id}`),
      )
      if (fresh) {
        // The summary is already in words (needsYou.ts); only its closing full stop is trimmed, so the
        // announcement does not end in two.
        setLiveMessage(
          fresh.kind === 'question' ? `New question from ${fresh.title}.` : `New approval waiting: ${withoutFinalStop(fresh.summary)}.`,
        )
      }
    }
    setLastNeedsIds(ids)
    setLastNeedsBoard(latest)
  }

  const pendingChanges = frozen && latest ? countChanged(frozen, latest) : 0

  const questionCount = board.questions.filter((question) => question.status === 'pending').length
  const approvalCount = board.approvals.length
  const forMeCount =
    board.questions.filter((question) => question.status === 'pending' && me != null && question.requestedBy === me).length +
    board.approvals.filter((approval) => approval.canDecide).length
  useDocumentTitle(forMeCount > 0 ? `(${forMeCount}) Orchestrator` : 'Orchestrator')

  const setWindowToken = (token: string) => navigate(withParams(search, { window: token === '2h' ? null : token }), { replace: true, scroll: false })
  const setSelectedAgentId = useCallback(
    (agentId: string | null) => navigate(withParams(search, { agent: agentId }), { replace: true, scroll: false }),
    [navigate, search],
  )
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

  const openGoal = useCallback(
    (id: string, focusNext?: 'question' | 'approval') =>
      navigate(withParams(search, { goal: id, run: null, focus: focusNext ?? null }), { scroll: false }),
    [navigate, search],
  )
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

  // Folded by default: the map and the timeline explain the workforce, they are not what needs a
  // person. The choice is remembered, like the page's other folded sections.
  const [workforceOpen, setWorkforceOpen] = useCollapsed('orc.sections.workforce', false)
  const [timelineOpen, setTimelineOpen] = useCollapsed('orc.sections.timeline', false)
  const agentBulk = useAgentBulk(board)
  const busyAgents = board.agents.filter((agent) => BUSY_NODE.has(nodeStatus(agent))).length
  const workforceSummary = `${formatCount(board.agents.length)} agents · ${busyAgents > 0 ? `${formatCount(busyAgents)} busy` : 'all idle'}`

  const nothingActive = board.stats.running + board.stats.queued + board.stats.waitingApproval + board.stats.waitingInput === 0

  // Everything the header used to spell out as buttons, folded into one menu beside New goal.
  // Stop everything stays reachable, last and in red, and still asks before it acts.
  const headerMenu: MenuEntry[] = [
    { id: 'live', label: 'Live updates', note: live ? 'On' : 'Paused', checked: live, onSelect: () => onSetLive(!live) },
    { id: 'schedules', label: 'Schedules', onSelect: () => navigate('/schedules') },
    { id: 'shortcuts', label: 'Keyboard shortcuts', onSelect: () => setShortcutsOpen(true) },
    ...(canStopAll
      ? ([
          { id: 'sep', separator: true },
          {
            id: 'stop',
            label: 'Stop everything',
            ...(nothingActive ? { note: 'Nothing is running' } : {}),
            danger: true,
            disabled: nothingActive,
            onSelect: () => setStopOpen(true),
          },
        ] satisfies MenuEntry[])
      : []),
  ]

  // The map is the largest drawing on the page. Built once per board (and per selection), so a
  // status message, an opened dialog or a filter elsewhere on the page does not redraw it.
  const flowMap = useMemo(
    () => <FlowMap board={board} selectedAgentId={selectedAgentId} onSelectAgent={setSelectedAgentId} onOpenGoal={openGoal} />,
    [board, selectedAgentId, setSelectedAgentId, openGoal],
  )

  return (
    <>
      <PageHeader
        eyebrow="Live coordination"
        title="Orchestrator"
        description="Every agent at work right now, and what needs you."
        action={
          <>
            {canCreate && <Button onClick={() => setTaskDialogOpen(true)}>New goal</Button>}
            <MenuButton label="More actions" trigger="icon" icon={<MoreIcon />} items={headerMenu} align="end" className="orc-header-menu" />
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
          onToggleFailedToday={() => toggleTodayTile('failed')}
        />

        <NeedsYouInbox board={latest ?? board} onOpenGoal={openGoal} onOpenRun={openRun} />

        <section aria-labelledby="orc-goals-heading" ref={goalsSectionRef} className="orc-section">
          <h2 id="orc-goals-heading" className="orc-section-heading orc-section-title">
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
            doneToday={{
              count: board.stats.goalsCompletedToday,
              pressed: isToday && status.has('finished'),
              onToggle: () => toggleTodayTile('finished'),
            }}
          />
        </section>

        <Collapsible
          title="Workforce"
          summary={workforceSummary}
          open={workforceOpen}
          onToggle={setWorkforceOpen}
          className="orc-collapsible orc-workforce-section"
          actions={agentBulk.actions}
        >
          <div className="orc-workforce">
            <AgentsStrip board={board} onOpenGoal={openGoal} bulk={agentBulk} />
            <div className="orc-panel orc-panel-map">
              <div className="orc-panel-head">
                <h3 className="orc-panel-title">Live map</h3>
                <span className="caption muted">Select an agent to show only its goals.</span>
              </div>
              {flowMap}
            </div>
            <Collapsible
              title="Timeline"
              headingLevel="h3"
              open={timelineOpen}
              onToggle={setTimelineOpen}
              className="orc-collapsible orc-collapsible-nested"
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
                <TimelineLanes board={board} windowMinutes={board.windowMinutes} timezone={board.timezone} onOpenGoal={openGoal} />
              </div>
            </Collapsible>
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
      {agentBulk.dialogs}
      <ShortcutsDialog open={shortcutsOpen} onClose={() => setShortcutsOpen(false)} groups={shortcutGroups()} />
    </>
  )
}

/**
 * The timeline with the one-second clock it needs for the bars still running. The clock lives
 * here rather than in the page, so each tick redraws only the lanes, not the board, the map and
 * the inbox beside them.
 */
function TimelineLanes(props: Omit<ComponentProps<typeof Swimlanes>, 'now'>) {
  const now = useNow(1_000)
  return <Swimlanes {...props} now={now} />
}

function MoreIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="3.5" cy="8" r="1.25" fill="currentColor" />
      <circle cx="8" cy="8" r="1.25" fill="currentColor" />
      <circle cx="12.5" cy="8" r="1.25" fill="currentColor" />
    </svg>
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
