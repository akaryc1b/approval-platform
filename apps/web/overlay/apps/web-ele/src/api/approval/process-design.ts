import {
  approvalCommandHeaders,
  approvalRequest,
} from '#/api/approval/transport';

export { ApprovalApiError as ApprovalDesignApiError } from '#/api/approval/transport';

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
export type ApprovalPreflightScope = 'DEPLOYMENT' | 'PUBLICATION';
export type ApprovalPreflightSeverity = 'ERROR' | 'INFO' | 'WARNING';

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

export interface ApprovalPreflightIssue {
  code: string;
  message: string;
  severity: ApprovalPreflightSeverity;
  subject: string;
}

export interface ApprovalPreflightReport {
  compiler: {
    artifactBytes: number;
    attempted: boolean;
    compilerVersion?: string;
    deterministic: boolean;
    resourceName?: string;
    successful: boolean;
  };
  deployable: boolean;
  deploymentCompatibility: {
    bpmnWellFormed: boolean;
    existingDeploymentStatus?: string;
    processDefinitionKey?: string;
    processKeyMatches: boolean;
    semanticReplay: boolean;
    supported: boolean;
    target: string;
  };
  deploymentTarget: string;
  draftId: string;
  draftRevision: number;
  errors: ApprovalPreflightIssue[];
  generatedHashes: {
    bpmnHash?: string;
    compiledArtifactHash?: string;
    definitionHash?: string;
    deploymentMetadataHash?: string;
    formPackageHash?: string;
    releasePackageHash?: string;
  };
  infos: ApprovalPreflightIssue[];
  preflightHash: string;
  publishable: boolean;
  scope: ApprovalPreflightScope;
  simulation: {
    executed: boolean;
    issueCodes: string[];
    requested: boolean;
    status?: string;
    stepCount: number;
    terminalNodeId?: string;
  };
  targetDefinitionVersion: number;
  targetReleaseVersion: number;
  tenantId: string;
  warnings: ApprovalPreflightIssue[];
}

export interface ApprovalPreflightScenario {
  decisions: Record<string, 'APPROVE' | 'REJECT'>;
  formValues: Record<string, unknown>;
  maxTransitions: number;
}

export interface ApprovalPublicationPreflightInput {
  definitionKey: string;
  deploymentTarget: string;
  draftId: string;
  expectedRevision: number;
  scenario?: ApprovalPreflightScenario;
  targetDefinitionVersion: number;
  targetReleaseVersion: number;
}

export interface PublishApprovalDesignDraftInput {
  acknowledgedWarningCodes: string[];
  definitionVersion: number;
  deploymentTarget: string;
  expectedRevision: number;
  preflightHash: string;
  preflightScenario?: ApprovalPreflightScenario;
  releaseVersion: number;
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

export type ApprovalFormValueDisclosure = 'FIELD_NAMES_ONLY' | 'FULL' | 'MASKED';
export type ApprovalBatchRunStatus =
  | 'BLOCKED'
  | 'ERROR'
  | 'EXPECTATION_FAILED'
  | 'PASSED'
  | 'TRANSITION_LIMIT_REACHED';

export interface ApprovalBatchScenarioInput {
  approvalDecisions: Record<string, 'APPROVE' | 'REJECT'>;
  expectedSkippedNodeIds: string[];
  expectedTerminalStatus?: SimulationResult['status'];
  expectedVisitedNodeIds: string[];
  formValues: Record<string, unknown>;
  identitySnapshots: Record<string, StableIdentitySnapshot[]>;
  maxTransitions: number;
  name: string;
  scenarioId: string;
}

export interface ApprovalCoverageMetric {
  covered: number;
  percentage: number;
  total: number;
}

export interface ApprovalBatchIdentityResolution {
  inputVariable: string;
  nodeId: string;
  resolvable: boolean;
  resolver: AssigneeResolver;
  snapshotHashes: string[];
  subjectIds: string[];
}

export interface ApprovalBatchScenarioResult {
  expectationFailures: string[];
  formFieldNames: string[];
  formValues: Record<string, unknown>;
  identityResolutions: ApprovalBatchIdentityResolution[];
  issueCodes: string[];
  name: string;
  pathSummary: string;
  runStatus: ApprovalBatchRunStatus;
  scenarioId: string;
  simulationStatus?: SimulationResult['status'];
  skippedNodeIds: string[];
  steps: SimulationStep[];
  terminalNodeId?: string;
  visitedNodeIds: string[];
}

export interface ApprovalBatchCoverageReport {
  approvalPassPaths: ApprovalCoverageMetric;
  approvalRejectPaths: ApprovalCoverageMetric;
  blockedObserved: boolean;
  blockedScenarioIds: string[];
  conditionRoutes: ApprovalCoverageMetric;
  criticalPathCoverage: ApprovalCoverageMetric;
  decisionCoverage: ApprovalCoverageMetric;
  defaultRoutes: ApprovalCoverageMetric;
  endNodes: ApprovalCoverageMetric;
  handleRevisionLoops: ApprovalCoverageMetric;
  nodes: ApprovalCoverageMetric;
  parallelBranches: ApprovalCoverageMetric;
  parallelJoins: ApprovalCoverageMetric;
  parallelSplits: ApprovalCoverageMetric;
  startNodes: ApprovalCoverageMetric;
  structuralCoverage: ApprovalCoverageMetric;
  transitionLimitObserved: boolean;
  transitionLimitScenarioIds: string[];
  uncoveredApprovalPassNodeIds: string[];
  uncoveredApprovalRejectNodeIds: string[];
  uncoveredConditionRouteIds: string[];
  uncoveredDefaultRouteIds: string[];
  uncoveredEndNodeIds: string[];
  uncoveredHandleNodeIds: string[];
  uncoveredNodeIds: string[];
  uncoveredParallelBranchIds: string[];
  uncoveredParallelJoinNodeIds: string[];
  uncoveredParallelSplitNodeIds: string[];
  uncoveredStartNodeIds: string[];
}

export interface ApprovalBatchUncoveredItem {
  category: string;
  stableId: string;
}

export interface ApprovalBatchSimulationReport {
  coverage: ApprovalBatchCoverageReport;
  definitionKey: string;
  definitionVersion: number;
  draftId: string;
  draftRevision: number;
  formPackageHash: string;
  formPackageVersion: number;
  formSchemaHash: string;
  formSchemaVersion: number;
  formValueDisclosure: ApprovalFormValueDisclosure;
  generatedAt: string;
  reportHash: string;
  scenarioCount: number;
  scenarioResults: ApprovalBatchScenarioResult[];
  schemaVersion: string;
  tenantId: string;
  uiSchemaHash: string;
  uiSchemaVersion: number;
  uncoveredItems: ApprovalBatchUncoveredItem[];
}

export interface ApprovalBatchSimulationInput {
  expectedRevision: number;
  formValueDisclosure?: ApprovalFormValueDisclosure;
  scenarios: ApprovalBatchScenarioInput[];
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
  return approvalRequest<ApprovalDesignDraftPage>(
    `/approval/process-design-drafts?${query.toString()}`,
  );
}

export function findApprovalDesignDraft(draftId: string) {
  return approvalRequest<ApprovalDesignDraft>(
    `/approval/process-design-drafts/${encodeURIComponent(draftId)}`,
  );
}

export function createApprovalDesignDraft(input: CreateApprovalDesignDraftInput) {
  return approvalRequest<ApprovalDesignDraft>('/approval/process-design-drafts', {
    body: JSON.stringify(input),
    headers: approvalCommandHeaders('approval-design-create'),
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
  return approvalRequest<ApprovalDesignDraft>('/approval/process-design-drafts/from-published', {
    body: JSON.stringify(input),
    headers: approvalCommandHeaders('approval-design-copy'),
    method: 'POST',
  });
}

export function updateApprovalDesignDraft(
  draftId: string,
  input: UpdateApprovalDesignDraftInput,
) {
  return approvalRequest<ApprovalDesignDraft>(
    `/approval/process-design-drafts/${encodeURIComponent(draftId)}`,
    {
      body: JSON.stringify(input),
      headers: approvalCommandHeaders('approval-design-save'),
      method: 'PUT',
    },
  );
}

export function validateApprovalDesignDraft(draftId: string, expectedRevision: number) {
  return approvalRequest<ApprovalDesignValidationResult>(
    `/approval/process-design-drafts/${encodeURIComponent(draftId)}/validate`,
    {
      body: JSON.stringify({ expectedRevision }),
      headers: approvalCommandHeaders('approval-design-validate'),
      method: 'POST',
    },
  );
}

export function simulateApprovalDesignDraft(
  draftId: string,
  expectedRevision: number,
  input: ApprovalSimulationInput,
) {
  return approvalRequest<ApprovalSimulationResponse>(
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

export function simulateApprovalDesignDraftBatch(
  draftId: string,
  input: ApprovalBatchSimulationInput,
) {
  return approvalRequest<ApprovalBatchSimulationReport>(
    `/approval/process-design-drafts/${encodeURIComponent(draftId)}/batch-simulate`,
    {
      body: JSON.stringify(input),
      method: 'POST',
    },
  );
}

export async function exportApprovalDesignDraftBatchReport(
  draftId: string,
  input: ApprovalBatchSimulationInput,
) {
  const report = await simulateApprovalDesignDraftBatch(draftId, input);
  return new Blob([JSON.stringify(report, null, 2)], {
    type: 'application/json;charset=utf-8',
  });
}

export function archiveApprovalDesignDraft(draftId: string, expectedRevision: number) {
  return approvalRequest<ApprovalDesignDraft>(
    `/approval/process-design-drafts/${encodeURIComponent(draftId)}/archive`,
    {
      body: JSON.stringify({ expectedRevision }),
      headers: approvalCommandHeaders('approval-design-archive'),
      method: 'POST',
    },
  );
}

export function preflightApprovalPublication(input: ApprovalPublicationPreflightInput) {
  return approvalRequest<ApprovalPreflightReport>('/approval/preflight/publication', {
    body: JSON.stringify(input),
    method: 'POST',
  });
}

export function publishApprovalDesignDraft(
  draftId: string,
  input: PublishApprovalDesignDraftInput,
) {
  return approvalRequest<ApprovalPublishResult>(
    `/approval/process-design-drafts/${encodeURIComponent(draftId)}/publish`,
    {
      body: JSON.stringify(input),
      headers: approvalCommandHeaders('approval-release-publish'),
      method: 'POST',
    },
  );
}
