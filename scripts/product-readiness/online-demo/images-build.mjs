import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, lstatSync } from 'node:fs';
import { resolve } from 'node:path';
import { digest, dockerBuildArguments, imageInputs, limits, pinnedImage, plan, validateSource, verifyImageInspection } from './images-contract.mjs';

// No host build secrets, VITE_* values, Docker build arguments, or tokens are forwarded.
export function processEnvironment(input = process.env) {
  return Object.fromEntries(['PATH', 'HOME', 'USERPROFILE', 'SystemRoot', 'TEMP', 'TMP',
    'TMPDIR', 'DOCKER_CONFIG', 'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS_VERIFY',
    'DOCKER_CERT_PATH'].filter(key => input[key] !== undefined).map(key => [key, input[key]]));
}
export function runCommand(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd, env: processEnvironment(), shell: false,
    timeout: options.timeoutMs || 30_000, maxBuffer: options.maxBuffer || 16 * 1024 * 1024,
    input: options.input, encoding: options.binary ? null : 'utf8',
    stdio: options.live ? ['pipe', 'inherit', 'inherit'] : 'pipe',
  });
  if (result.error || result.status !== 0) {
    // Do not echo child output: tools may print private registry or credential details.
    throw new Error(`${command} failed (${result.error?.code || result.status}); no successful build receipt written`);
  }
  return options.binary ? result.stdout : String(result.stdout || '').trim();
}
export function readSource(root, run = runCommand) {
  const git = args => run('git', args, { cwd: root });
  if (git(['status', '--porcelain', '--untracked-files=no'])) {
    throw new Error('commit tracked source changes before building images');
  }
  const commitSha = git(['rev-parse', 'HEAD']);
  const treeSha = git(['rev-parse', `${commitSha}^{tree}`]);
  const pom = git(['show', `${commitSha}:pom.xml`]);
  const versions = [...pom.matchAll(/<revision>([^<]+)<\/revision>/gu)];
  if (versions.length !== 1) throw new Error('source must declare exactly one application revision');
  const upstream = name => JSON.parse(git(['show', `${commitSha}:apps/${name}/upstream.json`])).commit;
  return validateSource({ commitSha, treeSha, version: versions[0][1],
    epoch: Number(git(['show', '-s', '--format=%ct', commitSha])),
    upstreams: { pc: upstream('web'), h5: upstream('mobile') } });
}
export function resolveImageOptions(root, source, options, run = runCommand) {
  const missing = imageInputs.filter(name => !options.images[name]);
  let defaults = {};
  if (missing.length) {
    const raw = run('git', ['show', `${source.commitSha}:deploy/online-demo/images/base-images.json`], { cwd: root });
    const manifest = JSON.parse(raw);
    if (manifest.schemaVersion !== 1 || !manifest.images
        || Object.keys(manifest.images).sort().join(',') !== [...imageInputs].sort().join(',')) {
      throw new Error('invalid committed base-image pin manifest');
    }
    defaults = manifest.images;
  }
  return { ...options, images: Object.fromEntries(imageInputs.map(name => [
    name, pinnedImage(options.images[name] || defaults[name], name),
  ])) };
}

export function validateTreeListing(listing) {
  const entries = String(listing).split('\0').filter(Boolean);
  if (!entries.length) throw new Error('empty source tree');
  for (const entry of entries) {
    const match = entry.match(/^(100644|100755) blob [0-9a-f]{40}\t([^\x00-\x1f\x7f]+)$/u);
    if (!match) throw new Error('source archive rejects links, submodules or malformed paths');
    const path = match[2];
    if (path.startsWith('/') || path.includes('\\')
        || path.split('/').some(part => ['..', '.', '.git', '.runtime', '.upstream',
          'node_modules', 'target'].includes(part))) throw new Error('unsafe source archive path');
  }
}
function privateDirectory(path) {
  try { mkdirSync(path, { mode: 0o700 }); }
  catch (error) { if (error.code !== 'EEXIST') throw error; }
  if (!lstatSync(path).isDirectory() || lstatSync(path).isSymbolicLink()) {
    throw new Error('build output parent must be a real directory');
  }
}
export function executeImageBuild(root, options, run = runCommand) {
  const source = readSource(root, run);
  const buildPlan = plan(source, resolveImageOptions(root, source, options, run));
  if (buildPlan.missingInputs.length) throw new Error('all four digest-pinned base images are required');
  // The archive pins the previously read SHA, never a moving HEAD or local build outputs.
  const listing = run('git', ['ls-tree', '-rz', source.commitSha], { cwd: root });
  validateTreeListing(listing);
  const archive = run('git', ['archive', '--format=tar', source.commitSha], {
    cwd: root, binary: true, maxBuffer: limits.archiveBytes,
  });
  if (!Buffer.isBuffer(archive) || archive.length < 1024 || archive.length > limits.archiveBytes) {
    throw new Error('source archive size is invalid');
  }
  const archiveSha256 = digest(archive);
  privateDirectory(resolve(root, '.runtime'));
  const outputRoot = resolve(root, '.runtime/online-demo-images');
  privateDirectory(outputRoot);
  const directory = mkdtempSync(resolve(outputRoot, 'build-'));
  const receipt = { schemaVersion: 1, kind: 'ONLINE_DEMO_LOCAL_IMAGE_BUILD_RECEIPT',
    status: 'BUILDING', source, archiveSha256, baseImages: buildPlan.baseImages,
    platform: buildPlan.platform, dependencyPolicy: buildPlan.dependencyPolicy,
    images: [], nonClaims: buildPlan.nonClaims, startedAt: new Date().toISOString() };
  const writeReceipt = () => writeFileSync(resolve(directory, 'image-build.json'),
    `${JSON.stringify(receipt, null, 2)}\n`, { mode: 0o600 });
  writeReceipt();
  try {
    for (const image of buildPlan.images) {
      const iidFile = resolve(directory, `${image.component}.iid`);
      run('docker', dockerBuildArguments(buildPlan, image, archiveSha256, iidFile), {
        cwd: root, input: archive, live: true, timeoutMs: limits.buildMs,
      });
      const imageId = readFileSync(iidFile, 'utf8').trim();
      const inspected = JSON.parse(run('docker', ['image', 'inspect', image.tag], { cwd: root }));
      if (!Array.isArray(inspected) || inspected.length !== 1) throw new Error('ambiguous image inspection');
      receipt.images.push(verifyImageInspection(inspected[0], buildPlan, image, imageId, archiveSha256));
      rmSync(iidFile);
      writeReceipt();
    }
    receipt.status = 'LOCAL_IMAGES_BUILT_NOT_RUNTIME_ACCEPTED';
  } catch (error) {
    receipt.status = 'FAILED';
    receipt.failedComponent = buildPlan.images[receipt.images.length]?.component;
    // Images from earlier successful steps remain local; do not remove operator images.
    receipt.partialImagesRetained = true;
    throw error;
  } finally {
    receipt.completedAt = new Date().toISOString();
    writeReceipt();
  }
  return { directory, receipt };
}
