// Current Boot utilities source is verified before historical tests consume its exact predecessor.
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { after } from 'node:test';

const views = new Map();
export function retainedBootUtilitiesSource(source) {
  source = path.resolve(source);
  if (views.has(source)) return views.get(source);
  const temporary = mkdtempSync(path.join(tmpdir(), 'boot-utilities-history-'));
  const directory = path.join(temporary, 'accepted-http');
  after(() => rmSync(temporary, { recursive: true, force: true }));
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B',
    path.join(source, 'scripts/ci/boot_utilities_contract.py'), `--root=${source}`,
    `--materialize-retained-source=${directory}`], {
    encoding: 'utf8', timeout: 20000, maxBuffer: 2 * 1024 * 1024,
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
  });
  assert.equal(result.status, 0, `Boot historical source projection failed: ${result.stderr}`);
  const projection = JSON.parse(result.stdout);
  assert.equal(projection.suppliedRootPomSha256, '2f22ec8cae77cdfb519b81a8e48417fc75632416bcf28989f589f33d39261579');
  assert.equal(projection.retainedRootPomSha256, 'dd3d2a854fd32d53efc2ccbe0efc1c217af46a7824de89c4c30f5bf29249e3c1');
  assert.equal(projection.retainedSourceRoot, directory);
  const view = { directory, projection };
  views.set(source, view);
  return view;
}
