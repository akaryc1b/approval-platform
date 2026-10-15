import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';

// Run the actual live gate with stubbed process and file I/O. Never import the
// live module: on hosted CI importing it would execute Maven.
const url = new URL('./jar-plugin-live-compatibility.test.mjs', import.meta.url);
const source = readFileSync(url, 'utf8').replace(/^import .+;\n/gm, '')
  .replaceAll('import.meta.url', JSON.stringify(url.href));
const pinsPath = fileURLToPath(new URL('./fixtures/jar-plugin-artifact-pins.json', import.meta.url));
const pins = JSON.parse(readFileSync(pinsPath, 'utf8'));
const resultMarker = 'JAR_PLUGIN_RESULT ';
const portableMarker = 'JAR_PLUGIN_PORTABLE_RESULT ';
const resultPath = '/fixture/result.json';
const portablePath = '/fixture/portable-result.json';
const limit = 8 * 1024 * 1024;
const hash = 'a'.repeat(64);
const versions = { baseline: '3.5.0', candidate: '3.5.1' };
const caseGoals = {
  selection: ['jar@omitted', 'jar@explicit', 'jar@all'], empty: ['jar@empty-keep', 'jar@empty-skip'],
  'incremental-first': ['jar@main'], 'incremental-unchanged': ['jar@main'], 'incremental-changed': ['jar@main'],
  attachments: ['jar@main', 'jar@attached', 'test-jar@tests'], duplicate: ['jar@main', 'jar@duplicate'],
  boot: ['jar@main', 'boot:repackage'],
};
function command(label, args, exitStatus = 0) {
  return { case: label, command: args, status: 'EXITED', exitStatus, elapsedSeconds: 1.25,
    capturedBytes: 123, captureComplete: true, processGroupCleaned: true, stdoutSha256: hash, stderrSha256: hash };
}
function archive(extraMembers = {}) {
  const member = { sha256: hash, bytes: 30, crc32: 42, mode: 0o100644, directory: false, timestamp: [2024, 1, 2, 3, 4, 6] };
  return { archiveSha256: hash, manifest: { 'Manifest-Version': '1.0', 'Fixture-Identity': 'jar-plugin' },
    members: { 'META-INF/MANIFEST.MF': { ...member }, ...Object.fromEntries(Object.entries(extraMembers)
      .map(([name, bytes]) => [name, { ...member, bytes }])) } };
}
function origin(klass, coordinate, jarRealm = true) {
  const [group, artifact, , version] = coordinate.split(':');
  return { class: klass, coordinate, jarRealm, loader: jarRealm ? 'cafe' : 'beef', sha256: hash, entry: klass + '.class',
    path: '/cache/' + group.replaceAll('.', '/') + '/' + artifact + '/' + version + '/' + artifact + '-' + version + '.jar' };
}
function fixtures() {
  const receipt = { status: 'PASSED', inputSealsUnchanged: true, scannerEvidence: false,
    plan: { variants: versions, caseGoals, commandsPerVariant: 8, mavenCommands: 16, jarGoals: 28, bootGoals: 2,
      executableMainCommands: 2, javacCommands: 3, commandTimeoutSeconds: 60, aggregateTimeoutSeconds: 900,
      outerTimeoutSeconds: 930, outputTreeMaximumBytes: 256 * 1024 * 1024, outputTreeMaximumEntries: 2048,
      outputTreePollIntervalSeconds: 0.1, outputTreeBoundIsSampled: true, classLoadCorroborationMaximumBytes: 4 * 1024 * 1024,
      offline: true, sockets: false, scannerEvidence: false, productionActivationClaim: false, symbolicLinkageIsInvocationCoverage: false,
      symbolicConsumers: ['AbstractJarMojo', 'JarMojo', 'TestJarMojo', 'MavenArchiver', 'BuildHelper'] },
    outputTreeStorage: { maximumBytes: 256 * 1024 * 1024, maximumEntries: 2048, pollIntervalSeconds: 0.1,
      sampledBound: true, scans: 80, peakObservedBytes: 100000000, peakObservedEntries: 120, failure: null,
      fileLimits: Object.fromEntries(Object.keys(versions).flatMap(variant => Object.keys(caseGoals)
        .map(name => [`${variant}-${name}.class-load.log`, 4 * 1024 * 1024]))) },
    commands: [command('compile-observers', ['/runtime/bin/javac', 'JarRealmObserver.java'])], variants: {},
    retainedExtra: { unicode: '完整回执', nested: [1, true, null], sourceHashes: { 'fixture.py': hash } } };
  for (const [variant, version] of Object.entries(versions)) {
    const cases = receipt.variants[variant] = {};
    for (const [name, goals] of Object.entries(caseGoals)) {
      const jarCoordinate = 'org.apache.maven.plugins:maven-jar-plugin:jar:' + version;
      const archiverCoordinate = pins.variants[variant].find(value => value.startsWith('org.apache.maven:maven-archiver:'));
      const plexusCoordinate = pins.variants[variant].find(value => value.startsWith('org.codehaus.plexus:plexus-archiver:'));
      const natural = [origin('org/apache/maven/plugins/jar/JarMojo', jarCoordinate)];
      if (name !== 'empty') natural.push(origin('org/apache/maven/archiver/MavenArchiver', archiverCoordinate), origin('org/codehaus/plexus/archiver/jar/JarArchiver', plexusCoordinate));
      if (name === 'attachments') natural.push(origin('org/apache/maven/plugins/jar/TestJarMojo', jarCoordinate));
      if (name === 'selection') natural.push(origin('org/codehaus/plexus/archiver/zip/ConcurrentJarCreator', plexusCoordinate), origin('org/codehaus/plexus/archiver/zip/OffloadingOutputStream', plexusCoordinate));
      let behavior = archive({ 'ordinary.txt': 9 });
      if (name === 'selection') behavior = Object.fromEntries(['omitted', 'explicit', 'all'].map(classifier => {
        const value = archive({ 'large/incompressible.bin': 11 * 1024 * 1024 }); value.manifest['Multi-Release'] = 'true';
        value.offloading = { maximumThresholdBytes: 10000000, compressedMemberBytes: 11537856, memberBytes: 11 * 1024 * 1024,
          smallCompressedBytes: 79, crcAndFullPayloadVerified: true }; return [classifier, value];
      }));
      if (name === 'attachments') behavior = { main: archive(), extra: archive(), tests: archive() };
      if (name === 'duplicate') behavior = { failure: 'duplicate-main-artifact' };
      if (name === 'boot') {
        behavior = archive({ 'BOOT-INF/classes/JarBootFixture.class': 250 }); behavior.originalJar = archive({ 'JarBootFixture.class': 250 });
        behavior.manifest['Main-Class'] = 'org.springframework.boot.loader.launch.JarLauncher'; behavior.manifest['Start-Class'] = 'JarBootFixture';
        natural.push(origin('org/springframework/boot/maven/RepackageMojo', 'org.springframework.boot:spring-boot-maven-plugin:jar:4.0.8', false),
          origin('org/springframework/boot/loader/tools/Repackager', 'org.springframework.boot:spring-boot-loader-tools:jar:4.0.8', false));
      }
      const jarGoals = goals.filter(goal => goal !== 'boot:repackage');
      cases[name] = { behavior, actualJarRealm: [...pins.variants[variant]], naturalOrigins: natural,
        symbolicLoadOrigins: [origin('org/apache/maven/plugins/jar/AbstractJarMojo', jarCoordinate)],
        symbolicConsumerCount: 5, symbolicMemberCount: 53, symbolicLinkageIsInvocationCoverage: false,
        classLoadCorroborationLog: { path: `/fixture/${variant}-${name}.class-load.log`, bytes: 200000, sha256: hash },
        terminalEvents: jarGoals.map((goal, i) => [name === 'duplicate' && i === jarGoals.length - 1 ? 'MojoFailed' : 'MojoSucceeded', version, ...goal.split('@')]),
        artifactEvents: name === 'attachments' ? [['tests', 'main', 'jar', '-'], ['tests', 'attached', 'jar', 'extra'], ['tests', 'attached', 'test-jar', 'tests']] : [] };
      if (name === 'boot') cases[name].actualBootRealm = [...pins.boot];
      receipt.commands.push(command(`${variant}-${name}`, ['/runtime/bin/mvn', '-o', '-N', '--strict-checksums', '-s', '/fixture/settings.xml',
        '-gs', '/fixture/settings.xml', '-t', '/fixture/toolchains.xml', '-gt', '/fixture/toolchains.xml', ...goals.map(goal => goal === 'boot:repackage'
          ? 'org.springframework.boot:spring-boot-maven-plugin:4.0.8:repackage' : `org.apache.maven.plugins:maven-jar-plugin:${version}:${goal}`)], name === 'duplicate' ? 1 : 0));
    }
    cases.incrementalStates = [{ sha256: hash, mtimeNs: 1700000000000000000 }, { sha256: hash, mtimeNs: 1700000000000000000 },
      { sha256: 'b'.repeat(64), mtimeNs: 1700000004000000000 }];
    receipt.commands.push(command(`${variant}-compile-main`, ['/runtime/bin/javac', 'JarBootFixture.java']));
    const main = command(`${variant}-archive-main`, ['/runtime/bin/java', '-jar', `/fixture/${variant}-boot/target/fixture.jar`]);
    main.stdoutSha256 = createHash('sha256').update('JAR_BOOT_MAIN_OK\n').digest('hex'); receipt.commands.push(main);
  }
  const portable = { status: 'PASSED', resolutionCommandsRun: 2, resolutionCommandsMaximum: 2, existingArtifactHashesUnchanged: true,
    commands: ['baseline', 'candidate'].map(variant => command('prepare-' + variant, ['/runtime/bin/mvn', 'resolve-plugins'])),
    centralPreparationOnly: true, offlineMavenCommands: 16, offlineJarGoals: 28, offlineBootGoals: 2,
    scannerEvidence: false, productionActivationClaim: false, executionSealSha256: hash, retainedExtra: { nested: ['full portable receipt'] } };
  // Each negative test mutates an independent fixture, including plan arrays.
  return JSON.parse(JSON.stringify({ receipt, portable }));
}
function harness({ receipt, portable, env = { GITHUB_ACTIONS: 'true' }, run = {}, files = {} } = fixtures()) {
  const lines = []; const calls = []; const reads = []; const registered = [];
  const contents = { [resultPath]: JSON.stringify(receipt), [portablePath]: JSON.stringify(portable), [pinsPath]: JSON.stringify(pins), ...files };
  const stdout = 'UNREVIEWED CHILD OUTPUT\n' + resultMarker + JSON.stringify({ status: 'PASSED', path: resultPath }) + '\n'
    + portableMarker + JSON.stringify({ status: 'PASSED', path: portablePath }) + '\n';
  const context = vm.createContext({ assert, Buffer, URL, createHash, fileURLToPath, process: { env }, registered,
    console: { log(line) { lines.push(line); } }, test(name, options, callback) { registered.push({ name, options, callback }); },
    spawnSync(...args) { calls.push(args); return { status: 0, stdout, stderr: 'UNREVIEWED CHILD STDERR', ...run }; },
    readFileSync(path, encoding) { reads.push(path); assert.equal(encoding, 'utf8'); assert.equal(typeof path, 'string');
      if (!Object.hasOwn(contents, path)) throw new Error('missing receipt file'); return contents[path]; },
  });
  vm.runInContext(source, context, { timeout: 1000 }); assert.equal(registered.length, 1);
  return { lines, calls, reads, options: registered[0].options,
    execute() { vm.runInContext('registered[0].callback()', context, { timeout: 3000 }); } };
}

test('hosted JAR gate logs both complete validated receipts and bounds the same paired fixture', () => {
  const input = fixtures(); const observed = harness(input); observed.execute();
  assert.equal(observed.lines.length, 2);
  assert.deepEqual(JSON.parse(observed.lines[0].slice(resultMarker.length)), input.receipt);
  assert.deepEqual(JSON.parse(observed.lines[1].slice(portableMarker.length)), input.portable);
  assert.deepEqual(observed.reads, [resultPath, portablePath, pinsPath]); assert.equal(observed.calls.length, 1);
  const [program, args, options] = observed.calls[0]; assert.equal(program, 'python3');
  assert.deepEqual(Array.from(args.slice(0, 3)), ['-I', '-S', '-B']); assert.equal(args.length, 4);
  assert.ok(args[3].endsWith('/jar-plugin-portable.py')); assert.equal(options.timeout, 1200000);
  assert.equal(options.maxBuffer, 4 * 1024 * 1024); assert.equal(options.env.JAR_PLUGIN_COMPATIBILITY, 'true');
  assert.ok(observed.lines.every(line => !line.includes('UNREVIEWED CHILD')));
});

test('local JAR gate is inert by default, requires a seal, and retains the full local receipt', () => {
  const input = fixtures(); const inert = harness({ ...input, env: {} });
  assert.notEqual(inert.options.skip, false); assert.deepEqual(inert.calls, []); assert.deepEqual(inert.lines, []);
  const missing = harness({ ...input, env: { JAR_PLUGIN_COMPATIBILITY: 'true' } });
  assert.throws(() => missing.execute(), /sealed source/); assert.deepEqual(missing.calls, []);
  const local = harness({ ...input, env: { JAR_PLUGIN_COMPATIBILITY: 'true', JAR_PLUGIN_COMPATIBILITY_SEAL: '/reviewed/seal.json' } });
  local.execute(); assert.equal(local.options.skip, false); assert.equal(local.lines.length, 1);
  assert.deepEqual(JSON.parse(local.lines[0].slice(resultMarker.length)), input.receipt); assert.deepEqual(local.reads, [resultPath, pinsPath]);
  assert.ok(local.calls[0][1][3].endsWith('/jar-plugin-compatibility.py'));
  assert.deepEqual(Array.from(local.calls[0][1].slice(4)), ['--execute-seal', '/reviewed/seal.json']);
  assert.equal(local.calls[0][2].timeout, 930000); assert.equal(local.calls[0][2].maxBuffer, 4 * 1024 * 1024);
});

test('receipt emission preserves exact integers above 2^53 and escaped string whitespace', () => {
  const input = fixtures(); input.receipt.retainedExtra.escaped = 'line\nwith\ttab and "quotes"';
  const exact = '1792064500533451565';
  const raw = JSON.stringify(input.receipt, null, 2).replaceAll('1700000000000000000', exact);
  assert.equal(raw.split(exact).length - 1, 4);
  const observed = harness({ ...input, files: { [resultPath]: raw } }); observed.execute();
  assert.equal(observed.lines[0], resultMarker + raw.replace(/[\r\n\t]/g, ''));
  assert.equal(observed.lines[0].split(exact).length - 1, 4);
  assert.ok(!observed.lines[0].includes('1792064500533451500'));
  assert.deepEqual(JSON.parse(observed.lines[0].slice(resultMarker.length)).retainedExtra, input.receipt.retainedExtra);
});

test('JAR gate rejects bad counts, commands, origins, storage, behaviors and portable status before printing either receipt', () => {
  const mutations = [
    x => { x.run = { status: 1 }; }, x => { x.receipt.status = 'FAILED'; }, x => { x.receipt.inputSealsUnchanged = false; },
    x => { x.receipt.scannerEvidence = true; }, x => { x.receipt.plan.productionActivationClaim = true; },
    x => { x.receipt.commands.pop(); }, x => { x.receipt.commands[1].case = x.receipt.commands[0].case; },
    x => { x.receipt.commands[0].captureComplete = false; }, x => { x.receipt.commands[0].processGroupCleaned = false; },
    x => { x.receipt.commands[0].status = 'TIMED_OUT'; }, x => { x.receipt.commands[0].stdoutSha256 = 'truncated'; },
    x => { x.receipt.outputTreeStorage.failure = 'storage exceeded'; }, x => { x.receipt.outputTreeStorage.sampledBound = false; },
    x => { x.receipt.outputTreeStorage.peakObservedBytes = 256 * 1024 * 1024 + 1; }, x => { x.receipt.outputTreeStorage.peakObservedEntries = 2049; },
    x => { delete x.receipt.outputTreeStorage.fileLimits['baseline-boot.class-load.log']; },
    x => { x.portable.status = 'FAILED'; }, x => { x.portable.resolutionCommandsRun = 3; }, x => { x.portable.resolutionCommandsRun = -1; },
    x => { x.portable.existingArtifactHashesUnchanged = false; }, x => { x.portable.commands[0].exitStatus = 1; },
    x => { x.portable.commands[0].captureComplete = false; }, x => { x.portable.commands.pop(); },
  ];
  for (const field of ['mavenCommands', 'jarGoals', 'bootGoals', 'javacCommands', 'executableMainCommands']) mutations.push(x => { x.receipt.plan[field]++; });
  for (const variant of Object.keys(versions)) {
    for (const name of Object.keys(caseGoals)) {
      const at = x => x.receipt.variants[variant][name];
      mutations.push(x => { delete x.receipt.variants[variant][name]; }, x => { at(x).symbolicConsumerCount = 4; },
        x => { at(x).symbolicMemberCount = 52; }, x => { at(x).symbolicLinkageIsInvocationCoverage = true; },
        x => { at(x).actualJarRealm[0] = 'org.apache.maven.plugins:maven-jar-plugin:jar:0.0.0'; },
        x => { at(x).naturalOrigins[0].path = '/wrong/origin.jar'; }, x => { at(x).naturalOrigins[0].sha256 = 'short'; },
        x => { at(x).naturalOrigins[0].jarRealm = false; }, x => { at(x).classLoadCorroborationLog.bytes = 4 * 1024 * 1024 + 1; },
        x => { at(x).classLoadCorroborationLog.sha256 = 'short'; }, x => { at(x).terminalEvents.pop(); },
        x => { x.receipt.commands.find(c => c.case === `${variant}-${name}`).exitStatus = name === 'duplicate' ? 0 : 1; },
        x => { x.receipt.commands.find(c => c.case === `${variant}-${name}`).command.push('org.apache.maven.plugins:maven-jar-plugin:3.5.0:jar@extra'); });
    }
    mutations.push(x => { x.receipt.variants[variant].selection.behavior.omitted.offloading.compressedMemberBytes = 10000000; },
      x => { x.receipt.variants[variant].selection.behavior.omitted.offloading.crcAndFullPayloadVerified = false; },
      x => { x.receipt.variants[variant].empty.behavior.members['META-INF/MANIFEST.MF'].mode++; },
      x => { x.receipt.variants[variant].attachments.artifactEvents.pop(); },
      x => { x.receipt.variants[variant].boot.actualBootRealm = []; },
      x => { x.receipt.variants[variant].boot.behavior.manifest['Start-Class'] = 'WrongMain'; },
      x => { x.receipt.variants[variant].incrementalStates[1].sha256 = 'c'.repeat(64); },
      x => { x.receipt.commands.find(c => c.case === `${variant}-archive-main`).stdoutSha256 = hash; });
  }
  for (const [index, mutate] of mutations.entries()) {
    const input = fixtures(); mutate(input); const observed = harness(input);
    assert.throws(() => observed.execute(), undefined, `mutation ${index}`); assert.deepEqual(observed.lines, [], `mutation ${index} printed success evidence`);
  }
});

test('missing, duplicate, malformed, truncated, failed or unreadable JAR receipt pointers retain no success evidence', () => {
  for (const [marker, path] of [[resultMarker, resultPath], [portableMarker, portablePath]]) {
    const other = marker === resultMarker ? portableMarker + JSON.stringify({ status: 'PASSED', path: portablePath })
      : resultMarker + JSON.stringify({ status: 'PASSED', path: resultPath });
    const valid = marker + JSON.stringify({ status: 'PASSED', path });
    const faults = [{ run: { stdout: other + '\n' } }, { run: { stdout: [other, valid, valid].join('\n') } },
      { run: { stdout: [other, marker + '{'].join('\n') } }, { run: { stdout: [other, marker + '{}'].join('\n') } },
      { run: { stdout: [other, marker + JSON.stringify({ status: 'PASSED', path: 123 })].join('\n') } },
      { run: { stdout: [other, marker + JSON.stringify({ status: 'FAILED', path })].join('\n') } },
      { run: { stdout: [other, marker + JSON.stringify({ status: 'PASSED', path: '/missing.json' })].join('\n') } },
      { files: { [path]: '{malformed' } }, { files: { [path]: JSON.stringify({ status: 'PASSED' }) } },
      { files: { [path]: 'null' } }];
    for (const fault of faults) { const observed = harness({ ...fixtures(), ...fault }); assert.throws(() => observed.execute()); assert.deepEqual(observed.lines, []); }
  }
});

test('both JAR receipts retain every field at the UTF-8 size boundary and reject overflow before logging', () => {
  for (const key of ['receipt', 'portable']) {
    const input = fixtures(); input[key].padding = '';
    const remaining = limit - Buffer.byteLength(JSON.stringify(input[key]), 'utf8');
    input[key].padding = 'é'.repeat(Math.floor(remaining / 2)) + (remaining % 2 ? 'x' : '');
    assert.equal(Buffer.byteLength(JSON.stringify(input[key]), 'utf8'), limit);
    const exact = harness(input); exact.execute(); assert.equal(exact.lines.length, 2);
    assert.deepEqual(JSON.parse(exact.lines[key === 'receipt' ? 0 : 1].slice(key === 'receipt' ? resultMarker.length : portableMarker.length)), input[key]);
    input[key].padding += 'x'; const oversized = harness(input);
    assert.throws(() => oversized.execute(), /receipt exceeds 8 MiB/); assert.deepEqual(oversized.lines, []);
  }
});
