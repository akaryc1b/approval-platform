import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'

export interface MobileApprovalApiErrorPayload {
  code?: string
  error?: string
  message?: string
  occurredAt?: string
  requestId?: string
  retryable?: boolean
}

export interface MobileApprovalRequestOptions {
  allowNotFound?: boolean
  data?: unknown
  header?: Record<string, string>
  method?: 'DELETE' | 'GET' | 'POST' | 'PUT'
}

export class MobileApprovalApiError extends Error {
  readonly code: string
  readonly occurredAt?: string
  readonly requestId?: string
  readonly retryable: boolean
  readonly status: number

  constructor(status: number, payload: MobileApprovalApiErrorPayload, responseRequestId?: string) {
    const code = payload.code || payload.error || 'APPROVAL_REQUEST_FAILED'
    const requestId = payload.requestId || responseRequestId
    const message = payload.message || payload.error || `请求失败（${status}）`
    const details = [message, `[${code}]`, requestId ? `requestId=${requestId}` : undefined]
      .filter(Boolean)
      .join(' · ')
    super(details)
    this.name = 'MobileApprovalApiError'
    this.code = code
    this.occurredAt = payload.occurredAt
    this.requestId = requestId
    this.retryable = payload.retryable === true
    this.status = status
  }
}

export function mobileApprovalOperationId(prefix: string) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(16).slice(2)}`
}

export function mobileApprovalMutationHeaders(prefix: string) {
  const requestId = mobileApprovalOperationId(`${prefix}-request`)
  return {
    'Idempotency-Key': mobileApprovalOperationId(prefix),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  }
}

export function mobileApprovalUrl(path: string) {
  const { apiBaseUrl } = getApprovalRuntimeConfig()
  const normalized = apiBaseUrl.endsWith('/') ? apiBaseUrl.slice(0, -1) : apiBaseUrl
  return `${normalized}${path.startsWith('/') ? path : `/${path}`}`
}

export function mobileApprovalHeaders(
  extra: Record<string, string> = {},
): Record<string, string> {
  const runtime = getApprovalRuntimeConfig()
  return {
    Accept: 'application/json',
    'X-Operator-Id': runtime.operatorId,
    'X-Tenant-Id': runtime.tenantId,
    ...extra,
  }
}

function responseRequestId(header: unknown) {
  if (!header || typeof header !== 'object') return undefined
  for (const [name, value] of Object.entries(header)) {
    if (name.toLowerCase() === 'x-request-id' && typeof value === 'string') return value
  }
  return undefined
}

function errorPayload(payload: unknown): MobileApprovalApiErrorPayload {
  if (payload && typeof payload === 'object') return payload as MobileApprovalApiErrorPayload
  if (typeof payload === 'string' && payload.trim()) return { message: payload }
  return {}
}

export function mobileApprovalError(
  payload: unknown,
  status: number,
  header?: unknown,
) {
  return new MobileApprovalApiError(
    status,
    errorPayload(payload),
    responseRequestId(header),
  )
}

export function mobileApprovalRequest<T>(
  path: string,
  options: MobileApprovalRequestOptions = {},
) {
  const suppliedHeaders = options.header || {}
  const requestId = suppliedHeaders['X-Request-Id']
    || mobileApprovalOperationId('mobile-approval-request')
  const header = mobileApprovalHeaders({
    'X-Request-Id': requestId,
    'X-Trace-Id': suppliedHeaders['X-Trace-Id'] || requestId,
    ...suppliedHeaders,
  })
  if (options.data !== undefined && !header['Content-Type']) {
    header['Content-Type'] = 'application/json'
  }
  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: mobileApprovalUrl(path),
      method: options.method || 'GET',
      data: options.data,
      header,
      success: (response) => {
        if (options.allowNotFound && response.statusCode === 404) {
          resolve(undefined as T)
          return
        }
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data as T)
          return
        }
        reject(mobileApprovalError(response.data, response.statusCode, response.header))
      },
      fail: error => reject(new MobileApprovalApiError(0, {
        code: 'APPROVAL_NETWORK_ERROR',
        message: error.errMsg || '网络请求失败',
        requestId,
        retryable: true,
      })),
    })
  })
}
