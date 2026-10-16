import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { resultMarker, portableMarker, retainReceipts, validateReceipt, validatePortable } from './fixtures/assembly-enforcer-lang-live-support.mjs';

const ci = process.env.GITHUB_ACTIONS === 'true';
test('real offline Assembly / Enforcer private Lang 3.14.0 and 3.18.0 nine-pair compatibility', {
  skip: !ci && process.env.ASSEMBLY_ENFORCER_LANG_COMPATIBILITY !== 'true' ? 'explicit reviewed Lang fixture opt-in required' : false,
}, () => {
  const seal = process.env.ASSEMBLY_ENFORCER_LANG_COMPATIBILITY_SEAL;
  if (!ci) assert.ok(seal, 'reviewed sealed source/runtime/cache inputs required');
  const fixture = fileURLToPath(new URL(ci ? './fixtures/assembly-enforcer-lang-portable.py' : './fixtures/assembly-enforcer-lang-compatibility.py', import.meta.url));
  const run = spawnSync('python3', ['-I', '-S', '-B', fixture, ...(ci ? ['--execute'] : ['--execute-seal', seal])],
    { encoding: 'utf8', timeout: ci ? 1500000 : 1230000, maxBuffer: 4 * 1024 * 1024,
      env: { ...process.env, ASSEMBLY_ENFORCER_LANG_COMPATIBILITY: 'true' } });
  const records = retainReceipts(run.stdout || '', ci);
  assert.equal(run.status, 0, `${run.error?.message || ''}\n${run.stdout || ''}\n${run.stderr || ''}`);
  const pins = JSON.parse(readFileSync(new URL('./fixtures/assembly-enforcer-lang-pins.json', import.meta.url), 'utf8'));
  assert.ok(records[resultMarker], 'complete actual fixture result required'); validateReceipt(records[resultMarker], pins);
  if (ci) { assert.ok(records[portableMarker], 'complete portable result required'); validatePortable(records[portableMarker], pins); }
});
