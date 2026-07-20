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

interface ApiErrorPayload {
  code?: string;
  error?: string;
  message?: string;
}

function operationId(prefix: string) {
  const randomId = globalThis.crypto?.randomUUID?.() ??
    `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}-${randomId}`;
}

function joinUrl(baseUrl: string, path: string) {
  return `${baseUrl}${path.startsWith('/') ? path : `/${path}`}`;
}

async function parseError(response: Response) {
  let payload: ApiErrorPayload | undefined;
  try {
    payload = (await response.json()) as ApiErrorPayload;
  } catch {
    payload = undefined;
  }
  return payload?.message || payload?.error || payload?.code ||
    `请求失败（${response.status}）`;
}

export async function findApprovalIdentityCandidates(keyword = '', limit = 20) {
  const runtime = getApprovalRuntimeConfig();
  const requestId = operationId('web-identity-candidates');
  const query = new URLSearchParams({
    connectorKey: runtime.connector,
    limit: String(limit),
  });
  if (keyword.trim()) query.set('keyword', keyword.trim());
  const response = await fetch(
    joinUrl(runtime.apiBaseUrl, `/approval/identities/candidates?${query.toString()}`),
    {
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        'X-Operator-Id': runtime.operatorId,
        'X-Request-Id': requestId,
        'X-Tenant-Id': runtime.tenantId,
        'X-Trace-Id': requestId,
      },
    },
  );
  if (!response.ok) throw new Error(await parseError(response));
  return (await response.json()) as ApprovalIdentityCandidate[];
}
