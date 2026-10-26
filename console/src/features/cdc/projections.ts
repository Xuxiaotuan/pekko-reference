import type { CdcTaskView } from './types'

/** List/detail requests can finish out of order. Keep the latest monotone projection for this task. */
export function reconcileCdcSelection(current: CdcTaskView | null, incoming: readonly CdcTaskView[]): CdcTaskView | null {
  if (!current) return null
  return incoming.reduce((selected, candidate) => {
    if (candidate.taskId !== selected.taskId || candidate.controlRevision < selected.controlRevision ||
      candidate.journalRevision < selected.journalRevision || candidate.observedAt < selected.observedAt) return selected
    return candidate
  }, current)
}

/** An old proof remains evidence of initialization, but a failed observation cannot authorize an action. */
export function cdcTaskForDisplay(task: CdcTaskView, observationUnavailable: boolean): CdcTaskView {
  return observationUnavailable ? { ...task, runtimeHealth: 'UNKNOWN', cancelAllowed: false } : task
}
