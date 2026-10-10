// @find: toast, notification, feedback message, saved, success error message, ToastProvider, useToast
// @what: Shows a brief message after every action finishes (saved, approved, failed).
// @flow: Used by useCopyText and every mutation handler
import { createContext, useCallback, useContext, useState } from 'react'
import type { ReactNode } from 'react'

/*
 * Feedback for an action that finished.
 *
 * Every action a person takes gets an answer: saved, approved, uploaded, or what went wrong.
 * An action that succeeds silently is indistinguishable from one that did nothing, and people
 * respond to that uncertainty by clicking again - which, for "approve and send", is the one thing
 * they must not do.
 *
 * Messages are announced to screen readers through a live region, and they leave on their own
 * after a few seconds, except errors, which stay until dismissed because somebody may need to
 * read them twice.
 */

type Tone = 'success' | 'error' | 'info'
type ToastAction = { label: string; onSelect: () => void }
type ToastOptions = { action?: ToastAction }
type Toast = { id: number; tone: Tone; message: string; action?: ToastAction }

const ToastContext = createContext<(tone: Tone, message: string, opts?: ToastOptions) => void>(() => {})

/** A toast with an action stays long enough to read and press it, rather than the ordinary 4.5 s. */
const DEFAULT_LIFETIME_MS = 4500
const ACTION_LIFETIME_MS = 8000

// @find: toast provider, toast container
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])

  const dismiss = useCallback((id: number) => setToasts((all) => all.filter((t) => t.id !== id)), [])

  const push = useCallback(
    (tone: Tone, message: string, opts?: ToastOptions) => {
      const id = Date.now() + Math.random()
      const action = opts?.action
      setToasts((all) => [...all.slice(-3), action ? { id, tone, message, action } : { id, tone, message }])
      if (tone !== 'error') setTimeout(() => dismiss(id), action ? ACTION_LIFETIME_MS : DEFAULT_LIFETIME_MS)
    },
    [dismiss],
  )

  return (
    <ToastContext.Provider value={push}>
      {children}
      {/*
       * Each toast is its own live region rather than one shared one: an error needs an assertive
       * announcement that interrupts (the same "alert" role Notice already uses for its warning
       * tone), while a success or info toast waits its turn like any polite update.
       */}
      <div className="toast-region">
        {toasts.map((toast) => {
          const action = toast.action
          return (
            <div
              key={toast.id}
              className={`toast toast-${toast.tone}`}
              role={toast.tone === 'error' ? 'alert' : 'status'}
              aria-live={toast.tone === 'error' ? 'assertive' : 'polite'}
            >
              <span>{toast.message}</span>
              {action && (
                <button
                  type="button"
                  className="toast-action"
                  onClick={() => {
                    action.onSelect()
                    dismiss(toast.id)
                  }}
                >
                  {action.label}
                </button>
              )}
              <button type="button" className="toast-close" aria-label="Dismiss" onClick={() => dismiss(toast.id)}>
                <svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
                  <path d="M2 2l8 8M10 2l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                </svg>
              </button>
            </div>
          )
        })}
      </div>
    </ToastContext.Provider>
  )
}

// @find: use toast, show success or error message
export function useToast() {
  const push = useContext(ToastContext)
  return {
    success: (message: string, opts?: ToastOptions) => push('success', message, opts),
    error: (message: string, opts?: ToastOptions) => push('error', message, opts),
    info: (message: string, opts?: ToastOptions) => push('info', message, opts),
  }
}
