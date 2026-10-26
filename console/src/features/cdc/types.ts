export interface CdcTaskDefinition {
  captureId: string
  table: string
  generation: string
  targetTable: string
}

export interface CdcTaskRequest extends CdcTaskDefinition {
  requestId: string
}

export interface CdcTaskView extends CdcTaskRequest {
  taskId: string
  definitionFingerprint: string
  desiredState: string
  controlRevision: number
  phase: string
  journalRevision: number
  executionId: string | null
  captureReadiness: string
  initializationEvidence: string | null
  runtimeHealth: string
  releaseStatus: string
  failureCode: string | null
  observedAt: number
  lastVerifiedAt: number | null
  cancelAllowed: boolean
  cancellationEvidence?: string | null
}

export interface CdcTaskAcceptance { task: CdcTaskView; created: boolean }
export interface CdcTaskPage { items: CdcTaskView[]; nextAfter: string | null }
export interface CdcCaptureView {
  captureId: string
  database: string
  readiness: string
  runtimeHealth: string
  reason: string | null
}
export interface CdcPlatformStatus {
  enabled: boolean
  ownerState: string
  reason: string | null
  activeSteps: number
  maxActiveSteps: number
}
