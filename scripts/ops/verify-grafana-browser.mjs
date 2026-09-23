import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lstatSync, mkdirSync, readFileSync, realpathSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import { sourceIdentities } from './grafana-browser-runtime.mjs';

// Official Grafana OSS standalone download, verified 2026-09-22:
// https://grafana.com/grafana/download/13.2.2?edition=oss
export const grafanaPin = Object.freeze({ version: '13.2.2',
  url: 'https://dl.grafana.com/grafana/release/13.2.2/grafana_13.2.2_34846740809_linux_amd64.tar.gz',
  sha256: '9662c838a09824fdb072e5f6fbdd45b62cf541b20f3d609ea5011e6e5f544c8f' });
const sha256 = value => createHash('sha256').update(value).digest('hex');
export function verifyGrafanaArchive(file) {
  const stat = lstatSync(file);
  assert.ok(stat.isFile() && !stat.isSymbolicLink() && stat.size > 0 && stat.size <= 512 * 1024 * 1024,
    'GRAFANA_BROWSER_ARCHIVE_FILE');
  assert.equal(sha256(readFileSync(file)), grafanaPin.sha256, 'GRAFANA_BROWSER_ARCHIVE_DIGEST');
}
export function validateGrafanaReceipt(receipt, expectedInputs) {
  assert.equal(receipt.status, 'OPS_GRAFANA_BROWSER_VERIFIED');
  assert.equal(receipt.grafanaVersion, grafanaPin.version); assert.match(receipt.browserVersion, /^(HeadlessChrome|Chrome)\/[0-9.]+$/u);
  assert.deepEqual(receipt.inputs, expectedInputs);
  assert.match(receipt.cjkFont, /^(Noto Sans CJK|Noto Sans SC|Source Han Sans|PingFang SC|Hiragino Sans GB|Microsoft YaHei|WenQuanYi Micro Hei)/u);
  assert.ok(Number.isSafeInteger(receipt.cjkGlyphCount) && receipt.cjkGlyphCount >= 5);
  assert.equal(receipt.distinctCjkGlyphs, 4);
  for (const key of ['realGrafana', 'realPrometheus', 'realChromium', 'navigationBothWays',
    'viewerWriteDenied', 'anonymousReadDenied', 'cleanupPassed']) assert.equal(receipt[key], true, key);
  for (const key of ['businessDatabaseVerified', 'humanNotificationVerified', 'productionDeploymentVerified']) assert.equal(receipt[key], false, key);
  assert.equal(receipt.provisionedDashboards, 2); assert.equal(receipt.originalQueries, 25); assert.equal(receipt.engineQueries, 11);
  assert.equal(receipt.overviewPanelsRendered, 25); assert.equal(receipt.enginePanelsRendered, 8);
  assert.equal(receipt.metricSource, 'CONTROLLED_HTTP_FIXTURE');
  assert.deepEqual(receipt.readings, [
    { state: 'healthy', values: ['3', '5', '2', '1'] },
    { state: 'unavailable', values: ['unknown', 'unknown', 'unknown', 'unknown'] },
    { state: 'empty', values: ['0', '0', '0', '0'] },
  ]);
  assert.deepEqual(receipt.screenshots.map(s => s.name), ['overview', 'engine-healthy', 'engine-unavailable', 'engine-empty']);
  for (const image of receipt.screenshots) {
    const bytes = Buffer.from(image.base64, 'base64');
    assert.ok(bytes.length > 5000 && bytes.length <= 150000);
    assert.equal(bytes.toString('base64'), image.base64); assert.equal(bytes.length, image.bytes);
    assert.equal(bytes.readUInt16BE(0), 0xffd8); assert.equal(bytes.readUInt16BE(bytes.length - 2), 0xffd9);
    assert.equal(sha256(bytes), image.sha256);
  }
}

/** Reuse already verified Prometheus; one digest-pinned Grafana download, no new workflow or npm install. */
export function verifyGrafanaBrowser({ directory, repositoryRoot, prometheus, runCommand }) {
  assert.ok(isAbsolute(prometheus));
  const stat = lstatSync(prometheus);
  assert.ok(stat.isFile() && !stat.isSymbolicLink() && realpathSync(prometheus) === prometheus, 'GRAFANA_BROWSER_PROMETHEUS_FILE');
  const inputs = sourceIdentities(repositoryRoot);
  const archive = resolve(directory, 'grafana.tgz'), home = resolve(directory, 'grafana-home');
  mkdirSync(home, { mode: 0o700 }); // An existing path is not reused or overwritten.
  runCommand('curl', ['--fail', '--location', '--silent', '--show-error', '--proto', '=https', '--proto-redir', '=https',
    '--connect-timeout', '15', '--max-time', '60', '--max-filesize', String(512 * 1024 * 1024), grafanaPin.url, '-o', archive], directory, 65000);
  verifyGrafanaArchive(archive);
  runCommand('tar', ['--extract', '--gzip', '--file', archive, '--directory', home,
    '--strip-components=1', '--no-same-owner', '--no-same-permissions'], directory, 20000);
  const binary = resolve(home, 'bin/grafana');
  assert.ok(lstatSync(binary).isFile() && !lstatSync(binary).isSymbolicLink(), 'GRAFANA_BROWSER_BINARY_REQUIRED');
  // This separate owned child bounds the unchanged Quick Start installer and strips credentials.
  const fontOutput = runCommand(process.execPath, [resolve(repositoryRoot, 'scripts/ops/grafana-browser-fonts.mjs'),
    process.env.GITHUB_ACTIONS === 'true' ? 'ci' : 'local'], directory, 70000);
  const fontLines = fontOutput.split(/\r?\n/u).filter(line => line.startsWith('OPS_GRAFANA_FONT_RESULT='));
  assert.equal(fontLines.length, 1, 'GRAFANA_BROWSER_FONT_RECEIPT_REQUIRED');
  const fontPreparation = JSON.parse(fontLines[0].slice('OPS_GRAFANA_FONT_RESULT='.length));
  assert.equal(fontPreparation.status, 'OPS_GRAFANA_CJK_READY');
  const output = runCommand(process.execPath, [resolve(repositoryRoot, 'scripts/ops/grafana-browser-runtime.mjs'),
    directory, repositoryRoot, home, prometheus], directory, 120000);
  const lines = output.split(/\r?\n/u).filter(line => line.startsWith('OPS_GRAFANA_BROWSER_RESULT='));
  assert.equal(lines.length, 1, 'GRAFANA_BROWSER_ONE_RECEIPT_REQUIRED');
  const receipt = JSON.parse(lines[0].slice('OPS_GRAFANA_BROWSER_RESULT='.length));
  validateGrafanaReceipt(receipt, inputs);
  return { ...receipt, fontPreparation, grafanaArchiveSha256: grafanaPin.sha256 };
}
