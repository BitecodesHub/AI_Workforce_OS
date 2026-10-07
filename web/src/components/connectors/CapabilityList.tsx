import { useId } from 'react'
import { Tag } from '../ui'
import { asksFirst, capabilityLabel, groupCapabilities, type CapabilityTool } from '../../lib/connectors'

/*
 * What a connector can do, read-only, in its four groups: Reads, Creates and edits, Sends - asks
 * first, Deletes - asks first. Used on each Connectors card and under each connector an agent has.
 *
 * Each capability is its plain name; the tool's own description is its tooltip, and the group
 * heading carries whether a person is asked first, so the list itself stays short.
 */

export function CapabilityList({
  tools,
  emptyText = 'It offers nothing an agent can use yet.',
}: {
  tools: readonly CapabilityTool[]
  emptyText?: string
}) {
  const baseId = useId()
  const groups = groupCapabilities(tools)
  if (groups.length === 0) return <p className="caption">{emptyText}</p>

  return (
    <div className="stack" style={{ gap: 'var(--space-4)' }}>
      {groups.map((group) => {
        const headingId = `${baseId}-${group.key}`
        return (
          <div key={group.key} className="stack" style={{ gap: 'var(--space-2)' }}>
            <p className="caption" id={headingId}>
              {group.label}
            </p>
            <ul
              className="row"
              aria-labelledby={headingId}
              style={{ flexWrap: 'wrap', gap: 'var(--space-2)', margin: 0, padding: 0, listStyle: 'none' }}
            >
              {group.tools.map((tool) => (
                <li key={tool.name} style={{ maxWidth: '100%' }}>
                  <Tag tone="neutral" title={tool.description ?? undefined}>
                    {capabilityLabel(tool)}
                    {/* A read or an edit that asks first anyway says so; the two groups that
                        always ask already say it in their heading. */}
                    {!group.asksFirst && asksFirst(tool) ? ' · asks first' : ''}
                  </Tag>
                </li>
              ))}
            </ul>
          </div>
        )
      })}
    </div>
  )
}
