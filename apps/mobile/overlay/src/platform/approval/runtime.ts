import {
  approvalLocalDemoEnabled,
  requireApprovalLocalDemoTenant,
  resolveApprovalLocalDemoOperatorId,
} from './local-demo'

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
  connectorKey: string
  localDemo: boolean
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

function normalizeBaseUrl(value: string) {
  const normalized = value.trim()
  return normalized.endsWith('/') ? normalized.slice(0, -1) : normalized
}

function defaultConnectorKey(connector: ApprovalConnectorType) {
  if (connector === 'standalone' || connector === 'generic') return 'generic-rest'
  return connector
}

function currentUniPlatform() {
  try {
    return (uni.getSystemInfoSync() as { uniPlatform?: string }).uniPlatform || ''
  }
  catch {
    return ''
  }
}

function configuredApiBaseUrl() {
  const fallback = import.meta.env.VITE_APPROVAL_API_URL || '/api'
  const platform = currentUniPlatform()
  if (platform === 'web') {
    return import.meta.env.VITE_APPROVAL_H5_API_URL || fallback
  }
  if (platform === 'mp-weixin') {
    return import.meta.env.VITE_APPROVAL_WEIXIN_API_URL || fallback
  }
  return fallback
}

/**
 * Reads deployment data without binding pages to RuoYi, DingTalk or Feishu SDKs.
 * The explicit local demo adapter supplies deterministic request headers only
 * during Vite development. Production remains principal-authenticated.
 */
export function getApprovalRuntimeConfig(): ApprovalRuntimeConfig {
  const localDemo = approvalLocalDemoEnabled()
  const apiBaseUrl = normalizeBaseUrl(
    requiredValue(configuredApiBaseUrl(), 'VITE_APPROVAL_API_URL'),
  )
  const connector = (
    import.meta.env.VITE_APPROVAL_CONNECTOR || 'standalone'
  ) as ApprovalConnectorType
  const configuredTenantId = requiredValue(
    import.meta.env.VITE_APPROVAL_TENANT_ID,
    'VITE_APPROVAL_TENANT_ID',
  )
  const configuredOperatorId = requiredValue(
    import.meta.env.VITE_APPROVAL_OPERATOR_ID,
    'VITE_APPROVAL_OPERATOR_ID',
  )
  const tenantId = localDemo
    ? requireApprovalLocalDemoTenant(configuredTenantId)
    : configuredTenantId
  const operatorId = localDemo
    ? resolveApprovalLocalDemoOperatorId(configuredOperatorId)
    : configuredOperatorId

  return {
    apiBaseUrl,
    connector,
    connectorKey: import.meta.env.VITE_APPROVAL_CONNECTOR_KEY?.trim()
      || (localDemo ? 'demo-directory' : defaultConnectorKey(connector)),
    localDemo,
    operatorId,
    tenantId,
  }
}
