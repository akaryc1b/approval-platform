import {
  mobileApprovalOperationId,
  mobileApprovalRequest,
} from '@/api/approval/transport'
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

export function findApprovalIdentityCandidates(
  keyword = '',
  limit = 30,
  activeOnly = true,
) {
  const runtime = getApprovalRuntimeConfig()
  const requestId = mobileApprovalOperationId('mobile-identity-candidates')
  const query = [
    `activeOnly=${encodeURIComponent(String(activeOnly))}`,
    `connectorKey=${encodeURIComponent(runtime.connectorKey)}`,
    `limit=${encodeURIComponent(String(limit))}`,
  ]
  if (keyword.trim()) query.push(`keyword=${encodeURIComponent(keyword.trim())}`)
  return mobileApprovalRequest<ApprovalIdentityCandidate[]>(
    `/approval/identities/candidates?${query.join('&')}`,
    {
      header: {
        'X-Request-Id': requestId,
        'X-Trace-Id': requestId,
      },
    },
  )
}
