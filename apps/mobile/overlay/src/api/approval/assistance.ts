import { mobileApprovalRequest } from '@/api/approval/transport'

export type ApprovalAssistanceUseCase =
  | 'MATERIAL_COMPLETENESS'
  | 'RISK_REVIEW'
  | 'SUMMARY'

export interface ApprovalAssistanceLimitation {
  code: string
  message: string
}

export interface ApprovalAssistanceTaskSnapshot {
  compilerVersion: string
  contentHash: string
  definitionKey: string
  definitionVersion: number
  formKey: string
  formVersion: number
  instanceId: string
  instanceUpdatedAt: string
  taskDefinitionKey: string
  taskId: string
  taskUpdatedAt: string
}

export interface ApprovalAssistanceReadView {
  advisoryResult: null
  assertionStatus: 'UNVERIFIED_ADVISORY'
  authority: 'ADVISORY'
  availability: 'AVAILABLE' | 'PROVIDER_NOT_CONFIGURED'
  availableUseCases: ApprovalAssistanceUseCase[]
  code: 'AI_ASSISTANCE_AVAILABLE' | 'AI_ASSISTANCE_PROVIDER_REQUIRED'
  commandAvailable: false
  instanceId: string
  limitations: ApprovalAssistanceLimitation[]
  needsHumanReview: true
  providerInvocationStarted: false
  providerSelectable: false
  requestedUseCase: ApprovalAssistanceUseCase
  resultAvailable: false
  taskId: string
  taskSnapshot: ApprovalAssistanceTaskSnapshot
}

export interface ApprovalAssistanceObservation {
  evidenceReferenceIds: string[]
  id: string
  text: string
}

export interface ApprovalAssistanceRiskSignal {
  evidenceReferenceIds: string[]
  id: string
  severity: 'HIGH' | 'INFO' | 'LOW' | 'MEDIUM'
  text: string
}

export interface ApprovalAssistanceMissingMaterial {
  id: string
  materialType: string
  reason: string
}

export interface ApprovalAssistanceRecommendation {
  evidenceReferenceIds: string[]
  id: string
  text: string
  type:
    | 'NO_ACTION_SUGGESTED'
    | 'REQUEST_INFORMATION'
    | 'REVIEW_RISK'
    | 'SEEK_SPECIALIST_REVIEW'
    | 'VERIFY_EVIDENCE'
}

export interface ApprovalAssistanceEvidenceReference {
  description: string
  fieldKey: string
  id: string
}

export interface ApprovalAssistanceAdvisoryView {
  confidence: {
    band: 'HIGH' | 'LOW' | 'MEDIUM'
    score: number
  }
  evidenceReferences: ApprovalAssistanceEvidenceReference[]
  limitations: string[]
  missingMaterials: ApprovalAssistanceMissingMaterial[]
  observations: ApprovalAssistanceObservation[]
  recommendations: ApprovalAssistanceRecommendation[]
  riskSignals: ApprovalAssistanceRiskSignal[]
  summary: string
}

export interface ApprovalAssistanceGenerationView {
  advisoryResult: ApprovalAssistanceAdvisoryView | null
  assertionStatus: 'UNVERIFIED_ADVISORY'
  authority: 'ADVISORY'
  code: string
  commandAvailable: false
  evidenceId: string | null
  fallbackAttempted: false
  needsHumanReview: true
  providerSelectable: false
  retryAttempted: false
  status:
    | 'DISABLED'
    | 'EVIDENCE_CONFLICT'
    | 'EVIDENCE_UNAVAILABLE'
    | 'INVALID_OUTPUT'
    | 'INVALID_REQUEST'
    | 'LOW_CONFIDENCE'
    | 'NOT_FOUND'
    | 'POLICY_BLOCKED'
    | 'PROVIDER_UNAVAILABLE'
    | 'STALE_TASK'
    | 'SUCCESS'
    | 'TIMEOUT'
    | 'UNKNOWN'
}

export function findApprovalAssistance(
  taskId: string,
  useCase: ApprovalAssistanceUseCase = 'SUMMARY',
) {
  return mobileApprovalRequest<ApprovalAssistanceReadView>(
    `/approval/tasks/${encodeURIComponent(taskId)}/assistance?useCase=${encodeURIComponent(useCase)}`,
    { method: 'GET' },
  )
}

export function generateApprovalAssistance(
  taskId: string,
  useCase: ApprovalAssistanceUseCase,
) {
  return mobileApprovalRequest<ApprovalAssistanceGenerationView>(
    `/approval/tasks/${encodeURIComponent(taskId)}/assistance/generations`,
    {
      data: JSON.stringify({ useCase }),
      method: 'POST',
    },
  )
}
