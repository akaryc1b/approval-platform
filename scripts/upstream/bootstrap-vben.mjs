import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, '../..');
const configPath = resolve(repositoryRoot, 'apps/web/upstream.json');
const overlayDirectory = resolve(repositoryRoot, 'apps/web/overlay');
const generatedRoot = resolve(repositoryRoot, '.upstream');
const upstreamDirectory = resolve(generatedRoot, 'vben');
const markerPath = resolve(upstreamDirectory, '.approval-platform-upstream.json');

const config = JSON.parse(await readFile(configPath, 'utf8'));
const cleanOnly = process.argv.includes('--clean');

if (cleanOnly) {
  await rm(upstreamDirectory, { force: true, recursive: true });
  console.log('Removed generated Vben workspace.');
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
    '--depth',
    '1',
    '--branch',
    config.tag,
    config.repository,
    upstreamDirectory,
  ]);
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
  console.warn(
    `Generated Vben workspace is at ${currentCommit}; expected ${config.commit}. Recreating it.`,
  );
  await clonePinnedUpstream();
  currentCommit = run(
    'git',
    ['-C', upstreamDirectory, 'rev-parse', 'HEAD'],
    { capture: true },
  );
}

if (currentCommit !== config.commit) {
  throw new Error(
    `Vben tag ${config.tag} resolved to ${currentCommit}, not pinned commit ${config.commit}.`,
  );
}

run('git', ['-C', upstreamDirectory, 'reset', '--hard', config.commit]);

const upstreamApplication = resolve(upstreamDirectory, config.applicationPath);
const managedPaths = [
  'src/router/routes/modules',
  'src/views/approval',
  'src/platform/approval',
];

for (const managedPath of managedPaths) {
  await rm(resolve(upstreamApplication, managedPath), {
    force: true,
    recursive: true,
  });
}

await cp(overlayDirectory, upstreamDirectory, {
  force: true,
  recursive: true,
});

await writeFile(
  markerPath,
  `${JSON.stringify(
    {
      repository: config.repository,
      tag: config.tag,
      commit: config.commit,
      applicationPath: config.applicationPath,
      generatedAt: new Date().toISOString(),
    },
    null,
    2,
  )}\n`,
  'utf8',
);

console.log(`Vben ${config.tag} prepared at ${upstreamDirectory}.`);
