import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';

// Execute the actual live-test body with in-memory receipts and stubbed I/O only.
// Importing the live module directly would enable Maven on hosted CI.
const url = new URL('./assembly-plexus-plugin-live-compatibility.test.mjs', import.meta.url);
const source = readFileSync(url, 'utf8').replace(/^import .+;\n/gm, '')
  .replaceAll('import.meta.url', JSON.stringify(url.href));
const resultMarker = 'ASSEMBLY_PLEXUS_RESULT ';
const portableMarker = 'ASSEMBLY_PLEXUS_PORTABLE_RESULT ';
const resultPath = '/fixture/result.json';
const portablePath = '/fixture/portable-result.json';
const limit = 8 * 1024 * 1024;

function fixtures() {
  const cases = ['archives', 'discovery', 'missing-main', 'malformed-main', 'missing-component', 'malformed-component'];
  const goal = 'org.apache.maven.plugins:maven-assembly-plugin:3.7.1:single';
  const command = { status: 'EXITED', processGroupCleaned: true, captureComplete: true,
    exitStatus: 0, elapsedSeconds: 1.25, capturedBytes: 123, stdoutSha256: 'a'.repeat(64), stderrSha256: 'b'.repeat(64) };
  const receipt = { status: 'PASSED', inputSealsUnchanged: true, scannerEvidence: false, productionActivationClaim: false,
    plan: { goal, cases, actualGoalCount: 12, commandTimeoutSeconds: 60, aggregateTimeoutSeconds: 900 },
    commands: [{ ...command, command: ['/runtime/bin/javac', 'Observer.java'] },
      ...Array.from({ length: 12 }, (_, i) => ({ ...command, case: `case-${i}`, command: ['/runtime/bin/mvn', '-o', goal] }))],
    variants: Object.fromEntries(['baseline', 'candidate'].map(variant => [variant, {
      coverage: { symbolicallyResolvedConsumers: 55, effectiveMultiReleaseClasses: ['META-INF/versions/11/Example.class'] },
      ...Object.fromEntries(cases.map(name => [name, { missingMemberControl: 'PASSED', behavior: { case: name },
        naturalOrigins: [{ class: 'org/example/Consumer', origin: `file:/cache/${variant}.jar` }],
        resolvedMembers: [{ owner: 'org/example/Consumer', name: 'read', descriptor: '()V' }] }])),
    }])),
    expectedArchiveDelta: Object.fromEntries(['dir', 'zip', 'jar', 'tar'].map(format => [format, {
      removedRegularFiles: Array.from({ length: 8 }, (_, i) => `removed-${i}`), explicitFalseUnchanged: true,
      retainedFilesEqual: true, allOtherMetadataEqual: true, directoryChanges: { '.gitignore': { baselineMode: '0750', candidateMode: '0755' } },
    }])),
    inventory: { consumers: [{ classEntry: 'org/example/Consumer.class', memberReferences: ['read()V'] }] },
    effectiveInventory: { entries: ['META-INF/versions/11/Example.class'] },
    sourceHashes: { 'fixture.py': 'c'.repeat(64) }, runtimeHashes: { '/runtime/bin/java': 'd'.repeat(64) },
    cacheHashes: { '/cache/plugin.jar': 'e'.repeat(64) }, retainedExtra: { unicode: '完整回执', nested: [1, true, null] },
  };
  const portable = { status: 'PASSED', resolutionCommandsRun: 2, existingArtifactHashesUnchanged: true,
    commands: [{ ...command, command: ['/runtime/bin/mvn', 'resolve-plugins'] }],
    resolutionCommandsMaximum: 2, centralPreparationOnly: true, offlineGoalCount: 12, acceptanceGoal: false,
    executionSealSha256: 'f'.repeat(64), retainedExtra: { nested: ['full portable receipt'] } };
  return { receipt, portable };
}

function harness({ receipt, portable, env = { GITHUB_ACTIONS: 'true' }, run = {}, files = {} } = fixtures()) {
  const lines = []; const calls = []; const reads = []; const registered = [];
  const contents = { [resultPath]: JSON.stringify(receipt), [portablePath]: JSON.stringify(portable), ...files };
  const stdout = 'UNREVIEWED CHILD OUTPUT\n' + resultMarker + JSON.stringify({ status: 'PASSED', path: resultPath }) + '\n'
    + portableMarker + JSON.stringify({ status: 'PASSED', path: portablePath }) + '\n';
  const context = vm.createContext({ assert, Buffer, URL, fileURLToPath, process: { env }, registered,
    console: { log(line) { lines.push(line); } },
    test(name, options, callback) { registered.push({ name, options, callback }); },
    spawnSync(...args) { calls.push(args); return { status: 0, stdout, stderr: 'UNREVIEWED CHILD STDERR', ...run }; },
    readFileSync(path, encoding) {
      reads.push(path); assert.equal(encoding, 'utf8');
      assert.equal(typeof path, 'string', 'receipt path must be a string');
      if (!Object.hasOwn(contents, path)) throw new Error('missing receipt file');
      return contents[path];
    },
  });
  vm.runInContext(source, context, { timeout: 1000 });
  assert.equal(registered.length, 1);
  return { lines, calls, reads, options: registered[0].options,
    execute() { vm.runInContext('registered[0].callback()', context, { timeout: 2000 }); } };
}

test('hosted Assembly logs exactly both full receipts and preserves child bounds', () => {
  const input = fixtures(); const observed = harness(input); observed.execute();
  assert.equal(observed.lines.length, 2);
  assert.ok(observed.lines[0].startsWith(resultMarker)); assert.ok(observed.lines[1].startsWith(portableMarker));
  assert.deepEqual(JSON.parse(observed.lines[0].slice(resultMarker.length)), input.receipt);
  assert.deepEqual(JSON.parse(observed.lines[1].slice(portableMarker.length)), input.portable);
  assert.deepEqual(observed.reads, [resultPath, portablePath]);
  assert.equal(observed.calls.length, 1);
  const [program, args, options] = observed.calls[0];
  assert.equal(program, 'python3');
  assert.deepEqual(Array.from(args.slice(0, 3)), ['-I', '-S', '-B']);
  assert.ok(args[3].endsWith('/assembly-plexus-portable.py')); assert.equal(args.length, 4);
  assert.equal(options.timeout, 1200000); assert.equal(options.maxBuffer, 4 * 1024 * 1024);
  assert.equal(options.env.ASSEMBLY_PLEXUS_COMPATIBILITY, 'true');
  assert.ok(observed.lines.every(line => !line.includes('UNREVIEWED CHILD')));
});

test('local Assembly retains only the full live receipt and preserves opt-in and deadline', () => {
  const input = fixtures();
  const inert = harness({ ...input, env: {} });
  assert.notEqual(inert.options.skip, false); assert.equal(inert.calls.length, 0); assert.deepEqual(inert.lines, []);
  const missingSeal = harness({ ...input, env: { ASSEMBLY_PLEXUS_COMPATIBILITY: 'true' } });
  assert.throws(() => missingSeal.execute(), /sealed source/);
  assert.equal(missingSeal.calls.length, 0); assert.deepEqual(missingSeal.lines, []);
  const local = harness({ ...input, env: { ASSEMBLY_PLEXUS_COMPATIBILITY: 'true', ASSEMBLY_PLEXUS_COMPATIBILITY_SEAL: '/reviewed/seal.json' } });
  local.execute(); assert.equal(local.options.skip, false); assert.equal(local.lines.length, 1);
  assert.deepEqual(JSON.parse(local.lines[0].slice(resultMarker.length)), input.receipt);
  assert.deepEqual(local.reads, [resultPath]);
  assert.ok(local.calls[0][1][3].endsWith('/assembly-plexus-compatibility.py'));
  assert.deepEqual(Array.from(local.calls[0][1].slice(4)), ['--execute-seal', '/reviewed/seal.json']);
  assert.equal(local.calls[0][2].timeout, 930000); assert.equal(local.calls[0][2].maxBuffer, 4 * 1024 * 1024);
});

test('every original Assembly success assertion must pass before either receipt is emitted', () => {
  const mutations = [
    x => { x.run = { status: 1 }; },
    x => { x.portable.status = 'FAILED'; }, x => { x.portable.resolutionCommandsRun = 3; },
    x => { x.portable.existingArtifactHashesUnchanged = false; },
    x => { x.receipt.status = 'FAILED'; }, x => { x.receipt.inputSealsUnchanged = false; },
    x => { x.receipt.scannerEvidence = true; }, x => { x.receipt.productionActivationClaim = true; },
    x => { x.receipt.commands.pop(); }, x => { x.receipt.commands[0].status = 'TIMED_OUT'; },
    x => { x.receipt.commands[0].processGroupCleaned = false; }, x => { x.receipt.commands[0].captureComplete = false; },
    x => { delete x.receipt.expectedArchiveDelta.tar; },
  ];
  for (const variant of ['baseline', 'candidate']) {
    mutations.push(x => { x.receipt.variants[variant].coverage.symbolicallyResolvedConsumers = 54; });
    for (const name of fixtures().receipt.plan.cases) {
      mutations.push(x => { x.receipt.variants[variant][name].missingMemberControl = 'FAILED'; });
      if (name !== 'archives') mutations.push(x => { x.receipt.variants[variant][name].behavior = { changed: true }; });
    }
  }
  for (const format of ['dir', 'zip', 'jar', 'tar']) {
    mutations.push(x => { x.receipt.expectedArchiveDelta[format].removedRegularFiles.pop(); });
    for (const field of ['explicitFalseUnchanged', 'retainedFilesEqual', 'allOtherMetadataEqual']) {
      mutations.push(x => { x.receipt.expectedArchiveDelta[format][field] = false; });
    }
  }
  for (const [index, mutate] of mutations.entries()) {
    const input = fixtures(); mutate(input); const observed = harness(input);
    assert.throws(() => observed.execute(), { name: 'AssertionError' }, `mutation ${index}`);
    assert.deepEqual(observed.lines, [], `mutation ${index} emitted success evidence`);
  }
});

test('missing, duplicate, malformed or unreadable Assembly receipt pointers fail without success output', () => {
  for (const [marker, path] of [[resultMarker, resultPath], [portableMarker, portablePath]]) {
    const other = marker === resultMarker ? portableMarker + JSON.stringify({ path: portablePath })
      : resultMarker + JSON.stringify({ path: resultPath });
    const valid = marker + JSON.stringify({ path });
    const cases = [
      { run: { stdout: other + '\n' } },
      { run: { stdout: [other, valid, valid].join('\n') } },
      { run: { stdout: [other, marker + '{'].join('\n') } },
      { run: { stdout: [other, marker + '{}'].join('\n') } },
      { run: { stdout: [other, marker + JSON.stringify({ path: 123 })].join('\n') } },
      { run: { stdout: [other, marker + JSON.stringify({ path: '/missing.json' })].join('\n') } },
      { files: { [path]: '{malformed' } },
    ];
    for (const fault of cases) {
      const observed = harness({ ...fixtures(), ...fault });
      assert.throws(() => observed.execute()); assert.deepEqual(observed.lines, []);
    }
  }
});

test('Assembly receipt bound counts UTF-8 bytes and rejects either oversized object before logging', () => {
  for (const key of ['receipt', 'portable']) {
    const input = fixtures(); input[key].padding = '';
    const remaining = limit - Buffer.byteLength(JSON.stringify(input[key]), 'utf8');
    input[key].padding = 'é'.repeat(Math.floor(remaining / 2)) + (remaining % 2 ? 'x' : '');
    assert.equal(Buffer.byteLength(JSON.stringify(input[key]), 'utf8'), limit);
    const exact = harness(input); exact.execute(); assert.equal(exact.lines.length, 2);
    assert.deepEqual(JSON.parse(exact.lines[key === 'receipt' ? 0 : 1].slice(key === 'receipt' ? resultMarker.length : portableMarker.length)), input[key]);
    input[key].padding += 'x';
    const oversized = harness(input);
    assert.throws(() => oversized.execute(), /receipt exceeds 8 MiB/); assert.deepEqual(oversized.lines, []);
  }
});
