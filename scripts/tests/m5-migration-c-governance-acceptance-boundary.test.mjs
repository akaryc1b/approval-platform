import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const text = (file) => readFile(path.join(root, file), 'utf8');
const acceptancePath = 'docs/M5_C_GOVERNANCE_ACCEPTANCE.md';
const protocolPath = 'docs/M5_C_IMMUTABLE_MIGRATION_PLAN_PROTOCOL.md';

test('M5-C governance accepts immutable plans without authorizing production execution', async () => {
  const [acceptance, protocol] = await Promise.all([
    text(acceptancePath),
    text(protocolPath),
  ]);
  for (const marker of [
    'M5-C governance decision: `ACCEPTED`',
    'Acceptance evidence status: `PERMANENTLY_VALIDATED`',
    'M5-D stage authorization: `AUTHORIZED_TO_BEGIN`',
    'Production migration execution authorization: `NOT_AUTHORIZED`',
    'No additional M5-C plan lifecycle\nslice is required before stage acceptance',
    'PR #58 remains Open + Draft',
    'Issues #13, #14 and #56 remain Open',
  ]) assert.ok(acceptance.includes(marker), `M5-C acceptance omits ${marker}`);
  for (const marker of [
    'M5-C stage status: `ACCEPTED`',
    'M5-C governance decision: `ACCEPTED`',
    'M5-D stage authorization: `AUTHORIZED_TO_BEGIN`',
    'Production migration execution authorization: `NOT_AUTHORIZED`',
  ]) assert.ok(protocol.includes(marker), `M5-C protocol omits ${marker}`);
  assert.doesNotMatch(acceptance, /Production migration execution authorization: `AUTHORIZED`/);
});

test('M5-C acceptance freezes exact evidence and retained limitations', async () => {
  const acceptance = await text(acceptancePath);
  for (const marker of [
    'Run ID: `30137372365`',
    'run number: `#535`',
    'head: `6732134b0fe330862635b7c8afc78cf8747718f6`',
    'Maven aggregate: `560` tests',
    'M5-C1 domain/application/JDBC: `20/20`',
    'M5 permanent Node boundaries: `35/35`',
    'cae0b9c6f31d08e226ea185885cf009f3c1ef761e30d2e914a48e9c443d71a8c',
    'Failed Runs #532 (`30113635674`) and #533 (`30136606769`) remain retained',
    'plan consumption or a `CONSUMED` transition',
    'Production execution must\nremain disabled by default',
    'M5-D authorization to begin is not production migration execution approval',
    'Run ID: `30148042479`',
    'run number: `#536`',
    'head: `e01db0cc3f70e52075915ca3157ab3774272c5aa`',
    'M5 permanent Node boundaries: `37/37`',
    '02d10440bdad5e3276a7e12c230405913ed8af87fe89c64f7e8cabe24ce66c80',
  ]) assert.ok(acceptance.includes(marker), `M5-C acceptance evidence omits ${marker}`);
  assert.doesNotMatch(acceptance, /PR #58 status: READY/);
  assert.doesNotMatch(acceptance, /M5-D governance decision: `ACCEPTED`/);
});
