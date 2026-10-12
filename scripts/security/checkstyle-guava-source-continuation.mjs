import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const hash = value => createHash('sha256').update(value).digest('hex');
const PRIOR_POM = 'd55f730aa8490c60ddf8c3d76d062fd6c4706be4816adf8db8936df15941a6fb';
const CURRENT_POM = 'd95d582e6ccfc91c01780717c83054cd9b52aeffa43c934fbf568f550288cdd5';
const property = '        <maven.checkstyle.guava.version>32.0.1-jre</maven.checkstyle.guava.version>\n';
const dependency = '                    <dependency>\n                        <groupId>com.google.guava</groupId>\n                        <artifactId>guava</artifactId>\n                        <version>${maven.checkstyle.guava.version}</version>\n                    </dependency>\n';

/** Preserve exact accepted Lang bytes through only the pinned Guava addition. */
export function readPreservedCheckstyleSource(path, expected) {
  if (typeof path !== 'string' || !/^[A-Za-z0-9_./-]+$/.test(path) || path.startsWith('/') || path.includes('\\')
      || path.split('/').some(part => !part || part === '.' || part === '..'))
    throw new Error('Checkstyle Guava continuation requires a safe relative source path');
  const current = readFileSync(new URL(`../../${path}`, import.meta.url));
  if (hash(current) === expected) return current;
  if (path !== 'pom.xml' || expected !== PRIOR_POM || hash(current) !== CURRENT_POM)
    throw new Error('Checkstyle Guava continuation historical source byte digest mismatch');
  let reversed = current.toString('utf8');
  for (const addition of [property, dependency]) {
    if (reversed.split(addition).length !== 2) throw new Error('Checkstyle Guava continuation ambiguous addition');
    reversed = reversed.replace(addition, '');
  }
  const baseline = readFileSync(new URL('../../docs/operations/checkstyle-plugin-evidence/candidate-root-pom.xml', import.meta.url));
  if (hash(baseline) !== PRIOR_POM || !Buffer.from(reversed).equals(baseline))
    throw new Error('Checkstyle Guava continuation retained source byte digest mismatch');
  return baseline;
}
