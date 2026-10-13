// @find: tests for structured data, JSON-LD, FAQPage matches visible FAQ, no invented ratings or prices, no placeholder Organization, SoftwareApplication, BreadcrumbList
// @what: Tests that the JSON-LD graph names the product truthfully and that its FAQ is word for word the FAQ the home page shows.
// @flow: Tests src/seo/structuredData.ts against components/landing/sections/Faq.tsx and faqData.ts

import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Faq } from '../components/landing/sections/Faq'
import { FAQ_ITEMS } from '../components/landing/sections/faqData'
import { faqEntities, featureList, structuredDataFor } from './structuredData'
import type { JsonLdNode } from './structuredData'

const SITE = 'https://ai-workforce.bitecodes.com'

/** Every key used anywhere in a JSON value. */
function keysOf(value: unknown): string[] {
  if (Array.isArray(value)) return value.flatMap(keysOf)
  if (value && typeof value === 'object') return Object.entries(value).flatMap(([key, inner]) => [key, ...keysOf(inner)])
  return []
}

/** The node of a type in a page's graph. */
function nodeOf(graph: JsonLdNode[], type: string): JsonLdNode {
  const node = graph.find((item) => item['@type'] === type)
  if (!node) throw new Error(`No ${type} node`)
  return node
}

describe('structured data', () => {
  it('describes the site and the product on every public page', () => {
    for (const page of ['home', 'trust', 'sign-in'] as const) {
      const graph = structuredDataFor(page, SITE)['@graph']
      expect(graph.map((node) => node['@type'])).toEqual(expect.arrayContaining(['WebSite', 'SoftwareApplication']))
      const app = nodeOf(graph, 'SoftwareApplication')
      expect(app).toMatchObject({
        name: 'AI Workforce OS',
        applicationCategory: 'BusinessApplication',
        operatingSystem: 'Web browser',
        url: `${SITE}/`,
      })
    }
  })

  it('names no publisher until the owner confirms one, rather than a placeholder Organization', () => {
    for (const page of ['home', 'trust', 'sign-in'] as const) {
      const data = structuredDataFor(page, SITE)
      expect(data['@graph'].some((node) => node['@type'] === 'Organization'), page).toBe(false)
      expect(keysOf(data), page).not.toContain('publisher')
      expect(JSON.stringify(data), page).not.toContain('#organization')
    }
  })

  it('claims no price, offer, rating, review or award, since the site shows none', () => {
    for (const page of ['home', 'trust', 'sign-in'] as const) {
      const keys = keysOf(structuredDataFor(page, SITE))
      for (const invented of ['offers', 'price', 'aggregateRating', 'review', 'award', 'ratingValue']) {
        expect(keys, `${page} carries ${invented}`).not.toContain(invented)
      }
    }
  })

  it('states the connectors only as connecting once someone sets them up', () => {
    const connectors = featureList().find((feature) => feature.includes('connectors'))
    expect(connectors).toContain('connect once an owner or admin sets them up and use practice data until then')
    expect(featureList().join(' ')).not.toMatch(/certified|tested with|partner/i)
  })

  it('puts the FAQ on the home page only, word for word as the page shows it', () => {
    const home = structuredDataFor('home', SITE)['@graph']
    const faqPage = nodeOf(home, 'FAQPage')
    expect(faqPage.url).toBe(`${SITE}/`)
    expect(faqPage.mainEntity).toEqual(faqEntities(FAQ_ITEMS))

    const { container } = render(<Faq />)
    const shown = [...container.querySelectorAll('details')].map((item) => ({
      q: item.querySelector('summary')?.textContent ?? '',
      a: item.querySelector('p')?.textContent ?? '',
    }))
    const marked = (faqPage.mainEntity as JsonLdNode[]).map((question) => ({
      q: question.name,
      a: (question.acceptedAnswer as JsonLdNode).text,
    }))
    expect(marked).toEqual(shown)

    for (const page of ['trust', 'sign-in'] as const) {
      expect(structuredDataFor(page, SITE)['@graph'].some((node) => node['@type'] === 'FAQPage')).toBe(false)
    }
  })

  it('gives the technical page a breadcrumb from the home page', () => {
    const graph = structuredDataFor('trust', SITE)['@graph']
    expect(nodeOf(graph, 'WebPage').url).toBe(`${SITE}/trust`)
    expect(nodeOf(graph, 'BreadcrumbList').itemListElement).toEqual([
      { '@type': 'ListItem', position: 1, name: 'Home', item: `${SITE}/` },
      { '@type': 'ListItem', position: 2, name: 'Security and technical details', item: `${SITE}/trust` },
    ])
  })

  it('round-trips through JSON', () => {
    const graph = structuredDataFor('home', SITE)
    expect(JSON.parse(JSON.stringify(graph))).toEqual(graph)
  })
})
