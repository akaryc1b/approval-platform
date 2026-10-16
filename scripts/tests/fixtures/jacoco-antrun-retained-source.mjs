// Verify the exact pair successor before historical contracts consume accepted utilities source.
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { after } from 'node:test';

export function retainedJacocoAntRunSource(source) {
  source = path.resolve(source);
  const raw = readFileSync(path.join(source, 'pom.xml'));
  const digest = createHash('sha256').update(raw).digest('hex');
  if (digest === '2f22ec8cae77cdfb519b81a8e48417fc75632416bcf28989f589f33d39261579')
    return { directory: source, projection: null };
  const temporary = mkdtempSync(path.join(tmpdir(), 'jacoco-antrun-history-'));
  const directory = path.join(temporary, 'accepted-utilities');
  after(() => rmSync(temporary, { recursive: true, force: true }));
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', '-c', String.raw`
import json,runpy,sys
from pathlib import Path
source,destination=Path(sys.argv[1]),Path(sys.argv[2])
jar=runpy.run_path(str(source/'scripts/ci/jar_plugin_retained_source.py'))
jar_projection=None
if jar['is_jar_source'](source):
    jar_projection=jar['materialize_accepted_source'](source,destination.parent/'accepted-assembly')
    source=Path(jar_projection['retainedSourceRoot'])
pair=runpy.run_path(str(source/'scripts/ci/jacoco_antrun_contract.py'))
projection=pair['materialize_retained_source'](source,destination)
if jar_projection:jar['verify_projection'](jar_projection)
pair['verify_projection'](projection)
print(json.dumps({'projection':projection,'jarProjection':jar_projection,'currentDirectory':str(source)}))
`, source, directory], {
    encoding: 'utf8', timeout: 20000, maxBuffer: 2 * 1024 * 1024,
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
  });
  assert.equal(result.status, 0, `Pair historical source projection failed: ${result.stderr}`);
  const { projection, jarProjection, currentDirectory } = JSON.parse(result.stdout);
  assert.equal(projection.suppliedRootPomSha256, jarProjection ? jarProjection.retainedRootPomSha256 : digest);
  assert.ok(['d36a115813577c7ce997c3adb990cd17247b060071fce25a201d1c73d392042c',
    '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629',
    '92694a65be5d42493864470184469b465df8107bfd98388b8b0db15292c54c7f',
    'ddb286b44b1976dd19ad74b06f3ceef86e19d0fbee87dd27dc87985ffb1f7932'].includes(digest));
  if (jarProjection) {
    assert.equal(jarProjection.suppliedRootPomSha256, digest);
    assert.equal(jarProjection.retainedRootPomSha256, '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629');
    assert.equal(jarProjection.sourcePomCount, 28);
    assert.equal(jarProjection.retainedSourceRoot, currentDirectory);
  }
  assert.equal(projection.retainedRootPomSha256, '2f22ec8cae77cdfb519b81a8e48417fc75632416bcf28989f589f33d39261579');
  assert.equal(projection.retainedSourceRoot, directory);
  const view = { directory, projection, jarProjection, currentDirectory };
  return view;
}
