import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const sha = value => createHash('sha256').update(value).digest('hex');
const PRIOR_HYGIENE_HELPER = '820cffc7c6202de35c767c445a60361c00e632e8c7163a5284b0e7490e605f0e';
const CURRENT_HYGIENE_HELPER = '76ada2498855ebf319612b92dbaf4fa4fd223d420bd8a10e030ab11051699b07';
const PRIOR_HYGIENE_WORKFLOW = '5e0d736ccc9b5b43156397534eea2e1fc612a9fd9ee653d13e31aed3090c9ea5';
const CURRENT_HYGIENE_WORKFLOW = 'd108d8a4544796bc6a068e1345efb85b16bd7b559a3d202db024f40d7247782d';
const hygieneWorkflowEdits = [
  ["      - name: Verify final tracked-tree hygiene\n        shell: bash\n        run: |\n          m3_status=0\n          node --test scripts/tests/m3-repository-hygiene.test.mjs \\\n            >m3-repository-hygiene.log 2>&1 || m3_status=$?\n          cat m3-repository-hygiene.log\n          exit \"$m3_status\"\n",
    "      - name: Verify final tracked-tree hygiene\n        run: node --test scripts/tests/m3-repository-hygiene.test.mjs\n"],
  ["          path: |\n            m3-repository-hygiene.log\n            m4-sla-calendar-boundary.log\n",
    "          path: |\n            m4-sla-calendar-boundary.log\n"],
];
const hygieneHelperEdits = [
  ["const M3_RETENTION_MANIFEST_SHA256 = '452c856216d8213b7705a5a83b0eaf801c146a782c1a1759fe5a7d99e75f8efc';\n"
    + "const M3_RETENTION_WORKFLOW_BLOB = '4cdfa529d6f5209d8c0dd913b7ae845d2829ce30';\n", ''],
  [`export function readHygieneM3LogRetentionTransition() {
  const raw = readFileSync(new URL('../../docs/m6/hygiene-m3-log-retention-transition.json', import.meta.url));
  requireValue(createHash('sha256').update(raw).digest('hex') === M3_RETENTION_MANIFEST_SHA256,
    'M3 log retention manifest drift');
  return JSON.parse(raw);
}

// Historical fixtures need no new manifest. Only this exact later blob may
// reverse the two reviewed logging edits; all other content remains unaltered.
export function priorM3LogRetentionWorkflow(workflowPath, content) {
  if (blob(content) !== M3_RETENTION_WORKFLOW_BLOB) return content;
  const manifest = readHygieneM3LogRetentionTransition();
  const change = manifest.workflows[workflowPath];
  requireValue(change && change.toBlob === M3_RETENTION_WORKFLOW_BLOB, 'M3 log retention workflow identity drift');
  let prior = content;
  for (const replacement of change.replacements) {
    const pieces = prior.split(replacement.after);
    requireValue(pieces.length === 2, 'M3 log retention replacement count drift');
    prior = pieces.join(replacement.before);
  }
  requireValue(blob(prior) === change.fromBlob, 'M3 log retention prior workflow blob drift');
  return prior;
}

`, ''],
  ['  content = priorM3LogRetentionWorkflow(workflowPath, content);\n', ''],
  ['  const retentionWorkflows = {};\n', ''],
  [`    const projected = priorM3LogRetentionWorkflow(workflowPath, current.content);
    const projectedBlob = blob(projected);
    if (projected !== current.content) retentionWorkflows[workflowPath] = { fromBlob: projectedBlob, toBlob: current.blobSha };
    if (projectedBlob === change.fromBlob) continue;
    requireValue(projectedBlob === change.toBlob && blob(priorJava21Workflow(workflowPath, current.content)) === change.fromBlob,
`, `    if (current.blobSha === change.fromBlob) continue;
    requireValue(current.blobSha === change.toBlob && blob(priorJava21Workflow(workflowPath, current.content)) === change.fromBlob,
`],
  [`    ...(Object.keys(retentionWorkflows).length ? { m3LogRetentionContinuation: {
      schemaVersion: readHygieneM3LogRetentionTransition().schemaVersion,
      manifestSha256: M3_RETENTION_MANIFEST_SHA256,
      workflowBlobs: retentionWorkflows,
      originalTestCommandAndExitStatusRetained: true,
    } } : {}),
`, ''],
];

/** Preserve exact historical helper/workflow bytes and sizes without changing retained captures. */
export function preservedHygieneJava21Source(path, expected, current) {
  const projection = path === 'scripts/security/hygiene-java21-workflow-transition.mjs' && expected === PRIOR_HYGIENE_HELPER
    ? { currentSha256: CURRENT_HYGIENE_HELPER, edits: hygieneHelperEdits, bytes: 3254 }
    : path === '.github/workflows/approval-platform-validation.yml' && expected === PRIOR_HYGIENE_WORKFLOW
      ? { currentSha256: CURRENT_HYGIENE_WORKFLOW, edits: hygieneWorkflowEdits, bytes: 19151 } : null;
  if (!projection || sha(current) === expected) return current;
  if (sha(current) !== projection.currentSha256)
    throw new Error('M3 retention source current byte digest mismatch');
  let prior = current.toString('utf8');
  for (const [addition, previous] of projection.edits) {
    if (prior.split(addition).length !== 2) throw new Error('M3 retention source ambiguous source edit');
    prior = prior.replace(addition, previous);
  }
  const raw = Buffer.from(prior);
  if (sha(raw) !== expected || raw.length !== projection.bytes) throw new Error('M3 retention source predecessor byte digest mismatch');
  return raw;
}

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
