import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { chownSync, chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';

const installed = spawnSync('nginx', ['-v'], { encoding: 'utf8', timeout: 5_000 });
const delay = ms => new Promise(done => setTimeout(done, ms));
async function freePort() {
  const server = createServer();
  await new Promise((done, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', done); });
  const port = server.address().port;
  await new Promise((done, reject) => server.close(error => error ? reject(error) : done()));
  return port;
}

test('real non-root Nginx serves static routes, rejects APIs/secrets/methods, and stops cleanly', {
  skip: installed.status === 0 ? false : 'nginx binary unavailable: real static-server runtime NOT verified',
  timeout: 20_000,
}, async t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'approval-nginx-test-'));
  let child;
  t.after(async () => {
    try {
      if (child && child.exitCode === null && child.signalCode === null) {
        child.kill('SIGQUIT');
        const deadline = Date.now() + 3_000;
        while (child.exitCode === null && child.signalCode === null && Date.now() < deadline) await delay(25);
        if (child.exitCode === null && child.signalCode === null) { child.kill('SIGKILL'); throw new Error('Nginx graceful cleanup timed out'); }
      }
    } finally { rmSync(directory, { recursive: true, force: true }); }
  });
  const publicRoot = resolve(directory, 'public');
  mkdirSync(publicRoot, { mode: 0o755 });
  writeFileSync(resolve(publicRoot, 'index.html'), '<!doctype html><title>Static fixture</title>');
  writeFileSync(resolve(publicRoot, 'app.js'), 'console.log("static fixture");');
  // Deliberately planted fixtures prove denies do not depend on file absence.
  writeFileSync(resolve(publicRoot, '.env'), 'not-public');
  writeFileSync(resolve(publicRoot, 'app.js.map'), 'not-public');
  const port = await freePort();
  const original = readFileSync(new URL('../../deploy/online-demo/images/nginx.conf', import.meta.url), 'utf8');
  const config = original.replaceAll('/tmp/', `${directory}/`).replace('worker_processes auto;', 'worker_processes 1;')
    .replace('error_log /dev/stderr warn;', `error_log ${directory}/error.log warn;`)
    .replace('listen 8080;', `listen 127.0.0.1:${port};`)
    .replace('root /app/public;', `root ${publicRoot};`);
  const configPath = resolve(directory, 'nginx.conf');
  writeFileSync(configPath, config);
  chmodSync(directory, 0o755);
  const asRoot = process.getuid?.() === 0;
  const credentials = asRoot ? { uid: 101, gid: 101 } : {};
  if (asRoot) chownSync(directory, 101, 101);
  const checked = spawnSync('nginx', ['-t', '-p', `${directory}/`, '-c', configPath], {
    ...credentials, encoding: 'utf8', timeout: 5_000,
  });
  assert.equal(checked.status, 0, checked.stderr || checked.error?.message);
  child = spawn('nginx', ['-p', `${directory}/`, '-c', configPath, '-g', 'daemon off;'], {
    ...credentials, stdio: 'ignore', shell: false,
  });
  let launchError;
  child.on('error', error => { launchError = error; });
  const origin = `http://127.0.0.1:${port}`;
  let ready = false;
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    if (launchError) throw launchError;
    if (child.exitCode !== null || child.signalCode !== null) throw new Error('Nginx exited before readiness');
    try { const response = await fetch(`${origin}/healthz`, { signal: AbortSignal.timeout(300) }); ready = response.status === 200; await response.text(); }
    catch (error) { if (Date.now() >= deadline) throw error; }
    if (ready) break;
    await delay(25);
  }
  assert.equal(ready, true);
  for (const path of ['/', '/approval/tasks']) {
    const response = await fetch(origin + path);
    assert.equal(response.status, 200); assert.match(await response.text(), /Static fixture/u);
    assert.match(response.headers.get('x-robots-tag'), /noindex/u);
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(response.headers.get('cache-control'), 'no-store');
  }
  const js = await fetch(`${origin}/app.js`); assert.equal(js.status, 200);
  assert.match(js.headers.get('content-type'), /javascript/u); await js.text();
  for (const path of ['/api', '/api/approval/tasks', '/approval-api/api', '/actuator/health',
    '/payment-sandbox/v1/events', '/missing.js', '/missing.css', '/.env', '/app.js.map']) {
    const response = await fetch(origin + path); assert.equal(response.status, 404, path);
    assert.doesNotMatch(await response.text(), /Static fixture|not-public/u);
  }
  for (const method of ['POST', 'PUT', 'DELETE', 'OPTIONS']) {
    const response = await fetch(origin, { method }); assert.equal(response.status, 405); await response.text();
  }
  const robots = await fetch(`${origin}/robots.txt`);
  assert.match(await robots.text(), /Disallow: \//u);
});
