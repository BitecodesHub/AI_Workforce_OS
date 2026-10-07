import { useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { Brand } from '../../layout/Brand'
import { CTA } from '../shared/landingFacts'
import { useDemoCta } from '../shared/useDemoCta'
import { useInPageLink } from '../shared/useInPageLink'

/*
 * The public page's sticky bar.
 *
 * It floats transparent over the hero and turns into a glass capsule once the page scrolls. The
 * in-page links mark the section being read, and the demo button joins "Sign in" only after the
 * hero's own calls to action have scrolled away, so the same offer is never shown twice at once.
 * Like the hero's, that button offers the demo accounts only where this site has them (see
 * useDemoCta); otherwise it offers "Create your workspace", since the bar already links to the
 * demos on the page.
 *
 * All three behaviours come from IntersectionObservers; there are no scroll listeners. Where the
 * observer does not exist, the bar stays transparent, no link is marked and the button shows.
 */

export type BarSection = { id: string; label: string }

/** The home page's sections, in page order. */
export const HOME_SECTIONS: readonly BarSection[] = [
  { id: 'how', label: 'How it works' },
  { id: 'team', label: 'AI team' },
  { id: 'demos', label: 'See it work' },
  { id: 'safety', label: 'Safety' },
  { id: 'faq', label: 'Questions' },
]

/** A 5% band just above the middle of the viewport decides which section is being read. */
const SCROLLSPY_MARGIN = '-45% 0px -50% 0px'

export type LandingBarProps = { sections?: readonly BarSection[] }

export function LandingBar({ sections = HOME_SECTIONS }: LandingBarProps): ReactElement {
  const inPage = useInPageLink()
  const demo = useDemoCta()
  const sentinelRef = useRef<HTMLDivElement>(null)
  const [solid, setSolid] = useState(false)
  const [ctaVisible, setCtaVisible] = useState<boolean>(() => typeof IntersectionObserver === 'undefined')
  const [current, setCurrent] = useState<string | null>(null)

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
    const targets = sections.map((section) => document.getElementById(section.id)).filter(
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
        const match = sections.find((section) => crossing.has(section.id))
        setCurrent(match ? match.id : null)
      },
      { rootMargin: SCROLLSPY_MARGIN },
    )
    for (const target of targets) observer.observe(target)
    return () => observer.disconnect()
  }, [sections])

  return (
    <>
      <div ref={sentinelRef} className="lp-bar-sentinel" aria-hidden="true" />
      <header className="lp-bar" data-solid={solid ? 'true' : 'false'}>
        <div className="lp-bar-inner">
          <Brand />
          <nav aria-label="On this page" className="nav-capsule lp-bar-nav">
            {sections.map((section) => (
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
            {/* Without demo accounts the demo button would repeat the "See it work" link beside it,
                so the bar offers the next step instead. */}
            <a
              className="button button-primary lp-bar-cta"
              href={demo.accounts ? demo.href : CTA.create.href}
              onClick={inPage}
              data-visible={ctaVisible ? 'true' : 'false'}
            >
              {demo.accounts ? demo.label : CTA.create.label}
            </a>
          </div>
        </div>
      </header>
    </>
  )
}
