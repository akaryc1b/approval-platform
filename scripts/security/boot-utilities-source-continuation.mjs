import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const hash = value => createHash('sha256').update(value).digest('hex');
const PRIOR_POM = 'dd3d2a854fd32d53efc2ccbe0efc1c217af46a7824de89c4c30f5bf29249e3c1';
const CURRENT_POM = '2f22ec8cae77cdfb519b81a8e48417fc75632416bcf28989f589f33d39261579';
const addition = "                        <dependency>\n                            <groupId>org.codehaus.plexus</groupId>\n                            <artifactId>plexus-utils</artifactId>\n                            <version>3.6.1</version>\n                        </dependency>\n                        <dependency>\n                            <groupId>org.apache.commons</groupId>\n                            <artifactId>commons-lang3</artifactId>\n                            <version>3.18.0</version>\n                        </dependency>\n";

/** Preserve the exact accepted Boot HTTP source by reversing only two private utility additions. */
export function readPreservedBootHttpSource(path, expected) {
  if (typeof path !== 'string' || !/^[A-Za-z0-9_./-]+$/.test(path) || path.startsWith('/') || path.includes('\\')
      || path.split('/').some(part => !part || part === '.' || part === '..'))
    throw new Error('Boot utilities continuation requires a safe relative source path');
  const current = readFileSync(new URL(`../../${path}`, import.meta.url));
  if (hash(current) === expected) return current;
  if (path !== 'pom.xml' || expected !== PRIOR_POM || hash(current) !== CURRENT_POM)
    throw new Error('Boot utilities continuation historical source byte digest mismatch');
  const text = current.toString('utf8');
  if (text.split(addition).length !== 2) throw new Error('Boot utilities continuation ambiguous additions');
  const baseline = readFileSync(new URL('../../docs/operations/boot-http-plugin-evidence/candidate-root-pom.xml', import.meta.url));
  if (hash(baseline) !== PRIOR_POM || !Buffer.from(text.replace(addition, '')).equals(baseline))
    throw new Error('Boot utilities continuation retained source byte digest mismatch');
  return baseline;
}
