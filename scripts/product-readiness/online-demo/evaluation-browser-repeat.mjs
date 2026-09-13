import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { basename, dirname, resolve } from 'node:path';
import { performance } from 'node:perf_hooks';

const passed = 'TWO_BROWSER_PC_H5_BUSINESS_RESET_PASSED';
const requireValue = (value, code) => { if (!value) throw new Error(code); };
const uuid = value => typeof value === 'string'
  && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(value);

/** Two clean sequential rehearsals, never a retry. Reuse build bytes, not runtime state. */
export async function executeEvaluationBrowserRepeat({ smoke, directory, scenario, applicationRoots, deadline },
  { rehearse, now = () => performance.now() } = {}) {
  requireValue(typeof rehearse === 'function' && typeof now === 'function'
    && typeof directory === 'string' && applicationRoots
    && /^[0-9a-f]{40}$/u.test(smoke?.source?.commitSha || '')
    && /^[0-9a-f]{40}$/u.test(smoke?.source?.treeSha || '')
    && Number.isFinite(deadline), 'BROWSER_REPEAT_INPUT_REQUIRED');
  const leaf = basename(resolve(directory));
  requireValue(/^[A-Za-z0-9_-]+$/u.test(leaf), 'BROWSER_REPEAT_DIRECTORY_REQUIRED');
  const receipt = { schemaVersion: 1, kind: 'EVALUATION_BROWSER_REPEAT',
    source: { commitSha: smoke.source.commitSha, treeSha: smoke.source.treeSha },
    status: 'RUNNING', requiredRuns: 2, runs: [] };
  const file = resolve(directory, 'evaluation-browser-repeat.json');
  const save = flag => writeFileSync(file, JSON.stringify(receipt, null, 2) + '\n', { mode: 0o600, flag });
  save('wx'); // Never erase a previous invocation's evidence.
  const identities = new Set();
  try {
    for (const ordinal of [1, 2]) {
      receipt.activeRun = ordinal; save('w');
      const maximumMs = Math.min(360_000, Math.floor(deadline - now()));
      requireValue(Number.isInteger(maximumMs) && maximumMs >= 1000, 'BROWSER_REPEAT_DEADLINE');
      // Sibling run directories already match the unchanged artifact upload globs.
      const evidenceDirectory = ordinal === 1 ? '.' : `../${leaf}-browser-repeat-2`;
      const runDirectory = ordinal === 1 ? resolve(directory) : resolve(dirname(resolve(directory)), `${leaf}-browser-repeat-2`);
      if (ordinal === 2) mkdirSync(runDirectory, { mode: 0o700 });
      for (const name of ['rehearsal', 'resources', 'trace']) {
        requireValue(!existsSync(resolve(runDirectory, `evaluation-browser-${name}.json`)), 'BROWSER_REPEAT_EVIDENCE_EXISTS');
      }
      const result = await rehearse({ smoke, directory: runDirectory, scenario,
        applicationRoots, browser: true, maximumMs });
      requireValue(result?.status === passed && result.checksPassed === true
        && result.kind === 'EVALUATION_BROWSER_BUSINESS_REHEARSAL'
        && result.source?.commitSha === receipt.source.commitSha && result.source?.treeSha === receipt.source.treeSha
        && result.cleanup?.status === 'PASSED' && result.browserCleanup?.status === 'PASSED', 'BROWSER_REPEAT_RUN_REQUIRED');
      requireValue(now() < deadline, 'BROWSER_REPEAT_DEADLINE');
      const ids = [];
      for (const [key, suffix] of [['a', 'A'], ['b', 'B']]) {
        const created = result.created?.[key]; const payment = result[`payment${suffix}`];
        requireValue(uuid(created?.instanceId) && uuid(created?.attachmentId)
          && payment?.status === 'EXACT_SIGNED_SANDBOX_PAYMENT_DELIVERED'
          && payment.eventIds?.length === 1 && uuid(payment.eventIds[0])
          && /^[0-9a-f]{32}$/u.test(payment.before?.generation || ''), 'BROWSER_REPEAT_IDENTITIES_REQUIRED');
        ids.push(created.instanceId, created.attachmentId, payment.eventIds[0], payment.before.generation);
      }
      requireValue(new Set(ids).size === ids.length && ids.every(id => !identities.has(id)), 'BROWSER_REPEAT_REUSED_STATE');
      ids.forEach(id => identities.add(id));
      receipt.runs.push({ ordinal, evidenceDirectory, status: result.status,
        elapsedMs: result.elapsedMs, cleanup: 'PASSED', browserCleanup: 'PASSED',
        instanceIds: [result.created.a.instanceId, result.created.b.instanceId] });
      save('w');
    }
    receipt.status = 'TWO_CLEAN_BROWSER_RUNS_PASSED'; delete receipt.activeRun; save('w');
    return receipt;
  } catch {
    receipt.status = 'FAILED'; receipt.failure = 'BROWSER_REPEAT_FAILED'; save('w');
    // The individual run retains its detailed sanitized diagnostics. No retry or error-text copying.
    throw new Error('BROWSER_REPEAT_FAILED');
  }
}
