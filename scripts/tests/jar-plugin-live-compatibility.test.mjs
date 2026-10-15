import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const ci = process.env.GITHUB_ACTIONS === 'true';
const maximumReceiptBytes = 8 * 1024 * 1024;
const jar = 'org.apache.maven.plugins:maven-jar-plugin:';
const boot = 'org.springframework.boot:spring-boot-maven-plugin:4.0.8:repackage';
const versions = { baseline: '3.5.0', candidate: '3.5.1' };
const caseGoals = {
  selection: ['jar@omitted', 'jar@explicit', 'jar@all'], empty: ['jar@empty-keep', 'jar@empty-skip'],
  'incremental-first': ['jar@main'], 'incremental-unchanged': ['jar@main'], 'incremental-changed': ['jar@main'],
  attachments: ['jar@main', 'jar@attached', 'test-jar@tests'], duplicate: ['jar@main', 'jar@duplicate'],
  boot: ['jar@main', 'boot:repackage'],
};
const sha256 = value => assert.match(value, /^[a-f0-9]{64}$/);
const boundedInteger = (value, maximum) => assert.ok(Number.isSafeInteger(value) && value >= 0 && value <= maximum);
const comparable = value => Array.isArray(value) ? value.map(comparable)
  : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value)
    .filter(([key]) => key !== 'archiveSha256').map(([key, item]) => [key, comparable(item)])) : value;

function commandComplete(command, expectedExit) {
  assert.equal(command.status, 'EXITED'); assert.equal(command.exitStatus, expectedExit);
  assert.equal(command.processGroupCleaned, true); assert.equal(command.captureComplete, true);
  assert.ok(Array.isArray(command.command) && command.command.length > 1 && command.command.every(v => typeof v === 'string'));
  assert.ok(Number.isFinite(command.elapsedSeconds) && command.elapsedSeconds >= 0);
  boundedInteger(command.capturedBytes, 16 * 1024 * 1024);
  sha256(command.stdoutSha256); sha256(command.stderrSha256);
}

function readReceipt(stdout, marker) {
  const rows = stdout.split('\n').filter(line => line.startsWith(marker + ' '));
  assert.equal(rows.length, 1, `exactly one ${marker} pointer required`);
  const pointer = JSON.parse(rows[0].slice(marker.length + 1));
  assert.equal(pointer.status, 'PASSED');
  assert.equal(typeof pointer.path, 'string'); assert.match(pointer.path, /^\/[^\0\r\n]+\.json$/);
  const text = readFileSync(pointer.path, 'utf8');
  assert.ok(Buffer.byteLength(text, 'utf8') <= maximumReceiptBytes, `${marker} receipt exceeds 8 MiB`);
  const receipt = JSON.parse(text);
  assert.ok(receipt && typeof receipt === 'object' && !Array.isArray(receipt), `${marker} object receipt required`);
  // JSON strings cannot contain literal CR/LF/tab. Remove only those formatting
  // bytes so one log record preserves every original string and numeric lexeme.
  return { value: receipt, json: text.replace(/[\r\n\t]/g, '') };
}

function validateArchive(archive) {
  sha256(archive.archiveSha256);
  assert.equal(archive.manifest['Manifest-Version'], '1.0');
  assert.equal(archive.manifest['Fixture-Identity'], 'jar-plugin');
  assert.ok(archive.members && Object.keys(archive.members).length > 0 && Object.keys(archive.members).length <= 256);
  assert.ok(Object.hasOwn(archive.members, 'META-INF/MANIFEST.MF'));
  for (const [name, member] of Object.entries(archive.members)) {
    assert.ok(name && !name.startsWith('/') && !name.includes('\\') && !name.split('/').includes('..'));
    sha256(member.sha256); boundedInteger(member.bytes, 11 * 1024 * 1024); boundedInteger(member.crc32, 0xffffffff);
    boundedInteger(member.mode, 0xffff); assert.equal(typeof member.directory, 'boolean');
    assert.deepEqual(member.timestamp, [2024, 1, 2, 3, 4, 6]);
  }
}

function validateOrigins(observed, variant, name, pins, maven) {
  const realm = observed.actualJarRealm;
  assert.ok(Array.isArray(realm) && realm.length > 0 && new Set(realm).size === realm.length);
  assert.ok(realm.includes(jar + 'jar:' + versions[variant]));
  assert.ok(realm.every(coordinate => pins.variants[variant].includes(coordinate)));
  if (variant === 'candidate') assert.ok(realm.every(coordinate => !coordinate.startsWith('io.airlift:aircompressor:')));
  const bootRealm = name === 'boot' ? observed.actualBootRealm : [];
  if (name === 'boot') {
    assert.ok(Array.isArray(bootRealm) && new Set(bootRealm).size === bootRealm.length);
    assert.ok(bootRealm.includes('org.springframework.boot:spring-boot-maven-plugin:jar:4.0.8'));
    assert.ok(bootRealm.includes('org.springframework.boot:spring-boot-loader-tools:jar:4.0.8'));
    assert.ok(bootRealm.every(coordinate => pins.boot.includes(coordinate)));
  }
  assert.ok(Array.isArray(observed.naturalOrigins) && observed.naturalOrigins.length > 0);
  assert.ok(Array.isArray(observed.symbolicLoadOrigins));
  for (const origin of [...observed.naturalOrigins, ...observed.symbolicLoadOrigins]) {
    assert.match(origin.class, /^[A-Za-z0-9_$/]+$/); assert.match(origin.loader, /^[a-f0-9]+$/);
    sha256(origin.sha256); assert.equal(typeof origin.jarRealm, 'boolean');
    assert.ok(origin.entry === origin.class + '.class'
      || /^META-INF\/versions\/(?:9|1[0-9]|2[01])\//.test(origin.entry) && origin.entry.endsWith('/' + origin.class + '.class'));
    assert.equal(typeof origin.path, 'string'); assert.ok(origin.path.startsWith('/') && !origin.path.split('/').includes('..'));
    if (origin.coordinate === null) {
      assert.equal(origin.jarRealm, false);
      assert.ok(origin.path.startsWith(maven.slice(0, -'bin/mvn'.length) + 'lib/') && origin.path.endsWith('.jar'));
    } else {
      assert.ok((origin.jarRealm ? realm : [...realm, ...bootRealm]).includes(origin.coordinate));
      const [group, artifact, type, version] = origin.coordinate.split(':'); assert.equal(type, 'jar');
      assert.ok(origin.path.endsWith('/' + group.replaceAll('.', '/') + '/' + artifact + '/' + version + '/' + artifact + '-' + version + '.jar'));
    }
  }
  const required = ['org/apache/maven/plugins/jar/JarMojo'];
  if (name !== 'empty') required.push('org/apache/maven/archiver/MavenArchiver', 'org/codehaus/plexus/archiver/jar/JarArchiver');
  if (name === 'attachments') required.push('org/apache/maven/plugins/jar/TestJarMojo');
  if (name === 'selection') required.push('org/codehaus/plexus/archiver/zip/ConcurrentJarCreator', 'org/codehaus/plexus/archiver/zip/OffloadingOutputStream');
  for (const klass of required) assert.ok(observed.naturalOrigins.some(row => row.class === klass && row.jarRealm));
  if (name === 'boot') for (const klass of ['org/springframework/boot/maven/RepackageMojo', 'org/springframework/boot/loader/tools/Repackager']) {
    assert.ok(observed.naturalOrigins.some(row => row.class === klass && bootRealm.includes(row.coordinate)));
  }
  assert.equal(observed.symbolicConsumerCount, 5); assert.equal(observed.symbolicMemberCount, 53);
  assert.equal(observed.symbolicLinkageIsInvocationCoverage, false);
  const log = observed.classLoadCorroborationLog;
  assert.equal(typeof log.path, 'string'); assert.ok(log.path.endsWith(`/${variant}-${name}.class-load.log`));
  sha256(log.sha256); boundedInteger(log.bytes, 4 * 1024 * 1024); assert.ok(log.bytes > 0);
  const goals = caseGoals[name].filter(goal => goal !== 'boot:repackage');
  assert.deepEqual(observed.terminalEvents, goals.map((goal, i) =>
    [name === 'duplicate' && i === goals.length - 1 ? 'MojoFailed' : 'MojoSucceeded', versions[variant], ...goal.split('@')]));
  assert.ok(Array.isArray(observed.artifactEvents));
}

test('real offline paired JAR 3.5.0 / 3.5.1 goals and accepted Boot 4.0.8 compatibility', {
  skip: !ci && process.env.JAR_PLUGIN_COMPATIBILITY !== 'true' ? 'explicit reviewed JAR fixture opt-in required' : false,
}, () => {
  const seal = process.env.JAR_PLUGIN_COMPATIBILITY_SEAL;
  if (!ci) assert.ok(seal, 'reviewed sealed source/runtime/cache inputs required');
  const fixture = fileURLToPath(new URL(ci ? './fixtures/jar-plugin-portable.py' : './fixtures/jar-plugin-compatibility.py', import.meta.url));
  const run = spawnSync('python3', ['-I', '-S', '-B', fixture, ...(ci ? [] : ['--execute-seal', seal])],
    { encoding: 'utf8', timeout: ci ? 1200000 : 930000, maxBuffer: 4 * 1024 * 1024,
      env: { ...process.env, JAR_PLUGIN_COMPATIBILITY: 'true' } });
  assert.equal(run.status, 0, run.stdout + run.stderr);
  const receiptRecord = readReceipt(run.stdout, 'JAR_PLUGIN_RESULT');
  const portableRecord = ci ? readReceipt(run.stdout, 'JAR_PLUGIN_PORTABLE_RESULT') : undefined;
  const receipt = receiptRecord.value; const portable = portableRecord?.value;
  const pins = JSON.parse(readFileSync(fileURLToPath(new URL('./fixtures/jar-plugin-artifact-pins.json', import.meta.url)), 'utf8'));
  assert.equal(receipt.status, 'PASSED'); assert.equal(receipt.inputSealsUnchanged, true);
  assert.equal(receipt.scannerEvidence, false); assert.notEqual(receipt.productionActivationClaim, true);
  const plan = receipt.plan;
  assert.deepEqual(plan.variants, versions); assert.deepEqual(plan.caseGoals, caseGoals);
  for (const [field, expected] of Object.entries({ commandsPerVariant: 8, mavenCommands: 16, jarGoals: 28, bootGoals: 2,
    executableMainCommands: 2, javacCommands: 3, commandTimeoutSeconds: 60, aggregateTimeoutSeconds: 900,
    outerTimeoutSeconds: 930, outputTreeMaximumBytes: 256 * 1024 * 1024, outputTreeMaximumEntries: 2048,
    outputTreePollIntervalSeconds: 0.1, outputTreeBoundIsSampled: true, classLoadCorroborationMaximumBytes: 4 * 1024 * 1024,
    offline: true, sockets: false, scannerEvidence: false, productionActivationClaim: false, symbolicLinkageIsInvocationCoverage: false })) assert.equal(plan[field], expected);
  assert.deepEqual(plan.symbolicConsumers, ['AbstractJarMojo', 'JarMojo', 'TestJarMojo', 'MavenArchiver', 'BuildHelper']);
  const storage = receipt.outputTreeStorage;
  assert.equal(storage.maximumBytes, 256 * 1024 * 1024); assert.equal(storage.maximumEntries, 2048);
  assert.equal(storage.pollIntervalSeconds, 0.1); assert.equal(storage.sampledBound, true); assert.equal(storage.failure, null);
  boundedInteger(storage.peakObservedBytes, storage.maximumBytes); boundedInteger(storage.peakObservedEntries, storage.maximumEntries);
  assert.ok(Number.isSafeInteger(storage.scans) && storage.scans > 0);
  assert.deepEqual(storage.fileLimits, Object.fromEntries(Object.keys(versions).flatMap(variant =>
    Object.keys(caseGoals).map(name => [`${variant}-${name}.class-load.log`, 4 * 1024 * 1024]))));
  assert.equal(receipt.commands.length, 21);
  const commands = new Map(receipt.commands.map(command => [command.case, command])); assert.equal(commands.size, 21);
  const compile = commands.get('compile-observers'); commandComplete(compile, 0); assert.ok(compile.command[0].endsWith('/bin/javac'));
  assert.deepEqual(Object.keys(receipt.variants).sort(), ['baseline', 'candidate']);
  let jarGoals = 0; let bootGoals = 0; let mavenCommands = 0;
  for (const [variant, version] of Object.entries(versions)) {
    const cases = receipt.variants[variant];
    assert.deepEqual(Object.keys(cases).sort(), [...Object.keys(caseGoals), 'incrementalStates'].sort());
    for (const [name, goals] of Object.entries(caseGoals)) {
      const command = commands.get(variant + '-' + name); commandComplete(command, name === 'duplicate' ? 1 : 0);
      assert.ok(command.command[0].endsWith('/bin/mvn'));
      const actualGoals = command.command.filter(arg => arg.startsWith(jar) || arg.startsWith('org.springframework.boot:spring-boot-maven-plugin:'));
      assert.deepEqual(actualGoals, goals.map(goal => goal === 'boot:repackage' ? boot : jar + version + ':' + goal));
      for (const flag of ['-o', '-N', '--strict-checksums', '-s', '-gs', '-t', '-gt']) assert.ok(command.command.includes(flag));
      jarGoals += actualGoals.filter(goal => goal.startsWith(jar)).length; bootGoals += actualGoals.filter(goal => goal === boot).length; mavenCommands++;
      validateOrigins(cases[name], variant, name, pins, command.command[0]);
      const behavior = cases[name].behavior;
      if (name === 'selection') {
        assert.deepEqual(Object.keys(behavior).sort(), ['all', 'explicit', 'omitted']);
        for (const archive of Object.values(behavior)) {
          validateArchive(archive); assert.equal(archive.manifest['Multi-Release'], 'true');
          const offload = archive.offloading; assert.equal(offload.maximumThresholdBytes, 10000000);
          boundedInteger(offload.compressedMemberBytes, 32 * 1024 * 1024); assert.ok(offload.compressedMemberBytes > 10000000);
          assert.equal(offload.memberBytes, 11 * 1024 * 1024); assert.equal(offload.crcAndFullPayloadVerified, true);
          assert.ok(Number.isSafeInteger(offload.smallCompressedBytes) && offload.smallCompressedBytes > 0 && offload.smallCompressedBytes < 65536);
          assert.equal(archive.members['large/incompressible.bin'].bytes, offload.memberBytes);
        }
        assert.deepEqual(comparable(behavior.omitted), comparable(behavior.explicit));
      } else if (name === 'attachments') {
        assert.deepEqual(Object.keys(behavior).sort(), ['extra', 'main', 'tests']); Object.values(behavior).forEach(validateArchive);
        const attached = cases[name].artifactEvents.filter(row => row[0] === 'tests').map(row => row.slice(1, 4).join(':'));
        assert.deepEqual([...new Set(attached)].sort(), ['attached:jar:extra', 'attached:test-jar:tests', 'main:jar:-']);
      } else if (name === 'duplicate') assert.deepEqual(behavior, { failure: 'duplicate-main-artifact' });
      else {
        validateArchive(behavior);
        if (name === 'boot') {
          validateArchive(behavior.originalJar); assert.equal(behavior.manifest['Main-Class'], 'org.springframework.boot.loader.launch.JarLauncher');
          assert.equal(behavior.manifest['Start-Class'], 'JarBootFixture'); assert.ok(behavior.members['BOOT-INF/classes/JarBootFixture.class']);
        }
      }
      assert.deepEqual(comparable(receipt.variants.baseline[name].behavior), comparable(receipt.variants.candidate[name].behavior));
    }
    const states = cases.incrementalStates; assert.equal(states.length, 3);
    for (const state of states) { sha256(state.sha256); assert.ok(Number.isFinite(state.mtimeNs) && state.mtimeNs > 0); }
    assert.deepEqual(states[0], states[1]); assert.notEqual(states[0].sha256, states[2].sha256);
    const mainCompile = commands.get(variant + '-compile-main'); commandComplete(mainCompile, 0); assert.ok(mainCompile.command[0].endsWith('/bin/javac'));
    const main = commands.get(variant + '-archive-main'); commandComplete(main, 0);
    assert.ok(main.command[0].endsWith('/bin/java')); assert.equal(main.command.at(-2), '-jar'); assert.ok(main.command.at(-1).endsWith(`/${variant}-boot/target/fixture.jar`));
    assert.equal(main.stdoutSha256, createHash('sha256').update('JAR_BOOT_MAIN_OK\n').digest('hex'));
  }
  assert.equal(mavenCommands, 16); assert.equal(jarGoals, 28); assert.equal(bootGoals, 2);
  if (portable) {
    assert.equal(portable.status, 'PASSED'); boundedInteger(portable.resolutionCommandsRun, 2);
    assert.equal(portable.resolutionCommandsMaximum, 2); assert.equal(portable.commands.length, portable.resolutionCommandsRun);
    assert.equal(portable.existingArtifactHashesUnchanged, true); assert.equal(portable.centralPreparationOnly, true);
    assert.equal(portable.offlineMavenCommands, 16); assert.equal(portable.offlineJarGoals, 28); assert.equal(portable.offlineBootGoals, 2);
    assert.equal(portable.scannerEvidence, false); assert.equal(portable.productionActivationClaim, false); sha256(portable.executionSealSha256);
    for (const command of portable.commands) commandComplete(command, 0);
  }
  // Retain the complete validated evidence only after both receipts pass every check.
  const records = [['JAR_PLUGIN_RESULT', receiptRecord], ...(portableRecord ? [['JAR_PLUGIN_PORTABLE_RESULT', portableRecord]] : [])];
  const lines = records.map(([marker, record]) => {
    const json = record.json; assert.ok(Buffer.byteLength(json, 'utf8') <= maximumReceiptBytes, `${marker} receipt exceeds 8 MiB`);
    return marker + ' ' + json;
  });
  for (const line of lines) console.log(line);
});
