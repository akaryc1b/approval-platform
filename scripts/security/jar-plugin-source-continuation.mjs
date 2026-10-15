import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const sha = value => createHash('sha256').update(value).digest('hex');
const PRIOR_POM = '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629';
const CURRENT_POM = '92694a65be5d42493864470184469b465df8107bfd98388b8b0db15292c54c7f';
const property = '        <maven.jar.version>3.5.1</maven.jar.version>\n';
const addition = `                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-jar-plugin</artifactId>
                    <version>\${maven.jar.version}</version>
                </plugin>
`;

/** Reverse only the two exact JAR additions before unchanged historical source checks. */
export function readPreservedAssemblySource(path, expected) {
  if (typeof path !== 'string' || !/^[A-Za-z0-9_./-]+$/.test(path) || path.startsWith('/') || path.includes('\\')
      || path.split('/').some(part => !part || part === '.' || part === '..'))
    throw new Error('JAR continuation requires a safe relative source path');
  const current = readFileSync(new URL(`../../${path}`, import.meta.url));
  if (sha(current) === expected) return current;
  if (path !== 'pom.xml' || expected !== PRIOR_POM || sha(current) !== CURRENT_POM)
    throw new Error('JAR continuation historical source byte digest mismatch');
  const text = current.toString('utf8');
  if (text.split(property).length !== 2 || text.split(addition).length !== 2)
    throw new Error('JAR continuation ambiguous addition');
  const baseline = readFileSync(new URL('../../docs/operations/assembly-plexus-evidence/candidate-root-pom.xml', import.meta.url));
  if (sha(baseline) !== PRIOR_POM || !Buffer.from(text.replace(property, '').replace(addition, '')).equals(baseline))
    throw new Error('JAR continuation retained source byte digest mismatch');
  return baseline;
}
