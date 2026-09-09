#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import { executeImageRuntime } from './online-demo/images-runtime.mjs';
import { selectImageRuntimeScope } from './online-demo/runtime-scope.mjs';
import { executeEvaluationBusinessRehearsal } from './online-demo/evaluation-business-rehearsal.mjs';
import { executeEvaluationSlotRehearsal } from './online-demo/evaluation-slot-rehearsal.mjs';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
try {
  const [command = 'ci', ...extra] = process.argv.slice(2);
  if (!['ci', 'run'].includes(command) || extra.length) throw new Error('usage: online-demo-images-runtime.mjs ci|run');
  const scope = command === 'ci' ? selectImageRuntimeScope(root) : { selected: true, reason: 'EXPLICIT_LOCAL_RUN' };
  console.log(`ONLINE_DEMO_IMAGE_RUNTIME_SCOPE=${JSON.stringify(scope)}`);
  if (scope.selected) {
    const started = performance.now();
    const result = await executeImageRuntime(root);
    console.log(result.receipt.status);
    console.log(`ONLINE_DEMO_IMAGE_RUNTIME_EVIDENCE=${result.directory}`);
    // Reuse inspected image IDs after smoke cleanup; do not pay for a second build.
    // Reserve the remaining job time for bounded cleanup rather than extending CI.
    const maximumMs = Math.min(360_000, Math.floor(42 * 60_000 - (performance.now() - started)));
    const slots = await executeEvaluationSlotRehearsal({ smoke: result.receipt, directory: result.directory,
      scenario: JSON.parse(readFileSync(resolve(root, 'config/demo/purchase-payment-golden-path.json'), 'utf8')), maximumMs, readOnlyIdentity: true });
    if (slots.privateReadIdentity?.status !== 'SIGNED_PENDING_READ_PREFLIGHT_PASSED') {
      throw new Error('SIGNED_READ_IDENTITY_PREFLIGHT_REQUIRED');
    }
    console.log(slots.status);
    const business = await executeEvaluationBusinessRehearsal({ smoke: result.receipt, directory: result.directory,
      scenario: JSON.parse(readFileSync(resolve(root, 'config/demo/purchase-payment-golden-path.json'), 'utf8')),
      maximumMs: Math.min(360_000, Math.floor(42 * 60_000 - (performance.now() - started))) });
    if (business.status !== 'TWO_SESSION_REAL_BUSINESS_API_RESET_PASSED') throw new Error('BUSINESS_API_REHEARSAL_REQUIRED');
    console.log(business.status);
    console.log(`ONLINE_DEMO_SLOT_EVIDENCE=${result.directory}/evaluation-slot-rehearsal.json`);
  }
} catch (error) {
  console.error(`ONLINE_DEMO_IMAGE_RUNTIME_FAILED: ${error.message}`);
  process.exitCode = 1;
}
