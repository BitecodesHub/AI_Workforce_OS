// @find: workflow draft, autosave, unsaved changes on this device, restore draft, discard draft, localStorage, workflowDraft
// @what: Keeps the canvas editor's unsaved work in this browser so a reload, a crash or a closed tab does not lose it.
// @flow: Used by routes/WorkflowEditor.tsx; every storage access is wrapped so blocked storage never breaks the editor
import { normaliseGraph } from './workflowGraph'
import type { WorkflowGraph } from './workflowGraph'

/*
 * A draft is a convenience on this device only: it is never sent anywhere, and a browser that
 * blocks storage simply keeps no draft. The workspace copy changes only on Save.
 */

/** What a draft holds: the editor's fields, the version they were based on and when they were kept. */
export type WorkflowDraft = {
  name: string
  description: string
  graph: WorkflowGraph
  /** The server version the edits started from; null for a workflow never saved. */
  baseVersion: number | null
  savedAt: string
}

const PREFIX = 'aiwos:workflow-draft:'

/**
 * The storage key of a workflow's draft; "new" for one not saved yet. Scoped to the person and
 * the workspace, so a person in two workspaces is never offered one workspace's unsaved work in
 * the other.
 */
export function draftKey(workflowId: string | null, userId: string | null, workspaceId: string | null): string {
  return `${PREFIX}${userId ?? 'anyone'}:${workspaceId ?? 'none'}:${workflowId ?? 'new'}`
}

// @find: forget every workflow draft, sign out clears drafts
/** Forgets every workflow draft kept in this browser, for anybody; does nothing when storage is blocked. Called on sign-out. */
export function clearAllDrafts(): void {
  try {
    const keys: string[] = []
    for (let index = 0; index < localStorage.length; index += 1) {
      const key = localStorage.key(index)
      if (key?.startsWith(PREFIX)) keys.push(key)
    }
    for (const key of keys) localStorage.removeItem(key)
  } catch {
    // Storage is blocked, so nothing was kept.
  }
}

// @find: read workflow draft
/** The draft under a key, or null when there is none, it cannot be read, or storage is blocked. */
export function readDraft(key: string): WorkflowDraft | null {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return null
    const value: unknown = JSON.parse(raw)
    if (typeof value !== 'object' || value === null) return null
    const record = value as Record<string, unknown>
    return {
      name: typeof record.name === 'string' ? record.name : '',
      description: typeof record.description === 'string' ? record.description : '',
      graph: normaliseGraph(record.graph),
      baseVersion: typeof record.baseVersion === 'number' ? record.baseVersion : null,
      savedAt: typeof record.savedAt === 'string' ? record.savedAt : new Date(0).toISOString(),
    }
  } catch {
    return null
  }
}

// @find: write workflow draft, autosave
/** Keeps a draft; does nothing when storage is blocked or full. */
export function writeDraft(key: string, draft: WorkflowDraft): void {
  try {
    localStorage.setItem(key, JSON.stringify(draft))
  } catch {
    // Private browsing or a full quota: the editor keeps working without a draft.
  }
}

// @find: clear workflow draft
/** Forgets a draft; does nothing when storage is blocked. */
export function clearDraft(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
    // Nothing was kept, so there is nothing to forget.
  }
}
