// @find: tests for prerender document, compose html, strip style attributes, json-ld escaping, head tags, manifest links, font preload
// @what: Tests the string functions that turn the built index.html into each prerendered page.
// @flow: Tests src/seo/document.ts against web/index.html as the template

import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { privateHead, publicHead } from '../lib/seo'
import {
  chunkLinks,
  composeDocument,
  escapeHtml,
  hasStyleAttribute,
  headTags,
  jsonLdScript,
  latinFontFile,
  stripStyleAttributes,
} from './document'
import type { ViteManifest } from './document'
import { structuredDataFor } from './structuredData'

const SITE = 'https://ai-workforce.bitecodes.com'

/** web/index.html with the module script where Vite's build puts it, as dist/index.html is shaped. */
const TEMPLATE = readFileSync(join(__dirname, '..', '..', 'index.html'), 'utf8')
  .replace('    <script type="module" src="/src/main.tsx"></script>\n', '')
  .replace('  </head>', '    <script type="module" crossorigin src="/assets/index-abc.js"></script>\n  </head>')

describe('stripStyleAttributes', () => {
  it('removes every style attribute and nothing else', () => {
    const html = '<h1 id="t" style="--i:1">Hello</h1><p class="a" style=\'width:3px\'>x</p><span data-style="keep">y</span>'
    expect(stripStyleAttributes(html)).toBe('<h1 id="t">Hello</h1><p class="a">x</p><span data-style="keep">y</span>')
    expect(hasStyleAttribute(stripStyleAttributes(html))).toBe(false)
  })

  it('leaves the theme script alone, which writes style through the DOM, not an attribute', () => {
    expect(hasStyleAttribute(TEMPLATE)).toBe(false)
  })
})

describe('jsonLdScript', () => {
  it('cannot be closed early by the text it carries', () => {
    const graph = { '@context': 'https://schema.org' as const, '@graph': [{ name: '</script><script>alert(1)</script> & more' }] }
    const script = jsonLdScript(graph)
    expect(script.match(/<\/script>/g)).toHaveLength(1)
    const json = script.replace('<script type="application/ld+json">', '').replace('</script>', '')
    expect(JSON.parse(json)).toEqual(graph)
  })
})

describe('headTags', () => {
  it('gives a public page its canonical link, link preview and large-image card', () => {
    const tags = headTags(publicHead('trust', SITE), SITE).join('\n')
    expect(tags).toContain('<link rel="canonical" href="https://ai-workforce.bitecodes.com/trust" />')
    expect(tags).toContain('<meta property="og:url" content="https://ai-workforce.bitecodes.com/trust" />')
    expect(tags).toContain('<meta property="og:image" content="https://ai-workforce.bitecodes.com/og-image.png" />')
    expect(tags).toContain('<meta property="og:image:width" content="1200" />')
    expect(tags).toContain('<meta property="og:image:height" content="630" />')
    expect(tags).toMatch(/<meta property="og:image:alt" content="[^"]{20,}" \/>/)
    expect(tags).toContain('<meta name="twitter:card" content="summary_large_image" />')
    expect(tags).not.toContain('noindex')
  })

  it('marks a page that is not indexed, with no canonical link', () => {
    const tags = headTags(privateHead('Not found'), SITE).join('\n')
    expect(tags).toContain('<meta name="robots" content="noindex" />')
    expect(tags).not.toContain('rel="canonical"')
    expect(tags).not.toContain('og:url')
  })

  it('escapes what it writes into attributes', () => {
    expect(escapeHtml('a "b" <c> & d')).toBe('a &quot;b&quot; &lt;c&gt; &amp; d')
  })
})

describe('composeDocument', () => {
  const page = composeDocument(TEMPLATE, {
    head: publicHead('home', SITE),
    site: SITE,
    markup: '<main id="main"><h1 style="--i:1">Hand the busywork to AI.</h1></main>',
    page: 'home',
    structuredData: structuredDataFor('home', SITE),
    styles: ['/assets/LandingFooter-x.css'],
    modules: ['/assets/Landing-x.js'],
    font: '/assets/inter-latin-opsz-normal-x.woff2',
  })

  it('writes the page title and description over the template', () => {
    expect(page.match(/<title>/g)).toHaveLength(1)
    expect(page).toContain('<title>AI employees for your business · AI Workforce OS</title>')
    expect(page.match(/<meta name="description"/g)).toHaveLength(1)
    expect(page).toContain(`<meta name="description" content="${publicHead('home').description}" />`)
  })

  it('puts the rendered markup in #root, inside the wrapper the app replaces, without its style attributes', () => {
    expect(page).toContain(
      '<div id="root"><div class="prerendered-page" data-page="home"><main id="main"><h1>Hand the busywork to AI.</h1></main></div></div>',
    )
    expect(page).toContain('<html lang="en" data-prerendered="home">')
    expect(hasStyleAttribute(page)).toBe(false)
  })

  it('links the styles, chunks and typeface the first paint needs, in the head', () => {
    const head = page.slice(0, page.indexOf('</head>'))
    expect(head).toContain('<link rel="stylesheet" crossorigin href="/assets/LandingFooter-x.css" />')
    expect(head).toContain('<link rel="modulepreload" crossorigin href="/assets/Landing-x.js" />')
    expect(head).toContain('<link rel="preload" href="/assets/inter-latin-opsz-normal-x.woff2" as="font" type="font/woff2" crossorigin />')
    expect(head).toContain('<script type="application/ld+json">')
    // The theme script stays the first script, unchanged, so its CSP hash still holds.
    expect(head.indexOf('<script>(function(){')).toBeLessThan(head.indexOf('<script type="application/ld+json">'))
  })

  it('writes an empty shell for the app when there is no markup', () => {
    const shell = composeDocument(TEMPLATE, { head: privateHead('Loading'), site: SITE })
    expect(shell).toContain('<div id="root"></div>')
    expect(shell).toContain('<html lang="en">')
    expect(shell).toContain('<meta name="robots" content="noindex" />')
    expect(shell).not.toContain('application/ld+json')
  })

  it('fails the build when the template has lost a part it writes into', () => {
    expect(() => composeDocument('<html lang="en"><head></head><body></body></html>', { head: privateHead('x'), site: SITE })).toThrow(
      /no <title>/,
    )
  })
})

describe('chunkLinks', () => {
  const manifest: ViteManifest = {
    'index.html': { file: 'assets/index-1.js', isEntry: true, css: ['assets/index-1.css'] },
    'src/routes/Landing.tsx': { file: 'assets/Landing-1.js', imports: ['index.html', '_shared-1.js'] },
    'src/routes/Trust.tsx': { file: 'assets/Trust-1.js', imports: ['index.html', '_shared-1.js'], css: ['assets/Trust-1.css'] },
    '_shared-1.js': { file: 'assets/shared-1.js', imports: ['index.html'], css: ['assets/shared-1.css'] },
  }

  it('collects the styles and chunks of a screen and what it imports, but not the entry', () => {
    expect(chunkLinks(manifest, ['src/routes/Landing.tsx'])).toEqual({
      styles: ['/assets/shared-1.css'],
      modules: ['/assets/Landing-1.js', '/assets/shared-1.js'],
    })
    expect(chunkLinks(manifest, ['src/routes/Trust.tsx']).styles).toEqual(['/assets/Trust-1.css', '/assets/shared-1.css'])
  })

  it('fails when the manifest has no such screen', () => {
    expect(() => chunkLinks(manifest, ['src/routes/Gone.tsx'])).toThrow(/no chunk/)
  })
})

describe('latinFontFile', () => {
  it('picks the Latin subset of the typeface, not Latin Extended', () => {
    const files = ['inter-latin-ext-opsz-normal-a.woff2', 'inter-latin-opsz-normal-B_c-1.woff2', 'index-1.js']
    expect(latinFontFile(files)).toBe('/assets/inter-latin-opsz-normal-B_c-1.woff2')
    expect(latinFontFile(['index-1.js'])).toBeNull()
  })
})
