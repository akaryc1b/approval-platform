import './document-authority-boundary.test.mjs';
import './acceptance-catalog-boundary.test.mjs';
import './m6-e-approval-assistance-boundary.test.mjs';
import './m6-e-p4-durable-evidence-boundary.test.mjs';
import './m6-e-p5-read-only-presentation-boundary.test.mjs';
import './m6-e-p6-openai-provider-audit-boundary.test.mjs';
import './m6-e-p6-openai-secret-source-boundary.test.mjs';
import './m6-e-p6-openai-codec-boundary.test.mjs';
import './m6-e-p6-openai-codec-hardening-boundary.test.mjs';
import './m6-e-p6-openai-sender-boundary.test.mjs';
import './product-readiness-demo-guides-boundary.test.mjs';
import './product-readiness-demo-backend-boundary.test.mjs';
import './product-readiness-purchase-payment-contract.test.mjs';
import './product-readiness-demo-seed-boundary.test.mjs';
import './product-readiness-cross-client-local-demo-boundary.test.mjs';
import './product-readiness-cross-client-runtime-evidence-boundary.test.mjs';

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { basename, extname } from 'node:path';
import test from 'node:test';

const trackedFiles = execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8' })
  .split('\0')
  .filter(Boolean)
  .sort();

const forbiddenDirectories = new Set([
  '.upstream',
  'build',
  'coverage',
  'dist',
  'node_modules',
  'target',
]);

const forbiddenExtensions = new Set([
  '.base64',
  '.b64',
  '.class',
  '.diff',
  '.dll',
  '.dylib',
  '.exe',
  '.jar',
  '.log',
  '.patch',
  '.so',
  '.tgz',
  '.zip',
]);

function pathSegments(path) {
  return path.split('/');
}

test('tracked tree contains no build or dependency directories', () => {
  const offenders = trackedFiles.filter(path =>
    pathSegments(path).some(segment => forbiddenDirectories.has(segment)));
  assert.deepEqual(offenders, []);
});

test('tracked tree contains no generated payload, log or binary extensions', () => {
  const offenders = trackedFiles.filter(path => forbiddenExtensions.has(extname(path).toLowerCase()));
  assert.deepEqual(offenders, []);
});

test('tracked tree contains no temporary PR workflows or patch helpers', () => {
  const offenders = trackedFiles.filter((path) => {
    const name = basename(path).toLowerCase();
    if (path.startsWith('.github/workflows/')) {
      return /(^|[-_.])(pr\d+|temporary|temp|patch|debug)([-_.]|$)/.test(name);
    }
    if (path.startsWith('.github/scripts/')) {
      return /(^|[-_.])(apply-pr|temporary|temp|patch|payload)([-_.]|$)/.test(name);
    }
    return false;
  });
  assert.deepEqual(offenders, []);
});

test('repository root contains no accidental debug or output files', () => {
  const offenders = trackedFiles.filter((path) => {
    if (path.includes('/')) return false;
    return /^(debug|output|result|temp|tmp)([-_.]|$)/i.test(path);
  });
  assert.deepEqual(offenders, []);
});

test('client templates contain no rendered TODO or debug comments', async () => {
  const clientTemplates = trackedFiles.filter(path =>
    (path.startsWith('apps/web/') || path.startsWith('apps/mobile/'))
      && path.endsWith('.vue'));
  const offenders = [];
  for (const path of clientTemplates) {
    const source = await readFile(path, 'utf8');
    if (/<!--[^>]*(TODO|FIXME|DEBUG|临时调试|开发说明)[^>]*-->/i.test(source)) {
      offenders.push(path);
    }
  }
  assert.deepEqual(offenders, []);
});
