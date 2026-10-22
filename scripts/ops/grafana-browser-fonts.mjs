import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stopProcess } from './grafana-browser-driver.mjs';

const fontModuleUrl = new URL('../product-readiness/quick-start/cjk-fonts.mjs', import.meta.url).href;

/** Runtime readiness stays bounded to 60s, including when invoked directly. */
export async function prepareGrafanaFonts(mode, {
  moduleUrl = fontModuleUrl,
  timeoutMs = 60000,
} = {}) {
  assert.ok(['ci', 'local'].includes(mode), 'GRAFANA_FONT_MODE');
  assert.ok(Number.isSafeInteger(timeoutMs) && timeoutMs > 0 && timeoutMs <= 60000, 'GRAFANA_FONT_TIMEOUT');
  return runFontHelper(mode, moduleUrl, timeoutMs);
}

/** CI-only cold prerequisite, before runtime tests: unchanged installer phase budgets
 * 180s update + 240s install + 120s cache + two 10s probes + 10s child overhead.
 * Owned process-group cleanup may take a further 3s. This is not browser readiness.
 */
export async function provisionGrafanaCiFonts({ moduleUrl = fontModuleUrl, timeoutMs = 570000 } = {}) {
  assert.ok(Number.isSafeInteger(timeoutMs) && timeoutMs > 0 && timeoutMs <= 570000, 'GRAFANA_FONT_PROVISION_TIMEOUT');
  return runFontHelper('ci', moduleUrl, timeoutMs);
}

/** Reuse Quick Start's unchanged distribution-font helper without credentials or retries. */
async function runFontHelper(mode, moduleUrl, timeoutMs) {
  const child = spawn(process.execPath, ['--input-type=module', '-e',
    `const {ensureCjkFontRuntime}=await import(${JSON.stringify(moduleUrl)});ensureCjkFontRuntime();`], {
    detached: true, stdio: ['ignore', 'pipe', 'ignore'],
    env: { PATH: process.env.PATH, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', GITHUB_ACTIONS: mode === 'ci' ? 'true' : 'false' },
  });
  let output = '', timer, interrupt;
  try {
    await new Promise((done, reject) => {
      timer = setTimeout(() => reject(new Error('GRAFANA_FONT_PREPARATION_TIMEOUT')), timeoutMs);
      interrupt = () => reject(new Error('GRAFANA_FONT_PREPARATION_INTERRUPTED'));
      process.once('SIGTERM', interrupt); process.once('SIGINT', interrupt);
      child.stdout.setEncoding('utf8');
      child.stdout.on('data', data => {
        output += data;
        if (output.length > 8192) reject(new Error('GRAFANA_FONT_OUTPUT_LIMIT'));
      });
      child.once('error', () => reject(new Error('GRAFANA_FONT_PREPARATION_START')));
      child.once('close', code => code === 0 ? done() : reject(new Error('GRAFANA_FONT_PREPARATION_FAILED')));
    });
    const lines = output.split(/\r?\n/u).filter(line => line.startsWith('CJK_FONT_RUNTIME_READY='));
    assert.equal(lines.length, 1, 'GRAFANA_FONT_READY_REQUIRED');
    const family = lines[0].slice('CJK_FONT_RUNTIME_READY='.length);
    assert.match(family, /^(Noto Sans CJK SC|Noto Sans SC|Source Han Sans SC|PingFang SC|Hiragino Sans GB|Microsoft YaHei|WenQuanYi Micro Hei)$/u);
    return { status: 'OPS_GRAFANA_CJK_READY', family };
  } finally {
    clearTimeout(timer);
    if (interrupt) { process.removeListener('SIGTERM', interrupt); process.removeListener('SIGINT', interrupt); }
    await stopProcess(child);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    assert.equal(process.argv.length, 3, 'GRAFANA_FONT_ARGUMENTS');
    console.log('OPS_GRAFANA_FONT_RESULT=' + JSON.stringify(await prepareGrafanaFonts(process.argv[2])));
  } catch (error) {
    console.error(error.message?.startsWith('GRAFANA_FONT_') ? error.message : 'GRAFANA_FONT_VALIDATION_FAILED');
    process.exitCode = 1;
  }
}
