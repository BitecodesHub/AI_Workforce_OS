// @find: tests for nginx routes, app shell allow-list, prerendered page locations, real 404, X-Robots-Tag noindex, security headers kept, route drift between App.tsx and nginx.conf
// @what: Holds infra/launcher/nginx.conf to the app's route table and to the files the prerender writes, so a new screen cannot answer 404 and a public page cannot lose its HTML.
// @flow: Reads infra/launcher/nginx.conf; compares with routePatterns() in App.tsx, redirectFor in lib/router.tsx and the pages src/entry-server.tsx writes

import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { routePatterns } from '../App'
import { redirectFor } from '../lib/router'

/*
 * The table of routes lives in App.tsx and nginx needs its own copy: an address nginx does not
 * know is answered 404 with the not-found page. That page still loads the app, so the screen
 * would show, but with the wrong status and a noindex. This compares the two, in the manner of
 * the gateway's GatewayRoutesTest for the API prefixes.
 */

const NGINX = readFileSync(join(__dirname, '..', '..', '..', 'infra', 'launcher', 'nginx.conf'), 'utf8')
/** The configuration without comments, so a commented-out line never counts. */
const CONFIG = NGINX.split('\n')
  .map((line) => line.replace(/#.*$/, ''))
  .join('\n')

/** The body of the location block that starts with `opening`. */
function locationBody(opening: string): string {
  const start = CONFIG.indexOf(`location ${opening} {`)
  expect(start, `nginx.conf has no "location ${opening}"`).toBeGreaterThanOrEqual(0)
  return CONFIG.slice(start, CONFIG.indexOf('}', start))
}

/** The pattern of the location that serves the app shell, as a RegExp. */
function appShellPattern(): { regex: RegExp; segments: string[] } {
  const match = /location ~ (\^\/\(([a-z|-]+)\)\(\/\|\$\)) \{\s*try_files \/app\.html =404;/.exec(CONFIG)
  expect(match, 'nginx.conf has no location serving /app.html').not.toBeNull()
  return { regex: new RegExp(match![1]!), segments: match![2]!.split('|') }
}

/** The public pages, which have their own prerendered file instead of the shell. */
const PUBLIC_PAGES = ['home', 'trust', 'sign-in']

describe('nginx and the app routes', () => {
  it('serves the app shell for exactly the routes App.tsx has, besides the public pages', () => {
    const fromApp = new Set(
      routePatterns()
        .map((pattern) => pattern.split('/')[1] ?? '')
        .filter((segment) => segment !== '' && !PUBLIC_PAGES.includes(segment)),
    )
    const { segments } = appShellPattern()
    expect([...new Set(segments)].sort()).toEqual([...fromApp].sort())
  })

  it('matches every route pattern with sample parameters, and nothing beside them', () => {
    const { regex } = appShellPattern()
    for (const pattern of routePatterns()) {
      const path = pattern.replace(/:[a-zA-Z]+/g, 'abc123')
      const first = path.split('/')[1] ?? ''
      if (first === '' || PUBLIC_PAGES.includes(first)) continue
      expect(regex.test(path), path).toBe(true)
      expect(regex.test(`${path}/`), `${path}/`).toBe(true)
    }
    for (const path of ['/', '/home', '/trust', '/agentsx', '/robots.txt', '/no-such-page', '/assets/x.js']) {
      expect(regex.test(path), path).toBe(false)
    }
  })

  it('serves each public page from the file the prerender writes for it', () => {
    expect(locationBody('= /')).toContain('try_files /index.html =404;')
    expect(locationBody('= /home')).toContain('try_files /index.html =404;')
    expect(locationBody('= /trust')).toContain('try_files /trust.html =404;')
    expect(locationBody('= /sign-in')).toContain('try_files /sign-in.html =404;')
  })

  it('answers an unknown address with the not-found page and a 404, not the home page', () => {
    const fallback = locationBody('/')
    expect(fallback).toContain('try_files $uri =404;')
    expect(fallback).toContain('error_page 404 /404.html;')
    expect(locationBody('= /404.html')).toContain('internal;')
    expect(CONFIG).not.toMatch(/try_files[^;]*\/index\.html;/)
  })

  it('redirects the old address the router redirects', () => {
    expect(redirectFor('/integrations')).toBe('/connectors')
    expect(CONFIG).toMatch(/location ~ \^\/integrations\/\*\$ \{\s*return 301 \/connectors\$is_args\$args;/)
  })
})

describe('nginx headers', () => {
  it('sends the security headers, the robots rule and the cache rule from the server block only', () => {
    for (const header of [
      'add_header Content-Security-Policy $aiwos_csp always;',
      'add_header X-Content-Type-Options "nosniff" always;',
      'add_header Referrer-Policy "strict-origin-when-cross-origin" always;',
      'add_header X-Frame-Options "DENY" always;',
      'add_header X-Robots-Tag $aiwos_robots always;',
      'add_header Cache-Control $aiwos_cache_control;',
    ]) {
      expect(CONFIG).toContain(header)
    }
    // A location with an add_header of its own would inherit none of the server's.
    expect(CONFIG.match(/add_header/g)).toHaveLength(6)
  })

  it('lets search engines index only the public pages', () => {
    const map = CONFIG.slice(CONFIG.indexOf('map $request_uri $aiwos_robots'), CONFIG.indexOf('}', CONFIG.indexOf('map $request_uri $aiwos_robots')))
    expect(map).toContain('default "noindex";')
    const indexed = [...map.matchAll(/"([^"]+)"\s+"";/g)].map((match) => match[1]!)
    /** Whether the map gives this address no noindex. */
    const isIndexed = (uri: string) => indexed.some((key) => (key.startsWith('~') ? new RegExp(key.slice(1)).test(uri) : key === uri))
    for (const uri of ['/', '/home', '/trust', '/trust?utm_source=x', '/sign-in', '/robots.txt', '/sitemap.xml', '/llms.txt', '/llms-full.txt', '/og-image.png']) {
      expect(isIndexed(uri), uri).toBe(true)
    }
    for (const uri of ['/agents', '/approvals', '/create-workspace', '/accept-invite?token=x', '/sign-in?next=%2Fagents', '/no-such-page', '/index.html', '/trust.html', '/api/auth/me']) {
      expect(isIndexed(uri), uri).toBe(false)
    }
  })
})
