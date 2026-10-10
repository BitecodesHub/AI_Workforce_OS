// @find: tests for page title, tab title, setPageTitle, setAttentionCount
// @what: Unit tests for the browser tab title.
import { afterEach, describe, expect, it } from 'vitest'
import { setAttentionCount, setPageTitle } from './router'

/*
 * The tab title is written from two parts that change on their own schedules: what the screen is
 * called, and how many things are waiting. Whichever changes, the other survives.
 */

afterEach(() => {
  setAttentionCount(0)
  setPageTitle('AI Workforce OS')
})

describe('the tab title', () => {
  it('has no count when nothing is waiting', () => {
    setPageTitle('Agents · AI Workforce OS')
    expect(document.title).toBe('Agents · AI Workforce OS')
  })

  it('puts the count in front, and keeps it when the screen changes its own part', () => {
    setPageTitle('Agents · AI Workforce OS')
    setAttentionCount(2)
    expect(document.title).toBe('(2) Agents · AI Workforce OS')

    setPageTitle('Maya · AI Workforce OS')
    expect(document.title).toBe('(2) Maya · AI Workforce OS')
  })

  it('keeps the screen’s part when the count changes, and takes the count off at zero', () => {
    setPageTitle('Approvals · AI Workforce OS')
    setAttentionCount(3)
    setAttentionCount(1)
    expect(document.title).toBe('(1) Approvals · AI Workforce OS')

    setAttentionCount(0)
    expect(document.title).toBe('Approvals · AI Workforce OS')
  })

  it('never shows a count twice when a screen put its own in front', () => {
    setAttentionCount(2)
    setPageTitle('(5) Orchestrator · AI Workforce OS')
    expect(document.title).toBe('(2) Orchestrator · AI Workforce OS')

    setAttentionCount(0)
    expect(document.title).toBe('Orchestrator · AI Workforce OS')
  })

  it('treats a negative or fractional count as what it nearly is', () => {
    setPageTitle('Runs · AI Workforce OS')
    setAttentionCount(-4)
    expect(document.title).toBe('Runs · AI Workforce OS')
    setAttentionCount(2.9)
    expect(document.title).toBe('(2) Runs · AI Workforce OS')
  })
})
