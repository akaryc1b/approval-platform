import { spawnSync } from 'node:child_process';

const expectedFamilies = [
  'Noto Sans CJK SC',
  'Noto Sans SC',
  'Source Han Sans SC',
  'PingFang SC',
  'Hiragino Sans GB',
  'Microsoft YaHei',
  'WenQuanYi Micro Hei',
];

// Advisory presence hints from existing bounded native output, not a causal or
// chronological phase claim. Never retain raw lines, mirrors, paths or packages.
function reportInstallHints(phase, result) {
  if (!['package-index', 'package-install'].includes(phase)) return;
  try {
    const patterns = [
      /^(?:Reading package lists|Building dependency tree|The following (?:NEW )?packages)/u,
      /^Need to get /u,
      /^Get:[0-9]+\s/u,
      /^(?:Preparing to unpack|Unpacking )/u,
      /^(?:Setting up |Processing triggers for )/u,
      /(?:Could not get lock|Unable to acquire the dpkg frontend lock|Waiting for cache lock)/u,
      /(?:Temporary failure resolving|Could not resolve|Connection timed out|Connection refused|ETIMEDOUT|ECONNRESET|ENOTFOUND|Failed to fetch)/u,
    ];
    const hints = patterns.map(() => 0);
    for (const output of [result.stdout, result.stderr]) {
      const text = String(output ?? '');
      for (let offset = 0; offset < text.length;) {
        const newline = text.indexOf('\n', offset);
        const end = newline === -1 ? text.length : newline;
        const contentEnd = text[end - 1] === '\r' ? end - 1 : end;
        if (contentEnd > offset && contentEnd - offset <= 8_192) {
          const line = text.slice(offset, contentEnd);
          for (let index = 0; index < patterns.length; index += 1) {
            if (!hints[index] && patterns[index].test(line)) hints[index] = 1;
          }
        }
        offset = end + 1;
      }
    }
    console.log(`CJK_FONT_INSTALL_HINTS_V1 phase=${phase}`
      + ` resolver=${hints[0]} planned=${hints[1]} fetch=${hints[2]} unpack=${hints[3]}`
      + ` configure=${hints[4]} lock=${hints[5]} network=${hints[6]}`);
  } catch {} // Optional observations cannot turn native failure into success.
}

function observedRun(command, args, options, phase, observe) {
  const started = Date.now();
  const report = result => {
    try {
      observe?.({ phase, state: result ? 'complete' : 'start',
        elapsedMs: Math.max(0, Math.min(570000, Date.now() - started)),
        exitCode: Number.isInteger(result?.status) && result.status >= 0 && result.status <= 255 ? result.status : null,
        signal: result?.signal == null ? null : ['SIGTERM','SIGKILL','SIGABRT','SIGSEGV','SIGBUS','SIGINT'].includes(result.signal) ? result.signal : 'other',
        error: result?.error == null ? null : ['ETIMEDOUT','ENOENT','EACCES','EPERM','ENOBUFS','EIO','EAGAIN'].includes(result.error.code) ? result.error.code : 'other',
      });
    } catch {} // Optional diagnostics never alter installer execution or its failure.
  };
  report();
  const result = spawnSync(command, args, options);
  report(result);
  reportInstallHints(phase, result);
  return result;
}

function runChecked(command, args, timeoutMs, phase, observe) {
  const result = observedRun(command, args, {
    encoding: 'utf8',
    env: {
      ...process.env,
      DEBIAN_FRONTEND: 'noninteractive',
    },
    shell: false,
    timeout: timeoutMs,
  }, phase, observe);
  if (result.error) {
    throw new Error(`${command} could not run: ${result.error.message}`);
  }
  if (result.status !== 0) {
    const detail = `${result.stdout ?? ''}\n${result.stderr ?? ''}`
      .trim()
      .slice(-4_000);
    throw new Error(
      `${command} ${args.join(' ')} failed with exit code ${result.status}`
      + (detail ? `\n${detail}` : ''),
    );
  }
  return String(result.stdout ?? '').trim();
}

function installedChineseFamilies(phase, observe) {
  const result = observedRun('fc-list', [':lang=zh', 'family'], {
    encoding: 'utf8',
    shell: false,
    timeout: 10_000,
  }, phase, observe);
  if (result.error || result.status !== 0) return [];
  return String(result.stdout ?? '')
    .split(/\r?\n/u)
    .flatMap(line => line.split(','))
    .map(value => value.trim())
    .filter(Boolean);
}

function selectedFamily(families) {
  return expectedFamilies.find(expected =>
    families.some(family => family.includes(expected)));
}

export function ensureCjkFontRuntime({ observe } = {}) {
  if (process.platform !== 'linux') {
    console.log(`CJK_FONT_RUNTIME_USING_SYSTEM_FALLBACK=${process.platform}`);
    return {
      platform: process.platform,
      prepared: false,
      family: 'system-cjk-fallback',
    };
  }

  let families = installedChineseFamilies('probe-before', observe);
  let family = selectedFamily(families);

  if (!family && process.env.GITHUB_ACTIONS === 'true') {
    runChecked(
      'sudo',
      ['-n', 'apt-get', 'update', '-qq'],
      180_000,
      'package-index', observe,
    );
    runChecked(
      'sudo',
      [
        '-n',
        'apt-get',
        'install',
        '-y',
        '--no-install-recommends',
        'fontconfig',
        'fonts-noto-cjk',
      ],
      240_000,
      'package-install', observe,
    );
    runChecked('fc-cache', ['-f'], 120_000, 'font-cache', observe);
    families = installedChineseFamilies('probe-after', observe);
    family = selectedFamily(families);
  }

  if (!family) {
    throw new Error(
      'No Simplified Chinese font is available. Install fonts-noto-cjk '
      + '(or another Chinese font) before running the Quick Start.',
    );
  }

  console.log(`CJK_FONT_RUNTIME_READY=${family}`);
  return {
    platform: process.platform,
    prepared: true,
    family,
  };
}
