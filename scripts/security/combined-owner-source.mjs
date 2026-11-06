import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync } from 'node:fs';
import { resolve } from 'node:path';

export const COMBINED_OWNER_POM_SHA256 = '9e91a467ef498a2b20c6cd971a336ea643d9c911f6dff9c651d07742ad778b7b';
export const COMBINED_OWNER_PRIOR_POM_SHA256 = '7aaef759cbdebaafde2383186cbb90861c23f51bd4fe3219614ed0fa8c22a9ab';
export const COMBINED_OWNER_CHANGES = Object.freeze({
  'maven-assembly-plugin': [['io.airlift', 'aircompressor', '2.0.3']],
  'maven-checkstyle-plugin': [['org.codehaus.plexus', 'plexus-archiver', '4.10.4'],
    ['io.airlift', 'aircompressor', '2.0.3'], ['commons-beanutils', 'commons-beanutils', '1.11.0']],
  'maven-site-plugin': [['commons-beanutils', 'commons-beanutils', '1.11.0']],
  'maven-dependency-plugin': [['commons-beanutils', 'commons-beanutils', '1.11.0']],
});
const sha = raw => createHash('sha256').update(raw).digest('hex');
const requireValue = (ok, message) => { if (!ok) throw new Error(`Combined owner source: ${message}`); };

/** Authenticate exactly seven ordinary owner edits; never execute historical probes. */
export function reverseCombinedOwnerPom(raw) {
  requireValue(Buffer.isBuffer(raw) && raw.length < 128 * 1024, 'bounded POM bytes required');
  requireValue(sha(raw) === COMBINED_OWNER_POM_SHA256, 'current POM digest mismatch');
  let text = raw.toString('utf8');
  for (const [owner, dependencies] of Object.entries(COMBINED_OWNER_CHANGES)) {
    const marker = `<artifactId>${owner}</artifactId>`;
    requireValue(text.split(marker).length === 2, `unique owner required: ${owner}`);
    const pos = text.indexOf(marker), start = text.lastIndexOf('<plugin>', pos);
    const end = text.indexOf('</plugin>', pos) + '</plugin>'.length;
    let block = text.slice(start, end);
    const indent = ' '.repeat(owner === 'maven-checkstyle-plugin' ? 16 : 20);
    for (const [group, artifact, version] of dependencies) {
      const addition = `\n${indent}    <dependency>\n${indent}        <groupId>${group}</groupId>\n${indent}        <artifactId>${artifact}</artifactId>\n${indent}        <version>${version}</version>\n${indent}    </dependency>`;
      requireValue(block.split(addition).length === 2, `exact owner dependency required: ${owner}/${artifact}`);
      block = block.replace(addition, '');
    }
    if (owner === 'maven-dependency-plugin') block = block.replace(`\n${indent}<dependencies>\n${indent}</dependencies>`, '');
    if (owner === 'maven-assembly-plugin') {
      requireValue(block.split('<version>3.8.0</version>').length === 2, 'Assembly version');
      block = block.replace('<version>3.8.0</version>', '<version>3.7.1</version>');
    }
    text = text.slice(0, start) + block + text.slice(end);
  }
  const prior = Buffer.from(text);
  requireValue(sha(prior) === COMBINED_OWNER_PRIOR_POM_SHA256, 'exact predecessor digest mismatch');
  return prior;
}

export function requireCombinedOwnerSource(root) {
  const absolute = resolve(root), file = resolve(absolute, 'pom.xml');
  requireValue(realpathSync(absolute) === absolute && realpathSync(file) === file
    && lstatSync(file).isFile() && !lstatSync(file).isSymbolicLink(), 'real source file required');
  const raw = readFileSync(file); reverseCombinedOwnerPom(raw);
  return { currentPomSha256: sha(raw), predecessorPomSha256: COMBINED_OWNER_PRIOR_POM_SHA256,
    editCount: 7, changedOwnerCount: 4, vulnerabilityReproductionExecuted: false };
}
