import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Conversation } from '../../lib/queries'
import type { useSpeaker } from '../../lib/voice'
import { ThreadHeader } from './ThreadHeader'

/*
 * The thread header says who can read a conversation, and offers nothing that would act on a
 * conversation that could not be opened.
 */

const CONVERSATION: Conversation = {
  id: 'c1',
  title: 'Q3 quotes',
  createdBy: 'user-1',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
  lastMessagePreview: '',
  messageCount: 3,
  pinned: false,
  archived: false,
  activity: 'idle',
  canManage: true,
  unread: false,
  match: null,
}

const speaker = { provider: 'none', muted: true, setMuted: () => {} } as unknown as ReturnType<typeof useSpeaker>

function renderHeader(props: { title: string; conversation: Conversation | null; unavailable?: boolean }) {
  render(
    <ThreadHeader
      eyebrow="Chat"
      title={props.title}
      conversation={props.conversation}
      {...(props.unavailable ? { unavailable: true } : {})}
      participants={[]}
      readOnly={false}
      onRename={async () => true}
      onTogglePin={() => {}}
      onCopyLink={() => {}}
      onCopyConversation={() => {}}
      onSetVisibility={() => {}}
      onAddPeople={() => {}}
      onDelete={() => {}}
      speaker={speaker}
      detailsMode="auto"
      onDetailsMode={() => {}}
      onShowShortcuts={() => {}}
    />,
  )
  fireEvent.click(screen.getByRole('button', { name: 'Conversation actions' }))
}

const menuItems = () => Array.from(document.querySelectorAll('[role^="menuitem"]')).map((item) => item.textContent?.trim())

describe('ThreadHeader', () => {
  it('says a workspace conversation is read by everyone in the workspace', () => {
    renderHeader({ title: 'Q3 quotes', conversation: CONVERSATION })
    expect(screen.getByText('Everyone in this workspace')).toBeInTheDocument()
    expect(menuItems()).toEqual(expect.arrayContaining(['Rename', 'Pin', 'Copy link', 'Copy conversation', 'Delete']))
  })

  it('says a private conversation is only for its creator and the people added, and offers to share it', () => {
    renderHeader({ title: 'Salary review', conversation: { ...CONVERSATION, visibility: 'private', canShare: true } })
    expect(screen.getByText('Only you and people you add')).toBeInTheDocument()
    expect(menuItems()).toEqual(expect.arrayContaining(['Share with workspace', 'Add people']))
    expect(menuItems()).not.toContain('Make private')
  })

  it('offers to make a shared conversation private only to somebody who may change who reads it', () => {
    renderHeader({ title: 'Q3 quotes', conversation: { ...CONVERSATION, canShare: true } })
    expect(menuItems()).toContain('Make private')
    expect(menuItems()).not.toContain('Add people')
  })

  it('offers no sharing to somebody who only reads it', () => {
    renderHeader({ title: 'Salary review', conversation: { ...CONVERSATION, visibility: 'private' } })
    expect(menuItems()).not.toContain('Share with workspace')
    expect(menuItems()).not.toContain('Add people')
  })

  it('offers no rename, pin, copy or delete for a conversation that could not be opened', () => {
    renderHeader({ title: 'Conversation unavailable', conversation: null, unavailable: true })
    expect(screen.getByRole('heading', { level: 1, name: 'Conversation unavailable' })).toBeInTheDocument()
    expect(screen.queryByText('Everyone in this workspace')).toBeNull()
    const items = menuItems()
    for (const label of ['Rename', 'Pin', 'Unpin', 'Copy link', 'Copy conversation', 'Delete']) {
      expect(items).not.toContain(label)
    }
    expect(items).toContain('Keyboard shortcuts')
  })
})
