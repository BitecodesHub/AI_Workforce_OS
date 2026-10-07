import { ApiError, NETWORK_FAILURE, api, normaliseFields, refreshAccessToken } from './api'
import type { ChatMessage } from './queries'
import { accessToken, clearSession } from './session'

/*
 * Files attached to a chat message.
 *
 * A file is uploaded as soon as it is chosen, before the message is sent, so the person sees it
 * succeed or fail beside the composer rather than after pressing Send. An upload made before the
 * chat exists (a new chat has no id until its first message) is a draft the server binds to the
 * conversation on send. The server reads every file's content to decide what it is; the checks
 * here are only the cheap ones (name, type and size), so a person hears about an obvious mistake
 * without waiting for an upload.
 *
 * Bytes come back through an authorized fetch, the same way voice.ts fetches audio, because a link
 * or an <img> cannot carry the bearer token.
 */

export type AttachmentKind = 'pdf' | 'document' | 'presentation' | 'spreadsheet' | 'text' | 'image'

/** An uploaded file, as the server describes it. */
export type AttachmentView = {
  id: string
  conversationId: string | null
  name: string
  mimeType: string
  size: number
  kind: AttachmentKind
  status: 'ready' | 'unreadable'
  pageCount: number | null
  /** Why the text could not be read, in plain words. */
  problem: string | null
  notice: string | null
  /** False for an image the models cannot view, such as HEIC. */
  imageReadable: boolean
  savedToKnowledge: boolean
  createdAt: string
}

/** An attachment as a sent message carries it, in `detail.attachments`. */
export type SentAttachment = {
  id: string
  name: string
  mimeType: string
  size: number
  kind: AttachmentKind
  pageCount: number | null
}

export const MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
export const MAX_ATTACHMENTS = 10

const EXTENSION_KIND: Record<string, AttachmentKind> = {
  pdf: 'pdf',
  docx: 'document',
  doc: 'document',
  pptx: 'presentation',
  ppt: 'presentation',
  xlsx: 'spreadsheet',
  xls: 'spreadsheet',
  csv: 'spreadsheet',
  txt: 'text',
  md: 'text',
  json: 'text',
  html: 'text',
  htm: 'text',
  png: 'image',
  jpg: 'image',
  jpeg: 'image',
  webp: 'image',
  gif: 'image',
  heic: 'image',
  heif: 'image',
}

const MIME_KIND: Record<string, AttachmentKind> = {
  'application/pdf': 'pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'document',
  'application/msword': 'document',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation': 'presentation',
  'application/vnd.ms-powerpoint': 'presentation',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'spreadsheet',
  'application/vnd.ms-excel': 'spreadsheet',
  'text/csv': 'spreadsheet',
  'text/plain': 'text',
  'text/markdown': 'text',
  'application/json': 'text',
  'text/html': 'text',
  'image/png': 'image',
  'image/jpeg': 'image',
  'image/webp': 'image',
  'image/gif': 'image',
  'image/heic': 'image',
  'image/heif': 'image',
}

/** For <input type="file" accept>: every extension and type Chat reads. */
export const ACCEPT = [
  ...Object.keys(EXTENSION_KIND).map((extension) => `.${extension}`),
  ...Object.keys(MIME_KIND),
].join(',')

/** Image types a browser can draw, so a thumbnail or an opened tab shows the picture. */
const VIEWABLE_IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif'])

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot >= 0 ? name.slice(dot + 1).toLowerCase() : ''
}

/** The kind of file, from its extension first and its type second, or null when Chat cannot read it. */
export function kindOf(name: string, mime: string): AttachmentKind | null {
  return EXTENSION_KIND[extensionOf(name)] ?? MIME_KIND[mime.split(';')[0]!.trim().toLowerCase()] ?? null
}

/** Whether a browser can show this image itself (HEIC, for one, it cannot). */
export function isViewableImage(attachment: { name: string; mimeType: string }): boolean {
  const mime = attachment.mimeType.split(';')[0]!.trim().toLowerCase()
  if (VIEWABLE_IMAGE_TYPES.has(mime)) return true
  return ['png', 'jpg', 'jpeg', 'webp', 'gif'].includes(extensionOf(attachment.name)) && !mime.startsWith('image/hei')
}

/** The plain reason a file cannot be attached, or null when it can be uploaded. */
export function checkFile(file: { name: string; type: string; size: number }): string | null {
  if (kindOf(file.name, file.type) === null) {
    return `${file.name} is not a file type Chat can read. Attach a PDF, Word, PowerPoint, Excel or CSV file, text, or an image.`
  }
  if (file.size > MAX_ATTACHMENT_BYTES) return `${file.name} is larger than 25 MB.`
  if (file.size === 0) return `${file.name} is empty.`
  return null
}

/** "820 bytes", "14 KB", "3.2 MB". */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return ''
  if (bytes < 1024) return `${bytes.toLocaleString('en-AU')} ${bytes === 1 ? 'byte' : 'bytes'}`
  const kb = bytes / 1024
  if (kb < 1024) return `${Math.max(1, Math.round(kb)).toLocaleString('en-AU')} KB`
  const mb = kb / 1024
  return `${mb.toLocaleString('en-AU', { maximumFractionDigits: mb < 10 ? 1 : 0 })} MB`
}

/** "1 page", "12 pages", or null when the count is unknown. */
export function formatPages(pageCount: number | null | undefined): string | null {
  if (typeof pageCount !== 'number' || pageCount <= 0) return null
  return `${pageCount.toLocaleString('en-AU')} ${pageCount === 1 ? 'page' : 'pages'}`
}

/* ---- Reaching the server ------------------------------------------------------------------------ */

const BASE = '/api/conversations/attachments'
const contentPath = (id: string) => `${BASE}/${encodeURIComponent(id)}/content`

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function sessionEnded(): ApiError {
  clearSession()
  return new ApiError(401, 'token_expired', 'Your session has ended. Sign in again.', false, {})
}

/** A failed response's problem body read the way api.ts reads it, with a plain sentence when it says nothing. */
function problemError(status: number, text: string, fallback: string, requestId: string | null): ApiError {
  const body = text ? parseJson(text) : null
  const problem = isRecord(body) ? body : {}
  const code = typeof problem.code === 'string' && problem.code ? problem.code : 'unknown_error'
  const message = typeof problem.detail === 'string' && problem.detail ? problem.detail : fallback
  const retryable = typeof problem.retryable === 'boolean' ? problem.retryable : status >= 500
  const id = (typeof problem.requestId === 'string' && problem.requestId) || requestId || undefined
  return new ApiError(status, code, message, retryable, normaliseFields(problem.errors), id)
}

/** What a person is told when an upload fails and the server said nothing more useful. */
function uploadFallback(status: number, name: string): string {
  if (status === 413) return `${name} is larger than 25 MB.`
  if (status === 415 || status === 422) return `${name} could not be attached. Check that it is a PDF, Office file, text or image.`
  if (status === 503) return 'Files cannot be read right now. Try again in a few minutes.'
  return `${name} could not be attached. Try again.`
}

function abortError(): DOMException {
  return new DOMException('The upload was cancelled.', 'AbortError')
}

type XhrResult = { status: number; text: string; requestId: string | null }

function sendUpload(
  url: string,
  file: File,
  token: string | null,
  onProgress: ((fraction: number) => void) | undefined,
  signal: AbortSignal | undefined,
): Promise<XhrResult> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError())
      return
    }
    const xhr = new XMLHttpRequest()
    const onAbort = () => xhr.abort()
    xhr.open('POST', url)
    xhr.withCredentials = true
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) onProgress?.(Math.min(1, event.loaded / event.total))
    }
    xhr.onload = () => {
      signal?.removeEventListener('abort', onAbort)
      resolve({ status: xhr.status, text: xhr.responseText ?? '', requestId: xhr.getResponseHeader('X-Request-Id') })
    }
    xhr.onerror = () => {
      signal?.removeEventListener('abort', onAbort)
      reject(new ApiError(0, 'network_error', NETWORK_FAILURE, true, {}))
    }
    xhr.onabort = () => {
      signal?.removeEventListener('abort', onAbort)
      reject(abortError())
    }
    signal?.addEventListener('abort', onAbort, { once: true })
    const form = new FormData()
    form.append('file', file, file.name)
    xhr.send(form)
  })
}

/**
 * Uploads one file, reporting progress from 0 to 1. Without a conversation id the upload is a
 * draft for a chat not created yet. A cancelled upload rejects with an AbortError; every other
 * failure is an ApiError whose message can be shown as it is.
 */
export async function uploadAttachment(
  file: File,
  conversationId: string | null,
  options: { onProgress?: (fraction: number) => void; signal?: AbortSignal } = {},
): Promise<AttachmentView> {
  const url = conversationId ? `${BASE}?conversationId=${encodeURIComponent(conversationId)}` : BASE
  const token = accessToken()
  let result = await sendUpload(url, file, token, options.onProgress, options.signal)
  if (result.status === 401 && token) {
    if (!(await refreshAccessToken())) throw sessionEnded()
    options.onProgress?.(0)
    result = await sendUpload(url, file, accessToken(), options.onProgress, options.signal)
  }
  if (result.status < 200 || result.status >= 300) {
    throw problemError(result.status, result.text, uploadFallback(result.status, file.name), result.requestId)
  }
  const body = parseJson(result.text)
  if (!isRecord(body) || typeof body.id !== 'string') {
    throw new ApiError(result.status, 'unknown_error', uploadFallback(500, file.name), true, {})
  }
  return body as AttachmentView
}

/** Removes an attachment that was uploaded but not sent. */
export function deleteAttachment(id: string): Promise<void> {
  return api<void>(`${BASE}/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

/** The file's bytes, with the bearer token and one refresh on 401, as fetchAudio does. */
export async function fetchAttachmentBlob(id: string, signal?: AbortSignal): Promise<Blob> {
  const send = async (token: string | null) => {
    try {
      return await fetch(contentPath(id), {
        method: 'GET',
        credentials: 'include',
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        ...(signal ? { signal } : {}),
      })
    } catch (error) {
      if (signal?.aborted) throw error
      throw new ApiError(0, 'network_error', NETWORK_FAILURE, true, {})
    }
  }
  let response = await send(accessToken())
  if (response.status === 401 && accessToken()) {
    if (!(await refreshAccessToken())) throw sessionEnded()
    response = await send(accessToken())
  }
  if (!response.ok) {
    const text = await response.text().catch(() => '')
    throw problemError(response.status, text, 'This file could not be opened. Try again.', response.headers.get('X-Request-Id'))
  }
  return response.blob()
}

type Openable = { id: string; name: string; mimeType: string; kind: AttachmentKind }

/** Object URLs handed to a new tab or a download are freed once the browser has had time to use them. */
function revokeLater(url: string) {
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.rel = 'noopener'
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  link.remove()
  revokeLater(url)
}

/**
 * The blob as the browser should show it in a tab, or null when it should be downloaded instead.
 * Text of every kind (HTML included) is shown as plain text: a stored HTML file must never run as
 * a page of this console, where it could read the session.
 */
function viewableBlob(blob: Blob, attachment: Openable): Blob | null {
  if (attachment.kind === 'pdf') return new Blob([blob], { type: 'application/pdf' })
  if (attachment.kind === 'text') return new Blob([blob], { type: 'text/plain;charset=utf-8' })
  if (attachment.kind === 'image' && isViewableImage(attachment)) {
    const mime = attachment.mimeType.split(';')[0]!.trim().toLowerCase()
    return new Blob([blob], { type: VIEWABLE_IMAGE_TYPES.has(mime) ? mime : 'image/png' })
  }
  return null
}

/**
 * Opens a PDF, image or text file in a new tab, and downloads anything else (Office files, HEIC).
 * The tab is opened before the bytes arrive, while the click still counts as the person's, so a
 * pop-up blocker lets it through; if it is blocked anyway, the file is downloaded instead.
 */
export async function openAttachment(attachment: Openable): Promise<void> {
  const inTab = attachment.kind === 'pdf' || attachment.kind === 'text' || (attachment.kind === 'image' && isViewableImage(attachment))
  const tab = inTab ? window.open('', '_blank') : null
  try {
    const blob = await fetchAttachmentBlob(attachment.id)
    const viewable = viewableBlob(blob, attachment)
    if (tab && viewable) {
      tab.opener = null
      const url = URL.createObjectURL(viewable)
      tab.location.href = url
      revokeLater(url)
      return
    }
    tab?.close()
    saveBlob(blob, attachment.name)
  } catch (error) {
    tab?.close()
    throw error
  }
}

/** Saves the file to the person's computer under its own name. */
export async function downloadAttachment(attachment: { id: string; name: string }): Promise<void> {
  saveBlob(await fetchAttachmentBlob(attachment.id), attachment.name)
}

/** Adds the file to the workspace's knowledge, so agents can find it in later chats. */
export function saveAttachmentToKnowledge(id: string): Promise<{ documentId: string; sourceName: string }> {
  return api<{ documentId: string; sourceName: string }>(`${BASE}/${encodeURIComponent(id)}/knowledge`, { method: 'POST' })
}

const KINDS = new Set<AttachmentKind>(['pdf', 'document', 'presentation', 'spreadsheet', 'text', 'image'])

/** The attachments a sent message carries, skipping any entry too damaged to show. */
export function sentAttachmentsOf(message: Pick<ChatMessage, 'detail'>): SentAttachment[] {
  const detail: unknown = message.detail
  const list = isRecord(detail) ? detail.attachments : undefined
  if (!Array.isArray(list)) return []
  const out: SentAttachment[] = []
  for (const entry of list) {
    if (!isRecord(entry) || typeof entry.id !== 'string' || !entry.id) continue
    const name = typeof entry.name === 'string' && entry.name ? entry.name : 'Attached file'
    const mimeType = typeof entry.mimeType === 'string' ? entry.mimeType : ''
    const kind =
      typeof entry.kind === 'string' && KINDS.has(entry.kind as AttachmentKind)
        ? (entry.kind as AttachmentKind)
        : (kindOf(name, mimeType) ?? 'document')
    out.push({
      id: entry.id,
      name,
      mimeType,
      size: typeof entry.size === 'number' && Number.isFinite(entry.size) ? entry.size : 0,
      kind,
      pageCount: typeof entry.pageCount === 'number' ? entry.pageCount : null,
    })
  }
  return out
}
