import { useId, useRef, useState } from 'react'
import type { ChangeEvent, CSSProperties, KeyboardEvent, ReactElement } from 'react'
import { Tag } from '../../ui'
import { useCountUp } from '../../../hooks/useCountUp'
import { useInView } from '../../../hooks/useInView'
import { Icon } from '../shared/Icon'
import { LandingSection, Reveal, SectionHead } from '../shared/LandingSection'
import { CTA, DEMO_ACCOUNTS_HEDGE } from '../shared/landingFacts'
import {
  ALL_CODES,
  CAPABILITIES,
  CONSOLE_AREAS,
  PERMISSION_GROUPS,
  ROLE_ARTICLE,
  ROLE_CODES,
  ROLE_DESCRIPTION,
  ROLE_LABEL,
  ROLE_ORDER,
  ROLE_SUMMARY,
  ROLE_TONE,
  canOpenArea,
  compareRoles,
  countAreas,
  holdsAll,
} from './roleData'
import type { RoleId } from './roleData'

/*
 * The role explorer: five built-in roles, what each may do in plain words, which console screens
 * it opens, and every permission code it holds, with an optional comparison against another role.
 *
 * Everything shown is derived from roleData, which mirrors the permission registry, the role
 * seeder and the console's route table. Nothing here is typed in twice.
 *
 * WAI-ARIA tabs with automatic activation: arrow keys, Home and End move between roles and select
 * as they go. Announcements go to one polite, visually hidden line and are set only in the event
 * handlers that change the selection or the comparison.
 */

type CompareTarget = RoleId | 'none'

const TOTAL_CODES = ALL_CODES.length
const TOTAL_AREAS = CONSOLE_AREAS.length
const CODE_INDEX: ReadonlyMap<string, number> = new Map(ALL_CODES.map((code, index) => [code, index]))

function isRoleId(value: string): value is RoleId {
  return (ROLE_ORDER as readonly string[]).includes(value)
}

function spoken(count: number): string {
  return count === 0 ? 'none' : String(count)
}

function comparisonSentence(role: RoleId, other: RoleId): string {
  const { added, removed } = compareRoles(role, other)
  return `${ROLE_LABEL[role]} compared with ${ROLE_LABEL[other]}: ${spoken(added.length)} added, ${spoken(removed.length)} removed.`
}

function selectionSentence(role: RoleId, other: CompareTarget): string {
  const base =
    `${ROLE_LABEL[role]}: ${ROLE_CODES[role].size} of ${TOTAL_CODES} permission codes. ` +
    `${countAreas(role)} of ${TOTAL_AREAS} console areas.`
  return other === 'none' ? base : `${base} ${comparisonSentence(role, other)}`
}

function chipStyle(code: string): CSSProperties {
  return { '--ci': String(CODE_INDEX.get(code) ?? 0) } as CSSProperties
}

/** The meter's fill, read by roles-sections.css as scaleX(var(--lp-roles-fill)). */
function meterStyle(held: number): CSSProperties {
  return { '--lp-roles-fill': String(held / TOTAL_CODES) } as CSSProperties
}

export function RoleSwitcher(): ReactElement {
  const [selected, setSelected] = useState<RoleId>('manager')
  const [compareWith, setCompareWith] = useState<CompareTarget>('none')
  const [announcement, setAnnouncement] = useState('')
  const tabs = useRef<Partial<Record<RoleId, HTMLButtonElement | null>>>({})
  const selectId = useId()
  const { ref: panelRef, inView } = useInView<HTMLDivElement>({ threshold: 0.2 })

  const held = ROLE_CODES[selected]
  const heldCount = held.size
  const shownCount = useCountUp(heldCount, inView)
  const areaCount = countAreas(selected)
  const comparison = compareWith === 'none' ? null : compareRoles(selected, compareWith)
  const added = new Set(comparison?.added ?? [])
  const removed = new Set(comparison?.removed ?? [])
  const label = ROLE_LABEL[selected]
  const otherLabel = compareWith === 'none' ? '' : ROLE_LABEL[compareWith]

  function selectRole(role: RoleId, moveFocus: boolean) {
    if (moveFocus) tabs.current[role]?.focus({ preventScroll: true })
    if (role === selected) return
    // Comparing a role with itself says nothing, so choosing the compared role clears it.
    const nextCompare: CompareTarget = compareWith === role ? 'none' : compareWith
    setSelected(role)
    setCompareWith(nextCompare)
    setAnnouncement(selectionSentence(role, nextCompare))
  }

  function onTabKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const last = ROLE_ORDER.length - 1
    let next: number
    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        next = index === last ? 0 : index + 1
        break
      case 'ArrowLeft':
      case 'ArrowUp':
        next = index === 0 ? last : index - 1
        break
      case 'Home':
        next = 0
        break
      case 'End':
        next = last
        break
      default:
        return
    }
    event.preventDefault()
    const role = ROLE_ORDER[next]
    if (role) selectRole(role, true)
  }

  function onCompareChange(event: ChangeEvent<HTMLSelectElement>) {
    const value = event.target.value
    const next: CompareTarget = isRoleId(value) && value !== selected ? value : 'none'
    setCompareWith(next)
    setAnnouncement(
      next === 'none'
        ? `Comparison cleared. ${label}: ${heldCount} of ${TOTAL_CODES} permission codes.`
        : comparisonSentence(selected, next),
    )
  }

  function codeNote(code: string, isHeld: boolean): string {
    if (added.has(code)) return `, held, not held by ${otherLabel}`
    if (removed.has(code)) return `, not held, held by ${otherLabel}`
    return isHeld ? ', held' : ', not held'
  }

  return (
    <LandingSection id="roles" labelledBy="roles-title">
      <SectionHead
        eyebrow="Who can do what"
        title="Five roles, and you can compose your own"
        titleId="roles-title"
        lead="Roles are built from individual permissions and edited in the console. A change takes effect the next time somebody's session refreshes, not the next time the platform is deployed."
      />

      <Reveal className="lp-roles lp-frost">
        <div role="tablist" aria-label="Roles" className="lp-roles-tabs">
          {ROLE_ORDER.map((role, index) => {
            const isSelected = role === selected
            const count = ROLE_CODES[role].size
            return (
              <button
                key={role}
                ref={(node) => {
                  tabs.current[role] = node
                }}
                type="button"
                role="tab"
                id={`roles-tab-${role}`}
                className="lp-roles-tab"
                aria-selected={isSelected}
                aria-controls="roles-panel"
                aria-label={`${ROLE_LABEL[role]}, ${count} of ${TOTAL_CODES} permission codes`}
                tabIndex={isSelected ? 0 : -1}
                onClick={() => selectRole(role, false)}
                onKeyDown={(event) => onTabKeyDown(event, index)}
              >
                <span className="lp-roles-tab-name">{ROLE_LABEL[role]}</span>
                <span className="lp-roles-tab-count">
                  {count} / {TOTAL_CODES}
                </span>
                <span className="lp-roles-tab-desc">{ROLE_DESCRIPTION[role]}</span>
                <span className="lp-roles-meter" aria-hidden="true">
                  <span className="lp-roles-meter-fill" style={meterStyle(count)} />
                </span>
              </button>
            )
          })}
        </div>

        <div
          ref={panelRef}
          role="tabpanel"
          id="roles-panel"
          className="lp-roles-panel"
          aria-labelledby={`roles-tab-${selected}`}
        >
          <div className="lp-roles-panel-head">
            <div className="lp-roles-identity">
              <div className="lp-roles-name-row">
                <h3 className="lp-roles-name">{label}</h3>
                <Tag tone={ROLE_TONE[selected]}>Built-in role</Tag>
              </div>
              <p className="lp-roles-summary">{ROLE_SUMMARY[selected]}</p>
            </div>
            <p className="lp-roles-count">
              <span className="lp-roles-count-value tabular">
                <span aria-hidden="true">{shownCount}</span>
                <span className="visually-hidden">{heldCount}</span>
              </span>
              <span className="lp-roles-count-label">of {TOTAL_CODES} permission codes</span>
            </p>
            <div className="lp-roles-compare">
              <label className="lp-roles-compare-label" htmlFor={selectId}>
                Compare with
              </label>
              <select id={selectId} className="lp-select" value={compareWith} onChange={onCompareChange}>
                <option value="none">None</option>
                {ROLE_ORDER.filter((role) => role !== selected).map((role) => (
                  <option key={role} value={role}>
                    {ROLE_LABEL[role]}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="lp-roles-body">
            <div className="lp-roles-block">
              <h4 className="lp-roles-h4">In plain words</h4>
              <ul className="lp-roles-checks">
                {CAPABILITIES.map((capability) => {
                  const allowed = holdsAll(selected, capability.codes)
                  return (
                    <li key={capability.label} className="lp-roles-check" data-allowed={allowed}>
                      <Icon name={allowed ? 'check' : 'dash'} className="lp-roles-check-icon" />
                      <span className="lp-roles-check-text">
                        <span className="visually-hidden">{allowed ? 'Allowed: ' : 'Not allowed: '}</span>
                        <span className="lp-roles-check-label">{capability.label}</span>
                        <span className="lp-roles-check-codes">{capability.codes.join(' · ')}</span>
                      </span>
                    </li>
                  )
                })}
              </ul>
            </div>

            <div className="lp-roles-block">
              <div className="lp-roles-block-head">
                <h4 className="lp-roles-h4">Console areas</h4>
                <p className="lp-roles-tally">
                  {areaCount} of {TOTAL_AREAS} areas
                </p>
              </div>
              <ul className="lp-roles-areas">
                {CONSOLE_AREAS.map((area) => {
                  const open = canOpenArea(selected, area)
                  return (
                    <li key={area.label} className="lp-roles-area" data-open={open}>
                      {!open && <Icon name="lock" className="lp-roles-area-icon" />}
                      <span className="lp-roles-area-label">{area.label}</span>
                      {!open && <span className="visually-hidden">, not available to this role</span>}
                    </li>
                  )
                })}
              </ul>
            </div>
          </div>

          <div className="lp-roles-block">
            <div className="lp-roles-block-head">
              <h4 className="lp-roles-h4">All {TOTAL_CODES} permission codes</h4>
              <ul className="lp-roles-legend" aria-label="Key to the permission codes">
                <li>
                  <span className="lp-roles-swatch" data-held="true" aria-hidden="true" />
                  Held by {label}
                </li>
                <li>
                  <span className="lp-roles-swatch" data-held="false" aria-hidden="true" />
                  Not held
                </li>
                {comparison && (
                  <>
                    <li>
                      <span className="lp-roles-swatch" data-diff="added" aria-hidden="true">
                        +
                      </span>
                      Held by {label}, not by {otherLabel}
                    </li>
                    <li>
                      <span className="lp-roles-swatch" data-diff="removed" aria-hidden="true">
                        −
                      </span>
                      Held by {otherLabel}, not by {label}
                    </li>
                  </>
                )}
              </ul>
            </div>
            <div className="lp-roles-map">
              {PERMISSION_GROUPS.map((group) => {
                const heldInGroup = group.codes.filter((code) => held.has(code)).length
                return (
                  <ul
                    key={group.domain}
                    className="lp-roles-group"
                    aria-label={`${group.domain}: ${heldInGroup} of ${group.codes.length} held`}
                  >
                    {group.codes.map((code) => {
                      const isHeld = held.has(code)
                      const diff = added.has(code) ? 'added' : removed.has(code) ? 'removed' : null
                      return (
                        <li
                          key={code}
                          className="lp-roles-code"
                          data-held={isHeld}
                          {...(diff ? { 'data-diff': diff } : {})}
                          style={chipStyle(code)}
                        >
                          {diff === 'added' && (
                            <span className="lp-roles-code-sign" aria-hidden="true">
                              +
                            </span>
                          )}
                          {diff === 'removed' && (
                            <span className="lp-roles-code-sign" aria-hidden="true">
                              −
                            </span>
                          )}
                          <span className="lp-roles-code-text">{code}</span>
                          <span className="visually-hidden">{codeNote(code, isHeld)}</span>
                        </li>
                      )
                    })}
                  </ul>
                )
              })}
            </div>
          </div>

          <div className="lp-roles-foot">
            <a className="lp-link" href={CTA.signIn.href}>
              Sign in as {ROLE_ARTICLE[selected]} {label.toLowerCase()}
              <Icon name="arrow-right" />
            </a>
            <p className="caption">{DEMO_ACCOUNTS_HEDGE}</p>
          </div>
        </div>

        <p className="visually-hidden" aria-live="polite" aria-atomic="true">
          {announcement}
        </p>
      </Reveal>
    </LandingSection>
  )
}
