#!/usr/bin/env node
import { resolve } from 'node:path';
import { parseArguments, plan, UsageError } from './online-demo/images-contract.mjs';
import { executeImageBuild, readSource, resolveImageOptions } from './online-demo/images-build.mjs';

const root = resolve(import.meta.dirname, '../..');
try {
  const options = parseArguments(process.argv.slice(2));
  if (options.command === 'plan') {
    const source = readSource(root);
    console.log(JSON.stringify(plan(source, resolveImageOptions(root, source, options)), null, 2));
  } else {
    const { directory, receipt } = executeImageBuild(root, options);
    console.log(`ONLINE_DEMO_IMAGE_RECEIPT=${directory}/image-build.json`);
    console.log(receipt.status);
    for (const value of receipt.nonClaims) console.log(value);
  }
} catch (error) {
  console.error(`ONLINE_DEMO_IMAGES_FAILED: ${error.message}`);
  process.exitCode = error instanceof UsageError ? 2 : 1;
}
