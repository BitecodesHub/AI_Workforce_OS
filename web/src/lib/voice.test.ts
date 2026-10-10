// @find: tests for voice, useVoiceInput, useSpeaker, pickRecorderType, recognitionErrorText
// @what: Unit tests for voice input and output.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { hash, pickBrowserVoice, pickRecorderType, recognitionErrorText, useSpeaker } from './voice'

/*
 * Only the pure helpers are tested here. useVoiceInput and useSpeaker depend on MediaRecorder,
 * SpeechRecognition, speechSynthesis and the network, none of which jsdom provides in this
 * project's test setup (no user-event, no signed-in screen harness - see queries.ts's own
 * comment); their no-op-without-the-API behaviour is exercised in practice by the modules that
 * mount them, per the routing/mentions style of keeping logic pure and testable in lib/.
 *
 * The forced-speak path (D1, B1.12) is the one behaviour worth exercising through the real hook:
 * a manual "Read aloud" press must reach speechSynthesis even while replies are muted. A minimal
 * speechSynthesis double is installed for that one describe block only.
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

describe('pickRecorderType', () => {
  const original = (globalThis as { MediaRecorder?: unknown }).MediaRecorder

  afterEach(() => {
    if (original) (globalThis as { MediaRecorder?: unknown }).MediaRecorder = original
    else delete (globalThis as { MediaRecorder?: unknown }).MediaRecorder
  })

  it('returns null when MediaRecorder does not exist at all', () => {
    delete (globalThis as { MediaRecorder?: unknown }).MediaRecorder
    expect(pickRecorderType(['audio/webm', 'audio/mp4'])).toBeNull()
  })

  it('picks the first candidate the browser reports as supported', () => {
    Object.defineProperty(globalThis, 'MediaRecorder', {
      configurable: true,
      value: { isTypeSupported: (type: string) => type === 'audio/mp4' },
    })
    expect(pickRecorderType(['audio/webm', 'audio/mp4', 'audio/ogg'])).toBe('audio/mp4')
  })

  it('returns null when nothing on the list is supported (D16)', () => {
    Object.defineProperty(globalThis, 'MediaRecorder', {
      configurable: true,
      value: { isTypeSupported: () => false },
    })
    expect(pickRecorderType(['audio/webm', 'audio/mp4', 'audio/ogg'])).toBeNull()
  })
})

class FakeUtterance {
  text: string
  voice: SpeechSynthesisVoice | null = null
  pitch = 1
  onend: (() => void) | null = null
  onerror: (() => void) | null = null
  constructor(text: string) {
    this.text = text
  }
}

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return createElement(QueryClientProvider, { client }, children)
}

describe('useSpeaker forced speak (D1)', () => {
  let speakSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.stubGlobal('fetch', () => Promise.reject(new Error('No network in tests')))
    speakSpy = vi.fn()
    Object.defineProperty(window, 'speechSynthesis', {
      configurable: true,
      value: { getVoices: () => [], speak: speakSpy, cancel: vi.fn() },
    })
    Object.defineProperty(window, 'SpeechSynthesisUtterance', { configurable: true, value: FakeUtterance })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    delete (window as { speechSynthesis?: unknown }).speechSynthesis
    delete (window as { SpeechSynthesisUtterance?: unknown }).SpeechSynthesisUtterance
  })

  it('speaks even while muted, when the caller forces it', async () => {
    const { result } = renderHook(() => useSpeaker(), { wrapper })

    act(() => result.current.setMuted(true))
    await waitFor(() => expect(result.current.muted).toBe(true))

    await act(async () => {
      await result.current.speak('Hello there', 'agent-1', { force: true, key: 'm1' })
    })

    expect(speakSpy).toHaveBeenCalledTimes(1)
    expect(result.current.speakingKey).toBe('m1')
  })

  it('stays silent while muted without force', async () => {
    const { result } = renderHook(() => useSpeaker(), { wrapper })

    act(() => result.current.setMuted(true))
    await waitFor(() => expect(result.current.muted).toBe(true))

    await act(async () => {
      await result.current.speak('Hello there', 'agent-1')
    })

    expect(speakSpy).not.toHaveBeenCalled()
  })
})

describe('recognitionErrorText', () => {
  it('says the microphone is not allowed, rather than that speech was not understood', () => {
    expect(recognitionErrorText('not-allowed')).toMatch(/not allowed to use the microphone/)
    expect(recognitionErrorText('service-not-allowed')).toMatch(/not allowed to use the microphone/)
  })

  it('names a missing microphone and silence, and says nothing when the person stopped it', () => {
    expect(recognitionErrorText('audio-capture')).toMatch(/No microphone/)
    expect(recognitionErrorText('no-speech')).toMatch(/Nothing was heard/)
    expect(recognitionErrorText('aborted')).toBeNull()
    expect(recognitionErrorText(undefined)).toBe('Speech recognition could not understand that. Try again.')
  })
})

