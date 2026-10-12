// @find: tests for workflow draft, autosave, restore draft, blocked storage, draft key per person workspace and workflow, clear all drafts
// @what: Tests for lib/workflowDraft: keeping, reading and forgetting the editor's unsaved work, and surviving blocked storage.
// @flow: Exercises lib/workflowDraft with an in-memory Storage installed as localStorage
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearAllDrafts, clearDraft, draftKey, readDraft, writeDraft } from './workflowDraft'

/*
 * Recent Node versions define their own global localStorage, which shadows jsdom's and reads as
 * undefined (see persist.test.ts), so each test installs its own.
 */
function memoryStorage(): Storage {
  const items = new Map<string, string>()
  return {
    get length() {
      return items.size
    },
    clear: () => items.clear(),
    getItem: (key) => items.get(key) ?? null,
    key: (index) => [...items.keys()][index] ?? null,
    removeItem: (key) => void items.delete(key),
    setItem: (key, value) => void items.set(key, String(value)),
  }
}

function blockedStorage(): Storage {
  const fail = () => {
    throw new Error('blocked')
  }
  return { length: 0, clear: fail, getItem: fail, key: fail, removeItem: fail, setItem: fail }
}

beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('workflow drafts', () => {
  it('keys a draft by person, workspace and workflow, with "new" for one never saved', () => {
    expect(draftKey('wf-1', 'user-1', 'ws-a')).toBe('aiwos:workflow-draft:user-1:ws-a:wf-1')
    expect(draftKey(null, null, null)).toBe('aiwos:workflow-draft:anyone:none:new')
    // The same person's new-workflow draft in another workspace is another key, so it is never offered there.
    expect(draftKey(null, 'user-1', 'ws-a')).not.toBe(draftKey(null, 'user-1', 'ws-b'))
  })

  it('forgets every workflow draft, and nothing else, when asked (as signing out does)', () => {
    const empty = { name: '', description: '', graph: { nodes: [], edges: [] }, baseVersion: null, savedAt: '' }
    writeDraft(draftKey(null, 'user-1', 'ws-a'), empty)
    writeDraft(draftKey('wf-1', 'user-2', 'ws-b'), empty)
    localStorage.setItem('aiwos:theme', 'dark')
    clearAllDrafts()
    expect(readDraft(draftKey(null, 'user-1', 'ws-a'))).toBeNull()
    expect(readDraft(draftKey('wf-1', 'user-2', 'ws-b'))).toBeNull()
    expect(localStorage.getItem('aiwos:theme')).toBe('dark')
    vi.stubGlobal('localStorage', blockedStorage())
    expect(() => clearAllDrafts()).not.toThrow()
  })

  it('keeps, reads back and forgets a draft', () => {
    const key = draftKey('wf-1', 'user-1', 'ws-a')
    writeDraft(key, {
      name: 'Reply',
      description: '',
      graph: { nodes: [{ id: 'a', type: 'agent', agentId: null, title: null, instruction: 'Go.', connectors: [], x: 1, y: 2 }], edges: [] },
      baseVersion: 3,
      savedAt: '2026-10-12T09:00:00.000Z',
    })
    const draft = readDraft(key)
    expect(draft?.name).toBe('Reply')
    expect(draft?.baseVersion).toBe(3)
    expect(draft?.graph.nodes[0]?.instruction).toBe('Go.')
    clearDraft(key)
    expect(readDraft(key)).toBeNull()
  })

  it('reads a damaged draft as none', () => {
    localStorage.setItem('broken', '{not json')
    expect(readDraft('broken')).toBeNull()
    localStorage.setItem('odd', '42')
    expect(readDraft('odd')).toBeNull()
  })

  it('never throws when storage is blocked', () => {
    vi.stubGlobal('localStorage', blockedStorage())
    expect(readDraft('any')).toBeNull()
    expect(() =>
      writeDraft('any', { name: '', description: '', graph: { nodes: [], edges: [] }, baseVersion: null, savedAt: '' }),
    ).not.toThrow()
    expect(() => clearDraft('any')).not.toThrow()
  })
})
