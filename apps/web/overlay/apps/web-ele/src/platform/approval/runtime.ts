import {
  approvalLocalDemoEnabled,
  requireApprovalLocalDemoTenant,
  resolveApprovalLocalDemoOperatorId,
} from './local-demo';

export interface ApprovalRuntimeConfig {
  apiBaseUrl: string;
  connector: string;
  localDemo: boolean;
  operatorId: string;
  tenantId: string;
}

function normalizeBaseUrl(value: string) {
  const normalized = value.trim();
  return normalized.endsWith('/') ? normalized.slice(0, -1) : normalized;
}

function requireValue(value: string | undefined, name: string) {
  const normalized = value?.trim();
  if (!normalized) {
    throw new Error(`${name} 未配置`);
  }
  return normalized;
}

/**
 * Keeps approval pages independent from the host authentication implementation.
 * The explicit local demo adapter supplies deterministic request headers only
 * during Vite development. Production remains principal-authenticated.
 */
export function getApprovalRuntimeConfig(): ApprovalRuntimeConfig {
  const localDemo = approvalLocalDemoEnabled();
  const apiBaseUrl = normalizeBaseUrl(
    import.meta.env.VITE_APPROVAL_API_URL
      || import.meta.env.VITE_GLOB_API_URL
      || '/api',
  );
  const configuredTenantId = requireValue(
    import.meta.env.VITE_APPROVAL_TENANT_ID,
    'VITE_APPROVAL_TENANT_ID',
  );
  const configuredOperatorId = requireValue(
    import.meta.env.VITE_APPROVAL_OPERATOR_ID,
    'VITE_APPROVAL_OPERATOR_ID',
  );
  const tenantId = localDemo
    ? requireApprovalLocalDemoTenant(configuredTenantId)
    : configuredTenantId;
  const operatorId = localDemo
    ? resolveApprovalLocalDemoOperatorId(configuredOperatorId)
    : configuredOperatorId;

  return {
    apiBaseUrl,
    connector: import.meta.env.VITE_APPROVAL_CONNECTOR_KEY?.trim()
      || (localDemo ? 'demo-directory' : 'generic-rest'),
    localDemo,
    operatorId,
    tenantId,
  };
}
