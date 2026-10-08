import { createHash } from 'node:crypto';

const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };

/** Shared fail-closed envelope boundary; finding-specific proof remains separate. */
export function requireCompleteCurrentE4(e4, repository) {
  requireValue(e4 && /^[0-9a-f]{64}$/.test(e4.contentSha256 || ''), 'current E4 canonical digest required');
  const { contentSha256, ...payload } = e4;
  requireValue(createHash('sha256').update(canonical(payload)).digest('hex') === contentSha256,
    'current E4 canonical digest mismatch');
  requireValue(e4.schemaVersion === 'M6_PR_E_E4_SCANNER_EVIDENCE_V1'
    && e4.repository === repository && /^[0-9a-f]{40}$/.test(e4.commitSha || '')
    && /^[0-9a-f]{64}$/.test(e4.e2GraphDigest || '') && /^[0-9a-f]{64}$/.test(e4.e2CurrentContentSha256 || ''),
  'current E4 schema/repository/Head identity mismatch');
  requireValue(e4.allScannersCompleted === true && e4.rawScannerReportsRetained === false
    && e4.candidateSecretMaterialRetained === false && e4.authoritativeGitHubInventoryStillUnavailable === true
    && e4.workstreamReleaseBlocked === true, 'current E4 complete redacted release-blocked evidence required');
  const checkout = e4.checkout;
  requireValue(checkout && checkout.expectedHeadSha === e4.commitSha && checkout.exactTreeMatches === true
    && checkout.trackedWorktreeClean === true && /^[0-9a-f]{40}$/.test(checkout.checkedOutSha || '')
    && /^[0-9a-f]{40}$/.test(checkout.expectedHeadTreeSha || '')
    && checkout.checkedOutTreeSha === checkout.expectedHeadTreeSha, 'current E4 exact scanned checkout required');
  const sourceClasses = { osv: 'E4_OSV_SCANNER', gitleaks: 'E4_GITLEAKS', zizmor: 'E4_ZIZMOR', semgrep: 'E4_SEMGREP' };
  requireValue(canonical(Object.keys(e4.scanners || {}).sort()) === canonical(Object.keys(sourceClasses).sort()),
    'current E4 requires exactly four scanner inventories');
  const identities = new Set();
  for (const [name, sourceClass] of Object.entries(sourceClasses)) {
    const scanner = e4.scanners[name];
    requireValue(scanner?.scanCompleted === true && scanner.rawReportRetained === false
      && Array.isArray(scanner.findings) && scanner.findings.length === scanner.findingCount,
    `complete current E4 ${name} scanner evidence required`);
    for (const finding of scanner.findings) {
      const key = `${finding.sourceClass}:${finding.findingId}`;
      requireValue(finding.sourceClass === sourceClass && /^[0-9a-f]{64}$/.test(finding.findingId || '')
        && !identities.has(key), 'current E4 scanner identity drift');
      identities.add(key);
    }
  }
  requireValue(identities.size === e4.totalFindingCount, 'current E4 total mismatch');
  requireValue(e4.scanners.semgrep.sourceSnippetRetained === false
    && e4.scanners.gitleaks.candidateSecretMaterialRetained === false, 'current E4 scanner redaction required');
  return e4;
}
