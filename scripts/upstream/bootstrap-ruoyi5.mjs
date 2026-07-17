import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, '../..');
const configPath = resolve(repositoryRoot, 'integrations/ruoyi5-host-starter/upstream.json');
const overlayDirectory = resolve(repositoryRoot, 'integrations/ruoyi5-host-starter/overlay');
const generatedRoot = resolve(repositoryRoot, '.upstream');
const upstreamDirectory = resolve(generatedRoot, 'ruoyi5');
const markerPath = resolve(upstreamDirectory, '.approval-platform-upstream.json');

const config = JSON.parse(await readFile(configPath, 'utf8'));
const cleanOnly = process.argv.includes('--clean');

if (cleanOnly) {
  await rm(upstreamDirectory, { force: true, recursive: true });
  console.log('Removed generated RuoYi 5.X workspace.');
  process.exit(0);
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    stdio: options.capture ? 'pipe' : 'inherit',
  });

  if (result.status !== 0) {
    const details = [result.stdout, result.stderr].filter(Boolean).join('\n');
    throw new Error(`${command} ${args.join(' ')} failed\n${details}`);
  }

  return options.capture ? result.stdout.trim() : undefined;
}

async function clonePinnedUpstream() {
  await mkdir(generatedRoot, { recursive: true });
  await rm(upstreamDirectory, { force: true, recursive: true });
  run('git', [
    'clone',
    '--filter=blob:none',
    '--no-checkout',
    config.repository,
    upstreamDirectory,
  ]);
  run('git', [
    '-C',
    upstreamDirectory,
    'fetch',
    '--depth',
    '1',
    'origin',
    config.commit,
  ]);
  run('git', ['-C', upstreamDirectory, 'checkout', '--detach', config.commit]);
}

if (!existsSync(resolve(upstreamDirectory, '.git'))) {
  await clonePinnedUpstream();
}

let currentCommit = run(
  'git',
  ['-C', upstreamDirectory, 'rev-parse', 'HEAD'],
  { capture: true },
);

if (currentCommit !== config.commit) {
  console.warn(`RuoYi 5.X workspace is at ${currentCommit}; recreating pinned source.`);
  await clonePinnedUpstream();
  currentCommit = run(
    'git',
    ['-C', upstreamDirectory, 'rev-parse', 'HEAD'],
    { capture: true },
  );
}

if (currentCommit !== config.commit) {
  throw new Error(`RuoYi 5.X resolved to ${currentCommit}, not ${config.commit}.`);
}

run('git', ['-C', upstreamDirectory, 'reset', '--hard', config.commit]);

const moduleDirectory = resolve(
  upstreamDirectory,
  'ruoyi-extend/ruoyi-approval-host-starter',
);
await rm(moduleDirectory, { force: true, recursive: true });
await cp(
  resolve(overlayDirectory, 'ruoyi-extend/ruoyi-approval-host-starter'),
  moduleDirectory,
  { force: true, recursive: true },
);

const extendPomPath = resolve(upstreamDirectory, 'ruoyi-extend/pom.xml');
let extendPom = await readFile(extendPomPath, 'utf8');
const moduleLine = '        <module>ruoyi-approval-host-starter</module>';
if (!extendPom.includes(moduleLine.trim())) {
  const anchor = '        <module>ruoyi-snailjob-server</module>';
  if (!extendPom.includes(anchor)) {
    throw new Error('Unable to locate RuoYi 5.X extension module anchor.');
  }
  extendPom = extendPom.replace(anchor, `${anchor}\n${moduleLine}`);
  await writeFile(extendPomPath, extendPom, 'utf8');
}

const adminPomPath = resolve(upstreamDirectory, 'ruoyi-admin/pom.xml');
let adminPom = await readFile(adminPomPath, 'utf8');
const starterArtifact = '<artifactId>ruoyi-approval-host-starter</artifactId>';
if (!adminPom.includes(starterArtifact)) {
  const dependency = `
        <!-- Approval Platform signed host connector -->
        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-approval-host-starter</artifactId>
            <version>\${revision}</version>
        </dependency>
`;
  const anchor = '    </dependencies>';
  if (!adminPom.includes(anchor)) {
    throw new Error('Unable to locate RuoYi 5.X admin dependency anchor.');
  }
  adminPom = adminPom.replace(anchor, `${dependency}\n${anchor}`);
  await writeFile(adminPomPath, adminPom, 'utf8');
}

await writeFile(
  markerPath,
  `${JSON.stringify(
    {
      repository: config.repository,
      branch: config.branch,
      commit: config.commit,
      revision: config.revision,
      hostSdkVersion: config.hostSdkVersion,
      generatedAt: new Date().toISOString(),
    },
    null,
    2,
  )}\n`,
  'utf8',
);

console.log(`RuoYi 5.X prepared at ${upstreamDirectory}.`);
