// @find: tests for ready-made assistants, agent templates, TEMPLATES, templateFor, connectPrompts, AgentTemplates.java drift check, landing page examples
// @what: Unit tests that keep the template copy in step with AgentTemplates.java and the home page.
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { AGENTS } from '../components/landing/shared/landingFacts'
import { TEMPLATES, TEMPLATE_KEYS, connectPrompts, templateFor } from './templates'

/*
 * The ready-made assistants exist in three places: AgentTemplates.java (the source of truth, which
 * the platform serves), lib/templates.ts (what the console shows before it can ask the platform)
 * and landingFacts.ts (what the public home page says). They are held together here.
 *
 * Reading the Java source is crude, but it is the one way a change to the catalogue - a renamed
 * assistant, a new key - fails here instead of turning into a signup step that offers an assistant
 * the platform no longer has. A checkout without orchestrator-service skips that comparison.
 */

const CATALOGUE = join(
  __dirname,
  '../../../services/orchestrator-service/src/main/java/os/aiworkforce/orchestrator/service/AgentTemplates.java',
)

type JavaTemplate = { key: string; name: string; category: string; description: string; suggested: string[] }

/** Every `new Template("key", "Name", "category", "description", ...)` entry, with its suggested connectors. */
function catalogue(source: string): JavaTemplate[] {
  const entry =
    /new Template\(\s*"([a-z0-9-]+)",\s*"([^"]+)",\s*"([a-z]+)",\s*"([^"]+)",\s*"""[\s\S]*?""",(?:\s*\/\/[^\n]*)*\s*List\.of\(([^)]*)\)/g
  return [...source.matchAll(entry)].map((match) => ({
    key: match[1] as string,
    name: match[2] as string,
    category: match[3] as string,
    description: match[4] as string,
    suggested: [...(match[5] as string).matchAll(/"([a-z0-9-]+)"/g)].map((item) => item[1] as string),
  }))
}

describe('ready-made assistants', () => {
  it.skipIf(!existsSync(CATALOGUE))(
    'match the catalogue in AgentTemplates.java, key for key',
    () => {
      const java = catalogue(readFileSync(CATALOGUE, 'utf8'))
      expect(java.map((template) => template.key)).toEqual([...TEMPLATE_KEYS])
      for (const template of TEMPLATES) {
        const source = java.find((candidate) => candidate.key === template.key)
        expect(source, `${template.key} is not in AgentTemplates.java`).toBeDefined()
        expect(template.name).toBe(source?.name)
        expect(template.category).toBe(source?.category)
        expect(template.description).toBe(source?.description)
        expect([...template.suggestedConnectors]).toEqual(source?.suggested)
      }
    },
  )

  it('are the four the public home page describes, under the same keys and names', () => {
    expect(AGENTS.map((agent) => agent.key)).toEqual([...TEMPLATE_KEYS])
    for (const agent of AGENTS) {
      const template = templateFor(agent.key)
      expect(template, `${agent.key} is on the home page but not in the catalogue`).toBeDefined()
      expect(agent.name).toBe(template?.name)
      expect(agent.category).toBe(template?.category)
    }
  })

  it('have unique keys of the shape the platform accepts', () => {
    expect(new Set(TEMPLATE_KEYS).size).toBe(TEMPLATE_KEYS.length)
    for (const key of TEMPLATE_KEYS) expect(key).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/)
  })

  it('say what to connect, one sentence for each connector', () => {
    const hr = templateFor('hr')!
    const names: Record<string, string> = { gmail: 'Gmail', calendar: 'Google Calendar' }
    expect(connectPrompts(hr.suggestedConnectors, (id) => names[id] ?? id)).toEqual([
      'Connect Gmail to let it act.',
      'Connect Google Calendar to let it act.',
    ])
    expect(templateFor('nobody')).toBeUndefined()
  })
})
