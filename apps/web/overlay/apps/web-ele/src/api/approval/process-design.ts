import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export type ApprovalMode = 'ALL' | 'ANY' | 'SINGLE';
export type ApprovalDesignDraftStatus = 'ARCHIVED' | 'DRAFT' | 'PUBLISHED' | 'VALIDATED';
export type ApprovalSaveMode = 'AUTO_SAVE' | 'EXPLICIT';
export type AssigneeResolver =
  | 'INITIATOR_MANAGER'
  | 'VARIABLE_USER'
  | 'VARIABLE_USER_LIST';
export type ComparisonOperator =
  | 'EQUAL'
  | 'GREATER_THAN'
  | 'GREATER_THAN_OR_EQUAL'
  | 'LESS_THAN'
  | 'LESS_THAN_OR_EQUAL'
  | 'NOT_EQUAL';
export type ValidationSeverity = 'ERROR' | 'INFO' | 'WARNING';

interface BaseNode {
  id: string;
  name: string;
}

export interface StartNode extends BaseNode {
  kind: 'START';
  next: string;
}

export interface ApprovalStep extends BaseNode {
  assignee: AssigneeRule;
  kind: 'APPROVAL';
  mode: { type: ApprovalMode };
  next: string;
  rejectNext?: string;
}

export interface HandleStep extends BaseNode {
  assignee: AssigneeRule;
  kind: 'HANDLE';
  next: string;
}

export interface ConditionStep extends BaseNode {
  defaultNext: string;
  kind: 'CONDITION';
  routes: ConditionRoute[];
}

export interface ParallelSplitNode extends BaseNode {
  branches: ParallelBranch[];
  joinNodeId: string;
  kind: 'PARALLEL_SPLIT';
}

export interface ParallelJoinNode extends BaseNode {
  kind: 'PARALLEL_JOIN';
  next: string;
}

export interface EndNode extends BaseNode {
  kind: 'END';
}

export type ApprovalNode =
  | ApprovalStep
  | ConditionStep
  | EndNode
  | HandleStep
  | ParallelJoinNode
  | ParallelSplitNode
  | StartNode;

export interface AssigneeRule {
  emptyPolicy: 'FAIL';
  resolver: AssigneeResolver;
  variable: string;
}

export interface ComparisonCondition {
  field: string;
  operator: ComparisonOperator;
  value: number;
}

export interface ConditionRoute {
  condition: ComparisonCondition;
  next: string;
}

export interface ParallelBranch {
  id: string;
  name: string;
  next: string;
}

export interface ApprovalDefinition {
  definitionKey: string;
  name: string;
  nodes: ApprovalNode[];
  schemaVersion: '1.0';
  startNodeId: string;
  version: number;
}

export interface FormPackageReference {
  formKey: string;
  packageHash: string;
  packageVersion: number;
}

export interface ApprovalDesignDraft {
  createdAt: string;
  createdBy: string;
  definition: ApprovalDefinition;
  definitionKey: string;
  draftId: string;
  formPackage: FormPackageReference;
  name: string;
  publishedDefinitionVersion?: number;
  publishedReleaseVersion?: number;
  revision: number;
  sourceDefinitionVersion?: number;
  status: ApprovalDesignDraftStatus;
  tenantId: string;
  updatedAt: string;
  updatedBy: string;
}

export interface ApprovalDesignDraftSummary {
  definitionKey: string;
  definitionVersion: number;
  draftId: string;
  formPackageVersion: number;
  name: string;
  publishedDefinitionVersion?: number;
  publishedReleaseVersion?: number;
  revision: number;
  status: ApprovalDesignDraftStatus;
  updatedAt: string;
  updatedBy: string;
}

export interface ApprovalDesignDraftPage {
  hasMore: boolean;
  items: ApprovalDesignDraftSummary[];
  limit: number;
  offset: number;
  total: number;
}

export interface ValidationIssue {
  code: string;
  message: string;
  severity: ValidationSeverity;
  subject: string;
}

export interface ApprovalDesignValidationResult {
  definitionHash: string;
  draftId: string;
  issues: ValidationIssue[];
  revision: number;
  status: ApprovalDesignDraftStatus;
  valid: boolean;
}

export interface SimulationStep {
  kind: ApprovalNode['kind'];
  nextNodeId?: string;
  nodeId: string;
  nodeName: string;
  outcome: string;
  sequence: number;
}

export interface SimulationIssue {
  code: string;
  message: string;
  nodeId: string;
}

export interface SimulationResult {
  completed: boolean;
  issues: SimulationIssue[];
  status: 'BLOCKED' | 'COMPLETED' | 'REJECTED' | 'TRANSITION_LIMIT_REACHED';
  steps: SimulationStep[];
  terminalNodeId: string;
}

export interface StableIdentitySnapshot {
  snapshotHash: string;
  subjectId: string;
  subjectType: string;
}

export interface AssigneeResolutionSummary {
  approvalMode: string;
  identities: StableIdentitySnapshot[];
  inputVariable: string;
  nodeId: string;
  resolvable: boolean;
  resolver: AssigneeResolver;
}

export interface ApprovalSimulationResponse {
  assigneeResolutions: AssigneeResolutionSummary[];
  draftId: string;
  pathSummary: string;
  revision: number;
  simulation: SimulationResult;
  staticIssues: ValidationIssue[];
}

export interface ApprovalReleasePackage {
  bpmnHash: string;
  compiledArtifactHash: string;
  compilerVersion: string;
  definitionHash: string;
  definitionKey: string;
  definitionVersion: number;
  deploymentMetadataHash: string;
  formHash: string;
  formPackageHash: string;
  formPackageVersion: number;
  packageHash: string;
  publishedAt: string;
  publishedBy: string;
  releaseVersion: number;
  sourceDraftId: string;
  tenantId: string;
  uiSchemaHash: string;
  uiSchemaVersion: number;
}

export interface ApprovalPublishResult {
  draftRevision: number;
  releasePackage: ApprovalReleasePackage;
  replayedExistingRelease: boolean;
}

export interface CreateApprovalDesignDraftInput {
  definitionKey: string;
  definitionVersion: number;
  formPackageVersion: number;
  name: string;
  source: 'BLANK' | 'PURCHASE_PAYMENT_TEMPLATE';
}

export interface UpdateApprovalDesignDraftInput {
  definition: ApprovalDefinition;
  expectedRevision: number;
  formPackageVersion: number;
  name: string;
  saveMode: ApprovalSaveMode;
}

export interface ApprovalSimulationInput {
  decisions: Record<string, 'APPROVE' | 'REJECT'>;
  formValues: Record<string, unknown>;
  identityInputs?: Record<string, StableIdentitySnapshot[]>;
  maxTransitions?: number;
}

interface ApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
}

export class ApprovalDesignApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = 'ApprovalDesignApiError';
  }
}

function operationId(prefix: string) {
  const value = globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${value}`;
}

function writeHeaders(action: string) {
  const requestId = operationId(`web-${action}-request`);
  return {
    'Idempotency-Key': operationId(`web-${action}`),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  };
}

async function request<T>(path: string, init: RequestInit = {}) {
  const runtime = getApprovalRuntimeConfig();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  headers.set('X-Operator-Id', runtime.operatorId);
  headers.set('X-Tenant-Id', runtime.tenantId);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${runtime.apiBaseUrl}${path}`, {
    ...init,
    credentials: 'same-origin',
    headers,
  });
  if (!response.ok) {
    let payload: ApiErrorPayload | undefined;
    try {
      payload = (await response.json()) as ApiErrorPayload;
    } catch {
      payload = undefined;
    }
    throw new ApprovalDesignApiError(
      payload?.message || payload?.error || payload?.code ||
        `请求失败（${response.status}）`,
      response.status,
      payload?.code,
    );
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export function findApprovalDesignDrafts(
  keyword = '',
  status?: ApprovalDesignDraftStatus,
  limit = 50,
  offset = 0,
) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (keyword.trim()) query.set('keyword', keyword.trim());
  if (status) query.set('status', status);
  return request<ApprovalDesignDraftPage>(
    `/approval/process-design-drafts?${query.toString()}`,
  );
}

export function findApprovalDesignDraft(draftId: string) {
  return request<ApprovalDesignDraft>(
    `/approval/process-design-drafts/${encodeURIComponent(draftId)}`,
  );
}

export function createApprovalDesignDraft(input: CreateApprovalDesignDraftInput) {
  return request<ApprovalDesignDraft>('/approval/process-design-drafts', {
    body: JSON.stringify(input),
    headers: writeHeaders('approval-design-create'),
    method: 'POST',
  });
}

export function copyPublishedApprovalDesignDraft(input: {
  definitionKey: string;
  formPackageVersion: number;
  name: string;
  sourceDefinitionVersion: number;
  targetDefinitionVersion: number;
}) {
  return request<ApprovalDesignDraft>('/approval/process-design-drafts/from-published', {
    body: JSON.stringify(input),
    headers: writeHeaders('approval-design-copy'),
    method: 'POST',
  });
}

export function updateApprovalDesignDraft(
  draftId: string,
  input: UpdateApprovalDesignDraftInput,
) {
  return request<ApprovalDesignDraft>(
    `/approval/process-design-drafts/${encodeURIComponent(draftId)}`,
    {
      body: JSON.stringify(input),
      headers: writeHeaders('approval-design-save'),
      method: 'PUT',
    },
  );
}

export function validateApprovalDesignDraft(draftId: string, expectedRevision: number) {
  return request<ApprovalDesignValidationResult>(
    `/approval/process-design-drafts/${encodeURIComponent(draftId)}/validate`,
    {
      body: JSON.stringify({ expectedRevision }),
      headers: writeHeaders('approval-design-validate'),
      method: 'POST',
    },
  );
}

export function simulateApprovalDesignDraft(
  draftId: string,
  expectedRevision: number,
  input: ApprovalSimulationInput,
) {
  return request<ApprovalSimulationResponse>(
    `/approval/process-design-drafts/${encodeURIComponent(draftId)}/simulate`,
    {
      body: JSON.stringify({
        expectedRevision,
        identityInputs: input.identityInputs ?? {},
        scenario: {
          decisions: input.decisions,
          formValues: input.formValues,
          maxTransitions: input.maxTransitions ?? 200,
        },
      }),
      method: 'POST',
    },
  );
}

export function archiveApprovalDesignDraft(draftId: string, expectedRevision: number) {
  return request<ApprovalDesignDraft>(
    `/approval/process-design-drafts/${encodeURIComponent(draftId)}/archive`,
    {
      body: JSON.stringify({ expectedRevision }),
      headers: writeHeaders('approval-design-archive'),
      method: 'POST',
    },
  );
}

export function publishApprovalDesignDraft(
  draftId: string,
  expectedRevision: number,
  definitionVersion: number,
  releaseVersion: number,
) {
  return request<ApprovalPublishResult>(
    `/approval/process-design-drafts/${encodeURIComponent(draftId)}/publish`,
    {
      body: JSON.stringify({ definitionVersion, expectedRevision, releaseVersion }),
      headers: writeHeaders('approval-release-publish'),
      method: 'POST',
    },
  );
}
