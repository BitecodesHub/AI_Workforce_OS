// @find: plus menu, composer + button, attach menu, upload files, add from knowledge, attach a repository, use a skill, schedule this, choose an agent, popover, keyboard menu
// @what: The composer's single "+" button and the popover it opens: upload files, ground in knowledge, attach a repository, use a skill, schedule the message, choose an agent.
// @flow: Used by Composer; pickers in ContextPickers; state in composerContext; files go to useAttachments.
import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent, ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { ACCEPT } from '../../lib/attachments'
import type { Agent } from '../../lib/queries'
import type { ComposerContext } from './composerContext'
import { AgentPicker, KnowledgePicker, RepoPicker, SkillPicker } from './ContextPickers'
import './attachments.css'
import './plusMenu.css'

/*
 * One "+" replaces the paperclip. It opens upward from the composer (there is rarely room below),
 * in a layer of its own so the chat panel's scrolling cannot clip it, and every row can be reached
 * from the keyboard: arrows move, Enter or Space chooses, Right opens a list, Left or Escape goes
 * back, and Escape at the top closes it and returns focus to "+". Typing a letter at the top moves
 * to the first row that starts with it. The long lists (documents, repositories, skills, agents)
 * open as a view inside the same popover with a search box.
 *
 * While the box answers a question only "Choose an agent" stays available: an answer is text, and
 * mentioning another agent is how it becomes a new request.
 */

/** The views the popover can show: the root list of actions, or one of four searchable pickers. */
export type PlusView = 'root' | 'knowledge' | 'repo' | 'skill' | 'agent'

/** Spacing constants for plusMenuPosition: GAP between the trigger and the panel, MARGIN kept clear of the window edges. */
const GAP = 6
const MARGIN = 8

// @find: plus menu position, open upward
/** Above the trigger when it fits (or fits better than below), clamped inside the window. */
export function plusMenuPosition(
  trigger: { top: number; bottom: number; left: number },
  panel: { width: number; height: number },
  viewport: { width: number; height: number },
): { top: number; left: number; placement: 'above' | 'below' } {
  const roomAbove = trigger.top - GAP - MARGIN
  const roomBelow = viewport.height - trigger.bottom - GAP - MARGIN
  const placement = panel.height <= roomAbove || roomAbove >= roomBelow ? 'above' : 'below'
  let top = placement === 'above' ? trigger.top - GAP - panel.height : trigger.bottom + GAP
  top = Math.max(MARGIN, Math.min(top, viewport.height - MARGIN - panel.height))
  const left = Math.max(MARGIN, Math.min(trigger.left, viewport.width - MARGIN - panel.width))
  return { top, left, placement }
}

/** Shared 16 pixel SVG frame for the row icons. */
function Glyph({ children }: { children: ReactNode }) {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      {children}
    </svg>
  )
}

/** Icon for each root row, keyed by row id. */
const ICONS: Record<string, ReactNode> = {
  upload: (
    <Glyph>
      <path d="M8 10.5V2.5M4.5 6 8 2.5 11.5 6M2.5 10.5v2a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1v-2" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
    </Glyph>
  ),
  knowledge: (
    <Glyph>
      <path d="M3 2.5h7l3 3v8H3z M10 2.5v3h3 M5.5 8.5h5M5.5 11h3.5" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" strokeLinecap="round" />
    </Glyph>
  ),
  repo: (
    <Glyph>
      <circle cx="4.5" cy="3.5" r="1.5" stroke="currentColor" strokeWidth="1.3" />
      <circle cx="4.5" cy="12.5" r="1.5" stroke="currentColor" strokeWidth="1.3" />
      <circle cx="11.5" cy="5.5" r="1.5" stroke="currentColor" strokeWidth="1.3" />
      <path d="M4.5 5v6M11.5 7c0 2.5-3 2.5-6 4" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </Glyph>
  ),
  skill: (
    <Glyph>
      <path d="M9 1.8 3.5 9h4l-1 5.2L12.5 7h-4z" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
    </Glyph>
  ),
  schedule: (
    <Glyph>
      <circle cx="8" cy="8" r="5.8" stroke="currentColor" strokeWidth="1.3" />
      <path d="M8 4.8V8l2.2 1.6" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </Glyph>
  ),
  agent: (
    <Glyph>
      <circle cx="8" cy="5.5" r="2.6" stroke="currentColor" strokeWidth="1.3" />
      <path d="M3 13.5c.6-2.6 2.6-4 5-4s4.4 1.4 5 4" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </Glyph>
  ),
}

/** Arrow shown on rows that open a picker. */
function Chevron() {
  return (
    <svg className="plus-row-chevron" width="10" height="10" viewBox="0 0 10 10" fill="none" aria-hidden="true">
      <path d="M3.5 2 6.5 5l-3 3" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

/** A row of the root list: id, label, helper note, icon, the picker it opens (absent for actions that happen at once) and whether it is disabled. */
type RootRow = {
  id: string
  label: string
  note: string
  icon: ReactNode
  opens?: PlusView
  disabled?: boolean
}

// @find: PlusMenu, plus menu, composer + button, attach menu
/**
 * The composer's "+" button and the popover it opens.
 * Root rows: Upload files (clicks a hidden file input, so the browser picker opens inside the user gesture,
 * and passes the chosen files to onFiles), Add from Knowledge, Attach a repository and Use a skill (each opens
 * a picker view in the same popover and edits the ComposerContext through onContextChange), Schedule this
 * (closes the menu and calls onSchedule) and Choose an agent (calls onMention). While the box answers a
 * question (attachDisabled) only the agent row remains available; Schedule this is also disabled without
 * the schedule permission (canSchedule).
 * Rendering: the panel is portalled into the nearest dialog or document.body and positioned with
 * plusMenuPosition, which prefers opening upward and is recomputed on resize, scroll and when the panel
 * height changes (ResizeObserver). A pointer down outside closes it.
 * Keyboard: arrows, Home and End move between rows, Right opens a picker, a letter jumps to the next row
 * starting with it, Escape goes back or closes, and Tab at the root closes and returns focus to the trigger.
 * Inside a picker Tab is trapped and wraps within the panel. Focus lands on the first enabled row, the
 * remembered row, or the picker's search box. After something is chosen, onChosen moves focus to the text
 * box; without it focus returns to the trigger. The trigger label reports how many items are attached.
 */
export function PlusMenu({
  context,
  onContextChange,
  onFiles,
  agents,
  mentionedIds,
  onMention,
  onSchedule,
  attachDisabled = false,
  canSchedule = true,
  onChosen,
}: {
  context: ComposerContext
  onContextChange: (next: ComposerContext) => void
  onFiles: (files: File[]) => void
  /** In the order to offer them, General Employee first. */
  agents: Agent[]
  mentionedIds: string[]
  onMention: (agent: Agent) => void
  onSchedule: () => void
  /** The box answers a question: only choosing an agent is offered. */
  attachDisabled?: boolean
  /** Whether this person may create schedules (task:create). */
  canSchedule?: boolean
  /** Where focus goes once something is chosen (the text box), so the person can keep typing. */
  onChosen?: () => void
}) {
  const [open, setOpen] = useState(false)
  const [view, setView] = useState<PlusView>('root')
  const [host, setHost] = useState<HTMLElement | null>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const lastRootRow = useRef<string | null>(null)
  const panelId = useId()

  const close = (focusTrigger: boolean) => {
    setOpen(false)
    setView('root')
    if (focusTrigger) triggerRef.current?.focus()
  }

  const openMenu = () => {
    setHost(triggerRef.current?.closest('dialog') ?? document.body)
    setView('root')
    setOpen(true)
  }

  /** Closes after a choice and hands focus to the text box, or back to "+" without one. */
  const finish = () => {
    if (onChosen) {
      close(false)
      onChosen()
    } else close(true)
  }

  const goBack = () => {
    setView('root')
  }

  const notWhileAnswering = 'Not while answering a question'
  const rows: RootRow[] = [
    {
      id: 'upload',
      label: 'Upload files',
      note: attachDisabled ? notWhileAnswering : 'Documents and images, up to 10 files',
      icon: ICONS.upload,
      disabled: attachDisabled,
    },
    {
      id: 'knowledge',
      label: 'Add from Knowledge',
      note: attachDisabled ? notWhileAnswering : 'Ground this message in workspace documents',
      icon: ICONS.knowledge,
      opens: 'knowledge',
      disabled: attachDisabled,
    },
    {
      id: 'repo',
      label: 'Attach a repository',
      note: attachDisabled ? notWhileAnswering : 'Work on a GitHub repository, branch or folder',
      icon: ICONS.repo,
      opens: 'repo',
      disabled: attachDisabled,
    },
    {
      id: 'skill',
      label: 'Use a skill',
      note: attachDisabled ? notWhileAnswering : 'Apply a saved playbook to this message',
      icon: ICONS.skill,
      opens: 'skill',
      disabled: attachDisabled,
    },
    {
      id: 'schedule',
      label: 'Schedule this',
      note: attachDisabled
        ? notWhileAnswering
        : canSchedule
          ? 'Run this message on a timetable'
          : 'Your role cannot create schedules',
      icon: ICONS.schedule,
      disabled: attachDisabled || !canSchedule,
    },
    {
      id: 'agent',
      label: 'Choose an agent',
      note: 'Address someone in particular, or type @',
      icon: ICONS.agent,
      opens: 'agent',
    },
  ]

  // Focus: the first usable row (or the row a list was opened from) at the top; the list's own
  // search box inside a list.
  useEffect(() => {
    if (!open) return
    const panel = panelRef.current
    if (!panel) return
    if (view === 'root') {
      const remembered = lastRootRow.current
        ? panel.querySelector<HTMLButtonElement>(`[data-row="${lastRootRow.current}"]:not(:disabled)`)
        : null
      ;(remembered ?? panel.querySelector<HTMLButtonElement>('[role="menuitem"]:not(:disabled)'))?.focus()
    } else {
      panel.querySelector<HTMLElement>('[data-autofocus]')?.focus()
    }
  }, [open, view])

  useEffect(() => {
    if (!open) return
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target
      if (!(target instanceof Node)) return
      if (panelRef.current?.contains(target) || triggerRef.current?.contains(target)) return
      // A dialog opened from the menu (none today) or a native select's list is not "outside".
      setOpen(false)
      setView('root')
    }
    document.addEventListener('pointerdown', onPointerDown)
    return () => document.removeEventListener('pointerdown', onPointerDown)
  }, [open])

  useLayoutEffect(() => {
    if (!open) return
    const place = () => {
      const trigger = triggerRef.current
      const panel = panelRef.current
      if (!trigger || !panel) return
      const position = plusMenuPosition(
        trigger.getBoundingClientRect(),
        { width: panel.offsetWidth, height: panel.offsetHeight },
        { width: window.innerWidth, height: window.innerHeight },
      )
      panel.style.top = `${position.top}px`
      panel.style.left = `${position.left}px`
      panel.dataset.placement = position.placement
    }
    place()
    // A list loading in changes the panel's height; keep it anchored as it does.
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(place)
    if (observer && panelRef.current) observer.observe(panelRef.current)
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      observer?.disconnect()
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [open, view])

  const chooseRow = (row: RootRow) => {
    if (row.disabled) return
    lastRootRow.current = row.id
    if (row.opens) {
      setView(row.opens)
    } else if (row.id === 'upload') {
      // Clicked while the gesture is still live, so the browser opens its picker.
      fileRef.current?.click()
      close(false)
    } else if (row.id === 'schedule') {
      close(false)
      onSchedule()
    }
  }

  const onRootKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    const items = Array.from(panelRef.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ?? [])
    const current = items.indexOf(document.activeElement as HTMLButtonElement)
    const move = (index: number) => items[(index + items.length) % items.length]?.focus()
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      move(current + 1)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      move(current < 0 ? items.length - 1 : current - 1)
    } else if (event.key === 'Home') {
      event.preventDefault()
      move(0)
    } else if (event.key === 'End') {
      event.preventDefault()
      move(items.length - 1)
    } else if (event.key === 'ArrowRight') {
      const row = rows.find((r) => r.id === items[current]?.dataset.row)
      if (row?.opens) {
        event.preventDefault()
        chooseRow(row)
      }
    } else if (event.key.length === 1 && /\S/.test(event.key) && !event.metaKey && !event.ctrlKey && !event.altKey) {
      // Type-ahead: the next row after the focused one whose label starts with the letter.
      const letter = event.key.toLowerCase()
      const ordered = [...items.slice(current + 1), ...items.slice(0, current + 1)]
      const hit = ordered.find((item) => item.dataset.label?.toLowerCase().startsWith(letter))
      if (hit) {
        event.preventDefault()
        hit.focus()
      }
    }
  }

  const onPanelKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      if (view === 'root') close(true)
      else goBack()
      return
    }
    if (event.key === 'Tab') {
      if (view === 'root') {
        // The panel sits at the end of the document; Tab hands focus back to "+" rather than
        // jumping somewhere unrelated.
        event.preventDefault()
        close(true)
        return
      }
      // Inside a list (a search box, a branch, a path, Attach) Tab moves between its controls and
      // wraps, so focus never leaves the open panel for the page behind it.
      const focusable = Array.from(
        panelRef.current?.querySelectorAll<HTMLElement>(
          'button:not(:disabled):not([tabindex="-1"]), input:not(:disabled):not([tabindex="-1"]), select:not(:disabled), a[href]',
        ) ?? [],
      )
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last?.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first?.focus()
      }
      return
    }
    if (view === 'root') onRootKeyDown(event)
  }

  const attachedCount =
    context.knowledge.length + (context.repo ? 1 : 0) + (context.skill ? 1 : 0)

  const panel = (
    <div
      id={panelId}
      ref={panelRef}
      className="plus-menu"
      style={{ position: 'fixed', top: 0, left: 0 }}
      onKeyDown={onPanelKeyDown}
      data-view={view}
    >
      {view === 'root' ? (
        <div role="menu" aria-label="Add to message" className="plus-menu-root">
          {rows.map((row, index) => (
            <div key={row.id}>
              {index === rows.length - 1 && <div className="plus-menu-separator" role="separator" />}
              <button
                type="button"
                role="menuitem"
                className="plus-row"
                data-row={row.id}
                data-label={row.label}
                disabled={row.disabled}
                aria-haspopup={row.opens ? 'true' : undefined}
                onClick={() => chooseRow(row)}
              >
                <span className="plus-row-icon">{row.icon}</span>
                <span className="plus-row-text">
                  <span className="plus-row-label">{row.label}</span>
                  <span className="plus-row-note">{row.note}</span>
                </span>
                {row.opens && <Chevron />}
              </button>
            </div>
          ))}
        </div>
      ) : view === 'knowledge' ? (
        <KnowledgePicker context={context} onChange={onContextChange} onBack={goBack} onDone={finish} />
      ) : view === 'repo' ? (
        <RepoPicker
          current={context.repo}
          onBack={goBack}
          onAttach={(repo) => {
            onContextChange({ ...context, repo })
            finish()
          }}
        />
      ) : view === 'skill' ? (
        <SkillPicker
          current={context.skill}
          onBack={goBack}
          onChoose={(skill) => {
            onContextChange({ ...context, skill: context.skill?.id === skill.id ? null : skill })
            finish()
          }}
        />
      ) : (
        <AgentPicker
          agents={agents}
          mentionedIds={mentionedIds}
          onBack={goBack}
          onChoose={(agent) => {
            onMention(agent)
            finish()
          }}
        />
      )}
    </div>
  )

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className="icon-button plus-trigger"
        aria-label={attachedCount > 0 ? `Add to message, ${attachedCount} attached` : 'Add to message'}
        title="Add files, knowledge, a repository, a skill or a schedule"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        data-active={open || undefined}
        onClick={() => (open ? close(false) : openMenu())}
      >
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      </button>
      <input
        ref={fileRef}
        type="file"
        multiple
        accept={ACCEPT}
        className="visually-hidden"
        tabIndex={-1}
        aria-hidden="true"
        disabled={attachDisabled}
        data-testid="chat-attach-input"
        onChange={(event) => {
          const files = Array.from(event.currentTarget.files ?? [])
          event.currentTarget.value = ''
          if (files.length > 0) onFiles(files)
        }}
      />
      {open && createPortal(panel, host ?? document.body)}
    </>
  )
}
