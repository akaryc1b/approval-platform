import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { readPreservedCheckstyleSource } from './checkstyle-guava-source-continuation.mjs';

const hash = value => createHash('sha256').update(value).digest('hex');
const PRIOR_POM = '1569b0356f1bfc122066ced97c29038c3c38e891135ea277e7b7067726782e1a';
const CURRENT_POM = 'd55f730aa8490c60ddf8c3d76d062fd6c4706be4816adf8db8936df15941a6fb';
const property = '        <maven.checkstyle.commons-lang3.version>3.18.0</maven.checkstyle.commons-lang3.version>\n';
const dependency = '                <dependencies>\n                    <dependency>\n                        <groupId>org.apache.commons</groupId>\n                        <artifactId>commons-lang3</artifactId>\n                        <version>${maven.checkstyle.commons-lang3.version}</version>\n                    </dependency>\n                </dependencies>\n';

/** Exact historical bytes; no allowance for any other root or source change. */
export function readPreservedCompilerSource(path, expected) {
  if (typeof path !== 'string' || !/^[A-Za-z0-9_./-]+$/.test(path) || path.startsWith('/') || path.includes('\\')
      || path.split('/').some(part => !part || part === '.' || part === '..'))
    throw new Error('Checkstyle continuation requires a safe relative source path');
  const raw = readFileSync(new URL(`../../${path}`, import.meta.url));
  if (hash(raw) === expected) return raw;
  const current = path === 'pom.xml' ? readPreservedCheckstyleSource(path, CURRENT_POM) : raw;
  if (path !== 'pom.xml' || expected !== PRIOR_POM || hash(current) !== CURRENT_POM)
    throw new Error('Checkstyle continuation historical source byte digest mismatch');
  let reversed = current.toString('utf8');
  for (const addition of [property, dependency]) {
    if (reversed.split(addition).length !== 2) throw new Error('Checkstyle continuation ambiguous addition');
    reversed = reversed.replace(addition, '');
  }
  const baseline = readFileSync(new URL('../../docs/operations/compiler-plugin-evidence/candidate-root-pom.xml', import.meta.url));
  if (hash(baseline) !== PRIOR_POM || !Buffer.from(reversed).equals(baseline))
    throw new Error('Checkstyle continuation retained source byte digest mismatch');
  return baseline;
}
