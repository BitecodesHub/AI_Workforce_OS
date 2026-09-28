import { useEffect, useState } from 'react'
import { fetchAudio } from '../../lib/voice'

/**
 * A voice clip a tool_call step produced (its detail.clipId), played from an ordinary <audio>
 * element. GET /api/voice/clips/{id} needs the viewer's own bearer token, which an <audio src>
 * cannot attach itself, so the bytes are fetched here and turned into an object URL, revoked again
 * when the clip is replaced or this step scrolls out of the page.
 */
export function ClipAudio({ clipId }: { clipId: string }) {
  const [url, setUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let cancelled = false
    let objectUrl: string | null = null

    fetchAudio(`/api/voice/clips/${clipId}`, { method: 'GET' })
      .then((blob) => {
        if (cancelled) return
        objectUrl = URL.createObjectURL(blob)
        setUrl(objectUrl)
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })

    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [clipId])

  if (failed) return <p className="caption muted">The voice clip could not be loaded.</p>
  if (!url) return <p className="caption muted">Loading the voice clip…</p>
  return <audio controls src={url} style={{ width: '100%', marginTop: 'var(--space-2)' }} />
}
