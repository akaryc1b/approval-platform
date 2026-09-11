import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test, { after } from 'node:test';
import { createEvaluationBusinessSigner, evaluationBusinessRoute, normalizeEvaluationBusinessRequest }
  from '../product-readiness/online-demo/evaluation-business-request.mjs';

const root = resolve(import.meta.dirname, '../..');
const scenario = JSON.parse(readFileSync(resolve(root, 'config/demo/purchase-payment-golden-path.json')));
const taskId = '00000000-0000-4000-8000-000000000001';
const generation = 'a'.repeat(32);
const classes = mkdtempSync(resolve(tmpdir(), 'evaluation-task-reads-'));
after(() => rmSync(classes, { recursive: true, force: true }));
writeFileSync(resolve(classes, 'TaskReadHarness.java'), `
import java.io.*; import java.time.*; import java.util.*;
import io.github.akaryc1b.approval.security.OnlineEvaluationBusinessTicket;
public class TaskReadHarness {
 public static void main(String[] args) throws Exception {
  var lines = new BufferedReader(new InputStreamReader(System.in)).lines().toList();
  if(args[0].equals("route")) {
   for(String line:lines){var f=line.split("\\t",-1);
    try{System.out.println(OnlineEvaluationBusinessTicket.route(f[0],f[1]));}
    catch(Exception failure){System.out.println("DENIED");}}
   return;
  }
  var verifier = new OnlineEvaluationBusinessTicket(args[0],args[1],
   Set.of("demo-employee","demo-manager","demo-finance-reviewer","demo-finance-approver-a","demo-finance-approver-b"),
   Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC));
  for(String line:lines){var f=line.split("\\t",-1);
   try{System.out.println(verifier.verify(f[0],f[1],f[2],f[3],f[4],f[5],f[6]).actorId());}
   catch(Exception failure){System.out.println("DENIED");}}
 }
}
`);
const build = spawnSync('javac', ['--release', '17', '-d', classes,
  resolve(root, 'apps/server/src/main/java/io/github/akaryc1b/approval/security/OnlineEvaluationBusinessTicket.java'),
  resolve(classes, 'TaskReadHarness.java')], { encoding: 'utf8', timeout: 15000 });
assert.equal(build.status, 0, build.stderr);
function java(args, lines) {
  const result = spawnSync('java', ['-cp', classes, 'TaskReadHarness', ...args], {
    input: lines.join('\n') + '\n', encoding: 'utf8', timeout: 5000,
  });
  assert.equal(result.status, 0, result.stderr); return result.stdout.trim().split('\n');
}
for (const suffix of ['delegation', 'sla']) {
  test(`Node/Java accept only the exact ${suffix} read and bind its signed proof`, async () => {
    const target = `/api/approval/tasks/${taskId}/${suffix}`;
    const allowed = ['GET', target];
    const denied = [['POST', target], ['GET', target + '/admin'], ['GET', target + '?scope=all'],
      ['GET', target.replace(suffix, '%73' + suffix.slice(1))]];
    assert.equal(evaluationBusinessRoute(...allowed).write, false);
    for (const pair of denied) assert.throws(() => evaluationBusinessRoute(...pair));
    assert.deepEqual(java(['route'], [allowed, ...denied].map(pair => pair.join('\t'))),
      ['READ', ...denied.map(() => 'DENIED')]);
    const signer = createEvaluationBusinessSigner(scenario, generation, () => 1000);
    try {
      const request = await normalizeEvaluationBusinessRequest({ method: 'GET', target,
        contentType: '', idempotencyKey: '', body: Buffer.alloc(0) }, scenario, 'demo-manager');
      const proof = signer.issue('demo-manager', request, 'task-read-test');
      const wire = path => [proof, 'GET', path, '', request.bodySha256, '', 'task-read-test'].join('\t');
      assert.deepEqual(java([signer.publicKeyBase64, generation], [
        wire(`/api/approval/tasks/${taskId}/form-runtime`), wire(target), wire(target),
      ]), ['DENIED', 'demo-manager', 'DENIED']);
    } finally { signer.disable(); }
  });
}
