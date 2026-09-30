import { formatCount } from '../../lib/format'
import { MenuButton } from '../ui/Menu'
import type { MenuEntry } from '../ui/Menu'

/*
 * The one row of controls above the goals (B2.5): search on the left, the status chips beside it,
 * every less-used filter (source, agent, requester) folded into a single Filters menu, and the
 * count and the Board or List choice on the right. It only draws the controls - every value lives
 * in the URL through useListFilter and the page's own `agent` parameter, exactly as before.
 *
 * It sits outside the board's own keyboard container on purpose: the menu's arrow keys must never
 * reach the j/k and arrow handling that moves focus between cards.
 */

export type ToolbarOption = { value: string; label: string; count?: number }

export type ListGroup = 'none' | 'agent' | 'requester'

const GROUP_LABEL: Record<ListGroup, string> = { none: 'None', agent: 'Agent', requester: 'Requester' }

function RemoveIcon() {
  return (
    <svg width="10" height="10" viewBox="0 0 10 10" fill="none" aria-hidden="true">
      <path d="M2.5 2.5l5 5M7.5 2.5l-5 5" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </svg>
  )
}

function SearchIcon() {
  return (
    <svg className="orc-search-icon" width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <circle cx="7" cy="7" r="4.75" stroke="currentColor" strokeWidth="1.4" />
      <path d="M10.5 10.5 14 14" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  )
}

export function BoardToolbar({
  query,
  onQueryChange,
  statusOptions,
  statusSelected,
  onToggleStatus,
  sourceOptions,
  sourceSelected,
  onToggleSource,
  agentOptions,
  agentSelected,
  onSelectAgent,
  requesterOptions,
  requesterSelected,
  onSelectRequester,
  shown,
  total,
  active,
  onClear,
  view,
  onSetView,
  group,
  onSetGroup,
}: {
  query: string
  onQueryChange: (query: string) => void
  statusOptions: ToolbarOption[]
  statusSelected: readonly string[]
  onToggleStatus: (value: string) => void
  sourceOptions: ToolbarOption[]
  sourceSelected: readonly string[]
  onToggleSource: (value: string) => void
  agentOptions: ToolbarOption[]
  agentSelected: string | null
  onSelectAgent: (value: string | null) => void
  requesterOptions: ToolbarOption[]
  requesterSelected: string | null
  onSelectRequester: (value: string | null) => void
  shown: number
  total: number
  active: boolean
  onClear: () => void
  view: 'board' | 'list'
  onSetView: (view: 'board' | 'list') => void
  group: ListGroup
  onSetGroup: (group: ListGroup) => void
}) {
  const menuCount = sourceSelected.length + (agentSelected ? 1 : 0) + (requesterSelected ? 1 : 0)

  const filterItems: MenuEntry[] = [
    { id: 'source', groupLabel: 'Source' },
    ...sourceOptions.map((option) => ({
      id: `source-${option.value}`,
      label: option.label,
      checked: sourceSelected.includes(option.value),
      onSelect: () => onToggleSource(option.value),
    })),
    { id: 'agent', groupLabel: 'Agent' },
    { id: 'agent-any', label: 'Every agent', checked: agentSelected === null, group: 'agent', onSelect: () => onSelectAgent(null) },
    ...agentOptions.map((option) => ({
      id: `agent-${option.value}`,
      label: option.label,
      checked: agentSelected === option.value,
      group: 'agent',
      onSelect: () => onSelectAgent(option.value),
    })),
    { id: 'requester', groupLabel: 'Requester' },
    {
      id: 'requester-any',
      label: 'Everyone',
      checked: requesterSelected === null,
      group: 'requester',
      onSelect: () => onSelectRequester(null),
    },
    ...requesterOptions.map((option) => ({
      id: `requester-${option.value}`,
      label: option.label,
      checked: requesterSelected === option.value,
      group: 'requester',
      onSelect: () => onSelectRequester(option.value),
    })),
  ]

  const groupItems: MenuEntry[] = [
    { id: 'group', groupLabel: 'Group the list by' },
    ...(['none', 'agent', 'requester'] as const).map((value) => ({
      id: `group-${value}`,
      label: GROUP_LABEL[value],
      checked: group === value,
      group: 'group',
      onSelect: () => onSetGroup(value),
    })),
  ]

  // What the Filters menu is set to, as removable pills beside it, so a filter chosen from inside
  // a closed menu (or from the workforce map) is never invisible.
  const pills: Array<{ key: string; label: string; onRemove: () => void }> = [
    ...sourceSelected.map((value) => ({
      key: `source-${value}`,
      label: sourceOptions.find((option) => option.value === value)?.label ?? value,
      onRemove: () => onToggleSource(value),
    })),
    ...(agentSelected
      ? [{ key: 'agent', label: agentOptions.find((option) => option.value === agentSelected)?.label ?? 'One agent', onRemove: () => onSelectAgent(null) }]
      : []),
    ...(requesterSelected
      ? [
          {
            key: 'requester',
            label: `From ${requesterOptions.find((option) => option.value === requesterSelected)?.label ?? 'one person'}`,
            onRemove: () => onSelectRequester(null),
          },
        ]
      : []),
  ]

  return (
    <div className="orc-toolbar" role="search" aria-label="Search and filter goals">
      <div className="orc-toolbar-filters">
        <label className="orc-search">
          <span className="visually-hidden">Search goals</span>
          <SearchIcon />
          <input
            type="search"
            className="input orc-search-input"
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="Search goals"
            autoComplete="off"
            spellCheck={false}
          />
        </label>

        <div className="orc-status-chips" role="group" aria-label="Status">
          {statusOptions.map((option) => (
            <button
              key={option.value}
              type="button"
              className="filter-chip orc-chip"
              aria-pressed={statusSelected.includes(option.value)}
              onClick={() => onToggleStatus(option.value)}
            >
              {option.label}
              {option.count !== undefined && <span className="filter-chip-count tabular">{formatCount(option.count)}</span>}
            </button>
          ))}
        </div>

        <MenuButton
          label="Filter by source, agent or requester"
          text={menuCount > 0 ? `Filters · ${formatCount(menuCount)}` : 'Filters'}
          items={filterItems}
          className="orc-filters-menu"
        />

        {pills.map((pill) => (
          <button key={pill.key} type="button" className="orc-pill" aria-label={`Remove filter: ${pill.label}`} onClick={pill.onRemove}>
            <span className="orc-pill-label">{pill.label}</span>
            <RemoveIcon />
          </button>
        ))}

        {active && (
          <button type="button" className="link orc-clear" onClick={onClear}>
            Clear filters
          </button>
        )}
      </div>

      <div className="orc-toolbar-view">
        <p role="status" className="caption muted tabular orc-toolbar-count">
          Showing {formatCount(shown)} of {formatCount(total)}
        </p>
        {view === 'list' && (
          <MenuButton label="Group the list" text={`Group: ${GROUP_LABEL[group]}`} items={groupItems} align="end" className="orc-filters-menu" />
        )}
        <div className="orc-segmented" role="group" aria-label="Board or list view">
          <button type="button" aria-pressed={view === 'board'} onClick={() => onSetView('board')}>
            Board
          </button>
          <button type="button" aria-pressed={view === 'list'} onClick={() => onSetView('list')}>
            List
          </button>
        </div>
      </div>
    </div>
  )
}
