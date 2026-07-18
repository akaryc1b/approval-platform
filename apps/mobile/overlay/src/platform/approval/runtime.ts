export type ApprovalConnectorType =
  | 'standalone'
  | 'ruoyi5'
  | 'ruoyi6'
  | 'dingtalk'
  | 'feishu'
  | 'generic'

export interface ApprovalRuntimeConfig {
  apiBaseUrl: string
  connector: ApprovalConnectorType
  operatorId: string
  tenantId: string
}

function requiredValue(value: string | undefined, name: string) {
  const normalized = value?.trim()
  if (!normalized) {
    throw new Error(`${name} is required for approval API requests`)
  }
  return normalized
}

/**
 * Reads deployment data without binding pages to RuoYi, DingTalk or Feishu SDKs.
 * Platform-specific bootstrap code must replace tenant and operator values with
 * a trusted authenticated identity before production use.
 */
export function getApprovalRuntimeConfig(): ApprovalRuntimeConfig {
  const apiBaseUrl = requiredValue(
    import.meta.env.VITE_APPROVAL_API_URL || '/api',
    'VITE_APPROVAL_API_URL',
  )
  const connector = (
    import.meta.env.VITE_APPROVAL_CONNECTOR || 'standalone'
  ) as ApprovalConnectorType
  const tenantId = requiredValue(
    import.meta.env.VITE_APPROVAL_TENANT_ID,
    'VITE_APPROVAL_TENANT_ID',
  )
  const operatorId = requiredValue(
    import.meta.env.VITE_APPROVAL_OPERATOR_ID,
    'VITE_APPROVAL_OPERATOR_ID',
  )

  return {
    apiBaseUrl,
    connector,
    operatorId,
    tenantId,
  }
}
