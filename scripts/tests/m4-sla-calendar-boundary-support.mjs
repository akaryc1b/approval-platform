import { execFile } from 'node:child_process';
import { access, readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { promisify } from 'node:util';

const exec = promisify(execFile);
export const root = process.cwd();
export const migrationsRoot = 'server-modules/approval-persistence-jdbc/src/main/resources/db/migration';
export const clientRoots = [
  'apps/web/overlay/apps/web-ele/src',
  'apps/mobile/overlay/src',
];
export const textExtensions = new Set([
  '.css', '.html', '.java', '.js', '.json', '.mjs', '.sql',
  '.ts', '.tsx', '.vue', '.xml', '.yaml', '.yml',
]);

export async function text(relativePath) {
  return readFile(path.join(root, relativePath), 'utf8');
}

export async function exists(relativePath) {
  try {
    await access(path.join(root, relativePath));
    return true;
  } catch {
    return false;
  }
}

export async function filesUnder(relativePath, acceptedExtensions = null) {
  const result = [];
  async function visit(current) {
    for (const entry of await readdir(path.join(root, current), { withFileTypes: true })) {
      const next = path.join(current, entry.name);
      if (entry.isDirectory()) await visit(next);
      else if (!acceptedExtensions || acceptedExtensions.has(path.extname(entry.name))) result.push(next);
    }
  }
  await visit(relativePath);
  return result;
}

export async function git(...args) {
  return (await exec('git', args, { cwd: root })).stdout.trim();
}

export { exec, path, readdir };
