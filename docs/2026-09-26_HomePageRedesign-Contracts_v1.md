FOUNDATION CONTRACTS (exact names; consumers must not redefine)

1. STYLESHEETS
- web/src/styles/landing/index.css imports, in this order: base.css, hero.css, agent-run.css, failover-answer.css, roles-sections.css.
- Only Landing.tsx imports index.css. Each owner must create its own stylesheet.
- Every selector starts with .lp.
- Owner prefixes:
  - hero: .lp-bar-*, .lp-hero-*, .lp-facts / .lp-fact*, .lp-console-*
  - agent-run: .lp-run-*, .lp-approval-*
  - failover: .lp-route-*
  - answer: .lp-cite-*
  - roles-and-sections: .lp-roles-*, .lp-agents-*, .lp-audit-*, .lp-platform-*, .lp-limits-*, .lp-cta-*, .lp-footer-*
- Keyframes use the same owner sub-prefixes: lp-hero-*, lp-run-*, lp-route-*, lp-cite-*, lp-roles-*, lp-audit-*.

2. CUSTOM PROPERTIES (declared on .lp in base.css; nothing added to tokens.css)
- Layout:
  - --lp-max 1280px; --lp-gutter 36px (16px at 599px and below); --lp-gap 44px (32px at 599px and below); --lp-grid-gap 16px
  - --lp-tile-pad 22px (16px at 599px and below); --lp-bar-h 56px (52px at 899px and below); --lp-scroll-offset 84px
- Type:
  - --lp-text-display 44px (40px at 1179px and below, 32px at 599px and below)
  - --lp-text-h2 24px (20px at 599px and below)
  - --lp-text-h3 16px; --lp-text-lead 14px; --lp-text-copy 13.5px
- Surfaces:
  - --lp-glass-strong rgba(255, 255, 255, 0.9); --lp-glass rgba(255, 255, 255, 0.8); --lp-glass-soft rgba(255, 255, 255, 0.62); --lp-glass-blur 18px
  - --lp-frost linear-gradient(180deg, rgba(255, 255, 255, 0.97), rgba(255, 255, 255, 0.86))
  - --lp-inset-fill color-mix(in srgb, var(--paper) 78%, var(--blue-light))
- Light:
  - --lp-aurora-1: var(--blue) at 20%, mixed with transparent
  - --lp-aurora-2: var(--chart-secondary) at 42%, mixed with transparent
  - --lp-aurora-3: var(--blue-light) at 95%, mixed with transparent
  - --lp-grid-line: blue 7%; --lp-pool: blue 14%; --lp-spot: blue 9%; --lp-tint-04, --lp-tint-08, --lp-tint-12: blue 4%, 8%, 12%; --lp-bezel: blue 45%. Each is color-mix(in srgb, var(--blue) N%, transparent).
  - --lp-ok-bg: color-mix with var(--green) at 9% and white
  - --lp-bad-bg: color-mix with var(--danger) at 8% and white
- Motion:
  - --lp-ease-out cubic-bezier(0.22, 1, 0.36, 1); --lp-ease-in-out cubic-bezier(0.65, 0, 0.35, 1)
  - --lp-quick 180ms; --lp-state 320ms; --lp-enter 420ms; --lp-hero 700ms; --lp-stagger 50ms
- Runtime variables written by JS: --i (reveal index), --mx and --my (spotlight px), --px and --py (hero parallax, -1..1), --lp-bar-ms, --lp-hop.
- Literal glass borders, used as-is and never through a variable: 1px solid rgba(210, 221, 238, 0.9) for glass; 1px solid rgba(210, 221, 238, 0.7) for insets.

3. ROOT ATTRIBUTES (set by LandingRoot)
- div.lp[data-motion='on'|'off'][data-ambient='running'|'paused'].
- Every hidden start state and every animation applies only under .lp[data-motion='on'], inside @media (prefers-reduced-motion: no-preference).
- Everything ambient (aurora drift, .lp-pulse rings) pauses under .lp[data-ambient='paused'].

4. BASE CLASSES (base.css)
- Root and layout: .lp, .lp-main, .lp-shell, .lp-section (tabindex -1 target; scroll-margin-top), .lp-section-tight, .lp-head, .lp-head-main, .lp-head-aside
- Type: .lp-h2, .lp-h2-band, .lp-h3, .lp-lead, .lp-copy, .lp-mono, .lp-micro
- Background: .lp-aurora, .lp-aurora-blob (x3), .lp-aurora::after (blueprint grid), .lp-aurora-low
- Surfaces: .lp-glass (blur; ::before specular), .lp-frost (no blur), .lp-inset, .lp-code, .lp-hairline-grid, .lp-hairline-cell, .lp-notice-pill
- Bento: .lp-bento with child [data-area='approval'|'failover'|'audit'|'cited'] spans
  - 1180px and above: 7, 5, 5, 7
  - 900–1179px: 12, 6, 6, 12
  - below 900px: all 12
- Demo frame: .lp-demo (container-type inline-size; ::before light pool, brighter when [data-inview='true']), .lp-demo-head, .lp-demo-title, .lp-demo-lead, .lp-demo-body, .lp-demo-status, .lp-demo-foot, .lp-demo-actions
- Controls: .lp-button-lg (with .button), .lp-sheen, .lp-chip ([aria-pressed='true'] state), .lp-select, .lp-seg, .lp-seg-legend, .lp-seg-legend-hidden, .lp-seg-options, .lp-seg-input, .lp-seg-pill, .lp-seg-full, .lp-link
- Status: .lp-dot with .lp-dot-green, -blue, -warning, -danger; .lp-pulse; .lp-bar; .lp-bar-fill[data-run='fill'|'drain'|'full'|'empty'] (uses --lp-bar-ms); .lp-scan-host[data-scanning='true']
- Reveal and motion: .lp-reveal (driven by useReveal's data-inview; stagger calc(min(var(--i,0),6) * var(--lp-stagger)); fill backwards), .lp-anim-rise, .lp-anim-slide, .lp-anim-fade, .lp-anim-tick
- Hover: .lp-lift, .lp-spot, .lp-bezel (child span)
- Reserved pseudo-elements, which owners must not use on elements carrying these classes: .lp-glass::before, .lp-demo::before, .lp-spot::after, .lp-sheen::after, .lp-pulse::after, .lp-scan-host::after.

5. KEYFRAMES (base.css, declared inside @media (prefers-reduced-motion: no-preference))
- lp-rise: opacity 0 and translateY(12px) to none
- lp-fade: opacity 0 to 1
- lp-slide: opacity 0 and translateX(8px) to none
- lp-tick: opacity 0 and translateY(6px) to none
- lp-pulse: scale 1 at opacity 0.45 to scale 2.2 at opacity 0; 1600ms, infinite
- lp-fill: scaleX 0 to 1
- lp-drain: scaleX 1 to 0
- lp-scan: translateX(-100%) to translateX(800%); 1100ms
- lp-sheen: translateX(-120%) to translateX(120%); 700ms
- lp-drift-1, lp-drift-2, lp-drift-3: 32s, 38s and 44s; infinite alternate
- Under reduce, base.css zeroes animation-delay and transition-delay inside .lp.

6. RADIUS MAP
- --radius-drawer: solid bar
- --radius-card: tiles, panels, hero back layer
- --radius-glass-tile: hero trace and approval layers
- --radius-tile: insets, stages, fact strip, hairline grids, approval card
- --radius-control-lg: code and payload blocks, large buttons
- --radius-control: selects, buttons
- --radius-tag: chips in the permission map, page thumbnails
- --radius-pill: dots, pills, meters, bars
- border-radius may otherwise be only 0 or inherit.

7. HOOKS (web/src/hooks/)
- useReducedMotion(): boolean
  - useSyncExternalStore; true when matchMedia is missing; server snapshot true.
- useInView<T extends Element>(options?: { threshold?: number; rootMargin?: string; once?: boolean }): { ref: React.RefCallback<T>; inView: boolean }
  - Defaults 0, '0px', true. true when IntersectionObserver is missing.
- useReveal<T extends HTMLElement>(): React.RefCallback<T>
  - One shared observer: threshold 0, rootMargin '0px 0px -12% 0px'.
  - Sets node.dataset.inview = 'true' once. Never render data-inview from JSX.
- revealStyle(index: number): React.CSSProperties
  - Returns { '--i': String(index) }. Exported from useReveal.ts.
- useSequence(): { play(steps: ReadonlyArray<{ at: number; run: () => void }>): void; cancel(): void }
  - Stable identities.
  - Synchronous in order of at when reduced; otherwise one setTimeout per step, including at 0.
  - Cleared on cancel and on unmount.
- useCountUp(target: number, active: boolean, durationMs?: number): number
  - 600ms default. target when reduced; 0 before the first activation when motion is on.
  - Animates on activation and on target change (300ms). setState only in rAF.
- usePointerSpot<T extends HTMLElement>(): React.RefCallback<T>
  - Writes --mx and --my; fine pointer and motion on only.

8. COMPONENTS (web/src/components/landing/shared/)
- LandingRoot({ children })
  - Exports type LandingMotion = { reduced: boolean; ambientPaused: boolean; setAmbientPaused: (paused: boolean) => void } and useLandingMotion(): LandingMotion.
  - Outside a root, useLandingMotion returns { reduced, ambientPaused: false, setAmbientPaused: no-op }.
- LandingSection({ id: string; labelledBy: string; children: ReactNode; className?: string; tight?: boolean })
  - Renders section.lp-section[tabIndex=-1] wrapping div.lp-shell.
- SectionHead({ eyebrow: string; title: ReactNode; titleId: string; lead?: ReactNode; aside?: ReactNode; band?: boolean })
  - Renders <Eyebrow> and h2.lp-h2 (plus lp-h2-band when band is set) inside a Reveal with .lp-head.
- Reveal({ as?: 'div'|'article'|'li'|'figure'|'aside'|'header'|'section'; index?: number; className?: string; id?: string; labelledBy?: string; children: ReactNode })
- DemoFrame({ area: 'approval'|'failover'|'audit'|'cited'; index: '01'|'02'|'03'|'04'; name: string; title: string; lead: ReactNode; status: string; children: ReactNode; footnote?: string; busy?: boolean })
  - Renders article#{area}[data-area] with classes lp-demo lp-frost lp-spot lp-reveal.
  - Eyebrow '{index} · {name}'; Tag neutral 'Simulated'; h3#{area}-title.
  - p.lp-demo-status[role=status][aria-atomic=true], empty at mount.
- SegmentedControl<V extends string>({ legend: string; value: V; options: ReadonlyArray<{ value: V; label: string }>; onChange: (v: V) => void; disabled?: boolean; hideLegend?: boolean; fullWidth?: boolean })
  - A native radio fieldset.
- Icon({ name: IconName; size?: 14|16|18; className?: string })
  - IconName = 'check'|'dash'|'x'|'lock'|'pause'|'play'|'arrow-right'|'link'|'link-broken'|'document'|'route'|'gate'|'clock'|'slash'|'pencil'|'reset'
- useInPageLink(): (e: React.MouseEvent<HTMLAnchorElement>) => void
  - Smooth scroll unless reduced; pushState '#id'; focus the target with preventScroll; guarded for jsdom.
- landingFacts.ts: AgentCategory, AgentId, AGENTS, PROVIDERS, SANDBOX_MODEL, TOOL_SERVERS, SERVICE_COUNT, PERMISSION_CODE_COUNT, BUILT_IN_ROLE_COUNT, SIMULATED_PAGE_NOTICE, CTA, DEMO_ACCOUNTS_HEDGE.

9. ANCHOR IDS (fixed; none is hex-like)
- Sections and demos: main, agents, demos, approval, failover, audit, cited, roles, platform, limits.
- Headings and targets: hero-title, agents-title, demos-title, roles-title, platform-title, limits-title, cta-title, lp-hero-ctas, lp-console-caption, approval-card-title, run-timeline-title, roles-panel, roles-tab-{role}, agent-{id}-title, cite-passage-1, cite-passage-2.

10. DEMO PATTERNS
- Initial state: useReducer(reducer, reduced, init). init returns the static end state when reduced.
- Autoplay: useInView at threshold 0.45, plus an effect conditioned on reducer state (phase idle and not autoplayed), never on a ref. The first step dispatches an event that sets autoplayed.
- Announcements go through DemoFrame status, only at the start and end of a sequence and on the control changes the spec names.
- Focus moves only after a user action, via focus { target, nonce } held in reducer state.
- A busy trigger uses aria-disabled, never disabled.