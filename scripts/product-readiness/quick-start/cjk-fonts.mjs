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

function runChecked(command, args, timeoutMs) {
  const result = spawnSync(command, args, {
    encoding: 'utf8',
    env: {
      ...process.env,
      DEBIAN_FRONTEND: 'noninteractive',
    },
    shell: false,
    timeout: timeoutMs,
  });
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

function installedChineseFamilies() {
  const result = spawnSync('fc-list', [':lang=zh', 'family'], {
    encoding: 'utf8',
    shell: false,
    timeout: 10_000,
  });
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

export function ensureCjkFontRuntime() {
  if (process.platform !== 'linux') {
    console.log(`CJK_FONT_RUNTIME_USING_SYSTEM_FALLBACK=${process.platform}`);
    return {
      platform: process.platform,
      prepared: false,
      family: 'system-cjk-fallback',
    };
  }

  let families = installedChineseFamilies();
  let family = selectedFamily(families);

  if (!family && process.env.GITHUB_ACTIONS === 'true') {
    runChecked(
      'sudo',
      ['-n', 'apt-get', 'update', '-qq'],
      180_000,
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
    );
    runChecked('fc-cache', ['-f'], 120_000);
    families = installedChineseFamilies();
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
