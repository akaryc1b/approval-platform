import { createHash } from 'node:crypto';
import path from 'node:path';

export const DESIGNER_PROTOTYPE_TARGET = '/src/apps/web/overlay/apps/web-ele/src/views/approval/designer/designer-conflict.ts';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const hash = value => createHash('sha256').update(value).digest('hex');
const canonical = value => JSON.stringify(stable(value));
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
export const canonicalSemgrepPath = value => {
  requireValue(typeof value === 'string' && value.length > 0 && !value.includes('\0'), 'Semgrep path must be nonempty text');
  return path.posix.resolve('/src', value);
};

/** Validate the real JSON report before normalization. Retain no raw snippets. */
export function normalizeSemgrepReport(raw, expectedVersion) {
  requireValue(raw && Array.isArray(raw.results) && Array.isArray(raw.errors), 'Semgrep results/errors arrays required');
  requireValue(raw.errors.length === 0, `Semgrep returned ${raw.errors.length} scan errors`);
  requireValue(raw.version === expectedVersion, 'Semgrep report version mismatch');
  requireValue(raw.paths && Array.isArray(raw.paths.scanned), 'Semgrep scanned paths array required');
  const scanned = raw.paths.scanned.map(canonicalSemgrepPath).sort();
  requireValue(new Set(scanned).size === scanned.length, 'Semgrep duplicate scanned path');
  const skippedReported = Object.hasOwn(raw.paths, 'skipped');
  requireValue(!skippedReported || Array.isArray(raw.paths.skipped), 'Semgrep skipped paths must be an array when reported');
  const skipped = (raw.paths.skipped || []).map(item => {
    requireValue(item && typeof item === 'object' && typeof item.reason === 'string'
      && item.reason.length > 0, 'Semgrep skipped path metadata required');
    return { path: canonicalSemgrepPath(item.path), reason: item.reason };
  }).sort((left, right) => canonical(left).localeCompare(canonical(right)));
  requireValue(scanned.includes(DESIGNER_PROTOTYPE_TARGET), 'Semgrep required designer target absent from scanned paths');
  requireValue(!skipped.some(item => item.path === DESIGNER_PROTOTYPE_TARGET), 'Semgrep required designer target explicitly skipped');
  const findings = raw.results.map(result => {
    requireValue(result && typeof result.check_id === 'string' && result.check_id.length > 0,
      'Semgrep result rule identity required');
    requireValue(scanned.includes(canonicalSemgrepPath(result.path)), 'Semgrep finding path absent from scanned paths');
    for (const location of [result.start, result.end]) {
      requireValue(location && Number.isSafeInteger(location.line) && location.line > 0
        && Number.isSafeInteger(location.col) && location.col > 0, 'Semgrep result location required');
    }
    requireValue(result.extra && typeof result.extra.severity === 'string' && result.extra.severity.length > 0
      && result.extra.metadata && typeof result.extra.metadata === 'object' && !Array.isArray(result.extra.metadata),
    'Semgrep result severity/metadata required');
    const metadata = result.extra.metadata;
    const safe = value => Array.isArray(value) ? value.map(String).sort() : value == null ? [] : [String(value)];
    return { findingId: hash(['SEMGREP', result.check_id, result.path, String(result.start.line), String(result.start.col)].join('\0')),
      sourceClass: 'E4_SEMGREP', ruleId: result.check_id, upstreamSeverity: result.extra.severity,
      path: result.path, startLine: result.start.line, startColumn: result.start.col,
      endLine: result.end.line, endColumn: result.end.col, cwe: safe(metadata.cwe), owasp: safe(metadata.owasp),
      category: metadata.category ? String(metadata.category) : null };
  }).sort((left, right) => left.findingId.localeCompare(right.findingId));
  const payload = stable({ schemaVersion: 'M6_PR_E_E4_SEMGREP_TARGET_COVERAGE_V1',
    requiredTarget: DESIGNER_PROTOTYPE_TARGET, requiredTargetScanned: true, requiredTargetExplicitlySkipped: false,
    scannedPathCount: scanned.length, scannedPathsSha256: hash(canonical(scanned)),
    skippedPathInventoryReported: skippedReported, reportedSkippedPathCount: skipped.length,
    reportedSkippedPathsSha256: hash(canonical(skipped)),
    rawPathsCanonicalSha256: hash(canonical(raw.paths)),
    rawReportRetained: false, sourceSnippetRetained: false });
  return { findings, coverage: stable({ ...payload, contentSha256: hash(canonical(payload)) }) };
}
