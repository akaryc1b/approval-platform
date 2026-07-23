import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const aiRoots = [
  path.join(root, 'server-modules/approval-ai-spi'),
  path.join(root, 'server-modules/approval-ai-core'),
];

function filesUnder(directory) {
  if (!existsSync(directory)) return [];
  const result = [];
  for (const entry of readdirSync(directory)) {
    const absolute = path.join(directory, entry);
    if (statSync(absolute).isDirectory()) result.push(...filesUnder(absolute));
    else result.push(absolute);
  }
  return result;
}

function relative(file) {
  return path.relative(root, file).split(path.sep).join('/');
}

function text(file) {
  return readFileSync(file, 'utf8');
}

const aiFiles = aiRoots.flatMap(filesUnder);
const aiMainJava = aiFiles.filter((file) =>
  relative(file).includes('/src/main/java/') && file.endsWith('.java'));
const aiTestJava = aiFiles.filter((file) =>
  relative(file).includes('/src/test/java/') && file.endsWith('.java'));

test('AI production code has no network client, credentials, provider adapter or prompt asset', () => {
  assert.ok(aiMainJava.length > 0, 'AI main sources must exist');
  const production = aiMainJava.map(text).join('\n');
  const forbiddenPatterns = [
    /import\s+java\.net\./,
    /import\s+org\.springframework\.web\./,
    /import\s+okhttp3\./,
    /HttpClient\s*\./,
    /WebClient\s*\./,
    /RestClient\s*\./,
    /System\.getenv\s*\(/,
    /System\.getProperty\s*\(/,
    /@Value\s*\(/,
    /implements\s+AiAdvisoryProvider/,
    /Authorization\s*:/,
    /api[_-]?key\s*=/i,
  ];
  for (const pattern of forbiddenPatterns) {
    assert.doesNotMatch(production, pattern);
  }
  for (const moduleRoot of aiRoots) {
    assert.equal(
      existsSync(path.join(moduleRoot, 'src/main/resources')),
      false,
      `${relative(moduleRoot)} must not contain production prompt or knowledge assets`,
    );
  }
});

test('AI foundation has no persistence, V33, customer knowledge data or production mock', () => {
  const production = aiMainJava.map(text).join('\n');
  for (const pattern of [
    /import\s+java\.sql\./,
    /import\s+javax\.sql\./,
    /import\s+org\.springframework\.jdbc\./,
    /import\s+io\.github\.akaryc1b\.approval\.persistence\./,
    /\bJdbc[A-Z]/,
    /\bFlyway\b/,
  ]) {
    assert.doesNotMatch(production, pattern);
  }
  assert.equal(
    aiMainJava.some((file) => path.basename(file).includes('MockAiProvider')),
    false,
  );
  assert.ok(
    aiTestJava.some((file) => path.basename(file) === 'DeterministicMockAiProvider.java'),
    'deterministic mock must remain test-only',
  );

  const migrations = filesUnder(
    path.join(root, 'server-modules/approval-persistence-jdbc/src/main/resources/db/migration'),
  );
  assert.equal(
    migrations.some((file) => /^V33__/.test(path.basename(file))),
    false,
    'M6-D must not create V33',
  );
});

test('AI contracts cannot execute approval, transfer, termination or migration commands', () => {
  const production = aiMainJava.map(text).join('\n');
  for (const pattern of [
    /import\s+io\.github\.akaryc1b\.approval\.application\./,
    /import\s+io\.github\.akaryc1b\.approval\.engine\./,
    /\.(approve|reject|returnTask|transfer|withdraw|terminate|migrate|resubmit)\s*\(/,
    /RecommendationType\s*\{[^}]*(APPROVE|REJECT|TRANSFER|MIGRATE)/s,
  ]) {
    assert.doesNotMatch(production, pattern);
  }

  const resultContract = text(path.join(
    root,
    'server-modules/approval-ai-spi/src/main/java/' +
      'io/github/akaryc1b/approval/ai/spi/AiAdvisoryResult.java',
  ));
  assert.match(resultContract, /UNVERIFIED_ADVISORY/);
  assert.match(resultContract, /needsHumanReview/);
  assert.doesNotMatch(resultContract, /\bApprovalDecision\b|\bApprovalCommand\b/);
});

test('only the permanent validation workflow is automatic', () => {
  const workflowRoot = path.join(root, '.github/workflows');
  const workflows = filesUnder(workflowRoot)
    .filter((file) => /\.ya?ml$/.test(file));
  const automatic = workflows.filter((file) => {
    const content = text(file);
    return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
  });
  assert.deepEqual(
    automatic.map(relative).sort(),
    ['.github/workflows/approval-platform-validation.yml'],
  );
});

test('M6-D branch diff preserves M5, runtime binding and frozen governance boundaries', () => {
  if (process.env.GITHUB_HEAD_REF !== 'agent/m6-d-ai-foundation') return;

  const changed = execFileSync(
    'git',
    ['diff', '--name-only', 'origin/main...HEAD'],
    { cwd: root, encoding: 'utf8' },
  ).trim().split('\n').filter(Boolean);
  const forbidden = changed.filter((file) =>
    file.startsWith('server-modules/approval-persistence-jdbc/') ||
    /migration.*(plan|intent|attempt|verification|reconciliation)/i.test(file) ||
    /runtime.*binding/i.test(file) ||
    /^docs\/M3_FINAL_ACCEPTANCE\.md$/.test(file) ||
    /^docs\/M4_.*GOVERNANCE\.md$/.test(file));
  assert.deepEqual(forbidden, []);
  assert.equal(changed.some((file) => /V33__/.test(file)), false);
});
