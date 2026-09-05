import { lstatSync, mkdirSync, readFileSync, readdirSync, realpathSync, utimesSync, writeFileSync } from 'node:fs';
import { basename, dirname, extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { digest, requireSha } from './images-contract.mjs';

const extensions = new Set(['.html', '.js', '.mjs', '.css', '.json', '.webmanifest', '.txt',
  '.svg', '.png', '.jpg', '.jpeg', '.gif', '.webp', '.avif', '.ico', '.woff', '.woff2',
  '.ttf', '.otf', '.eot', '.wasm', '.mp4', '.webm']);
export const staticLimits = Object.freeze({ files: 10_000, fileBytes: 32 * 1024 * 1024, totalBytes: 256 * 1024 * 1024 });
function regularFile(path, maximum) {
  const value = lstatSync(path);
  if (!value.isFile() || value.isSymbolicLink() || value.size > maximum || value.size === 0) {
    throw new Error('artifact must be a nonempty bounded regular file');
  }
  const bytes = readFileSync(path);
  if (bytes.length !== value.size) throw new Error('artifact changed during inventory');
  return bytes;
}
export function collectStaticArtifacts(directory) {
  const root = resolve(directory);
  if (realpathSync(root) !== root || !lstatSync(root).isDirectory()) {
    throw new Error('static root must not traverse a symbolic link');
  }
  const files = [];
  let totalBytes = 0;
  function walk(path, relative = '') {
    for (const name of readdirSync(path).sort()) {
      if (name.startsWith('.') || /[\\\x00-\x1f\x7f]/u.test(name)) {
        throw new Error('hidden or unsafe static artifact path');
      }
      const target = resolve(path, name);
      const item = relative ? `${relative}/${name}` : name;
      const stat = lstatSync(target);
      if (stat.isSymbolicLink()) throw new Error('static artifacts reject symbolic links');
      if (stat.isDirectory()) { walk(target, item); continue; }
      const unpacked = item.replace(/\.(?:gz|br)$/u, '');
      if (!extensions.has(extname(unpacked).toLowerCase())
          || /^(?:package(?:-lock)?\.json|pnpm-lock\.yaml|yarn\.lock)$/iu.test(basename(unpacked))) {
        throw new Error('static artifact type is not allowed');
      }
      const bytes = regularFile(target, staticLimits.fileBytes);
      totalBytes += bytes.length;
      if (files.length >= staticLimits.files || totalBytes > staticLimits.totalBytes) {
        throw new Error('static artifact inventory exceeds limits');
      }
      files.push({ path: item, size: bytes.length, sha256: digest(bytes) });
    }
  }
  walk(root);
  if (!files.some(file => file.path === 'index.html')) throw new Error('static build is missing index.html');
  return { files, totalBytes, inventorySha256: digest(JSON.stringify(files)) };
}
export function stageStaticArtifacts(component, source, lockPath, output, identity) {
  if (!['pc', 'h5'].includes(component)) throw new Error('static component must be pc or h5');
  requireSha(identity.commitSha, 'commitSha');
  requireSha(identity.treeSha, 'treeSha');
  if (!Number.isSafeInteger(identity.epoch) || identity.epoch <= 0) throw new Error('invalid source epoch');
  const inventory = collectStaticArtifacts(source);
  const lock = regularFile(lockPath, 16 * 1024 * 1024);
  const target = resolve(output);
  // A fresh output only: never merge stale artifacts into a current-source image.
  mkdirSync(target, { mode: 0o755 });
  for (const item of inventory.files) {
    const bytes = regularFile(resolve(source, item.path), staticLimits.fileBytes);
    if (digest(bytes) !== item.sha256) throw new Error('static artifact changed before staging');
    const path = resolve(target, 'public', item.path);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, bytes, { flag: 'wx', mode: 0o644 });
    utimesSync(path, identity.epoch, identity.epoch);
  }
  const metadata = {
    schemaVersion: 1, kind: 'ONLINE_DEMO_STATIC_ARTIFACT_INVENTORY', component, ...identity,
    ...inventory, lockSha256: digest(lock),
    dependencyResolution: component === 'pc' ? 'FROZEN_LOCKFILE' : 'NON_FROZEN_RESOLVED_LOCK',
    scope: 'PACKAGING_ONLY_NOT_BROWSER_OR_BUSINESS_ACCEPTANCE',
  };
  writeFileSync(resolve(target, 'build-info.json'), `${JSON.stringify(metadata, null, 2)}\n`, { flag: 'wx' });
  writeFileSync(resolve(target, 'resolved-pnpm-lock.yaml'), lock, { flag: 'wx' });
  return metadata;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.length !== 6) throw new Error('expected component, dist, lockfile, fresh output');
    stageStaticArtifacts(...process.argv.slice(2), {
      commitSha: process.env.SOURCE_COMMIT, treeSha: process.env.SOURCE_TREE,
      epoch: Number(process.env.SOURCE_DATE_EPOCH),
    });
  } catch (error) {
    console.error(`ONLINE_DEMO_STATIC_PACKAGING_FAILED: ${error.message}`);
    process.exitCode = 1;
  }
}
