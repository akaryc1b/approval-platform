import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import { test } from 'node:test';

import {
  createSafeProcessOutput,
  maximumCheckedOutputBytes,
  maximumProcessLineBytes,
  safeProcessErrorCode,
} from '../product-readiness/pc-h5-runtime/safe-output.mjs';

const processModule = new URL('../product-readiness/pc-h5-runtime/processes.mjs', import.meta.url).href;
const restoreOnlyMarkers = [
  'DEMO_BACKEND_RESTORE_ONLY_STARTED',
  'BACKEND_LOCAL_RESTORE_ONLY_STARTED',
];
const jwt = ['eyJzeW50aGV0aWMiOnRydWV9', 'eyJjYW5hcnkiOiJvbmx5In0', 'not-a-real-signature'].join('.');
const password = 'SYNTHETIC-only-password-canary';
const cookie = 'SYNTHETIC-only-cookie-canary';
const encodedCanaries = [
  ...[jwt, password, cookie].flatMap(value => [
    Buffer.from(value).toString('base64'),
    [...value].map(character => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`).join(''),
    [...Buffer.from(value)].map(byte => `%${byte.toString(16).padStart(2, '0')}`).join(''),
  ]),
];
const secretLines = [
  `Authorization: Bearer ${jwt}`,
  `{"password":"${password}","headers":{"COOKIE":"session=${cookie}"}}`,
  `Set-Cookie: session=${cookie}; HttpOnly`,
  `request?access_token=${jwt}&password=${password}`,
  `plain unlabeled value ${password}`,
  `Error: failed with ${jwt}`,
  `WARN ${cookie}`,
  `# pass 9 ${password}`,
  `1 passed (1s) ${cookie}`,
  `[INFO] BUILD SUCCESS ${jwt}`,
  ...encodedCanaries,
  `password:\n${password}\n  41 | const password = '${password}';`,
  `Authorization:\n  Bearer\n  ${jwt}`,
];

function assertNoCanaries(value) {
  for (const canary of [jwt, password, cookie, ...encodedCanaries]) {
    assert.equal(value.includes(canary), false, 'a synthetic credential reached an output sink');
  }
}

function capture() {
  const values = [];
  const recorder = createSafeProcessOutput({ emit: value => values.push(value) });
  return { recorder, output: () => values.join('') };
}

function runFixture(source, environment = process.env) {
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', source], {
    encoding: 'utf8',
    env: environment,
    maxBuffer: 1_024 * 1_024,
    timeout: 15_000,
  });
  assert.equal(result.error, undefined);
  assert.equal(result.status, 0, result.stderr);
  const output = `${result.stdout}${result.stderr}`;
  assertNoCanaries(output);
  return output;
}

function managedRestoreFixture(chunks, expectedMarkers = []) {
  const directory = mkdtempSync(join(tmpdir(), 'approval-safe-restore-'));
  const logFile = join(directory, 'managed.log');
  try {
    const childSource = `const chunks = ${JSON.stringify(chunks)};
      for (const [pipe, chunk] of chunks) {
        process[pipe].write(chunk);
        await new Promise(resolve => setTimeout(resolve, 5));
      }`;
    const output = runFixture(`
      import assert from 'node:assert/strict';
      import { once } from 'node:events';
      import { startManagedNode, waitForMarker } from ${JSON.stringify(processModule)};
      const managed = startManagedNode('Synthetic restore',
        ['--input-type=module', '-e', ${JSON.stringify(childSource)}],
        ${JSON.stringify(logFile)}, process.env);
      await once(managed.child, 'close');
      const expected = ${JSON.stringify(expectedMarkers)};
      for (const marker of ${JSON.stringify(restoreOnlyMarkers)}) {
        assert.equal(managed.state.buffer.includes(marker), expected.includes(marker));
        if (expected.includes(marker)) await waitForMarker(managed, marker, 100);
        else await assert.rejects(waitForMarker(managed, marker, 100), /exited before/u);
      }
      console.log('STATE=' + managed.state.buffer);
    `);
    const log = readFileSync(logFile, 'utf8');
    assertNoCanaries(log);
    for (const marker of restoreOnlyMarkers) {
      assert.equal(log.includes(marker), expectedMarkers.includes(marker));
      assert.equal(output.includes(marker), expectedMarkers.includes(marker));
    }
    return { output, log };
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

test('only fixed markers and bounded numeric summaries survive the output gate', () => {
  const { recorder, output } = capture();
  recorder.write(Buffer.from([
    ...secretLines,
    '\u001b[32m[INFO] BUILD SUCCESS\u001b[0m',
    '# tests 18', '# pass 17', '# fail 1', '# duration_ms 4.125',
    '[INFO] Tests run: 18, Failures: 1, Errors: 0, Skipped: 2',
    '12 passed (1.2s)',
    `2026-10-09 INFO Demo : PURCHASE_PAYMENT_LOCAL_SANDBOX_STARTED endpoint=${jwt}`,
    `PURCHASE_PAYMENT_DEMO_SEED_APPLIED password=${password}`,
    'BACKEND_LOCAL_START_VERIFIED',
  ].join('\n')));
  recorder.end();
  assertNoCanaries(output());
  assert.match(output(), /\[INFO\] BUILD SUCCESS\n/u);
  assert.match(output(), /# tests 18\n# pass 17\n# fail 1\n# duration_ms 4.125\n/u);
  assert.match(output(), /Tests run: 18, Failures: 1, Errors: 0, Skipped: 2\n/u);
  assert.match(output(), /12 passed\n/u);
  assert.match(output(), /PURCHASE_PAYMENT_LOCAL_SANDBOX_STARTED\n/u);
  assert.match(output(), /PURCHASE_PAYMENT_DEMO_SEED_APPLIED\n/u);
  assert.match(output(), /Error reported/u);
  assert.match(output(), /warning reported/u);
  assert.doesNotMatch(output(), /# pass 9|1 passed|endpoint=/u);
});

test('all chunk boundaries, including UTF-8 splits, are safe before emission', () => {
  const bytes = Buffer.from(`前缀 ${secretLines.join('\n')}\nBACKEND_LOCAL_START_VERIFIED\n`);
  for (let split = 0; split <= bytes.length; split += 1) {
    const { recorder, output } = capture();
    recorder.write(bytes.subarray(0, split));
    assertNoCanaries(output());
    recorder.write(bytes.subarray(split));
    recorder.end();
    assertNoCanaries(output());
    assert.match(output(), /BACKEND_LOCAL_START_VERIFIED\n/u);
  }
  const { recorder, output } = capture();
  for (const byte of bytes) recorder.write(Buffer.from([byte]));
  recorder.end();
  assertNoCanaries(output());
});

test('separate stdout/stderr framers never assemble a protocol marker across pipes', () => {
  const values = [];
  const stdout = createSafeProcessOutput({ emit: value => values.push(value) });
  const stderr = createSafeProcessOutput({ emit: value => values.push(value) });
  stdout.write('BACKEND_LOCAL_');
  stderr.write('START_VERIFIED\n');
  stdout.write(`${password}\n`);
  stdout.end();
  stderr.end();
  assertNoCanaries(values.join(''));
  assert.doesNotMatch(values.join(''), /BACKEND_LOCAL_START_VERIFIED/u);
});

test('oversized unterminated lines are dropped until a newline, with no suffix fallback', () => {
  const { recorder, output } = capture();
  for (let index = 0; index < 128; index += 1) {
    recorder.write(Buffer.alloc(maximumProcessLineBytes, 120));
  }
  recorder.write(`${password} BACKEND_LOCAL_START_VERIFIED\n# tests 7\n`);
  recorder.end();
  assert.equal(output(), '[subprocess] unstructured output omitted\n# tests 7\n');
  assertNoCanaries(output());
});

test('unterminated failures and numeric floods stay bounded and fail closed', () => {
  const { recorder, output } = capture();
  for (let index = 0; index < 10_000; index += 1) recorder.write(`# tests ${index}\n`);
  recorder.write(`Error: ${password}`);
  recorder.end();
  recorder.end();
  assertNoCanaries(output());
  assert.equal((output().match(/# tests /gu) ?? []).length, 64);
  assert.match(output(), /further numeric summaries omitted/u);
  assert.match(output(), /Error reported/u);
  assert.ok(output().length < 2_048);
});

test('Quick Start control values remain internal and are never logged', () => {
  const values = [];
  const controls = [];
  const recorder = createSafeProcessOutput({
    emit: value => values.push(value),
    onControlLine: value => controls.push(value),
  });
  recorder.write(`QUICK_START_PC_URL=http://localhost/?password=${password}\n`);
  recorder.write('QUICK_START_EVIDENCE=/synthetic/run\n');
  recorder.write('QUICK_START_EVIDENCE=/synthetic/duplicate\n');
  recorder.end();
  assertNoCanaries(values.join(''));
  assert.equal(controls.length, 2);
  assert.equal(controls[1], 'QUICK_START_EVIDENCE=/synthetic/run\n');
  assert.match(values.join(''), /QUICK_START_PC_URL=\[value withheld\]/u);
});

test('checked Node commands sanitize both successful and failed stdout/stderr', () => {
  for (const exitCode of [0, 3]) {
    const childSource = `process.stdout.write(${JSON.stringify(secretLines.join('\n'))});`
      + `process.stderr.write(${JSON.stringify(`\nError: ${password}\n# tests 8\n`)});`
      + `process.exitCode = ${exitCode};`;
    const output = runFixture(`
      import { runNodeChecked } from ${JSON.stringify(processModule)};
      try {
        runNodeChecked('Synthetic child', ['-e', ${JSON.stringify(childSource)}]);
      } catch (error) { console.log(error.message); }
    `);
    assert.match(output, /# tests 8/u);
    if (exitCode) assert.match(output, /Synthetic child failed with exit code 3/u);
  }
});

test('root-install-style pnpm output cannot bypass the checked output gate', {
  skip: process.platform === 'win32',
}, () => {
  const directory = mkdtempSync(join(tmpdir(), 'approval-safe-pnpm-'));
  try {
    writeFileSync(join(directory, 'pnpm'), `#!${process.execPath}\n`
      + `process.stdout.write(${JSON.stringify(`${jwt}\n# tests 3\n`)});\n`
      + `process.stderr.write(${JSON.stringify(`Error: ${password}\n`)});\n`
      + 'process.exitCode = 4;\n', { mode: 0o700 });
    const output = runFixture(`
      import { runPnpmChecked } from ${JSON.stringify(processModule)};
      try { runPnpmChecked('Synthetic install', ['install']); }
      catch (error) { console.log(error.message); }
    `, { ...process.env, PATH: `${directory}${delimiter}${process.env.PATH ?? ''}` });
    assert.match(output, /# tests 3/u);
    assert.match(output, /Synthetic install failed with exit code 4/u);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('managed output is sanitized before console, file, and ordinary state retention', () => {
  const directory = mkdtempSync(join(tmpdir(), 'approval-safe-managed-'));
  const logFile = join(directory, 'managed.log');
  try {
    const childSource = `const data = ${JSON.stringify(`${secretLines.join('\n')}\nBACKEND_LOCAL_START_VERIFIED\n`)};`
      + 'for (const byte of Buffer.from(data)) process.stdout.write(Buffer.from([byte]));'
      + `process.stderr.write(${JSON.stringify(`Error: ${cookie}`)});`;
    const output = runFixture(`
      import { once } from 'node:events';
      import { startManagedNode, waitForMarker } from ${JSON.stringify(processModule)};
      const managed = startManagedNode('Synthetic managed', ['-e', ${JSON.stringify(childSource)}],
        ${JSON.stringify(logFile)}, process.env);
      await once(managed.child, 'close');
      await waitForMarker(managed, 'BACKEND_LOCAL_START_VERIFIED', 100);
      console.log('STATE=' + managed.state.buffer);
    `);
    const log = readFileSync(logFile, 'utf8');
    assertNoCanaries(log);
    assert.match(log, /BACKEND_LOCAL_START_VERIFIED/u);
    assert.match(log, /Error reported/u);
    assert.match(output, /STATE=/u);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('restore-only fixed lines survive every chunk split only after line completion', () => {
  for (const marker of restoreOnlyMarkers) {
    for (let split = 0; split <= marker.length; split += 1) {
      const { recorder, output } = capture();
      recorder.write(marker.slice(0, split));
      recorder.write(marker.slice(split));
      assert.equal(output(), '', 'an unfinished line must not establish readiness');
      recorder.write('\n');
      assert.equal(output(), `${marker}\n`);
      recorder.end();
      assert.equal(output(), `${marker}\n`);
    }
  }
});

test('restore-only fixed lines reach managed readiness with LF and existing ANSI trim or EOF semantics', () => {
  for (const marker of restoreOnlyMarkers) {
    for (const [pipe, prefix, suffix] of [
      ['stdout', '', '\n'],
      ['stderr', ' \u001b[32m', '\u001b[0m \r\n'],
      ['stdout', '', ''],
    ]) {
      const { log } = managedRestoreFixture([
        [pipe, `${password}\n`],
        [pipe, prefix + marker.slice(0, 12)],
        [pipe, marker.slice(12) + suffix],
      ], [marker]);
      assert.equal(log, `[subprocess] unstructured output omitted\n${marker}\n`);
    }
  }
});

test('restore-only managed readiness rejects non-fixed lines and never projects their variable tails', () => {
  for (const marker of restoreOnlyMarkers) {
    for (const line of [
      `${marker}_EXTRA`,
      `EXTRA_${marker}`,
      `prefix ${marker}`,
      `${marker} suffix`,
      `2026-10-16 INFO Demo : ${marker} value=${password}`,
      `BROWSER_FAILURE_V1 phase=PC_AUDIT category=FAILED reason=UNKNOWN ${marker} ${password}`,
      `${' '.repeat(maximumProcessLineBytes + 1)}${marker}`,
      'PURCHASE_PAYMENT_DEMO_RESTORE_ONLY_STARTED',
      `PURCHASE_PAYMENT_DEMO_RESTORE_ONLY_STARTED seedEnabled=false restoredDataVerified=false ${password}`,
    ]) {
      const { output, log } = managedRestoreFixture([['stdout', `${line}\n`]]);
      assert.equal(log, '[subprocess] unstructured output omitted\n');
      assert.doesNotMatch(output, /PURCHASE_PAYMENT_DEMO_RESTORE_ONLY_STARTED/u);
    }
    const { log } = managedRestoreFixture([
      ['stdout', marker.slice(0, 12)],
      ['stderr', `${marker.slice(12)}\n`],
      ['stdout', '\n'],
    ]);
    assert.equal(log, '[subprocess] unstructured output omitted\n[subprocess] unstructured output omitted\n');
  }
});

test('unterminated restore-only managed output cannot establish readiness before EOF', () => {
  for (const marker of restoreOnlyMarkers) {
    const directory = mkdtempSync(join(tmpdir(), 'approval-safe-restore-pending-'));
    const logFile = join(directory, 'managed.log');
    try {
      const childSource = `process.stdout.write(${JSON.stringify(marker)});
        setTimeout(() => process.stdout.write(${JSON.stringify(` suffix ${password}\n`)}), 1_500);`;
      const output = runFixture(`
        import assert from 'node:assert/strict';
        import { once } from 'node:events';
        import { startManagedNode, terminateManaged, waitForMarker } from ${JSON.stringify(processModule)};
        const managed = startManagedNode('Synthetic pending restore', ['-e', ${JSON.stringify(childSource)}],
          ${JSON.stringify(logFile)}, process.env);
        const closed = once(managed.child, 'close');
        try {
          await once(managed.child.stdout, 'data');
          assert.equal(managed.state.buffer.includes(${JSON.stringify(marker)}), false);
          await assert.rejects(waitForMarker(managed, ${JSON.stringify(marker)}, 1), /did not emit/u);
          await closed;
          assert.equal(managed.state.buffer.includes(${JSON.stringify(marker)}), false);
          console.log('STATE=' + managed.state.buffer);
        } finally {
          terminateManaged(managed);
          await closed;
        }
      `);
      const log = readFileSync(logFile, 'utf8');
      assertNoCanaries(log);
      assert.doesNotMatch(output, new RegExp(marker, 'u'));
      assert.equal(log, '[subprocess] unstructured output omitted\n');
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  }
});

test('fixed error types and scalar assertion diagnostics preserve safe failure context', () => {
  const { recorder, output } = capture();
  recorder.write([
    `TypeError: ${password}`,
    `AssertionError [ERR_ASSERTION]: ${cookie}`,
    "  code: 'ERR_ASSERTION'", "  operator: 'strictEqual'",
    '  expected: 4', '  actual: 3',
    `  expected: '${password}'`,
    'ℹ tests 3', 'ℹ fail 1',
  ].join('\n'));
  recorder.end();
  assertNoCanaries(output());
  assert.match(output(), /TypeError reported/u);
  assert.match(output(), /AssertionError reported/u);
  assert.match(output(), /code: 'ERR_ASSERTION'/u);
  assert.match(output(), /expected: 4\nactual: 3/u);
  assert.match(output(), /# tests 3\n# fail 1/u);
});

test('sanitizer and sink exceptions have no raw-message or partial-output fallback', () => {
  for (const callback of ['emit', 'onControlLine']) {
    const values = [];
    const recorder = createSafeProcessOutput({
      emit: value => values.push(value),
      [callback]: () => { throw new Error(password); },
    });
    const input = callback === 'emit' ? `Error: ${jwt}\n` : `QUICK_START_EVIDENCE=${cookie}\n`;
    assert.throws(() => recorder.write(input), error => {
      assert.equal(error.message, 'subprocess output sanitization failed');
      assert.equal(error.cause, undefined);
      assertNoCanaries(error.stack);
      return true;
    });
    assert.throws(() => recorder.write(`${password}\n`), /output sanitization failed/u);
    recorder.end();
    assertNoCanaries(values.join(''));
  }
});

test('checked output overflow, timeout, and spawn validation errors never include raw details', () => {
  const output = runFixture(`
    import { runNodeChecked } from ${JSON.stringify(processModule)};
    for (const args of [
      ['-e', ${JSON.stringify(`process.stdout.write(${JSON.stringify(password)}); process.stdout.write(Buffer.alloc(${maximumCheckedOutputBytes + 65_536}, 120));`)}],
      ['-e', ${JSON.stringify(`process.stderr.write(${JSON.stringify(password)}); setInterval(() => {}, 1000);`)}],
      ['-e', ${JSON.stringify(`${password}\u0000`)}],
    ]) {
      try { runNodeChecked('Synthetic bounded', args, process.env, 250); }
      catch (error) { console.log(error.message); }
    }
  `);
  assert.match(output, /ENOBUFS/u);
  assert.match(output, /ETIMEDOUT/u);
  assert.match(output, /PROCESS_ERROR/u);
  assert.equal(safeProcessErrorCode({ code: password, message: jwt }), 'PROCESS_ERROR');
  assert.equal(safeProcessErrorCode({ code: 'ENOENT', message: password }), 'ENOENT');
});
