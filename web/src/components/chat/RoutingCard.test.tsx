import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Agent, ChatMessage } from '../../lib/queries'
import { becauseText } from './chatModel'
import { RoutingCard } from './RoutingCard'

const HR = { id: 'a1', name: 'HR', status: 'active', summary: 'People questions' } as unknown as Agent
const IT = { id: 'a2', name: 'IT', status: 'active', summary: 'Devices' } as unknown as Agent

const message = {
  id: 'm1',
  kind: 'routing',
  goalId: 'g1',
  createdAt: '2026-10-01T00:00:00Z',
  detail: {
    mode: 'model',
    reason: 'The request is about screening job applications, which is HR work. '.repeat(3),
    agents: [{ id: 'a1', name: 'HR' }],
  },
} as unknown as ChatMessage

describe('RoutingCard', () => {
  it('is one quiet line with a Change control that opens the reroute list', () => {
    const onReroute = vi.fn()
    render(
      <RoutingCard message={message} agentNames={{ a1: HR, a2: IT }} settled chosenName={null} rerouting={false} onReroute={onReroute} />,
    )
    expect(screen.getByText('Sent to HR')).toBeInTheDocument()
    const why = screen.getByRole('button', { name: /^because the request/ })
    expect(why).toHaveAttribute('aria-expanded', 'false')
    expect(why).toHaveAttribute('title')
    fireEvent.click(why)
    expect(why).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Change who takes this request' }))
    fireEvent.click(screen.getByRole('button', { name: /IT/ }))
    expect(onReroute).toHaveBeenCalledWith('a2')
  })

  it('lower-cases the start of a reason and drops its final full stop', () => {
    expect(becauseText('The request is about HR.')).toBe('the request is about HR')
    expect(becauseText('HR owns this.')).toBe('HR owns this')
  })
})
