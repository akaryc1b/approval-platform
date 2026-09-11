import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync } from 'node:fs';
import { extname, resolve } from 'node:path';

export const evaluationApplicationPaths = Object.freeze({
  pc: '/evaluation/pc/#/approval/workbench',
  h5: '/evaluation/h5/#/pages/task/list',
  purchase: '/evaluation/h5/#/pages/initiate/form?formKey=purchase-payment&version=1',
});
const registries = new WeakSet();
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const requireValue = value => { if (!value) throw new Error('EVALUATION_APPLICATION_ARTIFACT_REJECTED'); };
const types = new Map(Object.entries({ '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.gif': 'image/gif', '.webp': 'image/webp', '.avif': 'image/avif', '.ico': 'image/x-icon',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.otf': 'font/otf',
  '.eot': 'application/vnd.ms-fontobject', '.wasm': 'application/wasm', '.webmanifest': 'application/manifest+json',
  '.mp4': 'video/mp4', '.webm': 'video/webm', '.txt': 'text/plain; charset=utf-8',
  '.gz': 'application/gzip', '.br': 'application/octet-stream' }));
function regular(path, maximum) {
  const stat = lstatSync(path);
  requireValue(stat.isFile() && !stat.isSymbolicLink() && realpathSync(path) === path
    && stat.size > 0 && stat.size <= maximum);
  const bytes = readFileSync(path);
  requireValue(bytes.length === stat.size);
  return bytes;
}
function applicationCsp(html) {
  // Trusted, inventoried build bytes only. Request input never becomes HTML or a CSP source.
  const hashes = [];
  for (const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script\s*>/giu)) {
    if (match[1].trim()) hashes.push("'sha256-" + createHash('sha256').update(match[1]).digest('base64') + "'");
  }
  requireValue(hashes.length <= 32);
  return "default-src 'none'; script-src 'self' " + hashes.join(' ')
    + "; script-src-attr 'none'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:;"
    + " font-src 'self' data:; connect-src 'self'; media-src 'self' blob:; base-uri 'none';"
    + " object-src 'none'; frame-ancestors 'none'; form-action 'self'; worker-src 'none'";
}

/** Snapshot exact build inventories once. There is no request-time filesystem read or HTTP proxy. */
export function createEvaluationApplicationAssets({ source, roots } = {}) {
  requireValue(source && /^[0-9a-f]{40}$/u.test(source.commitSha) && !/^0+$/u.test(source.commitSha)
    && /^[0-9a-f]{40}$/u.test(source.treeSha) && !/^0+$/u.test(source.treeSha)
    && roots && Object.keys(roots).sort().join(',') === 'h5,pc');
  const resources = new Map();
  let totalBytes = 0;
  for (const component of ['pc', 'h5']) {
    const spec = roots[component];
    requireValue(spec && typeof spec.publicDirectory === 'string' && typeof spec.inventoryFile === 'string');
    const directory = resolve(spec.publicDirectory);
    requireValue(realpathSync(directory) === directory && lstatSync(directory).isDirectory());
    const manifest = JSON.parse(regular(resolve(spec.inventoryFile), 4 * 1024 * 1024).toString('utf8'));
    const prefix = `/evaluation/${component}/`;
    requireValue(manifest.schemaVersion === 1 && manifest.kind === 'ONLINE_DEMO_STATIC_ARTIFACT_INVENTORY'
      && manifest.component === component && manifest.commitSha === source.commitSha && manifest.treeSha === source.treeSha
      && manifest.evaluation?.enabled === true && manifest.evaluation.basePath === prefix
      && Array.isArray(manifest.files) && manifest.files.length > 0 && manifest.files.length <= 10_000
      && manifest.inventorySha256 === digest(JSON.stringify(manifest.files)));
    const seen = new Set(); const staged = []; let bytesRead = 0;
    for (const item of manifest.files) {
      requireValue(item && typeof item.path === 'string' && item.path.length <= 512
        && /^[A-Za-z0-9_][A-Za-z0-9_./-]*$/u.test(item.path)
        && item.path.split('/').every(part => part && part !== '.' && part !== '..' && !part.startsWith('.'))
        && !seen.has(item.path) && types.has(extname(item.path))
        && Number.isSafeInteger(item.size) && item.size > 0 && item.size <= 32 * 1024 * 1024
        && /^[0-9a-f]{64}$/u.test(item.sha256));
      seen.add(item.path); totalBytes += item.size; bytesRead += item.size;
      requireValue(totalBytes <= 256 * 1024 * 1024);
      const bytes = regular(resolve(directory, item.path), 32 * 1024 * 1024);
      requireValue(bytes.length === item.size && digest(bytes) === item.sha256);
      staged.push({ path: item.path, body: bytes, type: types.get(extname(item.path)) });
    }
    requireValue(bytesRead === manifest.totalBytes && seen.has('index.html'));
    const csp = applicationCsp(staged.find(item => item.path === 'index.html').body.toString('utf8'));
    for (const item of staged) {
      const resource = { body: item.body, type: item.type, csp, document: item.path === 'index.html' };
      resources.set(prefix + item.path, resource);
      if (resource.document) resources.set(prefix, resource);
    }
  }
  const registry = Object.freeze({ entries: evaluationApplicationPaths,
    has(path) { return typeof path === 'string' && resources.has(path); },
    get(path) {
      const item = typeof path === 'string' ? resources.get(path) : null;
      return item ? Object.freeze({ ...item, body: Buffer.from(item.body) }) : null;
    } });
  registries.add(registry);
  return registry;
}
export const isEvaluationApplicationAssets = value => registries.has(value);
