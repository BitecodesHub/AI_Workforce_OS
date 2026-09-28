import { describe, expect, it } from 'vitest'
import { hash, pickBrowserVoice } from './voice'

/*
 * Only the pure helpers are tested here. useVoiceInput and useSpeaker depend on MediaRecorder,
 * SpeechRecognition, speechSynthesis and the network, none of which jsdom provides in this
 * project's test setup (no user-event, no signed-in screen harness - see queries.ts's own
 * comment); their no-op-without-the-API behaviour is exercised in practice by the modules that
 * mount them, per the routing/mentions style of keeping logic pure and testable in lib/.
 */

function voice(name: string, lang: string): SpeechSynthesisVoice {
  return { name, lang, default: false, localService: true, voiceURI: name } as unknown as SpeechSynthesisVoice
}

describe('hash', () => {
  it('is stable for the same input', () => {
    expect(hash('agent-hr')).toBe(hash('agent-hr'))
  })

  it('differs for different inputs, usually', () => {
    expect(hash('agent-hr')).not.toBe(hash('agent-support'))
  })

  it('is never negative', () => {
    expect(hash('')).toBeGreaterThanOrEqual(0)
    expect(hash('anything at all')).toBeGreaterThanOrEqual(0)
  })
})

describe('pickBrowserVoice', () => {
  const voices = [voice('Karen', 'en-AU'), voice('Daniel', 'en-GB'), voice('Amelie', 'fr-FR')]

  it('returns null with no voices at all', () => {
    expect(pickBrowserVoice([], 'agent-hr', 'en-AU')).toBeNull()
  })

  it('prefers a voice matching the language prefix', () => {
    const picked = pickBrowserVoice(voices, 'agent-hr', 'en-AU')
    expect(picked && ['en-AU', 'en-GB']).toContain(picked?.lang)
  })

  it('falls back to any voice when none match the language', () => {
    const picked = pickBrowserVoice(voices, 'agent-hr', 'de-DE')
    expect(picked).not.toBeNull()
  })

  it('picks the same voice for the same agent every time', () => {
    const first = pickBrowserVoice(voices, 'agent-research', 'en-AU')
    const second = pickBrowserVoice(voices, 'agent-research', 'en-AU')
    expect(first).toBe(second)
  })

  it('can pick different voices for different agents', () => {
    const names = new Set(
      ['agent-a', 'agent-b', 'agent-c', 'agent-d', 'agent-e'].map((id) => pickBrowserVoice(voices, id, 'en-AU')?.name),
    )
    expect(names.size).toBeGreaterThan(1)
  })
})
