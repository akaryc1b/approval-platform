import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { readPreservedBootHttpSource } from './boot-utilities-source-continuation.mjs';

const hash = value => createHash('sha256').update(value).digest('hex');
const PRIOR_POM = 'd95d582e6ccfc91c01780717c83054cd9b52aeffa43c934fbf568f550288cdd5';
const CURRENT_POM = 'dd3d2a854fd32d53efc2ccbe0efc1c217af46a7824de89c4c30f5bf29249e3c1';
const addition = '                        <dependency>\n                            <groupId>org.apache.httpcomponents.client5</groupId>\n                            <artifactId>httpclient5</artifactId>\n                            <version>5.6.3</version>\n                        </dependency>\n';

/** Preserve the exact accepted Guava source by reversing one pinned Boot addition. */
export function readPreservedGuavaSource(path, expected) {
  if (typeof path !== 'string' || !/^[A-Za-z0-9_./-]+$/.test(path) || path.startsWith('/') || path.includes('\\')
      || path.split('/').some(part => !part || part === '.' || part === '..'))
    throw new Error('Boot HTTP continuation requires a safe relative source path');
  const raw = readFileSync(new URL(`../../${path}`, import.meta.url));
  if (hash(raw) === expected) return raw;
  const current = path === 'pom.xml' ? readPreservedBootHttpSource(path, CURRENT_POM) : raw;
  if (hash(current) === expected) return current;
  if (path !== 'pom.xml' || expected !== PRIOR_POM || hash(current) !== CURRENT_POM)
    throw new Error('Boot HTTP continuation historical source byte digest mismatch');
  const text = current.toString('utf8');
  if (text.split(addition).length !== 2) throw new Error('Boot HTTP continuation ambiguous addition');
  const baseline = readFileSync(new URL('../../docs/operations/checkstyle-guava-evidence/candidate-root-pom.xml', import.meta.url));
  if (hash(baseline) !== PRIOR_POM || !Buffer.from(text.replace(addition, '')).equals(baseline))
    throw new Error('Boot HTTP continuation retained source byte digest mismatch');
  return baseline;
}
