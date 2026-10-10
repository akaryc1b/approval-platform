// Replay hash-verified Git objects without trusting a checkout or asserted path.
import { createHash } from 'node:crypto';
const canonical = value => JSON.stringify(value);
const requireValue = (condition, message) => { if (!condition) throw new Error(`Gitleaks source proof ${message}`); };
const SHA40 = /^[0-9a-f]{40}$/;
export const objectHash = (type, bytes) => createHash('sha1').update(`${type} ${bytes.length}\0`).update(bytes).digest('hex');

export function bytesFromBase64(value) {
  requireValue(typeof value === 'string', 'object bytes required');
  const bytes = Buffer.from(value, 'base64');
  requireValue(bytes.toString('base64') === value, 'noncanonical object bytes');
  return bytes;
}
export function commitObject(row) {
  requireValue(row && canonical(Object.keys(row).sort()) === canonical(['contentBase64', 'sha']), 'commit object shape drift');
  const bytes = bytesFromBase64(row.contentBase64);
  requireValue(SHA40.test(row.sha || '') && objectHash('commit', bytes) === row.sha, 'Git commit object hash mismatch');
  const text = bytes.toString('utf8'), header = text.split('\n\n')[0], lines = header.split('\n');
  requireValue(text.includes('\n\n') && /^tree [0-9a-f]{40}$/.test(lines[0])
    && lines.every(line => !/^(tree|parent)(?:\s|$)/.test(line) || /^(tree|parent) [0-9a-f]{40}$/.test(line)),
    'Git commit header syntax mismatch');
  const trees = [...header.matchAll(/^tree ([0-9a-f]{40})$/gm)].map(match => match[1]);
  const parents = [...header.matchAll(/^parent ([0-9a-f]{40})$/gm)].map(match => match[1]);
  requireValue(trees.length === 1 && lines.slice(1, parents.length + 1).every(line => /^parent /.test(line))
    && /^author .+ [0-9]+ [+-][0-9]{4}$/.test(lines[parents.length + 1] || '')
    && /^committer .+ [0-9]+ [+-][0-9]{4}$/.test(lines[parents.length + 2] || ''), 'Git commit tree/parents/identity syntax mismatch');
  return { sha: row.sha, tree: trees[0], parents };
}
export function treeEntries(bytes) {
  const entries = [], seen = new Set();
  let offset = 0;
  while (offset < bytes.length) {
    const space = bytes.indexOf(32, offset), end = bytes.indexOf(0, space + 1);
    requireValue(space > offset && end > space && end + 21 <= bytes.length, 'Git tree encoding mismatch');
    const mode = bytes.subarray(offset, space).toString('utf8'), name = bytes.subarray(space + 1, end).toString('utf8');
    requireValue(['40000', '100644', '100755', '120000', '160000'].includes(mode)
      && name && !name.includes('/') && !seen.has(name), 'Git tree entry mismatch');
    seen.add(name);
    entries.push({ mode, name, sha: bytes.subarray(end + 1, end + 21).toString('hex') });
    offset = end + 21;
  }
  return entries;
}
export function verifyPath(trees, rootTree, path, expectedBlob) {
  requireValue(Array.isArray(trees), 'Git path proof required');
  const parts = path.split('/');
  let expectedTree = rootTree;
  for (let index = 0; index < parts.length; index++) {
    const row = trees[index];
    requireValue(row && canonical(Object.keys(row).sort()) === canonical(['contentBase64', 'sha']), 'Git path proof shape drift');
    const bytes = bytesFromBase64(row.contentBase64);
    requireValue(row.sha === expectedTree && objectHash('tree', bytes) === expectedTree, 'Git path tree hash mismatch');
    const entry = treeEntries(bytes).find(item => item.name === parts[index]);
    if (!entry) {
      requireValue(expectedBlob === null && trees.length === index + 1, 'Git source path absent');
      return;
    }
    if (index === parts.length - 1) {
      requireValue(expectedBlob !== null && entry.mode === '100644' && entry.sha === expectedBlob
        && trees.length === parts.length, 'Git source path/blob mismatch');
      return;
    }
    requireValue(entry.mode === '40000', 'Git source directory mismatch');
    expectedTree = entry.sha;
  }
}
