// @find: attachments hook, upload attachment, attach files, upload progress, cancel upload, remove attachment, draft attachments, composer files
// @what: Hook holding the files attached to the message being written and uploading them.
// @flow: Used by Composer.
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../../lib/api'
import {
  MAX_ATTACHMENTS,
  checkFile,
  deleteAttachment,
  isViewableImage,
  kindOf,
  uploadAttachment,
  type AttachmentKind,
  type AttachmentView,
} from '../../lib/attachments'

/*
 * The files attached to the message being written.
 *
 * Each file uploads as soon as it is added, all of them at once, so the person sees each one
 * succeed or fail beside the composer before they press Send. An item that failed stays in the
 * list with its reason until it is removed, and is never sent.
 *
 * What an item holds outside React (its upload's AbortController and its preview's object URL) is
 * kept in a ref and released whenever the item leaves the list, by whatever route: removed,
 * cleared after a send, dropped when the person moves to another conversation, or unmounted.
 */

export type DraftAttachment = {
  localId: string
  file: File
  name: string
  size: number
  /** Null for a file Chat cannot read, which is only ever an error item. */
  kind: AttachmentKind | null
  status: 'uploading' | 'ready' | 'error'
  /** 0 to 1 while uploading. */
  progress: number
  error?: string
  /** Something worth knowing about a file that did attach (an image the assistant cannot view). */
  notice?: string
  attachment?: AttachmentView
  /** An object URL for an image's thumbnail. */
  previewUrl?: string
}

type Held = { controller: AbortController | null; previewUrl: string | null }

let nextLocalId = 0
const localId = () => `att-${(nextLocalId += 1)}`

function failureReason(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Something went wrong. Try again.'
}

function capMessage(skipped: string[]): string {
  const names = skipped.length === 1 ? skipped[0]! : `${skipped.length} files`
  return `You can attach up to ${MAX_ATTACHMENTS} files to one message, so ${names} ${skipped.length === 1 ? 'was' : 'were'} not added.`
}

function canPreview(file: File): boolean {
  return typeof URL.createObjectURL === 'function' && isViewableImage({ name: file.name, mimeType: file.type })
}

// @find: useAttachments, use attachments, attachments hook, upload attachment, attach files, upload progress
export function useAttachments(conversationId: string | null) {
  const [items, setItems] = useState<DraftAttachment[]>([])
  const [statusMessage, setStatusMessage] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  const [seenConversationId, setSeenConversationId] = useState(conversationId)
  const held = useRef(new Map<string, Held>())
  // Items the person removed while their upload was still running: if the server accepted the file
  // anyway, the draft it made is deleted as soon as its id is known.
  const removed = useRef(new Set<string>())
  const conversationRef = useRef(conversationId)

  // Moving to another conversation drops the drafts, adjusted during render as Composer does. A new
  // chat (null) receiving its id on first send is the same conversation, so it keeps them.
  if (seenConversationId !== conversationId) {
    setSeenConversationId(conversationId)
    if (seenConversationId !== null) {
      setItems([])
      setNotice(null)
      setStatusMessage('')
    }
  }

  useEffect(() => {
    conversationRef.current = conversationId
  }, [conversationId])

  // Releases what any item that has left the list was holding.
  useEffect(() => {
    const present = new Set(items.map((item) => item.localId))
    for (const [id, resources] of held.current) {
      if (present.has(id)) continue
      resources.controller?.abort()
      if (resources.previewUrl) URL.revokeObjectURL(resources.previewUrl)
      held.current.delete(id)
    }
  }, [items])

  useEffect(() => {
    const resources = held.current
    return () => {
      for (const entry of resources.values()) {
        entry.controller?.abort()
        if (entry.previewUrl) URL.revokeObjectURL(entry.previewUrl)
      }
      resources.clear()
    }
  }, [])

  const update = useCallback((id: string, change: (item: DraftAttachment) => DraftAttachment) => {
    setItems((current) => current.map((item) => (item.localId === id ? change(item) : item)))
  }, [])

  const upload = useCallback(
    (item: DraftAttachment, controller: AbortController) => {
      uploadAttachment(item.file, conversationRef.current, {
        signal: controller.signal,
        onProgress: (fraction) => update(item.localId, (current) => ({ ...current, progress: fraction })),
      }).then(
        (attachment) => {
          const resources = held.current.get(item.localId)
          if (resources) resources.controller = null
          if (removed.current.has(item.localId)) {
            removed.current.delete(item.localId)
            void deleteAttachment(attachment.id).catch(() => {})
            return
          }
          if (attachment.status === 'unreadable') {
            const reason = attachment.problem ?? `${item.name} could not be read.`
            update(item.localId, (current) => ({ ...current, status: 'error', progress: 1, error: reason, attachment }))
            setStatusMessage(`${item.name} could not be attached: ${reason}`)
            return
          }
          const imageNotice =
            attachment.kind === 'image' && !attachment.imageReadable
              ? 'The assistant cannot view this type of image, but it will be sent with your message.'
              : null
          const extra = attachment.notice ?? imageNotice
          update(item.localId, (current) => ({
            ...current,
            status: 'ready',
            progress: 1,
            attachment,
            ...(extra ? { notice: extra } : {}),
          }))
          setStatusMessage(`${item.name} attached`)
        },
        (error: unknown) => {
          removed.current.delete(item.localId)
          if (error instanceof DOMException && error.name === 'AbortError') return
          const resources = held.current.get(item.localId)
          if (resources) resources.controller = null
          const reason = failureReason(error)
          update(item.localId, (current) => ({ ...current, status: 'error', error: reason }))
          setStatusMessage(`${item.name} could not be attached: ${reason}`)
        },
      )
    },
    [update],
  )

  /** Adds files and starts uploading them. Returns the reason some were not added, or null. */
  const add = useCallback(
    (files: File[] | FileList): string | null => {
      const list = Array.from(files)
      if (list.length === 0) return null
      const counted = items.filter((item) => item.status !== 'error').length
      const room = Math.max(0, MAX_ATTACHMENTS - counted)
      const accepted: DraftAttachment[] = []
      const skipped: string[] = []
      const toUpload: Array<[DraftAttachment, AbortController]> = []

      for (const file of list) {
        const problem = checkFile(file)
        const base = { localId: localId(), file, name: file.name, size: file.size, kind: kindOf(file.name, file.type) }
        if (problem) {
          accepted.push({ ...base, status: 'error', progress: 0, error: problem })
          continue
        }
        if (toUpload.length >= room) {
          skipped.push(file.name)
          continue
        }
        const previewUrl = canPreview(file) ? URL.createObjectURL(file) : null
        const controller = new AbortController()
        const item: DraftAttachment = { ...base, status: 'uploading', progress: 0, ...(previewUrl ? { previewUrl } : {}) }
        held.current.set(item.localId, { controller, previewUrl })
        accepted.push(item)
        toUpload.push([item, controller])
      }

      const message = skipped.length > 0 ? capMessage(skipped) : null
      setNotice(message)
      setItems((current) => [...current, ...accepted])
      const firstError = accepted.find((item) => item.status === 'error')
      if (message) setStatusMessage(message)
      else if (toUpload.length === 1) setStatusMessage(`Uploading ${toUpload[0]![0].name}`)
      else if (toUpload.length > 1) setStatusMessage(`Uploading ${toUpload.length} files`)
      else if (firstError?.error) setStatusMessage(firstError.error)
      for (const [item, controller] of toUpload) upload(item, controller)
      return message
    },
    [items, upload],
  )

  /** Takes a file off the message, cancelling its upload or deleting the unsent draft on the server. */
  const remove = useCallback(
    (id: string) => {
      const item = items.find((entry) => entry.localId === id)
      if (!item) return
      if (item.attachment) void deleteAttachment(item.attachment.id).catch(() => {})
      else if (item.status === 'uploading') removed.current.add(id)
      setItems((current) => current.filter((entry) => entry.localId !== id))
      setNotice(null)
      setStatusMessage(`${item.name} removed`)
    },
    [items],
  )

  /** Forgets every item without deleting anything, for after the message carrying them was sent. */
  const clear = useCallback(() => {
    setItems([])
    setNotice(null)
  }, [])

  const readyIds = items.filter((item) => item.status === 'ready' && item.attachment).map((item) => item.attachment!.id)

  return {
    items,
    add,
    remove,
    clear,
    readyIds,
    uploading: items.some((item) => item.status === 'uploading'),
    hasErrors: items.some((item) => item.status === 'error'),
    /** For an aria-live region: the latest thing that happened to an attachment. */
    statusMessage,
    /** A visible note when some files were not added (the 10-file cap), or null. */
    notice,
  }
}

export type UseAttachments = ReturnType<typeof useAttachments>
