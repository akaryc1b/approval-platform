// Three exact historical observations; never a path, prefix, rule or value allowlist.
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { bytesFromBase64, commitObject, treeEntries, verifyPath, objectHash } from './gitleaks-git-source-proof.mjs';
import { requireCompleteCurrentE4 } from './scanner-evidence-provenance.mjs';

export const HISTORICAL_SOURCE_REVIEW_BASE = '89b91b703cb6e51edae489bd0aeaad0dd8a907d8';
const BASE_TREE = '01e7a5c497664e1e2a3162f4052058b29456d15a';
const INVENTORY = '1bca2f9e5eefa0c0883ec6a44b3f5a466550c676335e0087cb188e00b077b8de';
const SOURCES = [
  { findingId: '04d58ffd3accfc0cc945cdaa34e1b9b92678d3a060e9e0f75f33c222b90a904f', commit: '445d1205bd611f6e3b9255737c5dd482dddebaa9',
    path: 'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalSlaExecutionIndexPlanIntegrationTest.java', line: 96,
    blobSha: '2d20fd8dd3e41826c42b6f1956d5062f2b97f953', sha256: '79d0d5489fa4e3c5eb62530148f2c231e439992488feac72be8505822dc4516b',
    lineSha256: 'fa3ee282f4b2ae2bf23fd09bc864e3f67e0a43914baab194513bbb9582ed265b', role: 'SQL_QUERY_PREDICATE_TEST_FIXTURE' },
  { findingId: '35546688230e94e02dd1dbba590bab1f463fd43d77659d5caf58a0592a7da81e', commit: 'a2b3da316fc5fb97bcb7394627c3aa9262ce37fb',
    path: 'contracts/sdk/v1/fixtures/adapter-binding-v1.json', line: 91,
    blobSha: 'd1518d53135f02b013d18e6b4a641c2de97f02c1', sha256: 'b45066e31f238eef8a6dbd81fd1a17df49e1e03dc3a86881e67ab36d78070d34',
    lineSha256: '9b411991a27c084ff45707ae84e01208544d60849c19569be53ce06375723bee', role: 'SDK_LEASE_REFERENCE_FIXTURE_METADATA' },
  { findingId: 'bbfccade1028572131367011f9d5c54538a1fb8f97d1414208c2d645a678d254', commit: '2a310553d4de552c064906ee1f57efb742e18162',
    path: 'docs/m6/M6_A_CONNECTOR_OPERATIONS_DIAGNOSTICS_EVIDENCE_CORRECTION.md', line: 18,
    blobSha: 'b4a0d73686437fe8b75dcc26e902523b0a69c82c', sha256: 'b563fb13e0d8f62319a8dabba9774886d41a3e1b9b53c16bff5ca7f7d25e1671',
    lineSha256: '3f2eb1e394427f87e489e28500aa1921b73b40cdd085751ceb5b5529f62562a5', role: 'DOCUMENTED_ANCESTRAL_GIT_COMMIT' },
];
const CONTRACT = { path: 'contracts/sdk/v1/ADAPTER_BINDING.md', blobSha: '96596c71fc555a17ee23f92fa848a5d08ffb4b38',
  sha256: '50fefd2f984b53daf0f295ad8ca36888d0e7a78d55a6d7137ffeb70ed20c74e4' };
const LAYER = 'E3-I2-APPEND-ONLY-GITLEAKS-HISTORICAL-SOURCE';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return stable({ ...payload, contentSha256: hash(canonical(payload)) }); };
const requireValue = (condition, message) => { if (!condition) throw new Error(`Gitleaks historical source ${message}`); };
const key = value => `${value.sourceClass}:${value.findingId}`;
export const historicalNonCredentialSources = () => structuredClone(SOURCES);

function pinnedSource(source) {
  const url = new URL(`../../${source.path}`, import.meta.url), stat = lstatSync(url);
  requireValue(stat.isFile() && !stat.isSymbolicLink(), 'regular source file required');
  const bytes = readFileSync(url), text = bytes.toString('utf8');
  requireValue(hash(bytes) === source.sha256 && objectHash('blob', bytes) === source.blobSha, 'pinned source bytes changed');
  if (source.line) requireValue(hash(text.split('\n')[source.line - 1] || '') === source.lineSha256, 'pinned source line changed');
  return text;
}
function sourceContext() {
  const [sql, lease, documentation] = SOURCES.map(pinnedSource), contract = pinnedSource(CONTRACT);
  requireValue(/^\s*assertPlan\("idempotency_key='[a-z0-9-]+'",$/.test(sql.split('\n')[95])
    && sql.includes('void idempotencyLookupUsesUniqueIndex()') && sql.includes('@Testcontainers')
    && sql.includes('POSTGRES.getJdbcUrl()') && sql.includes('assertPlan(String predicateAndOrder, String expectedIndex)'), 'SQL fixture context changed');
  const fixture = JSON.parse(lease), line = JSON.parse(`{${lease.split('\n')[90].trim().replace(/,$/, '')}}`);
  requireValue(Object.keys(line).length === 1 && line.credentialLeaseId === fixture.expectations.credentialLeaseId
    && fixture.expectations.credentialLeaseId === fixture.credentialLease.leaseId
    && contract.includes('No bearer value, private key, certificate bytes, header value or password enters the SDK contract.'), 'nominal lease context changed');
  const reference = documentation.split('\n')[17].match(/^- Token handoff correction: `([a-f0-9]{40})`$/);
  requireValue(reference, 'documented Git reference grammar changed');
  return { reference: reference[1] };
}
function findings(e4) {
  requireCompleteCurrentE4(e4, 'akaryc1b/approval-platform');
  requireValue(e4.scanners.gitleaks.findingCount === 554
    && hash(canonical(e4.scanners.gitleaks.findings)) === INVENTORY, 'complete unchanged 554 observation inventory required');
  return SOURCES.map(source => {
    const finding = e4.scanners.gitleaks.findings.find(row => row.findingId === source.findingId);
    const fingerprint = `${source.commit}:${source.path}:generic-api-key:${source.line}`;
    requireValue(finding && finding.sourceClass === 'E4_GITLEAKS' && finding.commit === source.commit && finding.path === source.path
      && finding.ruleId === 'generic-api-key' && finding.startLine === source.line && finding.endLine === source.line
      && finding.fingerprint === fingerprint
      && finding.findingId === hash(['GITLEAKS', fingerprint, finding.ruleId, source.path, String(source.line)].join('\0')), 'exact finding identity changed');
    return finding;
  });
}
function chain(rows, first, last) {
  requireValue(Array.isArray(rows) && rows.length > 0 && rows.length <= 4096, 'bounded ancestry required');
  const parsed = rows.map(commitObject);
  requireValue(parsed[0].sha === first && parsed.at(-1).sha === last && new Set(parsed.map(row => row.sha)).size === parsed.length, 'ancestry endpoints changed');
  for (let i = 0; i < parsed.length - 1; i++) requireValue(parsed[i].parents.includes(parsed[i + 1].sha), 'ancestry parent changed');
  return parsed;
}

export function verifyGitleaksHistoricalSourceReview(e4, snapshot) {
  const reviewed = findings(e4), context = sourceContext(), proof = snapshot?.proof;
  requireValue(snapshot?.repository === e4.repository && snapshot.commitSha === e4.commitSha
    && snapshot.currentE4CanonicalSha256 === e4.contentSha256, 'exact E4 binding required');
  requireValue(proof && canonical(Object.keys(proof).sort()) === canonical(['contract', 'currentCommits', 'referenceCommits', 'sources']), 'proof shape changed');
  const current = chain(proof.currentCommits, e4.commitSha, HISTORICAL_SOURCE_REVIEW_BASE);
  requireValue(current[0].tree === e4.checkout.expectedHeadTreeSha && current.at(-1).tree === BASE_TREE, 'current/base tree changed');
  requireValue(Array.isArray(proof.sources) && proof.sources.length === 3, 'three source proofs required');
  const verifySource = (row, source, historicalTree) => {
    requireValue(row && canonical(Object.keys(row).sort()) === canonical(['acceptedPath', 'currentPath', 'historicalPath']), 'source path proof shape changed');
    verifyPath(row.historicalPath, historicalTree, source.path, source.blobSha);
    verifyPath(row.acceptedPath, BASE_TREE, source.path, source.blobSha);
    verifyPath(row.currentPath, current[0].tree, source.path, source.blobSha);
  };
  const historical = SOURCES.map((source, index) => {
    const row = proof.sources[index];
    requireValue(row && canonical(Object.keys(row).sort()) === canonical(['commits', 'findingId', 'paths']) && row.findingId === source.findingId, 'source identity/order changed');
    const commits = chain(row.commits, HISTORICAL_SOURCE_REVIEW_BASE, source.commit);
    verifySource(row.paths, source, commits.at(-1).tree); return commits.at(-1);
  });
  verifySource(proof.contract, CONTRACT, historical[1].tree);
  chain(proof.referenceCommits, SOURCES[2].commit, context.reference);
  return seal({ schemaVersion: 'M6_PR_E_E3_GITLEAKS_HISTORICAL_SOURCE_REVIEW_V1', repository: e4.repository,
    commitSha: e4.commitSha, sourceE4CanonicalSha256: e4.contentSha256, reviewBasisCommit: HISTORICAL_SOURCE_REVIEW_BASE,
    reviewBasisTree: BASE_TREE, retainedFindingCount: 554, retainedFindingsSha256: INVENTORY,
    findings: reviewed, sources: SOURCES, supportingContract: CONTRACT, proof,
    decision: { disposition: 'NOT_APPLICABLE', evidenceCode: 'EXACT_PINNED_NON_CREDENTIAL_SQL_LEASE_AND_GIT_CONTEXT', exploitPreconditionsSatisfied: false },
    releaseBlocked: true, rawCandidateMaterialRetained: false, rawSourceBlobsRetained: false });
}
export function requireGitleaksHistoricalSourceReviewReceipt(e4, evidence) {
  const verified = verifyGitleaksHistoricalSourceReview(e4, { repository: evidence?.repository, commitSha: evidence?.commitSha,
    currentE4CanonicalSha256: evidence?.sourceE4CanonicalSha256, proof: evidence?.proof });
  requireValue(canonical(evidence) === canonical(verified), 'receipt changed'); return verified;
}
function decisionReview(evidence, source) {
  return { reviewLayer: LAYER, reviewCanonicalSha256: evidence.contentSha256, sourceCommitSha: source.commit,
    sourcePath: source.path, sourceBlobSha: source.blobSha, sourceLineSha256: source.lineSha256,
    metadataRole: source.role, evidenceCode: evidence.decision.evidenceCode, exploitPreconditionsSatisfied: false };
}
export function historicalSourceDecisions(decisions, evidence, apply = false) {
  requireValue(canonical(evidence.sources) === canonical(SOURCES) && evidence.findings.length === 3, 'exact reviewed source scope required');
  const sources = new Map(SOURCES.map(source => [source.findingId, source])), seen = new Set();
  const result = decisions.map(decision => {
    const source = sources.get(decision.findingId); if (!source) return decision;
    const finding = evidence.findings.find(row => row.findingId === source.findingId);
    requireValue(!seen.has(key(decision)) && key(decision) === key(finding) && decision.severityBand === 'UNKNOWN'
      && ['ruleId', 'commit', 'fingerprint', 'path', 'startLine', 'endLine'].every(field => decision.sourceIdentity?.[field] === finding[field]), 'decision identity changed');
    seen.add(key(decision));
    const reviewed = { ...decision, disposition: 'NOT_APPLICABLE', exploitPreconditions: ['ACTUAL_CREDENTIAL_MATERIAL_PRESENT'], reviewEvidence: decisionReview(evidence, source) };
    if (apply) requireValue(decision.disposition === 'UNRESOLVED' && !decision.reviewEvidence, 'only exact unresolved findings may be reviewed');
    else requireValue(canonical(decision) === canonical(reviewed), 'decision review changed');
    return stable(reviewed);
  });
  requireValue(seen.size === 3, 'exactly three current decisions required');
  requireValue(result.filter(row => row.reviewEvidence?.reviewLayer === LAYER).length === 3, 'unexpected historical source review');
  return result;
}

/** Read-only Git-object collector. No blob payload, source mutation or scanner configuration. */
export function buildGitleaksHistoricalSourceReviewSnapshot(e4, { root, git = spawnSync } = {}) {
  findings(e4); const context = sourceContext(), cache = new Map();
  const read = (type, sha) => {
    requireValue(/^[a-f0-9]{40}$/.test(sha || '') && ['commit', 'tree'].includes(type), 'Git object identity required');
    const id = `${type}:${sha}`;
    if (!cache.has(id)) {
      const result = git('git', ['cat-file', type, sha], { cwd: root, timeout: 10000, maxBuffer: 32 * 1024 * 1024 });
      requireValue(result.status === 0, `Git ${type} read failed`);
      cache.set(id, { sha, contentBase64: Buffer.from(result.stdout).toString('base64') });
    }
    return cache.get(id);
  };
  const ancestry = (head, target) => {
    const previous = new Map([[head, null]]), queue = [head]; let found = false;
    while (queue.length && previous.size <= 4096) {
      const next = queue.shift(), parsed = commitObject(read('commit', next));
      if (next === target) { found = true; break; }
      for (const parent of parsed.parents) if (!previous.has(parent)) { previous.set(parent, next); queue.push(parent); }
    }
    requireValue(found, 'required historical commit absent from current ancestry');
    const rows = []; for (let next = target; next !== null; next = previous.get(next)) rows.unshift(read('commit', next)); return rows;
  };
  const pathProof = (tree, path) => {
    const rows = []; let next = tree;
    for (const part of path.split('/')) {
      const row = read('tree', next); rows.push(row);
      const entry = treeEntries(bytesFromBase64(row.contentBase64)).find(item => item.name === part);
      if (!entry || entry.mode !== '40000') break; next = entry.sha;
    }
    return rows;
  };
  const currentCommits = ancestry(e4.commitSha, HISTORICAL_SOURCE_REVIEW_BASE), currentTree = commitObject(currentCommits[0]).tree;
  const paths = (source, historicalTree) => ({ historicalPath: pathProof(historicalTree, source.path),
    acceptedPath: pathProof(BASE_TREE, source.path), currentPath: pathProof(currentTree, source.path) });
  const sources = SOURCES.map(source => {
    const commits = ancestry(HISTORICAL_SOURCE_REVIEW_BASE, source.commit);
    return { findingId: source.findingId, commits, paths: paths(source, commitObject(commits.at(-1)).tree) };
  });
  return { repository: e4.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    proof: { currentCommits, sources, contract: paths(CONTRACT, commitObject(sources[1].commits.at(-1)).tree),
      referenceCommits: ancestry(SOURCES[2].commit, context.reference) } };
}
