export interface ApprovalRuntimeConfig {
  apiBaseUrl: string;
  connector: string;
  localIdentityHeaders: boolean;
  operatorId: string;
  tenantId: string;
}

function normalizeBaseUrl(value: string) {
  const normalized = value.trim();
  return normalized.endsWith('/') ? normalized.slice(0, -1) : normalized;
}

function requireValue(value: string | undefined, name: string) {
  const normalized = value?.trim();
  if (!normalized) {
    throw new Error(`${name} 未配置`);
  }
  return normalized;
}

function parseBoolean(value: string | undefined, name: string) {
  const normalized = value?.trim().toLowerCase();
  if (!normalized || normalized === 'false') return false;
  if (normalized === 'true') return true;
  throw new Error(`${name} 仅允许 true 或 false`);
}

function isPrivateIpv4(hostname: string) {
  const octets = hostname.split('.').map(value => Number.parseInt(value, 10));
  if (octets.length !== 4 || octets.some(value => !Number.isInteger(value))) return false;
  if (octets.some(value => value < 0 || value > 255)) return false;
  return octets[0] === 10
    || octets[0] === 127
    || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31)
    || (octets[0] === 192 && octets[1] === 168);
}

function isLocalDemoApiBaseUrl(value: string) {
  if (value.startsWith('/')) return true;
  try {
    const url = new URL(value);
    const hostname = url.hostname.toLowerCase();
    const localHost = hostname === 'localhost'
      || hostname === '::1'
      || isPrivateIpv4(hostname);
    return url.protocol === 'http:'
      && localHost
      && !url.username
      && !url.password;
  } catch {
    return false;
  }
}

function localIdentityHeaders(apiBaseUrl: string) {
  const enabled = parseBoolean(
    import.meta.env.VITE_APPROVAL_LOCAL_IDENTITY_HEADERS,
    'VITE_APPROVAL_LOCAL_IDENTITY_HEADERS',
  );
  if (!enabled) return false;
  if (!import.meta.env.DEV) {
    throw new Error('本地审批身份头只能用于 Vite development 模式');
  }
  if (!isLocalDemoApiBaseUrl(apiBaseUrl)) {
    throw new Error('本地审批身份头只允许访问同源或私有网络 Demo 后端');
  }
  return true;
}

/**
 * Keeps approval pages independent from the host authentication implementation.
 * Local identity headers are an explicit development-only bridge to the backend
 * local profile; production continues to require a trusted host principal.
 */
export function getApprovalRuntimeConfig(): ApprovalRuntimeConfig {
  const apiBaseUrl = normalizeBaseUrl(
    import.meta.env.VITE_APPROVAL_API_URL
      || import.meta.env.VITE_GLOB_API_URL
      || '/api',
  );

  return {
    apiBaseUrl,
    connector: import.meta.env.VITE_APPROVAL_CONNECTOR_KEY?.trim() || 'generic-rest',
    localIdentityHeaders: localIdentityHeaders(apiBaseUrl),
    operatorId: requireValue(
      import.meta.env.VITE_APPROVAL_OPERATOR_ID,
      'VITE_APPROVAL_OPERATOR_ID',
    ),
    tenantId: requireValue(
      import.meta.env.VITE_APPROVAL_TENANT_ID,
      'VITE_APPROVAL_TENANT_ID',
    ),
  };
}
