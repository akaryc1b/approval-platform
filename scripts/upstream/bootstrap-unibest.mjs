import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, '../..');
const configPath = resolve(repositoryRoot, 'apps/mobile/upstream.json');
const overlayDirectory = resolve(repositoryRoot, 'apps/mobile/overlay');
const generatedRoot = resolve(repositoryRoot, '.upstream');
const upstreamDirectory = resolve(generatedRoot, 'unibest');
const markerPath = resolve(upstreamDirectory, '.approval-platform-upstream.json');

const config = JSON.parse(await readFile(configPath, 'utf8'));
const cleanOnly = process.argv.includes('--clean');

if (cleanOnly) {
  await rm(upstreamDirectory, { force: true, recursive: true });
  console.log('Removed generated Unibest workspace.');
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
  console.warn(
    `Generated Unibest workspace is at ${currentCommit}; expected ${config.commit}. Recreating it.`,
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
    `Unibest resolved to ${currentCommit}, not pinned commit ${config.commit}.`,
  );
}

run('git', ['-C', upstreamDirectory, 'reset', '--hard', config.commit]);

const managedPaths = [
  'src/api/approval',
  'src/pages/index',
  'src/pages/task',
  'src/pages/message',
  'src/pages/discussion',
  'src/pages/initiate',
  'src/pages/profile',
  'src/pages/operations',
  'src/components/approval',
  'src/platform/approval',
];

for (const managedPath of managedPaths) {
  await rm(resolve(upstreamDirectory, managedPath), {
    force: true,
    recursive: true,
  });
}

await cp(overlayDirectory, upstreamDirectory, {
  force: true,
  recursive: true,
});

// The pinned Unibest development server enables Eruda for every H5 run. The
// product-readiness local demo uses real visible controls, so its generated
// workspace must not place a debug overlay above those controls. Keep normal
// upstream development behavior unchanged and fail closed if the pinned
// boundary drifts.
const viteConfigPath = resolve(upstreamDirectory, 'vite.config.ts');
const upstreamErudaBoundary =
  "open: UNI_PLATFORM === 'h5' && mode === 'development',";
const governedErudaBoundary = [
  "open: UNI_PLATFORM === 'h5'",
  "          && mode === 'development'",
  "          && env.VITE_APPROVAL_LOCAL_DEMO !== 'true',",
].join('\n');
let viteConfig = await readFile(viteConfigPath, 'utf8');
const erudaBoundaryMatches = viteConfig.split(upstreamErudaBoundary).length - 1;
if (erudaBoundaryMatches !== 1) {
  throw new Error(
    `Pinned Unibest Eruda boundary changed; expected one match, found ${erudaBoundaryMatches}.`,
  );
}
viteConfig = viteConfig.replace(upstreamErudaBoundary, governedErudaBoundary);
await writeFile(viteConfigPath, viteConfig, 'utf8');

const packagePath = resolve(upstreamDirectory, 'package.json');
const packageJson = JSON.parse(await readFile(packagePath, 'utf8'));
packageJson.name = '@approval/mobile';
packageJson.private = true;
packageJson.description = 'Approval Platform mobile client generated from Unibest';
packageJson.repository = {
  type: 'git',
  url: 'https://github.com/akaryc1b/approval-platform.git',
  directory: 'apps/mobile',
};
packageJson.dependencies = {
  ...packageJson.dependencies,
  'wot-design-uni': config.wotDesignUniVersion,
};
await writeFile(packagePath, `${JSON.stringify(packageJson, null, 2)}\n`, 'utf8');

const tsconfigPath = resolve(upstreamDirectory, 'tsconfig.json');
const tsconfig = JSON.parse(await readFile(tsconfigPath, 'utf8'));
const types = (tsconfig.compilerOptions?.types ?? []).filter(
  typeName => typeName !== 'wot-design-uni/global',
);
tsconfig.compilerOptions = {
  ...tsconfig.compilerOptions,
  target: 'ES2020',
  downlevelIteration: true,
  types,
};
await writeFile(tsconfigPath, `${JSON.stringify(tsconfig, null, 2)}\n`, 'utf8');

// Unibest references these generated declarations in tsconfig but its base
// generator does not create them. Empty stubs keep type checking deterministic
// until an optional async component plugin writes richer declarations.
const generatedTypesDirectory = resolve(upstreamDirectory, 'src/types');
await mkdir(generatedTypesDirectory, { recursive: true });
for (const fileName of ['async-component.d.ts', 'async-import.d.ts']) {
  const filePath = resolve(generatedTypesDirectory, fileName);
  if (!existsSync(filePath)) {
    await writeFile(filePath, 'export {};\n', 'utf8');
  }
}

// The generated project lives below the approval Monorepo. Without a local
// workspace manifest pnpm walks upward and installs the parent workspace instead.
await writeFile(
  resolve(upstreamDirectory, 'pnpm-workspace.yaml'),
  "packages:\n  - '.'\n",
  'utf8',
);

await writeFile(
  markerPath,
  `${JSON.stringify(
    {
      repository: config.repository,
      version: config.version,
      commit: config.commit,
      wotDesignUniVersion: config.wotDesignUniVersion,
      generatedAt: new Date().toISOString(),
    },
    null,
    2,
  )}\n`,
  'utf8',
);

console.log(`Unibest ${config.version} prepared at ${upstreamDirectory}.`);
