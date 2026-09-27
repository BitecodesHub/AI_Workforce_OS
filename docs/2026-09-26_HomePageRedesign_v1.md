# AI Workforce OS: Public Home Page Redesign, Final Build Spec (v1)

## 0. Executive summary
- **Direction.** "Living Console", light with depth and motion. The hero is a glass-layered, simulated replica of the console doing work. Below it sit:
  - agent tiles with verified tool limits;
  - a 12-column bento of four independent, clearly simulated demos: approval gate, provider failover, audit chain and cited answer;
  - a role explorer;
  - a spec band;
  - the honesty block beside the final call to action.
- **Density.** About 3,400px at 1440 wide, against about 2,300px today, holding roughly 2.5 times the verifiable facts. Every viewport below the hero holds two or more side-by-side panels. The page is never padded to fill a viewport.
- **Parallel build.** No demo shares state with another. Foundation ships shared CSS, hooks and components first. Four owners then build in parallel. The integrator composes the page and extends the design test.
- **Constraints.** Every hard constraint is kept. The design-system test is extended so landing stylesheets cannot escape the border, radius, hex or keyframe rules.

## 1. Rules every owner follows

**Colour and CSS values**
- **R1. No hex in any .tsx file**, including comments, strings and test files.
  - The test regex `#[0-9a-fA-F]{3,8}\b` also matches text such as 'PR #412', '#bed' and '#faded'.
  - Never write '#' followed by three or more hex characters. The anchor ids in section 3 all pass.
  - Hashes are computed at runtime and shown without a '#'.
- **R2. No hex anywhere in the landing CSS.** The new test enforces this.
  - Allowed colour forms: var(--token); color-mix(in srgb, var(--x) N%, transparent | white | var(--y)); rgba(255, 255, 255, a); rgba(210, 221, 238, a).
  - Use no id selectors.
- **R3. Borders.** Every `border*: 1px solid X` must contain literally `var(--line)`, `rgba(210, 221, 238, a)` or `transparent`.
  - A custom property holding the rgba value fails the test.
  - Emphasis goes in a separate `border-color:` declaration, for example var(--border-hover).
  - There are no coloured 2px borders. Coloured bars and connectors are backgrounds or pseudo-elements.
  - Dashed borders also use var(--line).
- **R4. Radii.** `border-radius` may only be var(--radius-*), 0 or inherit. See the map in 4.4.
- **R5. No resting shadows.** No box-shadow at rest on any tile, card, panel or glass surface. The only shadow is var(--shadow-hover), on hover. Status glows are pseudo-element rings drawn with a background.

**Interface text and fonts**
- **R6. Eyebrow and text rules.**
  - Landing.tsx must contain a literal `<Eyebrow>`.
  - No '!' and no emoji or pictograph in any JSX text.
  - All icons are `<Icon>` SVGs. Never use check-mark, cross, arrow (U+2190–21FF), play, pause or copyright glyphs.
  - Allowed punctuation: · – — … − +.
- **R7. Fonts.** DM Sans, plus IBM Plex Mono at weights 400 and 500 only.

**Motion**
- **R8. No new dependencies.**
  - Motion is CSS transform and opacity, plus small React state machines using IntersectionObserver, setTimeout, setInterval and rAF.
  - No canvas, and no animated filter or blur.
- **R9. Reduced motion.**
  - Every hidden start state and every keyframe applies only under `.lp[data-motion='on']`, inside `@media (prefers-reduced-motion: no-preference)`.
  - JS sequences render their end states synchronously when motion is reduced; useSequence does this.
  - base.css zeroes animation-delay and transition-delay under reduce, because the global rule in tokens.css does not.

**Scope and naming**
- **R10. Scoping.**
  - Every landing selector starts with `.lp`.
  - Class prefixes are per owner (4.3).
  - Keyframes are named `lp-*`, with owner sub-prefixes.
  - Owners never style pseudo-elements that foundation reserves (4.2).

**Content and links**
- **R11. Honest copy.**
  - Use only the facts and copy in this spec.
  - Every demo carries a 'Simulated' tag.
  - The page notice says time is compressed.
  - No logos, testimonials or latency figures.
- **R12. Links.** Links are real `<a href>`.
  - The calls to action go to '/sign-in' and '/create-workspace'.
  - In-page '#id' links use `useInPageLink()`.

**TypeScript, React and primitives**
- **R13. TypeScript.** strict, with exactOptionalPropertyTypes and noUncheckedIndexedAccess.
  - Use discriminated unions.
  - Use conditional spreads; never write `prop={undefined}`.
  - Guard every indexed access.
- **R14. react-hooks v7 lint** at --max-warnings 0.
  - No synchronous setState in an effect body. Update state in event handlers, timer callbacks, IntersectionObserver callbacks or rAF.
  - No Date.now() or Math.random() during render.
  - Every effect cleans up its timers and observers, because StrictMode runs effects twice.
- **R15. Primitives.**
  - Do not use Card (it does not type id or aria props), DataTable (it needs the router) or Notice (it sets role alert or status) on the landing page. Use native elements with classes.
  - Tag has no className prop; wrap it when extra styling is needed.
  - Button accepts className and aria-* attributes.

**Tests, accessibility and small screens**
- **R16. Test files.** No .tsx test file may live under src/routes/, because the eyebrow test scans every routes .tsx file.
- **R17. Accessibility.**
  - Use real buttons.
  - Keep focus visible. Focusables inside an overflow-hidden container use outline-offset: -2px.
  - Live regions exist only where this spec names them.
  - Targets are at least 24px. Primary controls are at least 44px below 600px.
- **R18. 375px.** No horizontal scroll.
  - `.lp` sets overflow-x: clip, never hidden, because hidden breaks the sticky bar.
  - Every grid and flex child sets min-width: 0.
  - Mono strings use overflow-wrap: anywhere.

## 2. Architecture and build order
```
web/src/styles/landing/          index.css (foundation) base.css (foundation) hero.css agent-run.css failover-answer.css roles-sections.css
web/src/hooks/                   useReducedMotion useInView useReveal useSequence useCountUp usePointerSpot (foundation)
web/src/components/landing/
  shared/                        foundation
  hero/                          hero
  agent-run/                     agent-run
  failover/  answer/             failover-and-answer
  roles/  sections/              roles-and-sections
  Landing.test.tsx               integrator
```

**Order of work**
1. Foundation works alone.
2. Then hero, agent-run, failover-and-answer and roles-and-sections work in parallel.
3. The integrator finishes.

**Rules for owners**
- Owners edit only the files they own.
- Owners verify with `pnpm exec tsc -b`, `pnpm lint` and vitest tests on their own components.
- index.css is imported only by Landing.tsx. A missing owner stylesheet therefore surfaces only at integration, so **every owner must create its stylesheet**.

## 3. Page composition, owned by the integrator

**Order and anchors.** None of these ids is hex-like.

| # | Element | Owner | id |
|---|---|---|---|
| 1 | `<a className="skip-link" href="#main">Skip to content</a>` | integrator | |
| 2 | `<LandingBar />` | hero | |
| 3 | `<main id="main" className="lp-main">` | integrator | main |
| 3.1 | `<Hero />` | hero | aria-labelledby hero-title |
| 3.2 | `<AgentsSection />` | roles-and-sections | agents |
| 3.3 | Demos section: inline head plus `.lp-bento` holding ApprovalDemo, FailoverDemo, AuditChainDemo and CitedAnswerDemo | integrator (tiles by owners) | demos, approval, failover, audit, cited |
| 3.4 | `<RoleSwitcher />` | roles-and-sections | roles |
| 3.5 | `<PlatformBand />` | roles-and-sections | platform |
| 3.6 | `<LimitsAndCta />` | roles-and-sections | limits |
| 4 | `<LandingFooter />` | roles-and-sections | |

**Landing.tsx skeleton.** This is the exact structure.
```tsx
import '../styles/landing/index.css'
import { Eyebrow } from '../components/ui'
// ...imports listed in the file plan
export function Landing() {
  return (
    <LandingRoot>
      <a className="skip-link" href="#main">Skip to content</a>
      <LandingBar />
      <main id="main" className="lp-main">
        <Hero />
        <AgentsSection />
        <LandingSection id="demos" labelledBy="demos-title">
          <Reveal className="lp-head">
            <div className="lp-head-main">
              <Eyebrow>Try it here</Eyebrow>
              <h2 id="demos-title" className="lp-h2">Four things that make it safe to switch on</h2>
            </div>
            <div className="lp-head-aside">
              <p className="lp-notice-pill"><span className="lp-dot lp-dot-green" aria-hidden="true" />{SIMULATED_PAGE_NOTICE}</p>
              <p className="caption">Where a demo waits, time is compressed.</p>
            </div>
          </Reveal>
          <div className="lp-bento">
            <ApprovalDemo />
            <FailoverDemo />
            <AuditChainDemo />
            <CitedAnswerDemo />
          </div>
        </LandingSection>
        <RoleSwitcher />
        <PlatformBand />
        <LimitsAndCta />
      </main>
      <LandingFooter />
    </LandingRoot>
  )
}
```

**Heading levels**
- h1: hero
- h2: each section
- h3: demo and agent tiles, role name
- h4: inner panels

## 4. Shared contracts, built by foundation

### 4.1 Custom properties
All are declared on `.lp` in base.css. Do not add them to tokens.css.

**Layout**
- `--lp-max: 1280px`
- `--lp-gutter: 36px`; 16px at 599px and below
- `--lp-gap: 44px`; 32px at 599px and below
- `--lp-grid-gap: 16px`
- `--lp-tile-pad: 22px`; 16px at 599px and below
- `--lp-bar-h: 56px`; 52px at 899px and below
- `--lp-scroll-offset: 84px`

**Type**
- `--lp-text-display: 44px`; 40px at 1179px and below; 32px at 599px and below
- `--lp-text-h2: 24px`; 20px at 599px and below
- `--lp-text-h3: 16px`
- `--lp-text-lead: 14px`
- `--lp-text-copy: 13.5px`

**Surfaces**
- `--lp-glass-strong: rgba(255, 255, 255, 0.9)`
- `--lp-glass: rgba(255, 255, 255, 0.8)`
- `--lp-glass-soft: rgba(255, 255, 255, 0.62)`
- `--lp-glass-blur: 18px`
- `--lp-frost: linear-gradient(180deg, rgba(255, 255, 255, 0.97), rgba(255, 255, 255, 0.86))`
- `--lp-inset-fill: color-mix(in srgb, var(--paper) 78%, var(--blue-light))`

**Light**
- `--lp-aurora-1: color-mix(in srgb, var(--blue) 20%, transparent)`
- `--lp-aurora-2: color-mix(in srgb, var(--chart-secondary) 42%, transparent)`
- `--lp-aurora-3: color-mix(in srgb, var(--blue-light) 95%, transparent)`
- `--lp-grid-line: color-mix(in srgb, var(--blue) 7%, transparent)`
- `--lp-pool: color-mix(in srgb, var(--blue) 14%, transparent)`
- `--lp-spot: color-mix(in srgb, var(--blue) 9%, transparent)`
- `--lp-tint-04`, `--lp-tint-08`, `--lp-tint-12`: color-mix(in srgb, var(--blue) 4%, 8% or 12%, transparent)
- `--lp-bezel: color-mix(in srgb, var(--blue) 45%, transparent)`
- `--lp-ok-bg: color-mix(in srgb, var(--green) 9%, white)`
- `--lp-bad-bg: color-mix(in srgb, var(--danger) 8%, white)`

**Motion**
- `--lp-ease-out: cubic-bezier(0.22, 1, 0.36, 1)`
- `--lp-ease-in-out: cubic-bezier(0.65, 0, 0.35, 1)`
- `--lp-quick: 180ms`; `--lp-state: 320ms`; `--lp-enter: 420ms`; `--lp-hero: 700ms`; `--lp-stagger: 50ms`
- Neither curve overshoots, so nothing bounces.

**Runtime variables written by JS**
- `--i`: reveal index
- `--mx`, `--my`: spotlight position in px
- `--px`, `--py`: hero parallax, range -1..1
- `--lp-bar-ms`: bar duration
- `--lp-hop`: packet slot index

**Breakpoints** (used by every owner): `@media (max-width: 1179px)`, `(max-width: 899px)`, `(max-width: 599px)`. Tile internals use container queries on `.lp-demo` (container-type: inline-size) at 640px and 520px.

### 4.2 base.css classes
Owners consume these and never redefine them.

**Root and layout**
- `.lp`: position relative; isolation isolate; overflow-x clip; min-height 100vh; color var(--ink).
- `.lp-main`: padding-bottom var(--lp-gap).
- `.lp-shell`: max-width var(--lp-max); margin 0 auto; padding-inline var(--lp-gutter).
- `.lp-section`:
  - padding-top var(--lp-gap); scroll-margin-top var(--lp-scroll-offset);
  - `:focus { outline: none }`, because it is only a programmatic focus target with tabindex -1;
  - `.lp-section-tight` sets padding-top 32px.
- `.lp-head`: split header.
  - grid-template-columns minmax(0,1fr) minmax(0,auto); align-items end; gap 22px; margin-bottom 16px.
  - At 899px and below, one column with gap 8px.
- `.lp-head-main`, `.lp-head-aside`: the aside is right-aligned, with max-width 44ch.
- `.lp-h2`: var(--lp-text-h2); weight 600; letter-spacing -0.015em; line-height 1.15. `.lp-h2-band` sets 16px.
- `.lp-h3`: 16px, weight 600.
- `.lp-lead`: 14px/1.55; var(--muted).
- `.lp-copy`: 13.5px/1.55.
- `.lp-mono`: IBM Plex Mono 11px.
- `.lp-micro`: mono 10px; uppercase; letter-spacing 0.06em; var(--eyebrow).

**Background**
- `.lp-aurora`: absolute; inset -120px 0 auto 0; height 1200px; z-index -1; pointer-events none; overflow hidden. It holds three `.lp-aurora-blob` spans, each with radius var(--radius-pill) and background radial-gradient(closest-side, var(--lp-aurora-N), transparent):
  - blob 1: 760px; top -200px; left 48%
  - blob 2: 620px; top 120px; left -8%
  - blob 3: 540px; top 420px; right -6%
  - At 599px and below, each blob is 60% of that size.
- `.lp-aurora::after` is the blueprint grid:
  - two linear-gradients of var(--lp-grid-line) 1px, transparent 1px, background-size 44px 44px;
  - mask-image and -webkit-mask-image: radial-gradient(ellipse 60% 55% at 65% 28%, black 20%, transparent 75%).
- `.lp-aurora-low`: one static blob, 520px, var(--lp-aurora-1). The parent positions it.

**Surfaces**
- `.lp-glass`:
  - background linear-gradient(180deg, var(--lp-glass-strong), var(--lp-glass));
  - backdrop-filter blur(var(--lp-glass-blur)) saturate(1.4), with the -webkit- prefix;
  - border 1px solid rgba(210, 221, 238, 0.9); radius var(--radius-card).
  - Fallback: `@supports not (backdrop-filter: blur(1px))` sets background var(--lp-glass-strong).
  - `::before` is a specular line: absolute; top 0; left 16px; right 16px; height 1px; background linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.95) 30%, rgba(255, 255, 255, 0.95) 70%, transparent).
- `.lp-frost`: background var(--lp-frost); border 1px solid var(--line); radius var(--radius-card). No blur.
- `.lp-inset`: background var(--lp-inset-fill); border 1px solid rgba(210, 221, 238, 0.7); radius var(--radius-tile); padding 16px (12px at 599px and below).
- `.lp-code`: background var(--surface); border 1px solid var(--line); radius var(--radius-control-lg); mono 11.5px/1.55; padding 12px; white-space pre-wrap; overflow-wrap anywhere.
- `.lp-hairline-grid`: display grid; gap 1px; background var(--line); border 1px solid var(--line); radius var(--radius-tile); overflow hidden. Child `.lp-hairline-cell` has background var(--surface) and padding 12px 16px.
- `.lp-notice-pill`: inline-flex; gap 8px; background var(--lp-glass-strong); border 1px solid rgba(210, 221, 238, 0.7); radius var(--radius-pill); padding 6px 12px; 12px text.

**Bento**
- `.lp-bento`: display grid; grid-template-columns repeat(12, minmax(0,1fr)); gap var(--lp-grid-gap); align-items stretch.
- Column spans by `[data-area]` on the child:

| Width | approval | failover | audit | cited |
|---|---|---|---|---|
| 1180px and above | 7 | 5 | 5 | 7 |
| 900–1179px | 12 | 6 | 6 | 12 |
| Below 900px | 12 | 12 | 12 | 12 |

**Demo frame**
- `.lp-demo`: container-type inline-size; position relative; isolation isolate; display flex column; gap 12px; padding var(--lp-tile-pad).
  - Hover: border-color var(--border-hover) and box-shadow var(--shadow-hover), over 220ms. There is no lift.
  - `::before` (reserved) is the light pool: absolute; inset 0; radius inherit; z-index -1; background radial-gradient(420px 240px at 14% 0%, color-mix(in srgb, var(--blue) 10%, transparent), transparent 72%); opacity 0.35. `.lp-demo[data-inview='true']::before` sets opacity 1, with transition opacity var(--lp-enter).
- `.lp-demo-head` (row, space-between); `.lp-demo-title` (16px/600); `.lp-demo-lead` (12.5px muted; max 64ch); `.lp-demo-body` (flex 1; display flex column; gap 12px).
- `.lp-demo-status`: mono 10.5px; min-height 16px; color var(--ink); border-top 1px solid var(--line); padding-top 8px.
- `.lp-demo-foot`: caption.
- `.lp-demo-actions`: flex; wrap; gap 8px. At 599px and below, children are full width with min-height 44px.

**Controls**
- `.lp-button-lg`: add to `.button`. padding 12px 22px; radius var(--radius-control-lg); font-size 13.5px; min-height 44px.
- `.lp-sheen`: relative; overflow hidden; isolation isolate. `::after` (reserved) is a gradient band, rgba(255, 255, 255, 0.35) at 50%, at z-index -1. It runs `lp-sheen` 700ms on hover, when motion is on.
- `.lp-chip`: a mono 10.5px pill button.
  - height 28px; padding 0 10px; border 1px solid var(--line); background var(--surface); radius var(--radius-pill).
  - `[aria-pressed='true']` sets background var(--blue-light), color var(--blue) and border-color var(--border-hover).
  - At 599px and below, min-height 36px.
- `.lp-select`: height 30px; radius var(--radius-control); border 1px solid var(--line); font 11px; padding 0 8px; background var(--surface); max-width 100%.
- `.lp-seg` (SegmentedControl) has these parts:
  - `.lp-seg-legend`: .lp-micro. `.lp-seg-legend-hidden` is visually hidden.
  - `.lp-seg-options`: inline-flex; wrap; gap 4px; padding 3px; background var(--surface); border 1px solid var(--line); radius var(--radius-pill).
  - `.lp-seg-input`: visually hidden.
  - `.lp-seg-pill`: 12px/550; padding 6px 12px; radius var(--radius-pill).
  - Checked (`.lp-seg-input:checked + .lp-seg-pill`): background var(--blue-light), color var(--blue).
  - Focus (`.lp-seg-input:focus-visible + .lp-seg-pill`): outline 2px solid var(--blue); outline-offset 2px.
  - `.lp-seg-full` makes the options full width, with equal flex.
- `.lp-link`: color var(--blue); 12.5px/550; inline-flex; gap 4px. Underline on hover.

**Status**
- `.lp-dot`: 6px; radius var(--radius-pill); background currentColor. Colour modifiers: `.lp-dot-green`, `.lp-dot-blue`, `.lp-dot-warning` (var(--warning-ink)), `.lp-dot-danger`.
- `.lp-pulse`: add to a positioned dot. `::after` (reserved) is a ring with the same background, animated `lp-pulse` 1600ms ease-out infinite when motion is on. `.lp[data-ambient='paused'] .lp-pulse::after` sets animation-play-state paused.
- `.lp-bar`: height 2px; background var(--line); radius var(--radius-pill); overflow hidden.
  - `.lp-bar-fill`: height 100%; background var(--blue); transform-origin left; transform scaleX(0).
  - `[data-run='fill']` runs `lp-fill var(--lp-bar-ms) linear forwards`.
  - `[data-run='drain']` runs `lp-drain var(--lp-bar-ms) linear forwards` and starts at scaleX(1).
  - `[data-run='full']` is scaleX(1); `[data-run='empty']` is scaleX(0).
  - When motion is off, 'fill' renders at scaleX(1) and 'drain' at scaleX(0).
- `.lp-scan-host`: relative; overflow hidden. `[data-scanning='true']::after` (reserved) is a band 12% wide with background linear-gradient(90deg, transparent, var(--lp-tint-12), transparent), running `lp-scan` 1100ms var(--lp-ease-in-out) once.

**Reveal and hover**
- `.lp-reveal`: visible by default.
  - Under `.lp[data-motion='on']`, `.lp-reveal:not([data-inview='true'])` sets opacity 0.
  - `.lp-reveal[data-inview='true']` runs `lp-rise var(--lp-enter) var(--lp-ease-out) backwards`, with animation-delay calc(min(var(--i, 0), 6) * var(--lp-stagger)).
  - Fill mode 'backwards' releases the transform afterwards, so hover transforms keep working.
- `.lp-anim-rise`, `.lp-anim-slide`, `.lp-anim-fade`, `.lp-anim-tick`: apply to freshly mounted or keyed elements. They run lp-rise (420ms), lp-slide (320ms), lp-fade (320ms) and lp-tick (180ms), with var(--lp-ease-out) and fill backwards, only when motion is on.
- `.lp-lift`:
  - transition transform 220ms var(--lp-ease-out), border-color 220ms, box-shadow 220ms.
  - `@media (hover: hover)`: hover sets border-color var(--border-hover) and box-shadow var(--shadow-hover). With motion on, it also sets translateY(-2px).
  - `:focus-within` sets border-color var(--border-hover).
- `.lp-spot`: relative; isolation isolate.
  - `::after` (reserved): absolute; inset 0; radius inherit; z-index -1; pointer-events none; opacity 0; background radial-gradient(280px circle at var(--mx, 50%) var(--my, 50%), var(--lp-spot), transparent 70%); transition opacity var(--lp-state).
  - Under `@media (hover: hover) and (pointer: fine)` with motion on, hover sets opacity 1.
- `.lp-bezel`: a child span; absolute; inset 10px; pointer-events none. It is eight no-repeat `linear-gradient(var(--lp-bezel) 0 0)` layers sized 12px 1px and 1px 12px at the four corners.

**Reduced-motion block**
`@media (prefers-reduced-motion: reduce) { .lp *, .lp *::before, .lp *::after { animation-delay: 0s !important; transition-delay: 0s !important; } }`

**Reserved pseudo-elements.** Owners must not use these on elements that carry the class:
- `.lp-glass::before`
- `.lp-demo::before`
- `.lp-spot::after`
- `.lp-sheen::after`
- `.lp-pulse::after`
- `.lp-scan-host::after`

**Header comment.** The top of base.css carries a comment recording that the public page, and only the public page, adds transform and opacity motion scoped to `.lp`, with no overshoot. The console keeps the rule 'Nothing moves'.

### 4.3 Owner prefixes
Each owner uses these for classes and keyframes. No two owners share a prefix.
- **hero**: `.lp-bar-*`, `.lp-hero-*`, `.lp-facts`, `.lp-fact*`, `.lp-console-*`; keyframes `lp-hero-*`
- **agent-run**: `.lp-run-*`, `.lp-approval-*`; keyframes `lp-run-*`
- **failover**: `.lp-route-*`; keyframes `lp-route-*`
- **answer**: `.lp-cite-*`; keyframes `lp-cite-*`
- **roles-and-sections**: `.lp-roles-*`, `.lp-agents-*`, `.lp-audit-*`, `.lp-platform-*`, `.lp-limits-*`, `.lp-cta-*`, `.lp-footer-*`; keyframes `lp-roles-*`, `lp-audit-*`

### 4.4 Radius map
Families are never mixed on one element.

| Radius | Used for |
|---|---|
| var(--radius-drawer) | the solid bar |
| var(--radius-card) | demo tiles, agent tiles, hero layers, roles panel, limits panel, CTA panel |
| var(--radius-glass-tile) | hero trace and approval layers |
| var(--radius-tile) | insets, stages, fact strip, hairline grids, approval card |
| var(--radius-control-lg) | code and payload blocks, large buttons |
| var(--radius-control) | selects, buttons |
| var(--radius-tag) | chips inside the permission map, page thumbnails |
| var(--radius-pill) | dots, pills, segmented controls, meters, bars |

### 4.5 Keyframes in base.css
All are declared inside `@media (prefers-reduced-motion: no-preference)`. Only transform and opacity animate.

| Keyframe | Motion |
|---|---|
| `lp-rise` | from opacity 0, translateY(12px) to none |
| `lp-fade` | opacity 0 to 1 |
| `lp-slide` | from opacity 0, translateX(8px) to none |
| `lp-tick` | from opacity 0, translateY(6px) to none |
| `lp-pulse` | scale(1) at opacity 0.45 to scale(2.2) at opacity 0 |
| `lp-fill` | scaleX(0) to scaleX(1) |
| `lp-drain` | scaleX(1) to scaleX(0) |
| `lp-scan` | translateX(-100%) to translateX(800%) |
| `lp-sheen` | translateX(-120%) to translateX(120%) |
| `lp-drift-1`, `lp-drift-2`, `lp-drift-3` | to translate3d(80px, 60px, 0) scale(1.06); translate3d(-60px, 40px, 0) scale(1.04); translate3d(40px, -50px, 0) scale(1.06) |

The drift keyframes are applied to blobs 1, 2 and 3 at 32s, 38s and 44s, var(--lp-ease-in-out), infinite alternate. They pause when `.lp[data-ambient='paused']`.

### 4.6 Hooks in web/src/hooks/

**`useReducedMotion(): boolean`**
- Built on useSyncExternalStore over `matchMedia('(prefers-reduced-motion: reduce)')`.
- The snapshot is `true` when window or matchMedia is unavailable. jsdom therefore renders static end states.
- The server snapshot is `true`.

**`useInView<T extends Element>(options?: { threshold?: number; rootMargin?: string; once?: boolean }): { ref: React.RefCallback<T>; inView: boolean }`**
- Defaults: threshold 0, rootMargin '0px', once true.
- If IntersectionObserver is undefined, the lazy initial state is `true`.
- setInView is called only inside the observer callback.

**`useReveal<T extends HTMLElement>(): React.RefCallback<T>`**
- Uses one module-level shared IntersectionObserver: threshold 0, rootMargin '0px 0px -12% 0px'.
- On intersect it sets `node.dataset.inview = 'true'` and unobserves the node.
- Without IntersectionObserver, it sets the attribute immediately.
- It returns a cleanup that unobserves (React 19 ref cleanup), and its identity is stable.
- Never render `data-inview` from JSX on the same element.

**`revealStyle(index: number): React.CSSProperties`**
- Exported from useReveal.ts. It returns `{ '--i': String(index) }`, cast through `as React.CSSProperties`.

**`useSequence(): { play: (steps: ReadonlyArray<{ at: number; run: () => void }>) => void; cancel: () => void }`**
- Both functions keep a stable identity.
- `play` first cancels pending timers. When reduced motion is on (via useReducedMotion), it runs every `run` synchronously, sorted by `at`. Otherwise it schedules one setTimeout per step, including `at: 0`.
- Timers are cleared on cancel and on unmount.

**`useCountUp(target: number, active: boolean, durationMs?: number): number`**
- Default duration is 600ms.
- When reduced, it returns `target`.
- With motion on:
  - before the first activation it returns 0;
  - on activation it animates 0 to target with rAF, using an ease-out cubic curve;
  - on a later target change while active, it animates from the displayed value to the new target over 300ms.
- setState happens only in rAF callbacks.
- Callers render `<span aria-hidden="true">{shown}</span><span className="visually-hidden">{target}</span>`.

**`usePointerSpot<T extends HTMLElement>(): React.RefCallback<T>`**
- Attaches pointermove only when `matchMedia('(hover: hover) and (pointer: fine)')` matches and motion is not reduced.
- Writes `--mx` and `--my` in px, relative to the element, coalesced with rAF.
- Returns a cleanup.

### 4.7 Components in web/src/components/landing/shared/

**`LandingRoot({ children }: { children: ReactNode })`**
- Renders `<div className="lp" data-motion={reduced ? 'off' : 'on'} data-ambient={paused ? 'paused' : 'running'}>`.
- Inside it: `<div className="lp-aurora" aria-hidden="true">` with three `<span className="lp-aurora-blob" />`, then `{children}`.
- Provides the motion context.
- Exports `type LandingMotion = { reduced: boolean; ambientPaused: boolean; setAmbientPaused: (paused: boolean) => void }`.
- Exports `useLandingMotion(): LandingMotion`. It calls useContext and useReducedMotion unconditionally. Outside a root it returns `{ reduced, ambientPaused: false, setAmbientPaused: () => {} }`, so components render standalone in tests.

**`LandingSection(props: { id: string; labelledBy: string; children: ReactNode; className?: string; tight?: boolean })`**
- Renders `<section id className="lp-section[ lp-section-tight][ className]" aria-labelledby tabIndex={-1}><div className="lp-shell">{children}</div></section>`.

**`SectionHead(props: { eyebrow: string; title: ReactNode; titleId: string; lead?: ReactNode; aside?: ReactNode; band?: boolean })`**
- Renders `<Reveal className="lp-head">`.
- `.lp-head-main` holds `<Eyebrow>{eyebrow}</Eyebrow>` and `<h2 id={titleId} className={band ? 'lp-h2 lp-h2-band' : 'lp-h2'}>`.
- `.lp-head-aside` holds the lead as `.lp-lead`, then the aside. It is rendered only when lead or aside is present.

**`Reveal(props: { as?: 'div' | 'article' | 'li' | 'figure' | 'aside' | 'header' | 'section'; index?: number; className?: string; id?: string; labelledBy?: string; children: ReactNode })`**
- Adds `lp-reveal`, the useReveal ref, and revealStyle(index ?? 0).

**`DemoFrame(props)`**
Props: `{ area: 'approval' | 'failover' | 'audit' | 'cited'; index: '01' | '02' | '03' | '04'; name: string; title: string; lead: ReactNode; status: string; children: ReactNode; footnote?: string; busy?: boolean }`.

It renders:
```html
<article id={area} data-area={area} className="lp-demo lp-frost lp-spot lp-reveal" style={revealStyle(n)} aria-labelledby={`${area}-title`}>
  <header className="lp-demo-head"><Eyebrow>{index} · {name}</Eyebrow><Tag tone="neutral">Simulated</Tag></header>
  <h3 id={`${area}-title`} className="lp-demo-title">{title}</h3>
  <p className="lp-demo-lead">{lead}</p>
  <div className="lp-demo-body" aria-busy={busy || undefined}>{children}</div>
  <p className="lp-demo-status" role="status" aria-atomic="true">{status}</p>
  {footnote && <p className="lp-demo-foot">{footnote}</p>}
</article>
```
- n is 0, 1, 2 and 3 for approval, failover, audit and cited.
- The ref merges useReveal and usePointerSpot.
- `aria-busy={busy || undefined}` is allowed inside DemoFrame itself: it is a DOM attribute, not an optional prop.

**`SegmentedControl<V extends string>(props)`**
Props: `{ legend: string; value: V; options: ReadonlyArray<{ value: V; label: string }>; onChange: (value: V) => void; disabled?: boolean; hideLegend?: boolean; fullWidth?: boolean }`.
- Renders a `<fieldset className="lp-seg" disabled>` with a legend, then native radio inputs in labels.
- The radio name comes from useId.
- Arrow keys work natively.

**`Icon({ name, size, className }: { name: IconName; size?: 14 | 16 | 18; className?: string })`**
- `IconName = 'check' | 'dash' | 'x' | 'lock' | 'pause' | 'play' | 'arrow-right' | 'link' | 'link-broken' | 'document' | 'route' | 'gate' | 'clock' | 'slash' | 'pencil' | 'reset'`.
- viewBox 0 0 16 16; stroke currentColor; strokeWidth 1.6; fill none; aria-hidden; focusable false. Default size 14.

**`useInPageLink(): (event: React.MouseEvent<HTMLAnchorElement>) => void`**
- Reads the '#id' href and finds the element.
- If the element exists and has scrollIntoView, it calls `preventDefault()`, then `scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' })`, then `history.pushState(null, '', '#' + id)`, then `el.focus({ preventScroll: true })`.
- Otherwise it lets the browser navigate.
- Never add `scroll-behavior: smooth` to html: the router's scrollTo would animate too.

### 4.8 landingFacts.ts, owned by foundation
- `AgentCategory = 'operations' | 'engineering' | 'growth' | 'support'`
- `AGENTS`: an array of `{ id: 'hr' | 'eng' | 'research' | 'support'; name; category; title; detail }`. The copy is verbatim from the current Landing.tsx USE_CASES:
  - HR, operations, 'Screening and onboarding'
  - Engineering Manager, engineering, 'Sprint bookkeeping'
  - Research, growth, 'Market and competitor reports'
  - Customer Support, support, 'Ticket triage'
- `PROVIDERS = ['OpenRouter','Groq','NVIDIA NIM','Google Gemini','AWS Bedrock','Anthropic','OpenAI'] as const`
- `SANDBOX_MODEL = 'Offline sandbox model'`
- `TOOL_SERVERS = ['gmail','calendar','slack','github','jira','drive'] as const`
- `SERVICE_COUNT = 8`; `PERMISSION_CODE_COUNT = 46`; `BUILT_IN_ROLE_COUNT = 5`
- `SIMULATED_PAGE_NOTICE = 'Simulated in your browser. Nothing is sent, and no account is needed.'`
- `CTA = { demo: { label: 'Try a demo account', href: '/sign-in' }, create: { label: 'Create a workspace', href: '/create-workspace' }, signIn: { label: 'Sign in', href: '/sign-in' } } as const`
- `DEMO_ACCOUNTS_HEDGE = 'When this environment offers demo accounts, the sign-in page lists one per role.'`

### 4.9 Patterns every demo owner follows

**Initial state**
- `useReducer(reducer, reduced, initFn)`: initFn returns the static end state when reduced, and idle otherwise.
- Read `reduced` from useLandingMotion.

**Autoplay**
```ts
const { ref, inView } = useInView<HTMLDivElement>({ threshold: 0.45 })
useEffect(() => { if (inView && state.phase === 'idle' && !state.autoplayed) play(steps) }, [inView, state.phase, state.autoplayed, play])
```
- The first step, at `at: 0`, dispatches an event that sets `autoplayed: true`.
- Never guard autoplay with a ref. In StrictMode the first timer is cleared, and a ref would block the replay.

**Announcements**
- The DemoFrame `status` is `''` at mount.
- Announce only at the start and end of a sequence, and on explicit control changes where this spec says so.

**Focus**
- Move focus only after a user action. Never move it on autoplay.
- Implement focus with `focusRequest: { target: ..., nonce: number }` held in reducer state, plus an effect that calls `.focus()`.

**Busy triggers**
- A busy trigger gets `aria-disabled="true"`, and its handler returns early. Never use `disabled` on a focused trigger.

## 5. Density budget at 1440x900
Content width is 1208px.

| Band | Height | Cumulative |
|---|---|---|
| Bar (12 offset + 56) | 68 | 68 |
| Hero (32 top + 540 console) | 572 | 640 |
| Agents (32 + 52 head + 16 + 212 tiles) | 312 | 952 |
| Demos (44 + 64 head + row A about 620 + 16 + row B about 460) | 1204 | 2156 |
| Roles (44 + head 72 + panel about 560) | 676 | 2832 |
| Platform (44 + 170) | 214 | 3046 |
| Limits and CTA (44 + 270) | 314 | 3360 |
| Footer (32 + 64) | 96 | 3456 |

- **Above the fold at 1440x900.** The bar; the whole hero (copy, both calls to action, sandbox caption, four-cell fact strip, complete console with trace and approval card); the agents header and the top of all four agent tiles.
- **Above the fold at 375x812.** The 52px bar; eyebrow, H1 (four lines at 32px), lead, stacked 44px calls to action and caption; the top 250px of the console.
- **Rhythm.** 44px between sections; 16px from head to content; 16px bento gap; 22px tile padding. Tiles in a row stretch to equal height. The hero is never min-height 100vh.

## 6. Hero owner: bar, hero and console

### 6.1 LandingBar.tsx
**Structure**
- `<div className="lp-bar-sentinel" aria-hidden="true" />`: absolute; top 0; left 0; width 1px; height 24px.
- Then `<header className="lp-bar" data-solid="false|true">`:
  - sticky; top 12px; z-index 40; padding-inline var(--lp-gutter).
- `.lp-bar-inner`:
  - max-width calc(var(--lp-max) - 2 * var(--lp-gutter)); margin 0 auto; height var(--lp-bar-h);
  - grid auto 1fr auto; align-items center; gap 16px; padding 0 16px;
  - border 1px solid transparent; radius var(--radius-drawer);
  - transition background-color 220ms, border-color 220ms.
- `[data-solid='true'] .lp-bar-inner`: background var(--lp-glass-strong); backdrop-filter blur(var(--lp-glass-blur)) saturate(1.4), with the prefix; border-color rgba(210, 221, 238, 0.9).

**Content**
- `<Brand />` on the left.
- A centred `<nav aria-label="On this page" className="lp-bar-nav nav-capsule">` holding four `.nav-pill` anchors, each wired to useInPageLink:
  - Agents → #agents
  - Demos → #demos
  - Roles → #roles
  - Limits → #limits
- Actions on the right:
  - `a.button.button-outline` 'Sign in' → /sign-in;
  - `a.button.button-primary.lp-bar-cta` 'Try a demo account' → /sign-in, with `data-visible`.
- When hidden, `.lp-bar-cta[data-visible='false']` has opacity 0, translateX(6px) and visibility hidden, so it leaves the tab order. The visibility transition is delayed by 220ms.

**Observers.** Three IntersectionObservers and no scroll listeners.
1. The sentinel: solid = !isIntersecting.
2. `document.getElementById('lp-hero-ctas')`: CTA visible = !isIntersecting. If the element is missing, the CTA stays visible.
3. Scrollspy over the four section ids, with rootMargin '-45% 0px -50% 0px'. It sets `aria-current="location"` on the matching pill.

**hero.css additions**
- `.lp .nav-pill[aria-current='location']`: same as the page state, background var(--blue-light) and color var(--blue).

**Responsive**
- 899px and below: the nav and `.lp-bar-cta` are display none; `.lp-bar .brand-wordmark` is 22px; top 8px.
- The actions keep 'Sign in' only, which fits in 311px.

### 6.2 Hero.tsx
**Structure**
- `<section className="lp-hero" aria-labelledby="hero-title"><div className="lp-shell lp-hero-grid">`.
- 1180px and above:
  - grid minmax(0,5fr) minmax(0,7fr); gap 32px; padding-top 32px; align-items center;
  - the console column is 540px tall.
- 900–1179px: one column; copy max-width 640px; console max-width 720px and 480px tall.
- 599px and below: the calls to action stack full width with min-height 44px; the console is 380px tall.

**Copy column `.lp-hero-copy`.** Each child gets revealStyle(0..4) for the load stagger.
1. `<Eyebrow>A governed AI workforce</Eyebrow>`
2. The h1:
   - `<h1 id="hero-title" className="lp-hero-title">A team of AI employees your company can <span className="lp-hero-accent">actually authorise</span></h1>`
   - var(--lp-text-display)/1.06; weight 600; letter-spacing -0.022em; max-width 15ch.
   - The accent is `background: linear-gradient(100deg, var(--blue), var(--blue-dark))` with background-clip text. `@supports not (background-clip: text)` falls back to color var(--blue-dark).
3. `<p className="lp-hero-lead">`: 14px/1.55; muted; 46ch. Text: 'Configure agents for the roles you already have, let them work from your own documents and your real tools, and keep a person in front of every action that cannot be taken back.'
4. `<div id="lp-hero-ctas" className="lp-hero-ctas">` holding two anchors:
   - `a.button.button-primary.lp-button-lg.lp-sheen` 'Try a demo account' plus `<Icon name="arrow-right" />`;
   - `a.button.button-outline.lp-button-lg` 'Create a workspace'.
5. `<p className="lp-hero-caption">`: `.lp-dot.lp-dot-green`, then 'Runs on an offline sandbox model out of the box. No API key is needed to look around.'
6. The fact strip:
   - `<ul className="lp-facts lp-glass">`, radius var(--radius-tile) (it overrides the card radius), height 64px.
   - Four `<li>`, divided by `border-left: 1px solid var(--line)` on the second to fourth.
   - Each holds `<a className="lp-fact" href onClick={inPage}>` with `.lp-fact-value` (20px/550, tabular) and `.lp-fact-label` (.lp-micro).
   - The cells:
     - '4' / 'agents' → #agents
     - '7 + 1' / 'providers + sandbox' → #failover
     - '6' / 'tool servers' → #approval
     - '46' / 'permission codes' → #roles
   - Focus uses outline-offset -2px.
   - 599px and below: a 2x2 grid; the second row gets border-top 1px solid var(--line); the third cell drops its left border.

**Load animation.** `.lp[data-motion='on'] .lp-hero-copy > * { animation: lp-hero-enter var(--lp-hero) var(--lp-ease-out) backwards; animation-delay: calc(var(--i) * 60ms) }`, where lp-hero-enter goes from opacity 0 and translateY(16px) to none. The console layers start at 120ms (back) and 360ms (trace).

### 6.3 HeroConsole.tsx and heroConsoleModel.ts

**Figure structure**
- `<figure className="lp-console" aria-labelledby="lp-console-caption">` contains:
  - `<span className="lp-bezel" aria-hidden="true" />`
  - `<div className="lp-console-stage">`, which carries --px and --py and has perspective none.
  - `<figcaption id="lp-console-caption" className="lp-console-caption">`, mono 10px muted: 'Illustration of the console, simulated in your browser. Figures are examples.'
  - `<p className="visually-hidden">`: 'The Command Map with four agents running, a routing trace that skipped an unavailable provider, and an email waiting for a person to approve it.'
- Each layer is a `.lp-console-depth` wrapper that carries the parallax transform, containing a `.lp-console-layer` that carries the glass surface and the arrival animation.
- A `.lp-console-pool` sits under each layer as an aria-hidden span: an ellipse radial-gradient(var(--lp-pool), transparent 70%). It replaces a drop shadow.

**(1) Back layer: `.lp-console-back`, aria-hidden**
- .lp-glass; radius var(--radius-card); 100% wide; 400px tall (340px at 599px and below).
- Chrome row:
  - an inline L-mark SVG, copying the Brand path with stroke var(--blue);
  - a mini capsule with 'Command Map' active, 'Agents' and 'Approvals' (with a badge showing approvalsBadge when it is 1);
  - mono 'sandbox model' on the right.
- Eyebrow text 'Your workforce, in focus'; title 'Command Map' at 18px.
- Stat row with four cells: 'Runs total', 'Running', 'Waiting for approval', 'Completed'.
- A 'Live activity' table styled with the real `.table` class. Columns: Agent (Tag withDot in the category tone), Goal, Status (Tag), Time (mono, tabular, 's' unit).
- At container width 520px and below, the Goal column is hidden and the stats form a 2x2 grid.

**(2) Trace layer: `.lp-console-trace`, aria-hidden**
- radius var(--radius-glass-tile); 292px wide; right -12px; bottom 40px; hidden at container width 520px and below.
- Eyebrow text 'Routing trace · sample run'.
- `<ol>` of mono 11px lines. Each line is a three-column grid: `.lp-console-verb` (5ch), provider, detail.

**(3) Approval layer: `.lp-console-approval`**
- radius var(--radius-glass-tile); 300px wide; bottom 0.
- At 1180px and above, left -32px, so it overhangs into the grid gap.
- At 520px container width and below, 92% wide and centred at the bottom.
- Content:
  - eyebrow text 'gmail.send_message'; title 'Send the welcome email';
  - Tag operations withDot 'HR'; Tag warning 'Waiting for approval';
  - caption 'Exactly what will be sent', then three mono lines: `to newhire@example.com`, `subject Welcome to the team`, `body Hello, welcome to the team.`;
  - `a.lp-link` 'Decide it yourself' → #approval, via useInPageLink.
- Before it arrives, the layer is rendered with `inert` and `aria-hidden="true"`, at opacity 0.

**Model: heroConsoleModel.ts** (a pure function)
```ts
export const CYCLE_MS = 10_000
export const COMPOSED_ELAPSED_MS = 5_000
export type HeroRow = { id: 'hr' | 'eng' | 'research' | 'support'; agent: string; category: AgentCategory; goal: string; status: 'running' | 'completed' | 'waiting'; seconds: number }
export type HeroTraceLine = { id: 1 | 2 | 3; verb: 'skip' | 'fail' | 'ok'; provider: string; detail: string }
export type HeroFrame = { stats: { total: number; running: number; waiting: number; completed: number }; rows: ReadonlyArray<HeroRow>; trace: ReadonlyArray<HeroTraceLine>; approvalArrived: boolean; approvalsBadge: 0 | 1 }
export function heroFrame(elapsedMs: number): HeroFrame
```

Rules, with e = elapsed % 60000, c = floor(e / 10000) and t = e % 10000:

**Arrival**
- arrived = elapsed >= 4500. This never resets.

**Stats**
- total = 18 + c + (t >= 6000 ? 1 : 0)
- completed = 15 + c + (t >= 3000 ? 1 : 0)
- waiting = arrived ? 1 : 0
- running = total - completed - waiting

**Rows**
- **HR**: goal 'Onboard the new hire'. Status is 'waiting' when arrived, otherwise 'running'. seconds = 42 + floor(min(elapsed, 4500) / 1000).
- **Engineering Manager**: goal 'Post the standup note'. Running. seconds = 18 + floor(e / 1000).
- **Research**: goal 'Summarise competitor pricing'.
  - When t < 3000: running, with seconds = (c === 0 ? 61 : 4) + floor(t / 1000).
  - When 3000 <= t < 6000: completed, with seconds frozen at the t = 3000 value.
  - When t >= 6000: running, with seconds = floor((t - 6000) / 1000).
- **Customer Support**: goal 'Triage the overnight queue'. Completed. seconds 51.

**Trace lines** (visible while t < 9600)
- `{1, skip, groq, 'circuit open'}` when t >= 800
- `{2, fail, openrouter, '429, honoured Retry-After, failed over'}` when t >= 1800
- `{3, ok, gemini, 'answered'}` when t >= 3000

**Driver**
- The initial elapsed value is `reduced ? COMPOSED_ELAPSED_MS : 0`.
- `setInterval(250)` adds 250 to elapsed only while all of these hold: motion is on; not ambientPaused; the figure is at least 30% in view (useInView, threshold 0.3, once false); and `!document.hidden`.
- The interval is created and cleared in an effect, and state changes only in its callback.
- When paused, the frame freezes where it is.
- Changing values are keyed and use `.lp-anim-tick`. Running status dots use `.lp-dot.lp-dot-blue.lp-pulse`. The approval layer arrives with `.lp-anim-rise`.

**Pause motion button**
- `<button type="button" className="lp-hero-pause" aria-pressed={ambientPaused}>` with `<Icon name={ambientPaused ? 'play' : 'pause'} />` and the constant label 'Pause motion'.
- It sits at the bottom right of the figure and calls setAmbientPaused. It is not rendered when reduced.
- It pauses the hero loop, the aurora drift and every pulse on the page (WCAG 2.2.2).

**Reduced motion.** The figure shows the frame at 5000ms: three trace lines, the approval card present, the HR row waiting, and the Research row completed. There is no interval and no pause button.

### 6.4 usePointerParallax.ts (hero only)
- Signature: `usePointerParallax(enabled: boolean): { hostRef: React.RefCallback<HTMLElement>; stageRef: React.RefCallback<HTMLElement> }`.
- enabled = motion on, not ambientPaused, `matchMedia('(pointer: fine) and (min-width: 1180px)')` matches, and the hero is in view.
- pointermove on the host is normalised to -1..1 and lerped toward the target at 0.1 per rAF frame. The loop stops when the change is below 0.001, and pointerleave sets the target to 0.
- It writes --px and --py on the stage.

**Layer offsets.** Translate only, no rotation.
- back: `translate3d(calc(var(--px, 0) * 4px), calc(var(--py, 0) * 4px), 0)`
- trace: 10px and 8px
- approval: 16px and 12px

## 7. Agent-run owner: approval gate (ApprovalDemo.tsx, approvalModel.ts, PayloadView.tsx)

### 7.1 Frame
DemoFrame props:
- area 'approval'; index '01'; name 'Approval gate'; title 'It asks before it acts'.
- lead: 'Reads and drafts run freely. Anything outbound or destructive, such as gmail.send_message, slack.post_message or calendar.delete_event, parks the run until a person with approval:decide answers.'
- footnote: 'Time is compressed. A real approval waits until its deadline.'

### 7.2 Layout
**Controls row: `.lp-run-controls`**
- `<SegmentedControl legend="Deciding as" options={[manager 'Manager', employee 'Employee', viewer 'Viewer']} />`.
- In idle only, also `Button primary 'Start the run'`.

**Stage: `.lp-run-stage`**
- Its ref comes from useInView with threshold 0.45.
- At container width 640px and above: grid minmax(0,5fr) minmax(0,7fr), gap 16px. Below that, the columns stack.

**Left column: `<section className="lp-run-timeline lp-inset" aria-labelledby="run-timeline-title">`**
- Header: `<h4 id="run-timeline-title" tabIndex={-1} className="lp-mono">Run · HR agent</h4>` and the run status Tag.
- `<ol>` of five `li.lp-run-step[data-state]`, each 44px tall. Each row has a 22px marker, a mono label, a muted detail, and a Tag on the right.
- The connector is `.lp-run-step::before`: a 2px line with background var(--line). A done step's line is linear-gradient(var(--blue), var(--chart-secondary)).

**Right column: `.lp-run-card-slot`**
- When the card is hidden: `p.lp-run-placeholder`, 'The approval will appear here when the run reaches gmail.send_message.', in a dashed 1px var(--line) box with radius var(--radius-tile).
- Otherwise: `<article className="lp-approval lp-anim-rise" aria-labelledby="approval-card-title">`, with background var(--surface), border 1px solid var(--line) and radius var(--radius-tile). While waiting it gets border-color var(--border-hover).

**Card contents, in order**
1. Eyebrow text 'Approval requested · gmail.send_message'.
2. `<h4 id="approval-card-title" tabIndex={-1}>Send the welcome email to the new hire</h4>`.
3. Meta row: Tag operations withDot 'HR'; mono 'Requested just now'; mono deadline label; card status Tag.
4. `.lp-bar` deadline bar. Its fill is `data-run='full'`, or `'drain'` with `--lp-bar-ms: 1200ms` while expiring, or `'empty'` once expired.
5. The caption 'Exactly what will be sent', then `<PayloadView payload={PAYLOAD} label="Email that will be sent" />`.
6. The action area, which depends on the decider:
   - **Manager**: `Button primary 'Approve'` with the check icon, `Button outline 'Reject'`, and `Button quiet 'Let the deadline pass'` with the clock icon.
   - **Employee**: `p.lp-approval-note` with the lock icon and 'Your role can see this approval but cannot decide it. Deciding needs approval:decide, which the manager, admin and owner roles hold.', then the quiet 'Let the deadline pass'.
   - **Viewer**: the card body is replaced by `p.lp-approval-note` with the lock icon and 'A viewer does not see the approval queue, because the role lacks approval:read. The run stays parked until someone who holds approval:decide answers.', then the quiet 'Let the deadline pass'.
   - **Terminal states**: the actions are replaced by `.lp-run-result`:
     - `<h4 tabIndex={-1}>`;
     - a paragraph;
     - `Button outline 'Run it again'` with the reset icon.

**Payload.** `PAYLOAD = { to: 'newhire@example.com', subject: 'Welcome to the team', body: 'Hello, welcome to the team.' }`.
- PayloadView renders `<pre className="lp-code lp-run-payload" aria-label={label}>` in pretty-printed JSON across five lines.
- Tokens are spans: `.lp-run-json-key` (var(--eyebrow)), `.lp-run-json-str` (var(--ink)), `.lp-run-json-punc` (var(--muted)).
- Braces are rendered as string expressions such as `{'{'}`.

### 7.3 Model: approvalModel.ts

**Types**
```ts
export type Decider = 'manager' | 'employee' | 'viewer'
export type StepId = 'plan' | 'draft' | 'gate' | 'send' | 'summary'
export type StepState = 'ghost' | 'active' | 'done' | 'waiting' | 'approved' | 'rejected' | 'expired' | 'skipped'
export type Phase = 'idle' | 'running' | 'parked' | 'approving' | 'completed' | 'rejected' | 'expiring' | 'expired'
export type CardStatus = 'hidden' | 'waiting' | 'approved' | 'rejected' | 'expired'
export type FocusTarget = 'none' | 'timeline' | 'card' | 'result'
export type ApprovalState = { phase: Phase; steps: Record<StepId, StepState>; card: CardStatus; decider: Decider; autoplayed: boolean; byUser: boolean; announcement: string; focus: { target: FocusTarget; nonce: number } }
export type ApprovalEvent = { type: 'START'; byUser: boolean } | { type: 'STEP'; step: StepId; state: StepState } | { type: 'PARK' } | { type: 'APPROVE' } | { type: 'RESOLVE_APPROVED' } | { type: 'REJECT' } | { type: 'LET_EXPIRE' } | { type: 'RESOLVE_EXPIRED' } | { type: 'SET_DECIDER'; decider: Decider } | { type: 'RESET' }
export const canDecide = (d: Decider): boolean => d === 'manager'
export function initApprovalState(reduced: boolean): ApprovalState
export function approvalReducer(s: ApprovalState, e: ApprovalEvent): ApprovalState
export const PAYLOAD: Readonly<{ to: string; subject: string; body: string }>
export const STEP_COPY: Record<StepId, { label: string; detail: string }>
```
- Reduced initial state: phase parked; plan and draft done; gate waiting; send and summary ghost; card waiting; autoplayed true.
- Idle initial state: every step ghost; card hidden.

**Step copy**
- plan: 'Model call · sandbox model' / 'Reads the new hire record and plans the email'
- draft: 'Tool · gmail.draft_message' / 'Draft written. Reads and drafts run freely.'
- gate: 'Approval · gmail.send_message' / 'Sending leaves the workspace, so the run parks here.'
- send: 'Tool · gmail.send_message' / 'Sent through the sandbox gmail driver. Nothing left your browser.'
- summary: 'Model call · same model' / 'Resumes on the same model and writes the run summary.'

**Step-state visuals**

| State | Marker | Tag |
|---|---|---|
| ghost | outline, row at 45% opacity | none |
| active | Spinner | blue 'Running' |
| done | check | success 'Done' |
| waiting | pause | warning 'Waiting for approval' |
| approved | check | success 'Approved' |
| rejected | x | danger 'Rejected' |
| expired | clock | neutral 'Expired' |
| skipped | dash, row at 50% opacity | neutral 'Not run' |

**Run status Tag**

| Phase | Tag |
|---|---|
| idle | neutral 'Not started' |
| running | blue 'Running' |
| parked, expiring | warning 'Waiting for approval' |
| approving | blue 'Resuming' |
| completed | success 'Completed' |
| rejected | danger 'Cancelled' |
| expired | neutral 'Cancelled' |

**Deadline label**
- 'Example deadline: 30 min', then 'Deadline passing' while expiring, then 'Deadline passed'.

**Sequences.** Played with useSequence. Times are in ms.
- **START**: at 0, START (phase running, plan active, announcement 'Running. Drafting the email.'); at 900, plan done and draft active; at 1700, draft done; at 2000, gate waiting and PARK.
  - PARK sets phase parked, card waiting, and announcement 'The run is parked, waiting for approval.'
  - If byUser, PARK also sets focus target 'card'.
- **APPROVE** (guard: phase parked and canDecide): at 0, APPROVE (phase approving, gate approved, card approved, announcement 'Approved. Resuming the run.'); at 600, send active; at 1300, send done and summary active; at 2000, summary done and RESOLVE_APPROVED.
  - RESOLVE_APPROVED sets phase completed, announcement 'The run resumed on the same model and completed.', and focus 'result'.
- **REJECT** (guard: phase parked and canDecide): instant. Phase rejected; gate rejected; send and summary skipped; card rejected; announcement 'An approver rejected the action this run needed.'; focus 'result'.
- **LET_EXPIRE** (guard: phase parked, any decider): at 0, LET_EXPIRE (phase expiring, announcement 'Letting the deadline pass.'); at 1200, RESOLVE_EXPIRED.
  - RESOLVE_EXPIRED sets phase expired, gate expired, send and summary skipped, card expired, announcement 'Nobody decided in time, so the approval was rejected by default. Nothing is sent because a deadline passed.', and focus 'result'.
- **SET_DECIDER**: accepted in any phase. Announcements:
  - manager: 'Deciding as a manager. This role holds approval:decide.'
  - employee: 'Deciding as an employee. This role can see the approval but cannot decide it.'
  - viewer: 'Deciding as a viewer. This role does not see the approval queue.'
- **RESET** ('Run it again'): back to idle with focus 'timeline', then plays START with byUser true. Under reduced motion it goes straight to parked, with focus 'card'.
- **Autoplay**: follows 4.9, using START with byUser false.

**Result copy**
- completed: h4 'Sent, then completed'. Paragraph: 'Approved by you, as a manager. The run resumed on the same model and completed. The email went to the sandbox gmail driver, so nothing left your browser.'
- rejected: h4 'Run cancelled'. Paragraph: 'An approver rejected the action this run needed. Nothing was sent.'
- expired: h4 'Run cancelled'. Paragraph: 'Nobody decided in time, so the approval was rejected by default. Nothing is sent because a deadline passed.'

**375px**
- The segmented control is full width, and so is Start.
- The timeline sits above the card.
- The actions stack at full width with min-height 44px.
- The payload wraps.

## 8. Failover-and-answer owner

### 8.1 Provider failover (FailoverDemo.tsx, failoverModel.ts)

**Frame.** DemoFrame props:
- area 'failover'; index '02'; name 'Model routing'; title 'It keeps working when a provider does not'.
- lead: 'Each agent has an ordered chain of provider candidates. Break one and watch the router skip it, record why, and try the next.'
- footnote: 'Example chain. Out of the box, agents answer on the offline sandbox model. The failures are ones you inject and say nothing about any real provider.'

**Model**
```ts
export type ProviderId = 'openrouter' | 'groq' | 'gemini' | 'anthropic'
export const CHAIN: ReadonlyArray<{ id: ProviderId; name: string }> = [{openrouter,'OpenRouter'},{groq,'Groq'},{gemini,'Google Gemini'},{anthropic,'Anthropic'}]
export type SkipFault = 'disabled' | 'no_credential' | 'circuit_open' | 'missing_capability' | 'context_too_small' | 'over_budget'
export type CallFault = 'rate_limited' | 'credential_rejected' | 'timeout' | 'model_not_found' | 'safety_refusal'
export type Fault = 'healthy' | SkipFault | CallFault
export type Policy = 'FAIL_CLOSED' | 'DEGRADE_TO_SANDBOX'
export type Config = Record<ProviderId, Fault>
export type PresetId = 'throttled' | 'bad_key' | 'provider_down' | 'safety_refusal' | 'everything_down'
export const PRESETS: Record<PresetId, { label: string; config: Config }>
export type NodeState = 'idle' | 'calling' | 'waiting' | 'backing_off' | 'skipped' | 'failed' | 'answered' | 'stopped' | 'not_tried'
export type TraceLine = { verb: 'skip' | 'try' | 'wait' | 'fail' | 'ok' | 'stop' | 'done' | 'sandbox'; provider: string; detail: string }
export type Outcome = { kind: 'answered'; by: string; failovers: number; skips: number } | { kind: 'stopped'; by: string } | { kind: 'failed_closed' } | { kind: 'degraded' }
export type RouteEvent = { at: number; kind: 'hop'; index: number } | { at: number; kind: 'node'; index: number; state: NodeState; label: string } | { at: number; kind: 'trace'; line: TraceLine } | { at: number; kind: 'bar'; index: number; ms: number } | { at: number; kind: 'breaker'; id: ProviderId } | { at: number; kind: 'sandbox' } | { at: number; kind: 'outcome'; outcome: Outcome }
export function planRoute(config: Config, policy: Policy): RouteEvent[]
export function summarise(o: Outcome): string
export const FAULT_LABEL: Record<Fault, string>
export const SKIP_REASON: Record<SkipFault, string>
```

**Fault labels**
- Healthy
- Disabled; No credential; Circuit open; Missing a capability; Context too small; Over budget
- Rate limited (429); Credential rejected (401 or 403); Timeout or 5xx; Model not found; Safety refusal

**Skip reasons**
- disabled; no credential; circuit open; missing a capability; context too small; over budget

**Presets**

| Preset | Label | OpenRouter | Groq | Google Gemini | Anthropic | Result |
|---|---|---|---|---|---|---|
| throttled | 'Throttled' | rate_limited | healthy | healthy | healthy | Groq answers |
| bad_key | 'Bad key' | credential_rejected | no_credential | healthy | healthy | Gemini answers |
| provider_down | 'Provider down' | timeout | circuit_open | model_not_found | healthy | Anthropic answers |
| safety_refusal | 'Safety refusal' | safety_refusal | healthy | healthy | healthy | stops at OpenRouter |
| everything_down | 'Everything down' | circuit_open | rate_limited | timeout | over_budget | exhausted |

**planRoute timings.** A cursor starts at 0 and advances per candidate i.
1. Hop to node i, then cursor += 320.
2. **Skip fault**: node skipped with label `Skipped: ${reason}`, and trace `{skip, id, reason}`. Then cursor += 240.
3. **Call**: node calling, label 'Calling', and trace `{try, id, 'called'}`. Then cursor += 600, followed by one of:
   - **healthy**: node answered, 'Answered'; trace `{ok, id, 'answered'}`; outcome answered. Stop.
   - **rate_limited**: node waiting, 'Retry-After 2 s'; bar with ms 1200; trace `{wait, id, '429 rate limited, honoured Retry-After'}`. Then cursor += 1200. Node failed, 'Failed over: 429'; trace `{fail, id, 'failed over'}`.
   - **credential_rejected**: node failed, 'Failed: credential rejected'; trace `{fail, id, '401, credential marked invalid, breaker opened, failed over'}`; breaker event.
   - **timeout**: node backing_off, 'Backing off'; bar with ms 800. Then cursor += 800. Node failed, 'Failed over: timeout'; trace `{fail, id, 'timeout, backed off, failed over'}`.
   - **model_not_found**: node failed, 'Failed: model not found'; trace `{fail, id, 'model not found, marked unavailable, failed over'}`.
   - **safety_refusal**: node stopped, 'Stopped: safety refusal'; trace `{stop, id, 'safety refusal, chain stopped'}`; every later node not_tried, 'Not tried'; outcome stopped. Stop.
4. **Chain exhausted**: trace `{done, 'chain', 'every candidate failed'}`.
   - FAIL_CLOSED: outcome failed_closed.
   - DEGRADE_TO_SANDBOX: a sandbox event appends a fifth node, 'Offline sandbox model', in the answered state; trace `{sandbox, 'sandbox', 'answered on the offline sandbox model'}`; outcome degraded.
5. The node state of every entry uses the same cursor value as its trace line.

**Summaries**
- **answered**, with one or more failovers or skips: `Answered by ${by} after ${n} ${n === 1 ? 'failover' : 'failovers'}${skips > 0 ? ` and ${skips} ${skips === 1 ? 'skip' : 'skips'}` : ''}. Every attempt and skip is in the trace.`
  - With zero failovers, the text starts 'Answered by X' and continues 'with no failover'.
- **stopped**: `${by} refused on safety grounds. The chain stopped instead of sending the prompt to another vendor.`
- **failed_closed**: 'Every candidate failed. The run failed closed, and nothing was guessed.'
- **degraded**: 'Every candidate failed. The run degraded to the offline sandbox model, and the trace says so.'

**Component state**
`{ phase: 'ready' | 'routing' | 'done'; config: Config; policy: Policy; nodes: Array<{ state: NodeState; label: string }>; sandboxShown: boolean; trace: TraceLine[]; hop: number; bar: { index: number; ms: number } | null; outcome: Outcome | null; breakerNote: ProviderId[]; autoplayed: boolean; announcement: string }`

**Transitions**
- **SEND**:
  - Plays planRoute(config, policy) through useSequence.
  - Announcement 'Routing through four candidates.'
  - The configuration fieldset is disabled and Send gets aria-disabled.
  - The outcome event sets phase done, announcement summarise(o), and applies breakers: every credential_rejected id becomes circuit_open in config and is added to breakerNote.
- **Any edit** in done (preset, select or policy) returns to ready, clears the trace and resets the nodes.
- **RESET** restores the throttled preset and clears breakerNote.
- **Autoplay**: throttled, per 4.9.
- **Reduced initial state**: the fully resolved throttled run, built by folding planRoute events synchronously.

**UI**
- **Presets**: `div.lp-route-presets[role=group][aria-label='Load a scenario']` holding five `button.lp-chip`. aria-pressed is true when the config deep-equals that preset.
- **Chain**: `<fieldset className="lp-route-config" disabled={routing}>` wraps `<ol className="lp-route-chain">`.
  - Each `li.lp-route-node[data-state]` is 56px tall at container width 520px and above. It holds:
    - `.lp-route-index`, mono '01'–'04', in a 22px circle;
    - `.lp-route-name` at 13px/550;
    - a `<label className="visually-hidden" htmlFor>` reading `What happens at ${name}`;
    - `<select className="lp-select">` with optgroups 'Answers', 'Skipped before calling' and 'Fails when called';
    - the state Tag;
    - when the id is in breakerNote, `.caption` 'Breaker opened by the last request';
    - a `.lp-bar` under the node when that node's bar is active (data-run 'fill', --lp-bar-ms).
  - Node background tints: calling var(--lp-tint-08); failed var(--lp-bad-bg); answered var(--lp-ok-bg); not_tried at 50% opacity. Text always accompanies the colour.
- **Tag tones**: idle neutral 'Standing by'; calling blue; waiting and backing_off warning; skipped neutral; failed danger; answered success; stopped danger; not_tried neutral.
- **Packet**: `span.lp-route-packet[aria-hidden]`, an 8px blue dot with an 18px radial halo drawn by a background, never a shadow.
  - It sits in the left gutter with `transform: translateY(calc(var(--lp-hop) * 64px))` and transition 320ms var(--lp-ease-in-out).
  - It is hidden at container width 520px and below, and when reduced.
- **Policy**: `<SegmentedControl legend="When every candidate fails">` with 'Fail closed (default)' and 'Degrade to sandbox'.
- **Actions**: `Button primary 'Send a request'` and `Button quiet 'Reset'`.
- **Trace**: `<ol className="lp-route-trace lp-code" aria-label="Routing trace">`.
  - It is not a live region. Each line is a grid of three columns: verb (5ch), provider (11ch), detail.
  - Lines enter with `.lp-anim-slide`.
  - max-height 188px; overflow-y auto; tabIndex 0 when it overflows.
- **Outcome Tag** after the chain: 'Answered by X' success; 'Stopped on purpose' warning; 'Failed closed' danger; 'Degraded to sandbox' warning.

**375px**
- Presets form a two-column grid.
- Chain rows wrap: name and Tag on the first line, the select full width on the second.
- The trace wraps.

### 8.2 Cited answer (CitedAnswerDemo.tsx, citedAnswerModel.ts)

**Frame.** DemoFrame props:
- area 'cited'; index '04'; name 'Cited answers'; title 'It answers from your documents'.
- lead: 'Hybrid keyword and vector retrieval over the workspace documents, with the document and page behind every claim.'
- footnote: 'Simulated answer, written from the two passages shown. Retrieval is replayed, not run.'
- busy = phase answering.

**Model**
```ts
export type QuestionId = 'send' | 'jupiter'
export const QUESTIONS: Record<QuestionId, string>
export type Segment = { kind: 'text'; text: string } | { kind: 'cite'; n: 1 | 2 }
export const ANSWER: ReadonlyArray<Segment>
export const PASSAGES: Record<1 | 2, { page: 4 | 5; quote: string }>
export const DECLINE = 'No document in this workspace supports an answer to that question.'
export function wordCount(segments: ReadonlyArray<Segment>): number
```
- **QUESTIONS**: send 'What happens when an agent wants to send an email?'; jupiter 'How many moons does Jupiter have?'
- **ANSWER**: 'It does not send it straight away. Outbound actions go to an approval queue, where a person reviews them before they run ' [1] '. The proposal gives that review to a manager or the CEO ' [2] '.'
- **PASSAGES**:
  - 1 is page 5: 'An approval queue where humans review outbound and high-impact actions before they run'
  - 2 is page 4: 'Approve agent actions - Manager / CEO - Review outbound or high-impact actions in an approval queue before they execute.'
- The document is 'Project_Proposal.pdf', which has 8 pages. This was verified.

**State**
`{ phase: 'idle' | 'retrieving' | 'answering' | 'answered' | 'declined'; question: QuestionId | null; words: number; hits: ReadonlyArray<4 | 5>; active: 1 | 2 | null; pinned: 1 | 2 | null; autoplayed: boolean; announcement: string }`

**ASK(q)**, allowed from any phase; it cancels the running sequence.
- At 0: user bubble; phase retrieving; `data-scanning='true'` on the pages; announcement 'Searching the workspace documents.'
- At 1100:
  - send: hits [5, 4], shown with `.lp-anim-rise` rings.
  - jupiter: phase declined; announcement 'No document supports an answer. The agent did not guess.' Stop.
- At 1400: phase answering. Words are revealed at 22ms each, one timed step per word generated from wordCount.
- At the end: phase answered; announcement 'Answer ready. It cites two passages from Project_Proposal.pdf, pages 5 and 4.'

**Other events**
- CLEAR returns to idle.
- Autoplay asks 'send', per 4.9.
- Reduced initial state: answered for 'send', with every word shown and both pages hit.

**Layout**
- At container width 600px and above: grid minmax(0,7fr) minmax(0,5fr), gap 16px. Below that, the sources move under the chat.

**Chat column: `.lp-cite-chat`**
- `div[role=group][aria-label='Ask a question']` holding two pill `button.lp-chip` with aria-pressed, which are full width and left-aligned at 599px and below, plus `Button quiet 'Clear'`.
- User bubble `p.lp-cite-user`: right-aligned; background var(--blue-light); radius var(--radius-tile).
- Agent bubble `div.lp-cite-answer.lp-inset`:
  - header: Tag growth withDot 'Research' and caption 'answering from 1 indexed document';
  - body: the words so far.
- Citation markers are `button.lp-cite-marker`: mono 10px; an 18px pill inside a 24px hit area; text '1' or '2'.
  - aria-label 'Source 1: Project_Proposal.pdf, page 5' (or source 2, page 4).
  - aria-controls 'cite-passage-1' or 'cite-passage-2'.
  - aria-pressed reflects pinned.
  - Hover and focus set active. Mouse leave and blur clear it unless pinned. Click toggles pinned.
- **Declined**: the text is DECLINE, with Tag neutral 'No citation' and caption 'The agent does not guess.'

**Sources column: `aside.lp-cite-sources[aria-label='Sources']`**
- Header: document icon, 'Project_Proposal.pdf' and Tag success 'Indexed'.
- `div.lp-cite-pages.lp-scan-host` holds eight `span.lp-cite-page`: 28 by 36px; radius var(--radius-tag); border 1px solid var(--line); three grey lines drawn with backgrounds; a mono page number.
  - Attributes: data-hit, and data-active when that page is the active citation. The active page gets background var(--blue-light) and border-color var(--border-hover) over 180ms.
- Retrieval line, mono: 'keyword 2 · vector 2 · merged 2 passages', or 'Searching…', or '0 passages matched'.
- `<ol className="lp-cite-passages">` holds `li#cite-passage-1` and `li#cite-passage-2`. Each shows a mono header 'Project_Proposal.pdf · page N' and the quote in 12px ink. The active passage gets the blue-light treatment.
- When declined, an empty state: the document icon and 'Nothing to cite'.

**375px.** The eight thumbnails fit in one row, at 280px.

## 9. Roles-and-sections owner

### 9.1 AgentsSection.tsx and agentGrants.ts
**Section and head**
- `<LandingSection id="agents" labelledBy="agents-title" tight>`.
- `SectionHead` with eyebrow 'What it is for', title 'Four agents, working the way a person would', titleId 'agents-title', and lead 'Each agent is given a role, a set of tools and a limit on what it may do unsupervised.'

**Grid**
- `.lp-agents-grid`: repeat(4, minmax(0,1fr)), gap 16px. 1179px and below: 2x2. 599px and below: one column.

**Tile.** `<Reveal as="article" index={i} className="lp-agents-tile lp-frost lp-lift lp-spot" labelledBy={`agent-${id}-title`}>`, padding 16px, holding:
1. Tag withDot in the category tone, with the agent name.
2. `h3#agent-{id}-title.lp-h3` at 13.5px, with the title.
3. `p.muted` detail from AGENTS.
4. `div[role=group][aria-label='{agent} tools'].lp-agents-tools` holding `button.lp-chip` with aria-pressed. Chips use roving tabindex; Left and Right move and select.
5. `p.lp-agents-grant[aria-live=polite]`: the lock icon when gated, then the sentence. It cross-fades with a keyed `.lp-anim-fade`.

**agentGrants.ts.** `Record<AgentId, ReadonlyArray<{ server: ToolServer; sentence: string; gated: boolean }>>`, verified against DemoAgentSeeder. The first gated entry is pressed by default.
- **HR**:
  - gmail: 'Reads and drafts freely. gmail.send_message waits for a person.' (gated)
  - calendar: 'Works in the calendar. Deleting an event, calendar.delete_event, waits for a person.' (gated)
- **Engineering Manager**:
  - github: 'Reads pull requests to summarise them.'
  - jira: 'Updates tickets directly.'
  - slack: 'Reads channels. slack.post_message waits for a person.' (gated)
- **Research**:
  - drive: 'Reads and writes documents. Sharing outside waits for a person.' (gated)
- **Customer Support**:
  - gmail: 'Drafts every reply from your handbook. Each send waits for a person.' (gated)
  - slack: 'slack.post_message waits for a person.' (gated)

### 9.2 AuditChainDemo.tsx and auditChain.ts (bento area 'audit')
**Frame.** DemoFrame props:
- area 'audit'; index '03'; name 'Audit chain'; title 'It writes down everything'.
- lead: 'Every decision, tool call and refusal is recorded against the person accountable. Each entry carries the previous entry hash, so an altered entry is detectable.'
- footnote: 'Illustrative short hashes computed in your browser, to show how chaining exposes an edit.'

**Model**
```ts
export function fnv1a32(input: string): string   // 8 lowercase hex chars, zero-padded
export type AuditEntry = { seq: number; time: string; text: string }
export type Recorded = AuditEntry & { prev: string; hash: string }
export const GENESIS = '00000000'
export const ENTRIES: ReadonlyArray<AuditEntry>
export function record(entries: ReadonlyArray<AuditEntry>): Recorded[]   // hash = fnv1a32(prev + '|' + seq + '|' + text)
export function tamper(entries: ReadonlyArray<AuditEntry>): AuditEntry[] // entry index 1: 'approved' -> 'rejected'
export function verify(recorded: ReadonlyArray<Recorded>, current: ReadonlyArray<AuditEntry>): Array<{ recomputed: string; altered: boolean; prevMismatch: boolean }>
```

**Entries**
- 1041, '09:14:02', 'HR agent called gmail.draft_message'
- 1042, '09:14:40', 'A manager approved gmail.send_message'
- 1043, '09:14:41', 'HR agent called gmail.send_message'
- 1044, '09:15:10', 'Research agent declined: no supporting document'

**Rendering**
- The recorded chain is computed once, with useMemo over ENTRIES.
- Current entries are ENTRIES, or tamper(ENTRIES) while altered.
- Everything is computed during render. There are no effects.

**UI**
- `<ol className="lp-audit-list">` of four `li.lp-audit-entry.lp-inset`, each 52px tall. Each holds:
  - mono 'seq 1042' and the time. Never '#1042'.
  - the text. While altered, entry 2 shows `<del>approved</del> <ins>rejected</ins>` and Tag warning 'Altered'.
  - two mono chips: 'prev {8 hex}' and 'hash {8 hex}'. For an altered entry, the hash chip shows the recomputed value, in danger colour.
- Connectors between entries are `.lp-audit-link`: a 2px line drawn with a background, plus the link icon. When the next entry's prevMismatch is true, the line turns var(--danger) and the icon becomes link-broken. Entry 3 then shows Tag danger 'Chain broken here'.
- `Button outline` with the pencil icon: aria-pressed, constant label 'Alter entry 2'.

**Status**
- Untampered: 'Every entry records the hash of the entry before it. The chain verifies.'
- Tampered: `Entry 3 records the previous hash ${a}, but entry 2 now hashes to ${b}. The alteration is detectable.`
- While tampered, also show the caption: 'Chaining makes an edit detectable. It does not by itself stop someone who can rewrite every later entry.'

**Motion.** Changed values use `.lp-anim-tick`. The connector colour transitions over 220ms, with a 300ms transition-delay. Under reduced motion both are instant.

### 9.3 RoleSwitcher.tsx and roleData.ts
**roleData.ts**
```ts
export type RoleId = 'owner' | 'admin' | 'manager' | 'employee' | 'viewer'
export const PERMISSION_GROUPS: ReadonlyArray<{ domain: string; codes: readonly string[] }>
export const ALL_CODES: readonly string[]
export const ROLE_ORDER: readonly RoleId[]   // owner, admin, manager, employee, viewer
export const ROLE_LABEL: Record<RoleId, string>   // 'Owner'..
export const ROLE_ARTICLE: Record<RoleId, 'a' | 'an'>
export const ROLE_CODES: Record<RoleId, ReadonlySet<string>>
export const ROLE_DESCRIPTION: Record<RoleId, string>
export const ROLE_SUMMARY: Record<RoleId, string>
export const ROLE_TONE: Record<RoleId, TagTone>
export const CAPABILITIES: ReadonlyArray<{ label: string; codes: readonly string[] }>
export const CONSOLE_AREAS: ReadonlyArray<{ label: string; code: string | null }>
export function holdsAll(role: RoleId, codes: readonly string[]): boolean
export function compareRoles(a: RoleId, b: RoleId): { added: string[]; removed: string[] }
```

**PERMISSION_GROUPS.** 17 domains in Permission.java order, 46 codes in all.

| Domain | Codes |
|---|---|
| workspace | read, update, delete |
| member | read, invite, update, remove |
| role | read, create, update, delete |
| api_key | read, manage |
| agent | read, create, update, delete, run, grant_tools, set_model_policy, set_approval_policy |
| task | read, create, cancel |
| run | read, cancel, replay |
| chat | use |
| approval | read, decide |
| knowledge | read, query, source_manage |
| integration | read, connect, disconnect |
| provider | read, manage |
| budget | read, manage |
| audit | read |
| analytics | read |
| settings | read, update |
| memory | read, purge |

**ROLE_CODES**
- **owner**: all 46.
- **admin**: all except workspace:delete (45).
- **manager** (25): workspace:read, member:read, role:read, agent:read, agent:create, agent:update, agent:run, agent:set_model_policy, task:read, task:create, task:cancel, run:read, run:cancel, chat:use, approval:read, approval:decide, knowledge:read, knowledge:query, knowledge:source_manage, integration:read, provider:read, budget:read, analytics:read, settings:read, memory:read.
- **employee** (12): workspace:read, member:read, agent:read, agent:run, task:read, task:create, run:read, chat:use, knowledge:read, knowledge:query, integration:read, approval:read.
- **viewer** (8): workspace:read, member:read, agent:read, task:read, run:read, knowledge:read, integration:read, analytics:read.

**ROLE_DESCRIPTION**, verbatim from PermissionSeeder:
- owner: 'Full control of the workspace, including billing and closure.'
- admin: 'Manages people, agents, integrations and settings.'
- manager: 'Runs agents, assigns work and approves actions.'
- employee: 'Asks questions and hands routine work to agents.'
- viewer: 'Reads dashboards and traces without changing anything.'

**ROLE_SUMMARY**
- owner: 'Everything, including billing and closing the workspace.'
- admin: 'Everything except closing the workspace.'
- manager: 'Runs agents, approves actions, cancels runs and tasks, and edits agents. Cannot change who may.'
- employee: 'Asks questions in chat and hands routine work to agents. Can see approvals, cannot decide them.'
- viewer: 'Reads dashboards and traces. Changes nothing.'

**ROLE_TONE** (as in SignIn.tsx): owner blue; admin blue; manager success; employee neutral; viewer neutral.

**CAPABILITIES**
- 'Read runs and traces' [run:read]
- 'Read analytics' [analytics:read]
- 'Ask questions and hand over work' [chat:use, task:create]
- 'See approvals' [approval:read]
- 'Approve or reject agent actions' [approval:decide]
- 'Cancel runs and edit agents' [run:cancel, agent:update]
- 'Change who may do what' [member:invite, role:update]
- 'Close the workspace' [workspace:delete]

**CONSOLE_AREAS**, from the route permissions in App.tsx:
- Command Map (null); Agents (agent:read); Tasks (task:read); Approvals (approval:read); Chat (chat:use); Knowledge (knowledge:read); Integrations (integration:read); Model routing (provider:read); Members (member:read); Audit log (audit:read); Analytics (analytics:read)
- Resulting access: owner 11, admin 11, manager 10, employee 8 and viewer 7 of 11.

**Layout**
- `<LandingSection id="roles" labelledBy="roles-title">`.
- SectionHead: eyebrow 'Who can do what'; title 'Five roles, and you can compose your own'; lead 'Roles are built from individual permissions and edited in the console. A change takes effect the next time somebody signs in, not the next time the platform is deployed.'
- Panel `div.lp-roles.lp-frost`, with padding 22px and a `.lp-reveal`.
- 900px and above: grid minmax(0,4fr) minmax(0,8fr), gap 22px.

**Tablist: `div[role=tablist][aria-label='Roles'].lp-roles-tabs`**
- Five `button[role=tab].lp-roles-tab`, each 64px tall:
  - id 'roles-tab-{role}'; aria-selected; aria-controls 'roles-panel'; roving tabindex.
  - Contents: name at 13.5px/550; the description at 10px muted; mono 'n / 46'; a 4px `.lp-roles-meter`.
  - The meter's fill uses scaleX(n / 46), transform-origin left, transition 420ms var(--lp-ease-out). The fill is var(--blue) when selected and var(--chart-secondary) otherwise.
  - Accessible name: '{Label}, {n} of 46 permission codes'.
- Keyboard: ArrowUp and ArrowLeft select the previous tab; ArrowDown and ArrowRight select the next; Home and End select the first and last. Activation is automatic.
- Below 900px: grid repeat(5, 1fr); names at 11px; count in mono 9px; descriptions display none.

**Panel: `div[role=tabpanel]#roles-panel`**, aria-labelledby the selected tab.
- **Header** `.lp-roles-panel-head`:
  - h3 with the role label at 20px, and its Tag;
  - the count via useCountUp at 30px tabular, with 'of 46 permission codes';
  - a native `<label>` 'Compare with' and `<select className="lp-select">` with options 'None' and the other four roles.
- **Body** `.lp-roles-body`: two columns at container width 640px and above, one column below.
  - **'In plain words'** (h4): `ul.lp-roles-checks`, eight rows. Each row has a check icon (green) or a dash icon (muted), the label, the mono codes, and visually hidden 'Allowed' or 'Not allowed'.
  - **'Console areas'** (h4): mono '{k} of 11 areas', then `ul.lp-roles-areas` in three columns (two below 520px). An unavailable area is shown at 45% opacity with the lock icon and visually hidden 'not available to this role'.
- **'All 46 permission codes'** (h4): `div.lp-roles-map` holds 17 `ul` groups, each with aria-label '{domain}: {x} of {y} held'. Each `li.lp-roles-code` is a mono 9px chip with radius var(--radius-tag):
  - held: var(--blue-light) background, var(--blue) text;
  - not held: transparent, border 1px solid var(--line), var(--muted) at 55% opacity;
  - each chip carries visually hidden ', held' or ', not held'.
- **Compare mode**:
  - Codes added over the compared role get a '+' prefix, background color-mix(in srgb, var(--green) 12%, white) and var(--green) text.
  - Removed codes get a '−' prefix and line-through.
  - A legend explains both.
- Chip colours transition over 200ms, with transition-delay min(i * 6ms, 280ms).
- **Footer**: `a.lp-link` 'Sign in as {article} {role}' → /sign-in, then the caption DEMO_ACCOUNTS_HEDGE.

**Announcements** go to `p.visually-hidden[aria-live=polite]`:
- On selection: '{Label}: {n} of 46 permission codes. {k} of 11 console areas.'
- On compare: '{Label} compared with {other}: {a} added, {r} removed.' A zero is spoken as 'none'.

**Defaults.** Manager, with no comparison.

### 9.4 PlatformBand.tsx
- `<LandingSection id="platform" labelledBy="platform-title">`.
- SectionHead with band: eyebrow 'Under the hood'; title 'What is actually running'.
- `div.lp-platform.lp-frost`, padding 16px, holding:

**Stats.** `ul.lp-platform-stats.lp-hairline-grid` with five columns. Below 1180px: three plus two. Below 600px: two columns, with the fifth cell spanning both. Each cell shows a useCountUp value at 26px/550 tabular, a unit and a note:
- 8 / services / 'Independent Spring Boot services'
- 7 / '+ sandbox' / 'Model providers, plus the offline sandbox model'
- 6 / tool servers / 'MCP servers, each with a sandbox driver'
- 46 / permission codes / 'Composed into roles'
- 5 / built-in roles / 'Plus any roles you compose'

**Chip rows**
- 'Model providers': neutral Tags for PROVIDERS plus 'Sandbox'.
- 'Tool servers': `span.lp-platform-chip` in mono for TOOL_SERVERS.
- The band is not focusable.

### 9.5 LimitsAndCta.tsx
- `<LandingSection id="limits" labelledBy="limits-title">`.
- 900px and above: grid minmax(0,7fr) minmax(0,5fr), gap 16px. Below 900px the panels stack, limits first.

**Left panel: `div.lp-limits.lp-frost.lp-reveal`**, padding 22px.
- `<Eyebrow>What it does not do</Eyebrow>`.
- `h2#limits-title.lp-h2` 'Stated plainly, so nothing here is a surprise later'.
- `ol.lp-limits-list.lp-hairline-grid`: two columns, or one below 600px. Each `li.lp-hairline-cell` has a mono index '01'–'04' in var(--eyebrow), the slash icon, and the sentence verbatim from the current Landing.tsx:
  - 'Agents are not autonomous. They stop at every outbound or destructive action and wait for a person.'
  - 'Answers are only as good as the documents you connect. With nothing indexed, an agent will tell you it cannot answer.'
  - 'The offline model is a placeholder. It returns sensible-looking text so the platform can be tried, and says so wherever it answers.'
  - 'Connecting a live tool account needs an administrator. Until then every server runs against a sandbox and nothing leaves your machine.'

**Right panel: `section.lp-cta.lp-glass[aria-labelledby=cta-title]`**
- position relative; radius var(--radius-card); padding 32px 22px.
- Background linear-gradient(135deg, color-mix(in srgb, var(--blue) 10%, white), var(--lp-glass)).
- An `.lp-aurora-low` sits behind it, positioned right -80px and top -60px, with z-index -1. The section is `.lp-section`, which holds `.lp-shell`, so relative positioning applies.
- Content:
  - `<Eyebrow>Look around</Eyebrow>`;
  - `h2#cta-title.lp-h2` 'Try it on the sandbox model';
  - `p.lp-copy`: 'Runs on an offline sandbox model out of the box, so no API key is needed. Live providers are one stored key and a routing policy away.';
  - `a.button.button-primary.lp-button-lg.lp-sheen` 'Try a demo account' → /sign-in, and `a.button.button-outline.lp-button-lg` 'Create a workspace' → /create-workspace. Below 600px both are full width;
  - caption DEMO_ACCOUNTS_HEDGE.

### 9.6 LandingFooter.tsx
- `<footer className="lp-footer"><div className="lp-shell lp-footer-row">`: border-top 1px solid var(--line); height 64px; space-between. Below 600px it wraps to two rows with an 8px gap.
- Caption: 'AI Workforce OS, an enterprise multi-agent platform.'
- Nav pills: 'Sign in' → /sign-in; 'Create a workspace' → /create-workspace.

## 10. Integrator
1. **web/src/routes/Landing.tsx**: exactly the composition in section 3. It must contain a literal `<Eyebrow>`. Remove the old arrays; their content now lives in landingFacts.ts and the owners' files.
2. **web/src/App.tsx**: fix the title. At '/' when signed out, the title is currently 'Command Map · AI Workforce OS', because privateRoute matches '/'.
   - Change to `const titlePattern = !signedIn && path === '/' ? '/home' : publicRoute?.pattern ?? privateRoute?.pattern ?? ''` and use it in the existing effect.
   - Do not set the title from inside Landing: child effects run before parent effects, and App's effect runs on every App render, so it would overwrite the value.
   - The result is 'A governed AI workforce · AI Workforce OS' on both '/' and '/home'.
3. **web/src/styles/design-system.test.ts**: add a `describe('landing styles')` block that reads every .css file in `src/styles/landing/` and checks:
   - (a) index.css contains `@import './{file}'` for every other file;
   - (b) the same border rule as the components test, over all landing files, with at least 6 matches;
   - (c) every `border-radius:` value matches `/^((0|var\(--radius-[a-z-]+\))(\s+(0|var\(--radius-[a-z-]+\))){0,3}|inherit)$/`;
   - (d) no `/#[0-9a-fA-F]{3,8}\b/` anywhere;
   - (e) every `@keyframes` name starts with `lp-`.
   Keep every existing test unchanged.
4. **web/src/styles/components.css**: delete the now-unused `.landing-bar` and `.landing-grid` rules and the `.landing-bar` rule inside the 900px media query. Change nothing else, so the test slices stay intact.
5. **web/src/components/landing/Landing.test.tsx**: see section 11.
6. **Final build**: `pnpm build`, `pnpm lint`, `pnpm test`.
   - Check manually at 375, 768, 1180 and 1440 wide, with no horizontal scroll.
   - Check with emulated reduced motion.
   - Check a keyboard-only pass through every demo.
   - Confirm that no network request is made from the landing page.

## 11. Tests (each owner writes their own)
- **foundation, `shared/shared.test.tsx`**:
  - useReducedMotion returns true without matchMedia;
  - useSequence runs steps synchronously when reduced;
  - DemoFrame renders role=status with empty text, and the 'Simulated' tag;
  - SegmentedControl changes value on click.
- **hero, `heroConsoleModel.test.ts`**:
  - stats at elapsed 0, 3000, 4500, 6000 and 60000;
  - running is never negative;
  - arrival persists after 60s.
- **agent-run**:
  - `approvalModel.test.ts`: the approve, reject, expire, employee-guard and viewer-guard paths.
  - `ApprovalDemo.test.tsx`: reduced mode renders parked; clicking Approve reaches the completed status.
- **failover-and-answer**:
  - `failoverModel.test.ts`: each preset's outcome; a refusal stops the chain; both exhausted policies; the breaker is remembered after a 401.
  - `citedAnswerModel.test.ts`: word count; the passages match their pages.
- **roles-and-sections**:
  - `roleData.test.ts`: role sizes 46, 45, 25, 12 and 8; ALL_CODES has 46 unique codes; employee is a subset of manager; compareRoles(manager, employee) gives 13 added and 0 removed; console areas give 11, 11, 10, 8 and 7.
  - `auditChain.test.ts`: the chain verifies untampered; tampering flags entry 3 only.
- **integrator, `Landing.test.tsx`**, rendered inside RouterProvider:
  - globalThis.fetch is spied and never called;
  - axe-core has no violations, with color-contrast disabled because jsdom cannot compute it;
  - reduced end states render: 'Exactly what will be sent', an 'Answered' tag, 'Project_Proposal.pdf', and the manager tab selected;
  - exactly one h1.

## 12. Accessibility and performance checklist
**Accessibility**
- Skip link first; `main#main`.
- One polite status per demo.
- aria-pressed toggles keep constant labels.
- WAI-ARIA tabs and native radio groups.
- Focus moves only after a user action.
- Colour is never the only signal: text, icons, and '+' and '−' prefixes accompany it.
- Muted text sits only on at least 0.86 white. Aurora alphas are no more than 20% blue, 42% chart-secondary and 95% blue-light.
- Mono labels of 10px or less use var(--ink) or var(--eyebrow), never var(--muted), on glass.

**Performance**
- At most five backdrop-filter surfaces per viewport: the bar, three hero layers and the fact strip.
- Insets and tiles are never blurred.
- No opacity below 1, filter, mask or will-change on any ancestor of glass. The one exception is transient reveal opacity.
- The aurora animates transform only.
- Intervals and rAF loops run only while visible and unpaused.
- Expect about 25KB gzip of JS and about 1,100 lines of CSS.

## 13. Risks, inconsistencies and open questions
- **Motion rule.** tokens.css says 'Nothing moves, nothing bounces'. The landing page is a documented exception, scoped to `.lp` and without overshoot. The console now feels stiller by comparison, and the team should accept that knowingly.
- **Page length.** It grows from about 2,300px to about 3,450px. Density per viewport rises sharply, but the page does not get shorter. If brevity was the real goal, the platform band can fold into the hero fact strip, saving about 214px.
- **Public quotation.** Two excerpts from Project_Proposal.pdf are quoted on a public page. Confirm this is acceptable.
- **SignIn.tsx contradiction.** SignIn.tsx says an employee cannot see Members, but the seeder grants employee member:read. The role explorer shows the seeder's truth. Fix SignIn.tsx separately; it is out of scope here.
- **Manager and the audit log.** Managers lack audit:read, so the explorer shows the Audit log locked for a manager. This is accurate; confirm the team is comfortable showing it.
- **Billing.** No permission code covers billing. The only billing mention is the seeder's own owner description.
- **Naming convention.** The organisation's YYYY-MM-DD_Subject_Version naming conflicts with module naming. Source files follow repository conventions, because imports and tooling depend on them. A saved copy of this spec would be named 2026-09-26_HomePageRedesign_v1.md.
- **Clarifying questions for the user:**
  1. Should the approval demo also offer the slack.post_message scenario? That needs the real payload shape.
  2. Is opening the page with simulated console activity acceptable, given that it is labelled on the figure, in the notice and per demo?
  3. Should the console itself adopt any of this motion later, or stay still?

## 14. Tone Check (Warm but Authoritative)

| Dimension | Score | Reason |
|---|---|---|
| Authority | 9/10 | Every claim is specific and verifiable: codes, counts, exact payloads. Limits are stated beside the call to action. |
| Warmth | 7/10 | Second person ('Decide it yourself', 'Try it here', 'Look around') and courteous status copy ('Nothing left your browser') soften a plain voice. The limit copy stays austere by design. |
| Overall | 8/10 | Adding one human clause to the error states, for example 'so you can decide calmly', would lift warmth without costing precision. |