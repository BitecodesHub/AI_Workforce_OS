// @find: tests for robots.txt, sitemap.xml, llms.txt, llms-full.txt, crawler files match public copies, AI crawlers allowed, GEO facts
// @what: Keeps the crawler files in web/public equal to what crawlerFiles.ts generates, and checks what they allow and state.
// @flow: Tests src/seo/crawlerFiles.ts; compares with web/public/robots.txt, sitemap.xml, llms.txt and llms-full.txt (run `npx vitest run src/seo/crawlerFiles.test.ts -u` to rewrite them after a change)

import { describe, expect, it } from 'vitest'
import { FAQ_ITEMS } from '../components/landing/sections/faqData'
import { CONNECTOR_LABEL, LIVE_CONNECTORS, PROVIDERS } from '../components/landing/shared/landingFacts'
import { DEFAULT_SITE_URL, PRODUCT_DEFINITION } from '../lib/seo'
import { NAMED_CRAWLERS, countWord, isIsoDate, llmsFullTxt, llmsTxt, robotsTxt, sitemapXml } from './crawlerFiles'

/** Contractions the organisation's style rules keep out of new copy (possessives are fine). */
const CONTRACTION = /n't\b|'re\b|'ve\b|'ll\b|'m\b|\b(it|that|there|what|here|who|let)'s\b/i

/** The robots.txt groups: each set of User-agent lines with the rules under it. */
function robotsGroups(text: string): Array<{ agents: string[]; rules: string[] }> {
  const groups: Array<{ agents: string[]; rules: string[] }> = []
  let current: { agents: string[]; rules: string[] } | null = null
  for (const raw of text.split('\n')) {
    const line = raw.replace(/#.*$/, '').trim()
    if (!line) continue
    const [field, ...rest] = line.split(':')
    const value = rest.join(':').trim()
    if (field === 'User-agent') {
      if (!current || current.rules.length > 0) {
        current = { agents: [], rules: [] }
        groups.push(current)
      }
      current.agents.push(value)
    } else if (current && (field === 'Allow' || field === 'Disallow')) {
      current.rules.push(`${field}: ${value}`)
    }
  }
  return groups
}

describe('the copies in web/public', () => {
  it('equal what the generator writes for the public address', async () => {
    await expect(robotsTxt(DEFAULT_SITE_URL)).toMatchFileSnapshot('../../public/robots.txt')
    await expect(sitemapXml(DEFAULT_SITE_URL)).toMatchFileSnapshot('../../public/sitemap.xml')
    await expect(llmsTxt(DEFAULT_SITE_URL)).toMatchFileSnapshot('../../public/llms.txt')
    await expect(llmsFullTxt(DEFAULT_SITE_URL)).toMatchFileSnapshot('../../public/llms-full.txt')
  })
})

describe('robots.txt', () => {
  const text = robotsTxt(DEFAULT_SITE_URL)
  const groups = robotsGroups(text)

  it('lets every crawler read the public pages and keeps them out of the API', () => {
    expect(groups.length).toBe(2)
    for (const group of groups) {
      expect(group.rules).toEqual(['Allow: /', 'Disallow: /api/', 'Disallow: /deploy-status.json'])
    }
    expect(groups[0]!.agents).toEqual(['*'])
  })

  it('names the search and AI crawlers explicitly', () => {
    for (const bot of ['GPTBot', 'ClaudeBot', 'PerplexityBot', 'Google-Extended', 'CCBot', 'Bingbot', 'OAI-SearchBot', 'Claude-SearchBot']) {
      expect(NAMED_CRAWLERS).toContain(bot)
      expect(groups[1]!.agents).toContain(bot)
    }
  })

  it('never blocks a signed-in route, which would hide its noindex from crawlers', () => {
    for (const route of ['/agents', '/approvals', '/sign-in', '/trust', '/create-workspace']) {
      expect(text).not.toContain(`Disallow: ${route}`)
    }
  })

  it('names the sitemap by its absolute address', () => {
    expect(text.trim().split('\n').at(-1)).toBe('Sitemap: https://ai-workforce.bitecodes.com/sitemap.xml')
  })
})

describe('sitemap.xml', () => {
  it('lists the canonical home and technical pages, and nothing signed-in', () => {
    const xml = sitemapXml(DEFAULT_SITE_URL)
    const locs = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((match) => match[1])
    expect(locs).toEqual(['https://ai-workforce.bitecodes.com/', 'https://ai-workforce.bitecodes.com/trust'])
    expect(xml).not.toContain('/home<')
    expect(xml.startsWith('<?xml version="1.0" encoding="UTF-8"?>')).toBe(true)
    expect(xml).not.toContain('<lastmod>')
    expect(xml).not.toMatch(/<priority>|<changefreq>/)
  })

  it('adds lastmod only for a real date', () => {
    expect(sitemapXml(DEFAULT_SITE_URL, '2026-10-13').match(/<lastmod>2026-10-13<\/lastmod>/g)).toHaveLength(2)
    expect(sitemapXml(DEFAULT_SITE_URL, 'yesterday')).not.toContain('<lastmod>')
    expect(isIsoDate('2026-13-45')).toBe(false)
  })

  it('refuses a day that does not exist, which Date.parse would roll into the next month', () => {
    expect(isIsoDate('2026-02-30')).toBe(false)
    expect(isIsoDate('2026-04-31')).toBe(false)
    expect(isIsoDate('2025-02-29')).toBe(false)
    expect(isIsoDate('2024-02-29')).toBe(true)
    expect(sitemapXml(DEFAULT_SITE_URL, '2026-02-30')).not.toContain('<lastmod>')
  })

  it('refuses a date that has not begun anywhere yet, and accepts one that has begun in an early time zone', () => {
    const build = new Date('2026-10-13T12:00:00Z')
    expect(isIsoDate('2099-01-01', build)).toBe(false)
    expect(isIsoDate('2026-10-15', build)).toBe(false)
    expect(isIsoDate('2026-10-13', build)).toBe(true)
    // 12:00 UTC is already the 14th in Sydney and Auckland.
    expect(isIsoDate('2026-10-14', build)).toBe(true)
    expect(sitemapXml(DEFAULT_SITE_URL, '2099-01-01', build)).not.toContain('<lastmod>')
  })

  it('follows the address the build names', () => {
    expect(sitemapXml('https://example.test')).toContain('<loc>https://example.test/trust</loc>')
    expect(robotsTxt('https://example.test')).toContain('Sitemap: https://example.test/sitemap.xml')
  })
})

describe('llms.txt and llms-full.txt', () => {
  const short = llmsTxt(DEFAULT_SITE_URL)
  const full = llmsFullTxt(DEFAULT_SITE_URL)

  it('follow the llmstxt.org shape: the H1 first, a one-sentence summary, then linked sections', () => {
    expect(short.startsWith('# AI Workforce OS\n\n> ')).toBe(true)
    expect(full.startsWith('# AI Workforce OS\n\n> ')).toBe(true)
    expect(short).toContain(`> ${PRODUCT_DEFINITION}`)
    expect(short).toMatch(/\n## Pages\n\n- \[Home page\]\(https:\/\/ai-workforce\.bitecodes\.com\/\): /)
    expect(short).toContain('- [Security and technical details](https://ai-workforce.bitecodes.com/trust): ')
    expect(short).toContain('(https://ai-workforce.bitecodes.com/llms-full.txt)')
    // No repository notes for a language model to read as content.
    for (const file of [short, full]) expect(file).not.toMatch(/<!--|@find|@what|@flow/)
  })

  it('give llms.txt only link lists under its H2s, as the reference parser requires, and the key facts before them', () => {
    const [intro, ...sections] = short.split(/^## .*$/m)
    expect(sections.map((section) => section.trim().length > 0)).toEqual([true, true, true])
    for (const section of sections) {
      for (const line of section.split('\n').filter((item) => item.trim())) {
        expect(line).toMatch(/^-\s*\[[^\]]+\]\([^)]+\)(:\s*.*)?$/)
      }
    }
    // Free text before the first H2 may hold paragraphs and lists, never a heading.
    expect(intro!.split('\n').filter((line) => line.startsWith('#'))).toEqual(['# AI Workforce OS'])
    expect(intro).toContain('\nKey facts:\n\n- ')
    expect(intro).toContain('An approval nobody decides expires after 24 hours, and nothing is sent.')
  })

  it('state the facts the pages state: providers, connectors and how connectors reach real accounts', () => {
    for (const provider of PROVIDERS) expect(short).toContain(provider)
    for (const id of LIVE_CONNECTORS) expect(short).toContain(CONNECTOR_LABEL[id])
    expect(short).toContain('connect once an owner or admin sets them up, and use practice data until then')
    expect(short).toContain('An approval nobody decides expires after 24 hours, and nothing is sent.')
  })

  it('state only what the backend allows: fixed built-in roles, and approval before sends and deletions', () => {
    for (const file of [short, full]) {
      expect(file).not.toMatch(/adjust them|shape the roles|sent, posted or deleted|only for deletions/)
    }
    expect(short).toContain('The built-in roles are fixed, and owners and admins can create roles of their own.')
    expect(full).toContain('only for actions that delete something or cannot be undone, such as refunds and cancellations')
  })

  it('carry every FAQ question and answer word for word in the full text', () => {
    for (const item of FAQ_ITEMS) {
      expect(full).toContain(`#### ${item.q}\n\n${item.a}`)
    }
  })

  it('claim nothing the site does not: no ratings, prices, customers or vendor certification', () => {
    for (const text of [short, full]) {
      expect(text).not.toMatch(/\brating|\bprice|\bpricing plan|\bcustomers? (use|trust)|certified|award|\$\d/i)
      expect(text).not.toMatch(CONTRACTION)
      expect(text).not.toContain('!')
    }
  })

  it('write small counts as words', () => {
    expect(countWord(5, true)).toBe('Five')
    expect(countWord(7)).toBe('seven')
    expect(countWord(46)).toBe('46')
    expect(short).toContain('Five built-in roles')
    expect(short).toContain('works with seven AI providers')
  })
})
