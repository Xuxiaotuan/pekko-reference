// Tab-scoped UI request identity only; this is not a CDC offset or recovery checkpoint.
const key = (workflowId: string) => `pekko.pendingExecution.${workflowId}`
export const pendingExecution = (workflowId: string): string | null => sessionStorage.getItem(key(workflowId))
export function reserveExecution(workflowId: string): string {
  const id = pendingExecution(workflowId) ?? crypto.randomUUID()
  sessionStorage.setItem(key(workflowId), id)
  return id
}
export function confirmExecution(workflowId: string, requestId: string): void {
  if (pendingExecution(workflowId) === requestId) sessionStorage.removeItem(key(workflowId))
}
