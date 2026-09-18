import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';

import {
  createExactEventAllowlist,
  publishExactEventAllowlist,
  verifyExactAcceptedPayments,
} from '../product-readiness/capacity-recovery/sandbox-event-allowlist.mjs';
import {
  validateBacklogHandoff,
  requireSameBacklogIdentity,
} from '../product-readiness/capacity-recovery/backlog-handoff.mjs';

const root = resolve(import.meta.dirname, '../..');
const type = 'purchase-payment.completed.v1';
const tenant = 'tenant-local-reference';
const fields = ['eventId', 'idempotencyKey', 'aggregateId', 'businessKey', 'purchaseOrderReference'];
const uuid = value => `00000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}`;
function fixture(count = 96) {
  const instances = Array.from({ length: count }, (_, i) => ({
    instanceId: uuid(i + 1000), businessKey: `PURCHASE-${i}`, purchaseOrderReference: `PO-${i}`,
    finalStatus: 'COMPLETED',
  }));
  return {
    instances,
    rows: instances.map((instance, i) => ({
      id: uuid(i + 2000), eventId: uuid(i + 3000), eventType: type,
      aggregateId: instance.instanceId, idempotencyKey: `${type}:${instance.instanceId}`,
      requestId: `request-${i}`, traceId: `trace-${i}`, status: 'PENDING', attempts: 0,
      deliveredAt: null, lastError: null, responseCode: null, providerRequestId: null,
    })),
  };
}
function allowlist(value = fixture()) {
  return createExactEventAllowlist(value.rows, value.instances, tenant, type);
}
function accepted(value = allowlist()) {
  return { acceptedPaymentResults: value.entries.length,
    acceptedPayments: structuredClone(value.entries), allowlistSha256: value.sha256 };
}

test('exact allowlist binds all 96 original events and is stable under input reordering', () => {
  const value = fixture();
  const original = allowlist(value);
  assert.equal(original.entries.length, 96);
  assert.equal(original.content.split('\n').length, 98);
  assert.match(original.sha256, /^[0-9a-f]{64}$/u);
  assert.deepEqual(allowlist({ rows: [...value.rows].reverse(), instances: [...value.instances].reverse() }), original);
  assert.equal(verifyExactAcceptedPayments(accepted(original), original), true);
  value.rows[0].eventId = 'mutated';
  assert.equal(original.entries[0].eventId, uuid(3000));
});

test('singleton restore event uses the same exact authorization format', () => {
  const value = allowlist(fixture(1));
  assert.equal(value.entries.length, 1);
  assert.equal(verifyExactAcceptedPayments(accepted(value), value), true);
});

for (const count of [0, 97]) test(`rejects event count ${count}`, () => {
  assert.throws(() => allowlist(fixture(count)), /between 1 and 96/u);
});
for (const field of ['eventId', 'aggregateId', 'idempotencyKey']) {
  test(`rejects duplicate event ${field}`, () => {
    const value = fixture();
    value.rows[1][field] = value.rows[0][field];
    assert.throws(() => allowlist(value), /duplicate|requires exact/u);
  });
}
for (const field of ['businessKey', 'purchaseOrderReference']) {
  test(`rejects duplicate ${field}`, () => {
    const value = fixture();
    value.instances[1][field] = value.instances[0][field];
    assert.throws(() => allowlist(value), /duplicate/u);
  });
  for (const bad of ['PO-*', 'PO-?', 'PO-\tOTHER', 'PO-\nOTHER', '', 'a'.repeat(257)]) {
    test(`rejects non-exact ${field} ${JSON.stringify(bad).slice(0, 30)}`, () => {
      const value = fixture();
      value.instances[0][field] = bad;
      assert.throws(() => allowlist(value), /exact bounded literal/u);
    });
  }
}

test('rejects missing, sparse, cross-instance and already-delivered inputs', () => {
  const value = fixture();
  assert.throws(() => allowlist({ ...value, rows: new Array(96) }), /requires exact/u);
  assert.throws(() => allowlist({ ...value, instances: new Array(96) }), /instance identity/u);
  assert.throws(() => allowlist({ ...value, instances: value.instances.slice(1) }), /counts differ/u);
  value.instances[0].instanceId = uuid(9000);
  assert.throws(() => allowlist(value), /does not belong/u);
  const next = fixture(); next.rows[0].status = 'DELIVERED';
  assert.throws(() => allowlist(next), /undelivered/u);
  assert.throws(() => createExactEventAllowlist(next.rows, next.instances, '*', type), /tenant/u);
  assert.throws(() => createExactEventAllowlist(next.rows, next.instances, tenant, 'wrong'), /completion type/u);
});

for (const field of fields) test(`accepted side effects must match the exact ${field}`, () => {
  const expected = allowlist(); const value = accepted(expected);
  value.acceptedPayments[95][field] += '-substituted';
  assert.equal(verifyExactAcceptedPayments(value, expected), false);
});

test('accepted results reject duplicate, missing, extra identities and different authorization digest', () => {
  const expected = allowlist();
  for (const change of [
    value => { value.acceptedPayments[95] = value.acceptedPayments[0]; },
    value => { value.acceptedPayments.pop(); },
    value => { value.acceptedPayments.push(value.acceptedPayments[0]); },
    value => { value.allowlistSha256 = '0'.repeat(64); },
    value => { value.acceptedPaymentResults = 97; },
    value => { value.acceptedPayments = new Array(96); },
  ]) {
    const value = accepted(expected); change(value);
    assert.equal(verifyExactAcceptedPayments(value, expected), false);
  }
});

test('atomic allowlist publication retains exact bytes and does not remove another temporary file', () => {
  const directory = mkdtempSync(join(tmpdir(), 'approval-allowlist-'));
  const path = join(directory, 'events.allowlist');
  try {
    const value = fixture();
    const result = publishExactEventAllowlist(path, value.rows, value.instances, tenant, type);
    assert.equal(readFileSync(path, 'utf8'), result.content);
    if (process.platform !== 'win32') assert.equal(statSync(path).mode & 0o777, 0o600);
    assert.equal(existsSync(`${path}.tmp`), false);
    writeFileSync(`${path}.tmp`, 'owned-by-another-writer');
    assert.throws(() => publishExactEventAllowlist(path, value.rows, value.instances, tenant, type), /EEXIST/u);
    assert.equal(readFileSync(`${path}.tmp`, 'utf8'), 'owned-by-another-writer');
    assert.equal(readFileSync(path, 'utf8'), result.content);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('handoff pins the original untouched 96 rows and accepts only identity-preserving retries', () => {
  const value = fixture();
  const original = validateBacklogHandoff({ sourceRunId: 'unit-profiles', instances: value.instances }, value.rows, 96);
  const retried = structuredClone(value.rows).reverse();
  retried.forEach(row => { row.attempts = 2; row.lastError = 'HTTP 503: payment sandbox unavailable'; });
  requireSameBacklogIdentity(original, retried);
  value.rows[0].eventId = 'mutated-input';
  assert.equal(original[0].eventId, uuid(3000));
});
for (const field of ['id', 'eventId', 'eventType', 'aggregateId', 'idempotencyKey', 'requestId', 'traceId']) {
  test(`handoff rejects replacement of original ${field}`, () => {
    const value = fixture(); const observed = structuredClone(value.rows);
    observed[0][field] += '-replaced';
    assert.throws(() => requireSameBacklogIdentity(value.rows, observed), /identity changed/u);
  });
}

test('handoff rejects reset, replayed work, incomplete profiles and missing rows', () => {
  const value = fixture();
  const h = { sourceRunId: 'unit-profiles', instances: value.instances };
  assert.throws(() => validateBacklogHandoff(null, value.rows, 96), /original 96/u);
  assert.throws(() => validateBacklogHandoff(h, value.rows.slice(1), 96), /original 96/u);
  assert.throws(() => validateBacklogHandoff(h, value.rows, 95), /original 96/u);
  value.rows[0].attempts = 1;
  assert.throws(() => validateBacklogHandoff(h, value.rows, 96), /untouched/u);
  value.rows[0].attempts = 0; value.instances[0].finalStatus = 'RUNNING';
  assert.throws(() => validateBacklogHandoff(h, value.rows, 96), /incomplete/u);
  assert.throws(() => requireSameBacklogIdentity(value.rows, value.rows.slice(1)), /count changed/u);
  assert.throws(() => requireSameBacklogIdentity(value.rows, [...value.rows.slice(1), value.rows[1]]), /identity changed/u);
});

test('real Java parser consumes Node files, freezes membership and rejects malformed authorization', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'approval-allowlist-java-'));
  const source = resolve(root, 'apps/server/src/main/java/io/github/akaryc1b/approval/demo/PurchasePaymentDemoEventAllowlist.java');
  const driver = join(directory, 'AllowlistProbe.java');
  const path = join(directory, 'events.allowlist');
  writeFileSync(driver, `
import java.nio.file.*;
import java.util.UUID;
import io.github.akaryc1b.approval.demo.PurchasePaymentDemoEventAllowlist;
public class AllowlistProbe {
  public static void main(String[] a) throws Exception {
    var list = PurchasePaymentDemoEventAllowlist.load(Path.of(a[0]), a[1]);
    var entry = new PurchasePaymentDemoEventAllowlist.Entry(UUID.fromString(a[2]), a[3], UUID.fromString(a[4]), a[5], a[6]);
    if (a.length > 7) Files.writeString(Path.of(a[0]), "replaced after load");
    System.out.println(list.size() + ":" + list.sha256() + ":" + list.contains(entry));
  }
}
`);
  const suffix = process.platform === 'win32' ? '.exe' : '';
  const jdk = name => process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `${name}${suffix}`) : `${name}${suffix}`;
  try {
    const compilation = spawnSync(jdk('javac'), ['-d', directory, source, driver], { encoding: 'utf8', timeout: 30_000 });
    assert.equal(compilation.status, 0, compilation.error?.message || compilation.stderr);
    const expected = allowlist();
    const invoke = (entry = expected.entries[0], target = path, tenantId = tenant, freeze = false) => spawnSync(jdk('java'),
      ['-cp', directory, 'AllowlistProbe', target, tenantId, ...fields.map(field => entry[field]), ...(freeze ? ['freeze'] : [])],
      { encoding: 'utf8', timeout: 10_000 });
    await t.test('same 96 tuples and same SHA-256 across languages', () => {
      writeFileSync(path, expected.content);
      const result = invoke();
      assert.equal(result.status, 0, result.stderr);
      assert.equal(result.stdout.trim(), `96:${expected.sha256}:true`);
    });
    for (const field of fields) await t.test(`mixed tuple ${field} cannot match`, () => {
      writeFileSync(path, expected.content);
      const entry = { ...expected.entries[0], [field]: expected.entries[1][field] };
      const result = invoke(entry);
      assert.equal(result.status, 0, result.stderr);
      assert.match(result.stdout, /:false\s*$/u);
    });
    await t.test('unknown event and matching business-key prefix are not authorized', () => {
      writeFileSync(path, expected.content);
      assert.match(invoke({ ...expected.entries[0], eventId: uuid(9999) }).stdout, /:false\s*$/u);
      assert.match(invoke({ ...expected.entries[0], businessKey: `${expected.entries[0].businessKey}-suffix` }).stdout, /:false\s*$/u);
    });
    await t.test('loaded membership cannot be widened by replacing the control data', () => {
      writeFileSync(path, expected.content);
      const result = invoke(expected.entries[0], path, tenant, true);
      assert.equal(result.status, 0, result.stderr);
      assert.equal(result.stdout.trim(), `96:${expected.sha256}:true`);
      assert.equal(readFileSync(path, 'utf8'), 'replaced after load');
    });
    for (const [label, content] of [
      ['empty', ''], ['missing entries', `PURCHASE_PAYMENT_EXACT_EVENTS_V1\t${tenant}\n`],
      ['wrong tenant', expected.content.replace(tenant, 'another-tenant')],
      ['wildcard', expected.content.replace('PURCHASE-0', 'PURCHASE-*')],
      ['duplicate identity', expected.content.split('\n').map((line, i, lines) => i === 2 ? lines[1] : line).join('\n')],
      ['missing final newline', expected.content.slice(0, -1)],
      ['empty field', expected.content.replace('PURCHASE-0', '')],
      ['excessive bytes', 'x'.repeat(128 * 1024 + 1)],
      ['excessive count', expected.content + expected.content.split('\n')[1] + '\n'],
      ['malformed UUID', expected.content.replace(uuid(3000), 'not-a-uuid')],
    ]) await t.test(`rejects ${label}`, () => {
      writeFileSync(path, content);
      assert.notEqual(invoke().status, 0);
    });
    await t.test('rejects missing files, directories and symlinks', () => {
      assert.notEqual(invoke(expected.entries[0], join(directory, 'missing')).status, 0);
      assert.notEqual(invoke(expected.entries[0], directory).status, 0);
      if (process.platform !== 'win32') {
        writeFileSync(path, expected.content);
        const link = join(directory, 'symlink'); symlinkSync(path, link);
        assert.notEqual(invoke(expected.entries[0], link).status, 0);
      }
    });
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
