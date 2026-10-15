import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';
import { acceptedE2GraphProjection } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { JAR_PLUGIN_GRAPH, readJarPluginManifest, readJarPluginBaseline, readJarPluginCandidate,
  readJarPluginSourceWitness, readJarPluginReport, readJarPluginAcceptedLineage,
  verifyJarPluginDelta, verifyJarPluginOwnerSubstitution, verifyJarPluginGraphSubstitution }
  from '../security/jar-plugin-graph-transition.mjs';
import { BASE_GRAPH, graphHash, verifyObservabilityGraph, requirePreservedGraph } from '../security/observability-dependency-graph.mjs';
import { osvInputFromE2, buildOsvCoverage } from '../security/osv-scan-coverage.mjs';
import { readCurrentSourceTransition, evaluateCurrentEvidence } from '../security/m6-pr-e-e3-r3a-review-osv-drift.mjs';
import { readServerDependencyRemediationPlan, verifyServerDependencyRemediation }
  from '../security/m6-pr-e-e3-verify-server-dependency-remediation.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const raw = path => readFileSync(join(root, path));
const json = path => JSON.parse(raw(path));
const sha = value => createHash('sha256').update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: graphHash(payload) }; };
const OLD = 'org.apache.maven.plugins:maven-jar-plugin:jar:3.5.0';
const NEW = 'org.apache.maven.plugins:maven-jar-plugin:jar:3.5.1', OWNER = NEW;
const manifest = readJarPluginManifest(), candidate = readJarPluginCandidate(manifest);
const retained = { before: acceptedE2GraphProjection(readJarPluginBaseline(manifest)), after: acceptedE2GraphProjection(candidate),
  beforeRaw: raw(manifest.base.pluginReport.path).toString(), afterRaw: readJarPluginReport(manifest) };
const admit = e2 => verifyObservabilityGraph(e2, acceptedE2GraphProjection(e2), BASE_GRAPH, e2.commitSha);

// These are data-only comparisons against retained actual captures, with no Maven/scanner execution.
test('actual JAR capture changes only its declared owner closure and preserves the complete accepted graph', () => {
  const f = structuredClone(retained), owners = verifyJarPluginOwnerSubstitution(f.beforeRaw, f.afterRaw);
  assert.equal(owners.ownerCount, 17); assert.equal(owners.unchangedOtherPluginOwnerCount, 16);
  assert.equal(owners.before.owners[OLD].length, 17);
  assert.equal(owners.before.occurrences[OLD], 24); assert.equal(owners.after.occurrences[NEW], 26);
  assert.deepEqual(owners.changes, manifest.pluginOwnerChanges);
  assert.deepEqual(verifyJarPluginGraphSubstitution(f.after, f.before, f.beforeRaw, f.afterRaw), f.before);
  assert.deepEqual(verifyJarPluginDelta(f.after), f.before);
  assert.equal(f.after.maven.resolvedPluginCoordinates.length, manifest.inventory.resolvedPluginCoordinateCount);
  assert.equal(f.before.maven.resolvedPluginCoordinates.length, 265);
  assert.ok(f.before.maven.resolvedPluginCoordinates.includes(OLD));
  assert.ok(!f.after.maven.resolvedPluginCoordinates.includes(OLD));
  assert.equal(f.after.maven.components.length, 237); assert.equal(f.after.maven.edges.length, 345);
  assert.equal(f.after.maven.importedBoms.length, 6); assert.equal(f.after.maven.reactorRoots.length, 26);
  for (const [name, projection] of [['prior', f.before], ['current', f.after]]) {
    const input = osvInputFromE2(projection);
    assert.equal(input.packageCount, manifest.osvInput[name].packageCount);
    assert.equal(sha(JSON.stringify(input.scannerInput)), manifest.osvInput[name].inputBytesSha256);
  }
  assert.equal(readJarPluginSourceWitness(manifest).sourcePomFiles.length, 28);
});
for (const [name, mutate] of [
  ['runtime component', p => { p.maven.components[0].version = 'forged'; }],
  ['runtime edge', p => { p.maven.edges.pop(); }],
  ['imported BOM', p => { p.maven.importedBoms.reverse(); }],
  ['scope', p => { p.maven.components[0].scope = 'forged'; }],
  ['license', p => { p.maven.components[0].licenses = ['forged']; }],
  ['Actions', p => { p.githubActions = { forged: true }; }],
  ['limitations', p => { p.limitations = ['forged']; }],
  ['pnpm', p => { p.pnpm = { forged: true }; }],
  ['old coordinate injected', p => { p.maven.resolvedPluginCoordinates.push(OLD); }],
  ['duplicate coordinate', p => { p.maven.resolvedPluginCoordinates.push(NEW); }],
  ['report hash', p => { p.maven.pluginResolutionSha256 = '0'.repeat(64); }],
]) test(`JAR graph rejects ${name}`, () => {
  const f = structuredClone(retained); mutate(f.after);
  assert.throws(() => verifyJarPluginGraphSubstitution(f.after, f.before, f.beforeRaw, f.afterRaw), /JAR (?:global owner union\/report mismatch|undeclared graph change)/);
});
for (const [name, mutate] of [
  ['no substitution', f => f.beforeRaw],
  ['unrelated owner with unchanged union', f => f.afterRaw.replace(/^(   \S+\n)/m, `$1      ${NEW}\n`)],
  ['duplicate owner occurrence', f => f.afterRaw + '\n' + f.afterRaw.match(/   org\.apache\.maven\.plugins:maven-jar-plugin:jar:3\.5\.1\n(?:      [^\n]+\n)+/)[0]],
  ['wrong owner version', f => f.afterRaw.replaceAll(NEW, OLD)],
  ['extra JAR dependency', f => f.afterRaw.replace(`   ${OWNER}\n`, `   ${OWNER}\n      foreign:dependency:jar:1\n`)],
  ['XML mediation drift', f => f.afterRaw.replaceAll('org.codehaus.plexus:plexus-xml:jar:3.0.0', 'org.codehaus.plexus:plexus-xml:jar:3.0.1')],
  ['Boot HTTP regression', f => f.afterRaw.replaceAll('org.apache.httpcomponents.core5:httpcore5:jar:5.4.3', 'org.apache.httpcomponents.core5:httpcore5:jar:5.3.6')],
  ['foreign line', f => f.afterRaw + '\nforged report\n'],
]) test(`JAR owner evidence rejects ${name}`, () => {
  const f = structuredClone(retained), changed = mutate(f); assert.notEqual(changed, f.afterRaw);
  assert.throws(() => verifyJarPluginOwnerSubstitution(f.beforeRaw, changed), /(?:JAR (?:owner|JAR owner|unrelated owner)|Checkstyle (?:report|repeated owner))/);
});
test('JAR receipt binds the current head and complete accepted main1862 lineage without changing release gates', () => {
  const e2 = seal({ ...candidate, commitSha: 'a'.repeat(40) }), receipt = admit(e2);
  assert.equal(receipt.currentE2GraphDigest, JAR_PLUGIN_GRAPH);
  assert.equal(receipt.commitSha, e2.commitSha); assert.equal(receipt.sourceE2ContentSha256, e2.contentSha256);
  assert.deepEqual(receipt.preservedAssemblyLineage, readJarPluginAcceptedLineage(manifest));
  assert.equal(receipt.preservedAssemblyLineage.commitSha, 'f1c99eaa998239044b164d32437a9f3c0765b321');
  assert.equal(receipt.releaseBlocked, true); assert.equal(receipt.findingReviewRequired, true);
  const envelope = { repository: e2.repository, commitSha: e2.commitSha, e2GraphDigest: JAR_PLUGIN_GRAPH,
    e2CurrentContentSha256: e2.contentSha256, e2GraphTransition: receipt };
  assert.deepEqual(requirePreservedGraph(envelope, BASE_GRAPH, e2.commitSha), receipt);
  assert.throws(() => requirePreservedGraph(envelope, BASE_GRAPH), /current verified head/);
  const altered = structuredClone(e2); altered.toolchain = { forged: true };
  assert.throws(() => admit(seal(altered)), /source\/Actions\/toolchain drift/);
  envelope.e2GraphTransition.preservedAssemblyLineage.releaseBlocked = false;
  assert.throws(() => requirePreservedGraph(envelope, BASE_GRAPH, e2.commitSha), /lineage mismatch/);
});

function replaceOnce(text, before, after) {
  assert.equal(text.split(before).length, 2, before); return text.replace(before, after);
}
test('real JAR boundary rejects ancestor, current, inventory and late mutations, then rereads restored bytes', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'jar-admission-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  cpSync(root, directory, { recursive: true, filter: path => path !== join(root, '.git') });
  const graphFile = join(directory, 'scripts/security/observability-dependency-graph.mjs');
  writeFileSync(graphFile, replaceOnce(readFileSync(graphFile, 'utf8'), '    return jarPluginReceipt(e2);',
    '    if (beforeJARReceipt) { const hook = beforeJARReceipt; beforeJARReceipt = undefined; hook(); }\n'
      + '    return jarPluginReceipt(e2);')
    + '\nlet beforeJARReceipt;\nexport function beforeNextJARReceipt(hook) { beforeJARReceipt = hook; }\n');
  // If the new route accidentally replays its ancestor admission this real test fails.
  const oldGraph = join(directory, 'scripts/security/assembly-plexus-graph-transition.mjs');
  let ancestor = replaceOnce(readFileSync(oldGraph, 'utf8'), 'export function readAssemblyPlexusManifest() {',
    "export function readAssemblyPlexusManifest() { throw new Error('Unexpected ancestor admission replay');");
  ancestor = replaceOnce(ancestor, 'export function assemblyPlexusReceipt(identity) {',
    "export function assemblyPlexusReceipt(identity) { throw new Error('Unexpected ancestor receipt replay');");
  writeFileSync(oldGraph, ancestor);
  const graph = await import(pathToFileURL(graphFile).href);
  const invoke = () => graph.verifyObservabilityGraph(candidate, retained.after, BASE_GRAPH, candidate.commitSha);
  const originalReceipt = invoke();
  for (const [label, file] of [
    ['ancestor owner report', 'docs/operations/checkstyle-plugin-evidence/plugin-owner-report.txt'],
    ['ancestor source blob', 'docs/operations/compiler-plugin-evidence/candidate-root-pom.xml'],
    ['ancestor collector', 'docs/operations/boot-http-plugin-evidence/capture.py'],
    ['ancestor executable contract', 'scripts/ci/compiler_plugin_contract.py'],
    ['ancestor witness', 'docs/operations/compiler-plugin-evidence/source-witness.json'],
    ['accepted receipt', manifest.base.lineage.path],
    ['flat inventory', manifest.base.artifactInventory.path],
    ['current manifest', 'docs/operations/jar-plugin-transition.json'],
    ['current candidate evidence', manifest.capture.e2.path],
    ['current root source', 'pom.xml'],
    ['current module source', 'apps/server/pom.xml'],
    ['paired model proof', 'docs/operations/jar-plugin-evidence/model-comparison.json'],
    ['paired model capture', 'docs/operations/jar-plugin-evidence/model-capture-execution.json'],
    ['paired candidate model', 'docs/operations/jar-plugin-evidence/candidate-effective-pom.xml'],
    ['accepted actual R4', manifest.base.serverRemediation.path],
  ]) {
    const target = join(directory, file), original = readFileSync(target);
    writeFileSync(target, Buffer.concat([original, Buffer.from('\n')]));
    assert.throws(invoke, /byte digest mismatch|historical source byte digest mismatch/, label);
    writeFileSync(target, original); assert.deepEqual(invoke(), originalReceipt, `restored ${label}`);
  }
  for (const file of ['pom.xml', manifest.base.lineage.path, manifest.capture.e2.path,
    'docs/operations/boot-http-plugin-evidence/plugin-owner-report.txt']) {
    const target = join(directory, file), original = readFileSync(target); let hookCount = 0, returned;
    graph.beforeNextJARReceipt(() => { hookCount++; writeFileSync(target, Buffer.concat([original, Buffer.from('\n')])); });
    try { assert.throws(() => { returned = invoke(); }, /byte digest mismatch|historical source byte digest mismatch/); }
    finally { writeFileSync(target, original); }
    assert.equal(hookCount, 1); assert.equal(returned, undefined); assert.deepEqual(invoke(), originalReceipt);
  }
  const manifestFile = join(directory, 'docs/operations/jar-plugin-transition.json');
  const originalManifest = readFileSync(manifestFile);
  let probeCount = 0;
  for (const [label, file, mutate] of [
    ['self-consistent shortened inventory', manifest.base.artifactInventory.path, value => {
      value.files.pop(); value.fileCount = value.files.length; value.totalBytes = value.files.reduce((sum, row) => sum + row.bytes, 0);
    }],
    ['self-consistent substituted inventory member', manifest.base.artifactInventory.path, value => {
      const replacement = 'scripts/security/osv-scan-coverage.mjs', bytes = readFileSync(join(directory, replacement));
      value.files[0] = { path: replacement, rawSha256: sha(bytes), bytes: bytes.length };
      value.totalBytes = value.files.reduce((sum, row) => sum + row.bytes, 0);
    }],
    ['stale accepted lineage', manifest.base.lineage.path, value => { value.commitSha = 'f'.repeat(40); }],
    ['semantically invalid inherited lineage', manifest.base.lineage.path, value => {
      value.preservedJacocoAntrunLineage.releaseBlocked = false;
      value.preservedJacocoAntrunLineage = seal(value.preservedJacocoAntrunLineage);
    }],
    ['fabricated accepted base', manifest.base.e2.path, value => { value.commitSha = 'f'.repeat(40); }],
  ]) {
    const target = join(directory, file), original = readFileSync(target), value = JSON.parse(original); mutate(value);
    const resealed = seal(value), editedBytes = Buffer.from(JSON.stringify(resealed, null, 2) + '\n');
    const editedManifest = JSON.parse(originalManifest);
    const key = Object.keys(editedManifest.base).find(key => editedManifest.base[key]?.path === file);
    editedManifest.base[key] = { path: file, rawSha256: sha(editedBytes), contentSha256: resealed.contentSha256 };
    if (label === 'fabricated accepted base') editedManifest.base.sourceHead = resealed.commitSha;
    const sealedManifest = seal(editedManifest), manifestBytes = Buffer.from(JSON.stringify(sealedManifest, null, 2) + '\n');
    writeFileSync(target, editedBytes); writeFileSync(manifestFile, manifestBytes);
    try {
      assert.throws(invoke, /byte digest mismatch/, label);
      // Even resealing the candidate cannot select different predecessor trust
      // anchors. Only the two candidate pins change in this isolated module.
      const probeFile = join(directory, 'scripts/security/jar-plugin-candidate-seal-probe.mjs');
      const source = readFileSync(join(directory, 'scripts/security/jar-plugin-graph-transition.mjs'), 'utf8')
        .replace(/const JAR_PLUGIN_MANIFEST_SHA256 = '[^']+';/, `const JAR_PLUGIN_MANIFEST_SHA256 = '${sha(manifestBytes)}';`)
        .replace(/const CONTENT = '[^']+';/, `const CONTENT = '${sealedManifest.contentSha256}';`);
      writeFileSync(probeFile, source);
      const probe = await import(pathToFileURL(probeFile).href + `?case=${probeCount++}`);
      assert.throws(() => probe.readJarPluginManifest(), /accepted main1862 trust anchor mismatch|transition identity mismatch/, label);
    }
    finally { writeFileSync(target, original); writeFileSync(manifestFile, originalManifest); }
    assert.deepEqual(invoke(), originalReceipt, `restored ${label}`);
  }
  for (const [label, mutate] of [
    ['fabricated accepted tree', value => { value.base.sourceTree = 'f'.repeat(40); }],
    ['fabricated accepted run number', value => { value.base.sourceRunNumber = 1861; }],
    ['fabricated accepted run id', value => { value.base.sourceRunId = 1; }],
    ['waived release gate', value => { value.releaseBlocked = false; }],
    ['waived finding review', value => { value.findingReviewRequired = false; }],
  ]) {
    const value = JSON.parse(originalManifest); mutate(value);
    const edited = seal(value), editedBytes = Buffer.from(JSON.stringify(edited, null, 2) + '\n');
    const probeFile = join(directory, 'scripts/security/jar-plugin-candidate-seal-probe.mjs');
    const source = readFileSync(join(directory, 'scripts/security/jar-plugin-graph-transition.mjs'), 'utf8')
      .replace(/const JAR_PLUGIN_MANIFEST_SHA256 = '[^']+';/, `const JAR_PLUGIN_MANIFEST_SHA256 = '${sha(editedBytes)}';`)
      .replace(/const CONTENT = '[^']+';/, `const CONTENT = '${edited.contentSha256}';`);
    writeFileSync(manifestFile, editedBytes); writeFileSync(probeFile, source);
    try {
      const probe = await import(pathToFileURL(probeFile).href + `?case=${probeCount++}`);
      assert.throws(() => probe.readJarPluginManifest(), /transition identity mismatch/, label);
    } finally { writeFileSync(manifestFile, originalManifest); }
    assert.deepEqual(invoke(), originalReceipt, `restored ${label}`);
  }
});

// Explicit synthetic checkout/runtime observations. The actual capture is precommit, not current CI evidence.
function runtimeFixture() {
  const transition = readCurrentSourceTransition(), current = structuredClone(candidate);
  return { contract: json('docs/m6/m6-pr-e-e3-r3a-osv-drift-review.json'), transition,
    commitSha: current.commitSha, currentE2: current,
    checkout: { expectedHeadSha: current.commitSha, checkedOutSha: current.commitSha,
      expectedHeadTreeSha: 'a'.repeat(40), checkedOutTreeSha: 'a'.repeat(40), exactTreeMatches: true, trackedWorktreeClean: true },
    runtimeComponents: [{ groupId: 'org.apache.tomcat.embed', artifactId: 'tomcat-embed-core', version: '11.0.26',
      type: 'jar', scope: 'compile', path: ['io.github.akaryc1b.approval:approval-server:0.1.0-SNAPSHOT',
        'org.springframework.boot:spring-boot-starter-web:4.0.8', 'org.springframework.boot:spring-boot-starter-tomcat:4.0.8',
        'org.springframework.boot:spring-boot-starter-tomcat-runtime:4.0.8', 'org.apache.tomcat.embed:tomcat-embed-core:11.0.26'] }],
    jarEvidence: { jar: transition.tomcat.jarFileName, jarSha256: transition.tomcat.jarSha256,
      jarBytes: transition.tomcat.jarBytes, entryCount: 1615, vulnerableCloudMembershipEntryCount: 0, vulnerableCloudMembershipEntries: [] },
    sourceMatches: [], pluginReport: retained.afterRaw };
}
test('JAR R3A still proves current runtime/JAR/source/owner observations and retains historical unresolved HTTP', () => {
  const input = runtimeFixture(), result = evaluateCurrentEvidence(input);
  assert.equal(result.currentE2GraphDigest, JAR_PLUGIN_GRAPH);
  assert.equal(result.currentHttpcoreObservation.package.version, '5.4.3');
  assert.equal(result.findings[0].package.version, '5.3.6'); assert.equal(result.findings[0].disposition, 'UNRESOLVED');
  assert.equal(result.decision.currentOsvTotalsClaimed, false); assert.equal(result.decision.releaseBlocked, true);
  assert.deepEqual(result.historicalReview.findings, input.contract.findings);
});
for (const [name, mutate] of [
  ['runtime path', x => { x.runtimeComponents[0].path[1] = 'forged:middle:1'; }],
  ['JAR identity', x => { x.jarEvidence.jarSha256 = '0'.repeat(64); }],
  ['production marker', x => { x.sourceMatches.push({ marker: 'CLOUD_MEMBERSHIP_SERVICE' }); }],
  ['plugin owner', x => { x.pluginReport = x.pluginReport.replaceAll('spring-boot-maven-plugin:jar:4.0.8', 'spring-boot-maven-plugin:jar:4.0.9'); }],
  ['checkout', x => { x.checkout.trackedWorktreeClean = false; }],
]) test(`JAR R3A still rejects ${name}`, () => {
  const input = runtimeFixture(); mutate(input); assert.throws(() => evaluateCurrentEvidence(input));
});

// SYNTHETIC UNIT-ONLY all-scanner envelope. No scanner execution or finding absence is claimed.
function scannerFixture(returnedAlias = false) {
  const e2 = structuredClone(candidate), baseline = json('docs/m6/m6-pr-e-e4-scanner-baseline.json');
  const input = osvInputFromE2(e2), inputPath = '/synthetic-unit-only-assembly-input.json';
  const selected = [...input.lookup.values()].find(row => row.package.name === 'org.apache.maven.plugins:maven-jar-plugin' && row.package.version === '3.5.1');
  const advisory = 'GHSA-synthetic-unit-only-assembly';
  const finding = { sourceClass: 'E4_OSV_SCANNER', findingId: sha(['OSV', advisory, selected.package.ecosystem,
    selected.package.name, selected.package.version].join('\0')), upstreamFindingId: advisory,
    aliases: returnedAlias ? ['CVE-2026-40976'] : [], package: selected.package,
    componentRefs: [...selected.componentRefs].sort(), scopes: [...selected.scopes].sort(), upstreamSeverity: [], fixedVersions: [] };
  const raw = { results: [{ source: { path: inputPath, type: 'lockfile' }, packages: input.scannerInput.results[0].packages.map(({ package: identity }) => ({
    package: identity, vulnerabilities: identity.name === selected.package.name && identity.version === selected.package.version ? [{ id: advisory }] : [] })) }] };
  const coverage = buildOsvCoverage(raw, e2, { head: e2.commitSha, graphDigest: JAR_PLUGIN_GRAPH,
    inputBytes: JSON.stringify(input.scannerInput), inputPath, findings: [finding], exitStatus: 1,
    stderr: `Starting filesystem walk for root: /\nScanned ${inputPath} file and found ${input.packageCount} packages\nEnd status: 0 dirs visited, 1 inodes visited, 1 Extract calls, 1ms elapsed, 1ms wall time\n` });
  const scanners = Object.fromEntries(Object.entries(baseline.scanners).map(([name, scanner]) => [name, {
    scanCompleted: true, version: scanner.version, sourceCommit: scanner.sourceCommit, rawReportRetained: false, findingCount: 0, findings: [],
  }]));
  Object.assign(scanners.osv, { coverage, findings: [finding], findingCount: 1, inputPackageCount: input.packageCount, binarySha256: 'a'.repeat(64) });
  scanners.gitleaks.candidateSecretMaterialRetained = false; scanners.semgrep.sourceSnippetRetained = false;
  return seal({ schemaVersion: 'M6_PR_E_E4_SCANNER_EVIDENCE_V1', repository: baseline.repository,
    commitSha: e2.commitSha, e2CurrentEvidence: e2, e2CurrentContentSha256: e2.contentSha256,
    e2GraphDigest: JAR_PLUGIN_GRAPH, e2GraphTransition: admit(e2),
    checkout: { expectedHeadSha: e2.commitSha, checkedOutSha: e2.commitSha, expectedHeadTreeSha: 'a'.repeat(40),
      checkedOutTreeSha: 'a'.repeat(40), exactTreeMatches: true, trackedWorktreeClean: true },
    scannerBaselineSourceHead: baseline.sourceHead, scanners, totalFindingCount: 1,
    allScannersCompleted: true, rawScannerReportsRetained: false, candidateSecretMaterialRetained: false,
    authoritativeGitHubInventoryStillUnavailable: true, workstreamReleaseBlocked: true });
}
const verifyServer = e4 => verifyServerDependencyRemediation(e4, readServerDependencyRemediationPlan(), { expectedCommitSha: e4.commitSha });
test('JAR server continuation requires fresh full coverage and preserves unrelated current findings', () => {
  const e4 = scannerFixture(), original = structuredClone(e4), result = verifyServer(e4);
  assert.equal(result.currentE2GraphDigest, JAR_PLUGIN_GRAPH); assert.equal(result.remediatedFindings.length, 2);
  assert.equal(result.releaseBlocked, true); assert.equal(result.findingReviewRequired, true);
  assert.equal(e4.scanners.osv.coverage.targets.length, manifest.osvInput.current.packageCount); assert.deepEqual(e4, original);
  assert.equal(e4.scanners.osv.findings[0].package.version, '3.5.1');
});
for (const [name, mutate] of [
  ['partial scanners', e => { e.allScannersCompleted = false; }],
  ['incomplete scanner', e => { e.scanners.semgrep.scanCompleted = false; }],
  ['missing fixed target', e => { e.scanners.osv.coverage.targets = e.scanners.osv.coverage.targets.filter(row => row.package.name !== 'org.springframework.boot:spring-boot'); e.scanners.osv.coverage = seal(e.scanners.osv.coverage); }],
  ['stale input identity', e => { e.scanners.osv.coverage.inputBytesSha256 = manifest.osvInput.prior.inputBytesSha256; e.scanners.osv.coverage = seal(e.scanners.osv.coverage); }],
  ['stale head', e => { e.checkout.expectedHeadSha = 'f'.repeat(40); }],
  ['forged inherited receipt', e => { e.e2GraphTransition.preservedAssemblyLineage.releaseBlocked = false; e.e2GraphTransition = seal(e.e2GraphTransition); }],
]) test(`JAR server continuation still rejects ${name}`, () => {
  const e4 = scannerFixture(); mutate(e4); assert.throws(() => verifyServer(seal(e4)));
});
test('JAR server continuation rejects a returned remediated alias even on another current target', () => {
  assert.throws(() => verifyServer(scannerFixture(true)), /advisory is still present/);
});
