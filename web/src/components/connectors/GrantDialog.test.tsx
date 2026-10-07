import axe from 'axe-core'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Tool } from '../../lib/queries'
import { GrantCapabilitiesForm } from './GrantDialog'

/*
 * The second step of giving an agent a connector: what it may do there. Reads start ticked,
 * creating, sending and deleting start unticked, an existing grant starts from its own tools, and
 * what is saved is the explicit list of what was ticked.
 */

function tool(name: string, sideEffect: Tool['sideEffect'], description = `Does ${name}.`): Tool {
  return {
    name,
    qualifiedName: `github.${name}`,
    description,
    sideEffect,
    requiredScopes: [],
    alwaysRequiresApproval: sideEffect === 'OUTBOUND' || sideEffect === 'DESTRUCTIVE',
  }
}

const TOOLS: Tool[] = [
  tool('list_issues', 'READ'),
  tool('create_issue', 'WRITE'),
  tool('comment_on_issue', 'OUTBOUND'),
  tool('get_issue', 'READ'),
  tool('delete_branch', 'DESTRUCTIVE'),
]

function renderForm(grant: Parameters<typeof GrantCapabilitiesForm>[0]['grant'] = null) {
  const onSubmit = vi.fn()
  const view = render(
    <GrantCapabilitiesForm
      connectorName="GitHub"
      tools={TOOLS}
      grant={grant}
      saving={false}
      onSubmit={onSubmit}
      onCancel={() => {}}
    />,
  )
  return { onSubmit, ...view }
}

const box = (name: string) => screen.getByRole('checkbox', { name: new RegExp(`^${name}`) })

describe('GrantCapabilitiesForm', () => {
  it('ticks only the reads for a connector the agent does not have yet', () => {
    renderForm()
    expect(box('List issues')).toBeChecked()
    expect(box('Get issue')).toBeChecked()
    expect(box('Create issue')).not.toBeChecked()
    expect(box('Comment on issue')).not.toBeChecked()
    expect(box('Delete branch')).not.toBeChecked()
    expect(screen.getByRole('switch', { name: /Always ask a person/ })).not.toBeChecked()
    expect(screen.getByText('2 of 5 chosen')).toBeInTheDocument()
  })

  it('groups the checkboxes under plain headings, saying which ones ask first', () => {
    renderForm()
    const sends = screen.getByRole('group', { name: 'Sends — asks first' })
    expect(within(sends).getByRole('checkbox', { name: /Comment on issue/ })).toBeInTheDocument()
    const deletes = screen.getByRole('group', { name: 'Deletes — asks first' })
    expect(within(deletes).getByRole('checkbox', { name: /Delete branch/ })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Reads' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Creates and edits' })).toBeInTheDocument()
  })

  it('starts an existing grant from its tools, with an empty list meaning everything', () => {
    renderForm({ tools: [], requireApproval: true, maxCallsPerRun: 20 })
    for (const name of ['List issues', 'Get issue', 'Create issue', 'Comment on issue', 'Delete branch']) {
      expect(box(name)).toBeChecked()
    }
    expect(screen.getByRole('switch', { name: /Always ask a person/ })).toBeChecked()
    expect(screen.getByLabelText(/Most calls per run/)).toHaveValue(20)
    expect(screen.getByRole('button', { name: 'Clear all' })).toBeInTheDocument()
  })

  it('selects everything at once and saves the explicit list in the connector order', () => {
    const { onSubmit } = renderForm()
    fireEvent.click(screen.getByRole('button', { name: 'Select all' }))
    expect(box('Delete branch')).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: 'Add connector' }))
    expect(onSubmit).toHaveBeenCalledWith({
      tools: ['list_issues', 'create_issue', 'comment_on_issue', 'get_issue', 'delete_branch'],
      requireApproval: false,
      maxCallsPerRun: null,
    })
  })

  it('saves the switch and the call limit with the ticked tools', () => {
    const { onSubmit } = renderForm()
    fireEvent.click(box('Get issue'))
    fireEvent.click(box('Create issue'))
    fireEvent.click(screen.getByRole('switch', { name: /Always ask a person/ }))
    fireEvent.change(screen.getByLabelText(/Most calls per run/), { target: { value: '25' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add connector' }))
    expect(onSubmit).toHaveBeenCalledWith({ tools: ['list_issues', 'create_issue'], requireApproval: true, maxCallsPerRun: 25 })
  })

  it('refuses to save nothing, or a call limit outside 1 to 200', () => {
    const { onSubmit } = renderForm()
    fireEvent.click(box('List issues'))
    fireEvent.click(box('Get issue'))
    expect(screen.getByText(/Nothing chosen yet/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add connector' })).toBeDisabled()

    fireEvent.click(box('List issues'))
    fireEvent.change(screen.getByLabelText(/Most calls per run/), { target: { value: '500' } })
    expect(screen.getByText(/Enter a whole number from 1 to 200/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add connector' })).toBeDisabled()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('has no detectable accessibility violations', async () => {
    const { container } = renderForm()
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })
    expect(results.violations.map((violation) => violation.id)).toEqual([])
  })
})
