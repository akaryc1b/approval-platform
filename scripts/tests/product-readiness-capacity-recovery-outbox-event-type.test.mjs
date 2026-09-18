import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

const root = resolve(import.meta.dirname, '../..');
const runtime = readFileSync(
  resolve(
    root,
    'scripts/product-readiness/capacity-recovery/upgrade-restore.mjs',
  ),
  'utf8',
);

test('upgrade restore queries the authoritative completion Outbox event type', () => {
  assert.match(
    runtime,
    /const completionEventType = eventType\(\)/u,
  );
  assert.match(
    runtime,
    /and event_type = '\$\{completionEventType\}'/u,
  );
  assert.doesNotMatch(
    runtime,
    /and event_type = 'APPROVAL_INSTANCE_COMPLETED'/u,
  );
  assert.match(
    runtime,
    /APPROVAL_INSTANCE_COMPLETED is the audit action, not the Outbox event type/u,
  );
});
