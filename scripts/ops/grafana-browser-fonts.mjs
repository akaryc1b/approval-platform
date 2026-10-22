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

// Decode only typed public phase fields; never forward helper errors, output or paths.
function lastFontPhase(output) {
  let last;
  for (const line of output.split(/\r?\n/u)) {
    if (!line.startsWith('CJK_FONT_RUNTIME_PHASE=') || line.length > 256) continue;
    try {
      const value = JSON.parse(line.slice('CJK_FONT_RUNTIME_PHASE='.length));
      if (!value || typeof value !== 'object' || Array.isArray(value)
        || Object.keys(value).sort().join(',') !== 'elapsedMs,error,exitCode,phase,signal,state'
        || !['probe-before','package-index','package-install','font-cache','probe-after'].includes(value.phase)
        || !['start','complete'].includes(value.state)
        || !Number.isInteger(value.elapsedMs) || value.elapsedMs < 0 || value.elapsedMs > 570000
        || !(value.exitCode === null || Number.isInteger(value.exitCode) && value.exitCode >= 0 && value.exitCode <= 255)
        || ![null,'SIGTERM','SIGKILL','SIGABRT','SIGSEGV','SIGBUS','SIGINT','other'].includes(value.signal)
        || ![null,'ETIMEDOUT','ENOENT','EACCES','EPERM','ENOBUFS','EIO','EAGAIN','other'].includes(value.error)) continue;
      last = value;
    } catch {} // Invalid optional diagnostics cannot replace the original failure.
  }
  return last;
}

/** Reuse Quick Start's distribution-font helper without credentials or retries. */
async function runFontHelper(mode, moduleUrl, timeoutMs) {
  const child = spawn(process.execPath, ['--input-type=module', '-e',
    `const {ensureCjkFontRuntime}=await import(${JSON.stringify(moduleUrl)});` +
    `ensureCjkFontRuntime({observe:value=>console.log('CJK_FONT_RUNTIME_PHASE='+JSON.stringify(value))});`], {
    detached: true, stdio: ['ignore', 'pipe', 'ignore'],
    env: { PATH: process.env.PATH, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', GITHUB_ACTIONS: mode === 'ci' ? 'true' : 'false' },
  });
  let output = '', timer, interrupt;
  const failure = (category, code = null, signal = null) => {
    const phase = lastFontPhase(output);
    const exit = Number.isInteger(code) && code >= 0 && code <= 255 ? code : null;
    const childSignal = [null,'SIGTERM','SIGKILL','SIGABRT','SIGSEGV','SIGBUS','SIGINT'].includes(signal) ? signal : 'other';
    return new Error(category + ':phase=' + (phase?.phase ?? 'unknown') + ',state=' + (phase?.state ?? 'unknown')
      + ',elapsedMs=' + (phase?.elapsedMs ?? 0) + ',exitCode=' + (phase?.exitCode ?? 'none')
      + ',signal=' + (phase?.signal ?? 'none') + ',error=' + (phase?.error ?? 'none')
      + ',childExit=' + (exit ?? 'none') + ',childSignal=' + (childSignal ?? 'none'));
  };
  try {
    await new Promise((done, reject) => {
      timer = setTimeout(() => reject(failure('GRAFANA_FONT_PREPARATION_TIMEOUT')), timeoutMs);
      interrupt = () => reject(failure('GRAFANA_FONT_PREPARATION_INTERRUPTED'));
      process.once('SIGTERM', interrupt); process.once('SIGINT', interrupt);
      child.stdout.setEncoding('utf8');
      child.stdout.on('data', data => {
        output += data;
        if (output.length > 8192) reject(new Error('GRAFANA_FONT_OUTPUT_LIMIT'));
      });
      child.once('error', () => reject(failure('GRAFANA_FONT_PREPARATION_START')));
      child.once('close', (code, signal) => code === 0 ? done() : reject(failure('GRAFANA_FONT_PREPARATION_FAILED', code, signal)));
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
