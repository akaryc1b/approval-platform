import assert from 'node:assert/strict';
import { lstatSync, readFileSync } from 'node:fs';

export const resultMarker = 'ASSEMBLY_ENFORCER_LANG_RESULT';
export const portableMarker = 'ASSEMBLY_ENFORCER_LANG_PORTABLE_RESULT';
export const maximumReceiptBytes = 8 * 1024 * 1024;
const versions = { baseline: '3.14.0', candidate: '3.18.0' };
const goals = { assembly: 'org.apache.maven.plugins:maven-assembly-plugin:3.7.1:single',
  enforcer: 'org.apache.maven.plugins:maven-enforcer-plugin:3.5.0:enforce@enforce-toolchain' };
const cases = { assembly: ['archives', 'discovery', 'missing-main', 'malformed-main', 'missing-component', 'malformed-component'],
  enforcer: ['success', 'bad-maven', 'bad-java'] };
const sha256 = value => assert.match(value, /^[a-f0-9]{64}$/);
const hashMap = value => { assert.ok(value && Object.keys(value).length > 0); Object.values(value).forEach(sha256); };

export function readReceipt(stdout, marker) {
  const rows = stdout.split('\n').filter(line => line.startsWith(marker + ' '));
  assert.equal(rows.length, 1, `exactly one ${marker} pointer required`);
  const pointer = JSON.parse(rows[0].slice(marker.length + 1));
  assert.match(pointer.path, /^\/[^\0\r\n]+\.json$/);
  const stat = lstatSync(pointer.path);
  assert.ok(stat.isFile() && !stat.isSymbolicLink() && stat.size <= maximumReceiptBytes, 'bounded regular receipt required');
  const text = readFileSync(pointer.path, 'utf8');
  assert.ok(Buffer.byteLength(text) <= maximumReceiptBytes);
  const value = JSON.parse(text);
  assert.ok(value && typeof value === 'object' && !Array.isArray(value));
  assert.equal(pointer.status, value.status);
  // Preserve integer lexemes and every JSON string, including escaped newlines.
  return { value, json: text.replace(/[\r\n\t]/g, '') };
}

export function retainReceipts(stdout, ci, emit = console.log) {
  const markers = [resultMarker, ...(ci ? [portableMarker] : [])];
  const records = {};
  // Emit full PASSED or FAILED evidence once, before subsequent assertions can
  // throw. Temporary receipt pointers alone disappear with the hosted runner.
  for (const marker of markers) {
    if (!stdout.split('\n').some(line => line.startsWith(marker + ' '))) continue;
    const record = readReceipt(stdout, marker);
    emit(marker + ' ' + record.json); records[marker] = record.value;
  }
  return records;
}

function commandComplete(command, expectedExit, timeout) {
  assert.equal(command.status, 'EXITED'); assert.equal(command.exitStatus, expectedExit);
  assert.equal(command.captureComplete, true); assert.equal(command.processGroupCleaned, true);
  assert.ok(Array.isArray(command.command) && command.command.every(v => typeof v === 'string'));
  assert.ok(Number.isFinite(command.elapsedSeconds) && command.elapsedSeconds >= 0 && command.elapsedSeconds <= timeout + 5);
  assert.ok(Number.isSafeInteger(command.capturedBytes) && command.capturedBytes >= 0 && command.capturedBytes <= 16 * 1024 * 1024);
  sha256(command.stdoutSha256); sha256(command.stderrSha256);
}

export function validateReceipt(receipt, pins) {
  assert.equal(receipt.status, 'PASSED'); assert.equal(receipt.inputSealsUnchanged, true);
  assert.equal(receipt.scannerEvidence, false); assert.equal(receipt.productionActivationClaim, false);
  assert.deepEqual(receipt.plan.variants, versions); assert.deepEqual(receipt.plan.goals, goals); assert.deepEqual(receipt.plan.cases, cases);
  for (const [key, value] of Object.entries({ offline: true, sockets: false, actualGoalCount: 18, compileCount: 1,
    commandTimeoutSeconds: 60, compileTimeoutSeconds: 30, aggregateTimeoutSeconds: 1200, outerTimeoutSeconds: 1230,
    classLoadMaximumBytes: 4 * 1024 * 1024, classLoadTotalMaximumBytes: 72 * 1024 * 1024,
    classLoadMaximumEntries: 18, classLoadPollIntervalSeconds: 0.1, classLoadBoundIsSampled: true })) {
    assert.equal(receipt.plan[key], value);
  }
  const storage = receipt.classLoadStorage;
  assert.equal(storage.maximumBytes, 72 * 1024 * 1024); assert.equal(storage.maximumEntries, 18);
  assert.equal(storage.pollIntervalSeconds, 0.1); assert.equal(storage.sampledBound, true); assert.equal(storage.failure, null);
  assert.ok(Number.isSafeInteger(storage.scans) && storage.scans > 0);
  assert.ok(Number.isSafeInteger(storage.peakObservedBytes) && storage.peakObservedBytes > 0 && storage.peakObservedBytes <= storage.maximumBytes);
  assert.equal(storage.peakObservedEntries, 18);
  assert.deepEqual(storage.fileLimits, Object.fromEntries(Object.keys(versions).flatMap(variant =>
    Object.entries(cases).flatMap(([owner, names]) => names.map(name => [`${variant}-${owner}-${name}.log`, 4 * 1024 * 1024])))));
  for (const key of ['sourceHashes', 'runtimeHashes', 'cacheHashes', 'generatedInputHashes']) hashMap(receipt[key]);
  assert.equal(receipt.inventory.consumers.length, 10); assert.equal(receipt.inventory.memberUses.length, 13);
  assert.equal(new Set(receipt.inventory.memberUses.map(row => JSON.stringify(row.slice(2)))).size, 10);
  assert.equal(receipt.classUtilsStatic.directSelfInvocation, false);
  sha256(receipt.classUtilsStatic.classSha256); sha256(receipt.classUtilsStatic.codeSha256);
  assert.deepEqual(receipt.comparison, { allNinePairsExactlyEqual: true, archiveBytesAndMetadataEqual: true, controlledResourceCopyEqual: true });
  const commands = new Map(receipt.commands.map(command => [command.case, command]));
  assert.equal(receipt.commands.length, 19); assert.equal(commands.size, 19);
  const compile = commands.get('compile-observer'); commandComplete(compile, 0, 30);
  assert.ok(compile.command[0].endsWith('/bin/javac')); assert.ok(compile.command.includes('-proc:none'));
  assert.deepEqual(Object.keys(receipt.variants).sort(), Object.keys(versions).sort());
  for (const [variant, version] of Object.entries(versions)) {
    assert.deepEqual(Object.keys(receipt.variants[variant]).sort(), Object.keys(cases).sort());
    for (const [owner, names] of Object.entries(cases)) {
      assert.deepEqual(Object.keys(receipt.variants[variant][owner]).sort(), [...names].sort());
      for (const name of names) {
        const observed = receipt.variants[variant][owner][name];
        const expectedExit = ['archives', 'discovery', 'success'].includes(name) ? 0 : 1;
        const command = commands.get(`${variant}-${owner}-${name}`); commandComplete(command, expectedExit, 60);
        const log = command.classLoadCorroborationLog;
        assert.equal(log.path, `class-load/${variant}-${owner}-${name}.log`);
        assert.ok(Number.isSafeInteger(log.bytes) && log.bytes > 0 && log.bytes <= receipt.plan.classLoadMaximumBytes);
        sha256(log.sha256); assert.deepEqual(observed.classLoadCorroborationLog, log);
        assert.equal(observed.exitStatus, expectedExit); assert.ok(command.command[0].endsWith('/bin/mvn'));
        assert.equal(command.command.at(-1), goals[owner]);
        for (const flag of ['-o', '-N', '--strict-checksums', '-s', '-gs', '-t', '-gt']) assert.ok(command.command.includes(flag));
        assert.deepEqual(command.command.filter(arg => arg.startsWith('org.apache.maven.plugins:')), [goals[owner]]);
        assert.equal(observed.missingMemberControl, 'PASSED'); assert.equal(observed.symbolicResolutionIsInvocationClaim, false);
        assert.equal(observed.linkageMemberCount, owner === 'assembly' ? 3 : 10);
        assert.ok(observed.realm.includes('org.apache.commons:commons-lang3:jar:' + version));
        assert.ok(observed.realm.every(coordinate => pins.owners[owner][variant].includes(coordinate)));
        assert.equal(observed.realm.filter(coordinate => coordinate.startsWith('org.apache.commons:commons-lang3:')).length, 1);
        assert.ok(observed.realm.includes('org.codehaus.plexus:plexus-utils:jar:' + (owner === 'assembly' ? '4.0.3' : '4.0.1')));
        for (const phase of ['naturalOrigins', 'controlledLoads', 'symbolicLoads', 'explicitOrigins']) assert.ok(Array.isArray(observed[phase]));
        assert.deepEqual(observed.behavior, receipt.variants.baseline[owner][name].behavior);
      }
      assert.equal(receipt.coverage[variant][owner].symbolicallyResolvedConsumers, owner === 'assembly' ? 3 : 7);
      assert.equal(receipt.coverage[variant][owner].naturalLoadIsMemberInvocationClaim, false);
    }
    assert.deepEqual(receipt.variants[variant].assembly.archives.controlledResourceCopy, {
      phase: 'controlled API after real goal', log: '[INFO] Copying 1 resource from filter to controlled-resources/nested',
      path: 'controlled-resources/nested/value.txt', sha256: '30bf651f5f3d4bc94205fd3c00b2902cbf912a39a10b4bb58d7fbc148e36be1b', bytes: 27,
    });
  }
}

export function validatePortable(receipt, pins) {
  assert.equal(receipt.status, 'PASSED'); assert.equal(receipt.centralPreparationOnly, true);
  assert.equal(receipt.existingArtifactHashesUnchanged, true); assert.equal(receipt.adapterSealsUnchanged, true);
  assert.equal(receipt.scannerEvidence, false); assert.equal(receipt.productionActivationClaim, false);
  assert.deepEqual(receipt.artifactHashes, pins.artifactHashes); assert.deepEqual(receipt.owners, pins.owners);
  for (const key of ['sourceHashes', 'runtimeHashes', 'adapterHashes', 'fixedPreparationHashes']) hashMap(receipt[key]);
  sha256(receipt.executionSealSha256);
  for (const [key, value] of Object.entries({ hostedOnly: true, resolutionCommandsMaximum: 2,
    preparationCommandTimeoutSeconds: 120, preparationAggregateTimeoutSeconds: 240, offlineGoalCount: 18,
    compileCount: 1, offlineAggregateTimeoutSeconds: 1200, outerTimeoutSeconds: 1500,
    repositoryUrl: 'https://repo.maven.apache.org/maven2' })) assert.equal(receipt.plan[key], value);
  assert.ok(Number.isSafeInteger(receipt.resolutionCommandsRun) && receipt.resolutionCommandsRun >= 0 && receipt.resolutionCommandsRun <= 2);
  assert.equal(receipt.commands.length, receipt.resolutionCommandsRun);
  assert.equal(new Set(receipt.commands.map(command => command.case)).size, receipt.commands.length);
  for (const command of receipt.commands) {
    commandComplete(command, 0, 120); assert.ok(['prepare-baseline', 'prepare-candidate'].includes(command.case));
    assert.equal(command.command.at(-3), 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins');
    assert.equal(command.command.at(-2), '-DincludeArtifactIds=maven-assembly-plugin,maven-enforcer-plugin');
    assert.equal(command.command.at(-1), '-DexcludeTransitive=false');
  }
}
