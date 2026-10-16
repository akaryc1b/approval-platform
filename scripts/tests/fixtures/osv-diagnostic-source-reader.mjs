// Read historical diagnostic helper bytes without replaying Git or scanner code.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const root = new URL('../../../', import.meta.url);
const scanner = 'scripts/security/m6-pr-e-e4-scan.mjs';
const historicalSha256 = '04d601d05b22a50b8d6bf2888e480ffd1ed9be88ab55bd99b3c094d5e70b0873';
const currentSha256 = 'c96104dda58a7260d98a23e65dc6f3966b1559356bd9d5376dc82f5546351e38';
const pullHelperSha256 = 'dd4494bb59694d0aa03401f6f308e86aec72b1be77a71d5150d693b1a7b55982';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const changes = [
  ["import { pullSemgrepImage } from './semgrep-image-pull.mjs';\n", ''],
  ['pullSemgrepImage(image,{env});', "run('docker',['pull','--quiet',image],{env,timeout:900000});"],
];

// Fresh reads on every invocation; all other paths retain the caller's direct
// byte-hash check. Only this exact reviewed acquisition change is reversible.
export function readOsvDiagnosticSource(path, expected) {
  const bytes = readFileSync(new URL(path, root));
  if (path !== scanner) return bytes;
  if (expected !== historicalSha256) throw new Error('OSV diagnostic expected historical scanner hash mismatch');
  if (bytes.length === 16606 && hash(bytes) === historicalSha256) return bytes;
  if (hash(bytes) !== currentSha256) throw new Error('OSV diagnostic current scanner hash mismatch');
  if (hash(readFileSync(new URL('scripts/security/semgrep-image-pull.mjs', root))) !== pullHelperSha256)
    throw new Error('OSV diagnostic pull helper hash mismatch');
  let source = bytes.toString('utf8');
  for (const [current, historical] of changes) {
    if (source.split(current).length !== 2) throw new Error('OSV diagnostic ambiguous scanner continuation');
    source = source.replace(current, historical);
  }
  const historical = Buffer.from(source);
  if (historical.length !== 16606 || hash(historical) !== historicalSha256)
    throw new Error('OSV diagnostic historical scanner bytes mismatch');
  return historical;
}
