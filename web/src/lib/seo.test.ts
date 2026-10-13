// @find: tests for seo, page head, canonical link, robots noindex, meta description, site address, public pages, applyPageHead, normaliseSiteUrl
// @what: Tests the page metadata source and the writer that keeps the document head in step with the screen.
// @flow: Tests lib/seo.ts

import { afterEach, describe, expect, it } from 'vitest'
import {
  DEFAULT_SITE_URL,
  PRODUCT_DEFINITION,
  PUBLIC_PAGES,
  SITE_URL,
  absoluteUrl,
  applyPageHead,
  clearPrerenderedMark,
  normaliseSiteUrl,
  privateHead,
  publicHead,
  publicPageFor,
} from './seo'

/** The head element a selector names, or null. */
function headElement(selector: string): Element | null {
  return document.head.querySelector(selector)
}

/** Contractions the organisation's style rules keep out of new copy (possessives are fine). */
const CONTRACTION = /n't\b|'re\b|'ve\b|'ll\b|'m\b|\b(it|that|there|what|here|who|let)'s\b/i

afterEach(() => {
  document.head.innerHTML = ''
  document.documentElement.removeAttribute('data-prerendered')
})

describe('the public address', () => {
  it('defaults to the custom domain, and the build can name another', () => {
    expect(SITE_URL).toBe(DEFAULT_SITE_URL)
    expect(normaliseSiteUrl('https://example.test/')).toBe('https://example.test')
    expect(normaliseSiteUrl('http://localhost:8080')).toBe('http://localhost:8080')
  })

  it('falls back to the default for anything that is not an http(s) origin', () => {
    for (const value of [undefined, null, '', 'ai-workforce.bitecodes.com', 'https://a.test/path', 'javascript:alert(1)']) {
      expect(normaliseSiteUrl(value), String(value)).toBe(DEFAULT_SITE_URL)
    }
  })

  it('writes absolute addresses on the site', () => {
    expect(absoluteUrl('/')).toBe('https://ai-workforce.bitecodes.com/')
    expect(absoluteUrl('trust')).toBe('https://ai-workforce.bitecodes.com/trust')
  })
})

describe('the public pages', () => {
  it('names / as the canonical home page, even when it is shown at /home', () => {
    expect(publicPageFor('/home')).toBe('home')
    expect(publicHead('home').canonical).toBe('https://ai-workforce.bitecodes.com/')
    expect(publicHead('trust').canonical).toBe('https://ai-workforce.bitecodes.com/trust')
    expect(publicHead('sign-in').canonical).toBe('https://ai-workforce.bitecodes.com/sign-in')
  })

  it('treats every other route as a page that is not indexed', () => {
    for (const pattern of ['/', '/agents', '/agents/:id', '/create-workspace', '/accept-invite', '']) {
      expect(publicPageFor(pattern), pattern).toBeNull()
    }
    expect(privateHead('Agents')).toEqual({
      title: 'Agents · AI Workforce OS',
      description: expect.any(String),
      canonical: null,
      index: false,
    })
  })

  it('keeps titles and descriptions short enough to show in full, and in the house style', () => {
    for (const [page, meta] of Object.entries(PUBLIC_PAGES)) {
      const head = publicHead(page as keyof typeof PUBLIC_PAGES)
      expect(head.title.length, `${page} title`).toBeLessThanOrEqual(60)
      expect(meta.description.length, `${page} description`).toBeLessThanOrEqual(160)
      expect(meta.description.length, `${page} description`).toBeGreaterThanOrEqual(40)
      for (const text of [head.title, meta.description]) {
        expect(text).not.toContain('!')
        expect(text).not.toMatch(CONTRACTION)
      }
    }
    expect(PRODUCT_DEFINITION).toMatch(/^AI Workforce OS is /)
    expect(PRODUCT_DEFINITION).not.toMatch(CONTRACTION)
  })
})

describe('applyPageHead', () => {
  it('writes the description, canonical link and link-preview text of a public page', () => {
    applyPageHead(publicHead('trust'))
    expect(headElement('meta[name="description"]')).toHaveAttribute('content', PUBLIC_PAGES.trust.description)
    expect(headElement('link[rel="canonical"]')).toHaveAttribute('href', 'https://ai-workforce.bitecodes.com/trust')
    expect(headElement('meta[property="og:url"]')).toHaveAttribute('content', 'https://ai-workforce.bitecodes.com/trust')
    expect(headElement('meta[property="og:title"]')).toHaveAttribute('content', 'Security and technical details · AI Workforce OS')
    expect(headElement('meta[name="twitter:description"]')).toHaveAttribute('content', PUBLIC_PAGES.trust.description)
    expect(headElement('meta[name="robots"]')).toBeNull()
  })

  it('marks a signed-in screen noindex and drops the canonical link it no longer has', () => {
    applyPageHead(publicHead('home'))
    applyPageHead(privateHead('Approvals'))
    expect(headElement('meta[name="robots"]')).toHaveAttribute('content', 'noindex')
    expect(headElement('link[rel="canonical"]')).toBeNull()
    expect(headElement('meta[property="og:url"]')).toBeNull()
    // Back on a public page, the noindex goes again.
    applyPageHead(publicHead('home'))
    expect(headElement('meta[name="robots"]')).toBeNull()
    expect(document.head.querySelectorAll('link[rel="canonical"]')).toHaveLength(1)
  })

  it('updates the elements a prerendered page already has rather than adding a second set', () => {
    document.head.innerHTML =
      '<meta name="description" content="old"><link rel="canonical" href="https://ai-workforce.bitecodes.com/"><meta name="robots" content="noindex">'
    applyPageHead(publicHead('sign-in'))
    expect(document.head.querySelectorAll('meta[name="description"]')).toHaveLength(1)
    expect(headElement('meta[name="description"]')).toHaveAttribute('content', PUBLIC_PAGES['sign-in'].description)
    expect(headElement('link[rel="canonical"]')).toHaveAttribute('href', 'https://ai-workforce.bitecodes.com/sign-in')
    expect(headElement('meta[name="robots"]')).toBeNull()
  })

  it('clears the prerendered mark', () => {
    document.documentElement.setAttribute('data-prerendered', 'home')
    clearPrerenderedMark()
    expect(document.documentElement.hasAttribute('data-prerendered')).toBe(false)
  })
})
