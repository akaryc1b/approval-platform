import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const hash = value => createHash('sha256').update(value).digest('hex');
const PRIOR_POM = 'e5637b8d5752474f9d1940f7b295ef0d3046bc6460391992f1936d4b306f40f2';
const CURRENT_POM = '1569b0356f1bfc122066ced97c29038c3c38e891135ea277e7b7067726782e1a';
const property = '        <maven.compiler.commons-io.version>2.20.0</maven.compiler.commons-io.version>\n';
const dependency = '                    <dependencies>\n                        <dependency>\n                            <groupId>commons-io</groupId>\n                            <artifactId>commons-io</artifactId>\n                            <version>${maven.compiler.commons-io.version}</version>\n                        </dependency>\n                    </dependencies>\n';

/** Return exact historical bytes only after reversing the two pinned Compiler additions. */
export function readPreservedCleanSource(path, expected) {
  const current = readFileSync(new URL(`../../${path}`, import.meta.url));
  if (hash(current) === expected) return current;
  if (path !== 'pom.xml' || expected !== PRIOR_POM || hash(current) !== CURRENT_POM)
    throw new Error('Compiler continuation historical source byte digest mismatch');
  const baseline = readFileSync(new URL('../../docs/operations/compiler-plugin-evidence/baseline-root-pom.xml', import.meta.url));
  const captured = readFileSync(new URL('../../docs/operations/compiler-plugin-evidence/candidate-root-pom.xml', import.meta.url));
  let reversed = current.toString('utf8');
  for (const addition of [property, dependency]) {
    if (reversed.split(addition).length !== 2) throw new Error('Compiler continuation ambiguous addition');
    reversed = reversed.replace(addition, '');
  }
  if (hash(baseline) !== PRIOR_POM || hash(captured) !== CURRENT_POM || !captured.equals(current)
      || !Buffer.from(reversed).equals(baseline)) throw new Error('Compiler continuation retained source byte digest mismatch');
  return baseline;
}
