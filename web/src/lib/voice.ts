import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, api, describeApiError, normaliseFields, refreshAccessToken } from './api'
import { accessToken, clearSession } from './session'
import { useVoiceStatus } from './queries'

/*
 * Voice in the console: listening (speech to text) and speaking (text to speech), each with an
 * honest fallback when no ElevenLabs key is stored.
 *
 * Everything here is written to no-op safely without the browser APIs it depends on (no
 * MediaRecorder, no SpeechRecognition, no speechSynthesis, no localStorage): under a test runner,
 * or a browser that simply lacks one, `supported`/`provider` says so and nothing throws.
 */

/* ---- Reaching the platform for audio ------------------------------------------------------------ */

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

async function voiceError(response: Response): Promise<ApiError> {
  const text = await response.text().catch(() => '')
  const body = text ? parseJson(text) : null
  const record = body && typeof body === 'object' ? (body as Record<string, unknown>) : {}
  const code = typeof record.code === 'string' && record.code ? record.code : 'unknown_error'
  const detail = typeof record.detail === 'string' && record.detail ? record.detail : 'The voice request failed.'
  return new ApiError(response.status, code, detail, response.status >= 500, normaliseFields(record.errors))
}

/**
 * A binary response from the platform (audio bytes), with the same bearer token and one-time
 * refresh-on-401 behaviour as api() - sharing api.ts's own refreshAccessToken(), so the two never
 * both refresh at once and a network failure during the refresh reads as a network failure rather
 * than an expired session - because an <audio> element cannot attach a header itself: the bytes
 * have to be fetched here first and turned into an object URL. Defaults to a POST with a JSON body
 * (speech synthesis); pass `{ method: 'GET' }` for a stored clip, which takes none.
 */
export async function fetchAudio(
  path: string,
  options: { method?: 'GET' | 'POST'; body?: unknown } = {},
): Promise<Blob> {
  const { method = 'POST', body } = options
  const send = (token: string | null) =>
    fetch(path, {
      method,
      credentials: 'include',
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : null,
    })

  let response = await send(accessToken())
  if (response.status === 401 && accessToken()) {
    if (await refreshAccessToken()) {
      response = await send(accessToken())
    } else {
      clearSession()
      throw new ApiError(401, 'token_expired', 'Your session has ended. Sign in again.', false, {})
    }
  }
  if (!response.ok) throw await voiceError(response)
  return response.blob()
}

/* ---- Listening: microphone or the browser's own speech recognition ------------------------------- */

export type VoiceInputProvider = 'elevenlabs' | 'browser' | 'none'

type RecognitionResultLike = { transcript: string }
type RecognitionEventLike = { results: ArrayLike<ArrayLike<RecognitionResultLike>> }
type RecognitionLike = {
  lang: string
  continuous: boolean
  interimResults: boolean
  onresult: ((event: RecognitionEventLike) => void) | null
  onerror: (() => void) | null
  onend: (() => void) | null
  start: () => void
  stop: () => void
}
type RecognitionCtor = new () => RecognitionLike

function recognitionCtor(): RecognitionCtor | null {
  if (typeof window === 'undefined') return null
  const w = window as unknown as { SpeechRecognition?: RecognitionCtor; webkitSpeechRecognition?: RecognitionCtor }
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null
}

function canRecord(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.MediaRecorder !== 'undefined' &&
    Boolean(navigator?.mediaDevices?.getUserMedia)
  )
}

/**
 * Turns speech into text in the composer: ElevenLabs' own transcription when a key is stored and
 * the microphone is available, otherwise the browser's built-in recognition, otherwise neither.
 * `onText` fires once per finished utterance; it is never called with empty text.
 */
export function useVoiceInput({ onText }: { onText: (text: string) => void }) {
  const status = useVoiceStatus()
  const elevenlabsReady = status.data?.provider === 'elevenlabs' && status.data.keyStored && canRecord()
  const provider: VoiceInputProvider = elevenlabsReady ? 'elevenlabs' : recognitionCtor() ? 'browser' : 'none'
  const supported = provider !== 'none'

  const [listening, setListening] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const recognitionRef = useRef<RecognitionLike | null>(null)
  const recorderRef = useRef<MediaRecorder | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const chunksRef = useRef<BlobPart[]>([])
  const onTextRef = useRef(onText)
  useEffect(() => {
    onTextRef.current = onText
  }, [onText])

  const transcribe = useCallback(async (blob: Blob) => {
    try {
      const form = new FormData()
      form.append('file', blob, 'voice-input.webm')
      const result = await api<{ text: string }>('/api/voice/transcriptions', { method: 'POST', form })
      if (result.text.trim()) onTextRef.current(result.text.trim())
    } catch (err) {
      setError(describeApiError(err))
    }
  }, [])

  const start = useCallback(async () => {
    setError(null)
    if (provider === 'elevenlabs') {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
        streamRef.current = stream
        chunksRef.current = []
        const recorder = new MediaRecorder(stream, { mimeType: 'audio/webm' })
        recorder.ondataavailable = (event) => {
          if (event.data.size > 0) chunksRef.current.push(event.data)
        }
        recorder.onstop = () => {
          stream.getTracks().forEach((track) => track.stop())
          const blob = new Blob(chunksRef.current, { type: 'audio/webm' })
          setListening(false)
          void transcribe(blob)
        }
        recorder.start()
        recorderRef.current = recorder
        setListening(true)
      } catch {
        setError('The microphone could not be used. Check that this page has permission to use it.')
      }
      return
    }
    if (provider === 'browser') {
      const Ctor = recognitionCtor()
      if (!Ctor) return
      const recognition = new Ctor()
      recognition.lang = typeof navigator !== 'undefined' ? navigator.language : 'en-US'
      recognition.continuous = false
      recognition.interimResults = false
      recognition.onresult = (event) => {
        const last = event.results[event.results.length - 1]
        const transcript = last?.[0]?.transcript
        if (typeof transcript === 'string' && transcript.trim()) onTextRef.current(transcript.trim())
      }
      recognition.onerror = () => setError('Speech recognition could not understand that. Try again.')
      recognition.onend = () => setListening(false)
      recognitionRef.current = recognition
      try {
        recognition.start()
        setListening(true)
      } catch {
        setError('Speech recognition could not start.')
      }
      return
    }
    setError('Voice input is not available in this browser.')
  }, [provider, transcribe])

  const stop = useCallback(() => {
    recorderRef.current?.stop()
    recorderRef.current = null
    recognitionRef.current?.stop()
    recognitionRef.current = null
    setListening(false)
  }, [])

  useEffect(
    () => () => {
      recorderRef.current?.stop()
      recognitionRef.current?.stop()
      streamRef.current?.getTracks().forEach((track) => track.stop())
    },
    [],
  )

  return { supported, provider, listening, start, stop, error }
}

/* ---- Speaking: ElevenLabs audio or the browser's own voices --------------------------------------- */

export type SpeakerProvider = 'elevenlabs' | 'browser' | 'none'

const MUTE_KEY = 'aiwos.voice.muted'
const MAX_SPEECH_CHARACTERS = 2500

function readMuted(): boolean {
  try {
    return localStorage.getItem(MUTE_KEY) === '1'
  } catch {
    return false
  }
}

function writeMuted(muted: boolean) {
  try {
    if (muted) localStorage.setItem(MUTE_KEY, '1')
    else localStorage.removeItem(MUTE_KEY)
  } catch {
    // Private browsing and blocked site data both throw here; the mute choice just does not
    // survive a reload, which is no worse than not persisting it at all.
  }
}

/** A small, stable hash of a string (FNV-1a), for picking a consistent voice and pitch per agent. */
export function hash(value: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < value.length; i += 1) {
    h ^= value.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

/**
 * A stable browser voice for an agent: filtered to voices matching the viewer's own language when
 * any do, otherwise any voice at all, then picked by a hash of the agent id so the same agent
 * always sounds the same and different agents usually do not.
 */
export function pickBrowserVoice(
  voices: readonly SpeechSynthesisVoice[],
  agentId: string,
  lang: string,
): SpeechSynthesisVoice | null {
  if (voices.length === 0) return null
  const prefix = lang.split('-')[0]?.toLowerCase() ?? ''
  const matching = prefix ? voices.filter((voice) => voice.lang.toLowerCase().startsWith(prefix)) : []
  const pool = matching.length > 0 ? matching : voices
  return pool[hash(agentId || 'agent') % pool.length] ?? null
}

/** A pitch between 0.9 and 1.1, picked by a hash so the same agent keeps the same pitch. */
function pickPitch(agentId: string): number {
  return 0.9 + (hash(agentId || 'agent') % 21) / 100
}

/**
 * Reads a reply aloud: ElevenLabs' own voice for the agent when a key is stored, otherwise the
 * browser's speechSynthesis with a voice and pitch chosen once per agent, otherwise nothing.
 * Honours a mute the person set, which survives a reload (localStorage, best effort).
 */
export function useSpeaker() {
  const status = useVoiceStatus()
  const elevenlabsReady = status.data?.provider === 'elevenlabs' && status.data.keyStored
  const supportsBrowser = typeof window !== 'undefined' && 'speechSynthesis' in window
  const provider: SpeakerProvider = elevenlabsReady ? 'elevenlabs' : supportsBrowser ? 'browser' : 'none'

  const [speaking, setSpeaking] = useState(false)
  const [muted, setMutedState] = useState(readMuted)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const urlRef = useRef<string | null>(null)
  // Set false the moment this hook's owner unmounts (a route change away from Chat, say), so a
  // synthesis request already in flight is not turned into audible sound for a screen nobody is
  // looking at any more, with no control left to stop it.
  const mountedRef = useRef(true)

  const cleanup = useCallback(() => {
    if (urlRef.current) {
      URL.revokeObjectURL(urlRef.current)
      urlRef.current = null
    }
    audioRef.current = null
  }, [])

  const stop = useCallback(() => {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) window.speechSynthesis.cancel()
    audioRef.current?.pause()
    cleanup()
    setSpeaking(false)
  }, [cleanup])

  const speak = useCallback(
    async (text: string, agentId?: string | null) => {
      if (muted || !text.trim() || provider === 'none') return
      stop()
      const clipped = text.slice(0, MAX_SPEECH_CHARACTERS)

      if (provider === 'elevenlabs') {
        try {
          setSpeaking(true)
          const blob = await fetchAudio('/api/voice/speech', { body: { text: clipped, agentId: agentId ?? null } })
          // The component that asked for this may be long gone by the time synthesis returns.
          if (!mountedRef.current) return
          const url = URL.createObjectURL(blob)
          urlRef.current = url
          const audio = new Audio(url)
          audioRef.current = audio
          audio.onended = () => {
            setSpeaking(false)
            cleanup()
          }
          audio.onerror = () => {
            setSpeaking(false)
            cleanup()
          }
          await audio.play()
        } catch {
          setSpeaking(false)
          cleanup()
        }
        return
      }

      const utterance = new SpeechSynthesisUtterance(clipped)
      const lang = typeof navigator !== 'undefined' ? navigator.language : 'en-US'
      const voice = pickBrowserVoice(window.speechSynthesis.getVoices(), agentId ?? '', lang)
      if (voice) utterance.voice = voice
      utterance.pitch = pickPitch(agentId ?? '')
      utterance.onend = () => setSpeaking(false)
      utterance.onerror = () => setSpeaking(false)
      setSpeaking(true)
      window.speechSynthesis.speak(utterance)
    },
    [muted, provider, stop, cleanup],
  )

  const setMuted = useCallback(
    (next: boolean) => {
      setMutedState(next)
      writeMuted(next)
      if (next) stop()
    },
    [stop],
  )

  useEffect(
    () => () => {
      mountedRef.current = false
      stop()
    },
    [stop],
  )

  return { speak, stop, speaking, provider, muted, setMuted }
}
