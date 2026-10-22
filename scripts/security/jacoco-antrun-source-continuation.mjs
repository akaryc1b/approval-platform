import { readMain905Source } from './jar-plugin-source-continuation.mjs';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { readPreservedJacocoAntrunSource } from './assembly-plexus-source-continuation.mjs';

const hash = value => createHash('sha256').update(value).digest('hex');
const PRIOR_POM = '2f22ec8cae77cdfb519b81a8e48417fc75632416bcf28989f589f33d39261579';
const CURRENT_POM = 'd36a115813577c7ce997c3adb990cd17247b060071fce25a201d1c73d392042c';
const additions = [
  "                <!-- Keep the inherited AntRun plugin inactive; constrain only its private realm. -->\n                <plugin>\n                    <groupId>org.apache.maven.plugins</groupId>\n                    <artifactId>maven-antrun-plugin</artifactId>\n                    <version>3.1.0</version>\n                    <dependencies>\n                        <dependency>\n                            <groupId>org.codehaus.plexus</groupId>\n                            <artifactId>plexus-utils</artifactId>\n                            <version>3.6.1</version>\n                        </dependency>\n                    </dependencies>\n                </plugin>\n",
  "                <dependencies>\n                    <dependency>\n                        <groupId>org.codehaus.plexus</groupId>\n                        <artifactId>plexus-utils</artifactId>\n                        <version>3.6.1</version>\n                    </dependency>\n                </dependencies>\n"
];

/** Preserve accepted Boot utilities bytes by reversing only the two private Plexus additions. */
export function readPreservedBootUtilitiesSource(path, expected) {
  if (typeof path !== 'string' || !/^[A-Za-z0-9_./-]+$/.test(path) || path.startsWith('/') || path.includes('\\')
      || path.split('/').some(part => !part || part === '.' || part === '..'))
    throw new Error('JaCoCo/AntRun continuation requires a safe relative source path');
  const raw = readMain905Source(path);
  if (hash(raw) === expected) return raw;
  const current = path === 'pom.xml' ? readPreservedJacocoAntrunSource(path, CURRENT_POM) : raw;
  if (hash(current) === expected) return current;
  if (path !== 'pom.xml' || expected !== PRIOR_POM || hash(current) !== CURRENT_POM)
    throw new Error('JaCoCo/AntRun continuation historical source byte digest mismatch');
  let text = current.toString('utf8');
  for (const addition of additions) {
    if (text.split(addition).length !== 2) throw new Error('JaCoCo/AntRun continuation ambiguous additions');
    text = text.replace(addition, '');
  }
  const baseline = readFileSync(new URL('../../docs/operations/boot-utilities-plugin-evidence/candidate-root-pom.xml', import.meta.url));
  if (hash(baseline) !== PRIOR_POM || !Buffer.from(text).equals(baseline))
    throw new Error('JaCoCo/AntRun continuation retained source byte digest mismatch');
  return baseline;
}
