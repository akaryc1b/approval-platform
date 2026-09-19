import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import './product-readiness-restore-database-native.test.mjs';
import { restoreDatabaseQuery, waitForRestoreDatabase }
  from '../product-readiness/capacity-recovery/restore-database-readiness.mjs';

const ok = { status: 0, signal: null, stdout: 'approval|approval|160014|off\n' };
const unavailable = { status: 2, signal: null, stderr: 'fixture details must not enter diagnostics' };
const compose = (...args) => ['docker', 'compose', '--project-name', 'approval-platform-demo',
  '-f', 'deploy/compose/docker-compose.yml', ...args];
function fixture(results, budget = 2000) {
  let time = 1000;
  const calls = []; const sleeps = [];
  const options = { deadline: time + budget, composeArguments: compose, cwd: '/controlled',
    environment: { PATH: '/controlled/bin' }, now: () => time,
    sleep: async milliseconds => { sleeps.push(milliseconds); time += milliseconds; },
    run(file, args, settings) {
      calls.push({ file, args, settings });
      const result = results.shift() ?? unavailable;
      if (typeof result === 'function') return result(milliseconds => { time += milliseconds; });
      return result;
    } };
  return { options, calls, sleeps };
}

test('terminal TCP and actual writable target database are both required', async () => {
  const f = fixture([ok, ok]);
  const result = await waitForRestoreDatabase(f.options);
  assert.deepEqual(result, { database: 'approval', user: 'approval', serverMajor: 16,
    readOnly: false, finalTcpServerReady: true, targetDatabaseQueryPassed: true,
    attempts: 1, elapsedMs: 0 });
  assert.equal(f.calls.length, 2); assert.deepEqual(f.sleeps, []);
  assert.deepEqual(f.calls[0].args, compose('exec', '-T', 'postgres', 'pg_isready',
    '-h', '127.0.0.1', '-p', '5432', '-U', 'approval', '-d', 'approval', '-t', '2').slice(1));
  assert.deepEqual(f.calls[1].args, compose('exec', '-T', '-e', 'PGCONNECT_TIMEOUT=2',
    '-e', 'PGOPTIONS=-c statement_timeout=2000', 'postgres', 'psql', '-X', '-w',
    '-h', '/var/run/postgresql', '-p', '5432', '-U', 'approval', '-d', 'approval',
    '-A', '-t', '-q', '-v', 'ON_ERROR_STOP=1', '-c', restoreDatabaseQuery).slice(1));
  for (const call of f.calls) {
    assert.equal(call.settings.shell, false); assert.equal(call.settings.maxBuffer, 4096);
    assert.equal(call.settings.killSignal, 'SIGKILL');
    assert.deepEqual(call.settings.stdio, ['ignore', 'pipe', 'pipe']);
    assert.equal(call.settings.env, f.options.environment);
    assert.equal(call.settings.timeout, 2000);
  }
});

test('socket-only initialization cannot pass even when old socket probe would succeed', async () => {
  const f = fixture([unavailable, ok, ok]);
  const result = await waitForRestoreDatabase(f.options);
  assert.equal(result.attempts, 2); assert.equal(result.elapsedMs, 500);
  assert.deepEqual(f.calls.map(c => c.args.includes('psql')), [false, false, true]);
});

test('server accepting connections with missing approval database is retried before restore', async () => {
  const f = fixture([ok, unavailable, ok, ok]);
  const result = await waitForRestoreDatabase(f.options);
  assert.equal(result.attempts, 2); assert.equal(f.calls.length, 4);
  assert.deepEqual(f.sleeps, [500]);
});

for (const stdout of ['', '1\n', 'approval|approval|160014|on\n',
  'postgres|approval|160014|off\n', 'approval|postgres|160014|off\n',
  'approval|approval|170000|off\n', 'approval|approval|160014|off\nextra\n']) {
  test(`reject unexpected successful SQL response ${JSON.stringify(stdout)}`, async () => {
    const f = fixture([ok, { ...ok, stdout }]);
    await assert.rejects(waitForRestoreDatabase(f.options), /IDENTITY_OR_WRITABILITY_MISMATCH/u);
    assert.equal(f.calls.length, 2); assert.deepEqual(f.sleeps, []);
  });
}

for (const failure of [unavailable, { status: 0, signal: 'SIGTERM' },
  { ...ok, error: { code: 'ETIMEDOUT' } }]) {
  test(`failed TCP probe cannot reach SQL: ${failure.signal || failure.error?.code || failure.status}`, async () => {
    const f = fixture([failure], 100);
    await assert.rejects(waitForRestoreDatabase(f.options), /NOT_READY stage=tcp attempts=1/u);
    assert.equal(f.calls.length, 1); assert.deepEqual(f.sleeps, [100]);
  });
}

test('missing database exhausts the original deadline without stderr leakage', async () => {
  const f = fixture([ok, unavailable, ok, unavailable], 600);
  await assert.rejects(waitForRestoreDatabase(f.options), error => {
    assert.match(error.message, /NOT_READY stage=database attempts=2/u);
    assert.doesNotMatch(error.message, /fixture details/u); return true;
  });
  assert.deepEqual(f.sleeps, [500, 100]);
  assert.deepEqual(f.calls.map(c => c.settings.timeout), [600, 600, 100, 100]);
});

test('slow probe cannot start SQL or authorize restore after deadline', async () => {
  const tcp = fixture([advance => { advance(100); return ok; }], 100);
  await assert.rejects(waitForRestoreDatabase(tcp.options), /NOT_READY/u);
  assert.equal(tcp.calls.length, 1);
  const sql = fixture([ok, advance => { advance(100); return ok; }], 100);
  await assert.rejects(waitForRestoreDatabase(sql.options), /NOT_READY/u);
  assert.equal(sql.calls.length, 2); assert.deepEqual(sql.sleeps, []);
});

test('missing executable fails immediately and invalid deadlines do not start a command', async () => {
  for (const code of ['ENOENT', 'EACCES']) {
    const f = fixture([{ error: { code } }]);
    await assert.rejects(waitForRestoreDatabase(f.options), /PROBE_UNAVAILABLE/u);
    assert.equal(f.calls.length, 1); assert.deepEqual(f.sleeps, []);
  }
  for (const deadline of [undefined, NaN, Infinity, '2000', 0, -1, 999, 1000]) {
    const f = fixture([ok]); f.options.deadline = deadline;
    await assert.rejects(waitForRestoreDatabase(f.options), /DEADLINE_REQUIRED|NOT_READY/u);
    assert.equal(f.calls.length, 0);
  }
});

test('actual child processes exercise readiness retries and bounded failure', async t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'restore-readiness-child-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const state = resolve(directory, 'state'); writeFileSync(state, '0');
  const child = resolve(directory, 'probe.mjs');
  writeFileSync(child, `import { readFileSync, writeFileSync } from 'node:fs';
const args = process.argv.slice(2); const state = args.shift();
const n = Number(readFileSync(state, 'utf8')); writeFileSync(state, String(n + 1));
if (n === 0 || n === 2) process.exit(2);
if (args.includes('psql')) console.log('approval|approval|160014|off');
`);
  const value = await waitForRestoreDatabase({ deadline: Date.now() + 10000, cwd: directory,
    composeArguments: (...args) => [process.execPath, child, state, ...args] });
  assert.equal(value.attempts, 3); assert.equal(readFileSync(state, 'utf8'), '5');
  assert.equal(value.targetDatabaseQueryPassed, true);
  writeFileSync(child, 'setInterval(() => {}, 1000);');
  const started = Date.now();
  await assert.rejects(waitForRestoreDatabase({ deadline: Date.now() + 100, cwd: directory,
    composeArguments: () => [process.execPath, child], run: spawnSync }), /NOT_READY/u);
  assert.ok(Date.now() - started < 3000);
});

test('runtime gates one pg_restore and retains readiness in the existing restore evidence', () => {
  const runtime = readFileSync(new URL('../product-readiness/capacity-recovery/upgrade-restore.mjs',
    import.meta.url), 'utf8');
  assert.ok(runtime.includes("import { waitForRestoreDatabase } from './restore-database-readiness.mjs';"));
  const infrastructure = runtime.slice(runtime.indexOf('async function recreateInfrastructure('),
    runtime.indexOf('function queryOutbox('));
  const wait = infrastructure.indexOf('const readiness = await waitForRestoreDatabase(');
  const restore = infrastructure.indexOf("'pg_restore'");
  assert.ok(wait >= 0 && restore > wait);
  assert.equal(infrastructure.match(/'pg_restore'/gu).length, 1);
  assert.match(infrastructure, /waitForRestoreDatabase\(\{ deadline, composeArguments, cwd: repositoryRoot \}\)/u);
  assert.match(infrastructure, /readiness,\n  \};/u);
  assert.doesNotMatch(infrastructure, /catch|retry|pg_isready|setTimeout/u);
  assert.match(infrastructure, /'--exit-on-error'/u);
});
