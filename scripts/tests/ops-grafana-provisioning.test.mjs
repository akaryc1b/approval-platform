import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstatSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { isAbsolute, resolve } from 'node:path';
import test from 'node:test';
import { runInNewContext } from 'node:vm';
import { archiveSha256Sync } from '../ops/archive-digest.mjs';

const root = resolve(import.meta.dirname, '../..');
const source = readFileSync(resolve(root, 'scripts/ops/verify-grafana-browser.mjs'), 'utf8');
const pinnedHash = '9662c838a09824fdb072e5f6fbdd45b62cf541b20f3d609ea5011e6e5f544c8f';
const inputs = [{ path: 'controlled/dashboard.json', sha256: 'a'.repeat(64) }];
const plain = value => JSON.parse(JSON.stringify(value));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const exclusions = [
  '--exclude=grafana-13.2.2/public/build/*.js.map',
  '--exclude=grafana-13.2.2/public/build/*.css.map',
  '--exclude=grafana-13.2.2/public/build-swagger/*.js.map',
  '--exclude=grafana-13.2.2/public/build-swagger/*.css.map',
];

function receipt() {
  // Parser fixture only: these boundary bytes are never visual/native acceptance evidence.
  const bytes = Buffer.alloc(6000); bytes.writeUInt16BE(0xffd8, 0); bytes.writeUInt16BE(0xffd9, bytes.length - 2);
  return { status: 'OPS_GRAFANA_BROWSER_VERIFIED', grafanaVersion: '13.2.2', browserVersion: 'Chrome/100.0.0.0',
    inputs, cjkFont: 'Noto Sans CJK SC', cjkGlyphCount: 5, distinctCjkGlyphs: 4,
    realGrafana: true, realPrometheus: true, realChromium: true, navigationBothWays: true,
    viewerWriteDenied: true, anonymousReadDenied: true, cleanupPassed: true,
    businessDatabaseVerified: false, humanNotificationVerified: false, productionDeploymentVerified: false,
    provisionedDashboards: 2, originalQueries: 25, engineQueries: 11, overviewPanelsRendered: 25,
    enginePanelsRendered: 8, metricSource: 'CONTROLLED_HTTP_FIXTURE',
    readings: [{ state: 'healthy', values: ['3', '5', '2', '1'] },
      { state: 'unavailable', values: ['unknown', 'unknown', 'unknown', 'unknown'] },
      { state: 'empty', values: ['0', '0', '0', '0'] }],
    screenshots: ['overview', 'engine-healthy', 'engine-unavailable', 'engine-empty'].map(name => ({
      name, bytes: bytes.length, sha256: sha(bytes), base64: bytes.toString('base64') })),
  };
}

function fixture(t, { fail, archiveKind, realDigest = false, binaryKind,
  fontOutput = 'OPS_GRAFANA_FONT_RESULT={"status":"OPS_GRAFANA_CJK_READY","family":"Noto Sans CJK SC"}',
  browserOutput = 'OPS_GRAFANA_BROWSER_RESULT=' + JSON.stringify(receipt()) } = {}) {
  const directory = mkdtempSync(resolve(tmpdir(), 'grafana-provisioning-unit-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const prometheus = resolve(directory, 'prometheus'); writeFileSync(prometheus, 'inert: never executed');
  const calls = [], order = [], failure = new Error('CONTROLLED_' + fail);
  const archive = resolve(directory, 'grafana.tgz'), home = resolve(directory, 'grafana-home');
  // Replace imported dependencies only in this private VM. Production has no hash-bypass seam.
  const body = source.replace(/^import [^\n]+\n/gmu, '').replace(/^export /gmu, '');
  assert.doesNotMatch(body, /^import /mu);
  const api = runInNewContext(`(() => {
    const sourceIdentities = () => (${JSON.stringify(inputs)});
    ${body}
    return { verifyGrafanaBrowser };
  })()`, {
    assert, createHash, lstatSync, mkdirSync, realpathSync, isAbsolute, resolve, Buffer,
    process: { execPath: '/controlled/node', env: { GITHUB_ACTIONS: 'true' } },
    archiveSha256Sync(file, options) {
      order.push('digest'); assert.equal(file, archive); assert.equal(options.maximumBytes, 512 * 1024 * 1024);
      if (fail === 'digest') throw failure;
      return realDigest ? archiveSha256Sync(file, options) : pinnedHash;
    },
  }, { timeout: 1000, filename: 'real-grafana-provisioner-with-private-mocks.mjs' });
  const runCommand = (file, args, cwd, timeout) => {
    calls.push({ file, args: Array.from(args), cwd, timeout });
    const phase = file === 'curl' ? 'download' : file === 'tar' ? 'extract'
      : args[0].endsWith('grafana-browser-fonts.mjs') ? 'fonts' : 'browser';
    order.push(phase); if (fail === phase) throw failure;
    if (phase === 'download') {
      if (archiveKind === 'symlink') symlinkSync(prometheus, archive);
      else if (archiveKind === 'directory') mkdirSync(archive);
      else writeFileSync(archive, 'invalid archive fixture bytes; never extracted');
      return '';
    }
    if (phase === 'extract') {
      if (binaryKind !== 'missing') {
        mkdirSync(resolve(home, 'bin'));
        if (binaryKind === 'symlink') symlinkSync(prometheus, resolve(home, 'bin/grafana'));
        else writeFileSync(resolve(home, 'bin/grafana'), 'inert: never executed');
      }
      return '';
    }
    return phase === 'fonts' ? fontOutput : browserOutput;
  };
  return { directory, prometheus, archive, home, calls, order, failure,
    run: () => api.verifyGrafanaBrowser({ directory, repositoryRoot: root, prometheus, runCommand }) };
}

test('real provisioner keeps its hash gate and command budgets while excluding only pinned public build maps', t => {
  const f = fixture(t), result = plain(f.run());
  assert.deepEqual(f.order, ['download', 'digest', 'extract', 'fonts', 'browser']);
  assert.equal(f.calls.length, 4);
  assert.deepEqual(f.calls[1], { file: 'tar', cwd: f.directory, timeout: 20000, args: [
    '--extract', '--gzip', '--file', f.archive, '--directory', f.home,
    '--strip-components=1', '--no-same-owner', '--no-same-permissions',
    '--anchored', '--no-wildcards-match-slash', '--no-recursion', ...exclusions,
  ] });
  assert.equal(lstatSync(f.home).mode & 0o777, 0o700);
  assert.deepEqual(f.calls.map(c => c.timeout), [65000, 20000, 70000, 120000]);
  assert.deepEqual(f.calls[0].args, ['--fail', '--location', '--silent', '--show-error',
    '--proto', '=https', '--proto-redir', '=https', '--connect-timeout', '15', '--max-time', '60',
    '--max-filesize', String(512 * 1024 * 1024),
    'https://dl.grafana.com/grafana/release/13.2.2/grafana_13.2.2_34846740809_linux_amd64.tar.gz', '-o', f.archive]);
  assert.deepEqual(f.calls[2], { file: '/controlled/node', cwd: f.directory, timeout: 70000,
    args: [resolve(root, 'scripts/ops/grafana-browser-fonts.mjs'), 'ci'] });
  assert.deepEqual(f.calls[3], { file: '/controlled/node', cwd: f.directory, timeout: 120000,
    args: [resolve(root, 'scripts/ops/grafana-browser-runtime.mjs'), f.directory, root, f.home, f.prometheus] });
  assert.deepEqual(result, { ...receipt(), fontPreparation: { status: 'OPS_GRAFANA_CJK_READY', family: 'Noto Sans CJK SC' },
    grafanaArchiveSha256: pinnedHash });
});

for (const [fail, expected] of [['download', ['download']], ['digest', ['download', 'digest']],
  ['extract', ['download', 'digest', 'extract']], ['fonts', ['download', 'digest', 'extract', 'fonts']]]) {
  test('provisioning ' + fail + ' failure remains terminal without retry or downstream launch', t => {
    const f = fixture(t, { fail }); assert.throws(f.run, error => error === f.failure);
    assert.deepEqual(f.order, expected);
  });
}

for (const archiveKind of ['corrupt', 'symlink', 'directory']) {
  test('real archive validation rejects ' + archiveKind + ' fixture before extraction', t => {
    const f = fixture(t, { realDigest: true, archiveKind });
    assert.throws(f.run, /GRAFANA_BROWSER_ARCHIVE_(DIGEST|FILE)/u);
    assert.deepEqual(f.order, ['download', 'digest']); assert.equal(f.calls.length, 1);
  });
}

for (const binaryKind of ['missing', 'symlink']) {
  test('a ' + binaryKind + ' extracted binary cannot reach font preparation or runtime', t => {
    const f = fixture(t, { binaryKind }); assert.throws(f.run);
    assert.deepEqual(f.order, ['download', 'digest', 'extract']);
  });
}

for (const fontOutput of ['', 'OPS_GRAFANA_FONT_RESULT={}\nOPS_GRAFANA_FONT_RESULT={}',
  'OPS_GRAFANA_FONT_RESULT={"status":"wrong"}']) {
  test('invalid font receipt still blocks the browser command: ' + JSON.stringify(fontOutput), t => {
    const f = fixture(t, { fontOutput }); assert.throws(f.run);
    assert.deepEqual(f.order, ['download', 'digest', 'extract', 'fonts']);
  });
}

for (const browserOutput of ['', 'OPS_GRAFANA_BROWSER_RESULT={}\nOPS_GRAFANA_BROWSER_RESULT={}',
  'OPS_GRAFANA_BROWSER_RESULT=' + JSON.stringify({ ...receipt(), viewerWriteDenied: false })]) {
  test('invalid runtime receipt remains a failure after selected extraction: ' + browserOutput.length, t => {
    const f = fixture(t, { browserOutput }); assert.throws(f.run);
    assert.deepEqual(f.order, ['download', 'digest', 'extract', 'fonts', 'browser']);
  });
}
