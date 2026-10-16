import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const sha = value => createHash('sha256').update(value).digest('hex');
const PRIOR_POM = '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629';
const CURRENT_POM = '92694a65be5d42493864470184469b465df8107bfd98388b8b0db15292c54c7f';
const LANG_POM = 'ddb286b44b1976dd19ad74b06f3ceef86e19d0fbee87dd27dc87985ffb1f7932';
const property = '        <maven.jar.version>3.5.1</maven.jar.version>\n';
const addition = `                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-jar-plugin</artifactId>
                    <version>\${maven.jar.version}</version>
                </plugin>
`;
const langEdits = [
  ['        <maven.enforcer.commons-lang3.version>3.18.0</maven.enforcer.commons-lang3.version>\n', ''],
  ['        <maven.assembly.commons-lang3.version>3.18.0</maven.assembly.commons-lang3.version>\n', ''],
  ['                <!-- Keep Assembly inactive; constrain its private utility dependencies. -->\n',
    '                <!-- Keep Assembly inactive; constrain only its private Plexus Utils dependency. -->\n'],
  [`                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>\${maven.assembly.commons-lang3.version}</version>
                        </dependency>
`, ''],
  [`                <dependencies>
                    <dependency>
                        <groupId>org.apache.commons</groupId>
                        <artifactId>commons-lang3</artifactId>
                        <version>\${maven.enforcer.commons-lang3.version}</version>
                    </dependency>
                </dependencies>
`, ''],
];

function readSource(path) {
  if (typeof path !== 'string' || !/^[A-Za-z0-9_./-]+$/.test(path) || path.startsWith('/') || path.includes('\\')
      || path.split('/').some(part => !part || part === '.' || part === '..'))
    throw new Error('JAR continuation requires a safe relative source path');
  return readFileSync(new URL(`../../${path}`, import.meta.url));
}

function preservedJarBytes(current) {
  if (sha(current) === CURRENT_POM) return current;
  if (sha(current) !== LANG_POM) throw new Error('JAR continuation historical source byte digest mismatch');
  let text = current.toString('utf8');
  for (const [addition, previous] of langEdits) {
    if (text.split(addition).length !== 2) throw new Error('JAR continuation ambiguous Lang edit');
    text = text.replace(addition, previous);
  }
  const baseline = readFileSync(new URL('../../docs/operations/jar-plugin-evidence/candidate-root-pom.xml', import.meta.url));
  if (sha(baseline) !== CURRENT_POM || !Buffer.from(text).equals(baseline))
    throw new Error('JAR continuation retained Lang predecessor byte digest mismatch');
  return baseline;
}

/** Reverse only the exact two-owner Lang edits to independently retained JAR bytes. */
export function readPreservedJarSource(path, expected) {
  const current = readSource(path);
  if (sha(current) === expected) return current;
  if (path !== 'pom.xml' || expected !== CURRENT_POM)
    throw new Error('JAR continuation historical source byte digest mismatch');
  return preservedJarBytes(current);
}

/** Reverse exact Lang edits when present, then the two exact JAR additions. */
export function readPreservedAssemblySource(path, expected) {
  const current = readSource(path);
  if (sha(current) === expected) return current;
  if (path !== 'pom.xml' || expected !== PRIOR_POM)
    throw new Error('JAR continuation historical source byte digest mismatch');
  const text = preservedJarBytes(current).toString('utf8');
  if (text.split(property).length !== 2 || text.split(addition).length !== 2)
    throw new Error('JAR continuation ambiguous addition');
  const baseline = readFileSync(new URL('../../docs/operations/assembly-plexus-evidence/candidate-root-pom.xml', import.meta.url));
  if (sha(baseline) !== PRIOR_POM || !Buffer.from(text.replace(property, '').replace(addition, '')).equals(baseline))
    throw new Error('JAR continuation retained source byte digest mismatch');
  return baseline;
}
