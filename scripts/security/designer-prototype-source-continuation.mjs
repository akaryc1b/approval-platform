// Exact reviewed proxy-safe JSON clone continuation; no finding disposition or scan claims.
import { createHash } from 'node:crypto';
import { readFileSync, lstatSync } from 'node:fs';
const hash = value => createHash('sha256').update(value).digest('hex');
const blob = value => createHash('sha1').update(`blob ${Buffer.byteLength(value)}\0`).update(value).digest('hex');
const requireValue = (value, message) => { if (!value) throw new Error(`prototype source continuation ${message}`); };
export const PROTOTYPE_SOURCE_PATH = "apps/web/overlay/apps/web-ele/src/views/approval/designer/designer-conflict.ts";
export const PRIOR_PROTOTYPE_PLAN = '53143fa727b6eafaae41319e6a2a4d159d14ee2557cd46cb650b895bbb7ee3ac';
const ARTIFACTS = {
  "apps/web/overlay/apps/web-ele/src/views/approval/designer/designer-conflict.ts": {
    "blobSha": "f29e6ef19f6c7a8a1e7224a647944a59844bdf7c",
    "sha256": "f4a2e6fbd9e801c82a8b1bcbd11ba627a6aec32d82248e674326b1360f23118d"
  },
  "apps/web/overlay/apps/web-ele/src/views/approval/designer/use-approval-designer.ts": {
    "blobSha": "275754a4a8781909e3cf752b682ceff863726d97",
    "sha256": "3f949dfeffa3e4311cca817412749842158e4bf16e8ee1bad99d5914ca0e2e79"
  },
  "scripts/tests/approval-designer-conflict.test.mjs": {
    "blobSha": "be2cf23c0292137d6bc6824039b620a83339d8f5",
    "sha256": "ce86a2894bba68d9554bc6ee282807e0c25830a8e22cdc67af40256dc33a6d8c"
  },
  "scripts/tests/approval-designer-save-lifecycle.test.mjs": {
    "blobSha": "573f575ff90103e6711cc3d6baa81dbc2df1201f",
    "sha256": "e0b454af7b1ab8135a5d6fb3ad7febeb6c821ea386a34f9d37f7783c93786110"
  },
  "scripts/tests/approval-designer-modal-state.test.mjs": {
    "blobSha": "f3daae4ef7abf14dbc4df4d1921f46aa23c0ba89",
    "sha256": "852871e906ff15d17c47ffe86d710b4daa5625f7d5aafbb43e22dfd34d0ce54f"
  },
  "package.json": {
    "blobSha": "8b3fc37b041e0e7fa666fd94fd8c54970370151f",
    "sha256": "18260ca9985c6e591a592d05c59e0163cdf802dd3e4b3c3399ad0586aa5fd570"
  }
};
const CLONE_HELPER = "/** Clone Approval DSL / editor DTOs through their JSON wire representation.\n * Reactive proxies cannot be passed to structuredClone. Keep the existing\n * dangerous-property rejection both before and after JSON serialization.\n */\nexport function cloneApprovalDesignerJson<T>(value: T): T {\n  assertSafeDesignerValue(value);\n  if (value === undefined) return value;\n  const result = JSON.parse(JSON.stringify(value)) as T;\n  assertSafeDesignerValue(result);\n  return result;\n}\n\n";
function replaceOnce(source, before, after) {
  requireValue(source.split(before).length === 2, 'exact clone substitution required');
  return source.replace(before, after);
}
export function verifyPrototypeCloneContinuation(source) {
  requireValue(typeof source === 'string' && hash(source) === ARTIFACTS[PROTOTYPE_SOURCE_PATH].sha256
    && blob(source) === ARTIFACTS[PROTOTYPE_SOURCE_PATH].blobSha, 'unreviewed current clone source');
  let prior = replaceOnce(source, CLONE_HELPER, '');
  prior = replaceOnce(prior, 'definition: cloneApprovalDesignerJson(draft.definition)', 'definition: structuredClone(draft.definition)');
  prior = replaceOnce(prior, 'const merged = cloneApprovalDesignerJson(server)', 'const merged = structuredClone(server)');
  prior = replaceOnce(prior, 'return cloneApprovalDesignerJson(value);', 'return value === undefined ? undefined : structuredClone(value);');
  requireValue(blob(prior) === 'dd7c59dd73a86ec9687cd0033a2d1e311a7bbcc6'
    && hash(prior) === '7fa9810c70e8b946f62e019319ce718723e666307bd928af4b91a08853b12473', 'accepted predecessor mismatch');
  return prior;
}
export function readDesignerPrototypeSourceContinuation() {
  for (const [path, expected] of Object.entries(ARTIFACTS)) {
    const file = new URL(`../../${path}`, import.meta.url);
    requireValue(lstatSync(file).isFile() && !lstatSync(file).isSymbolicLink(), 'regular current artifact required');
    const content = readFileSync(file, 'utf8');
    requireValue(hash(content) === expected.sha256 && blob(content) === expected.blobSha, `artifact byte drift ${path}`);
    if (path === PROTOTYPE_SOURCE_PATH) verifyPrototypeCloneContinuation(content);
  }
  return { schemaVersion: 'APPROVAL_DESIGNER_PROXY_CLONE_SOURCE_CONTINUATION_V1',
    priorPlanCanonicalSha256: PRIOR_PROTOTYPE_PLAN,
    priorSourceBlobSha: 'dd7c59dd73a86ec9687cd0033a2d1e311a7bbcc6',
    helperInsertionCount: 1, cloneCallSubstitutionCount: 3,
    reviewedArtifacts: structuredClone(ARTIFACTS),
    historicalReviewUnchanged: true, scannerExecutionClaimed: false,
    findingAbsenceClaimed: false, releaseBlocked: true };
}
