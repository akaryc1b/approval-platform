import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const sha = value => createHash('sha256').update(value).digest('hex');
const PRIOR_POM = 'd36a115813577c7ce997c3adb990cd17247b060071fce25a201d1c73d392042c';
const CURRENT_POM = '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629';
const addition = `                <!-- Keep Assembly inactive; constrain only its private Plexus Utils dependency. -->
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-assembly-plugin</artifactId>
                    <version>3.7.1</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.codehaus.plexus</groupId>
                            <artifactId>plexus-utils</artifactId>
                            <version>4.0.3</version>
                        </dependency>
                    </dependencies>
                </plugin>
`;

/** Reverse only the exact Assembly addition before unchanged historical source checks. */
export function readPreservedJacocoAntrunSource(path, expected) {
  if (typeof path !== 'string' || !/^[A-Za-z0-9_./-]+$/.test(path) || path.startsWith('/') || path.includes('\\')
      || path.split('/').some(part => !part || part === '.' || part === '..'))
    throw new Error('Assembly continuation requires a safe relative source path');
  const current = readFileSync(new URL(`../../${path}`, import.meta.url));
  if (sha(current) === expected) return current;
  if (path !== 'pom.xml' || expected !== PRIOR_POM || sha(current) !== CURRENT_POM)
    throw new Error('Assembly continuation historical source byte digest mismatch');
  const text = current.toString('utf8');
  if (text.split(addition).length !== 2) throw new Error('Assembly continuation ambiguous addition');
  const baseline = readFileSync(new URL('../../docs/operations/jacoco-antrun-plugin-evidence/candidate-root-pom.xml', import.meta.url));
  if (sha(baseline) !== PRIOR_POM || !Buffer.from(text.replace(addition, '')).equals(baseline))
    throw new Error('Assembly continuation retained source byte digest mismatch');
  return baseline;
}
