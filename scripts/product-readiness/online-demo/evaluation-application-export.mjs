import { randomBytes } from 'node:crypto';
import { chmodSync, mkdirSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { createEvaluationApplicationAssets } from './evaluation-applications.mjs';
import { runEvaluationDocker } from './evaluation-docker-slots.mjs';

const requireValue = (value, code) => { if (!value) throw new Error(code); };
const one = text => { const values = JSON.parse(text); requireValue(Array.isArray(values) && values.length === 1, 'APPLICATION_EXPORT_IDENTITY'); return values[0]; };
/** Export only already-built exact images. The two owned export containers NEVER start. */
export async function exportEvaluationApplications({ smoke, run = runEvaluationDocker, signal } = {}) {
  requireValue(smoke?.status === 'LOCAL_IMAGE_STARTUP_SMOKE_PASSED' && smoke.cleanup?.status === 'PASSED'
    && smoke.build?.source?.commitSha === smoke.source?.commitSha && smoke.build?.source?.treeSha === smoke.source?.treeSha,
  'EXACT_APPLICATION_IMAGES_REQUIRED');
  const namespace = randomBytes(16).toString('hex');
  const directory = mkdtempSync(resolve(tmpdir(), 'approval-evaluation-apps-')); chmodSync(directory, 0o700);
  const roots = {}; const owned = []; const actions = [];
  const command = args => run(args, { signal, timeoutMs: 30_000 });
  let cleanupWork;
  async function cleanup() {
    if (cleanupWork) return cleanupWork;
    cleanupWork = (async () => {
      let okay = true;
      for (const item of owned.reverse()) {
        try {
          const target = item.id || item.name;
          const value = one(await run(['container', 'inspect', target], { timeoutMs: 5000 }));
          requireValue((!item.id || value.Id === item.id) && /^[0-9a-f]{64}$/u.test(value.Id) && value.Name === '/' + item.name
            && value.Config?.Labels?.['io.approval.application-export'] === namespace
            && value.Image === item.image && value.State?.Running === false, 'APPLICATION_EXPORT_OWNERSHIP');
          item.id = value.Id;
          await run(['container', 'rm', item.id], { timeoutMs: 5000 });
          const remaining = await run(['container', 'ls', '-a', '-q', '--no-trunc', '--filter', 'id=' + item.id], { timeoutMs: 5000 });
          requireValue(remaining === '', 'APPLICATION_EXPORT_CLEANUP'); actions.push('removed:' + item.component);
        } catch { okay = false; }
      }
      return { status: okay ? 'PASSED' : 'FAILED', actions };
    })();
    return cleanupWork;
  }
  try {
    for (const component of ['pc', 'h5']) {
      const image = smoke.build.images.find(value => value.component === component);
      requireValue(image && /^sha256:[0-9a-f]{64}$/u.test(image.localImageId), 'APPLICATION_EXPORT_IMAGE');
      const info = one(await command(['image', 'inspect', image.localImageId]));
      const labels = info.Config?.Labels;
      requireValue(info.Id === image.localImageId && info.Config.User === '101:101'
        && labels?.['org.opencontainers.image.revision'] === smoke.source.commitSha
        && labels?.['io.approval.source.tree'] === smoke.source.treeSha
        && labels?.['io.approval.component'] === component, 'APPLICATION_EXPORT_SOURCE');
      const name = `ap-app-export-${namespace}-${component}`;
      const tracked = { id: null, name, image: image.localImageId, component }; owned.push(tracked);
      const id = await command(['container', 'create', '--name', name, '--label', 'io.approval.application-export=' + namespace,
        '--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
        '--entrypoint', '/bin/false', image.localImageId]);
      requireValue(/^[0-9a-f]{64}$/u.test(id), 'APPLICATION_EXPORT_ID');
      tracked.id = id;
      const publicDirectory = resolve(directory, component); mkdirSync(publicDirectory);
      const inventoryFile = resolve(directory, component + '.json');
      await command(['container', 'cp', `${id}:/app/public/.`, publicDirectory]);
      await command(['container', 'cp', `${id}:/opt/approval/build-info.json`, inventoryFile]);
      roots[component] = { publicDirectory, inventoryFile };
    }
    // Refuse serving stale, ordinary-mode, symbolic or digest-mismatched bytes.
    createEvaluationApplicationAssets({ source: smoke.source, roots });
    const removed = await cleanup(); requireValue(removed.status === 'PASSED', 'APPLICATION_EXPORT_CLEANUP');
    return { roots, cleanup: removed, dispose() { rmSync(directory, { recursive: true, force: true }); } };
  } catch {
    const removed = await cleanup(); rmSync(directory, { recursive: true, force: true });
    throw new Error(removed.status === 'PASSED' ? 'APPLICATION_EXPORT_FAILED' : 'APPLICATION_EXPORT_CLEANUP_FAILED');
  }
}
