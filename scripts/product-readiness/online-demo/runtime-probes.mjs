import { digest } from './images-contract.mjs';

export const deniedStaticPaths = Object.freeze([
  '/api', '/api/approval/tasks/pending', '/approval-api/api/approval/tasks/pending',
  '/actuator/health', '/payment-sandbox/v1/events', '/.env', '/.git/config',
  '/resolved-pnpm-lock.yaml', '/app.js.map', '/__missing-image-smoke__.js',
]);

export function verifyStaticInventory(value, source, component) {
  if (value?.schemaVersion !== 1 || value.kind !== 'ONLINE_DEMO_STATIC_ARTIFACT_INVENTORY'
      || value.component !== component || value.commitSha !== source.commitSha
      || value.treeSha !== source.treeSha || value.epoch !== source.epoch
      || !/^[0-9a-f]{64}$/u.test(value.lockSha256 || '')
      || !Array.isArray(value.files) || value.files.length < 1 || value.files.length > 10_000) {
    throw new Error('static inventory source/shape mismatch');
  }
  let total = 0;
  const paths = new Set();
  for (const file of value.files) {
    if (typeof file.path !== 'string' || file.path.length > 1024 || file.path.startsWith('/')
        || /[\\\x00-\x1f\x7f?#]/u.test(file.path)
        || file.path.split('/').some(part => !part || part.startsWith('.') || part === '..')
        || paths.has(file.path) || !Number.isSafeInteger(file.size)
        || file.size < 1 || file.size > 32 * 1024 * 1024
        || !/^[0-9a-f]{64}$/u.test(file.sha256 || '')) throw new Error('invalid static inventory entry');
    paths.add(file.path);
    total += file.size;
  }
  if (total !== value.totalBytes || total > 256 * 1024 * 1024
      || digest(JSON.stringify(value.files)) !== value.inventorySha256
      || !paths.has('index.html')) throw new Error('static inventory digest/size mismatch');
  const selected = ['index.html', ...['js', 'css'].map(extension => {
    const file = value.files.find(item => item.path.endsWith(`.${extension}`));
    if (!file) throw new Error(`static inventory has no ${extension} bundle`);
    return file.path;
  })];
  return selected.map(path => value.files.find(file => file.path === path));
}

export function verifyStaticResponse(result, expectedStatus, file) {
  if (result?.status !== expectedStatus || result.headers?.['x-content-type-options'] !== 'nosniff'
      || result.headers?.['x-frame-options'] !== 'DENY'
      || result.headers?.['cache-control'] !== 'no-store'
      || !result.headers?.['x-robots-tag']?.includes('noindex')) {
    throw new Error('static HTTP status/security headers mismatch');
  }
  if (file && (result.size !== file.size || result.sha256 !== file.sha256)) {
    throw new Error('served static bytes differ from the image inventory');
  }
  if (file?.path.endsWith('.js') && !/javascript/u.test(result.headers['content-type'] || '')) {
    throw new Error('JavaScript request was not served as JavaScript');
  }
  if (file?.path.endsWith('.css') && !/^text\/css/u.test(result.headers['content-type'] || '')) {
    throw new Error('CSS request was not served as CSS');
  }
}

// Executed inside the pinned Node probe container, not in the application images.
// Return hashes and selected headers, never response bodies or cookies.
export async function probeHttp(request) {
  const { createHash } = await import('node:crypto');
  const response = await fetch(request.url, {
    method: request.method || 'GET', redirect: 'manual',
    headers: { 'Accept-Encoding': 'identity' }, signal: AbortSignal.timeout(4_000),
  });
  const reader = response.body?.getReader();
  const hash = createHash('sha256');
  let size = 0;
  let health = '';
  try {
    while (reader) {
      const part = await reader.read();
      if (part.done) break;
      size += part.value.length;
      if (size > 32 * 1024 * 1024) throw new Error('HTTP response exceeds limit');
      hash.update(part.value);
      if (request.health && size <= 16_384) health += Buffer.from(part.value).toString('utf8');
    }
  } finally {
    await reader?.cancel();
  }
  const headers = Object.fromEntries(['content-type', 'x-content-type-options',
    'x-frame-options', 'cache-control', 'x-robots-tag'].map(key => [key, response.headers.get(key)]));
  return { status: response.status, size, sha256: hash.digest('hex'), headers,
    ...(request.health ? { healthy: response.status === 200 && JSON.parse(health).status === 'UP' } : {}) };
}
export const probeProgram = `(${probeHttp.toString()})(JSON.parse(process.argv[1]))\n`
  + '.then(value => console.log(JSON.stringify(value)))\n'
  + '.catch(() => { console.error("image HTTP probe failed"); process.exitCode = 1; });';
