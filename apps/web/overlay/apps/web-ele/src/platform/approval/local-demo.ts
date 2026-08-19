export interface ApprovalLocalDemoUser {
  displayName: string;
  operatorId: string;
  stage: string;
}

export const approvalLocalDemoUsers: readonly ApprovalLocalDemoUser[] = [
  {
    displayName: 'Demo Employee',
    operatorId: 'demo-employee',
    stage: '发起人',
  },
  {
    displayName: 'Demo Manager',
    operatorId: 'demo-manager',
    stage: '部门负责人审批',
  },
  {
    displayName: 'Demo Finance Reviewer',
    operatorId: 'demo-finance-reviewer',
    stage: '财务审核',
  },
  {
    displayName: 'Demo Finance Approver A',
    operatorId: 'demo-finance-approver-a',
    stage: '财务会签 A',
  },
  {
    displayName: 'Demo Finance Approver B',
    operatorId: 'demo-finance-approver-b',
    stage: '财务会签 B',
  },
  {
    displayName: 'Demo Administrator',
    operatorId: 'demo-admin',
    stage: '管理员核验',
  },
];

export const approvalLocalDemoTenantId = 'demo-purchase-payment';

const operatorQueryParameter = 'demoOperator';

function selectedBrowserOperatorId() {
  if (typeof window === 'undefined') {
    return undefined;
  }
  const direct = new URLSearchParams(window.location.search)
    .get(operatorQueryParameter)
    ?.trim();
  if (direct) {
    return direct;
  }
  const queryIndex = window.location.hash.indexOf('?');
  if (queryIndex < 0) {
    return undefined;
  }
  return new URLSearchParams(window.location.hash.slice(queryIndex + 1))
    .get(operatorQueryParameter)
    ?.trim();
}

function requireKnownOperator(operatorId: string) {
  if (!approvalLocalDemoUsers.some(user => user.operatorId === operatorId)) {
    throw new Error(`Unknown local demo operator: ${operatorId}`);
  }
  return operatorId;
}

export function approvalLocalDemoEnabled() {
  return import.meta.env.DEV
    && import.meta.env.VITE_APPROVAL_LOCAL_DEMO === 'true';
}

export function requireApprovalLocalDemoTenant(tenantId: string) {
  if (tenantId !== approvalLocalDemoTenantId) {
    throw new Error(
      `Local demo tenant must be ${approvalLocalDemoTenantId}, received ${tenantId}`,
    );
  }
  return tenantId;
}

export function resolveApprovalLocalDemoOperatorId(configuredOperatorId: string) {
  return requireKnownOperator(
    selectedBrowserOperatorId() || configuredOperatorId,
  );
}
