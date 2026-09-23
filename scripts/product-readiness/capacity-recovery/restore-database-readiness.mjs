import { spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

// pg_isready is a server liveness probe, not proof that the restore database exists.
// The official image's initialization server is socket-only. Require final TCP
// readiness first, then authenticate/query through the same local socket as pg_restore.
export const restoreDatabaseQuery = "select current_database(), current_user, "
  + "current_setting('server_version_num'), current_setting('transaction_read_only')";

export async function waitForRestoreDatabase({
  deadline,
  composeArguments,
  cwd,
  environment = process.env,
  run = spawnSync,
  now = Date.now,
  sleep = delay,
}) {
  if (!Number.isSafeInteger(deadline) || deadline <= 0) {
    throw new Error('RESTORE_DATABASE_DEADLINE_REQUIRED');
  }
  const started = now();
  let attempts = 0;
  let lastStage = 'not_started';
  const remaining = () => {
    const value = deadline - now();
    if (value <= 0) {
      throw new Error(`RESTORE_DATABASE_NOT_READY stage=${lastStage} attempts=${attempts}`);
    }
    return value;
  };
  const probe = (...args) => {
    const argv = composeArguments(...args);
    const result = run(argv[0], argv.slice(1), {
      cwd,
      encoding: 'utf8',
      env: environment,
      shell: false,
      stdio: ['ignore', 'pipe', 'pipe'],
      maxBuffer: 4096,
      killSignal: 'SIGKILL',
      timeout: Math.min(10_000, remaining()),
    });
    if (result.error && ['ENOENT', 'EACCES'].includes(result.error.code)) {
      throw new Error('RESTORE_DATABASE_PROBE_UNAVAILABLE');
    }
    return result;
  };
  const successful = result => !result.error && !result.signal && result.status === 0;

  for (;;) {
    remaining();
    attempts++;
    lastStage = 'tcp';
    const tcp = probe('exec', '-T', 'postgres', 'pg_isready',
      '-h', '127.0.0.1', '-p', '5432', '-U', 'approval', '-d', 'approval', '-t', '2');
    if (successful(tcp)) {
      lastStage = 'database';
      const database = probe('exec', '-T', '-e', 'PGCONNECT_TIMEOUT=2',
        '-e', 'PGOPTIONS=-c statement_timeout=2000', 'postgres',
        'psql', '-X', '-w', '-h', '/var/run/postgresql', '-p', '5432',
        '-U', 'approval', '-d', 'approval', '-A', '-t', '-q',
        '-v', 'ON_ERROR_STOP=1', '-c', restoreDatabaseQuery);
      remaining(); // A late successful process must not extend the caller's deadline.
      if (successful(database)) {
        if (typeof database.stdout !== 'string'
            || !/^approval\|approval\|16[0-9]{4}\|off$/u.test(database.stdout.trim())) {
          throw new Error('RESTORE_DATABASE_IDENTITY_OR_WRITABILITY_MISMATCH');
        }
        return {
          database: 'approval',
          user: 'approval',
          serverMajor: 16,
          readOnly: false,
          finalTcpServerReady: true,
          targetDatabaseQueryPassed: true,
          attempts,
          elapsedMs: Math.max(0, now() - started),
        };
      }
    }
    await sleep(Math.min(500, remaining()));
  }
}
