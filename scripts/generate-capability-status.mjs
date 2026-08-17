#!/usr/bin/env node

import assert from 'node:assert/strict';
import {
  existsSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifestPath = path.join(root, 'config/capabilities.json');
const outputDirectory = path.join(root, 'docs/current');
const checkOnly = process.argv.includes('--check');

const statusLabels = Object.freeze({
  yes: '是',
  partial: '部分',
  in_progress: '进行中',
  no: '否',
});
const allowedStatuses = new Set(Object.keys(statusLabels));

function read(relativePath) {
  return readFileSync(path.join(root, relativePath), 'utf8');
}

function parseJson(relativePath) {
  return JSON.parse(read(relativePath));
}

function walk(directory) {
  const files = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const child = path.join(directory, entry.name);
    if (entry.isDirectory()) files.push(...walk(child));
    else files.push(child);
  }
  return files;
}

function repositoryPath(absolutePath) {
  return path.relative(root, absolutePath).split(path.sep).join('/');
}

function escapeCell(value) {
  return String(value).replaceAll('|', '\\|').replaceAll('\n', '<br>');
}

function linkFromCurrent(relativePath) {
  if (relativePath.startsWith('docs/')) return `../${relativePath.slice('docs/'.length)}`;
  return `../../${relativePath}`;
}

function evidenceCell(paths) {
  return paths.map((evidencePath) => {
    const label = path.basename(evidencePath, path.extname(evidencePath));
    return `[${label}](${linkFromCurrent(evidencePath)})`;
  }).join('<br>');
}

function pomProperty(pom, name) {
  const openingTag = `<${name}>`;
  const closingTag = `</${name}>`;
  const openingIndex = pom.indexOf(openingTag);
  assert.notEqual(openingIndex, -1, `pom.xml must define ${name}`);
  const valueStart = openingIndex + openingTag.length;
  const closingIndex = pom.indexOf(closingTag, valueStart);
  assert.notEqual(closingIndex, -1, `pom.xml must close ${name}`);
  return pom.slice(valueStart, closingIndex).trim();
}

function loadRuntimeVersions() {
  const packageJson = parseJson('package.json');
  const pom = read('pom.xml');
  const packageManager = String(packageJson.packageManager ?? '');
  const separator = packageManager.lastIndexOf('@');
  assert.ok(separator > 0, 'packageManager must contain a name and version');
  return [
    {
      area: 'Java',
      value: pomProperty(pom, 'java.version'),
      status: '必需的构建与运行时基线',
    },
    {
      area: 'Spring Boot',
      value: pomProperty(pom, 'spring-boot.version'),
      status: '当前服务端框架基线',
    },
    {
      area: 'Flowable',
      value: pomProperty(pom, 'flowable.version'),
      status: '仅通过平台 Engine SPI 和公开 API 使用',
    },
    {
      area: 'Node.js',
      value: packageJson.engines?.node,
      status: '仓库客户端与工具基线',
    },
    {
      area: packageManager.slice(0, separator),
      value: packageManager.slice(separator + 1),
      status: '工作区包管理器基线',
    },
  ];
}

function discoverFlyway(manifest) {
  const flyway = manifest.project.flyway;
  assert.ok(
    Number.isInteger(flyway.firstVersion) && flyway.firstVersion > 0,
    'Flyway firstVersion must be a positive integer',
  );
  assert.ok(
    Number.isInteger(flyway.expectedVersion) && flyway.expectedVersion >= flyway.firstVersion,
    'Flyway expectedVersion must be an integer at or after firstVersion',
  );
  const scanRoot = path.join(root, flyway.scanRoot);
  assert.ok(existsSync(scanRoot), `Flyway scan root does not exist: ${flyway.scanRoot}`);
  for (const location of flyway.requiredLocations) {
    assert.ok(existsSync(path.join(root, location)), `Flyway location does not exist: ${location}`);
  }

  const migrations = walk(scanRoot)
    .map((absolutePath) => {
      const match = path.basename(absolutePath).match(/^V(\d+)__.+\.(sql|java)$/i);
      if (!match) return null;
      return {
        version: Number(match[1]),
        type: match[2].toLowerCase(),
        path: repositoryPath(absolutePath),
      };
    })
    .filter(Boolean)
    .sort((left, right) => left.version - right.version || left.path.localeCompare(right.path));

  assert.ok(migrations.length > 0, 'No Flyway migrations were discovered');
  const byVersion = new Map();
  for (const migration of migrations) {
    const existing = byVersion.get(migration.version) ?? [];
    existing.push(migration);
    byVersion.set(migration.version, existing);
  }
  for (const [version, entries] of byVersion) {
    assert.equal(entries.length, 1, `Flyway V${version} is duplicated: ${entries.map(entry => entry.path).join(', ')}`);
  }

  const firstVersion = Math.min(...byVersion.keys());
  const effectiveVersion = Math.max(...byVersion.keys());
  assert.equal(
    firstVersion,
    flyway.firstVersion,
    `Flyway first version is V${firstVersion}, expected V${flyway.firstVersion}`,
  );
  assert.equal(
    effectiveVersion,
    flyway.expectedVersion,
    `Flyway effective version is V${effectiveVersion}, expected V${flyway.expectedVersion}`,
  );
  for (let version = firstVersion; version <= effectiveVersion; version += 1) {
    assert.ok(byVersion.has(version), `Flyway chain is missing V${version}`);
  }
  for (const [versionText, expectedPath] of Object.entries(flyway.requiredMigrations)) {
    const version = Number(versionText);
    assert.deepEqual(
      byVersion.get(version)?.map(entry => entry.path),
      [expectedPath],
      `Flyway V${version} must resolve to its governed location`,
    );
  }

  return {
    firstVersion,
    effectiveVersion,
    count: migrations.length,
    locations: [...new Set(migrations.map(migration => path.posix.dirname(migration.path)))],
    migrations,
  };
}

function validateManifest(manifest) {
  assert.equal(manifest.schemaVersion, 1, 'Unsupported capabilities manifest version');
  assert.ok(Array.isArray(manifest.capabilities) && manifest.capabilities.length > 0);
  const ids = new Set();
  for (const capability of manifest.capabilities) {
    assert.match(capability.id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/);
    assert.equal(ids.has(capability.id), false, `Duplicate capability id: ${capability.id}`);
    ids.add(capability.id);
    for (const key of Object.keys(manifest.statusDefinitions)) {
      const value = capability.status[key];
      assert.ok(allowedStatuses.has(value), `${capability.id}.${key} has unsupported status ${value}`);
    }
    assert.ok(capability.evidence.length > 0, `${capability.id} requires evidence`);
    for (const evidencePath of capability.evidence) {
      assert.ok(existsSync(path.join(root, evidencePath)), `Missing evidence: ${evidencePath}`);
    }
    if (capability.status.released === 'yes') {
      assert.equal(capability.status.merged, 'yes', `${capability.id} cannot be released before merge`);
      assert.equal(capability.status.accepted, 'yes', `${capability.id} cannot be released before acceptance`);
    }
    if (capability.status.productionSupported === 'yes') {
      assert.equal(capability.status.released, 'yes', `${capability.id} cannot be production supported before release`);
    }
  }
  if (manifest.project.releaseStatus === 'UNRELEASED') {
    for (const capability of manifest.capabilities) {
      assert.equal(capability.status.released, 'no', `${capability.id} cannot be released while project is UNRELEASED`);
    }
  }
  if (manifest.project.productionReadiness === 'BLOCKED') {
    for (const capability of manifest.capabilities) {
      assert.notEqual(
        capability.status.productionSupported,
        'yes',
        `${capability.id} cannot claim production support while readiness is BLOCKED`,
      );
    }
  }
}

function renderCapabilityMarkdown(manifest, flyway) {
  const statusKeys = Object.keys(manifest.statusDefinitions);
  const headers = [
    '能力',
    '范围',
    'Implemented',
    'Tested',
    'Accepted',
    'Merged',
    'Released',
    'Production Supported',
    'Evidence',
    '说明',
  ];
  const rows = manifest.capabilities.map((capability) => [
    capability.name,
    capability.scope,
    ...statusKeys.map(key => statusLabels[capability.status[key]]),
    evidenceCell(capability.evidence),
    capability.note,
  ]);

  const semantics = Object.entries(manifest.statusDefinitions)
    .map(([key, description]) => `- **${key}**：${description}`)
    .join('\n');
  const table = [
    `| ${headers.join(' | ')} |`,
    `| ${headers.map(() => '---').join(' | ')} |`,
    ...rows.map(row => `| ${row.map(escapeCell).join(' | ')} |`),
  ].join('\n');
  const locations = flyway.locations.map(location => `- \`${location}\``).join('\n');

  return `# Current Capability Status\n\n`
    + `> 此文件由 \`scripts/generate-capability-status.mjs\` 根据 `
    + `\`config/capabilities.json\` 和仓库迁移树生成。不要手工编辑。\n\n`
    + `## 总体状态\n\n`
    + `| 项目维度 | 当前结论 |\n| --- | --- |\n`
    + `| Release | \`${manifest.project.releaseStatus}\` |\n`
    + `| Production Readiness | \`${manifest.project.productionReadiness}\` |\n`
    + `| Production Support | \`${manifest.project.productionSupport}\` |\n`
    + `| Effective Flyway | \`V${flyway.firstVersion}–V${flyway.effectiveVersion}\`（${flyway.count} 个连续版本） |\n\n`
    + `当前代码、测试和验收事实不能自动推导出 Release 或 Production Support。`
    + `所有发布和生产支持声明都必须经过独立、显式、可审计的决策。\n\n`
    + `## 状态语义\n\n${semantics}\n\n`
    + `## 能力矩阵\n\n${table}\n\n`
    + `## Flyway 组合拓扑\n\n`
    + `生成器递归读取所有 SQL 与 Java migration，而不是只扫描单一目录。当前有效位置：\n\n`
    + `${locations}\n\n`
    + `发现的仓库自有版本必须从 V${flyway.firstVersion} 连续至 V${flyway.effectiveVersion}，每个版本只能有一个权威实现。\n\n`
    + `## 维护规则\n\n`
    + `1. 修改能力事实时，更新 \`config/capabilities.json\` 并重新运行生成器。\n`
    + `2. 历史 SHA、Run、PR 和 Artifact 身份只进入不可变 Acceptance 或 Release 文档。\n`
    + `3. \`Released\` 只能来自真实 tag、GitHub Release、manifest 和制品摘要。\n`
    + `4. \`Production Supported\` 不能由测试通过、验收通过或合并自动推导。\n`;
}

function renderCompatibilityMarkdown(manifest, flyway, derivedRuntime) {
  const runtimeRows = [...derivedRuntime, ...manifest.runtime]
    .map(item => `| ${escapeCell(item.area)} | ${escapeCell(item.value)} | ${escapeCell(item.status)} |`)
    .join('\n');
  const databaseIds = new Set(['postgresql-16', 'mysql-8-4']);
  const databaseRows = manifest.capabilities
    .filter(capability => databaseIds.has(capability.id))
    .map(capability => `| ${escapeCell(capability.name)} | ${statusLabels[capability.status.tested]} | `
      + `${statusLabels[capability.status.accepted]} | ${statusLabels[capability.status.merged]} | `
      + `${statusLabels[capability.status.productionSupported]} | ${escapeCell(capability.note)} |`)
    .join('\n');
  const protocolRows = manifest.protocols
    .map(protocol => `| ${escapeCell(protocol.name)} | ${escapeCell(protocol.version)} | ${escapeCell(protocol.rule)} |`)
    .join('\n');
  const migrationRows = flyway.migrations
    .filter(migration => [2, 38, 49, 50].includes(migration.version))
    .map(migration => `| V${migration.version} | ${migration.type.toUpperCase()} | \`${migration.path}\` |`)
    .join('\n');

  return `# Current Compatibility\n\n`
    + `> 此文件由 \`scripts/generate-capability-status.mjs\` 生成。不要手工编辑。\n\n`
    + `本文件描述默认分支当前已合并代码的兼容边界。它不是 Release 快照，也不构成生产支持承诺。\n\n`
    + `## Runtime baseline\n\n| Area | Value | Status |\n| --- | --- | --- |\n${runtimeRows}\n\n`
    + `## Database support\n\n`
    + `| Database | Tested | Accepted | Merged | Production Supported | Boundary |\n`
    + `| --- | --- | --- | --- | --- | --- |\n${databaseRows}\n\n`
    + `数据库目标、局部测试通过和独立 Draft 工作流都不等于默认分支已支持。`
    + `生产支持必须同时满足合并、Release、运维和支持政策。\n\n`
    + `## Flyway compatibility\n\n`
    + `仓库自有组合迁移路径从 \`V${flyway.firstVersion}\` 连续至 \`V${flyway.effectiveVersion}\`，共 ${flyway.count} 个版本。`
    + `生成器同时识别 SQL、Java migration 和附加资源位置。关键跨位置版本如下：\n\n`
    + `| Version | Type | Governed path |\n| --- | --- | --- |\n${migrationRows}\n\n`
    + `Flyway migration 一经合并或应用不得重写。备份与恢复必须保持平台表和 Flowable 表处于同一恢复点。\n\n`
    + `## Protocol compatibility\n\n| Protocol | Version | Rule |\n| --- | --- | --- |\n${protocolRows}\n\n`
    + `## Permanent boundaries\n\n`
    + `- 生产代码不得查询或修改 Flowable \`ACT_*\` 内部表。\n`
    + `- 浏览器、Mobile、SDK、Connector 或 AI payload 不能制造可信 tenant、operator、permission、audit、worker、lease、credential 或 command authority。\n`
    + `- AI 建议不等于审批决定；Provider 不能直接调用命令。\n`
    + `- 未列为 Production Supported 的组合必须 fail closed，不得从“已有代码”推断为可生产部署。\n`;
}

function buildStatusJson(manifest, flyway, derivedRuntime) {
  return {
    schemaVersion: 1,
    source: 'config/capabilities.json',
    project: {
      name: manifest.project.name,
      releaseStatus: manifest.project.releaseStatus,
      productionReadiness: manifest.project.productionReadiness,
      productionSupport: manifest.project.productionSupport,
      automaticWorkflow: manifest.project.automaticWorkflow,
    },
    flyway: {
      firstVersion: flyway.firstVersion,
      effectiveVersion: flyway.effectiveVersion,
      count: flyway.count,
      locations: flyway.locations,
      versions: flyway.migrations.map(migration => migration.version),
      keyMigrations: flyway.migrations.filter(migration => [2, 38, 49, 50].includes(migration.version)),
    },
    runtime: [...derivedRuntime, ...manifest.runtime],
    protocols: manifest.protocols,
    statusDefinitions: manifest.statusDefinitions,
    capabilities: manifest.capabilities,
  };
}

function writeOrCheck(relativePath, content) {
  const absolutePath = path.join(root, relativePath);
  if (checkOnly) {
    assert.ok(existsSync(absolutePath), `Generated file is missing: ${relativePath}`);
    assert.equal(readFileSync(absolutePath, 'utf8'), content, `Generated file drift: ${relativePath}`);
    return;
  }
  writeFileSync(absolutePath, content, 'utf8');
}

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
validateManifest(manifest);
const flyway = discoverFlyway(manifest);
const runtime = loadRuntimeVersions();
const statusJson = buildStatusJson(manifest, flyway, runtime);

writeOrCheck('docs/current/capability-status.md', renderCapabilityMarkdown(manifest, flyway));
writeOrCheck('docs/current/capability-status.json', `${JSON.stringify(statusJson, null, 2)}\n`);
writeOrCheck('docs/current/compatibility.md', renderCompatibilityMarkdown(manifest, flyway, runtime));

if (checkOnly) {
  console.log('Generated capability and compatibility documents are current.');
} else {
  console.log('Generated docs/current/capability-status.md');
  console.log('Generated docs/current/capability-status.json');
  console.log('Generated docs/current/compatibility.md');
}
