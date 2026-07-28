import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
async function text(file) { return readFile(path.join(root, file), 'utf8'); }

const acceptancePath = 'docs/M5_B_GOVERNANCE_ACCEPTANCE.md';
const protocolPath = 'docs/M5_B_PROCESS_MIGRATION_PERSISTENCE_PROTOCOL.md';

test('M5-B governance decision accepts only the domain and persistence stage', async () => {
  const [acceptance, protocol] = await Promise.all([text(acceptancePath), text(protocolPath)]);
  for (const marker of [
    'M5-B governance decision: `ACCEPTED`',
    'Acceptance evidence status: `PERMANENTLY_VALIDATED`',
    'M5-C stage authorization: `AUTHORIZED_TO_BEGIN`',
    'Production migration execution authorization: `NOT_AUTHORIZED`',
    'Issue #56 defines M5-B as the governed migration domain model and persistence protocol',
    'PR #58 remains Open + Draft',
    'Issues #13, #14 and #56 remain Open',
    'does not authorize M5-D',
  ]) assert.ok(acceptance.includes(marker), `M5-B acceptance omits ${marker}`);
  for (const marker of [
    'M5-B stage status: `ACCEPTED`',
    'M5-C is the only authorized next stage',
    'does not authorize M5-D',
    'Never retry `UNKNOWN` automatically',
  ]) assert.ok(protocol.includes(marker), `M5-B protocol omits ${marker}`);
});

test('M5-B acceptance freezes permanent evidence without manufacturing execution authority', async () => {
  const acceptance = await text(acceptancePath);
  for (const marker of [
    'Run ID: `30089736069`',
    'run number: `#527`',
    'head: `86951bed70f54c25981f69da32b2cfdacf7afb22`',
    'Maven aggregate: `540` tests',
    'persistence-jdbc: `227/227`',
    'M5-B domain and JDBC protocol: `37/37`',
    'M5-B4 lease/UNKNOWN PostgreSQL scenarios: `8/8`',
    'M5 permanent Node boundaries: `29/29`',
    '6eb97232ea99f16a870484482b0f9e09aff41183367e7679acefcadab1de905c',
    'No V38 or later migration exists',
    'M5-C — Immutable Migration Plans and Approval Gates',
    'Run ID: `30094448784`',
    'run number: `#530`',
    'head: `3bcc679af0c573ef972bf303fd079c0f7ab65653`',
    'M5 permanent Node boundaries: `31/31`',
    'b9d005ecbcc9b22eae37ae835e1cfb499117c664f1199568b94f2439b6cf3241',
    'M5-C stage authorization: `AUTHORIZED_TO_BEGIN`',
  ]) assert.ok(acceptance.includes(marker), `M5-B acceptance evidence omits ${marker}`);
  assert.doesNotMatch(acceptance, /M5-D stage authorization: `AUTHORIZED`/);
  assert.doesNotMatch(acceptance, /Production migration execution authorization: `AUTHORIZED`/);
  assert.doesNotMatch(acceptance, /PR #58 status: `READY`/);
});
