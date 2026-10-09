import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import CaptureFailureReporter, { browserFailureCategories, browserFailureReasons, captureFailurePhase, captureFailurePhases, captureFailureReason, capturePageErrorContext, browserContextEngines, browserPageErrorKinds } from '../../apps/web/overlay/playground/__tests__/e2e/product-readiness-capture-diagnostics.ts';
import { createSafeProcessOutput, safeFailureDiagnostic } from '../product-readiness/pc-h5-runtime/safe-output.mjs';
import { publicationRejectionCategories } from '../product-readiness/artifact-privacy/publication.mjs';
import { relevantChangeSet } from '../product-readiness/pc-h5-runtime/ci-scope.mjs';

import { observeCaptureFailures, capturePageErrorKinds } from '../../apps/web/overlay/playground/__tests__/e2e/product-readiness-capture.ts';

const canary = ['SYNTHETIC', 'ONLY', 'PRIVATE', 'DIAGNOSTIC', '42'].join('-');
const hostile = `${canary} https://fixture.invalid/private?password=${canary} /private/${canary} actor-${canary}`;
function captureReporter(probe) {
  const saved = console.log;
  const lines = [];
  console.log = value => lines.push(value);
  try { probe(new CaptureFailureReporter()); }
  finally { console.log = saved; }
  return lines;
}
function projected(lines) {
  const output = [];
  const controls = [];
  const recorder = createSafeProcessOutput({ emit: value => output.push(value), onControlLine: value => controls.push(value) });
  for (const line of lines) recorder.write(`${line}\n`);
  recorder.end();
  assert.deepEqual(controls, [], 'failure diagnostics must never become readiness/control evidence');
  return output.join('');
}
const annotated = phase => ({ annotations: [{ type: 'capture-failure-phase-v1', description: phase }] });

await test('closed reporter vocabularies are immutable and every emitted phase/category survives safe projection', () => {
  assert.equal(Object.isFrozen(captureFailurePhases), true);
  assert.equal(Object.isFrozen(browserFailureCategories), true);
  assert.throws(() => captureFailurePhases.push(canary), TypeError);
  for (const phase of captureFailurePhases) {
    for (const [status, category] of [['failed', 'FAILED'], ['timedOut', 'TIMED_OUT'], ['interrupted', 'INTERRUPTED'], [canary, 'UNAVAILABLE']]) {
      const lines = captureReporter(reporter => reporter.onTestEnd(annotated(phase), { status, errors: [{ message: hostile, stack: hostile, value: hostile }] }));
      assert.deepEqual(lines, [`BROWSER_FAILURE_V1 phase=${phase} category=${category} reason=UNKNOWN`]);
      assert.equal(projected(lines), `${lines[0]}\n`);
    }
  }
  assert.deepEqual(captureReporter(reporter => reporter.onError({ message: hostile })), ['BROWSER_FAILURE_V1 phase=UNAVAILABLE category=RUNNER_ERROR reason=UNKNOWN']);
  assert.deepEqual(captureReporter(reporter => reporter.onEnd({ status: 'failed' })), ['BROWSER_FAILURE_V1 phase=UNAVAILABLE category=UNAVAILABLE reason=UNKNOWN']);
});
await test('passing and skipped runs never publish a failure marker or a diagnostic receipt', () => {
  assert.deepEqual(captureReporter(reporter => {
    reporter.onTestEnd(annotated('RECEIPT_PUBLICATION'), { status: 'passed', errors: [{ message: hostile }] });
    reporter.onTestEnd(annotated('TEST_SETUP'), { status: 'skipped' });
    reporter.onEnd({ status: 'passed' });
  }), []);
});
await test('missing, duplicate and hostile annotations remain unavailable without parsing exception markers', () => {
  for (const annotations of [undefined, [], canary, [{ type: 'capture-failure-phase-v1', description: hostile }], [...annotated('PC_FONT').annotations, ...annotated('H5_FONT').annotations]]) {
    const lines = captureReporter(reporter => reporter.onTestEnd({ annotations }, {
      status: 'failed', error: { name: 'BROWSER_FAILURE_V1', message: 'BROWSER_FAILURE_V1 phase=PC_FONT category=TIMED_OUT', stack: hostile },
    }));
    assert.deepEqual(lines, ['BROWSER_FAILURE_V1 phase=UNAVAILABLE category=FAILED reason=UNKNOWN']);
    assert.doesNotMatch(projected(lines), /TIMED_OUT|PC_FONT|SYNTHETIC/u);
  }
});
await test('phase bookkeeping preserves the original failing operation through cleanup', () => {
  const info = { annotations: [{ type: 'existing', description: 'retained' }] };
  const phase = captureFailurePhase(info);
  phase.enter('TEST_SETUP'); phase.enter('PC_SURFACE_READINESS');
  phase.preserveFailure(); phase.enter('CLEANUP');
  assert.deepEqual(info.annotations, [{ type: 'existing', description: 'retained' }, ...annotated('PC_SURFACE_READINESS').annotations]);
  assert.deepEqual(captureReporter(reporter => reporter.onTestEnd(info, { status: 'failed' })), ['BROWSER_FAILURE_V1 phase=PC_SURFACE_READINESS category=FAILED reason=UNKNOWN']);
  const successful = { annotations: [] }; const next = captureFailurePhase(successful);
  next.enter('RECEIPT_PUBLICATION'); next.enter('CLEANUP');
  assert.equal(successful.annotations[0].description, 'CLEANUP');
  next.enter(hostile); assert.equal(successful.annotations[0].description, 'UNAVAILABLE');
});
await test('diagnostic parser rejects normalization tricks, unknown protocol and input-controlled fields', () => {
  const line = 'BROWSER_FAILURE_V1 phase=PC_SURFACE_READINESS category=FAILED reason=UNKNOWN';
  for (const value of [
    ` ${line}`, `${line} `, `\u001b[31m${line}\u001b[0m`, `${line}\r`, `${line}\0`, `${line}\t`,
    `${line} ${hostile}`, `Error: ${line}`, `${canary}${line}`, line.replace('V1', 'V2'),
    line.replace('FAILED', canary), line.replace('PC_SURFACE_READINESS', canary),
    `${line} category=TIMED_OUT`, 'BROWSER_FAILURE_V1 category=FAILED phase=PC_FONT',
    'EVIDENCE_REJECTION_V2 category=ARCHIVE_INVALID', `EVIDENCE_REJECTION_V1 category=${hostile}`,
  ]) {
    assert.equal(safeFailureDiagnostic(value), undefined);
    const result = projected([value]);
    assert.equal(result.includes('BROWSER_FAILURE_V1'), false);
    assert.equal(result.includes('EVIDENCE_REJECTION_V1'), false);
    assert.equal(result.includes(canary), false);
  }
});
await test('actual checked child stdout and stderr preserve both failure layers while hostile details stay private', () => {
  const processModule = new URL('../product-readiness/pc-h5-runtime/processes.mjs', import.meta.url).href;
  const childSource = [
    `process.stdout.write(${JSON.stringify('BROWSER_FAILURE_V1 phase=PC_SURFACE_READINESS category=FAILED reason=UNKNOWN\n' + hostile + '\n')});`,
    `process.stderr.write(${JSON.stringify('EVIDENCE_REJECTION_V1 category=ASSERTION_CHANGE\n' + hostile + '\n')});`,
    'process.exitCode = 1;',
  ].join('\n');
  // Exercise the actual checked-process wrapper. The failure is synthetic;
  // this test launches no browser and cannot grant any acceptance claim.
  const child = spawnSync(process.execPath, ['--input-type=module', '-e', [
    `import { runNodeChecked } from ${JSON.stringify(processModule)};`,
    `try { runNodeChecked('Synthetic diagnostic failure', ['-e', ${JSON.stringify(childSource)}], process.env, 10000); }`,
    'catch { process.exitCode = 1; }',
  ].join('\n')], { encoding: 'utf8', timeout: 15_000, maxBuffer: 1_048_576 });
  assert.equal(child.status, 1); assert.equal(child.error, undefined); assert.equal(child.stderr, '');
  assert.match(child.stdout, /^BROWSER_FAILURE_V1 phase=PC_SURFACE_READINESS category=FAILED reason=UNKNOWN$/mu);
  assert.match(child.stdout, /^EVIDENCE_REJECTION_V1 category=ASSERTION_CHANGE$/mu);
  assert.equal(child.stdout.includes(canary), false);
});
await test('malformed diagnostic records cannot fall through to readiness or control parsing', () => {
  const marker = 'BACKEND_LOCAL_START_VERIFIED';
  for (const line of [
    `BROWSER_FAILURE_V1 phase=PC_FONT category=FAILED ${marker}`,
    `EVIDENCE_REJECTION_V9 category=CONTENT_INVALID ${marker}`,
    `prefix BROWSER_FAILURE_V1 ${marker}`,
    `\u001b[31mBROWSER_FAILURE_V1\u001b[0m ${marker}`,
    `BROWSER_\0FAILURE_V1 ${marker}`,
  ]) {
    assert.equal(projected([line]), '[subprocess] unstructured output omitted\n');
  }
});
await test('diagnostic-only source changes select the runtime and keep original reporters and capture policy', () => {
  for (const file of ['apps/web/overlay/playground/__tests__/e2e/product-readiness-capture-diagnostics.ts', 'scripts/tests/product-readiness-capture-diagnostics.test.mjs']) assert.equal(relevantChangeSet([file]), true, file);
  for (const name of ['product-readiness', 'browser-accessibility']) {
    const source = readFileSync(new URL(`../../apps/web/overlay/playground/${name}.playwright.config.ts`, import.meta.url), 'utf8');
    assert.ok(source.includes("reporter: [['list'], ['./__tests__/e2e/product-readiness-capture-diagnostics.ts']]"));
    assert.ok(source.includes("trace: 'on'")); assert.ok(source.includes("screenshot: 'only-on-failure'")); assert.ok(source.includes('retries: 0'));
  }
});

await test('every fixed publication rejection category survives the same output gate', () => {
  assert.equal(Object.isFrozen(publicationRejectionCategories), true);
  for (const category of publicationRejectionCategories) {
    const line = `EVIDENCE_REJECTION_V1 category=${category}`;
    assert.equal(projected([line]), `${line}\n`);
  }
});

await test('known source failures map privately to closed reasons through pinned reporter wrappers', () => {
  assert.equal(Object.isFrozen(browserFailureReasons), true);
  const cases = new Map([
    ['Surface readiness deadline expired (route)', 'ROUTE'],
    ['Surface readiness deadline expired (surface)', 'SURFACE'],
    ['Surface readiness deadline expired (completed task still present)', 'COMPLETED_TASK_PRESENT'],
    ['Surface readiness deadline expired (task identity)', 'TASK_IDENTITY'],
    ['Surface readiness deadline expired (list refresh)', 'LIST_REFRESH'],
    ['Surface readiness deadline expired (list totals)', 'LIST_TOTALS'],
    ['Surface readiness deadline expired (list controls or empty state)', 'LIST_CONTROLS'],
    ['Surface readiness deadline expired (bootstrap overlay)', 'BOOTSTRAP_OVERLAY'],
    ['Surface readiness deadline expired (blocking overlay)', 'BLOCKING_OVERLAY'],
    ['Surface readiness deadline expired (detail data)', 'DETAIL_DATA'],
    ['Surface readiness deadline expired (task assistance snapshot)', 'ASSISTANCE'],
    ['Surface readiness deadline expired (unresolved Wot component)', 'COMPONENT_RESOLUTION'],
    ['Surface readiness deadline expired (Wot button styles)', 'COMPONENT_STYLES'],
    ['Surface readiness deadline expired (fonts)', 'FONT'],
    ['Surface readiness deadline expired (image decode)', 'IMAGE'],
    ['Surface readiness deadline expired (finite transition)', 'TRANSITION'],
    ['Surface readiness deadline expired (surface opacity)', 'OPACITY'],
    ['Surface readiness deadline expired (viewport intersection)', 'VIEWPORT'],
    ['Surface readiness deadline expired (natural geometry)', 'GEOMETRY'],
    ['Invalid surface deadline', 'INVALID_DEADLINE'], ['Capture deadline expired', 'DEADLINE'],
    ['Capture cancelled by navigation', 'NAVIGATION_CHANGED'],
    ['Capture cancelled by surface replacement or dismissal', 'SURFACE_CHANGED'],
    ['Capture cancelled by refresh change', 'REFRESH_CHANGED'],
    ['Capture cancelled by detail reload', 'DETAIL_CHANGED'],
    ['Capture cancelled by assistance replacement', 'ASSISTANCE_CHANGED'],
    ['Capture cancelled by task change', 'TASK_CHANGED'],
    ['Capture witness changed before publication', 'WITNESS'],
    ['Capture assets changed before publication', 'ASSET_CHANGED'],
    ['Screenshot requires a current surface or acknowledged action', 'MISSING_WITNESS'],
    ['Capture receipt path already exists', 'RECEIPT_PUBLICATION'],
    ['Capture has failed script/style loads (11) or unresolved Wot components (0) or page errors (0)', 'REQUIRED_ASSET'],
    ['Capture has failed script/style loads (0) or unresolved Wot components (0) or page errors (1)', 'PAGE_ERROR'],
    ['Capture has failed script/style loads (11) or unresolved Wot components (0) or page errors (1)', 'RUNTIME_FAILURES'],
  ]);
  assert.deepEqual(new Set([...cases.values(), 'UNKNOWN']), new Set(browserFailureReasons));
  for (const [message, expected] of cases) {
    for (const prefix of ['', 'Error: ', 'Error: page.evaluateHandle: Error: ', 'Error: jsHandle.evaluate: Error: ']) {
      const errors = [{ message: `${prefix}${message}\n    at ${hostile}:1:2`, stack: hostile }];
      assert.equal(captureFailureReason(errors), expected);
      const lines = captureReporter(reporter => reporter.onTestEnd(annotated('PC_SURFACE_READINESS'), { status: 'failed', errors }));
      assert.deepEqual(lines, [`BROWSER_FAILURE_V1 phase=PC_SURFACE_READINESS category=FAILED reason=${expected}`]);
      assert.equal(projected(lines).includes(canary), false);
      assert.equal(projected(lines), `${lines[0]}\n`);
    }
  }
});
await test('unmatched, ambiguous, malformed and category-spoofing exceptions remain UNKNOWN', () => {
  const known = 'Capture deadline expired';
  for (const errors of [
    undefined, [], [{ message: known }, { message: known }], [{ value: known }],
    [{ message: hostile }], [{ message: `prefix ${known}` }], [{ message: `${known} ${hostile}` }],
    [{ message: `BROWSER_FAILURE_V1 phase=PC_FONT category=FAILED reason=FONT` }],
    [{ message: `Error: Error: ${known}` }], [{ message: `${known}\n${hostile}` }],
    [{ message: `${known}\n    at one:1:2\nSurface readiness deadline expired (fonts)` }],
    [{ message: `${known}\r` }], [{ message: `\u001b[31m${known}` }],
    [{ message: 'Surface readiness deadline expired (unknown)' }],
    [{ message: 'Capture has failed script/style loads (01) or unresolved Wot components (0) or page errors (0)' }],
    [{ message: 'Capture has failed script/style loads (1000000) or unresolved Wot components (0) or page errors (0)' }],
    [{ message: 'Capture has failed script/style loads (0) or unresolved Wot components (0) or page errors (0)' }],
    [{ get message() { throw new Error(hostile); } }],
  ]) assert.equal(captureFailureReason(errors), 'UNKNOWN');
});


const contextProject = (name, browserName) => ({ parent: { project: () => ({ name, use: { browserName } }) } });
const summary = (count, kind) => ({ count, kind });
await test('native page-error classification preserves counts and only retains fixed names', () => {
  assert.equal(Object.isFrozen(capturePageErrorKinds), true);
  assert.deepEqual(capturePageErrorKinds, browserPageErrorKinds);
  const classes = [
    ['Error', 'ERROR'], ['TypeError', 'TYPE_ERROR'], ['ReferenceError', 'REFERENCE_ERROR'],
    ['SyntaxError', 'SYNTAX_ERROR'], ['RangeError', 'RANGE_ERROR'], ['EvalError', 'EVAL_ERROR'],
    ['URIError', 'URI_ERROR'], ['AggregateError', 'AGGREGATE_ERROR'], [hostile, 'UNKNOWN'],
  ];
  for (const [name, kind] of classes) {
    const page = new EventEmitter(); const observer = observeCaptureFailures(page);
    assert.deepEqual(observer.pageErrorSummary(), summary(0, 'NONE'));
    page.emit('pageerror', { name, message: hostile, stack: hostile });
    assert.deepEqual(observer.pageErrorSummary(), summary(1, kind));
    assert.throws(() => observer.assert(), /page errors \(1\)/u);
    page.emit('pageerror', { name, message: 'Capture deadline expired' });
    assert.deepEqual(observer.pageErrorSummary(), summary(2, kind));
    page.emit('pageerror', { get name() { throw new Error(hostile); } });
    assert.deepEqual(observer.pageErrorSummary(), summary(3, kind === 'UNKNOWN' ? 'UNKNOWN' : 'MULTIPLE'));
    assert.equal(JSON.stringify(observer.pageErrorSummary()).includes(canary), false);
    observer.dispose(); assert.equal(page.listenerCount('pageerror'), 0);
    page.emit('pageerror', new Error(hostile)); assert.equal(observer.pageErrorSummary().count, 3);
  }
});
await test('fixed matrix context survives projection for every engine and page-error class', () => {
  assert.equal(Object.isFrozen(browserContextEngines), true);
  assert.equal(Object.isFrozen(browserPageErrorKinds), true);
  for (const [name, browser, engine] of [
    ['system-chromium', 'chromium', 'CHROMIUM'], ['bundled-firefox', 'firefox', 'FIREFOX'],
    ['bundled-webkit', 'webkit', 'WEBKIT'], [hostile, hostile, 'UNKNOWN'],
    ['bundled-firefox', 'chromium', 'UNKNOWN'],
  ]) {
    for (const kind of browserPageErrorKinds) {
      const info = { ...annotated('PC_DETAIL_READINESS'), ...contextProject(name, browser) };
      capturePageErrorContext(info, summary(kind === 'NONE' ? 0 : 2, kind), summary(0, 'NONE'));
      const lines = captureReporter(reporter => reporter.onTestEnd(info, { status: 'failed', errors: [{ message: hostile }] }));
      assert.equal(lines[1], `BROWSER_CONTEXT_V1 engine=${engine} pcCount=${kind === 'NONE' ? 0 : 2} pcKind=${kind} h5Count=0 h5Kind=NONE`);
      assert.equal(projected(lines), lines.join('\n') + '\n');
      assert.equal(projected(lines).includes(canary), false);
      assert.deepEqual(captureReporter(reporter => reporter.onTestEnd(info, { status: 'passed' })), []);
    }
  }
});
await test('missing malformed duplicate or inconsistent page-error annotations remain unknown', () => {
  const project = contextProject('bundled-firefox', 'firefox');
  const valid = 'pcCount=1 pcKind=TYPE_ERROR h5Count=0 h5Kind=NONE';
  const expected = 'BROWSER_CONTEXT_V1 engine=FIREFOX pcCount=UNKNOWN pcKind=UNKNOWN h5Count=UNKNOWN h5Kind=UNKNOWN';
  for (const value of [undefined, '', hostile, `${valid} `, ` ${valid}`, `${valid}\r`, `${valid}\0`,
    valid.replace('pcCount=1', 'pcCount=0'), valid.replace('pcCount=1', 'pcCount=01'),
    valid.replace('pcCount=1', 'pcCount=1000000'), valid.replace('TYPE_ERROR', canary),
    valid.replace('pcCount=1', 'pcCount=UNKNOWN'), valid.replace('TYPE_ERROR', 'MULTIPLE')]) {
    const info = { ...project, annotations: [{ type: 'capture-page-errors-v1', description: value }] };
    const lines = captureReporter(reporter => reporter.onTestEnd(info, { status: 'failed' }));
    assert.equal(lines[1], expected);
  }
  const entry = { type: 'capture-page-errors-v1', description: valid };
  assert.equal(captureReporter(reporter => reporter.onTestEnd({ ...project, annotations: [entry, entry] }, { status: 'failed' }))[1], expected);
  assert.equal(captureReporter(reporter => reporter.onTestEnd({ ...project, annotations: [] }, { status: 'failed' }))[1], expected);
  for (const count of [-1, 0.5, Infinity, NaN, 1_000_000, hostile]) {
    const info = { ...project, annotations: [] };
    capturePageErrorContext(info, summary(count, 'TYPE_ERROR'), summary(0, 'NONE'));
    assert.equal(captureReporter(reporter => reporter.onTestEnd(info, { status: 'failed' }))[1], expected);
  }
});
await test('context grammar rejects control, suffix and marker-smuggling without readiness fallback', () => {
  const valid = 'BROWSER_CONTEXT_V1 engine=WEBKIT pcCount=0 pcKind=NONE h5Count=1 h5Kind=REFERENCE_ERROR';
  assert.equal(projected([valid]), valid + '\n');
  for (const value of [` ${valid}`, `${valid} `, `${valid}\r`, `${valid}\0`, `\u001b[31m${valid}`, `prefix ${valid}`,
    valid.replace('V1', 'V2'), valid.replace('WEBKIT', canary), valid.replace('h5Count=1', 'h5Count=01'),
    valid.replace('h5Count=1', 'h5Count=1000000'), valid.replace('h5Count=1', 'h5Count=0'),
    valid.replace('REFERENCE_ERROR', canary), valid.replace('REFERENCE_ERROR', 'MULTIPLE'), `${valid} BACKEND_LOCAL_START_VERIFIED`,
    ...['\0', '\u0085', '\u200b', '\u2028', '\ufffd'].map(value => `BROWSER_${value}CONTEXT_V1 BACKEND_LOCAL_START_VERIFIED`)]) {
    assert.equal(safeFailureDiagnostic(value), undefined);
    assert.equal(projected([value]), '[subprocess] unstructured output omitted\n');
  }
});

await test('streaming distinct failure contexts have a fixed output bound and preserve first diagnostics', () => {
  const lines = Array.from({ length: 1000 }, (_, index) =>
    `BROWSER_CONTEXT_V1 engine=FIREFOX pcCount=${index + 1} pcKind=TYPE_ERROR h5Count=0 h5Kind=NONE`);
  const repeated = Array(100).fill(lines[0]);
  const output = projected([...repeated, ...lines, 'BACKEND_LOCAL_START_VERIFIED']);
  const retained = output.split('\n').filter(value => value.startsWith('BROWSER_CONTEXT_V1 '));
  assert.deepEqual(retained, lines.slice(0, 64));
  assert.equal(output.split('[subprocess] unstructured output omitted').length - 1, 1);
  assert.ok(output.includes('BACKEND_LOCAL_START_VERIFIED\n'));
});
