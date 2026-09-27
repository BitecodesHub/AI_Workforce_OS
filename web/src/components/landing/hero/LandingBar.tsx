import { useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { Brand } from '../../layout/Brand'
import { CTA } from '../shared/landingFacts'
import { useInPageLink } from '../shared/useInPageLink'

/*
 * The public page's sticky bar.
 *
 * It floats transparent over the hero and turns into a glass capsule once the page scrolls. The
 * in-page links mark the section being read, and the "Try a demo account" button joins "Sign in"
 * only after the hero's own calls to action have scrolled away, so the same offer is never shown
 * twice at once.
 *
 * All three behaviours come from IntersectionObservers; there are no scroll listeners. Where the
 * observer does not exist, the bar stays transparent, no link is marked and the button shows.
 */

const SECTIONS = [
  { id: 'agents', label: 'Agents' },
  { id: 'demos', label: 'Demos' },
  { id: 'roles', label: 'Roles' },
  { id: 'limits', label: 'Limits' },
] as const

type SectionId = (typeof SECTIONS)[number]['id']

/** A 5% band just above the middle of the viewport decides which section is being read. */
const SCROLLSPY_MARGIN = '-45% 0px -50% 0px'

export function LandingBar(): ReactElement {
  const inPage = useInPageLink()
  const sentinelRef = useRef<HTMLDivElement>(null)
  const [solid, setSolid] = useState(false)
  const [ctaVisible, setCtaVisible] = useState<boolean>(() => typeof IntersectionObserver === 'undefined')
  const [current, setCurrent] = useState<SectionId | null>(null)

  // 1. The bar turns solid once the sentinel at the very top of the page has scrolled away.
  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!sentinel || typeof IntersectionObserver === 'undefined') return undefined
    const observer = new IntersectionObserver((entries) => {
      const entry = entries[entries.length - 1]
      if (entry) setSolid(!entry.isIntersecting)
    })
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [])

  // 2. The bar's call to action shows while the hero's calls to action are off screen.
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return undefined
    const heroCtas = document.getElementById('lp-hero-ctas')
    if (!heroCtas) {
      // Nothing to hand over from, so the button simply stays available.
      const timer = setTimeout(() => setCtaVisible(true), 0)
      return () => clearTimeout(timer)
    }
    const observer = new IntersectionObserver((entries) => {
      const entry = entries[entries.length - 1]
      if (entry) setCtaVisible(!entry.isIntersecting)
    })
    observer.observe(heroCtas)
    return () => observer.disconnect()
  }, [])

  // 3. Scrollspy: the first listed section crossing the band is the current one.
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return undefined
    const targets = SECTIONS.map((section) => document.getElementById(section.id)).filter(
      (node): node is HTMLElement => node !== null,
    )
    if (targets.length === 0) return undefined

    const crossing = new Set<string>()
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) crossing.add(entry.target.id)
          else crossing.delete(entry.target.id)
        }
        const match = SECTIONS.find((section) => crossing.has(section.id))
        setCurrent(match ? match.id : null)
      },
      { rootMargin: SCROLLSPY_MARGIN },
    )
    for (const target of targets) observer.observe(target)
    return () => observer.disconnect()
  }, [])

  return (
    <>
      <div ref={sentinelRef} className="lp-bar-sentinel" aria-hidden="true" />
      <header className="lp-bar" data-solid={solid ? 'true' : 'false'}>
        <div className="lp-bar-inner">
          <Brand />
          <nav aria-label="On this page" className="nav-capsule lp-bar-nav">
            {SECTIONS.map((section) => (
              <a
                key={section.id}
                className="nav-pill"
                href={`#${section.id}`}
                onClick={inPage}
                {...(current === section.id ? { 'aria-current': 'location' as const } : {})}
              >
                {section.label}
              </a>
            ))}
          </nav>
          <div className="lp-bar-actions">
            <a className="button button-outline" href={CTA.signIn.href}>
              {CTA.signIn.label}
            </a>
            <a
              className="button button-primary lp-bar-cta"
              href={CTA.demo.href}
              data-visible={ctaVisible ? 'true' : 'false'}
            >
              {CTA.demo.label}
            </a>
          </div>
        </div>
      </header>
    </>
  )
}
