import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import {
  existsSync,
  readFileSync,
  readdirSync,
  statSync,
} from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const read = relativePath => readFileSync(path.join(root, relativePath), 'utf8');
const parse = relativePath => JSON.parse(read(relativePath));

function filesUnder(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const child = path.join(directory, entry.name);
    return entry.isDirectory() ? filesUnder(child) : [child];
  });
}

function gitBlobSha(relativePath) {
  const content = readFileSync(path.join(root, relativePath));
  const header = Buffer.from(`blob ${content.length}\0`, 'utf8');
  return createHash('sha1').update(header).update(content).digest('hex');
}

test('generated Current capability and compatibility documents have no drift', () => {
  execFileSync(
    process.execPath,
    ['scripts/generate-capability-status.mjs', '--check'],
    { cwd: root, stdio: 'pipe' },
  );
});

test('document generator uses literal bounded parsing instead of dynamic regular expressions', () => {
  const generator = read('scripts/generate-capability-status.mjs');
  assert.doesNotMatch(generator, /new RegExp\s*\(/u);
  assert.match(generator, /pom\.indexOf\(openingTag\)/u);
  assert.match(generator, /pom\.indexOf\(closingTag, valueStart\)/u);
});

test('README and Roadmap delegate current status instead of freezing milestone prose', () => {
  const readme = read('README.md');
  const roadmap = read('docs/ROADMAP.md');
  for (const content of [readme, roadmap]) {
    assert.match(content, /current\/capability-status\.md/);
    assert.doesNotMatch(content, /M4\s+已通过\s+PR|M5\s+正在|M6\s+规划|Flyway\s+(?:V1[–-])?V32/i);
    assert.doesNotMatch(content, /当前正式基线[^\n]*[0-9a-f]{40}/i);
  }
  assert.match(roadmap, /Roadmap 只描述未来优先级/);
  assert.match(roadmap, /测试通过不等于生产支持/);
});

test('Current documents contain no historical immutable evidence identities', () => {
  const currentRoot = path.join(root, 'docs/current');
  const currentFiles = filesUnder(currentRoot)
    .filter(file => ['.md', '.json'].includes(path.extname(file)));
  assert.ok(currentFiles.length >= 6);
  const offenders = [];
  for (const file of currentFiles) {
    const content = readFileSync(file, 'utf8');
    const reasons = [];
    if (/\b[0-9a-f]{40}\b/i.test(content)) reasons.push('full Git SHA');
    if (/\bPR\s*#\d+\b/i.test(content)) reasons.push('PR identity');
    if (/\b(?:Workflow\s+)?Run(?:\s+ID)?\s*[:#`-]*\s*\d{7,}\b/i.test(content)) {
      reasons.push('Workflow Run identity');
    }
    if (/58efb4255394fe3911700719669c4423a3ab212e/i.test(content)) {
      reasons.push('retired M4 baseline');
    }
    if (reasons.length > 0) {
      offenders.push(`${path.relative(root, file)}: ${reasons.join(', ')}`);
    }
  }
  assert.deepEqual(offenders, []);
});

test('legacy living-document paths are link-compatible Current shims', () => {
  const expectations = {
    'docs/ARCHITECTURE.md': 'current/architecture.md',
    'docs/OPERATIONS.md': 'current/operations.md',
    'docs/COMPATIBILITY.md': 'current/compatibility.md',
  };
  for (const [relativePath, target] of Object.entries(expectations)) {
    const content = read(relativePath);
    assert.equal(content.includes(target), true, `${relativePath} must delegate to ${target}`);
    assert.doesNotMatch(content, /\b[0-9a-f]{40}\b/i);
    assert.doesNotMatch(content, /Flyway[^\n]*V32|M4[^\n]*当前正式基线/i);
  }
});

test('registered acceptance records remain byte-for-byte immutable', () => {
  const lock = parse('config/acceptance-lock.json');
  assert.equal(lock.schemaVersion, 1);
  const entries = Object.entries(lock.documents);
  assert.ok(entries.length >= 10);
  for (const [relativePath, expectedBlob] of entries) {
    assert.equal(existsSync(path.join(root, relativePath)), true, `${relativePath} must exist`);
    assert.equal(
      gitBlobSha(relativePath),
      expectedBlob,
      `${relativePath} changed; add a correction record instead of rewriting acceptance`,
    );
  }
});

test('release directories cannot exist without a real tag and manifest', () => {
  const releaseRoot = path.join(root, 'docs/releases');
  const versionDirectories = readdirSync(releaseRoot)
    .filter(name => name !== 'next')
    .filter(name => statSync(path.join(releaseRoot, name)).isDirectory());
  const status = parse('docs/current/capability-status.json');
  if (status.project.releaseStatus === 'UNRELEASED') {
    assert.deepEqual(versionDirectories, []);
  }
  for (const version of versionDirectories) {
    execFileSync('git', ['rev-parse', '--verify', `refs/tags/${version}^{commit}`], {
      cwd: root,
      stdio: 'pipe',
    });
    assert.equal(
      existsSync(path.join(releaseRoot, version, 'manifest.json')),
      true,
      `${version} requires a release manifest`,
    );
  }
  assert.match(read('docs/releases/next/README.md'), /Status: `NOT_A_RELEASE`/);
});

test('capability semantics keep merge release and production support separate', () => {
  const status = parse('docs/current/capability-status.json');
  const capabilities = new Map(status.capabilities.map(capability => [capability.id, capability]));
  assert.equal(status.project.releaseStatus, 'UNRELEASED');
  assert.equal(status.project.productionReadiness, 'BLOCKED');
  for (const capability of capabilities.values()) {
    assert.equal(capability.status.released, 'no');
    assert.notEqual(capability.status.productionSupported, 'yes');
    if (capability.status.productionSupported === 'yes') {
      assert.equal(capability.status.released, 'yes');
    }
    if (capability.status.merged === 'no') {
      for (const statusName of ['implemented', 'tested', 'accepted']) {
        assert.equal(
          capability.status[statusName],
          'no',
          `${capability.id}.${statusName} cannot include unmerged candidate progress in Current`,
        );
      }
    }
  }
  assert.match(status.statusDefinitions.implemented, /默认分支/);
  assert.match(status.statusDefinitions.tested, /未合并候选分支/);
  assert.equal(capabilities.get('postgresql-16').status.accepted, 'yes');
  assert.equal(capabilities.get('postgresql-16').status.productionSupported, 'no');
  assert.equal(capabilities.get('mysql-8-4').status.implemented, 'no');
  assert.equal(capabilities.get('mysql-8-4').status.tested, 'no');
  assert.equal(capabilities.get('mysql-8-4').status.accepted, 'no');
  assert.equal(capabilities.get('mysql-8-4').status.merged, 'no');
  assert.equal(capabilities.get('mysql-8-4').status.productionSupported, 'no');
  assert.equal(capabilities.get('process-instance-migration').status.productionSupported, 'no');
  assert.equal(capabilities.get('controlled-automation-governance').status.productionSupported, 'no');
});

test('generated Flyway status reads the exact repository-owned V2 through V50 topology', () => {
  const manifest = parse('config/capabilities.json');
  const status = parse('docs/current/capability-status.json');
  assert.equal(manifest.project.flyway.firstVersion, 2);
  assert.equal(manifest.project.flyway.expectedVersion, 50);
  assert.equal(status.flyway.firstVersion, 2);
  assert.equal(status.flyway.effectiveVersion, 50);
  assert.equal(status.flyway.count, 49);
  assert.equal(new Set(status.flyway.versions).size, 49);
  assert.deepEqual(status.flyway.versions, Array.from({ length: 49 }, (_, index) => index + 2));
  assert.equal(status.flyway.versions.includes(1), false);
  const byVersion = new Map(status.flyway.keyMigrations.map(migration => [migration.version, migration]));
  assert.match(byVersion.get(2).path, /resources\/db\/migration\/V2__/);
  assert.equal(byVersion.get(38).type, 'java');
  assert.match(byVersion.get(38).path, /src\/main\/java\/db\/migration\/V38__/);
  assert.match(byVersion.get(49).path, /resources\/db\/migration\/V49__/);
  assert.match(byVersion.get(50).path, /resources\/m6f\/db\/migration\/V50__/);
});
