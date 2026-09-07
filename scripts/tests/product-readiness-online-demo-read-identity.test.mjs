import assert from 'node:assert/strict';
import { generateKeyPairSync, randomBytes, sign, verify } from 'node:crypto';
import { execFile, execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { after, before, test } from 'node:test';
import { createEvaluationReadSigner, evaluationReadPath, evaluationReadProbeProgram, verifyEvaluationReadIdentity }
  from '../product-readiness/online-demo/evaluation-read-identity.mjs';

// Protocol fixture, not a seeded approval execution. The Spring tests load the real resource.
const actors = ['demo-employee', 'demo-manager', 'demo-finance-reviewer',
  'demo-finance-approver-a', 'demo-finance-approver-b'];
const scenario = { schemaVersion: 1, tenant: { id: 'demo-purchase-payment' },
  directory: { users: actors.map(id => ({ id, displayName: id, roleCodes: ['EMPLOYEE'] })) },
  assigneeRules: { initiatorUserId: { value: actors[0] } },
  expectedWorkflow: [{ actorIds: actors.slice(1) }] };
const generation = 'a'.repeat(32);
const time = 1_800_000_000_000;
const root = resolve(import.meta.dirname, '../..');
let temporary;
const key = generateKeyPairSync('ed25519');
const publicKey = key.publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
function ticket(change = {}, pair = key) {
  const fields = ['AP-EVALUATION-READ-V1', 'GET', evaluationReadPath, generation,
    actors[0], String(time), String(time + 10_000), randomBytes(32).toString('hex')];
  for (const [index, value] of Object.entries(change)) fields[Number(index)] = value;
  const message = Buffer.from(fields.join('\n'));
  return `${message.toString('base64url')}.${sign(null, message, pair.privateKey).toString('base64url')}`;
}
// Compile the ACTUAL JDK verifier, not a translated JavaScript implementation or Java stub.
before(() => {
  temporary = mkdtempSync(resolve(tmpdir(), 'evaluation-read-java-'));
  const bridge = `import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import io.github.akaryc1b.approval.security.OnlineEvaluationReadTicket;
public class ReadTicketHarness {
  public static void main(String[] args) throws Exception {
    AtomicLong now = new AtomicLong();
    Clock clock = new Clock() {
      public ZoneId getZone() { return ZoneOffset.UTC; }
      public Clock withZone(ZoneId ignored) { return this; }
      public Instant instant() { return Instant.ofEpochMilli(millis()); }
      public long millis() { if (now.get() == -2) throw new IllegalStateException(); return now.get(); }
    };
    OnlineEvaluationReadTicket verifier;
    try { verifier = new OnlineEvaluationReadTicket(args[0], args[1], Set.of(args[2].split(",")), clock); }
    catch (Exception rejected) { System.out.println("CONFIG_REJECTED"); return; }
    var reader = new BufferedReader(new InputStreamReader(System.in));
    String line;
    while ((line = reader.readLine()) != null) {
      String[] fields = line.split("\\t", -1);
      now.set(Long.parseLong(fields[0]));
      try { var identity = verifier.verify(fields[3], fields[1], fields[2]);
        System.out.println("OK:" + identity.actorId() + ":" + identity.expiresAt().toEpochMilli()); }
      catch (SecurityException rejected) { System.out.println(rejected.getMessage()); }
    }
  }
}`;
  writeFileSync(resolve(temporary, 'ReadTicketHarness.java'), bridge);
  mkdirSync(resolve(temporary, 'classes'));
  execFileSync('javac', ['--release', '17', '-d', resolve(temporary, 'classes'),
    resolve(root, 'apps/server/src/main/java/io/github/akaryc1b/approval/security/OnlineEvaluationReadTicket.java'),
    resolve(temporary, 'ReadTicketHarness.java')], { timeout: 30_000, stdio: 'pipe' });
});
after(() => { if (temporary) rmSync(temporary, { recursive: true, force: true }); });
const denied = 'EVALUATION_READ_AUTHENTICATION_REQUIRED';
function java(rows, encodedKey = publicKey, slotGeneration = generation, allowedActors = actors) {
  const input = rows.map(row => [row.now ?? time, row.method ?? 'GET', row.path ?? evaluationReadPath,
    row.ticket].join('\t')).join('\n') + '\n';
  return execFileSync('java', ['-cp', resolve(temporary, 'classes'), 'ReadTicketHarness',
    encodedKey, slotGeneration, allowedActors.join(',')], { input, encoding: 'utf8', timeout: 20_000 })
    .trim().split('\n');
}

test('Node Ed25519 tickets are accepted by the real Java verifier for all canonical actors', () => {
  const signer = createEvaluationReadSigner(scenario, generation, () => time);
  assert.deepEqual(signer.actors, actors);
  const rows = actors.map(actor => ({ ticket: signer.issue(actor) }));
  assert.deepEqual(java(rows, signer.publicKeyBase64), actors.map(actor => `OK:${actor}:${time + 10_000}`));
  assert.deepEqual(Object.keys(signer).sort(), ['actors', 'disable', 'issue', 'publicKeyBase64']);
  signer.disable(); assert.throws(() => signer.issue(actors[0]));
});
test('Java consumes a valid nonce once, including repeated reads on one verifier instance', () => {
  const value = ticket();
  assert.deepEqual(java([{ ticket: value }, { ticket: value }]), [`OK:${actors[0]}:${time + 10_000}`, denied]);
});
for (const [label, changes] of Object.entries({ version: { 0: 'V2' }, method: { 1: 'POST' },
  path: { 2: '/api/approval/definitions' }, generation: { 3: 'b'.repeat(32) },
  admin: { 4: 'demo-admin' }, unknownActor: { 4: 'demo-intruder' }, future: { 5: String(time + 1) },
  expired: { 5: String(time - 1), 6: String(time) }, tooLong: { 6: String(time + 10_001) },
  zeroTtl: { 6: String(time) }, leadingZero: { 5: '0' + time }, hugeTime: { 5: '9'.repeat(20) },
  invalidNonce: { 7: 'invalid' }, extraField: { 8: 'extra' }, embeddedNewline: { 4: 'demo-employee\nadmin' } })) {
  test(`Java rejects even correctly signed invalid claims: ${label}`, () => {
    assert.deepEqual(java([{ ticket: ticket(changes) }]), [denied]);
  });
}
test('signatures cannot cross independently generated keys or reset generations', () => {
  const second = generateKeyPairSync('ed25519');
  assert.deepEqual(java([{ ticket: ticket({}, second) }]), [denied]);
  assert.deepEqual(java([{ ticket: ticket() }], publicKey, 'b'.repeat(32)), [denied]);
});
test('invalid proof encodings, truncation, tampering and oversized input never echo credentials', () => {
  const valid = ticket();
  const [payload, signature] = valid.split('.');
  const values = ['', valid + '=', payload + '=.' + signature, valid + '.extra',
    payload + '.' + randomBytes(64).toString('base64url'), payload + '.' + signature.slice(1),
    'x'.repeat(1025), '%%%.' + signature];
  assert.deepEqual(java(values.map(value => ({ ticket: value }))), values.map(() => denied));
});
test('request method and actual URI are verified rather than trusting signed fields alone', () => {
  assert.deepEqual(java([{ ticket: ticket(), method: 'POST' },
    { ticket: ticket(), path: evaluationReadPath + '?limit=100' }]), [denied, denied]);
});
test('bounded replay ledger does not evict a live nonce to admit request 257', () => {
  const rows = Array.from({ length: 257 }, () => ({ ticket: ticket() }));
  const result = java([...rows, { ticket: rows[0].ticket }]);
  assert.equal(result.slice(0, 256).every(line => line.startsWith('OK:')), true);
  assert.deepEqual(result.slice(256), [denied, denied]);
});
test('expired ledger entries can be removed without accepting expired proof replay', () => {
  const rows = Array.from({ length: 256 }, () => ({ ticket: ticket() }));
  const after = time + 10_000;
  const result = java([...rows, { ticket: rows[0].ticket, now: after },
    { ticket: ticket({ 5: String(after), 6: String(after + 10_000) }), now: after }]);
  assert.deepEqual(result.slice(256), [denied, `OK:${actors[0]}:${after + 10_000}`]);
});
for (const badTime of [time - 1, -1, -2]) {
  test(`clock failure/regression permanently closes the verifier: ${badTime}`, () => {
    const result = java([{ ticket: ticket() }, { ticket: ticket(), now: badTime }, { ticket: ticket() }]);
    assert.equal(result[0].startsWith('OK:'), true); assert.deepEqual(result.slice(1), [denied, denied]);
  });
}
test('Java rejects missing/alternate algorithm/configuration identities', () => {
  const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 }).publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  for (const encodedKey of ['', 'not-base64', publicKey + '\n', rsa]) {
    assert.deepEqual(java([], encodedKey), ['CONFIG_REJECTED']);
  }
  assert.deepEqual(java([], publicKey, '0'.repeat(32)), ['CONFIG_REJECTED']);
  assert.deepEqual(java([], publicKey, generation, ['demo-admin']), ['CONFIG_REJECTED']);
});
test('signer restricts tenant, canonical actors and profile generation without exporting a private key', () => {
  const signer = createEvaluationReadSigner(scenario, generation, () => time);
  assert.throws(() => signer.issue('demo-admin')); assert.throws(() => signer.issue('unknown'));
  for (const bad of ['', '0'.repeat(32), '../other']) assert.throws(() => createEvaluationReadSigner(scenario, bad));
  const other = structuredClone(scenario); other.tenant.id = 'another-tenant';
  assert.throws(() => createEvaluationReadSigner(other, generation));
  assert.equal(Object.isFrozen(signer), true); assert.equal(Object.isFrozen(signer.actors), true);
});
for (const invalid of [time - 1, NaN, Infinity, -1]) {
  test(`Node signer permanently disables on invalid clock ${invalid}`, () => {
    let now = time; const signer = createEvaluationReadSigner(scenario, generation, () => now);
    signer.issue(actors[0]); now = invalid; assert.throws(() => signer.issue(actors[0]));
    now = time + 1; assert.throws(() => signer.issue(actors[0]));
  });
}
test('read preflight signs five principals, tests every replay and retains no tickets', async () => {
  const signer = createEvaluationReadSigner(scenario, generation, () => time);
  const seen = new Set(); const calls = [];
  const result = await verifyEvaluationReadIdentity(signer, 'a'.repeat(64), async args => {
    calls.push(args); const [proof, path, extra] = args.slice(-3);
    let status = !proof ? 401 : extra ? 403 : path !== evaluationReadPath ? 404 : seen.has(proof) ? 401 : 200;
    if (status === 200) {
      const [body, signature] = proof.split('.');
      assert.equal(verify(null, Buffer.from(body, 'base64url'),
        { key: Buffer.from(signer.publicKeyBase64, 'base64'), type: 'spki', format: 'der' },
        Buffer.from(signature, 'base64url')), true);
      seen.add(proof);
    }
    return JSON.stringify({ status, taskCount: status === 200 ? 0 : null });
  });
  assert.equal(calls.length, 13); assert.equal(result.length, 5);
  for (const proof of seen) assert.equal(JSON.stringify(result).includes(proof), false);
});
test('unexpected preflight output is not accepted as successful identity integration', async () => {
  for (const result of [{ status: 200, taskCount: 0 }, { status: 401 },
    { status: 401, taskCount: null, proof: 'must-not-escape' }, null]) {
    await assert.rejects(verifyEvaluationReadIdentity(createEvaluationReadSigner(scenario, generation),
      'a'.repeat(64), async () => JSON.stringify(result)));
  }
});
test('existing runtime command selects the real signed-read rehearsal and the adapter retires its signer', () => {
  const read = path => readFileSync(resolve(root, path), 'utf8');
  const launch = read('scripts/product-readiness/online-demo-images-runtime.mjs');
  assert.match(launch, /readOnlyIdentity: true/u);
  const adapter = read('scripts/product-readiness/online-demo/evaluation-docker-slots.mjs');
  assert.match(adapter, /SPRING_PROFILES_ACTIVE=online-demo/u);
  assert.match(adapter, /verifyEvaluationReadIdentity\(readSigner, probe, command\)/u);
  assert.match(adapter, /finally \{ readSigner\?\.disable\(\); \}/u);
  assert.doesNotMatch(adapter, /privateKey|LOCAL_HEADERS/u);
  const filter = read('apps/server/src/main/java/io/github/akaryc1b/approval/security/OnlineEvaluationReadIdentityFilter.java');
  assert.match(filter, /ApprovalPrincipal\.active\(tenantId, identity\.actorId\(\), Set\.of\(\)/u);
  assert.match(filter, /request\.getQueryString\(\) != null/u);
  assert.match(filter, /tickets\.size\(\) != 1/u);
});

// Run the exact probe program against a real local HTTP fixture, not a rewritten parser.
test('actual probe accepts the existing page shape and rejects arrays or nonempty fresh state', async t => {
  let payload = { items: [], total: 0, limit: 20, offset: 0, hasMore: false };
  let status = 200;
  const server = createServer((request, response) => {
    response.writeHead(status, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify(payload));
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => new Promise(done => { server.closeAllConnections(); server.close(done); }));
  const execute = () => new Promise((done, fail) => execFile(process.execPath,
    ['--input-type=module', '-e', evaluationReadProbeProgram, '', evaluationReadPath, '', String(server.address().port)],
    { encoding: 'utf8', timeout: 5000, maxBuffer: 65536 },
    (error, output) => error ? fail(error) : done(JSON.parse(output))));
  assert.deepEqual(await execute(), { status: 200, taskCount: 0 });
  payload = []; await assert.rejects(execute(), /FRESH_PENDING_PAGE_REQUIRED/u);
  payload = { items: [], total: 1, limit: 20, offset: 0 };
  await assert.rejects(execute(), /FRESH_PENDING_PAGE_REQUIRED/u);
  status = 401; payload = { error: 'untrusted-body-must-not-be-returned' };
  assert.deepEqual(await execute(), { status: 401, taskCount: null });
});
