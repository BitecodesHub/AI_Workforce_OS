import { describe, expect, it } from 'vitest'
import { ApiError } from './api'
import {
  bulkSummary,
  contextLine,
  decisionError,
  groupIdentical,
  isDestructive,
  parsePayload,
  payloadHeadline,
  previewCaption,
  previewOpensByDefault,
  previewToggleLabel,
  readableSummary,
  runOutcome,
} from './approvals'

/*
 * What an approver is told after deciding. Approving hands the run back to its agent in the
 * background, so the usual answer is that it is carrying on, not where it ended.
 */

describe('runOutcome', () => {
  it('says the agent is continuing when the run carries on in the background', () => {
    expect(runOutcome('running')).toBe('Approved. The agent is continuing; open the run to follow along.')
    expect(runOutcome('RUNNING')).toBe('Approved. The agent is continuing; open the run to follow along.')
  })

  it('still reads a run that has already settled', () => {
    expect(runOutcome('cancelled')).toBe('The run was stopped.')
    expect(runOutcome('waiting_approval')).toBe('The run is waiting for another approval.')
    expect(runOutcome(null)).toBe('Open the run to see where it stands.')
  })
})

describe('decisionError', () => {
  it('tells an approver whose request timed out that the decision was still recorded', () => {
    const timedOut = new ApiError(504, 'gateway_timeout', 'Gateway Timeout', true, {})
    expect(decisionError(timedOut)).toBe(
      'The decision was recorded. The agent is still working; the run will update shortly.',
    )
  })

  it('keeps the specific answers for a request someone else decided', () => {
    const decided = new ApiError(409, 'approval_already_decided', 'Already decided', false, {})
    expect(decisionError(decided)).toBe('Someone already decided this request.')
  })
})

describe('readableSummary', () => {
  const sentence = 'Send something outside the workspace using gmail.send_message'

  it('swaps the tool the gateway names for its label', () => {
    expect(readableSummary({ tool: 'gmail.send_message', summary: sentence })).toBe(
      'Send something outside the workspace using Gmail · send message',
    )
  })

  it('reads the server__tool form a model is shown, whichever way the summary or the tool is written', () => {
    expect(
      readableSummary({ tool: 'gmail__send_message', summary: 'Send something outside the workspace using gmail__send_message' }),
    ).toBe('Send something outside the workspace using Gmail · send message')
    // The tool qualified one way, the sentence the other.
    expect(readableSummary({ tool: 'gmail.send_message', summary: 'Send using gmail__send_message' })).toBe(
      'Send using Gmail · send message',
    )
    expect(readableSummary({ tool: 'slack__post_message', summary: 'Post using slack.post_message' })).toBe(
      'Post using Slack · post message',
    )
  })

  it('leaves a sentence alone when there is no tool, or the tool is not in it', () => {
    expect(readableSummary({ tool: null, summary: sentence })).toBe(sentence)
    expect(readableSummary({ summary: sentence })).toBe(sentence)
    expect(readableSummary({ tool: 'stripe.refund_payment', summary: 'Send an email' })).toBe('Send an email')
  })
})

describe('what a request does', () => {
  it('captions and opens by what it does to the world', () => {
    expect(previewCaption('OUTBOUND')).toBe('Exactly what will be sent')
    expect(previewCaption('destructive')).toBe('Exactly what will be removed')
    expect(previewCaption('WRITE')).toBe('Exactly what will change')
    expect(previewCaption(null)).toBe('Exactly what will change')
    expect(previewToggleLabel('OUTBOUND')).toBe('Show what will be sent')
    expect(previewToggleLabel('DESTRUCTIVE')).toBe('Show what will be removed')
    expect(previewToggleLabel('WRITE')).toBe('Show what will change')
    expect(previewOpensByDefault('OUTBOUND')).toBe(true)
    expect(previewOpensByDefault('DESTRUCTIVE')).toBe(true)
    expect(previewOpensByDefault('WRITE')).toBe(false)
    expect(isDestructive('DESTRUCTIVE')).toBe(true)
    expect(isDestructive('OUTBOUND')).toBe(false)
  })
})

describe('parsePayload', () => {
  it('lists every top-level key, the known ones first in reading order', () => {
    const parsed = parsePayload(JSON.stringify({ attachments: ['a.pdf'], body: 'hi', To: 'x@y.example', subject: 's' }))

    expect(parsed.kind).toBe('fields')
    if (parsed.kind !== 'fields') return
    expect(parsed.known.map((row) => row.label)).toEqual(['To', 'Subject', 'Body'])
    expect(parsed.other).toEqual([{ key: 'attachments', label: 'Attachments', value: '["a.pdf"]' }])
  })

  it('writes text as it is and everything else as compact JSON', () => {
    const parsed = parsePayload(JSON.stringify({ text: 'a\nb', amount: 12.5, tags: { x: [1, 2] }, flag: true, nothing: null }))

    if (parsed.kind !== 'fields') throw new Error('expected fields')
    const values = Object.fromEntries([...parsed.known, ...parsed.other].map((row) => [row.key, row.value]))
    expect(values).toEqual({ text: 'a\nb', amount: '12.5', tags: '{"x":[1,2]}', flag: 'true', nothing: 'null' })
  })

  it('gives an unknown key a plain label', () => {
    const parsed = parsePayload(JSON.stringify({ reply_to: 'a', replyToAddress: 'b', 'x-request-id': 'c' }))

    if (parsed.kind !== 'fields') throw new Error('expected fields')
    expect(parsed.other.map((row) => row.label)).toEqual(['Reply to', 'Reply to address', 'X request id'])
  })

  it('falls back to the text when it is not an object', () => {
    expect(parsePayload('not json')).toEqual({ kind: 'raw', text: 'not json' })
    expect(parsePayload('[1,2]')).toEqual({ kind: 'raw', text: '[\n  1,\n  2\n]' })
    expect(parsePayload('null')).toMatchObject({ kind: 'raw' })
    expect(parsePayload('{}')).toEqual({ kind: 'fields', known: [], other: [] })
  })

  it('puts the first fields on one line to tell requests that read alike apart', () => {
    expect(payloadHeadline(JSON.stringify({ to: 'jane@x.example', subject: 'Welcome', body: 'long' }))).toBe(
      'To: jane@x.example · Subject: Welcome',
    )
    expect(payloadHeadline('plain words')).toBe('plain words')
    expect(payloadHeadline('{}')).toBe('')
  })
})

describe('contextLine', () => {
  const nameOf = (id: string) => (id === 'u-2' ? 'Priya Shah' : 'Someone')

  it('says what the work is for and who asked', () => {
    expect(contextLine({ goalTitle: 'Welcome the new starter', requestedBy: 'u-2' }, 'u-1', nameOf)).toBe(
      'For: Welcome the new starter · asked by Priya Shah',
    )
    expect(contextLine({ goalTitle: 'Welcome the new starter', requestedBy: 'u-1' }, 'u-1', nameOf)).toBe(
      'For: Welcome the new starter · asked by You',
    )
  })

  it('names a direct run and leaves out a person nobody asked', () => {
    expect(contextLine({ goalTitle: null, requestedBy: 'u-2' }, null, nameOf)).toBe(
      'For: a direct instruction · asked by Priya Shah',
    )
    expect(contextLine({ goalTitle: 'Daily summary', requestedBy: null }, 'u-1', nameOf)).toBe('For: Daily summary')
  })
})

describe('groupIdentical', () => {
  const row = (id: string, agentId: string, tool: string | null, summary: string) => ({ id, agentId, tool, summary })

  it('groups the same agent, tool and summary, in order of first appearance, and drops the single ones', () => {
    const groups = groupIdentical([
      row('1', 'a', 'slack.post_message', 'Post the standup'),
      row('2', 'a', 'gmail.send_message', 'Send the report'),
      row('3', 'a', 'slack.post_message', 'Post the standup'),
      row('4', 'b', 'slack.post_message', 'Post the standup'),
      row('5', 'a', 'slack.post_message', 'Post the standup'),
    ])

    expect(groups).toHaveLength(1)
    expect(groups[0]?.items.map((item) => item.id)).toEqual(['1', '3', '5'])
  })

  it('does not run two different words together into one group', () => {
    expect(groupIdentical([row('1', 'a', 'x.y', 'p q'), row('2', 'a', 'x', 'y p q')])).toEqual([])
  })
})

describe('bulkSummary', () => {
  it('says what was decided, then what could not be', () => {
    expect(
      bulkSummary(
        [
          { result: 'decided' },
          { result: 'decided' },
          { result: 'already_decided' },
          { result: 'expired' },
          { result: 'forbidden' },
          { result: 'not_found' },
        ],
        true,
      ),
    ).toBe(
      'Approved 2. 1 request was already decided by someone else. 1 request expired before a decision was made. 1 request was not yours to decide. 1 request could not be found.',
    )
  })

  it('says plainly when nothing was decided', () => {
    expect(bulkSummary([{ result: 'already_decided' }, { result: 'already_decided' }], false)).toBe(
      'Nothing was rejected. 2 requests were already decided by someone else.',
    )
    expect(bulkSummary([{ result: 'decided' }], false)).toBe('Rejected 1.')
  })
})

describe('decisionError, four eyes', () => {
  it('passes on the server sentence when the requester may not decide', () => {
    const refused = new ApiError(403, 'policy_violation', 'Someone other than the requester must decide this', false, {})
    expect(decisionError(refused)).toBe('Someone other than the requester must decide this.')
  })

  it('still reads a missing permission as one', () => {
    const denied = new ApiError(403, 'permission_denied', 'No', false, {})
    expect(decisionError(denied)).toBe('Deciding this request needs a permission your role does not have.')
  })
})
