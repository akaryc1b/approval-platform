import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { waitForRestoreDatabase }
  from '../product-readiness/capacity-recovery/restore-database-readiness.mjs';

test('real PostgreSQL initialization and missing target database cannot authorize restore', {
  skip: process.env.GITHUB_ACTIONS !== 'true' && process.env.APPROVAL_TEST_RESTORE_DATABASE !== 'true'
    ? 'enable APPROVAL_TEST_RESTORE_DATABASE=true with Docker; required in CI' : false,
  timeout: 120000,
}, async () => {
  const directory = mkdtempSync(resolve(tmpdir(), 'approval-restore-db-'));
  const project = 'approval-restore-db-' + randomUUID().replaceAll('-', '');
  const composeFile = resolve(directory, 'compose.json');
  const init = resolve(directory, 'hold-init.sh');
  writeFileSync(init, `#!/bin/sh
touch /tmp/approval-init-held
while [ ! -f /tmp/approval-init-continue ]; do sleep 0.1; done
`, { mode: 0o644 });
  writeFileSync(composeFile, JSON.stringify({ services: { postgres: {
    image: 'postgres:16-alpine', network_mode: 'none',
    environment: { POSTGRES_DB: 'postgres', POSTGRES_USER: 'approval', POSTGRES_PASSWORD: randomUUID() },
    volumes: [`${init}:/docker-entrypoint-initdb.d/00-hold.sh:ro`, 'data:/var/lib/postgresql/data'],
  } }, volumes: { data: {} } }), { mode: 0o600 });
  const composeArguments = (...args) => ['docker', 'compose', '--project-name', project, '-f', composeFile, ...args];
  const invoke = (args, required = true, timeout = 10000) => {
    const argv = composeArguments(...args);
    const result = spawnSync(argv[0], argv.slice(1), { cwd: directory, encoding: 'utf8',
      shell: false, timeout, maxBuffer: 32768, killSignal: 'SIGKILL' });
    if (required) {
      assert.equal(result.error, undefined, 'native Docker invocation failed');
      assert.equal(result.status, 0, String(result.stderr || '').slice(-1000));
    }
    return result;
  };
  const wait = async args => {
    const deadline = Date.now() + 30000;
    while (Date.now() < deadline) {
      const result = invoke(args, false, Math.min(5000, deadline - Date.now()));
      if (!result.error && result.status === 0) return;
      await delay(200);
    }
    throw new Error('native PostgreSQL fixture did not reach its expected stage');
  };
  let ready;
  try {
    invoke(['up', '-d', 'postgres'], true, 60000);
    await wait(['exec', '-T', 'postgres', 'test', '-f', '/tmp/approval-init-held']);
    // Demonstrate #1782's exact false-positive against a real socket-only server.
    invoke(['exec', '-T', 'postgres', 'pg_isready', '-U', 'approval', '-d', 'approval']);
    assert.notEqual(invoke(['exec', '-T', 'postgres', 'psql', '-X', '-w',
      '-U', 'approval', '-d', 'approval', '-c', 'select 1'], false).status, 0);
    await assert.rejects(waitForRestoreDatabase({ deadline: Date.now() + 1500,
      composeArguments, cwd: directory }), /RESTORE_DATABASE_NOT_READY stage=tcp/u);

    invoke(['exec', '-T', 'postgres', 'touch', '/tmp/approval-init-continue']);
    await wait(['exec', '-T', 'postgres', 'pg_isready', '-h', '127.0.0.1', '-p', '5432', '-t', '2']);
    // A final TCP server still cannot prove that approval exists.
    await assert.rejects(waitForRestoreDatabase({ deadline: Date.now() + 1500,
      composeArguments, cwd: directory }), /RESTORE_DATABASE_NOT_READY stage=database/u);
    invoke(['exec', '-T', 'postgres', 'createdb', '-U', 'approval', 'approval']);
    ready = await waitForRestoreDatabase({ deadline: Date.now() + 10000, composeArguments, cwd: directory });
    assert.equal(ready.targetDatabaseQueryPassed, true);
    assert.equal(ready.finalTcpServerReady, true);
    assert.equal(ready.database, 'approval'); assert.equal(ready.serverMajor, 16);

    invoke(['exec', '-T', 'postgres', 'psql', '-X', '-w', '-U', 'approval', '-d', 'approval',
      '-v', 'ON_ERROR_STOP=1', '-c', "alter role approval set default_transaction_read_only = 'on'"]);
    await assert.rejects(waitForRestoreDatabase({ deadline: Date.now() + 10000,
      composeArguments, cwd: directory }), /IDENTITY_OR_WRITABILITY_MISMATCH/u);
  } finally {
    try {
      invoke(['down', '--volumes', '--remove-orphans', '--timeout', '1'], true, 15000);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  }
  console.log(JSON.stringify({ status: 'RESTORE_DATABASE_NATIVE_REGRESSION_PASSED',
    oldSocketFalsePositiveReproduced: true, temporaryServerRejected: true,
    missingDatabaseRejected: true, readOnlyDatabaseRejected: true,
    ready, cleanupPassed: true, productRestoreVerified: false }));
});
