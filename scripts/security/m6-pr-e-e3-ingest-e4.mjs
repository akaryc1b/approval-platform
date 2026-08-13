#!/usr/bin/env node
import { createHash } from 'node:crypto';

const SOURCE_ORDER = Object.freeze(['osv', 'gitleaks', 'zizmor', 'semgrep']);
const SHA40 = /^[0-9a-f]{40}$/;
const SHA64 = /^[0-9a-f]{64}$/;
const HIGH_RISK_IMPACTS = new Set([
  'TENANT_ISOLATION', 'AUTHORIZATION', 'SECRET', 'RCE', 'INJECTION',
  'DESERIALIZATION', 'SSRF', 'WORKFLOW_SUPPLY_CHAIN', 'EVIDENCE_INTEGRITY',
]);

const stable = (value) => Array.isArray(value)
  ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
    : value;
const canonical = (value) => JSON.stringify(stable(value));
const sha256 = (value) => createHash('sha256').update(value).digest('hex');

function requireText(value, name) {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${name} is required`);
  return value;
}

function safePath(value) {
  return String(value || '').replace(/^\/src\//, '');
}

function rawSeverity(finding) {
  if (Array.isArray(finding.upstreamSeverity)) {
    return finding.upstreamSeverity.length
      ? canonical(finding.upstreamSeverity)
      : 'UPSTREAM_SEVERITY_NOT_PROVIDED_BY_OSV';
  }
  if (finding.upstreamSeverity != null && String(finding.upstreamSeverity).trim()) {
    return String(finding.upstreamSeverity);
  }
  return `${finding.sourceClass}_UPSTREAM_SEVERITY_NOT_PROVIDED`;
}

function sourceIdentity(finding) {
  if (finding.sourceClass === 'E4_OSV_SCANNER') {
    return {
      advisoryId: String(finding.upstreamFindingId || ''),
      aliases: [...(finding.aliases || [])].sort(),
      package: finding.package || null,
      fixedVersions: [...(finding.fixedVersions || [])].sort(),
    };
  }
  if (finding.sourceClass === 'E4_GITLEAKS') {
    return {
      ruleId: String(finding.ruleId || ''),
      commit: String(finding.commit || ''),
      fingerprint: String(finding.fingerprint || ''),
      path: String(finding.path || ''),
      startLine: finding.startLine ?? null,
      endLine: finding.endLine ?? null,
    };
  }
  if (finding.sourceClass === 'E4_ZIZMOR') {
    return {
      ruleId: String(finding.ruleId || ''),
      path: String(finding.path || ''),
      startLine: finding.startLine ?? null,
      startColumn: finding.startColumn ?? null,
      endLine: finding.endLine ?? null,
      endColumn: finding.endColumn ?? null,
    };
  }
  if (finding.sourceClass === 'E4_SEMGREP') {
    return {
      ruleId: String(finding.ruleId || ''),
      path: safePath(finding.path),
      startLine: finding.startLine ?? null,
      startColumn: finding.startColumn ?? null,
      endLine: finding.endLine ?? null,
      endColumn: finding.endColumn ?? null,
      cwe: [...(finding.cwe || [])].sort(),
      owasp: [...(finding.owasp || [])].sort(),
      category: finding.category ?? null,
    };
  }
  throw new Error(`unsupported E4 sourceClass ${finding.sourceClass}`);
}

function component(finding) {
  if (finding.sourceClass === 'E4_OSV_SCANNER') {
    const refs = [...(finding.componentRefs || [])].sort();
    if (refs.length === 0) throw new Error(`OSV finding ${finding.findingId} has no E2 componentRef`);
    const preferred = refs.find((ref) => ref.startsWith('pkg:maven/')) || refs[0];
    return {
      ecosystem: 'Maven',
      componentRef: preferred,
      alternateComponentRefs: refs.filter((ref) => ref !== preferred),
      dependencyPath: [preferred],
      dependencyPathComplete: false,
      dependencyPathEvidence: 'E4 binds the exact E2 component reference; root-to-component dependency path remains an explicit E3 triage requirement.',
      scope: [...(finding.scopes || [])].sort().join('+') || 'UNKNOWN',
      owner: 'Java Dependency Owner',
      reviewer: 'Application Security Reviewer',
      impacts: [],
    };
  }
  if (finding.sourceClass === 'E4_GITLEAKS') {
    const ref = `git-history:${finding.commit}:${finding.path}:${finding.startLine ?? 0}`;
    return {
      ecosystem: 'Repository history',
      componentRef: ref,
      alternateComponentRefs: [],
      dependencyPath: [ref],
      dependencyPathComplete: true,
      dependencyPathEvidence: 'Gitleaks finding is bound to exact historical commit, repository path and line metadata; raw candidate Secret is intentionally not retained.',
      scope: 'repository-history/secret',
      owner: 'Security Triage Owner',
      reviewer: 'Application Security Reviewer',
      impacts: ['SECRET'],
    };
  }
  if (finding.sourceClass === 'E4_ZIZMOR') {
    const ref = `workflow:${finding.path}:${finding.ruleId}:${finding.startLine ?? 0}`;
    return {
      ecosystem: 'GitHub Actions',
      componentRef: ref,
      alternateComponentRefs: [],
      dependencyPath: [ref],
      dependencyPathComplete: true,
      dependencyPathEvidence: 'zizmor finding is bound to exact current-tree workflow path, rule and source location.',
      scope: 'github-actions/workflow-supply-chain',
      owner: 'Workflow Supply-Chain Owner',
      reviewer: 'Application Security Reviewer',
      impacts: ['WORKFLOW_SUPPLY_CHAIN'],
    };
  }
  if (finding.sourceClass === 'E4_SEMGREP') {
    const path = safePath(finding.path);
    const ref = `source:${path}:${finding.ruleId}:${finding.startLine ?? 0}`;
    const scope = path.startsWith('server-modules/')
      ? 'server/source-code'
      : path.startsWith('apps/web/')
        ? 'client/source-code'
        : path.startsWith('scripts/')
          ? 'build-evidence/source-code'
          : 'repository/source-code';
    return {
      ecosystem: 'Repository source',
      componentRef: ref,
      alternateComponentRefs: [],
      dependencyPath: [ref],
      dependencyPathComplete: true,
      dependencyPathEvidence: 'Semgrep finding is bound to exact current-tree source path, rule and source location.',
      scope,
      owner: 'Application Security Reviewer',
      reviewer: 'Security Triage Owner',
      impacts: [],
    };
  }
  throw new Error(`unsupported E4 sourceClass ${finding.sourceClass}`);
}

function unresolvedReachability(finding) {
  const prefix = `${finding.sourceClass}:${finding.findingId}`;
  return {
    packaged: {
      value: null,
      evidence: `${prefix} intake does not infer packaging from scanner presence; positive build/deployment evidence is required.`,
    },
    loaded: {
      value: null,
      evidence: `${prefix} intake does not infer runtime/workflow loading; positive runtime or GitHub execution evidence is required.`,
    },
    invoked: {
      value: null,
      evidence: `${prefix} intake does not infer invocation from version, rule or source match; bounded invocation evidence is required.`,
    },
    externallyReachable: {
      value: null,
      evidence: `${prefix} intake does not infer external reachability; endpoint/trigger/deployment evidence is required.`,
    },
  };
}

function convertFinding(finding, snapshotTime) {
  requireText(finding.findingId, 'finding.findingId');
  requireText(finding.sourceClass, 'finding.sourceClass');
  const c = component(finding);
  return stable({
    findingId: finding.findingId,
    sourceClass: finding.sourceClass,
    sourceIdentity: sourceIdentity(finding),
    upstreamSeverity: rawSeverity(finding),
    severityBand: 'UNKNOWN',
    severityBandEvidence: 'Machine intake preserves raw scanner severity only; no CVSS/rule label is rewritten into a normalized band before Application Security review.',
    ecosystem: c.ecosystem,
    componentRef: c.componentRef,
    alternateComponentRefs: c.alternateComponentRefs,
    dependencyPath: c.dependencyPath,
    dependencyPathComplete: c.dependencyPathComplete,
    dependencyPathEvidence: c.dependencyPathEvidence,
    scope: c.scope,
    deploymentScopeEvidence: 'E4 proves scanner coverage at the exact PR Head; deployment applicability remains unresolved until E3 positive evidence is attached.',
    reachability: unresolvedReachability(finding),
    exploitPreconditions: [],
    impacts: c.impacts,
    mitigations: [],
    owner: c.owner,
    reviewer: c.reviewer,
    decisionTime: snapshotTime,
    decisionTimeEvidence: 'Deterministic exact-Head intake snapshot time; human applicability/reachability review remains pending.',
    disposition: 'UNRESOLVED',
  });
}

export function buildScannerFindingIntake(e4, { snapshotTime } = {}) {
  if (!e4 || typeof e4 !== 'object') throw new Error('E4 canonical evidence is required');
  if (!SHA40.test(e4.commitSha || '')) throw new Error('E4 commitSha must be exact SHA');
  if (!SHA64.test(e4.contentSha256 || '')) throw new Error('E4 contentSha256 must be SHA-256');
  if (!SHA64.test(e4.e2GraphDigest || '')) throw new Error('E4 e2GraphDigest must be SHA-256');
  if (e4.allScannersCompleted !== true) throw new Error('all four E4 scanners must complete before E3 intake');
  if (e4.rawScannerReportsRetained !== false) throw new Error('raw scanner reports must not be retained');
  if (e4.candidateSecretMaterialRetained !== false) throw new Error('candidate Secret material must not be retained');
  if (!Number.isFinite(Date.parse(snapshotTime || ''))) throw new Error('snapshotTime must be an instant');

  const findings = [];
  const sourceCounts = {};
  for (const key of SOURCE_ORDER) {
    const scanner = e4.scanners?.[key];
    if (!scanner || scanner.scanCompleted !== true || scanner.rawReportRetained !== false) {
      throw new Error(`E4 ${key} scanner evidence incomplete`);
    }
    if (!Array.isArray(scanner.findings) || scanner.findings.length !== scanner.findingCount) {
      throw new Error(`E4 ${key} finding count mismatch`);
    }
    sourceCounts[key] = scanner.findingCount;
    findings.push(...scanner.findings);
  }
  if (findings.length !== e4.totalFindingCount) throw new Error('E4 totalFindingCount mismatch');
  const identities = new Set();
  for (const finding of findings) {
    const key = `${finding.sourceClass}:${finding.findingId}`;
    if (identities.has(key)) throw new Error(`duplicate E4 finding identity ${key}`);
    identities.add(key);
  }

  const decisions = findings.map((finding) => convertFinding(finding, snapshotTime));
  const highRiskUnresolvedCount = decisions.filter((finding) =>
    finding.impacts.some((impact) => HIGH_RISK_IMPACTS.has(impact)),
  ).length;

  const payload = stable({
    schemaVersion: 'M6_PR_E_E3_SCANNER_INTAKE_V1',
    repository: e4.repository,
    commitSha: e4.commitSha,
    sourceE4ContentSha256: e4.contentSha256,
    e2GraphDigest: e4.e2GraphDigest,
    sourceReadiness: {
      authoritativeComplete: false,
      scannerCoverageComplete: true,
      authoritativeReason: 'AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE',
      scannerSourceCounts: sourceCounts,
    },
    findingInventory: {
      complete: true,
      itemCount: e4.totalFindingCount,
      knownFindingCount: decisions.length,
    },
    decisions,
    summary: {
      knownDecisionCount: decisions.length,
      unresolvedCount: decisions.filter((finding) => finding.disposition === 'UNRESOLVED').length,
      highRiskUnresolvedCount,
      releaseBlocked: true,
      reasonCodes: [
        'AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE',
        'E3_SCANNER_FINDINGS_UNRESOLVED',
      ],
    },
  });
  return stable({ ...payload, contentSha256: sha256(canonical(payload)) });
}

export const canonicalScannerIntake = canonical;
