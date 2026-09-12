#!/usr/bin/env node
import { mkdtempSync, readFileSync, writeFileSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { exportEvaluationApplications } from './online-demo/evaluation-application-export.mjs';
import { createEvaluationReadRuntime } from './online-demo/evaluation-read-runtime.mjs';

// Operator-only local entry. TLS material and invitation values never enter source control.
let runtime; let applications;
try {
  const [command, receiptFile, keyFile, certFile, portText = '8443', ...extra] = process.argv.slice(2);
  if (command !== 'serve' || !receiptFile || !keyFile || !certFile || extra.length
      || !/^[0-9]{4,5}$/u.test(portText) || Number(portText) > 65535 || Number(portText) < 1024
      || process.env.GITHUB_ACTIONS === 'true') {
    throw new Error('usage: online-demo-apps.mjs serve <image-receipt.json> <key.pem> <cert.pem> [local-port]');
  }
  const smoke = JSON.parse(readFileSync(resolve(receiptFile), 'utf8'));
  const key = readFileSync(resolve(keyFile)); const cert = readFileSync(resolve(certFile));
  if (!key.length || key.length > 16384 || !cert.length || cert.length > 16384) throw new Error('bounded TLS files required');
  const directory = mkdtempSync(resolve(tmpdir(), 'approval-evaluation-operator-')); chmodSync(directory, 0o700);
  const origin = `https://localhost:${portText}`;
  applications = await exportEvaluationApplications({ smoke });
  runtime = await createEvaluationReadRuntime({ source: smoke.source,
    backendImage: smoke.build.images.find(image => image.component === 'backend'), infrastructure: smoke.infrastructure,
    archiveSha256: smoke.build.archiveSha256, scenario: JSON.parse(readFileSync(new URL('../../config/demo/purchase-payment-golden-path.json', import.meta.url))),
    applicationRoots: applications.roots, key, cert, origin, workflow: true,
    record: value => writeFileSync(resolve(directory, 'resources.json'), JSON.stringify(value, null, 2), { mode: 0o600 }) });
  const closed = new Promise(done => runtime.server.once('close', done));
  const stop = () => { void runtime.dispose().catch(() => { process.exitCode = 1; }); };
  process.once('SIGINT', stop); process.once('SIGTERM', stop);
  await new Promise((done, fail) => { runtime.server.once('error', fail); runtime.server.listen(Number(portText), '127.0.0.1', done); });
  console.log(`Local entry: ${origin}/evaluation`);
  console.log('Private resource receipt: ' + resolve(directory, 'resources.json'));
  console.log('Non-production. Two independent invitations; do not publish them in logs or shared artifacts.');
  for (let index = 0; index < 2; index++) console.log(`Invitation ${index + 1}: ${runtime.controller.issueInvitation().invitation}`);
  await closed;
} catch (error) {
  console.error('EVALUATION_LOCAL_ENTRY_FAILED: ' + (error.message.startsWith('usage:') ? error.message : 'see private resource receipt'));
  process.exitCode = 1;
} finally {
  try { if (runtime && (await runtime.dispose()).status !== 'PASSED') process.exitCode = 1; }
  catch { process.exitCode = 1; }
  applications?.dispose();
}
