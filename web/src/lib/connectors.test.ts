// @find: tests for connectors, capability groups, asks first, grant tools, call limit, connector state, OAuth return
// @what: Unit tests for connector rules and wording.
import { describe, expect, it } from 'vitest'
import {
  CAPABILITY_GROUPS,
  agentsUsing,
  asksFirst,
  capabilityGroup,
  capabilityLabel,
  connectorCategory,
  connectorState,
  connectorSummary,
  defaultSelection,
  grantTools,
  grantedToolNames,
  groupCapabilities,
  initialSelection,
  parseCallLimit,
  placeFieldProblems,
  webAddressProblem,
  type CapabilityTool,
} from './connectors'
import { connectorCategoryLabel, connectorStateLabel, serverLabel } from './labels'

const TOOLS: CapabilityTool[] = [
  { name: 'send_message', sideEffect: 'OUTBOUND', alwaysRequiresApproval: true },
  { name: 'list_issues', sideEffect: 'READ', alwaysRequiresApproval: false },
  { name: 'delete_branch', sideEffect: 'DESTRUCTIVE', alwaysRequiresApproval: true },
  { name: 'create_issue', sideEffect: 'WRITE', alwaysRequiresApproval: false },
  { name: 'get_issue', sideEffect: 'READ', alwaysRequiresApproval: false },
  { name: 'merge_pull_request', sideEffect: 'WRITE', alwaysRequiresApproval: true },
]

describe('capability grouping', () => {
  it('maps each side-effect class to its plain-words group', () => {
    expect(capabilityGroup({ sideEffect: 'READ' })).toBe('reads')
    expect(capabilityGroup({ sideEffect: 'WRITE' })).toBe('edits')
    expect(capabilityGroup({ sideEffect: 'OUTBOUND' })).toBe('sends')
    expect(capabilityGroup({ sideEffect: 'DESTRUCTIVE' })).toBe('deletes')
    expect(capabilityGroup({ sideEffect: 'outbound' })).toBe('sends')
  })

  it('never presents a tool of unknown effect as only reading', () => {
    expect(capabilityGroup({ sideEffect: 'SOMETHING_NEW' })).toBe('edits')
  })

  it('groups tools in the safest-first order, keeping each group in the connector order', () => {
    const groups = groupCapabilities(TOOLS)
    expect(groups.map((group) => group.label)).toEqual([
      'Reads',
      'Creates and edits',
      'Sends — asks first',
      'Deletes — asks first',
    ])
    expect(groups[0]!.tools.map((tool) => tool.name)).toEqual(['list_issues', 'get_issue'])
    expect(groups[1]!.tools.map((tool) => tool.name)).toEqual(['create_issue', 'merge_pull_request'])
    expect(groups[2]!.tools.map((tool) => tool.name)).toEqual(['send_message'])
    expect(groups[3]!.tools.map((tool) => tool.name)).toEqual(['delete_branch'])
  })

  it('leaves out a group with nothing in it', () => {
    const groups = groupCapabilities(TOOLS.filter((tool) => tool.sideEffect === 'READ'))
    expect(groups.map((group) => group.key)).toEqual(['reads'])
    expect(groupCapabilities([])).toEqual([])
  })

  it('marks only sending and deleting as always asking first', () => {
    expect(CAPABILITY_GROUPS.filter((group) => group.asksFirst).map((group) => group.key)).toEqual(['sends', 'deletes'])
  })

  it('asks first for sending, deleting, a flagged tool, and everything when the grant says so', () => {
    const [send, read, remove, write, , flaggedWrite] = TOOLS
    expect(asksFirst(send!)).toBe(true)
    expect(asksFirst(remove!)).toBe(true)
    expect(asksFirst(flaggedWrite!)).toBe(true)
    expect(asksFirst(read!)).toBe(false)
    expect(asksFirst(write!)).toBe(false)
    expect(asksFirst(read!, true)).toBe(true)
    // Even unflagged, the platform parks every outbound or destructive call.
    expect(asksFirst({ name: 'post', sideEffect: 'OUTBOUND' })).toBe(true)
  })

  it('names a capability in plain words', () => {
    expect(capabilityLabel({ name: 'list_issues' })).toBe('List issues')
    expect(capabilityLabel({ name: 'createPage' })).toBe('Create page')
  })
})

describe('grant selection', () => {
  it('starts a new grant with the reads ticked and nothing else', () => {
    expect(defaultSelection(TOOLS)).toEqual(['list_issues', 'get_issue'])
    expect(initialSelection(null, TOOLS)).toEqual(['list_issues', 'get_issue'])
  })

  it('starts an existing grant from its own tools, and an empty list as every tool', () => {
    expect(initialSelection({ tools: ['send_message', 'get_issue'] }, TOOLS)).toEqual(['send_message', 'get_issue'])
    expect(initialSelection({ tools: [] }, TOOLS)).toEqual(TOOLS.map((tool) => tool.name))
    // A tool the connector no longer offers is not carried forward.
    expect(grantedToolNames({ tools: ['list_issues', 'retired_tool'] }, TOOLS)).toEqual(['list_issues'])
  })

  it('saves an explicit list in the connector order, even when everything is ticked', () => {
    expect(grantTools(['get_issue', 'list_issues'], TOOLS)).toEqual(['list_issues', 'get_issue'])
    const everything = grantTools([...TOOLS].reverse().map((tool) => tool.name), TOOLS)
    expect(everything).toEqual(TOOLS.map((tool) => tool.name))
    expect(everything).not.toHaveLength(0)
  })

  it('reads the call limit as empty, a whole number from 1 to 200, or invalid', () => {
    expect(parseCallLimit('')).toBeNull()
    expect(parseCallLimit('  ')).toBeNull()
    expect(parseCallLimit('20')).toBe(20)
    expect(parseCallLimit('1')).toBe(1)
    expect(parseCallLimit('200')).toBe(200)
    expect(parseCallLimit('0')).toBe(false)
    expect(parseCallLimit('201')).toBe(false)
    expect(parseCallLimit('2.5')).toBe(false)
    expect(parseCallLimit('-3')).toBe(false)
  })
})

describe('connector state', () => {
  const base = { server: 'github', status: 'sandbox', sandbox: true, reconnectRequired: false }

  it('is one of sandbox, connected or needs attention', () => {
    expect(connectorState(base)).toBe('sandbox')
    expect(connectorState({ ...base, status: 'connected', sandbox: false })).toBe('connected')
    expect(connectorState({ ...base, status: 'connected', sandbox: false, lastError: 'Bad credentials' })).toBe('attention')
    expect(connectorState({ ...base, status: 'error', sandbox: false })).toBe('attention')
    expect(connectorState({ ...base, status: 'connected', sandbox: false, reconnectRequired: true })).toBe('attention')
  })

  it('names each state for a person', () => {
    expect(connectorStateLabel('sandbox')).toEqual({ tone: 'neutral', label: 'Sandbox' })
    expect(connectorStateLabel('connected')).toEqual({ tone: 'success', label: 'Connected' })
    expect(connectorStateLabel('attention')).toEqual({ tone: 'warning', label: 'Needs attention' })
  })

  it('takes the catalog category, else a known one for older services, else other', () => {
    expect(connectorCategory({ server: 'github', category: 'Engineering' })).toBe('engineering')
    expect(connectorCategory({ server: 'gmail' })).toBe('communication')
    expect(connectorCategory({ server: 'somewhere-new', category: null })).toBe('other')
    expect(connectorCategoryLabel('sales')).toBe('Sales and CRM')
    expect(connectorCategoryLabel(undefined)).toBe('Other')
    expect(serverLabel('hubspot')).toBe('HubSpot')
  })

  it('lists the agents that use a connector, by name', () => {
    const agents = [
      { name: 'Support desk', tools: ['zendesk', 'gmail'] },
      { name: 'Account manager', tools: ['gmail'] },
      { name: 'Builder', tools: ['github'] },
      { name: 'General employee', tools: null },
    ]
    expect(agentsUsing('gmail', agents).map((agent) => agent.name)).toEqual(['Account manager', 'Support desk'])
    expect(agentsUsing('slack', agents)).toEqual([])
    expect(agentsUsing('gmail', undefined)).toEqual([])
  })
})

import { isHttpsUrl, parseOAuthReturn, usesSignIn, withoutOAuthReturn } from './connectors'

describe('signing in through a provider', () => {
  it('reads the redirect parameters as plain text', () => {
    expect(parseOAuthReturn(new URLSearchParams('connected=gmail'))).toEqual({ connected: 'gmail', error: null })
    expect(parseOAuthReturn(new URLSearchParams('error=Access%20was%20denied.'))).toEqual({
      connected: null,
      error: 'Access was denied.',
    })
    expect(parseOAuthReturn(new URLSearchParams('connected=&error=%20'))).toEqual({ connected: null, error: null })
    expect(parseOAuthReturn(new URLSearchParams({ error: 'x'.repeat(500) })).error).toHaveLength(300)
  })

  it('removes only the redirect parameters from the address', () => {
    expect(withoutOAuthReturn(new URLSearchParams('connected=gmail&q=mail'))).toBe('?q=mail')
    expect(withoutOAuthReturn(new URLSearchParams('error=no'))).toBe('')
  })

  it('accepts only https addresses', () => {
    expect(isHttpsUrl('https://accounts.example.test/auth?x=1')).toBe(true)
    expect(isHttpsUrl('http://accounts.example.test')).toBe(false)
    expect(isHttpsUrl('javascript:alert(1)')).toBe(false)
    expect(isHttpsUrl('not a url')).toBe(false)
    expect(isHttpsUrl(null)).toBe(false)
  })

  it('knows when a connector signs in', () => {
    expect(usesSignIn({ authType: 'oauth', oauth: { provider: 'google' } })).toBe(true)
    expect(usesSignIn({ authType: 'oauth', oauth: null })).toBe(false)
    expect(usesSignIn({ authType: 'token' })).toBe(false)
  })
})

describe('webAddressProblem', () => {
  it('accepts a full http or https address and leaves the rest of the rules to the service', () => {
    expect(webAddressProblem('https://hooks.example.test/a')).toBeNull()
    expect(webAddressProblem(' http://localhost:9000/hook ')).toBeNull()
  })

  it('names what is wrong with a blank, malformed or non-web address', () => {
    expect(webAddressProblem('  ')).toBe('Enter the web address that should receive events.')
    expect(webAddressProblem('hooks example')).toMatch(/not a valid web address/)
    expect(webAddressProblem('ftp://files.example.test')).toBe('Enter a full web address that starts with https://.')
  })
})

describe('placeFieldProblems', () => {
  const fields = [
    { key: 'site', label: 'Atlassian site' },
    { key: 'token', label: 'API token' },
  ]

  it('puts a validation problem under the field it names, reading after the label', () => {
    expect(placeFieldProblems({ code: 'validation_failed', message: 'x', fields: { site: 'must not be empty' } }, fields)).toEqual({
      inline: { site: 'Atlassian site must not be empty.' },
      unplaced: false,
    })
  })

  it('maps a service name onto a form field and keeps a whole sentence as it is', () => {
    const placed = placeFieldProblems(
      { code: 'validation_failed', message: 'x', fields: { settings: 'The tenant must be common.' } },
      [{ key: 'setting:tenant', label: 'Tenant' }],
      { settings: 'setting:tenant' },
    )
    expect(placed).toEqual({ inline: { 'setting:tenant': 'The tenant must be common.' }, unplaced: false })
  })

  it('leaves a field the form does not have for the whole form', () => {
    expect(placeFieldProblems({ code: 'validation_failed', message: 'x', fields: { accountLabel: 'is too long' } }, fields)).toEqual({
      inline: {},
      unplaced: true,
    })
  })

  it('puts a failed check under the only field, and nowhere in particular when there are several', () => {
    const failure = { code: 'connector_check_failed', message: 'Not accepted', fields: { server: 'github' } }
    expect(placeFieldProblems(failure, [{ key: 'token', label: 'Token' }])).toEqual({ inline: { token: 'Not accepted.' }, unplaced: false })
    expect(placeFieldProblems(failure, fields)).toEqual({ inline: {}, unplaced: true })
  })
})

describe('connectorSummary', () => {
  const base = { status: 'sandbox', sandbox: true, liveAvailable: true }
  const live = { server: 'gmail', status: 'connected', sandbox: false }
  const broken = { server: 'slack', status: 'error', sandbox: false }

  it('does not count a built-in connector such as Voice notes as practice data', () => {
    const voiceNotes = { server: 'voice', status: 'sandbox', sandbox: true, authType: 'none' }
    expect(connectorState(voiceNotes)).toBe('builtin')
    const result = connectorSummary([live, voiceNotes, { ...base, server: 'jira' }])
    expect(result.short).toBe('1 live · 1 practice')
  })

  it('counts live, practice and attention, with short words for the pill', () => {
    const result = connectorSummary([live, broken, { ...base, server: 'jira' }, { ...base, server: 'zoom' }])
    expect(result).toMatchObject({ live: 1, practice: 2, attention: 1, short: '1 live · 2 practice' })
    expect(result.sentence).toBe(
      '1 connected to a live account, 1 needs attention. The other 2 run on practice data in a sandbox, so nothing an agent does through them leaves this workspace.',
    )
  })

  it('says everything is on practice data when nothing is live', () => {
    const result = connectorSummary([{ ...base, server: 'jira' }, { ...base, server: 'zoom', liveAvailable: false }])
    expect(result.short).toBe('All on practice data')
    expect(result.sentence).toContain('nothing an agent does through them leaves this workspace')
    expect(result.sentence).toContain('1 of them can be connected to a live account')
  })

  it('leaves out the practice sentence when every connector is live', () => {
    expect(connectorSummary([live]).sentence).toBe('1 connected to a live account.')
  })
})
