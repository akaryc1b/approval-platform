import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'

export interface IdentityReference {
  objectType: string
  source: string
  value: string
}

export interface ApprovalIdentityCandidate {
  active: boolean
  departmentIds: string[]
  displayName: string
  email?: string
  mobile?: string
  positionCodes: string[]
  reference: IdentityReference
  roleCodes: string[]
  userId: string
  username: string
}

interface ApiErrorPayload {
  code?: string
  error?: string
  message?: string
}

function joinUrl(baseUrl: string, path: string) {
  const normalizedBase = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl
  return `${normalizedBase}${path.startsWith('/') ? path : `/${path}`}`
}

function operationId(prefix: string) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(16).slice(2)}`
}

function errorMessage(payload: unknown, statusCode: number) {
  if (payload && typeof payload === 'object') {
    const error = payload as ApiErrorPayload
    return error.message || error.error || error.code || `请求失败（${statusCode}）`
  }
  if (typeof payload === 'string' && payload.trim()) return payload
  return `请求失败（${statusCode}）`
}

export function findApprovalIdentityCandidates(keyword = '', limit = 30) {
  const runtime = getApprovalRuntimeConfig()
  const requestId = operationId('mobile-identity-candidates')
  const query = [
    `connectorKey=${encodeURIComponent(runtime.connectorKey)}`,
    `limit=${encodeURIComponent(String(limit))}`,
  ]
  if (keyword.trim()) query.push(`keyword=${encodeURIComponent(keyword.trim())}`)
  return new Promise<ApprovalIdentityCandidate[]>((resolve, reject) => {
    uni.request({
      url: joinUrl(runtime.apiBaseUrl, `/approval/identities/candidates?${query.join('&')}`),
      method: 'GET',
      header: {
        Accept: 'application/json',
        'X-Operator-Id': runtime.operatorId,
        'X-Request-Id': requestId,
        'X-Tenant-Id': runtime.tenantId,
        'X-Trace-Id': requestId,
      },
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data as ApprovalIdentityCandidate[])
          return
        }
        reject(new Error(errorMessage(response.data, response.statusCode)))
      },
      fail: error => reject(new Error(error.errMsg || '网络请求失败')),
    })
  })
}
