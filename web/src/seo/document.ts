// @find: prerender document, compose html page, head tags, canonical tag, robots meta, open graph tags, twitter card tags, JSON-LD script, strip style attributes, CSP inline style, vite manifest, route css links, modulepreload, font preload, composeDocument
// @what: Pure string functions that turn the built index.html into each prerendered page: its head tags, its rendered markup, and the links that let it paint styled before any script runs.
// @flow: Called by src/entry-server.tsx, which scripts/prerender.mjs runs after vite build; tested by document.test.ts

import type { JsonLdGraph } from './structuredData'
import { OG_IMAGE, SITE_NAME, absoluteUrl } from '../lib/seo'
import type { PageHead } from '../lib/seo'

/*
 * The build writes each public page as finished HTML so that crawlers which do not run
 * JavaScript, most AI crawlers among them, read the same content a visitor sees. The app still
 * starts with createRoot and replaces this markup on load (main.tsx); nothing is hydrated, so the
 * markup only has to look right, not match the first client render.
 *
 * The Content-Security-Policy (infra/launcher/nginx.conf) allows no inline style, so the rendered
 * markup must carry no style attribute: React writes style props through the DOM on the client,
 * which the policy allows, but in parsed HTML they would be refused and reported. They are removed
 * here; they only set animation stagger and meter widths, which the client puts back.
 */

/** One page ready to be written: its head, the markup inside #root and the links its first paint needs. */
export type PageDocument = {
  head: PageHead
  /** The public address the absolute links in the head use. */
  site: string
  /** The rendered markup for #root, or an empty string for the app shell. */
  markup?: string
  /** Names the prerendered page on <html data-prerendered> and on the markup's wrapper. */
  page?: string
  structuredData?: JsonLdGraph | null
  /** Stylesheets the page needs before it paints (absolute paths such as /assets/x.css). */
  styles?: readonly string[]
  /** Script chunks to fetch early (absolute paths). */
  modules?: readonly string[]
  /** The typeface file to preload (an absolute path), if any. */
  font?: string | null
}

// @find: escape html, attribute escape
/** Text made safe for an HTML attribute value or element content. */
export function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** U+2028 and U+2029, which end a line in older JavaScript parsers, written as code points so no tool turns them into raw characters in this file. */
const LINE_SEPARATOR = String.fromCharCode(0x2028)
const PARAGRAPH_SEPARATOR = String.fromCharCode(0x2029)

// @find: json-ld script tag, escape json for html
/**
 * The JSON-LD graph as a script element of type application/ld+json. A data block never runs, so
 * the CSP's script-src does not apply to it. Characters that could end the element early or break
 * a line in older parsers are escaped inside the JSON.
 */
export function jsonLdScript(graph: JsonLdGraph): string {
  const json = JSON.stringify(graph)
    .replace(/</g, '\\u003c')
    .replace(/>/g, '\\u003e')
    .replace(/&/g, '\\u0026')
    .split(LINE_SEPARATOR)
    .join('\\u2028')
    .split(PARAGRAPH_SEPARATOR)
    .join('\\u2029')
  return `<script type="application/ld+json">${json}</script>`
}

// @find: strip style attributes, inline style, CSP style-src
/** The markup without any style attribute, which the CSP would refuse (see the note at the top). */
export function stripStyleAttributes(html: string): string {
  return html.replace(/\s+style="[^"]*"/g, '').replace(/\s+style='[^']*'/g, '')
}

/** True when a style attribute is left anywhere in the document. */
export function hasStyleAttribute(html: string): boolean {
  return /<[^>]*\sstyle\s*=/i.test(html)
}

/** A meta element with name= or property= and its content, escaped. */
function meta(attr: 'name' | 'property', key: string, content: string): string {
  return `<meta ${attr}="${key}" content="${escapeHtml(content)}" />`
}

// @find: seo head tags, canonical, robots, og tags, twitter tags
/** The page's head tags besides the title and description: canonical, robots, link preview and Twitter card. */
export function headTags(head: PageHead, site: string): string[] {
  const image = absoluteUrl(OG_IMAGE.path, site)
  return [
    ...(head.canonical ? [`<link rel="canonical" href="${escapeHtml(head.canonical)}" />`] : []),
    ...(head.index ? [] : [meta('name', 'robots', 'noindex')]),
    meta('property', 'og:type', 'website'),
    meta('property', 'og:site_name', SITE_NAME),
    meta('property', 'og:title', head.title),
    meta('property', 'og:description', head.description),
    ...(head.canonical ? [meta('property', 'og:url', head.canonical)] : []),
    meta('property', 'og:image', image),
    meta('property', 'og:image:width', String(OG_IMAGE.width)),
    meta('property', 'og:image:height', String(OG_IMAGE.height)),
    meta('property', 'og:image:type', OG_IMAGE.type),
    meta('property', 'og:image:alt', OG_IMAGE.alt),
    meta('name', 'twitter:card', 'summary_large_image'),
    meta('name', 'twitter:title', head.title),
    meta('name', 'twitter:description', head.description),
    meta('name', 'twitter:image', image),
    meta('name', 'twitter:image:alt', OG_IMAGE.alt),
  ]
}

/** Replaces exactly one match of `pattern` in `html`, and fails the build when the template no longer has it. */
function replaceOnce(html: string, pattern: RegExp, replacement: string, what: string): string {
  if (!pattern.test(html)) throw new Error(`index.html has no ${what}; scripts/prerender.mjs cannot place the page head`)
  return html.replace(pattern, () => replacement)
}

// @find: compose document, write prerendered page, page html
/**
 * The template (the built dist/index.html) turned into one page: its title and description, the
 * head tags, the JSON-LD, the stylesheet, preload and modulepreload links, the rendered markup in
 * #root (wrapped so styles can tell it from the app's own render) and data-prerendered on <html>.
 * Throws when a style attribute would be left, so a page the CSP would complain about never ships.
 */
export function composeDocument(template: string, doc: PageDocument): string {
  const { head } = doc
  const markup = doc.markup ? stripStyleAttributes(doc.markup) : ''
  const links = [
    ...(doc.font ? [`<link rel="preload" href="${escapeHtml(doc.font)}" as="font" type="font/woff2" crossorigin />`] : []),
    ...(doc.styles ?? []).map((href) => `<link rel="stylesheet" crossorigin href="${escapeHtml(href)}" />`),
    ...(doc.modules ?? []).map((href) => `<link rel="modulepreload" crossorigin href="${escapeHtml(href)}" />`),
  ]
  const tags = [...headTags(head, doc.site), ...(doc.structuredData ? [jsonLdScript(doc.structuredData)] : []), ...links]

  let html = template
  html = replaceOnce(html, /<title>[^<]*<\/title>/, `<title>${escapeHtml(head.title)}</title>`, '<title>')
  html = replaceOnce(
    html,
    /<meta name="description" content="[^"]*"\s*\/?>/,
    meta('name', 'description', head.description),
    'description meta',
  )
  html = replaceOnce(html, /\s*<\/head>/, `\n    ${tags.join('\n    ')}\n  </head>`, '</head>')
  if (markup) {
    const wrapper = `<div class="prerendered-page" data-page="${escapeHtml(doc.page ?? 'page')}">${markup}</div>`
    html = replaceOnce(html, /<div id="root"><\/div>/, `<div id="root">${wrapper}</div>`, 'empty root div')
  }
  if (doc.page) {
    html = replaceOnce(html, /<html lang="en">/, `<html lang="en" data-prerendered="${escapeHtml(doc.page)}">`, '<html lang="en">')
  }
  if (hasStyleAttribute(html)) throw new Error('A prerendered page still carries a style attribute, which the CSP would refuse')
  return html
}

/** One chunk of the Vite manifest (dist/.vite/manifest.json), as far as the prerender reads it. */
export type ManifestChunk = {
  file: string
  isEntry?: boolean
  imports?: string[]
  css?: string[]
}

/** The Vite manifest: source path (or "_shared-chunk.js") to chunk. */
export type ViteManifest = Record<string, ManifestChunk>

// @find: route css links, modulepreload links from manifest
/**
 * The stylesheets and script chunks a lazily loaded screen needs, read from the Vite manifest:
 * the screen's own chunk and every chunk it imports, but not the entry (index.html), whose
 * script and stylesheet the template already links. Paths come back absolute (/assets/...),
 * the form Vite's preload helper looks for, so the app does not fetch a stylesheet twice.
 */
export function chunkLinks(manifest: ViteManifest, sources: readonly string[]): { styles: string[]; modules: string[] } {
  const styles: string[] = []
  const modules: string[] = []
  const seen = new Set<string>()
  /** Adds one chunk's script and styles, then those of every chunk it imports, each once. */
  const visit = (key: string) => {
    if (seen.has(key)) return
    seen.add(key)
    const chunk = manifest[key]
    if (!chunk) throw new Error(`The Vite manifest has no chunk for ${key}`)
    if (chunk.isEntry) return
    modules.push(`/${chunk.file}`)
    for (const css of chunk.css ?? []) if (!styles.includes(`/${css}`)) styles.push(`/${css}`)
    for (const imported of chunk.imports ?? []) visit(imported)
  }
  for (const source of sources) visit(source)
  return { styles, modules }
}

// @find: font preload, inter latin woff2
/** The Latin subset of the self-hosted typeface among the built asset file names, as an absolute path, or null. */
export function latinFontFile(fileNames: readonly string[]): string | null {
  const latin = fileNames.find((name) => /^inter-latin-opsz-normal-[\w-]+\.woff2$/.test(name))
  return latin ? `/assets/${latin}` : null
}
