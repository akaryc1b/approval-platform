import { spawnSync } from 'node:child_process';

// Apache's release archive digest is reviewed in the append-only toolchain transition.
export const MAVEN = Object.freeze({
  version: '3.9.16',
  url: 'https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz',
  sha512: '831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6',
});

export function requirePinnedMaven(command = 'mvn', run = spawnSync) {
  const result = run(command, ['--version', '-Dstyle.color=never'], {
    encoding: 'utf8', timeout: 30000, maxBuffer: 1024 * 1024,
  });
  if (result.error || result.status !== 0) throw new Error('Pinned Maven is unavailable; run node scripts/ci/setup-maven.mjs');
  const version = result.stdout.replace(/\u001b\[[0-9;]*m/g, '').split(/\r?\n/)[0];
  if (version !== `Apache Maven ${MAVEN.version}` && !version.startsWith(`Apache Maven ${MAVEN.version} (`)) {
    throw new Error(`Maven runtime drift: expected ${MAVEN.version}, got ${version}`);
  }
  return { version: MAVEN.version, distributionSha512: MAVEN.sha512, runtimeVerified: true };
}
