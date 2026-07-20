import type { IdentityReference } from '@/api/approval/identities'

import { getApprovalRuntimeConfig } from '@/platform/approval/runtime'

export type DelegationScope = 'ALL' | 'DEFINITION'
export type DelegationStatus = 'ACTIVE' | 'REVOKED'
export type AssignmentStatus = 'ACTIVE' | 'CANCELED' | 'COMPLETED' | 'SUPERSEDED'

export interface DelegationRule {
  createdAt: string
  createdBy: string
  definitionKey?: string
  delegateId: string
  principalId: string
  reason: string
  revokeReason?: string
  revokedAt?: string
  revokedBy?: string
  ruleId: string
  scope: DelegationScope
  status: DelegationStatus
  tenantId: string
  validFrom: string
  validUntil: string
  version: number
}

export interface DelegatedTaskAssignment {
  assignedAt: string
  assignmentId: string
  canceledAt?: string
  completedAt?: string
  completedBy?: string
  definitionKey: string
  delegateAssigneeId: string
  delegationRuleId: string
  delegationScope: DelegationScope
  engineInstanceId: string
  engineTaskId: string
  principalAssigneeId: string
  status: AssignmentStatus
  supersededAssigneeId?: string
  supersededAt?: string
  taskDefinitionKey: string
  tenantId: string
  version: number
}

export interface CreateDelegationPayload {
  connectorKey: string
  definitionKey?: string
  delegateIdentity: IdentityReference
  reason: string
  scope: DelegationScope
  validFrom: string
  validUntil: string
}

interface ApprovalRequestOptions {
  data?: unknown
  header?: Record<string, string>
  method?: 'GET' | 'POST'
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

function approvalRequest<T>(path: string, options: ApprovalRequestOptions = {}) {
  const runtime = getApprovalRuntimeConfig()
  const header: Record<string, string> = {
    Accept: 'application/json',
    'X-Operator-Id': runtime.operatorId,
    'X-Tenant-Id': runtime.tenantId,
    ...options.header,
  }
  if (options.data !== undefined && !header['Content-Type']) {
    header['Content-Type'] = 'application/json'
  }
  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: joinUrl(runtime.apiBaseUrl, path),
      method: options.method || 'GET',
      data: options.data,
      header,
      success: (response) => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data as T)
          return
        }
        reject(new Error(errorMessage(response.data, response.statusCode)))
      },
      fail: error => reject(new Error(error.errMsg || '网络请求失败')),
    })
  })
}

function commandHeaders(prefix: string) {
  const requestId = operationId(`${prefix}-request`)
  return {
    'Idempotency-Key': operationId(prefix),
    'X-Request-Id': requestId,
    'X-Trace-Id': requestId,
  }
}

export function findDelegationRules(includeRevoked = false) {
  return approvalRequest<DelegationRule[]>(
    `/approval/delegations?includeRevoked=${includeRevoked}`,
  )
}

export function createDelegationRule(payload: CreateDelegationPayload) {
  return approvalRequest<DelegationRule>('/approval/delegations', {
    data: payload,
    header: commandHeaders('mobile-delegation-create'),
    method: 'POST',
  })
}

export function revokeDelegationRule(ruleId: string, reason: string) {
  return approvalRequest<DelegationRule>(
    `/approval/delegations/${encodeURIComponent(ruleId)}/revoke`,
    {
      data: { reason: reason.trim() },
      header: commandHeaders('mobile-delegation-revoke'),
      method: 'POST',
    },
  )
}

export function findTaskDelegation(taskId: string) {
  return approvalRequest<DelegatedTaskAssignment | undefined>(
    `/approval/tasks/${encodeURIComponent(taskId)}/delegation`,
  )
}
