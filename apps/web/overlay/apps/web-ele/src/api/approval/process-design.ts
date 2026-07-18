export type ApprovalMode = 'ALL' | 'ANY' | 'SINGLE';
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

export interface ApprovalDslDraft {
  definitionKey: string;
  formPackage: FormPackageReference;
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

export type ApprovalNode =
  | ApprovalStep
  | ConditionStep
  | EndNode
  | HandleStep
  | StartNode;

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
  mode: ApprovalMode;
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

export interface EndNode extends BaseNode {
  kind: 'END';
}

export interface AssigneeRule {
  emptyPolicy: 'FAIL';
  resolver: AssigneeResolver;
  variable: string;
}

export interface ConditionRoute {
  condition: ComparisonCondition;
  next: string;
}

export interface ComparisonCondition {
  field: string;
  operator: ComparisonOperator;
  value: number;
}

export interface SimulationStep {
  kind: ApprovalNode['kind'];
  nodeId: string;
  nodeName: string;
  outcome: string;
}

export interface SimulationResult {
  issues: string[];
  status: 'BLOCKED' | 'COMPLETED' | 'REJECTED' | 'TRANSITION_LIMIT_REACHED';
  steps: SimulationStep[];
}

export function createBlankApprovalDraft(): ApprovalDslDraft {
  return {
    definitionKey: 'expense-approval',
    formPackage: {
      formKey: 'expense-approval',
      packageHash: '0'.repeat(64),
      packageVersion: 1,
    },
    name: '费用审批',
    nodes: [
      { id: 'start', kind: 'START', name: '发起', next: 'manager' },
      {
        assignee: {
          emptyPolicy: 'FAIL',
          resolver: 'INITIATOR_MANAGER',
          variable: 'managerId',
        },
        id: 'manager',
        kind: 'APPROVAL',
        mode: 'SINGLE',
        name: '直属主管审批',
        next: 'end',
      },
      { id: 'end', kind: 'END', name: '结束' },
    ],
    schemaVersion: '1.0',
    startNodeId: 'start',
    version: 1,
  };
}
