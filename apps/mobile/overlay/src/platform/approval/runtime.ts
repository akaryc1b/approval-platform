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
  localIdentityHeaders: boolean
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

function parseBoolean(value: string | undefined, name: string) {
  const normalized = value?.trim().toLowerCase()
  if (!normalized || normalized === 'false') return false
  if (normalized === 'true') return true
  throw new Error(`${name} must be true or false`)
}

function defaultConnectorKey(connector: ApprovalConnectorType) {
  if (connector === 'standalone' || connector === 'generic') return 'generic-rest'
  return connector
}

function isPrivateIpv4(hostname: string) {
  const octets = hostname.split('.').map(value => Number.parseInt(value, 10))
  if (octets.length !== 4 || octets.some(value => !Number.isInteger(value))) return false
  if (octets.some(value => value < 0 || value > 255)) return false
  return octets[0] === 10
    || octets[0] === 127
    || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31)
    || (octets[0] === 192 && octets[1] === 168)
}

function isLocalDemoApiBaseUrl(value: string) {
  if (value.startsWith('/')) return true
  if (value.includes('?') || value.includes('#') || value.includes('@')) return false
  const match = value.match(
    /^http:\/\/(\[[0-9a-f:]+\]|[^/:?#@]+)(?::([0-9]{1,5}))?(?:\/[^?#]*)?$/i,
  )
  if (!match) return false
  const hostname = match[1].replace(/^\[|\]$/g, '').toLowerCase()
  const port = match[2] ? Number.parseInt(match[2], 10) : undefined
  if (port !== undefined && (port < 1 || port > 65_535)) return false
  return hostname === 'localhost'
    || hostname === '::1'
    || isPrivateIpv4(hostname)
}

function localIdentityHeaders(apiBaseUrl: string) {
  const enabled = parseBoolean(
    import.meta.env.VITE_APPROVAL_LOCAL_IDENTITY_HEADERS,
    'VITE_APPROVAL_LOCAL_IDENTITY_HEADERS',
  )
  if (!enabled) return false
  if (!import.meta.env.DEV) {
    throw new Error('local approval identity headers require Vite development mode')
  }
  if (!isLocalDemoApiBaseUrl(apiBaseUrl)) {
    throw new Error('local approval identity headers require a same-origin or private demo API')
  }
  return true
}

/**
 * Reads deployment data without binding pages to RuoYi, DingTalk or Feishu SDKs.
 * The local-header bridge is explicit and development-only; production still
 * requires a trusted authenticated identity supplied by the host platform.
 */
export function getApprovalRuntimeConfig(): ApprovalRuntimeConfig {
  const apiBaseUrl = requiredValue(
    import.meta.env.VITE_APPROVAL_API_URL || '/api',
    'VITE_APPROVAL_API_URL',
  )
  const connector = (
    import.meta.env.VITE_APPROVAL_CONNECTOR || 'standalone'
  ) as ApprovalConnectorType
  const connectorKey = import.meta.env.VITE_APPROVAL_CONNECTOR_KEY?.trim()
    || defaultConnectorKey(connector)
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
    connectorKey,
    localIdentityHeaders: localIdentityHeaders(apiBaseUrl),
    operatorId,
    tenantId,
  }
}
