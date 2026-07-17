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
  tenantId?: string
}

/**
 * Reads deployment data without binding pages to RuoYi, DingTalk or Feishu SDKs.
 * Platform-specific bootstrap code may replace these values before the app starts.
 */
export function getApprovalRuntimeConfig(): ApprovalRuntimeConfig {
  const apiBaseUrl = import.meta.env.VITE_APPROVAL_API_URL || '/api'
  const connector = (import.meta.env.VITE_APPROVAL_CONNECTOR || 'standalone') as ApprovalConnectorType
  const tenantId = import.meta.env.VITE_APPROVAL_TENANT_ID || undefined

  return {
    apiBaseUrl,
    connector,
    tenantId,
  }
}
