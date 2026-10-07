import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { CONNECTOR_COUNT, CONNECTOR_LABEL, CONNECTOR_NOTICE, LIVE_CONNECTORS, PRACTICE_CONNECTORS, listOf } from './landingFacts'

/*
 * The connector lists the public pages state, checked against the catalogue they copy.
 *
 * mcp-core's ConnectorCatalog.java decides which connectors can reach a real account; the pages
 * only repeat it. Reading the Java source is crude, but it is the one way a change there - a
 * connector going live, or a new practice one - fails here instead of turning into a claim the
 * product does not keep. A checkout without mcp-core skips the comparison.
 */

const CATALOG = join(
  __dirname,
  '../../../../../mcp-core/src/main/java/os/aiworkforce/mcp/catalog/ConnectorCatalog.java',
)

/** Every `new ConnectorInfo("id", "name", "category", "description", "auth", liveAvailable` entry. */
function catalogue(source: string): { live: string[]; other: string[]; practice: string[] } {
  const live: string[] = []
  const other: string[] = []
  const entry = /new ConnectorInfo\(\s*"([a-z0-9-]+)",\s*"[^"]*",\s*"[^"]*",\s*"[^"]*",\s*"[^"]*",\s*(true|false)/g
  // Direct entries, then the token(...) and oauth(...) helpers, which are always live.
  for (const match of source.matchAll(entry)) {
    ;(match[2] === 'true' ? live : other).push(match[1] as string)
  }
  for (const match of source.matchAll(/^\s+(?:token|oauth)\(\s*"([a-z0-9-]+)"/gm)) live.push(match[1] as string)
  const practice = [...source.matchAll(/practice\(\s*"([a-z0-9-]+)"/g)].map((match) => match[1] as string)
  return { live, other, practice }
}

describe('connector facts', () => {
  it.skipIf(!existsSync(CATALOG))('match the live and practice-only connectors in ConnectorCatalog.java', () => {
    const { live, other, practice } = catalogue(readFileSync(CATALOG, 'utf8'))
    expect(live).toEqual([...LIVE_CONNECTORS])
    expect(practice).toEqual([...PRACTICE_CONNECTORS])
    // Voice notes are listed there too, but connect to nothing outside the workspace.
    expect(other).toEqual(['voice'])
  })

  it('label every connector and count the two lists once each', () => {
    const all = [...LIVE_CONNECTORS, ...PRACTICE_CONNECTORS]
    expect(new Set(all).size).toBe(all.length)
    expect(CONNECTOR_COUNT).toBe(19)
    for (const id of all) expect(CONNECTOR_LABEL[id]).toBeTruthy()
  })


  it('give the home page a connector sentence that promises nothing live', () => {
    expect(CONNECTOR_NOTICE).toMatch(/practice mode/)
    expect(CONNECTOR_NOTICE).not.toMatch(/live|your account|connects? to/i)
  })

  it('join a list the way the sentences need', () => {
    expect(listOf([])).toBe('')
    expect(listOf(['Slack'])).toBe('Slack')
    expect(listOf(['Slack', 'GitHub', 'Notion'])).toBe('Slack, GitHub and Notion')
  })
})
