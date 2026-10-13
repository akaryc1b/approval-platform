// Verify the exact pair successor before historical contracts consume accepted utilities source.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { after } from 'node:test';

const views = new Map();
export function retainedJacocoAntRunSource(source) {
  source = path.resolve(source);
  if (views.has(source)) return views.get(source);
  const raw = readFileSync(path.join(source, 'pom.xml'));
  const digest = createHash('sha256').update(raw).digest('hex');
  if (digest === '2f22ec8cae77cdfb519b81a8e48417fc75632416bcf28989f589f33d39261579')
    return { directory: source, projection: null };
  const temporary = mkdtempSync(path.join(tmpdir(), 'jacoco-antrun-history-'));
  const directory = path.join(temporary, 'accepted-utilities');
  after(() => rmSync(temporary, { recursive: true, force: true }));
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B',
    path.join(source, 'scripts/ci/jacoco_antrun_contract.py'), `--root=${source}`,
    `--materialize-retained-source=${directory}`], {
    encoding: 'utf8', timeout: 20000, maxBuffer: 2 * 1024 * 1024,
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
  });
  assert.equal(result.status, 0, `Pair historical source projection failed: ${result.stderr}`);
  const projection = JSON.parse(result.stdout);
  assert.equal(projection.suppliedRootPomSha256, 'd36a115813577c7ce997c3adb990cd17247b060071fce25a201d1c73d392042c');
  assert.equal(projection.retainedRootPomSha256, '2f22ec8cae77cdfb519b81a8e48417fc75632416bcf28989f589f33d39261579');
  assert.equal(projection.retainedSourceRoot, directory);
  const view = { directory, projection };
  views.set(source, view);
  return view;
}
