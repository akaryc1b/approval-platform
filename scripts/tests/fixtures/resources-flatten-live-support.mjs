import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, readFileSync, realpathSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

export const resultMarker = 'RESOURCES_FLATTEN_RESULT';
export const portableMarker = 'RESOURCES_FLATTEN_PORTABLE_RESULT';
export const resultStatus = 'PASSED_CURRENT_RUNTIME_PAIRED_GOALS_AND_COMPLETE_SYMBOLIC_LINKAGE';
export const maximumReceiptBytes = 16 * 1024 * 1024;
export const protocolLimits = Object.freeze({ bytes: 1024 * 1024, compressedBytes: 256 * 1024,
  rows: 2000, totalBytes: 8 * 1024 * 1024, totalRows: 40000 });
export const cases = Object.freeze(['resources-success', 'resources-repeat', 'resources-failure',
  'sdk-flatten', 'sdk-clean', 'demo-flatten', 'demo-repeat', 'demo-clean', 'flatten-failure', 'sdk-production-context']);
const variants = ['baseline', 'candidate'];
const resources = 'org.apache.maven.plugins:maven-resources-plugin:3.4.0';
const flatten = 'org.codehaus.mojo:flatten-maven-plugin:1.7.3';
const resolve = 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins';
const sharedHelpers = Object.freeze({
  "scripts/tests/fixtures/checkstyle_live_support.py": "35ec676d427cc424e4cadbded2dc99f42500623d9d99824d7d423dc5d40449c2",
  "scripts/tests/fixtures/run_boot_live_compatibility.py": "5257158869e64f0dd82dc680a93db7b55196225f454d1efbc9933276210e62be",
  "scripts/tests/fixtures/run_boot_http_compatibility.py": "b57ef86a8ed16b6a481d534dc64720016621125824988ddeaf7f005c86dfec30",
  "scripts/tests/fixtures/run_boot_goal_compatibility.py": "2cc13bcc31b171cf6f3fc6f50516895a64db05bc29dc31f868c41991a2c03c4c",
  "scripts/tests/fixtures/run_boot_utilities_compatibility.py": "b4da6836364caac77760b32740e485ea23853ffd16fd77f4bc323c31caf7e273",
  "scripts/tests/fixtures/jar_plugin_storage.py": "e0f9f0f518cda8ff5a9358d54db9cfcd7d797a64dadeb652936c4a3780653770",
  "scripts/tests/fixtures/assembly_plexus_inventory.py": "c32e5892dd495e8ac1cd6c5127781c549597edde4762d60f605f6c8ff061783e",
  "scripts/ci/boot_utilities_contract.py": "6ef0328fc5fb015e9a9f13a7999236275aaead5d0364fa0ed7ec2296bbced39f",
  "scripts/ci/jacoco_antrun_contract.py": "a3ea83d6d5127cfa32cdac1ed5ed62cdc48dd1d468a40a767570e6cf3edbae9e"
});
const hash = data => createHash('sha256').update(data).digest('hex');
const sha256 = value => assert.match(value, /^[a-f0-9]{64}$/);
const hashMap = value => {
  assert.ok(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length > 0);
  Object.values(value).forEach(sha256);
};
const exactKeys = (value, keys) => assert.deepEqual(Object.keys(value).sort(), [...keys].sort());
const canonical = value => {
  assert.equal(typeof value, 'string'); assert.ok(path.isAbsolute(value));
  assert.equal(path.normalize(value), value); assert.doesNotMatch(value, /[\s\x00-\x1f\x7f]/);
  return value;
};
const inside = (file, root) => {
  canonical(file); canonical(root);
  assert.ok(file.startsWith(root + '/'), 'owned descendant path required'); return file;
};
function safeRead(file, limit = maximumReceiptBytes) {
  canonical(file); const stat = lstatSync(file);
  assert.ok(stat.isFile() && !stat.isSymbolicLink() && stat.size <= limit);
  assert.equal(realpathSync(file), file, 'receipt/input ancestor symlink rejected');
  const bytes = readFileSync(file); assert.ok(bytes.length <= limit); return bytes;
}
const defaultIO = { read: safeRead, exists: existsSync, isDirectory: file => {
  const stat = lstatSync(file); return stat.isDirectory() && !stat.isSymbolicLink() && realpathSync(file) === file;
} };
function base64(value, limit) {
  assert.equal(typeof value, 'string');
  assert.ok(value.length <= 4 * Math.ceil(limit / 3));
  assert.match(value, /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/);
  const bytes = Buffer.from(value, 'base64'); assert.ok(bytes.length <= limit);
  assert.equal(bytes.toString('base64'), value); return bytes;
}

// Pure command selection. Importing or planning never starts Python or a JVM.
export function liveInvocation(environment, fixtureDirectory) {
  const ci = environment.GITHUB_ACTIONS === 'true';
  const optedIn = environment.RESOURCES_FLATTEN_COMPATIBILITY === 'true';
  if (!ci && !optedIn) return { enabled: false, ci };
  canonical(fixtureDirectory);
  const seal = environment.RESOURCES_FLATTEN_COMPATIBILITY_SEAL;
  if (!ci) { assert.ok(seal, 'local offline execution requires an explicitly prepared fresh seal'); canonical(seal); }
  const fixture = fixtureDirectory + (ci ? '/resources_flatten_portable.py' : '/resources_flatten_compatibility.py');
  return { enabled: true, ci, executable: '/usr/bin/timeout',
    arguments: ['--signal=TERM', '--kill-after=10s', ci ? '2090s' : '1820s',
      'python3', '-I', '-S', '-B', fixture, ...(ci ? ['--execute'] : ['--execute-seal', seal])],
    options: { encoding: 'utf8', timeout: ci ? 2110000 : 1840000, maxBuffer: 4 * 1024 * 1024,
      env: { ...environment, RESOURCES_FLATTEN_COMPATIBILITY: 'true' } } };
}

export function readReceipt(stdout, marker, { minimumMtimeMs = 0 } = {}) {
  assert.ok([resultMarker, portableMarker].includes(marker));
  const rows = stdout.split('\n').filter(line => line.startsWith(marker + ' '));
  assert.equal(rows.length, 1, `exactly one ${marker} pointer required`);
  const pointer = JSON.parse(rows[0].slice(marker.length + 1)); exactKeys(pointer, ['path', 'status']);
  canonical(pointer.path); assert.ok(pointer.path.endsWith('.json'));
  const stat = lstatSync(pointer.path); assert.ok(stat.mtimeMs >= minimumMtimeMs, 'receipt predates this invocation');
  const bytes = safeRead(pointer.path); const text = bytes.toString('utf8');
  assert.deepEqual(Buffer.from(text), bytes, 'valid receipt UTF-8 required');
  const value = JSON.parse(text); assert.ok(value && typeof value === 'object' && !Array.isArray(value));
  assert.equal(pointer.status, value.status);
  return { value, path: pointer.path, sha256: hash(bytes), json: text.replace(/[\r\n\t]/g, '') };
}

export function retainReceipts(stdout, ci, emit = console.log, options = {}) {
  const records = {}, pointers = {}; let firstError;
  // Read each available marker and retain each complete PASSED or FAILED record,
  // even if the other pointer is malformed. Status assertions happen afterwards.
  for (const marker of [resultMarker, ...(ci ? [portableMarker] : [])]) {
    if (!stdout.split('\n').some(line => line.startsWith(marker + ' '))) continue;
    try {
      const record = readReceipt(stdout, marker, options);
      emit(marker + ' ' + record.json); records[marker] = record.value;
      pointers[marker] = { path: record.path, sha256: record.sha256 };
    } catch (error) { firstError ??= error; }
  }
  Object.defineProperty(records, 'pointers', { value: pointers });
  if (firstError) throw firstError;
  if (ci && pointers[resultMarker] && pointers[portableMarker])
    assert.notEqual(pointers[resultMarker].path, pointers[portableMarker].path, 'distinct result pointers required');
  return records;
}

export function commandComplete(command, expectedExit, timeout) {
  assert.ok(command); assert.equal(command.status, 'EXITED'); assert.equal(command.exitStatus, expectedExit);
  assert.equal(command.captureComplete, true); assert.equal(command.processGroupCleaned, true);
  assert.ok(Array.isArray(command.command) && command.command.every(v => typeof v === 'string'));
  assert.ok(Number.isFinite(command.elapsedSeconds) && command.elapsedSeconds >= 0 && command.elapsedSeconds <= timeout + 5);
  assert.ok(Number.isSafeInteger(command.capturedBytes) && command.capturedBytes >= 0 && command.capturedBytes <= 16 * 1024 * 1024);
  sha256(command.stdoutSha256); sha256(command.stderrSha256);
}

export function expectedTerminals(name) {
  assert.ok(cases.includes(name));
  const terminal = (owner, goal, execution, project, outcome = 'MojoSucceeded') =>
    [owner, owner === 'resources' ? '3.4.0' : '1.7.3', goal, execution, project, outcome];
  if (name === 'resources-success' || name === 'resources-repeat') return [
    terminal('resources', 'resources', 'main-fixture', '.'), terminal('resources', 'testResources', 'test-fixture', '.'),
    terminal('resources', 'copy-resources', 'copy-fixture', '.')];
  if (name === 'resources-failure') return [terminal('resources', 'copy-resources', 'bad-encoding', '.', 'MojoFailed')];
  if (name === 'sdk-flatten') return [terminal('flatten', 'flatten', 'flatten', 'sdk'),
    terminal('flatten', 'flatten', 'flatten', 'sdk', 'MojoFailed')];
  if (name === 'sdk-clean') return Array.from({ length: 2 }, () => terminal('flatten', 'clean', 'flatten-clean', 'sdk'));
  if (name === 'demo-flatten' || name === 'demo-repeat') return ['.', 'ordinary', 'sdk'].map(p => terminal('flatten', 'flatten', 'flatten', p));
  if (name === 'demo-clean') return ['.', 'ordinary', 'sdk'].flatMap(p => Array.from({ length: 2 }, () => terminal('flatten', 'clean', 'flatten.clean', p)));
  if (name === 'flatten-failure') return [terminal('flatten', 'flatten', 'flatten', 'sdk', 'MojoFailed')];
  return [terminal('flatten', 'flatten', 'flatten', 'integrations/host-sdk'), terminal('flatten', 'clean', 'flatten-clean', 'integrations/host-sdk')];
}

export function decodeProtocol(value) {
  exactKeys(value, ['encoding', 'bytes', 'sha256', 'rowCount', 'data']); assert.equal(value.encoding, 'gzip+base64');
  assert.ok(Number.isSafeInteger(value.bytes) && value.bytes > 0 && value.bytes <= protocolLimits.bytes);
  assert.ok(Number.isSafeInteger(value.rowCount) && value.rowCount > 0 && value.rowCount <= protocolLimits.rows);
  sha256(value.sha256);
  const zipped = base64(value.data, protocolLimits.compressedBytes);
  const bytes = gunzipSync(zipped, { maxOutputLength: protocolLimits.bytes });
  assert.equal(bytes.length, value.bytes); assert.equal(hash(bytes), value.sha256);
  const text = bytes.toString('utf8'); assert.deepEqual(Buffer.from(text), bytes); assert.ok(text.endsWith('\n'));
  const rows = text.slice(0, -1).split('\n'); assert.equal(rows.length, value.rowCount);
  assert.ok(rows.every(row => row.startsWith('RF_') && !/[\r\ufffd]/.test(row)));
  assert.ok(!rows.some(row => row.startsWith('RF_FAILURE')), 'fatal observer failure');
  const allowed = new Set(['RF_PLAN', 'RF_REALM_URL', 'RF_EVENT', 'RF_OUTPUT', 'RF_NATURAL_LOAD', 'RF_CONTROLLED_LOAD',
    'RF_SYMBOLIC_LOAD', 'RF_AFTER_LOAD', 'RF_TYPE', 'RF_CONSUMER', 'RF_MEMBER', 'RF_TYPE_LINK', 'RF_MISSING_MEMBER',
    'RF_LINKAGE_COMPLETE', 'RF_CONTROLLED_PASS', 'RF_GOALS_COMPLETE', 'RF_CONTROLLED_COMPLETE', 'RF_COMPLETE', 'RF_SESSION_COMPLETE']);
  assert.ok(rows.every(row => allowed.has(row.split('\t')[0])), 'known complete protocol rows required'); return rows;
}

function artifact(repository, coordinate) {
  assert.match(coordinate, /^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:jar:[A-Za-z0-9_.-]+$/);
  const [group, name, kind, version] = coordinate.split(':');
  return `${repository}/${group.replaceAll('.', '/')}/${name}/${version}/${name}-${version}.${kind}`;
}
function fileUri(value) {
  const url = new URL(value); assert.equal(url.protocol, 'file:'); assert.equal(url.hostname, '');
  assert.equal(url.search, ''); assert.equal(url.hash, ''); return canonical(fileURLToPath(url));
}

export function validateProtocol(observed, inventory, variant, name, pins, config) {
  const rows = decodeProtocol(observed.protocol), terms = expectedTerminals(name);
  const owner = name.startsWith('resources-') ? 'resources' : 'flatten';
  const replay = ['resources-success', 'sdk-flatten', 'demo-flatten'].includes(name) ? 'api' : 'none';
  const version = variant === 'baseline' ? '4.0.2' : '4.0.3';
  const select = prefix => rows.filter(row => row.startsWith(prefix + '\t')).map(row => row.split('\t'));
  const once = line => { assert.equal(rows.filter(row => row === line).length, 1, `exact protocol receipt: ${line}`); return rows.indexOf(line); };
  const linkage = inventory.effectiveLinkageRows;
  assert.ok(Array.isArray(linkage) && linkage.length > 0 && new Set(linkage).size === linkage.length);
  const linkageHash = hash(linkage.join('\n') + '\n');
  once(`RF_PLAN\t${owner}\t${version}\t${replay}\t${terms.length}\t${hash(terms.map(t => t.join('\t')).join('\n') + '\n')}\t${linkageHash}`);
  assert.equal(select('RF_PLAN').length, 1);
  assert.equal(observed.actualSelectedTerminalCount, terms.length); assert.equal(observed.states.length, terms.length);
  assert.equal(observed.sessionComplete, true); assert.equal(observed.symbolicResolutionIsInvocationClaim, false);
  assert.equal(observed.naturalLoadingIsMemberInvocationClaim, false);
  const realms = Object.keys(observed.nativeRealms); assert.ok(realms.length >= 1 && realms.length <= 8);
  const expectedUrls = pins.owners[owner][variant].filter(c => !pins.coreExportedArtifacts.includes(c.split(':').slice(0, 2).join(':')))
    .map(c => artifact(config.repository, c)).sort();
  const rawRealmRows = select('RF_REALM_URL');
  assert.deepEqual([...new Set(rawRealmRows.map(r => r[1]))].sort(), [...realms].sort());
  for (const realm of realms) {
    assert.match(realm, /^[a-f0-9]+$/); const value = observed.nativeRealms[realm];
    assert.ok(value.name.startsWith(`ClassRealm[plugin>${pins.owners[owner].coordinate.replace(':jar:', ':')},`));
    assert.equal(new Set(value.urls).size, value.urls.length); assert.deepEqual([...value.urls].sort(), expectedUrls);
    assert.deepEqual(rawRealmRows.filter(r => r[1] === realm).map(r => {
      assert.equal(r.length, 4); assert.equal(r[2], value.name); return fileUri(r[3]);
    }), value.urls);
  }
  const events = select('RF_EVENT'); assert.equal(events.length, terms.length * 2);
  for (const [index, term] of terms.entries()) {
    const state = observed.states[index], start = events[2 * index], end = events[2 * index + 1];
    assert.equal(state.index, index); assert.deepEqual(state.expected, term);
    for (const [event, outcome, field] of [[start, 'MojoStarted', 'before'], [end, term[5], 'after']]) {
      assert.equal(event.length, 11); assert.equal(event[1], String(index)); assert.equal(event[2], outcome);
      assert.deepEqual(event.slice(3, 8), term.slice(0, 5));
      assert.ok(realms.includes(event[8]) || outcome === 'MojoStarted' && event[8] === 'PENDING');
      assert.deepEqual(JSON.parse(base64(event[10], 262144).toString('utf8')), state[field]);
    }
    assert.equal(start[9], end[9]); assert.ok(start[8] === 'PENDING' || start[8] === end[8]);
    assert.equal(state.realmAtStarted, start[8]); assert.equal(state.realmAtTerminal, end[8]);
    assert.equal(state.before.project.projectIdentity, state.after.project.projectIdentity);
    assert.equal(state.before.project.projectId, state.after.project.projectId);
    if (index > 0 && terms[index - 1][4] === term[4]) assert.deepEqual(observed.states[index - 1].after, state.before);
    if (owner === 'resources' || term[2] === 'clean' || term[5] === 'MojoFailed') assert.deepEqual(state.before, state.after);
    else {
      assert.notEqual(state.before.project.originalModelIdentity, state.after.project.originalModelIdentity);
      assert.notEqual(state.before.project.projectFile, state.after.project.projectFile);
    }
  }
  const snapshots = select('RF_OUTPUT');
  const wantedOutputIndices = terms.flatMap((term, i) => term[2] === 'flatten' && term[5] === 'MojoSucceeded' ? [i] : []);
  assert.deepEqual(snapshots.map(row => Number(row[1])), wantedOutputIndices);
  assert.equal(observed.outputSnapshotCount, snapshots.length);
  for (const row of snapshots) {
    assert.equal(row.length, 7); const index = Number(row[1]); assert.equal(row[2], terms[index][4]);
    assert.equal(base64(row[3], 8192).toString('utf8'), observed.states[index].after.project.projectFile);
    const bytes = base64(row[6], 262144); assert.equal(String(bytes.length), row[4]); assert.equal(hash(bytes), row[5]);
  }
  const goalsComplete = once(`RF_GOALS_COMPLETE\t${terms.length}\t${realms.length}`);
  const controlledComplete = once(`RF_CONTROLLED_COMPLETE\t${replay === 'api' ? 'PASSED' : 'SKIPPED'}`);
  const complete = once(`RF_COMPLETE\t${owner}\t${terms.length}\t${realms.length}`);
  const session = once(`RF_SESSION_COMPLETE\t${owner}\t${terms.length}`);
  assert.ok(goalsComplete < controlledComplete && controlledComplete < complete && complete < session);
  for (const tag of ['RF_GOALS_COMPLETE', 'RF_CONTROLLED_COMPLETE', 'RF_COMPLETE', 'RF_SESSION_COMPLETE']) assert.equal(select(tag).length, 1);
  assert.deepEqual(select('RF_CONTROLLED_PASS').map(r => r.join('\t')), replay === 'api'
    ? realms.map(realm => `RF_CONTROLLED_PASS\t${realm}\tStringUtils-and-SelectorUtils-bounded-API`) : []);
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i], tag = row.split('\t')[0];
    if (tag === 'RF_EVENT' || tag === 'RF_OUTPUT' || tag === 'RF_NATURAL_LOAD') assert.ok(i < goalsComplete);
    if (tag === 'RF_CONTROLLED_LOAD' || tag === 'RF_CONTROLLED_PASS') assert.ok(i > goalsComplete && i < controlledComplete);
    if (['RF_SYMBOLIC_LOAD', 'RF_CONSUMER', 'RF_MEMBER', 'RF_TYPE_LINK', 'RF_MISSING_MEMBER', 'RF_LINKAGE_COMPLETE'].includes(tag))
      assert.ok(i > controlledComplete && i < complete);
    if (tag === 'RF_TYPE') {
      const phase = row.split('\t')[1]; assert.ok(['CONTROLLED', 'SYMBOLIC'].includes(phase));
      assert.ok(phase === 'CONTROLLED' ? i > goalsComplete && i < controlledComplete : i > controlledComplete && i < complete);
    }
  }
  const explicit = select('RF_TYPE'); assert.equal(explicit.length, observed.explicitDefinitions.length);
  for (const [i, row] of explicit.entries()) {
    assert.equal(row.length, 8); const actual = observed.explicitDefinitions[i];
    assert.deepEqual([actual.phase, actual.selectedRealm, actual.class, actual.loader, actual.loaderName, actual.path, actual.sha256],
      [...row.slice(1, 6), fileUri(row[6]), row[7]]); sha256(actual.sha256);
  }
  const rawNatural = select('RF_NATURAL_LOAD'); assert.equal(rawNatural.length, observed.naturalDefinitions.length);
  for (const [i, row] of rawNatural.entries()) {
    assert.equal(row.length, 6); const actual = observed.naturalDefinitions[i];
    assert.deepEqual([actual.class, actual.loader, actual.loaderName, actual.path, actual.sha256],
      [...row.slice(1, 4), fileUri(row[4]), row[5]]); sha256(actual.sha256);
  }
  const consumers = new Map(inventory.effectiveInventory.consumers.map(row => [row.className, row]));
  const targets = new Map(inventory.expectedTypeDefinitions[variant].map(row => [`${row.consumer}\t${row.className}`, row]));
  const count = kind => linkage.filter(row => row.startsWith(kind + '\t')).length;
  assert.equal(observed.symbolicMemberCountPerRealm, count('member'));
  const privateUtils = artifact(config.repository, `org.codehaus.plexus:plexus-utils:jar:${version}`);
  for (const realm of realms) {
    const inRealm = tag => select(tag).filter(row => row[1] === realm);
    assert.deepEqual(inRealm('RF_CONSUMER').map(r => r.slice(2).join('\t')), linkage.filter(r => r.startsWith('consumer\t')));
    const members = inRealm('RF_MEMBER');
    assert.deepEqual(members.map(r => r.slice(2, -1).join('\t')), linkage.filter(r => r.startsWith('member\t')));
    // RF_MEMBER's final field is the resolved declaring class, not a loader id.
    assert.ok(members.every(r => /^[A-Za-z_$][A-Za-z0-9_$.]*$/.test(r.at(-1))));
    const types = inRealm('RF_TYPE_LINK');
    assert.deepEqual(types.map(r => { assert.equal(r.length, 6); return r.slice(2, -1).join('\t'); }), linkage.filter(r => r.startsWith('type\t')));
    once(`RF_LINKAGE_COMPLETE\t${realm}\t${count('consumer')}\t${count('type')}\t${count('member')}\t${linkageHash}`);
    once(`RF_MISSING_MEMBER\t${realm}\tPASSED`);
    const symbolic = observed.explicitDefinitions.filter(r => r.phase === 'SYMBOLIC' && r.selectedRealm === realm);
    const expected = linkage.flatMap(row => {
      const parts = row.split('\t'); const consumer = consumers.get(parts[1]); assert.ok(consumer);
      if (parts[0] === 'consumer') return [consumer];
      const target = targets.get(parts.slice(1, 3).join('\t')); assert.ok(target); return [consumer, target];
    });
    assert.equal(symbolic.length, expected.length + 1);
    for (const [index, wanted] of expected.entries()) {
      const actual = symbolic[index]; assert.equal(actual.class, wanted.className); assert.equal(actual.path, wanted.sourcePath);
      assert.equal(actual.sha256, wanted.classSha256); assert.equal(actual.entry, wanted.effectiveClassEntry);
      assert.ok(['selected-plugin-realm', 'maven-core-import'].includes(wanted.loaderRole));
      assert.equal(actual.loader === realm, wanted.loaderRole === 'selected-plugin-realm');
      if (wanted.loaderRole === 'maven-core-import') {
        assert.ok(!realms.includes(actual.loader)); assert.ok(actual.loaderName.startsWith('ClassRealm[plexus.core,'));
      }
      if (actual.loader === realm) assert.equal(actual.loaderName, observed.nativeRealms[realm].name);
    }
    const last = symbolic.at(-1); assert.equal(last.class, 'org/codehaus/plexus/util/StringUtils');
    assert.equal(last.path, privateUtils); assert.equal(last.loader, realm);
    let position = 0, typeIndex = 0;
    for (const row of linkage) {
      const kind = row.split('\t')[0]; if (kind === 'type') assert.equal(types[typeIndex++].at(-1), symbolic[position + 1].loader);
      position += kind === 'consumer' ? 1 : 2;
    }
    const controlled = observed.explicitDefinitions.filter(r => r.phase === 'CONTROLLED' && r.selectedRealm === realm);
    assert.deepEqual(controlled.map(r => r.class), replay === 'api' ? ['org/codehaus/plexus/util/StringUtils', 'org/codehaus/plexus/util/SelectorUtils'] : []);
    assert.ok(controlled.every(r => r.path === privateUtils && r.loader === realm));
  }
  assert.equal(select('RF_LINKAGE_COMPLETE').length, realms.length); assert.equal(select('RF_MISSING_MEMBER').length, realms.length);
  const natural = new Set(observed.naturalDefinitions.filter(r => realms.includes(r.loader)).map(r => r.class));
  assert.ok(observed.requiredNaturalClasses.length > 0 && observed.requiredNaturalClasses.every(c => natural.has(c)));
  const mojoClasses = { resources: 'org/apache/maven/plugins/resources/ResourcesMojo', testResources: 'org/apache/maven/plugins/resources/TestResourcesMojo',
    'copy-resources': 'org/apache/maven/plugins/resources/CopyResourcesMojo', flatten: 'org/codehaus/mojo/flatten/FlattenMojo', clean: 'org/codehaus/mojo/flatten/CleanMojo' };
  for (const term of terms) assert.ok(natural.has(mojoClasses[term[2]]));
  if (owner === 'flatten' && terms.some(term => term[2] === 'flatten')) assert.ok(natural.has('org/codehaus/plexus/util/StringUtils'));
  if (owner === 'resources' && name !== 'resources-failure') for (const required of [
    'org/apache/maven/shared/filtering/DefaultMavenResourcesFiltering', 'org/apache/maven/shared/filtering/DefaultMavenResourcesFiltering$1',
    ...['DirectoryScanner', 'AbstractScanner', 'MatchPatterns', 'MatchPattern', 'SelectorUtils'].map(n => 'org/codehaus/plexus/util/' + n)]) assert.ok(natural.has(required));
  return { rows, snapshots, terminals: terms.length, bytes: observed.protocol.bytes };
}

function expectedGoals(name) {
  if (name === 'resources-success' || name === 'resources-repeat') return ['resources@main-fixture', 'testResources@test-fixture', 'copy-resources@copy-fixture'].map(g => resources + ':' + g);
  if (name === 'resources-failure') return [resources + ':copy-resources@bad-encoding'];
  if (name === 'sdk-flatten') return ['process-resources', flatten + ':flatten@flatten'];
  if (name === 'sdk-clean') return [flatten + ':clean@flatten-clean', flatten + ':clean@flatten-clean'];
  if (name === 'demo-clean') return [flatten + ':clean@flatten.clean', flatten + ':clean@flatten.clean'];
  if (name === 'sdk-production-context') return [flatten + ':flatten@flatten', flatten + ':clean@flatten-clean'];
  return ['process-resources'];
}
function workFor(config, variant, name) {
  const suffix = name.startsWith('resources-') ? 'resources' : name.startsWith('demo-') ? 'demo'
    : name === 'flatten-failure' ? 'flatten-invalid' : name === 'sdk-production-context' ? name : 'sdk';
  return `${config.output}/${variant}-${suffix}`;
}
function resourceOracle(variant, changed) {
  const files = {
    'target/classes/plain/welcome.txt': changed ? 'changed=你好\n' : 'name=你好; escaped=${fixture.name}; unresolved=${missing}\n',
    'target/classes/plain/你好.txt': '文件名过滤\n', 'target/classes/plain/nested/final.txt': '路径过滤\n',
    'target/classes/plain/pixel.png': Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10, 0]), Buffer.from('${fixture.name}'), Buffer.from([255])]),
    'target/test-classes/tests/message.txt': 'test=你好; unknown=${unknown}\n', 'target/copied/copy/message.txt': 'copy=你好\n',
  };
  const all = ['a', 'b', 'ab', '%ant[a]', '%regex[a]', '%ant[ab]', '%regex[ab]', 'nested/a'];
  const selected = {
    'ant-one': [variant === 'baseline' ? '%ant[a]' : 'a'], 'regex-one': [variant === 'baseline' ? '%regex[a]' : 'a'],
    'ant-two': ['ab'], 'regex-two': ['ab'],
    'exclude-ant': all.filter(n => n !== (variant === 'baseline' ? '%ant[a]' : 'a')),
    'exclude-regex': all.filter(n => n !== (variant === 'baseline' ? '%regex[a]' : 'a')),
  };
  for (const [label, names] of Object.entries(selected)) for (const name of names)
    files[`target/classes/selector/${label}/${name}`] = `selected:${name}\n`;
  return Object.fromEntries(Object.entries(files).map(([name, data]) => {
    const bytes = Buffer.isBuffer(data) ? data : Buffer.from(data); return [name, { bytes: bytes.length, sha256: hash(bytes) }];
  }));
}
function validateStorage(storage, fileLimits) {
  assert.equal(storage.maximumBytes, 256 * 1024 * 1024); assert.equal(storage.maximumEntries, 3000);
  assert.equal(storage.pollIntervalSeconds, 0.1); assert.equal(storage.sampledBound, true); assert.equal(storage.failure, null);
  assert.ok(Number.isSafeInteger(storage.scans) && storage.scans > 0);
  assert.ok(Number.isSafeInteger(storage.peakObservedBytes) && storage.peakObservedBytes > 0 && storage.peakObservedBytes <= storage.maximumBytes);
  assert.ok(Number.isSafeInteger(storage.peakObservedEntries) && storage.peakObservedEntries > 0 && storage.peakObservedEntries <= storage.maximumEntries);
  assert.deepEqual(storage.fileLimits, fileLimits);
}
function validateSource(source, config, io) {
  const evidence = 'docs/operations/resources-flatten-evidence/';
  const manifestName = evidence + 'accepted-main905-source-manifest.json';
  const manifestHash = 'f28e7012dd8638a9b219e00aa16ade8357c771ff249f2b753755579df838120c';
  const additionsHash = 'dffad78082b0e7d4d7b1e743c4d8da28265daf3ae7aad0666a7176cb2a2ae7ae';
  const old = { 'pom.xml': 'ddb286b44b1976dd19ad74b06f3ceef86e19d0fbee87dd27dc87985ffb1f7932',
    'integrations/host-sdk/pom.xml': 'e755e3a2abd6d2df15b7e5739b10cd81f9a59476d8c21e6dc231961145b2d701' };
  const current = { 'pom.xml': '7aaef759cbdebaafde2383186cbb90861c23f51bd4fe3219614ed0fa8c22a9ab',
    'integrations/host-sdk/pom.xml': '2d933d9c89c7a0ee99b4870091a880ac086b295501914297c9bb6a21dd751078' };
  const originals = { 'docs/operations/assembly-enforcer-lang-evidence/candidate-root-pom.xml': old['pom.xml'],
    [evidence + 'accepted-main905-host-sdk-pom.xml']: old['integrations/host-sdk/pom.xml'] };
  for (const [key, value] of Object.entries({ schemaVersion: 'RESOURCES_FLATTEN_BOUNDED_SOURCE_PAIR_V1',
    sourceHead: '905cd30fde430972da58203a61136d50a28f81e8', sourceTree: '8b5d522974a5547a7d990f42b6ce386dadad0941',
    sourcePomCount: 28, checkedFileCount: 32, baselineCheckedFileCount: 32, candidateCheckedFileCount: 34,
    independentOriginalCount: 2, fullAcceptedSourceView: false, sourceScope: 'TWENTY_EIGHT_POMS_AND_EXACT_MODEL_HELPERS',
    historicalValidation: 'NOT_RUN', candidateCompatibility: 'NOT_RUN' })) assert.equal(source[key], value, `bounded source ${key}`);
  assert.deepEqual(source.sourceRoots, { baseline: config.baseline, candidate: config.candidate });
  for (const root of Object.values(source.sourceRoots)) canonical(root);
  assert.ok(config.baseline !== config.candidate && !config.baseline.startsWith(config.candidate + '/') && !config.candidate.startsWith(config.baseline + '/'));
  assert.deepEqual(source.anchorHashes, { manifest: manifestHash, approvedAdditions: additionsHash });
  const manifestBytes = io.read(`${config.candidate}/${manifestName}`, 2 * 1024 * 1024);
  assert.equal(hash(manifestBytes), manifestHash);
  assert.equal(hash(io.read(`${config.candidate}/${evidence}approved-additions.json`, 2 * 1024 * 1024)), additionsHash);
  const manifest = JSON.parse(manifestBytes.toString('utf8'));
  assert.equal(manifest.schemaVersion, 'RESOURCES_FLATTEN_ACCEPTED_MAIN905_SOURCE_CLOSURE_V1');
  assert.equal(manifest.head, source.sourceHead); assert.equal(manifest.tree, source.sourceTree);
  assert.equal(manifest.extractedFromManifestSha256, '920e81c1d2cb1a1377aba9107619ea8fa331578476d8e275722a592bcef79fd2');
  assert.equal(manifest.sourcePomCount, 28); assert.equal(manifest.checkedFileCount, 32);
  const baseline = Object.fromEntries(Object.entries(manifest.files).map(([file, row]) => [file, row.sha256]));
  for (const [file, expected] of Object.entries(old)) assert.equal(baseline[file], expected);
  exactKeys(source.sourceHashes, variants);
  assert.deepEqual(source.sourceHashes.baseline, baseline);
  assert.deepEqual(source.sourceHashes.candidate, { ...baseline, ...current, ...originals });
  const oldBytes = Object.values(manifest.files).reduce((sum, row) => {
    assert.ok(Number.isSafeInteger(row.bytes) && row.bytes > 0 && row.bytes <= 2 * 1024 * 1024); return sum + row.bytes;
  }, 0);
  // Replacing both files and retaining their two independent originals adds exactly the new bytes.
  assert.deepEqual(source.sourceBytes, { baseline: oldBytes, candidate: oldBytes + 22565 + 2226 });
  for (const [variant, count] of [['baseline', 32], ['candidate', 34]]) {
    hashMap(source.sourceHashes[variant]); assert.equal(Object.keys(source.sourceHashes[variant]).length, count);
    assert.equal(Object.keys(source.sourceHashes[variant]).filter(p => p === 'pom.xml' || p.endsWith('/pom.xml')).length, 28);
    for (const file of Object.keys(source.sourceHashes[variant]))
      assert.ok(!path.isAbsolute(file) && path.normalize(file) === file && !file.startsWith('../'));
  }
}

export function validateReceipt(receipt, pins, io = defaultIO) {
  assert.equal(receipt.schemaVersion, 'RESOURCES_FLATTEN_COMPAT_V1'); assert.equal(receipt.status, resultStatus);
  for (const flag of ['inputSealsUnchanged', 'freshSourceRuntimeCacheVerified', 'allOutputsCheckedAgainstIndependentOracles']) assert.equal(receipt[flag], true);
  for (const flag of ['scannerEvidence', 'publicAcceptance', 'productionRepeatProven', 'fullProductionBuildClaim', 'limitationFixedByDependencyChange']) assert.equal(receipt[flag], false);
  const config = receipt.config; exactKeys(config, ['baseline', 'candidate', 'maven', 'jdk', 'repository', 'output']); Object.values(config).forEach(canonical);
  assert.deepEqual(receipt.plan.casesPerVariant, cases);
  assert.deepEqual(receipt.plan.evidencePhases, ['natural real goals', 'separate bounded API replay', 'complete symbolic member resolution']);
  assert.equal(receipt.plan.scope, 'Resources 3.4.0 and Flatten 1.7.3 private Utils 4.0.2 to 4.0.3 only');
  for (const [key, value] of Object.entries({ execution: 'NOT_RUN', optIn: 'RESOURCES_FLATTEN_COMPATIBILITY=true',
    mavenProcessCount: 20, compileCount: 1, commandTimeoutSeconds: 90, compileTimeoutSeconds: 30,
    aggregateTimeoutSeconds: 1800, outerTimeoutSeconds: 1830, offline: true, downloadAllowed: false, scannerEvidence: false,
    classLogMaximumBytes: 6 * 1024 * 1024, classLogTotalMaximumBytes: 120 * 1024 * 1024,
    outputMaximumBytes: 256 * 1024 * 1024, outputMaximumEntries: 3000,
    protocolMaximumBytes: protocolLimits.bytes, protocolCompressedMaximumBytes: protocolLimits.compressedBytes,
    protocolRowMaximum: protocolLimits.rows, protocolTotalMaximumBytes: protocolLimits.totalBytes, receiptMaximumBytes: maximumReceiptBytes }))
    assert.equal(receipt.plan[key], value, `reviewed plan ${key}`);
  validateSource(receipt.source, config, io);
  for (const variant of variants) for (const [file, expected] of Object.entries(receipt.source.sourceHashes[variant]))
    assert.equal(hash(io.read(`${config[variant]}/${file}`)), expected, 'fresh paired source file');
  for (const key of ['harnessHashes', 'runtimeHashes', 'cacheHashes', 'generatedFixedInputs']) hashMap(receipt[key]);
  exactKeys(receipt.harnessHashes, [
    ...['resources_flatten_compatibility.py', 'resources_flatten_fixtures.py', 'resources_flatten_observations.py',
      'resources_flatten_inventory.py', 'ResourcesFlattenRealmObserver.java', 'ResourcesFlattenCompletionSpy.java',
      'resources-flatten-artifact-pins.json'].map(file => 'scripts/tests/fixtures/' + file),
    ...Object.keys(sharedHelpers),
    'scripts/ci/resources_flatten_contract.py', 'docs/operations/resources-flatten-evidence/accepted-main905-source-manifest.json',
    'docs/operations/resources-flatten-evidence/approved-additions.json', 'docs/operations/jar-plugin-evidence/compatibility-local-seal.json',
  ].map(file => `${config.candidate}/${file}`));
  for (const [file, expected] of Object.entries(receipt.harnessHashes)) assert.equal(hash(io.read(file)), expected, 'fresh reviewed harness file');
  for (const [file, expected] of Object.entries(sharedHelpers)) assert.equal(receipt.harnessHashes[`${config.candidate}/${file}`], expected);

  assert.equal(receipt.runtimeHashes[config.maven], pins.mavenLauncherSha256);
  for (const [coordinate, expected] of Object.entries(pins.artifactHashes)) {
    const jar = artifact(config.repository, coordinate); assert.equal(receipt.cacheHashes[jar], expected);
    sha256(receipt.cacheHashes[jar.slice(0, -4) + '.pom']);
  }
  for (const [file, expected] of Object.entries(receipt.generatedFixedInputs)) {
    inside(file, config.output); assert.equal(hash(io.read(file)), expected, 'fresh generated fixture/observer input');
  }
  for (const name of ['observer.jar', 'completion-spy.jar', 'resources-members.tsv', 'flatten-members.tsv']) sha256(receipt.generatedFixedInputs[`${config.output}/${name}`]);
  const freshPins = receipt.semanticInventory.pins;
  for (const key of ['schemaVersion', 'owners', 'artifactHashes', 'mavenCoreExtensionSha256', 'coreXmlExports', 'coreExportedArtifacts', 'status'])
    assert.deepEqual(freshPins[key], pins[key]);
  assert.equal(freshPins.artifacts.length, 13);
  assert.deepEqual(freshPins.artifacts, pins.artifacts.map(row => ({ ...row, path: artifact(config.repository, row.coordinate), retainedPinMatches: true })));
  const mavenHome = path.dirname(path.dirname(config.maven));
  assert.deepEqual(freshPins.runtime, pins.runtime.map(row => ({ path: `${mavenHome}/lib/${row.name}`, sha256: row.sha256, retainedPinMatches: true })));
  exactKeys(receipt.semanticInventory.owners, ['resources', 'flatten']);
  for (const owner of ['resources', 'flatten']) assert.deepEqual(io.read(`${config.output}/${owner}-members.tsv`),
    Buffer.from(receipt.semanticInventory.owners[owner].effectiveLinkageRows.join('\n') + '\n'));
  const labels = variants.flatMap(v => cases.map(name => `${v}-${name}`));
  validateStorage(receipt.storage, Object.fromEntries(labels.map(label => [`class-load/${label}.log`, 6 * 1024 * 1024])));
  assert.equal(receipt.commands.length, 21);
  assert.deepEqual(receipt.commands.map(c => c.case), ['compile-observer', ...labels]);
  const compile = receipt.commands[0]; commandComplete(compile, 0, 30);
  assert.deepEqual(compile.command.slice(0, 7), [`${config.jdk}/bin/javac`, '-J-Xmx128m', '-proc:none', '--release', '21', '-cp', compile.command[6]]);
  assert.ok(typeof compile.command[6] === 'string' && compile.command[6].split(':').length > 2);
  for (const entry of compile.command[6].split(':')) {
    assert.ok(entry.startsWith(`${mavenHome}/lib/`) || entry === `${mavenHome}/boot/plexus-classworlds-2.11.0.jar`);
    sha256(receipt.runtimeHashes[entry]);
  }
  assert.deepEqual(compile.command.slice(7), ['-d', `${config.output}/observer`,
    `${config.candidate}/scripts/tests/fixtures/ResourcesFlattenRealmObserver.java`, `${config.candidate}/scripts/tests/fixtures/ResourcesFlattenCompletionSpy.java`]);
  exactKeys(receipt.variants, variants);
  let terminalCount = 0, snapshotCount = 0, protocolBytes = 0, protocolRows = 0, classBytes = 0;
  for (const variant of variants) {
    exactKeys(receipt.variants[variant], cases);
    for (const name of cases) {
      const observed = receipt.variants[variant][name], label = `${variant}-${name}`;
      const command = receipt.commands.find(c => c.case === label);
      const expectedExit = ['resources-failure', 'sdk-flatten', 'flatten-failure'].includes(name) ? 1 : 0;
      commandComplete(command, expectedExit, 90); assert.equal(observed.exitStatus, expectedExit);
      const work = workFor(config, variant, name), demo = name.startsWith('demo-');
      const project = work + (name === 'sdk-production-context' ? '/integrations/host-sdk/pom.xml'
        : name.startsWith('sdk-') || name === 'flatten-failure' ? '/sdk/pom.xml' : '/pom.xml');
      assert.deepEqual(command.command, [config.maven, '-o', '-B', '-ntp', '-X', '--strict-checksums',
        '-s', `${config.output}/empty-settings.xml`, '-gs', `${config.output}/empty-settings.xml`,
        '-t', `${config.output}/empty-toolchains.xml`, '-gt', `${config.output}/empty-toolchains.xml`,
        '-Dmaven.repo.local=' + config.repository, '-Dstyle.color=never', '-Dmaven.ext.class.path=' + config.output + '/completion-spy.jar',
        '-Dmaven.multiModuleProjectDirectory=' + work, '-f', project, demo ? '-Pproduct-readiness-demo' : '-N',
        ...(name === 'flatten-failure' ? ['-DflattenedPomFilename=blocked/flattened-pom.xml'] : []), ...expectedGoals(name)]);
      const stdout = io.read(`${config.output}/${label}.stdout.log`, 16 * 1024 * 1024);
      const stderr = io.read(`${config.output}/${label}.stderr.log`, 16 * 1024 * 1024);
      assert.equal(hash(stdout), command.stdoutSha256); assert.equal(hash(stderr), command.stderrSha256);
      assert.equal(stdout.length + stderr.length, command.capturedBytes);
      const fullText = stdout.toString('utf8') + '\n' + stderr.toString('utf8');
      assert.ok(!fullText.includes('RF_FAILURE') && !fullText.includes('Failed to notify spy ResourcesFlattenCompletionSpy'));
      const owner = name.startsWith('resources-') ? 'resources' : 'flatten';
      const protocol = validateProtocol(observed, receipt.semanticInventory.owners[owner], variant, name, pins, config);
      const stderrLines = stderr.toString('utf8').split(/\r?\n/);
      assert.deepEqual(stderrLines.filter(line => line.startsWith('RF_')), protocol.rows);
      assert.deepEqual(observed.explicitDefinitions.map(row => row.line), stderrLines.flatMap((line, i) => line.startsWith('RF_TYPE\t') ? [i] : []));
      assert.deepEqual(observed.naturalDefinitions.map(row => row.line), stderrLines.flatMap((line, i) => line.startsWith('RF_NATURAL_LOAD\t') ? [i] : []));
      terminalCount += protocol.terminals; snapshotCount += protocol.snapshots.length; protocolBytes += protocol.bytes; protocolRows += protocol.rows.length;
      const log = command.classLoadCorroborationLog; assert.equal(log.path, `${config.output}/class-load/${label}.log`);
      assert.ok(Number.isSafeInteger(log.bytes) && log.bytes > 0 && log.bytes <= 6 * 1024 * 1024); sha256(log.sha256);
      const classLog = io.read(log.path, 6 * 1024 * 1024); assert.equal(classLog.length, log.bytes); assert.equal(hash(classLog), log.sha256); classBytes += log.bytes;
      const definitions = new Set(classLog.toString('utf8').split('\n').flatMap(line => {
        const match = line.match(/^\[\d+(?:\.\d+)?s\]\[info\]\[class,load\] (.+)$/); return match ? [match[1]] : [];
      }));
      for (const definition of [...observed.naturalDefinitions, ...observed.explicitDefinitions])
        assert.ok(definitions.has(`${definition.class.replaceAll('/', '.')} source: ${new URL('file://' + definition.path).href}`), 'independent class-load corroboration');
      for (const [index, state] of observed.states.entries()) {
        const term = expectedTerminals(name)[index], directory = term[4] === '.' ? work : `${work}/${term[4]}`;
        assert.equal(state.before.project.projectBasedir, directory); assert.equal(state.after.project.projectBasedir, directory);
      }
      for (const row of protocol.snapshots) {
        const state = observed.states[Number(row[1])], original = io.read(state.before.project.projectBasedir + '/pom.xml', 262144);
        let expected = original.toString('utf8');
        if (name === 'sdk-production-context') {
          const root = io.read(`${config[variant]}/pom.xml`, 1024 * 1024).toString('utf8');
          const revision = [...root.matchAll(/<revision>([^<>]+)<\/revision>/g)]; assert.equal(revision.length, 1);
          expected = expected.replaceAll('${revision}', revision[0][1]);
        } else for (const [key, value] of Object.entries({ revision: '1.2.3', sha1: '.g123', changelist: '-SNAPSHOT' })) expected = expected.replaceAll('${' + key + '}', value);
        assert.deepEqual(base64(row[6], 262144), Buffer.from(expected), 'full independent flattened output bytes');
        if (name !== 'sdk-production-context') assert.equal(observed.outputs[state.after.project.projectFile], row[5]);
      }
      if (owner === 'resources') {
        const key = name === 'resources-failure' ? 'retainedOutputs' : 'outputs';
        assert.deepEqual(observed[key], resourceOracle(variant, name !== 'resources-success'));
        assert.ok(io.isDirectory(work + '/target/copied/copy/empty/nested')); assert.deepEqual(io.read(work + '/neighbor-sentinel.txt'), Buffer.from('keep neighbor\n'));
        if (name !== 'resources-success') for (const [file, wanted] of Object.entries(observed[key])) {
          const bytes = io.read(work + '/' + file); assert.equal(bytes.length, wanted.bytes); assert.equal(hash(bytes), wanted.sha256);
        }
      }
      if (expectedExit === 1) {
        const expectedFailures = {
          'resources-failure': { kind: 'unsupported-encoding', requiredFragments: ['BOGUS-FIXTURE-CHARSET'] },
          'sdk-flatten': { kind: 'retained-ci-parent-repeat', requiredFragments: ['Non-resolvable parent POM', 'local.resources.flatten:fixture-parent:pom:1.2.3.g123-SNAPSHOT', "'parent.relativePath' points at wrong local POM"], parentCoordinate: 'local.resources.flatten:fixture-parent:pom:1.2.3.g123-SNAPSHOT' },
          'flatten-failure': { kind: 'blocked-flatten-output', requiredFragments: ['blocked'] },
        };
        assert.deepEqual(observed.expectedFailure, expectedFailures[name]); assert.equal(observed.actualFailureAttributionVerified, true);
        assert.equal(observed.failureAttributionLines.length, observed.expectedFailure.requiredFragments.length);
        observed.expectedFailure.requiredFragments.forEach((fragment, i) => {
          const line = observed.failureAttributionLines[i]; assert.ok(line.includes(fragment)); assert.ok(fullText.split(/\r?\n/).includes(line));
        });
      }
      if (name === 'sdk-flatten') assert.deepEqual(observed.knownBaselineLimitation, {
        parentCoordinate: 'local.resources.flatten:fixture-parent:pom:1.2.3.g123-SNAPSHOT', parentVersionPattern: '${revision}${sha1}${changelist}',
        parentRelativePath: '../pom.xml', firstFlattenSucceeded: true, secondFlattenFailedWithoutProjectMutation: true, productionRevisionParentRepeatProven: false,
      });
      if (name.endsWith('-clean') || name === 'sdk-production-context') for (const state of observed.states) {
        const directory = state.after.project.projectBasedir;
        assert.equal(io.exists(directory + (demo ? '/target/flattened-pom.xml' : '/.flattened-pom.xml')), false);
        assert.deepEqual(io.read(directory + '/neighbor-sentinel.txt'), Buffer.from('keep neighbor\n'));
      }
      if (name === 'sdk-production-context') {
        for (const flag of ['actualFlattenOutputSnapshotVerified', 'actualCleanRemovedFlattenedOutput', 'exactSourcePomBytes']) assert.equal(observed[flag], true);
        assert.equal(observed.productionRepeatProven, false); assert.equal(observed.fullProductionBuildClaim, false);
        for (const file of ['pom.xml', 'integrations/host-sdk/pom.xml']) assert.deepEqual(io.read(work + '/' + file), io.read(config[variant] + '/' + file));
        const [flattened, cleaned] = observed.states;
        assert.equal(flattened.before.project.originalParentVersion, '${revision}');
        assert.equal(flattened.before.project.originalParentRelativePath, '../../pom.xml');
        assert.equal(flattened.after.project.originalParentRelativePath, '../../pom.xml');
        assert.equal(flattened.before.project.projectFile, `${work}/integrations/host-sdk/pom.xml`);
        assert.equal(flattened.after.project.projectFile, `${work}/integrations/host-sdk/.flattened-pom.xml`);
        assert.deepEqual(flattened.after, cleaned.before); assert.deepEqual(cleaned.before, cleaned.after);
      }
    }
  }
  assert.equal(terminalCount, 52); assert.equal(snapshotCount, 16);
  assert.ok(protocolBytes <= protocolLimits.totalBytes && protocolRows <= protocolLimits.totalRows);
  assert.ok(classBytes > 0 && classBytes <= 120 * 1024 * 1024);
}

export function validatePortable(receipt, pins, goal, pointer, io = defaultIO) {
  assert.equal(receipt.schemaVersion, 'RESOURCES_FLATTEN_PORTABLE_V1'); assert.equal(receipt.status, 'PASSED');
  for (const flag of ['centralPreparationOnly', 'existingArtifactHashesUnchanged', 'adapterSealsUnchanged',
    'freshSourceRuntimeCacheVerified', 'preparationCompleted', 'cleanupComplete']) assert.equal(receipt[flag], true);
  for (const flag of ['scannerEvidence', 'productionRepeatProven', 'fullProductionBuildClaim']) assert.equal(receipt[flag], false);
  assert.deepEqual(receipt.artifactHashes, pins.artifactHashes); assert.deepEqual(receipt.owners, pins.owners); validateSource(receipt.source, receipt.config, io);
  for (const key of ['harnessHashes', 'runtimeHashes', 'adapterHashes', 'fixedPreparationHashes', 'cacheAfterPreparationHashes']) hashMap(receipt[key]);
  assert.ok(receipt.preexistingCacheHashes && typeof receipt.preexistingCacheHashes === 'object');
  Object.values(receipt.preexistingCacheHashes).forEach(sha256);
  for (const [file, expected] of Object.entries(receipt.preexistingCacheHashes)) assert.equal(receipt.cacheAfterPreparationHashes[file], expected);
  sha256(receipt.executionSealSha256); sha256(receipt.executionResultSha256);
  for (const [key, value] of Object.entries({ execution: 'NOT_RUN', hostedOnly: true, explicitOptIn: 'RESOURCES_FLATTEN_COMPATIBILITY=true',
    resolutionCommandsMaximum: 2, preparationCommandTimeoutSeconds: 120, preparationAggregateTimeoutSeconds: 240,
    offlineGoalCount: 20, compileCount: 1, offlineAggregateTimeoutSeconds: 1800, offlineOuterTimeoutSeconds: 1830,
    outerTimeoutSeconds: 2100, artifactUnionCount: 13, repositoryUrl: 'https://repo.maven.apache.org/maven2' })) assert.equal(receipt.plan[key], value);
  assert.deepEqual(receipt.plan.owners, ['resources', 'flatten']);
  assert.ok(Number.isSafeInteger(receipt.resolutionCommandsRun) && receipt.resolutionCommandsRun >= 0 && receipt.resolutionCommandsRun <= 2);
  assert.equal(receipt.commands.length, receipt.resolutionCommandsRun);
  assert.equal(new Set(receipt.commands.map(c => c.case)).size, receipt.commands.length);
  const sandbox = path.dirname(receipt.config.output);
  assert.deepEqual(receipt.commands.map(c => c.case), variants.map(v => 'prepare-' + v).filter(v => receipt.commands.some(c => c.case === v)));
  exactKeys(receipt.fixedPreparationHashes, ['central-settings.xml', 'empty-toolchains.xml', 'baseline-pom.xml', 'candidate-pom.xml'].map(file => `${sandbox}/${file}`));
  exactKeys(receipt.adapterHashes, [
    'scripts/tests/fixtures/resources_flatten_portable.py', 'scripts/tests/fixtures/resources-flatten-live-support.mjs',
    'scripts/tests/resources-flatten-live-compatibility.test.mjs', 'scripts/tests/resources-flatten-compatibility-harness.test.mjs',
    'scripts/ci/setup-maven.mjs', 'scripts/ci/maven-toolchain.mjs', 'scripts/ci/select-java21.mjs',
  ].map(file => `${receipt.config.candidate}/${file}`));
  for (const seals of [receipt.adapterHashes, receipt.fixedPreparationHashes]) for (const [file, expected] of Object.entries(seals))
    assert.equal(hash(io.read(file)), expected);
  assert.equal(io.read(`${sandbox}/central-settings.xml`).toString('utf8'), '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><mirrors><mirror><id>central</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>\n');
  assert.equal(io.read(`${sandbox}/empty-toolchains.xml`).toString('utf8'), '<toolchains/>\n');
  assert.equal(hash(io.read(`${sandbox}/execution-seal.json`)), receipt.executionSealSha256);
  for (const command of receipt.commands) {
    commandComplete(command, 0, 120); assert.ok(['prepare-baseline', 'prepare-candidate'].includes(command.case));
    const variant = command.case.slice('prepare-'.length);
    assert.deepEqual(command.command, [receipt.config.maven, '-B', '-ntp', '-N', '--strict-checksums',
      '-s', `${sandbox}/central-settings.xml`, '-gs', `${sandbox}/central-settings.xml`,
      '-t', `${sandbox}/empty-toolchains.xml`, '-gt', `${sandbox}/empty-toolchains.xml`,
      '-Dmaven.repo.local=' + receipt.config.repository, '-Dmaven.multiModuleProjectDirectory=' + sandbox, '-Dstyle.color=never',
      '-f', `${sandbox}/${variant}-pom.xml`, resolve, '-DincludeArtifactIds=maven-resources-plugin,flatten-maven-plugin', '-DexcludeTransitive=false']);
  }
  assert.ok(receipt.commands.reduce((total, c) => total + c.elapsedSeconds, 0) <= 245);
  assert.ok(Number.isFinite(receipt.elapsedSeconds) && receipt.elapsedSeconds >= 0 && receipt.elapsedSeconds <= 2100);
  validateStorage(receipt.storage, {});
  assert.ok(goal && pointer, 'preparation must bind this invocation\'s complete goal receipt');
  assert.equal(goal.schemaVersion, 'RESOURCES_FLATTEN_COMPAT_V1'); assert.equal(goal.status, resultStatus);
  assert.deepEqual(receipt.config, goal.config); assert.deepEqual(receipt.source, goal.source);
  assert.deepEqual(receipt.harnessHashes, goal.harnessHashes); assert.deepEqual(receipt.cacheAfterPreparationHashes, goal.cacheHashes);
  for (const [file, expected] of Object.entries(receipt.runtimeHashes)) assert.equal(goal.runtimeHashes[file], expected);
  assert.equal(receipt.executionResultPath, `${goal.config.output}/result.json`); assert.equal(receipt.executionResultPath, pointer.path);
  assert.equal(receipt.executionResultSha256, pointer.sha256);
}
