// @find: stepper, progress steps, wizard steps, step indicator, create workspace steps, Stepper
// @what: Small numbered step indicator for multi-step signed-out flows.
// @flow: Used by sign up / workspace creation screens
/*
 * Where a person is in a short, fixed sequence of steps.
 *
 * Said in words as well as colour: the current step is marked aria-current, a finished step
 * carries a tick and "completed" for a screen reader, and every step keeps its number and name.
 * The rail between two dots fills as the step before it is finished.
 */

export type StepperProps = {
  steps: readonly string[]
  current: number
}

// @find: Stepper, step indicator, progress steps
export function Stepper({ steps, current }: StepperProps) {
  return (
    <ol className="auth-steps" aria-label="Progress">
      {steps.map((label, index) => {
        const state = index < current ? 'done' : index === current ? 'current' : 'upcoming'
        return (
          <li key={label} className="auth-step" data-state={state} aria-current={state === 'current' ? 'step' : undefined}>
            <span className="auth-step-dot" aria-hidden="true">
              {state === 'done' ? (
                <svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
                  <path d="M2.5 6.2l2.3 2.3 4.7-5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              ) : (
                index + 1
              )}
            </span>
            <span className="auth-step-label">
              {label}
              {state === 'done' && <span className="visually-hidden">, completed</span>}
            </span>
          </li>
        )
      })}
    </ol>
  )
}
