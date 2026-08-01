import { approvalRequest } from '#/api/approval/transport';

export type ApprovalAssistanceUseCase =
  | 'MATERIAL_COMPLETENESS'
  | 'RISK_REVIEW'
  | 'SUMMARY';

export interface ApprovalAssistanceLimitation {
  code: string;
  message: string;
}

export interface ApprovalAssistanceTaskSnapshot {
  compilerVersion: string;
  contentHash: string;
  definitionKey: string;
  definitionVersion: number;
  formKey: string;
  formVersion: number;
  instanceId: string;
  instanceUpdatedAt: string;
  taskDefinitionKey: string;
  taskId: string;
  taskUpdatedAt: string;
}

export interface ApprovalAssistanceReadView {
  advisoryResult?: never;
  assertionStatus: 'UNVERIFIED_ADVISORY';
  authority: 'ADVISORY';
  availability: 'PROVIDER_NOT_CONFIGURED';
  availableUseCases: ApprovalAssistanceUseCase[];
  code: 'AI_ASSISTANCE_P6_PROVIDER_REQUIRED';
  commandAvailable: false;
  instanceId: string;
  limitations: ApprovalAssistanceLimitation[];
  needsHumanReview: true;
  providerInvocationStarted: false;
  providerSelectable: false;
  requestedUseCase: ApprovalAssistanceUseCase;
  resultAvailable: false;
  taskId: string;
  taskSnapshot: ApprovalAssistanceTaskSnapshot;
}

export function findApprovalAssistance(
  taskId: string,
  useCase: ApprovalAssistanceUseCase = 'SUMMARY',
) {
  const query = new URLSearchParams({ useCase });
  return approvalRequest<ApprovalAssistanceReadView>(
    `/approval/tasks/${encodeURIComponent(taskId)}/assistance?${query.toString()}`,
    { method: 'GET' },
  );
}
