import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { createEvaluationApplicationAssets } from '../product-readiness/online-demo/evaluation-applications.mjs';

const hash = value => createHash('sha256').update(value).digest('hex');
const alias = '/evaluation/pc/_app.config.js?v=5.7.0-a1b2c3d4';
const config = 'window._VBEN_ADMIN_PRO_APP_CONF_={"VITE_GLOB_API_URL":"/api"};';
const html = target => `<html><head><script src="${target}"></script><script type="module" src="/evaluation/pc/app.js"></script></head><body>Fixture</body></html>`;
function fixture(t, document = html(alias), includeConfig = true) {
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-pc-artifacts-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const source = { commitSha: 'a'.repeat(40), treeSha: 'b'.repeat(40) }; const roots = {};
  for (const component of ['pc', 'h5']) {
    const publicDirectory = resolve(directory, component); mkdirSync(publicDirectory);
    const content = { 'index.html': component === 'pc' ? document : '<html>H5 fixture</html>', 'app.js': 'console.log("fixture")' };
    if (component === 'pc' && includeConfig) content['_app.config.js'] = config;
    const files = Object.entries(content).map(([path, text]) => {
      const bytes = Buffer.from(text); writeFileSync(resolve(publicDirectory, path), bytes);
      return { path, size: bytes.length, sha256: hash(bytes) };
    });
    const inventoryFile = resolve(directory, component + '.json');
    writeFileSync(inventoryFile, JSON.stringify({ ...source, schemaVersion: 1,
      kind: 'ONLINE_DEMO_STATIC_ARTIFACT_INVENTORY', component,
      evaluation: { enabled: true, basePath: `/evaluation/${component}/` }, files,
      totalBytes: files.reduce((sum, file) => sum + file.size, 0), inventorySha256: hash(JSON.stringify(files)) }));
    roots[component] = { publicDirectory, inventoryFile };
  }
  return { directory, roots, create: () => createEvaluationApplicationAssets({ source, roots }) };
}

test('the Docker PC stage writes only literal public production settings before the unchanged frozen build', t => {
  const docker = readFileSync(new URL('../../deploy/online-demo/images/clients.Dockerfile', import.meta.url), 'utf8');
  const stage = docker.split('FROM client-tools AS pc-build')[1].split('FROM client-tools AS h5-build')[0];
  const script = stage.match(/&& (printf '%s\\n'[\s\S]*?> \.upstream\/vben\/apps\/web-ele\/\.env\.production\.local) \\\n/u)?.[1];
  assert.ok(script, 'Vben reads file-based production base; Docker ENV alone is insufficient');
  assert.ok(stage.indexOf('bootstrap-vben.mjs') < stage.indexOf(script));
  assert.ok(stage.indexOf(script) < stage.indexOf('pnpm --dir .upstream/vben install --frozen-lockfile'));
  assert.match(stage, /pnpm --dir \.upstream\/vben build:ele/u);
  const f = fixture(t); const app = resolve(f.directory, '.upstream/vben/apps/web-ele'); mkdirSync(app, { recursive: true });
  const original = 'VITE_BASE=/\nVITE_GLOB_API_URL=https://unreachable.invalid/api\n';
  writeFileSync(resolve(app, '.env.production'), original);
  execFileSync('sh', ['-eu', '-c', script], { cwd: f.directory, timeout: 2000 });
  const settings = readFileSync(resolve(app, '.env.production.local'), 'utf8');
  assert.deepEqual(settings.trim().split('\n'), ['VITE_BASE=/evaluation/pc/', 'VITE_APPROVAL_ONLINE_EVALUATION=true',
    'VITE_APPROVAL_LOCAL_DEMO=false', 'VITE_APPROVAL_CONNECTOR=standalone', 'VITE_APPROVAL_API_URL=/api',
    'VITE_GLOB_API_URL=/api', 'VITE_NITRO_MOCK=false']);
  assert.equal(readFileSync(resolve(app, '.env.production'), 'utf8'), original);
  assert.doesNotMatch(script, /printenv|process\.env|--env-mode|--no-frozen|TOKEN|PASSWORD|PRIVATE_KEY/u);
  // This executes the real shell fragment and filesystem, not a complete Turbo/Vite build.
});
test('exact HTML-declared version alias serves the same inventoried config snapshot', t => {
  const f = fixture(t); const assets = f.create(); assert.equal(assets.has(alias), true);
  const versioned = assets.get(alias); assert.equal(versioned.body.toString(), config);
  assert.equal(versioned.type, 'text/javascript; charset=utf-8'); assert.equal(versioned.document, false);
  assert.equal(versioned.csp, assets.get('/evaluation/pc/_app.config.js').csp);
  versioned.body.fill(0); assert.equal(assets.get(alias).body.toString(), config);
  writeFileSync(resolve(f.roots.pc.publicDirectory, '_app.config.js'), 'changed after snapshot');
  assert.equal(assets.get(alias).body.toString(), config);
});
test('other queries, root aliases, encoded paths and API routes remain unavailable', t => {
  const assets = fixture(t).create();
  for (const path of [alias + '&other=1', alias.replace('a1b2c3d4', 'a1b2c3d5'), alias.replace('?v=', '?cache='),
    '/_app.config.js?v=5.7.0-a1b2c3d4', '/evaluation/pc/_app.config.js?v=other',
    '/evaluation/pc/app.js?v=5.7.0-a1b2c3d4', '/evaluation/h5/_app.config.js?v=5.7.0-a1b2c3d4',
    '/evaluation/pc/%5fapp.config.js?v=5.7.0-a1b2c3d4', '/evaluation/pc/../_app.config.js', '/api/approval/tasks/pending']) {
    assert.equal(assets.has(path), false, path); assert.equal(assets.get(path), null, path);
  }
});
test('physical config presence alone does not permit a query alias', t => {
  const assets = fixture(t, '<html><script src="/evaluation/pc/app.js"></script></html>').create();
  assert.equal(assets.has('/evaluation/pc/_app.config.js'), true); assert.equal(assets.has(alias), false);
});
test('an HTML-declared config must also exist in the verified inventory', t => {
  assert.throws(fixture(t, html(alias), false).create, /ARTIFACT_REJECTED/u);
});
test('config and index tampering cannot mint an accepted version alias', t => {
  for (const file of ['_app.config.js', 'index.html']) {
    const f = fixture(t); writeFileSync(resolve(f.roots.pc.publicDirectory, file), 'tampered');
    assert.throws(f.create, /ARTIFACT_REJECTED/u);
  }
});
for (const suffix of ['?v=x&next=/api', '?v=%61', '?v=' + 'x'.repeat(102)]) {
  test(`rejects malformed trusted config reference ${suffix.slice(0, 20)}`, t => {
    assert.throws(fixture(t, html('/evaluation/pc/_app.config.js' + suffix)).create, /ARTIFACT_REJECTED/u);
  });
}
test('conflicting config versions in one build are rejected', t => {
  const document = `<html><script src="${alias}"></script><script src="${alias}x"></script></html>`;
  assert.throws(fixture(t, document).create, /ARTIFACT_REJECTED/u);
});
