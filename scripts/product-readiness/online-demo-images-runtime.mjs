#!/usr/bin/env node
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { executeImageRuntime } from './online-demo/images-runtime.mjs';
import { selectImageRuntimeScope } from './online-demo/runtime-scope.mjs';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
try {
  const [command = 'ci', ...extra] = process.argv.slice(2);
  if (!['ci', 'run'].includes(command) || extra.length) throw new Error('usage: online-demo-images-runtime.mjs ci|run');
  const scope = command === 'ci' ? selectImageRuntimeScope(root) : { selected: true, reason: 'EXPLICIT_LOCAL_RUN' };
  console.log(`ONLINE_DEMO_IMAGE_RUNTIME_SCOPE=${JSON.stringify(scope)}`);
  if (scope.selected) {
    const result = await executeImageRuntime(root);
    console.log(result.receipt.status);
    console.log(`ONLINE_DEMO_IMAGE_RUNTIME_EVIDENCE=${result.directory}`);
  }
} catch (error) {
  console.error(`ONLINE_DEMO_IMAGE_RUNTIME_FAILED: ${error.message}`);
  process.exitCode = 1;
}
