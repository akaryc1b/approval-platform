#!/usr/bin/env node

import assert from 'node:assert/strict';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourcePath = 'config/acceptance-catalog.json';
const outputRoot = 'docs/acceptance';
const checkOnly = process.argv.includes('--check');

function read(relativePath) {
  return readFileSync(path.join(root, relativePath), 'utf8');
}

function parse(relativePath) {
  return JSON.parse(read(relativePath));
}

function validate(catalog, lock) {
  assert.equal(catalog.schemaVersion, 1, 'Unsupported acceptance catalog schema version');
  assert.equal(lock.schemaVersion, 1, 'Unsupported acceptance lock schema version');
  assert.equal(catalog.lockFile, 'config/acceptance-lock.json');
  assert.ok(Array.isArray(catalog.milestones) && catalog.milestones.length > 0);

  const milestoneIds = new Set();
  const recordIds = new Set();
  const recordPaths = [];
  for (const milestone of catalog.milestones) {
    assert.match(milestone.id, /^m[0-9]+$/u);
    assert.equal(milestoneIds.has(milestone.id), false, `Duplicate milestone: ${milestone.id}`);
    milestoneIds.add(milestone.id);
    assert.ok(Array.isArray(milestone.records) && milestone.records.length > 0);
    for (const record of milestone.records) {
      assert.match(record.id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/u);
      assert.equal(recordIds.has(record.id), false, `Duplicate record id: ${record.id}`);
      recordIds.add(record.id);
      assert.match(record.kind, /^[a-z0-9]+(?:_[a-z0-9]+)*$/u);
      assert.equal(Object.hasOwn(record, 'blob'), false, `${record.id} must obtain its blob from the lock`);
      assert.ok(record.path.startsWith('docs/'), `${record.id} must reference a docs path`);
      assert.equal(
        existsSync(path.join(root, record.path)),
        true,
        `Acceptance record does not exist: ${record.path}`,
      );
      assert.match(
        lock.documents[record.path] ?? '',
        /^[0-9a-f]{40}$/u,
        `${record.path} must be registered in the acceptance lock`,
      );
      recordPaths.push(record.path);
    }
  }

  assert.deepEqual(
    [...recordPaths].sort(),
    Object.keys(lock.documents).sort(),
    'Acceptance catalog and immutable lock must cover the same records',
  );
  assert.equal(new Set(recordPaths).size, recordPaths.length, 'Acceptance paths must be unique');
}

function enrich(catalog, lock) {
  return {
    schemaVersion: 1,
    source: sourcePath,
    lock: catalog.lockFile,
    policy: catalog.policy,
    milestones: catalog.milestones.map(milestone => ({
      ...milestone,
      records: milestone.records.map(record => ({
        ...record,
        blob: lock.documents[record.path],
      })),
    })),
  };
}

function linkFrom(directory, target) {
  const relative = path.posix.relative(directory, target);
  return relative.startsWith('.') ? relative : `./${relative}`;
}

function renderRoot(catalog) {
  const rows = catalog.milestones.map(milestone =>
    `| ${milestone.title} | ${milestone.records.length} | `
      + `[分类入口](${milestone.id}/README.md) | ${milestone.summary} |`);

  return [
    '# Acceptance Records',
    '',
    '> 此文件由 `scripts/generate-acceptance-catalog.mjs` 根据 '
      + '`config/acceptance-catalog.json` 和 `config/acceptance-lock.json` 生成。不要手工编辑。',
    '',
    'Acceptance 文档是不可变历史证据，回答“某个精确范围在当时如何被验证”。'
      + '它们可以记录 commit、PR、Workflow Run、Job、Artifact、摘要、失败修正和非授权边界。',
    '',
    'Acceptance 不能回答“当前默认分支支持什么”“是否已经发布”或“是否支持生产”。'
      + '这些结论分别由 Current、Release 和 Production Support 决策承担。',
    '',
    '## 分类入口',
    '',
    '| Milestone | Locked records | Index | Scope |',
    '| --- | ---: | --- | --- |',
    ...rows,
    '',
    '机器可读目录见 [`catalog.json`](catalog.json)。',
    '',
    '## 不可变规则',
    '',
    '- `config/acceptance-lock.json` 是已登记历史正文的 Blob 锁；',
    '- Catalog 必须完整覆盖 Lock，且不得复制或手写 Blob；',
    '- 不得重写、删除或静默更正历史验收；',
    '- 修正必须新增 `CORRECTION`、`AMENDMENT` 或新的后续验收记录；',
    '- Current、Release、Roadmap 和 Acceptance 不得互相替代。',
    '',
    '## 路径保持策略',
    '',
    '本阶段通过 M3–M6 分类入口建立规范目录，但不移动已锁定正文。'
      + '历史正文中的相对链接、已有 PR/Issue 链接和外部引用也是证据上下文；'
      + '直接搬入子目录会破坏这些链接。',
    '',
    '后续只有在完整链接保持方案可以证明时，才允许使用保留相同 Git Blob 的受控迁移。'
      + '目录整理本身不能改变验收结论。',
    '',
  ].join('\n');
}

function renderMilestone(milestone) {
  const directory = `${outputRoot}/${milestone.id}`;
  const rows = milestone.records.map(record =>
    `| ${record.title} | \`${record.kind}\` | `
      + `[打开不可变正文](${linkFrom(directory, record.path)}) | \`${record.blob}\` |`);

  return [
    `# ${milestone.title} Acceptance Records`,
    '',
    '> 此文件由 `scripts/generate-acceptance-catalog.mjs` 生成。不要手工编辑。',
    '',
    milestone.summary,
    '',
    '| Record | Kind | Immutable document | Locked Git blob |',
    '| --- | --- | --- | --- |',
    ...rows,
    '',
    '本页只负责分类和导航，不复制、改写或重新解释历史正文。'
      + '当前能力状态请查看 [Current Capability Status](../../current/capability-status.md)。',
    '',
  ].join('\n');
}

function writeOrCheck(relativePath, content) {
  const absolutePath = path.join(root, relativePath);
  if (checkOnly) {
    assert.equal(existsSync(absolutePath), true, `Generated file is missing: ${relativePath}`);
    assert.equal(readFileSync(absolutePath, 'utf8'), content, `Generated file drift: ${relativePath}`);
    return;
  }
  mkdirSync(path.dirname(absolutePath), { recursive: true });
  writeFileSync(absolutePath, content, 'utf8');
}

const catalog = parse(sourcePath);
const lock = parse(catalog.lockFile);
validate(catalog, lock);
const enriched = enrich(catalog, lock);

writeOrCheck(`${outputRoot}/README.md`, renderRoot(enriched));
writeOrCheck(`${outputRoot}/catalog.json`, `${JSON.stringify(enriched, null, 2)}\n`);
for (const milestone of enriched.milestones) {
  writeOrCheck(`${outputRoot}/${milestone.id}/README.md`, renderMilestone(milestone));
}

if (checkOnly) {
  console.log('Generated acceptance catalog documents are current.');
} else {
  console.log('Generated docs/acceptance/README.md');
  console.log('Generated docs/acceptance/catalog.json');
  for (const milestone of enriched.milestones) {
    console.log(`Generated docs/acceptance/${milestone.id}/README.md`);
  }
}
