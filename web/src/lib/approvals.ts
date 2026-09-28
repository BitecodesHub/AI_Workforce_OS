import { ApiError, describeApiError } from './api'
import { statusLabel, toolLabel } from './labels'
import type { Approval } from './queries'

/*
 * Reading an approval request in words, shared between the Approvals queue and anywhere else a
 * pending or decided request needs to say the same thing the same way (a run's own trace, a chat
 * thread's inline approval card).
 */

/**
 * The request's summary in words. The gateway writes it as "Send something outside the workspace
 * using gmail.send_message", so the raw tool name is swapped for its label.
 */
export function readableSummary(approval: Approval): string {
  const tool = approval.tool?.trim()
  if (!tool || !approval.summary.includes(tool)) return approval.summary
  return approval.summary.split(tool).join(toolLabel(tool))
}

/** The backend guarantees valid JSON; the fallback only covers a payload from before this column was populated. */
export function formatPayload(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}

/** What happened to the run once the decision was made, from DecisionResult.runStatus. */
export function runOutcome(runStatus: string | null | undefined): string {
  switch (runStatus?.toLowerCase()) {
    case 'completed':
      return 'The run finished.'
    case 'waiting_approval':
      return 'The run is waiting for another approval.'
    case 'failed':
      return 'The run failed.'
    case 'cancelled':
      return 'The run was stopped.'
    case undefined:
    case '':
      return 'Open the run to see where it stands.'
    default:
      return `Run status: ${statusLabel('run', runStatus).label}.`
  }
}

/** Why a decision did not go through, in words. The queue refreshes after any failure. */
export function decisionError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'approval_already_decided') return 'Someone already decided this request.'
    if (error.code === 'approval_expired') return 'This request expired, so the run was stopped.'
    if (error.isPermissionDenied) return 'Deciding this request needs a permission your role does not have.'
  }
  return describeApiError(error)
}
