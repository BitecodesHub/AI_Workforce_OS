// @find: context pickers, knowledge picker, document picker, repository picker, branch picker, folder picker, skill picker, agent picker, searchable list, plus menu lists
// @what: The searchable lists inside the composer's "+" popover: workspace documents, GitHub repositories (branch and path), skills and agents.
// @flow: Used by PlusMenu; reads useSources/useSourceDocuments, chatContextQueries, skillQueries.
import { useId, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent, ReactNode } from 'react'
import { Spinner } from '../ui'
import { AgentAvatar } from '../ui/AgentAvatar'
import { describeApiError } from '../../lib/api'
import { useGitHubBranches, useGitHubRepos, useGitHubTree } from '../../lib/chatContextQueries'
import type { GitHubRepo } from '../../lib/chatContextQueries'
import { CATEGORY_LABEL } from '../../lib/labels'
import { useSourceDocuments, useSources } from '../../lib/queries'
import type { Agent, Source } from '../../lib/queries'
import { useRouter } from '../../lib/router'
import { useSkills } from '../../lib/skillQueries'
import {
  MAX_KNOWLEDGE,
  isKnowledgeChosen,
  normalisePath,
  repoLabel,
  toggleKnowledge,
} from './composerContext'
import type { ComposerContext, RepoChoice, SkillChoice } from './composerContext'

/* ---- Shared pieces ------------------------------------------------------------------------------ */

/**
 * Focuses a list's first control as it mounts. A list can appear after its data loads, or one
 * level down without the popover's view changing, so focusing on the view change alone missed it.
 */
const focusOnMount = (element: HTMLElement | null) => {
  element?.focus()
}

function BackIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
      <path d="M7.5 2.5 4 6l3.5 3.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function CheckIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
      <path d="M2.5 6.2 5 8.5l4.5-5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function PickerHeader({ title, onBack, action }: { title: string; onBack: () => void; action?: ReactNode }) {
  return (
    <div className="plus-picker-header">
      <button type="button" className="plus-back" onClick={onBack} aria-label="Back to the menu" title="Back">
        <BackIcon />
      </button>
      <span className="plus-picker-title">{title}</span>
      {action}
    </div>
  )
}

export type SearchItem = {
  id: string
  label: string
  note?: string | undefined
  icon?: ReactNode
  /** Shown with a tick: in the message already. */
  chosen?: boolean
  disabled?: boolean
  /** A list one level down, opened with its arrow button or the Right key. */
  drill?: { label: string; onOpen: () => void } | undefined
}

// @find: searchable list, listbox with search, active descendant
/**
 * A search box over a list. The box keeps focus throughout: Up and Down move the highlighted row,
 * Enter chooses it, Right opens its list when it has one. Rows are options of a listbox the box
 * controls, so a screen reader hears the highlighted row as it moves.
 */
export function SearchList({
  label,
  placeholder,
  items,
  query,
  onQuery,
  onChoose,
  empty,
  multiselect = false,
}: {
  label: string
  placeholder: string
  items: SearchItem[]
  query: string
  onQuery: (query: string) => void
  onChoose: (item: SearchItem) => void
  empty: ReactNode
  multiselect?: boolean
}) {
  const listId = useId()
  const [active, setActive] = useState(0)
  const enabled = items.filter((item) => !item.disabled)
  const activeIndex = Math.min(active, Math.max(0, enabled.length - 1))
  const activeItem = enabled[activeIndex]
  const optionId = (item: SearchItem) => `${listId}-${item.id.replace(/[^A-Za-z0-9_-]/g, '_')}`

  const onKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown' && enabled.length > 0) {
      event.preventDefault()
      setActive((activeIndex + 1) % enabled.length)
    } else if (event.key === 'ArrowUp' && enabled.length > 0) {
      event.preventDefault()
      setActive((activeIndex - 1 + enabled.length) % enabled.length)
    } else if (event.key === 'Enter' && activeItem) {
      event.preventDefault()
      onChoose(activeItem)
    } else if (event.key === 'ArrowRight' && activeItem?.drill && event.currentTarget.selectionStart === query.length) {
      event.preventDefault()
      activeItem.drill.onOpen()
    }
  }

  return (
    <div className="plus-search">
      <input
        type="search"
        className="plus-search-input"
        aria-label={label}
        placeholder={placeholder}
        value={query}
        data-autofocus
        ref={focusOnMount}
        role="combobox"
        aria-expanded="true"
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={activeItem ? optionId(activeItem) : undefined}
        onChange={(event) => {
          onQuery(event.target.value)
          setActive(0)
        }}
        onKeyDown={onKeyDown}
      />
      <ul className="plus-list" role="listbox" id={listId} aria-label={label} aria-multiselectable={multiselect || undefined}>
        {items.length === 0 ? (
          <li className="plus-list-empty caption muted" role="presentation">
            {empty}
          </li>
        ) : (
          items.map((item) => {
            const isActive = activeItem?.id === item.id
            return (
              <li
                key={item.id}
                id={optionId(item)}
                role="option"
                aria-selected={multiselect ? Boolean(item.chosen) : isActive}
                aria-disabled={item.disabled || undefined}
                className="plus-option"
                data-active={isActive || undefined}
                data-chosen={item.chosen || undefined}
                onMouseDown={(event) => event.preventDefault()}
                onMouseEnter={() => {
                  const index = enabled.indexOf(item)
                  if (index >= 0) setActive(index)
                }}
                onClick={() => !item.disabled && onChoose(item)}
              >
                {item.icon && <span className="plus-option-icon">{item.icon}</span>}
                <span className="plus-option-text">
                  <span className="plus-option-label">{item.label}</span>
                  {item.note && <span className="plus-option-note">{item.note}</span>}
                </span>
                {item.chosen && (
                  <span className="plus-option-check" aria-hidden="true">
                    <CheckIcon />
                  </span>
                )}
                {item.drill && (
                  <button
                    type="button"
                    className="plus-option-drill"
                    tabIndex={-1}
                    aria-label={item.drill.label}
                    title={item.drill.label}
                    onClick={(event) => {
                      event.stopPropagation()
                      item.drill?.onOpen()
                    }}
                  >
                    <svg width="10" height="10" viewBox="0 0 10 10" fill="none" aria-hidden="true">
                      <path d="M3.5 2 6.5 5l-3 3" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </button>
                )}
              </li>
            )
          })
        )}
      </ul>
    </div>
  )
}

function Loading({ label }: { label: string }) {
  return (
    <p className="plus-state caption muted" role="status">
      <Spinner size={13} /> {label}
    </p>
  )
}

const matches = (query: string, ...fields: (string | null | undefined)[]) => {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return fields.some((field) => field?.toLowerCase().includes(q))
}

const plural = (count: number, one: string, many: string) => `${count.toLocaleString('en-AU')} ${count === 1 ? one : many}`

/* ---- Knowledge ---------------------------------------------------------------------------------- */

function DocumentsList({
  source,
  context,
  onChange,
  onBack,
}: {
  source: Source
  context: ComposerContext
  onChange: (next: ComposerContext) => void
  onBack: () => void
}) {
  const [query, setQuery] = useState('')
  const documents = useSourceDocuments(source.id)
  const wholeChosen = isKnowledgeChosen(context, { sourceId: source.id, documentId: null })
  const full = context.knowledge.length >= MAX_KNOWLEDGE

  const items: SearchItem[] = [
    {
      id: `whole-${source.id}`,
      label: `All of ${source.name}`,
      note: plural(source.documentCount, 'document', 'documents'),
      chosen: wholeChosen,
      disabled: full && !wholeChosen,
    },
    ...(documents.data ?? [])
      .filter((doc) => matches(query, doc.title))
      .map((doc) => {
        const chosen = wholeChosen || isKnowledgeChosen(context, { sourceId: source.id, documentId: doc.id })
        const searchable = doc.chunkCount > 0 && !doc.removedAtSource
        return {
          id: doc.id,
          label: doc.title,
          note: searchable ? plural(doc.chunkCount, 'passage', 'passages') : 'Not searchable yet',
          chosen,
          disabled: !searchable || wholeChosen || (full && !chosen),
        }
      }),
  ]

  return (
    <>
      <PickerHeader title={source.name} onBack={onBack} />
      {documents.isPending ? (
        <Loading label="Loading documents" />
      ) : documents.isError ? (
        <p className="plus-state caption" role="alert">
          {describeApiError(documents.error)}
        </p>
      ) : (
        <SearchList
          label={`Documents in ${source.name}`}
          placeholder="Search documents"
          items={items}
          query={query}
          onQuery={setQuery}
          multiselect
          empty={`No document matches "${query}"`}
          onChoose={(item) => {
            if (item.id === `whole-${source.id}`) {
              onChange(toggleKnowledge(context, { sourceId: source.id, documentId: null, title: source.name }))
            } else {
              onChange(toggleKnowledge(context, { sourceId: source.id, documentId: item.id, title: item.label }))
            }
          }}
        />
      )}
    </>
  )
}

// @find: KnowledgePicker, add from knowledge, choose documents to ground message
export function KnowledgePicker({
  context,
  onChange,
  onBack,
  onDone,
}: {
  context: ComposerContext
  onChange: (next: ComposerContext) => void
  onBack: () => void
  onDone: () => void
}) {
  const [query, setQuery] = useState('')
  const [openSource, setOpenSource] = useState<Source | null>(null)
  const sources = useSources()
  const full = context.knowledge.length >= MAX_KNOWLEDGE

  if (openSource) {
    return <DocumentsList source={openSource} context={context} onChange={onChange} onBack={() => setOpenSource(null)} />
  }

  // An agent's own documents are searched only by that agent, so they are not offered here.
  const shared = (sources.data ?? []).filter((source) => !source.agentId)
  const items: SearchItem[] = shared
    .filter((source) => matches(query, source.name))
    .map((source) => {
      const chosen = isKnowledgeChosen(context, { sourceId: source.id, documentId: null })
      const some = context.knowledge.filter((k) => k.sourceId === source.id && k.documentId !== null).length
      return {
        id: source.id,
        label: source.name,
        note: some > 0 ? `${plural(some, 'document', 'documents')} chosen` : plural(source.documentCount, 'document', 'documents'),
        chosen,
        disabled: source.documentCount === 0 || (full && !chosen),
        drill: source.documentCount > 0 ? { label: `Choose documents in ${source.name}`, onOpen: () => setOpenSource(source) } : undefined,
      }
    })

  const count = context.knowledge.length
  return (
    <>
      <PickerHeader
        title="Add from Knowledge"
        onBack={onBack}
        action={
          count > 0 ? (
            <button type="button" className="plus-done" onClick={onDone}>
              Done
            </button>
          ) : undefined
        }
      />
      {sources.isPending ? (
        <Loading label="Loading sources" />
      ) : sources.isError ? (
        <p className="plus-state caption" role="alert">
          {describeApiError(sources.error)}
        </p>
      ) : shared.length === 0 ? (
        <p className="plus-state caption muted">No workspace documents yet. Add some on the Knowledge page.</p>
      ) : (
        <SearchList
          label="Workspace sources"
          placeholder="Search sources"
          items={items}
          query={query}
          onQuery={setQuery}
          multiselect
          empty={`No source matches "${query}"`}
          onChoose={(item) => {
            const source = shared.find((s) => s.id === item.id)
            if (source) onChange(toggleKnowledge(context, { sourceId: source.id, documentId: null, title: source.name }))
          }}
        />
      )}
      <p className="plus-footnote caption muted">
        {count > 0
          ? `${plural(count, 'item', 'items')} chosen. The agent searches these first.`
          : 'Choose a whole source, or open it to pick single documents.'}
      </p>
    </>
  )
}

/* ---- Repository --------------------------------------------------------------------------------- */

function RepoDetail({
  repo,
  current,
  onBack,
  onAttach,
}: {
  repo: GitHubRepo
  current: RepoChoice | null
  onBack: () => void
  onAttach: (repo: RepoChoice) => void
}) {
  const sameRepo = current && current.owner === repo.owner && current.name === repo.name ? current : null
  const [branch, setBranch] = useState<string>(sameRepo?.branch ?? repo.defaultBranch ?? '')
  const [path, setPath] = useState<string>(sameRepo?.path ?? '')
  const [filter, setFilter] = useState('')
  // The folder being browsed; `path` is what gets attached, which may be a file inside it.
  const [folder, setFolder] = useState<string | null>(normalisePath(sameRepo?.path ?? null))
  const branches = useGitHubBranches(repo.fullName)
  const browsePath = folder
  const tree = useGitHubTree(repo.fullName, branch || null, browsePath)
  const branchId = useId()
  const pathId = useId()

  const branchOptions = Array.from(new Set([repo.defaultBranch, ...(branches.data?.branches ?? [])].filter(Boolean)))
  // Everything directly in the folder (the server lists it in full), narrowed by the filter box.
  const entries = (tree.data?.entries ?? []).filter((entry) => matches(filter, entry.path.split('/').pop()))
  const browse = (next: string) => {
    setPath(next)
    setFolder(normalisePath(next))
    setFilter('')
  }
  const parent = browsePath && browsePath.includes('/') ? browsePath.slice(0, browsePath.lastIndexOf('/')) : null
  const choice: RepoChoice = {
    owner: repo.owner,
    name: repo.name,
    branch: branch || null,
    path: normalisePath(path),
  }

  return (
    <>
      <PickerHeader title={repo.fullName} onBack={onBack} />
      <div className="plus-form">
        <label className="plus-field" htmlFor={branchId}>
          <span className="caption">Branch</span>
          <select
            id={branchId}
            className="plus-select"
            value={branch}
            data-autofocus
            ref={focusOnMount}
            onChange={(event) => setBranch(event.target.value)}
          >
            {branchOptions.map((name) => (
              <option key={name} value={name}>
                {name === repo.defaultBranch ? `${name} (default)` : name}
              </option>
            ))}
          </select>
        </label>
        <label className="plus-field" htmlFor={pathId}>
          <span className="caption">
            Folder or file <span className="muted">(optional)</span>
          </span>
          <input
            id={pathId}
            className="plus-search-input"
            value={path}
            placeholder="src/app"
            onChange={(event) => setPath(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                onAttach(choice)
              }
            }}
          />
        </label>
        {tree.data?.available && (
          <>
            <input
              type="search"
              className="plus-search-input"
              aria-label="Filter this folder"
              placeholder={`Filter ${browsePath ?? 'the top folder'}`}
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
            />
            <ul className="plus-tree" aria-label={`Contents of ${browsePath ?? repo.fullName}`}>
              {browsePath && (
                <li>
                  <button type="button" className="plus-tree-row" onClick={() => browse(parent ?? '')}>
                    .. <span className="muted">up one folder</span>
                  </button>
                </li>
              )}
              {entries.slice(0, 500).map((entry) => {
                // Paths come back in full; the row shows the last part. A folder opens; a file is chosen.
                const name = entry.path.split('/').pop() ?? entry.path
                const dir = entry.type === 'dir'
                return (
                  <li key={entry.path}>
                    <button
                      type="button"
                      className="plus-tree-row"
                      data-kind={dir ? 'dir' : 'file'}
                      aria-pressed={!dir ? normalisePath(path) === entry.path : undefined}
                      onClick={() => (dir ? browse(entry.path) : setPath(entry.path))}
                    >
                      {dir ? `${name}/` : name}
                    </button>
                  </li>
                )
              })}
              {entries.length === 0 && !tree.isFetching && (
                <li className="caption muted plus-tree-empty">{filter ? `Nothing here matches "${filter}"` : 'This folder is empty'}</li>
              )}
              {entries.length > 500 && (
                <li className="caption muted plus-tree-empty">Showing 500 of {entries.length}. Filter to find the rest.</li>
              )}
            </ul>
          </>
        )}
        {tree.isError && (
          <p className="caption plus-preview" role="alert">
            {describeApiError(tree.error)}
          </p>
        )}
        {tree.isFetching && <Loading label="Reading folders" />}
        <p className="caption muted plus-preview" aria-live="polite">
          Attaches <strong>{repoLabel(choice)}</strong>. Agents read it; nothing is changed on GitHub unless you ask.
        </p>
        <button type="button" className="button button-primary plus-attach" onClick={() => onAttach(choice)}>
          Attach repository
        </button>
      </div>
    </>
  )
}

// @find: RepoPicker, attach a repository, choose GitHub repo, not connected explains Connectors
export function RepoPicker({
  current,
  onBack,
  onAttach,
}: {
  current: RepoChoice | null
  onBack: () => void
  onAttach: (repo: RepoChoice) => void
}) {
  const [query, setQuery] = useState('')
  const [chosen, setChosen] = useState<GitHubRepo | null>(null)
  const repos = useGitHubRepos(true)
  const { navigate } = useRouter()

  if (chosen) {
    return <RepoDetail repo={chosen} current={current} onBack={() => setChosen(null)} onAttach={onAttach} />
  }

  const list = repos.data?.repos ?? []
  const items: SearchItem[] = list
    .filter((repo) => matches(query, repo.fullName, repo.description))
    .map((repo) => ({
      id: repo.fullName,
      label: repo.fullName,
      note: [repo.private ? 'Private' : 'Public', repo.description].filter(Boolean).join(' · '),
      chosen: current?.owner === repo.owner && current?.name === repo.name,
      drill: { label: `Choose a branch or folder of ${repo.fullName}`, onOpen: () => setChosen(repo) },
    }))

  return (
    <>
      <PickerHeader title="Attach a repository" onBack={onBack} />
      {repos.isPending ? (
        <Loading label="Loading repositories from GitHub" />
      ) : repos.isError ? (
        <p className="plus-state caption" role="alert">
          {describeApiError(repos.error)}
        </p>
      ) : !repos.data.connected ? (
        <div className="plus-state stack" style={{ gap: 'var(--space-3)' }}>
          <p className="caption" style={{ margin: 0 }}>
            {repos.data.message ?? 'GitHub is not connected.'} Agents can then read its code, branches and pull
            requests for the work you ask about.
          </p>
          <button
            type="button"
            className="button button-outline"
            data-autofocus
            ref={focusOnMount}
            onClick={() => navigate('/connectors')}
          >
            Open Connectors
          </button>
        </div>
      ) : (
        <SearchList
          label="GitHub repositories"
          placeholder="Search repositories"
          items={items}
          query={query}
          onQuery={setQuery}
          empty={list.length === 0 ? 'This GitHub account has no repositories.' : `No repository matches "${query}"`}
          onChoose={(item) => {
            const repo = list.find((r) => r.fullName === item.id)
            if (repo) setChosen(repo)
          }}
        />
      )}
    </>
  )
}

/* ---- Skills ------------------------------------------------------------------------------------- */

// @find: SkillPicker, use a skill, choose a playbook for this message
export function SkillPicker({
  current,
  onBack,
  onChoose,
}: {
  current: SkillChoice | null
  onBack: () => void
  onChoose: (skill: SkillChoice) => void
}) {
  const [query, setQuery] = useState('')
  const skills = useSkills()
  const { navigate } = useRouter()
  const list = skills.data ?? []
  const items: SearchItem[] = list
    .filter((skill) => matches(query, skill.name, skill.description))
    .map((skill) => ({
      id: skill.id,
      label: skill.name,
      note: [skill.description, skill.defaultAgentName ? `Goes to ${skill.defaultAgentName}` : null].filter(Boolean).join(' · '),
      chosen: current?.id === skill.id,
    }))

  return (
    <>
      <PickerHeader
        title="Use a skill"
        onBack={onBack}
        action={
          <button type="button" className="plus-done" onClick={() => navigate('/skills')}>
            Manage
          </button>
        }
      />
      {skills.isPending ? (
        <Loading label="Loading skills" />
      ) : skills.isError ? (
        <p className="plus-state caption" role="alert">
          {describeApiError(skills.error)}
        </p>
      ) : (
        <SearchList
          label="Skills"
          placeholder="Search skills"
          items={items}
          query={query}
          onQuery={setQuery}
          empty={list.length === 0 ? 'No skills yet. Create one on the Skills page.' : `No skill matches "${query}"`}
          onChoose={(item) => {
            const skill = list.find((s) => s.id === item.id)
            if (skill) {
              onChoose({
                id: skill.id,
                name: skill.name,
                defaultAgentId: skill.defaultAgentId,
                defaultAgentName: skill.defaultAgentName,
              })
            }
          }}
        />
      )}
    </>
  )
}

/* ---- Agents ------------------------------------------------------------------------------------- */

// @find: AgentPicker, choose an agent from plus menu
export function AgentPicker({
  agents,
  mentionedIds,
  onBack,
  onChoose,
}: {
  agents: Agent[]
  mentionedIds: string[]
  onBack: () => void
  onChoose: (agent: Agent) => void
}) {
  const [query, setQuery] = useState('')
  const items: SearchItem[] = agents
    .filter((agent) => matches(query, agent.name, agent.category))
    .map((agent) => ({
      id: agent.id,
      label: agent.name,
      note: agent.fallback ? 'Default' : (CATEGORY_LABEL[agent.category] ?? agent.category),
      icon: <AgentAvatar name={agent.name} category={agent.category} fallback={agent.fallback ?? false} size="sm" />,
      chosen: mentionedIds.includes(agent.id),
    }))
  return (
    <>
      <PickerHeader title="Choose an agent" onBack={onBack} />
      <SearchList
        label="Agents"
        placeholder="Search agents"
        items={items}
        query={query}
        onQuery={setQuery}
        empty={agents.length === 0 ? 'No active agents.' : `No agent matches "${query}"`}
        onChoose={(item) => {
          const agent = agents.find((a) => a.id === item.id)
          if (agent) onChoose(agent)
        }}
      />
    </>
  )
}
