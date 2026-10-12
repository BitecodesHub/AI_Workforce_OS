// @find: tests for user bubble, edit and send again, card request bubble, message sent from a card, UserBubble component tests
// @what: Component tests for a person's own message: Copy and "Edit and send again", which a message sent from an answer card does not offer.
// @flow: Renders components/chat/UserBubble.tsx directly with a plain ChatMessage.
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ChatMessage } from '../../lib/queries'
import { UserBubble } from './UserBubble'

/** A text message by the viewer, with the given detail. */
function message(content: string, detail: Record<string, unknown> = {}): ChatMessage {
  return {
    id: 'u1',
    position: 0,
    authorKind: 'user',
    authorId: 'me',
    agentId: null,
    kind: 'text',
    content,
    detail,
    goalId: null,
    createdAt: '2026-10-13T00:00:00Z',
  }
}

describe('UserBubble', () => {
  it('offers "Edit and send again" for the viewer\'s own typed message, handing the text back', () => {
    const onEditAndResend = vi.fn()
    render(<UserBubble message={message('Draft a welcome email')} grouped={false} author={{ isMe: true, name: 'Maya' }} onEditAndResend={onEditAndResend} />)
    fireEvent.click(screen.getByRole('button', { name: 'Edit and send again' }))
    expect(onEditAndResend).toHaveBeenCalledWith('Draft a welcome email')
  })

  it('does not offer it for a message sent from an answer card, whose words alone would drop the card\'s content', () => {
    const card = { messageId: 'a1', index: 0, kind: 'email-draft', content: { to: 'sam@example.com', subject: 'Welcome', body: 'Hi Sam' } }
    render(
      <UserBubble
        message={message('Please send my edited email "Welcome" to sam@example.com, exactly as written in the draft.', { context: { card } })}
        grouped={false}
        author={{ isMe: true, name: 'Maya' }}
        onEditAndResend={vi.fn()}
      />,
    )
    expect(screen.getByText(/Please send my edited email/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit and send again' })).toBeNull()
    // Copy stays: it only copies the words.
    expect(screen.getByRole('button', { name: /Copy/ })).toBeInTheDocument()
  })
})
