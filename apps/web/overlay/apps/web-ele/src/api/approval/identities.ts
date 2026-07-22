import { approvalRequest } from '#/api/approval/transport';
import { getApprovalRuntimeConfig } from '#/platform/approval/runtime';

export interface IdentityReference {
  objectType: string;
  source: string;
  value: string;
}

export interface ApprovalIdentityCandidate {
  active: boolean;
  departmentIds: string[];
  displayName: string;
  email?: string;
  mobile?: string;
  positionCodes: string[];
  reference: IdentityReference;
  roleCodes: string[];
  userId: string;
  username: string;
}

export function findApprovalIdentityCandidates(
  keyword = '',
  limit = 20,
  activeOnly = true,
) {
  const runtime = getApprovalRuntimeConfig();
  const query = new URLSearchParams({
    activeOnly: String(activeOnly),
    connectorKey: runtime.connector,
    limit: String(limit),
  });
  if (keyword.trim()) query.set('keyword', keyword.trim());
  return approvalRequest<ApprovalIdentityCandidate[]>(
    `/approval/identities/candidates?${query.toString()}`,
  );
}
