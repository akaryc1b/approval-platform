export interface ApprovalRuntimeConfig {
  apiBaseUrl: string;
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
 * A later authentication adapter can replace these environment values without
 * changing the task-center API or views.
 */
export function getApprovalRuntimeConfig(): ApprovalRuntimeConfig {
  const apiBaseUrl = normalizeBaseUrl(
    import.meta.env.VITE_APPROVAL_API_URL ||
      import.meta.env.VITE_GLOB_API_URL ||
      '/api',
  );

  return {
    apiBaseUrl,
    operatorId: requireValue(
      import.meta.env.VITE_APPROVAL_OPERATOR_ID,
      'VITE_APPROVAL_OPERATOR_ID',
    ),
    tenantId: requireValue(
      import.meta.env.VITE_APPROVAL_TENANT_ID,
      'VITE_APPROVAL_TENANT_ID',
    ),
  };
}
