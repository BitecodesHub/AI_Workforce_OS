import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  CATEGORY_LABEL,
  OUTCOME_LABEL,
  OUTCOME_TONE,
  actionClassLabel,
  auditActionLabel,
  categoryTone,
  embeddingProviderLabel,
  goalSourceLabel,
  hasExplicitStatus,
  mediaTypeLabel,
  providerKindLabel,
  roleLabel,
  scheduleStateLabel,
  serverLabel,
  sourceKindLabel,
  startedByLabel,
  statusLabel,
  toolLabel,
} from './labels'
import type { StatusKind } from './labels'

/*
 * The status vocabulary is checked against the database, not against a list typed here: every
 * value a CHECK constraint allows must have a label of its own, so a status added in a migration
 * fails this test until someone decides what the console calls it.
 */

const SERVICES = join(__dirname, '../../../services')

type Constraint = { name: string; file: string; values: string[] }

function readConstraints(): Constraint[] {
  const found: Constraint[] = []
  for (const service of readdirSync(SERVICES)) {
    const dir = join(SERVICES, service, 'src/main/resources/db/migration')
    if (!existsSync(dir)) continue
    for (const file of readdirSync(dir).filter((name) => name.endsWith('.sql'))) {
      const sql = readFileSync(join(dir, file), 'utf8')
      const pattern = /CONSTRAINT\s+(\w+)\s+CHECK\s*\(\s*\w+\s+IN\s*\(([^)]*)\)/gi
      for (const match of sql.matchAll(pattern)) {
        const values = [...match[2]!.matchAll(/'([^']*)'/g)].map((value) => value[1]!)
        found.push({ name: match[1]!, file: `${service}/${file}`, values })
      }
    }
  }
  return found
}

const CONSTRAINTS = readConstraints()

/** Every value any migration has allowed for the constraint, across all services. */
function valuesOf(name: string): string[] {
  const values = CONSTRAINTS.filter((constraint) => constraint.name === name).flatMap((c) => c.values)
  return [...new Set(values)]
}

/** Which label map covers which status constraint. */
const STATUS_CONSTRAINTS: Record<string, StatusKind> = {
  runs_status_valid: 'run',
  tasks_status_valid: 'task',
  goals_status_valid: 'goal',
  approvals_status_valid: 'approval',
  agents_status_valid: 'agent',
  sources_status_valid: 'source',
  documents_status_valid: 'document',
  connections_status_valid: 'integration',
  invitations_status_valid: 'invitation',
  memberships_status_valid: 'member',
  audit_outcome_valid: 'outcome',
  tool_invocations_status_valid: 'toolCall',
  run_questions_status_valid: 'question',
}

/** Status constraints the console never shows, and why, so a new one cannot slip past unnoticed. */
const NOT_SHOWN: Record<string, string> = {
  users_status_valid: 'the members list shows the membership status, not the user record',
  signing_keys_status_valid: 'internal key rotation',
  organisations_status_valid: 'a closed or suspended workspace cannot be signed in to',
  ingestion_status_valid: 'ingestion jobs are shown through the source status',
  // Seeded 'missing' and only ever set to 'rejected', so it cannot say whether a key is stored.
  // Screens read credential presence from routing.ts credentialState instead.
  llm_credential_status_valid: 'replaced by credentialState in routing.ts',
  // The per-workspace successor of llm_credential_status_valid (V10). Screens still read it
  // through credentialState in routing.ts, never as a raw status.
  workspace_credential_status_valid: 'read through credentialState in routing.ts',
  // A chat attachment is either readable or shown with its own plain reason (AttachmentChips).
  chat_attachments_status_valid: 'shown as the file chip itself, or the reason it could not be read',
}

describe('status labels cover the database', () => {
  it('finds the migrations', () => {
    expect(CONSTRAINTS.length).toBeGreaterThan(10)
    expect(valuesOf('runs_status_valid')).toContain('waiting_approval')
    expect(valuesOf('runs_status_valid')).toContain('waiting_input')
  })

  for (const [constraint, kind] of Object.entries(STATUS_CONSTRAINTS)) {
    it(`labels every ${constraint} value as a ${kind} status`, () => {
      const values = valuesOf(constraint)
      expect(values.length).toBeGreaterThan(0)
      const missing = values.filter((value) => !hasExplicitStatus(kind, value))
      expect(missing).toEqual([])
    })
  }

  it('accounts for every status and outcome constraint in the migrations', () => {
    const statusConstraints = [
      ...new Set(
        CONSTRAINTS.map((constraint) => constraint.name).filter((name) => /_(status|outcome)_valid$/.test(name)),
      ),
    ]
    const unaccounted = statusConstraints.filter((name) => !(name in STATUS_CONSTRAINTS) && !(name in NOT_SHOWN))
    expect(unaccounted).toEqual([])
  })

  it('names every agent category the database allows', () => {
    for (const category of valuesOf('agents_category_valid')) {
      expect(CATEGORY_LABEL[category]).toBeTruthy()
      expect(categoryTone(category)).toBe(category)
    }
  })

  it('names every goal source the database allows (goals_source_valid, not a *_status_valid constraint)', () => {
    const sources = valuesOf('goals_source_valid')
    expect(sources.length).toBeGreaterThan(0)
    expect(sources.map((source) => goalSourceLabel(source).label)).toEqual(['Started by hand', 'From Chat', 'Scheduled'])
  })

  it('names every source kind, provider kind and action class the database allows', () => {
    expect(valuesOf('sources_kind_valid').map(sourceKindLabel)).toEqual([
      'Uploaded files',
      'Google Drive',
      'Notion',
      'Confluence',
      'GitHub wiki',
    ])
    expect(valuesOf('llm_provider_kind_valid').map(providerKindLabel)).toEqual([
      'OpenAI-compatible',
      'Anthropic',
      'Google Gemini',
      'AWS Bedrock',
      'Offline sandbox',
    ])
    expect(valuesOf('approval_policies_class_valid').map(actionClassLabel)).toEqual([
      'Reads data',
      'Changes data',
      'Leaves the workspace',
      'Removes something',
    ])
  })
})

describe('statusLabel', () => {
  it('gives one word one meaning per kind', () => {
    expect(statusLabel('approval', 'pending')).toEqual({ tone: 'warning', label: 'Awaiting decision' })
    expect(statusLabel('document', 'pending')).toEqual({ tone: 'neutral', label: 'Queued' })
    expect(statusLabel('task', 'pending')).toEqual({ tone: 'neutral', label: 'Waiting to start' })
    expect(statusLabel('task', 'skipped').label).toBe('Skipped')
    expect(statusLabel('document', 'skipped').label).toBe('Not indexable')
    expect(statusLabel('source', 'ready')).toEqual({ tone: 'success', label: 'Ready' })
    expect(statusLabel('agent', 'retired').label).toBe('Retired')
    expect(statusLabel('run', 'waiting_approval')).toEqual({ tone: 'warning', label: 'Waiting for approval' })
    expect(statusLabel('run', 'waiting_input')).toEqual({ tone: 'warning', label: 'Waiting for an answer' })
    expect(statusLabel('question', 'pending')).toEqual({ tone: 'warning', label: 'Waiting for an answer' })
    expect(statusLabel('question', 'cancelled')).toEqual({ tone: 'neutral', label: 'Withdrawn' })
  })

  it('ignores case and hyphens', () => {
    expect(statusLabel('circuit', 'CLOSED')).toEqual({ tone: 'success', label: 'Healthy' })
    expect(statusLabel('circuit', 'half-open').label).toBe('Recovering')
    expect(statusLabel('circuit', 'HALF_OPEN').label).toBe('Recovering')
    expect(statusLabel('circuit', 'FORCED_OPEN').label).toBe('Paused')
    expect(statusLabel('toolCall', 'INDETERMINATE').label).toBe('Outcome unknown')
  })

  it('falls back to sentence case, never a raw code', () => {
    expect(statusLabel('run', 'something_new')).toEqual({ tone: 'neutral', label: 'Something new' })
    expect(statusLabel('run', '')).toEqual({ tone: 'neutral', label: 'Unknown' })
    expect(statusLabel('run', null)).toEqual({ tone: 'neutral', label: 'Unknown' })
    expect(statusLabel('run', undefined).label).toBe('Unknown')
  })

  it('keeps the audit outcome maps in step', () => {
    expect(OUTCOME_LABEL).toEqual({ succeeded: 'Succeeded', failed: 'Failed', denied: 'Denied', locked: 'Locked' })
    expect(OUTCOME_TONE).toEqual({ succeeded: 'success', failed: 'danger', denied: 'warning', locked: 'warning' })
  })
})

describe('other labels', () => {
  it('capitalises built-in roles and keeps custom ones verbatim', () => {
    expect(roleLabel('owner')).toBe('Owner')
    expect(roleLabel('viewer')).toBe('Viewer')
    expect(roleLabel('reviewer (finance)')).toBe('reviewer (finance)')
    expect(roleLabel(undefined)).toBe('No role')
  })

  it('names categories and gives unknown ones a neutral tone', () => {
    expect(categoryTone('growth')).toBe('growth')
    expect(categoryTone('marketing')).toBe('neutral')
    expect(categoryTone(null)).toBe('neutral')
  })

  it('describes audit actions', () => {
    expect(auditActionLabel('run.complete')).toBe('Run completed')
    expect(auditActionLabel('run.fail')).toBe('Run failed')
    expect(auditActionLabel('approval.decide', { approved: true })).toBe('Approved an action')
    expect(auditActionLabel('approval.decide', { approved: false })).toBe('Rejected an action')
    expect(auditActionLabel('approval.decide')).toBe('Decided an approval')
    expect(auditActionLabel('member.role_change')).toBe('Changed a member’s role')
  })

  it('says what started a run', () => {
    expect(startedByLabel({ trigger: 'manual', taskId: null })).toBe('Direct instruction')
    expect(startedByLabel({ trigger: 'task', taskId: 't1' }, 'Draft the rota')).toBe('Task: Draft the rota')
    expect(startedByLabel({ trigger: 'task', taskId: 't1' })).toBe('A task')
  })

  it('names media types', () => {
    expect(mediaTypeLabel('application/pdf')).toBe('PDF')
    expect(mediaTypeLabel('application/vnd.openxmlformats-officedocument.wordprocessingml.document')).toBe('Word document')
    expect(mediaTypeLabel('text/plain; charset=utf-8')).toBe('Plain text')
    expect(mediaTypeLabel('text/markdown')).toBe('Markdown')
    expect(mediaTypeLabel('text/html')).toBe('HTML')
    expect(mediaTypeLabel('text/csv', 'rota.csv')).toBe('CSV')
    expect(mediaTypeLabel('application/octet-stream', 'notes.md')).toBe('Markdown')
    expect(mediaTypeLabel(undefined, 'README')).toBe('File')
    expect(mediaTypeLabel(null)).toBe('File')
  })

  it('names tool servers and tools', () => {
    expect(serverLabel('gmail')).toBe('Gmail')
    expect(serverLabel('calendar', 'calendar')).toBe('Google Calendar')
    expect(serverLabel('drive', 'Shared drive')).toBe('Shared drive')
    expect(serverLabel('zendesk')).toBe('Zendesk')
    expect(toolLabel('gmail.send_message')).toBe('Gmail · send message')
    expect(toolLabel('github.list_issues')).toBe('GitHub · list issues')
    expect(toolLabel('')).toBe('Unknown tool')
    expect(toolLabel(null)).toBe('Unknown tool')
  })

  it('names the ask tool', () => {
    expect(serverLabel('person')).toBe('A person')
    expect(toolLabel('person.ask_question')).toBe('Asked a question')
  })

  it('names provider kinds and action classes', () => {
    expect(providerKindLabel('OPENAI_COMPATIBLE')).toBe('OpenAI-compatible')
    expect(actionClassLabel('OUTBOUND')).toBe('Leaves the workspace')
    expect(actionClassLabel('DESTRUCTIVE')).toBe('Removes something')
  })

  it('names embedding providers by registry id', () => {
    expect(embeddingProviderLabel('sandbox')).toBe('Offline sandbox')
    expect(embeddingProviderLabel('openai')).toBe('OpenAI')
    expect(embeddingProviderLabel('gemini')).toBe('Google Gemini')
    expect(embeddingProviderLabel('voyage_ai')).toBe('Voyage ai')
    expect(embeddingProviderLabel(null)).toBe('No provider')
  })

  it('says how a goal came to exist, and falls back for an unset or unknown source', () => {
    expect(goalSourceLabel('manual')).toEqual({ tone: 'neutral', label: 'Started by hand' })
    expect(goalSourceLabel('chat')).toEqual({ tone: 'blue', label: 'From Chat' })
    expect(goalSourceLabel('schedule')).toEqual({ tone: 'operations', label: 'Scheduled' })
    expect(goalSourceLabel(null)).toEqual({ tone: 'neutral', label: 'Started by hand' })
    expect(goalSourceLabel('something_new')).toEqual({ tone: 'neutral', label: 'Something new' })
  })

  it('reads a schedule as active or paused from its enabled flag', () => {
    expect(scheduleStateLabel(true)).toEqual({ tone: 'success', label: 'Active' })
    expect(scheduleStateLabel(false)).toEqual({ tone: 'neutral', label: 'Paused' })
  })
})
