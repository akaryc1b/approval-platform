#!/usr/bin/env node
import { createHash } from 'node:crypto';

const SHA40 = /^[0-9a-f]{40}$/;
const SHA64 = /^[0-9a-f]{64}$/;
const WORKFLOW_PATH = /^\.github\/workflows\/[^/]+\.ya?ml$/;
const stable = (value) => Array.isArray(value)
  ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map((key) => [key, stable(value[key])]))
    : value;
const canonical = (value) => JSON.stringify(stable(value));
const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const gitBlobSha = (value) => createHash('sha1').update(`blob ${Buffer.byteLength(value)}\0`).update(value).digest('hex');
const findingKey = (finding) => `${finding.sourceClass}:${finding.findingId}`;

function indent(line) {
  return line.match(/^\s*/)[0].length;
}

function workflowSteps(source) {
  const lines = source.split(/\r?\n/);
  const steps = [];
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    const start = line.match(/^(\s*)-\s+(name|uses|run|id|shell|if|working-directory):\s*(.*)$/);
    if (!start) continue;
    const stepIndent = start[1].length;
    const fields = new Map([[start[2], start[3]]]);
    const raw = [line];
    let cursor = index + 1;
    while (cursor < lines.length) {
      const candidate = lines[cursor];
      if (candidate.trim() && indent(candidate) <= stepIndent) break;
      raw.push(candidate);
      const field = candidate.match(new RegExp(`^\\s{${stepIndent + 2}}([A-Za-z0-9_-]+):\\s*(.*)$`));
      if (field) fields.set(field[1], field[2]);
      cursor += 1;
    }
    steps.push({
      line: index + 1,
      indent: stepIndent,
      fields,
      raw: raw.join('\n'),
    });
    index = cursor - 1;
  }
  return steps;
}

function actionUse(step) {
  const match = step.fields.get('uses')?.match(/^([^@\s]+)@([0-9a-f]{40})\s+#\s+(\S+)\s*$/);
  if (!match) return null;
  return { repository: match[1], sha: match[2], versionComment: match[3] };
}

function checkoutSettings(step) {
  const settings = {};
  const lines = step.raw.split(/\r?\n/);
  const withLine = lines.findIndex((line) => /^\s*with:\s*$/.test(line));
  if (withLine < 0) return settings;
  const withIndent = indent(lines[withLine]);
  for (const line of lines.slice(withLine + 1)) {
    if (line.trim() && indent(line) <= withIndent) break;
    const match = line.match(/^\s*([A-Za-z0-9_-]+):\s*(.*?)\s*$/);
    if (match) settings[match[1]] = match[2];
  }
  return settings;
}

function physicalJobCount(source) {
  const lines = source.split(/\r?\n/);
  const jobsLine = lines.findIndex((line) => /^jobs:\s*$/.test(line));
  if (jobsLine < 0) throw new Error('workflow top-level jobs block required');
  const starts = [];
  for (let index = jobsLine + 1; index < lines.length; index += 1) {
    if (/^  [A-Za-z0-9_-]+:\s*$/.test(lines[index])) starts.push(index);
  }
  if (!starts.length) throw new Error('workflow logical jobs required');
  let physical = 0;
  for (let blockIndex = 0; blockIndex < starts.length; blockIndex += 1) {
    const start = starts[blockIndex];
    const end = starts[blockIndex + 1] ?? lines.length;
    const block = lines.slice(start, end);
    const matrixLine = block.findIndex((line) => /^      matrix:\s*$/.test(line));
    if (matrixLine < 0) {
      physical += 1;
      continue;
    }
    const dimensions = [];
    for (let index = matrixLine + 1; index < block.length; index += 1) {
      if (block[index].trim() && indent(block[index]) <= 6) break;
      const dimension = block[index].match(/^        ([A-Za-z0-9_-]+):\s*$/);
      if (!dimension) continue;
      let values = 0;
      for (let cursor = index + 1; cursor < block.length; cursor += 1) {
        if (block[cursor].trim() && indent(block[cursor]) <= 8) break;
        if (/^          -\s+/.test(block[cursor])) values += 1;
      }
      if (!values) throw new Error(`workflow matrix dimension must use explicit values ${dimension[1]}`);
      dimensions.push(values);
    }
    if (!dimensions.length) throw new Error('workflow matrix dimensions required');
    physical += dimensions.reduce((product, count) => product * count, 1);
  }
  return physical;
}

function topLevelOnBlock(source) {
  const lines = source.split(/\r?\n/);
  const start = lines.findIndex((line) => /^on:\s*$/.test(line));
  if (start < 0) throw new Error('workflow top-level on block required');
  const block = [];
  for (const line of lines.slice(start + 1)) {
    if (line.trim() && indent(line) === 0) break;
    block.push(line);
  }
  return block.join('\n');
}

function inspectWorkflow(path, source, actionPins) {
  if (!WORKFLOW_PATH.test(path)) throw new Error(`invalid workflow path ${path}`);
  const steps = workflowSteps(source);
  const uses = [];
  const checkout = [];
  for (const step of steps) {
    if (!step.fields.has('uses')) continue;
    const parsed = actionUse(step);
    if (!parsed) throw new Error(`workflow action ref must be reviewed immutable SHA with version comment ${path}:${step.line}`);
    const expected = actionPins[parsed.repository];
    if (!expected) throw new Error(`unreviewed external action repository ${parsed.repository} at ${path}:${step.line}`);
    if (parsed.sha !== expected.reviewedImmutableSha) throw new Error(`reviewed action SHA mismatch ${parsed.repository} at ${path}:${step.line}`);
    if (parsed.versionComment !== expected.versionComment) throw new Error(`action version comment mismatch ${parsed.repository} at ${path}:${step.line}`);
    uses.push({ path, line: step.line, ...parsed });
    if (parsed.repository === 'actions/checkout') {
      const settings = checkoutSettings(step);
      if (settings['persist-credentials'] !== 'false') throw new Error(`checkout persist-credentials false required ${path}:${step.line}`);
      checkout.push({ path, line: step.line, settings: stable(settings) });
    }
  }

  if (/pull_request_target\s*:/.test(source)) throw new Error(`pull_request_target prohibited ${path}`);
  if (/^\s*permissions:\s*write-all\s*$/m.test(source) || /^\s*[A-Za-z-]+:\s*write\s*$/m.test(source)) {
    throw new Error(`workflow permission widening prohibited ${path}`);
  }

  const onBlock = topLevelOnBlock(source);
  const automatic = /^\s*(pull_request|push):\s*$/m.test(onBlock);
  return { path, automatic, uses, checkout, steps };
}

function collectHistoricalFindings(plan) {
  const allowed = new Set(['zizmor/unpinned-uses', 'zizmor/artipacked', 'zizmor/template-injection']);
  const findings = [];
  for (const [ruleId, rows] of Object.entries(plan.historicalFindings || {})) {
    if (!allowed.has(ruleId) || !Array.isArray(rows)) throw new Error(`unexpected R2B historical finding group ${ruleId}`);
    for (const row of rows) {
      if (!Array.isArray(row) || row.length !== 4 || !SHA64.test(row[0] || '') || typeof row[1] !== 'string' || !Number.isInteger(row[2]) || typeof row[3] !== 'string') {
        throw new Error(`invalid R2B historical finding identity ${ruleId}`);
      }
      findings.push({
        sourceClass: 'E4_ZIZMOR',
        findingId: row[0],
        ruleId,
        path: row[1],
        startLine: row[2],
        upstreamSeverity: row[3],
      });
    }
  }
  const keys = findings.map(findingKey);
  if (new Set(keys).size !== keys.length) throw new Error('duplicate R2B historical finding identity');
  return findings;
}

function remediationStatus(ruleId) {
  if (ruleId === 'zizmor/unpinned-uses') return 'REMEDIATED_BY_IMMUTABLE_ACTION_PIN_AND_ABSENT_FROM_CURRENT_ZIZMOR';
  if (ruleId === 'zizmor/artipacked') return 'REMEDIATED_BY_CHECKOUT_CREDENTIAL_BOUNDARY_AND_ABSENT_FROM_CURRENT_ZIZMOR';
  if (ruleId === 'zizmor/template-injection') return 'REMEDIATED_BY_SHELL_ENV_DATA_BOUNDARY_AND_ABSENT_FROM_CURRENT_ZIZMOR';
  throw new Error(`unsupported R2B rule ${ruleId}`);
}

export function verifyWorkflowSupplyChainRemediation(e4, plan, snapshot) {
  if (!e4 || !plan || !snapshot) throw new Error('E4 evidence, R2B plan and exact repository snapshot required');
  if (plan.schemaVersion !== 'M6_PR_E_E3_R2B_WORKFLOW_SUPPLY_CHAIN_REMEDIATION_PLAN_V1') throw new Error('R2B plan schema mismatch');
  if (e4.repository !== plan.repository || snapshot.repository !== plan.repository) throw new Error('R2B repository mismatch');
  if (!SHA40.test(e4.commitSha || '') || snapshot.commitSha !== e4.commitSha) throw new Error('R2B exact Head mismatch');
  if (!SHA64.test(e4.contentSha256 || '') || snapshot.currentE4CanonicalSha256 !== e4.contentSha256) throw new Error('R2B current E4 canonical binding mismatch');
  if (snapshot.scannerExecutionCount !== 1) throw new Error(`R2B current scanner must execute exactly once, got ${snapshot.scannerExecutionCount}`);
  if (snapshot.dependabotBlobSha !== plan.dependabotBlobShaRetained) throw new Error('R2B Dependabot R2A blob drift');
  if ((snapshot.suppressionPathsPresent || []).length) throw new Error(`R2B scanner suppression/ignore path present: ${snapshot.suppressionPathsPresent.join(',')}`);
  if (e4.allScannersCompleted !== true || e4.rawScannerReportsRetained !== false || e4.candidateSecretMaterialRetained !== false) {
    throw new Error('R2B complete redacted current E4 evidence required');
  }
  for (const scannerName of ['osv', 'gitleaks', 'zizmor', 'semgrep']) {
    const scanner = e4.scanners?.[scannerName];
    if (!scanner || scanner.scanCompleted !== true || scanner.rawReportRetained !== false || !Array.isArray(scanner.findings) || scanner.findings.length !== scanner.findingCount) {
      throw new Error(`R2B complete current ${scannerName} evidence required`);
    }
  }
  if (e4.scanners.gitleaks.candidateSecretMaterialRetained !== false) throw new Error('R2B Gitleaks candidate Secret retention prohibited');
  if (e4.scanners.semgrep.sourceSnippetRetained !== false) throw new Error('R2B Semgrep source snippet retention prohibited');

  const z = e4.scanners?.zizmor;
  if (!z || z.scanCompleted !== true || z.rawReportRetained !== false || !Array.isArray(z.findings) || z.findings.length !== z.findingCount) {
    throw new Error('complete current zizmor evidence required');
  }
  if (z.findingCount !== 0 || z.findings.length !== 0) throw new Error(`R2B current zizmor findings must be zero, got ${z.findingCount}`);

  const expectedNonZizmor = plan.priorNonZizmorFindingCounts || { osv: 115, gitleaks: 27, semgrep: 3 };
  for (const scanner of ['osv', 'gitleaks', 'semgrep']) {
    const current = e4.scanners?.[scanner]?.findingCount;
    if (current !== expectedNonZizmor[scanner]) throw new Error(`R2B ${scanner} finding drift ${current} != ${expectedNonZizmor[scanner]}`);
  }

  const sourcePaths = Object.keys(plan.workflowInventory?.sourceBlobs || {}).sort();
  const targetPaths = Object.keys(plan.workflowInventory?.targetBlobs || {}).sort();
  const currentPaths = Object.keys(snapshot.workflows || {}).sort();
  if (sourcePaths.length !== plan.workflowInventory.expectedFileCount || canonical(sourcePaths) !== canonical(targetPaths) || canonical(targetPaths) !== canonical(currentPaths)) {
    throw new Error('R2B governed workflow inventory mismatch');
  }

  const inspections = [];
  for (const path of currentPaths) {
    const current = snapshot.workflows[path];
    if (!current || typeof current.content !== 'string' || !SHA40.test(current.blobSha || '')) throw new Error(`R2B exact workflow snapshot required ${path}`);
    if (gitBlobSha(current.content) !== current.blobSha) throw new Error(`R2B workflow content/blob mismatch ${path}`);
    if (current.blobSha !== plan.workflowInventory.targetBlobs[path]) throw new Error(`R2B target workflow blob mismatch ${path}`);
    if (current.blobSha === plan.workflowInventory.sourceBlobs[path]) throw new Error(`R2B workflow remained at vulnerable source blob ${path}`);
    inspections.push(inspectWorkflow(path, current.content, plan.actionPins));
  }

  const actionUses = inspections.flatMap((item) => item.uses);
  const checkout = inspections.flatMap((item) => item.checkout);
  if (actionUses.length !== 43) throw new Error(`R2B reviewed action use count mismatch ${actionUses.length}`);
  if (checkout.length !== 14) throw new Error(`R2B checkout credential boundary count mismatch ${checkout.length}`);
  const automatic = inspections.filter((item) => item.automatic).map((item) => item.path);
  if (canonical(automatic) !== canonical([plan.workflowInventory.automaticWorkflowPath])) throw new Error(`R2B automatic workflow inventory mismatch ${automatic.join(',')}`);

  const validation = snapshot.workflows['.github/workflows/approval-platform-validation.yml'].content;
  const currentPhysicalJobCount = physicalJobCount(validation);
  if (currentPhysicalJobCount !== plan.invariants.physicalJobCount || currentPhysicalJobCount !== 9) {
    throw new Error(`R2B physical Job count drift ${currentPhysicalJobCount}`);
  }
  const affectedStep = inspections
    .find((item) => item.path === '.github/workflows/approval-platform-validation.yml')
    ?.steps.find((step) => step.fields.get('name') === 'Verify Persistence JDBC shard');
  if (!affectedStep) throw new Error('R2B template-injection affected step missing');
  const runBlock = affectedStep.raw.split(/\r?\n/).slice(affectedStep.raw.split(/\r?\n/).findIndex((line) => /^\s*run:\s*\|\s*$/.test(line)) + 1).join('\n');
  if (/\$\{\{\s*steps\.selection\.outputs\.tests\s*\}\}/.test(runBlock)) throw new Error('R2B direct template expression remains in shell source');
  if (!/SELECTED_TESTS:\s*\$\{\{\s*steps\.selection\.outputs\.tests\s*\}\}/.test(affectedStep.raw)) throw new Error('R2B step-scoped environment boundary absent');
  if (!/-Dtest="\$SELECTED_TESTS"/.test(runBlock)) throw new Error('R2B quoted shell environment reference absent');
  if (/\b(eval|source)\b/.test(runBlock) || /\$\([^)]*SELECTED_TESTS/.test(runBlock)) throw new Error('R2B shell data boundary reinterprets selected tests');

  const permanentArtifactClasses = new Set();
  for (const match of validation.matchAll(/^\s*name:\s*approval-(hygiene|maven|vben|mobile)-\$\{\{\s*github\.run_id\s*\}\}\s*$/gm)) {
    permanentArtifactClasses.add(match[1][0].toUpperCase() + match[1].slice(1));
  }
  if (canonical([...permanentArtifactClasses].sort()) !== canonical([...plan.invariants.permanentArtifactClasses].sort())) {
    throw new Error(`R2B permanent Artifact class drift ${[...permanentArtifactClasses].join(',')}`);
  }

  const historical = collectHistoricalFindings(plan);
  const counts = {};
  for (const finding of historical) counts[finding.ruleId] = (counts[finding.ruleId] || 0) + 1;
  for (const [ruleId, expected] of Object.entries(plan.expectedCurrentRuleCounts || {})) {
    const current = z.findings.filter((finding) => finding.ruleId === ruleId).length;
    if (current !== expected) throw new Error(`R2B current zizmor rule count mismatch ${ruleId} ${current} != ${expected}`);
  }
  for (const [ruleId, expected] of Object.entries(plan.expectedHistoricalFindingCounts || {})) {
    if (ruleId === 'total') continue;
    if ((counts[ruleId] || 0) !== expected) throw new Error(`R2B historical finding count mismatch ${ruleId}`);
  }
  if (historical.length !== plan.expectedHistoricalFindingCounts.total || historical.length !== 58) throw new Error(`R2B historical finding total mismatch ${historical.length}`);
  const currentKeys = new Set(z.findings.map(findingKey));
  const remediatedFindings = historical.map((finding) => {
    const key = findingKey(finding);
    if (currentKeys.has(key)) throw new Error(`R2B historical finding still present ${key}`);
    return stable({
      sourceClass: finding.sourceClass,
      findingId: finding.findingId,
      ruleId: finding.ruleId,
      path: finding.path,
      startLine: finding.startLine,
      upstreamSeverity: finding.upstreamSeverity,
      priorDisposition: 'APPLICABLE',
      currentStatus: remediationStatus(finding.ruleId),
    });
  });

  const currentScannerCounts = stable(Object.fromEntries(Object.entries(e4.scanners).map(([name, scanner]) => [name, scanner.findingCount])));
  const totalFindingCount = Object.values(currentScannerCounts).reduce((sum, value) => sum + value, 0);
  if (totalFindingCount !== e4.totalFindingCount) throw new Error('R2B current scanner total mismatch');

  const payload = stable({
    schemaVersion: 'M6_PR_E_E3_R2B_WORKFLOW_SUPPLY_CHAIN_REMEDIATION_EVIDENCE_V1',
    repository: plan.repository,
    commitSha: e4.commitSha,
    sourceE4CanonicalSha256: e4.contentSha256,
    priorAcceptedHead: plan.priorAcceptedHead,
    priorI4FindingSetSha256: plan.priorI4FindingSetSha256,
    priorI4CanonicalSha256: plan.priorI4CanonicalSha256,
    priorR2ACanonicalSha256: plan.priorR2ACanonicalSha256,
    dependabotBlobShaRetained: snapshot.dependabotBlobSha,
    workflowBlobs: stable(Object.fromEntries(currentPaths.map((path) => [path, snapshot.workflows[path].blobSha]))),
    actionUseCount: actionUses.length,
    checkoutCredentialBoundaryCount: checkout.length,
    templateInjectionBoundaryCount: 1,
    historicalFindingCount: historical.length,
    remediatedFindings,
    currentZizmorFindingCount: z.findingCount,
    currentScannerCounts,
    totalFindingCount,
    automaticWorkflowCount: automatic.length,
    physicalJobCount: currentPhysicalJobCount,
    permanentArtifactClasses: [...permanentArtifactClasses].sort(),
    scannerExecutionCount: snapshot.scannerExecutionCount,
    workflowSupplyChainRemediationValidated: true,
    releaseBlocked: true,
    authoritativeGitHubInventoryComplete: false,
    reasonCodes: [
      'AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE',
      'E3_SCANNER_FINDINGS_UNRESOLVED',
      'M6_PR_E_E3_FINDING_TRIAGE_REQUIRED',
    ],
  });
  return stable({ ...payload, contentSha256: sha256(canonical(payload)) });
}

export const canonicalWorkflowSupplyChainRemediation = canonical;
