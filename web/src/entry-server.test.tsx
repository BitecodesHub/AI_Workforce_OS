// @vitest-environment node
// @find: tests for entry server, prerender under node, server rendering without window, public pages html, crawlers see content, FAQ in html, JSON-LD parses
// @what: Renders every prerendered page under plain Node (no window, no document) and checks each one carries its content, its head and valid structured data.
// @flow: Tests src/entry-server.tsx with web/index.html as the template and a small Vite manifest

import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { FAQ_ITEMS } from './components/landing/sections/faqData'
import { HERO_LEAD } from './components/landing/hero/Hero'
import { buildCrawlerFiles, buildPages } from './entry-server'
import type { ViteManifest } from './seo/document'
import { hasStyleAttribute } from './seo/document'

/*
 * This file runs in Node's own environment, not jsdom, so a screen that touches window or
 * document while rendering fails here exactly as it would fail the build.
 */

const TEMPLATE = readFileSync(join(__dirname, '..', 'index.html'), 'utf8')
  .replace('    <script type="module" src="/src/main.tsx"></script>\n', '')
  .replace('  </head>', '    <script type="module" crossorigin src="/assets/index-abc.js"></script>\n  </head>')

const MANIFEST: ViteManifest = {
  'index.html': { file: 'assets/index-abc.js', isEntry: true, css: ['assets/index-abc.css'] },
  'src/routes/Landing.tsx': { file: 'assets/Landing-1.js', imports: ['index.html', '_LandingFooter-1.js'] },
  'src/routes/Trust.tsx': { file: 'assets/Trust-1.js', imports: ['index.html', '_LandingFooter-1.js'] },
  '_LandingFooter-1.js': { file: 'assets/LandingFooter-1.js', imports: ['index.html'], css: ['assets/LandingFooter-1.css'] },
}

const pages = buildPages({ template: TEMPLATE, manifest: MANIFEST, font: '/assets/inter-latin-opsz-normal-1.woff2' })

/** The text inside <body>, tags removed and entities decoded, as a crawler that does not run JavaScript reads it. */
function bodyText(html: string): string {
  return html
    .slice(html.indexOf('<body>'))
    .replace(/<[^>]+>/g, ' ')
    .replace(/&#x27;|&#39;/g, "'")
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
}

/** The parsed JSON-LD graphs in a page. */
function jsonLd(html: string): Array<{ '@graph': Array<Record<string, unknown>> }> {
  return [...html.matchAll(/<script type="application\/ld\+json">([^<]*)<\/script>/g)].map((match) => JSON.parse(match[1]!))
}

describe('the prerendered pages', () => {
  it('renders under Node without a browser', () => {
    expect(typeof window).toBe('undefined')
    expect(Object.keys(pages).sort()).toEqual(['404.html', 'app.html', 'index.html', 'sign-in.html', 'trust.html'])
  })

  it('gives the home page its words, one h1 and every FAQ answer in the HTML', () => {
    const html = pages['index.html']
    expect(html.match(/<h1[\s>]/g)).toHaveLength(1)
    const text = bodyText(html)
    expect(text).toContain('Hand the busywork to AI.')
    expect(text).toContain(HERO_LEAD)
    for (const item of FAQ_ITEMS) {
      expect(text).toContain(item.q)
      expect(text).toContain(item.a)
    }
    expect(html).toContain('<title>AI employees for your business · AI Workforce OS</title>')
    expect(html).toContain('<link rel="canonical" href="https://ai-workforce.bitecodes.com/" />')
    expect(html).toContain('<html lang="en" data-prerendered="home">')
  })

  it('gives the technical page its own head, words and breadcrumb', () => {
    const html = pages['trust.html']
    expect(html.match(/<h1[\s>]/g)).toHaveLength(1)
    expect(bodyText(html)).toContain('How it keeps agents in check')
    expect(bodyText(html)).toContain('An approval nobody decides expires after 24 hours')
    expect(html).toContain('<title>Security and technical details · AI Workforce OS</title>')
    expect(html).toContain('<link rel="canonical" href="https://ai-workforce.bitecodes.com/trust" />')
    expect(jsonLd(html)[0]!['@graph'].map((node) => node['@type'])).toContain('BreadcrumbList')
  })

  it('renders the sign-in form without asking the network', () => {
    const html = pages['sign-in.html']
    expect(bodyText(html)).toContain('Welcome back')
    expect(html).toContain('<link rel="canonical" href="https://ai-workforce.bitecodes.com/sign-in" />')
  })

  it('marks the not-found page and the app shell noindex, with no canonical link', () => {
    for (const file of ['404.html', 'app.html'] as const) {
      expect(pages[file]).toContain('<meta name="robots" content="noindex" />')
      expect(pages[file]).not.toContain('rel="canonical"')
      expect(pages[file]).not.toContain('application/ld+json')
    }
    expect(bodyText(pages['404.html'])).toContain('There is nothing at this address')
    expect(pages['app.html']).toContain('<div id="root"></div>')
    expect(pages['app.html']).toContain('<title>AI Workforce OS</title>')
  })

  it('carries no style attribute the CSP would refuse, and keeps the app script', () => {
    for (const [file, html] of Object.entries(pages)) {
      expect(hasStyleAttribute(html), file).toBe(false)
      expect(html, file).toContain('<script type="module" crossorigin src="/assets/index-abc.js"></script>')
      // The theme script is still exactly one inline script without attributes.
      expect(html.match(/<script>/g), file).toHaveLength(1)
    }
  })

  it('links each landing page to the styles of its lazily loaded screen', () => {
    for (const file of ['index.html', 'trust.html'] as const) {
      expect(pages[file]).toContain('<link rel="stylesheet" crossorigin href="/assets/LandingFooter-1.css" />')
    }
    expect(pages['index.html']).toContain('<link rel="modulepreload" crossorigin href="/assets/Landing-1.js" />')
    expect(pages['sign-in.html']).not.toContain('LandingFooter')
  })

  it('carries JSON-LD that parses, with the FAQ only on the home page', () => {
    /** The JSON-LD node types of one page, in order. */
    const types = (file: keyof typeof pages) => jsonLd(pages[file]).flatMap((graph) => graph['@graph'].map((node) => node['@type']))
    expect(types('index.html')).toEqual(['Organization', 'WebSite', 'SoftwareApplication', 'FAQPage'])
    expect(types('trust.html')).not.toContain('FAQPage')
    expect(types('sign-in.html')).toContain('WebPage')
  })
})

describe('the crawler files', () => {
  it('are written for the address the build names', () => {
    const files = buildCrawlerFiles('https://example.test', '2026-10-13')
    expect(Object.keys(files).sort()).toEqual(['llms-full.txt', 'llms.txt', 'robots.txt', 'sitemap.xml'])
    expect(files['robots.txt']).toContain('Sitemap: https://example.test/sitemap.xml')
    expect(files['sitemap.xml']).toContain('<lastmod>2026-10-13</lastmod>')
    expect(files['llms.txt']).toContain('(https://example.test/trust)')
  })
})
