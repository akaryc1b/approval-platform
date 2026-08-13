import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { applyWorkflowSupplyChainReviews } from '../security/m6-pr-e-e3-apply-workflow-supply-chain-reviews.mjs';
import { verifyDependabotCooldownRemediation } from '../security/m6-pr-e-e3-verify-dependabot-cooldown-remediation.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const p=x=>path.join(root,x);
const text=x=>{const f=p(x);assert.equal(existsSync(f),true,`${x} must exist`);return readFileSync(f,'utf8');};
const contract='docs/m6/M6_PR_E_E3_I4_WORKFLOW_SUPPLY_CHAIN_AUDIT.md';
const reviewFile='docs/m6/m6-pr-e-e3-i4-reviewed-findings.json';
const actionBaselineFile='docs/m6/m6-pr-e-e2-action-resolution-baseline.json';
const r2aContract='docs/m6/M6_PR_E_E3_R2A_DEPENDABOT_COOLDOWN_REMEDIATION.md';
const r2aPlanFile='docs/m6/m6-pr-e-e3-r2a-dependabot-cooldown-remediation.json';

function planned(review){
  return Object.entries(review.findingGroups).flatMap(([ruleId,rows])=>rows.map(row=>({sourceClass:'E4_ZIZMOR',ruleId,findingId:row[0],path:row[1],startLine:row[2],upstreamSeverity:row[3]})));
}
function unresolved(x){return{sourceClass:x.sourceClass,findingId:x.findingId,severityBand:'UNKNOWN',disposition:'UNRESOLVED',reachability:{packaged:{value:null,evidence:'pending'},loaded:{value:null,evidence:'pending'},invoked:{value:null,evidence:'pending'},externallyReachable:{value:null,evidence:'pending'}}};}

test('E3-I4 retains the exact historical 61-finding audit and validates historical source blobs',()=>{
  const body=text(contract);for(const marker of [
    'MUTABLE_REF != IMMUTABLE_ACTION_IDENTITY','CURRENT_RESOLVED_SHA != COMMITTED_SHA_PIN','CONTENTS_READ != CREDENTIAL_NOT_PRESENT',
    'WORKFLOW_DISPATCH != SUPPLY_CHAIN_UNREACHABLE','DEPENDABOT_PULL_REQUEST_ONLY != COOLDOWN_PRESENT',
    'STATIC_MATRIX != TRUSTED_PULL_REQUEST_MODIFIABLE_STEP_OUTPUT','TEMPLATE_EXPANSION != SHELL_DATA_BOUNDARY',
    'NO_SUPPRESSION','NO_SEVERITY_DOWNGRADE','NO_EXCEPTION','NO_WORKFLOW_CHANGE_IN_E3_I4','NO_DEPENDABOT_CHANGE_IN_E3_I4',
    'M6_PR_E_E3_CLOSURE_NOT_ACCEPTED','PRB_16_REMAINS_OPEN','PRB_17_REMAINS_OPEN','NO_READY','NO_MERGE'
  ])assert.ok(body.includes(marker),marker);
  const review=JSON.parse(text(reviewFile)), baseline=JSON.parse(text(actionBaselineFile)), rows=planned(review);
  assert.equal(review.reviewBasisHead,'a88ca0267f199c98026e041aa2a43f84f5491b8e');assert.equal(rows.length,61);assert.equal(new Set(rows.map(x=>x.findingId)).size,61);
  const counts={};for(const x of rows)counts[x.ruleId]=(counts[x.ruleId]||0)+1;
  assert.deepEqual(counts,{'zizmor/unpinned-uses':43,'zizmor/artipacked':14,'zizmor/dependabot-cooldown':3,'zizmor/template-injection':1});
  assert.ok(rows.every(x=>review.rulePolicies[x.ruleId].disposition==='APPLICABLE'));assert.deepEqual(review.actionResolutions,baseline.actionRefs);
  assert.deepEqual(review.expectedPostReviewSummary,{totalFindingCount:206,notApplicableCount:3,applicableCount:61,unresolvedCount:142,cumulativeReviewedFindingCount:68,historicallyRemediatedFindingCount:2,releaseBlocked:true});
  assert.deepEqual(review.r2RemediationPlan,{immutableActionPins:43,checkoutPersistCredentialsFalse:14,dependabotCooldownEntries:3,templateInjectionEnvBoundaryFixes:1,workflowOrDependabotChangesInI4:0});
  assert.equal(review.summary.workflowChanges,0);assert.equal(review.summary.suppressions,0);assert.equal(review.summary.severityDowngrades,0);assert.equal(review.summary.automaticExceptions,0);assert.equal(review.summary.e3ClosureAuthorized,false);
  if(process.env.GITHUB_ACTIONS==='true')for(const [sourcePath,expected] of Object.entries(review.sourceBlobs)){
    const h=spawnSync('git',['rev-parse',`${review.reviewBasisHead}:${sourcePath}`],{cwd:root,encoding:'utf8'});assert.equal(h.status,0,h.stderr||h.stdout);assert.equal(h.stdout.trim(),expected,`historical I4 source drift ${sourcePath}`);
  }
});

test('E3-I4 unremediated fixture still advances the exact 61 reviewed findings to APPLICABLE',()=>{
  const review=JSON.parse(text(reviewFile)),z=planned(review),commitSha='7'.repeat(40);
  const decisions=[...z.map(unresolved),...Array.from({length:3},(_,i)=>({sourceClass:'E4_SEMGREP',findingId:`na-${i}`,severityBand:'UNKNOWN',disposition:'NOT_APPLICABLE'})),...Array.from({length:142},(_,i)=>({sourceClass:'E4_OSV_SCANNER',findingId:`u-${i}`,severityBand:'UNKNOWN',disposition:'UNRESOLVED'}))];
  const triage={repository:review.repository,commitSha,contentSha256:'8'.repeat(64),cumulativeReviewedFindingCount:7,remediatedHistoricalFindingCount:2,decisions};
  const e4={repository:review.repository,commitSha,contentSha256:'9'.repeat(64),scanners:{zizmor:{scanCompleted:true,rawReportRetained:false,findingCount:z.length,findings:z}}};
  const out=applyWorkflowSupplyChainReviews(triage,e4,review);assert.equal(out.reviewedFindingCount,61);assert.equal(out.cumulativeReviewedFindingCount,68);assert.equal(out.historicallyRemediatedFindingCount,2);assert.equal(out.summary.dispositionCounts.APPLICABLE,61);assert.equal(out.summary.dispositionCounts.NOT_APPLICABLE,3);assert.equal(out.summary.dispositionCounts.UNRESOLVED,142);assert.equal(out.summary.releaseBlocked,true);
});

test('E3-R2A remediates exactly three Dependabot cooldown findings and keeps the other 58 I4 findings applicable',()=>{
  const body=text(r2aContract);for(const marker of ['REMEDIATION != HISTORY_REWRITE','COOLDOWN_FINDING_ABSENCE_REQUIRES_EXACT_DEPENDABOT_BLOB','DEPENDABOT_COOLDOWN != SECURITY_UPDATE_DELAY','CURRENT_ZIZMOR_SET_PLUS_REMEDIATED_HISTORY == PRIOR_REVIEWED_I4_SET','NO_WORKFLOW_CHANGE_IN_R2A','NO_ACTION_PIN_CHANGE_IN_R2A','NO_SUPPRESSION','NO_SEVERITY_DOWNGRADE','NO_EXCEPTION','PRB_16_REMAINS_OPEN','PRB_17_REMAINS_OPEN'])assert.ok(body.includes(marker),marker);
  const review=JSON.parse(text(reviewFile)),plan=JSON.parse(text(r2aPlanFile)),all=planned(review),current=all.filter(x=>x.ruleId!=='zizmor/dependabot-cooldown'),commitSha='7'.repeat(40);
  assert.equal(plan.priorAcceptedHead,'bab2816ad0b8a43eaa022214170a79ae8af1473f');assert.equal(plan.sourceDependabotBlobSha,'6ad5b685c038fff8f2fded0ae9de0152dc0f6a34');assert.equal(plan.targetDependabotBlobSha,'38ba75af261c084b5c8984c52fb1bf23439fd1a9');assert.equal(plan.cooldownDefaultDays,7);assert.equal(plan.remediatedFindings.length,3);assert.equal(plan.expectedCurrentZizmorFindingCount,58);
  const d=text('.github/dependabot.yml');assert.equal((d.match(/package-ecosystem:/g)||[]).length,3);assert.equal((d.match(/interval:\s*weekly/g)||[]).length,3);assert.equal((d.match(/cooldown:\s*\n\s+default-days:\s*7/g)||[]).length,3);assert.equal((d.match(/open-pull-requests-limit:\s*5/g)||[]).length,3);
  if(process.env.GITHUB_ACTIONS==='true'){const h=spawnSync('git',['hash-object','.github/dependabot.yml'],{cwd:root,encoding:'utf8'});assert.equal(h.status,0,h.stderr||h.stdout);assert.equal(h.stdout.trim(),plan.targetDependabotBlobSha);}
  const decisions=[...current.map(unresolved),...Array.from({length:3},(_,i)=>({sourceClass:'E4_SEMGREP',findingId:`na-${i}`,severityBand:'UNKNOWN',disposition:'NOT_APPLICABLE'})),...Array.from({length:142},(_,i)=>({sourceClass:'E4_OSV_SCANNER',findingId:`u-${i}`,severityBand:'UNKNOWN',disposition:'UNRESOLVED'}))];
  const triage={repository:review.repository,commitSha,contentSha256:'8'.repeat(64),cumulativeReviewedFindingCount:7,remediatedHistoricalFindingCount:2,decisions};
  const e4={repository:review.repository,commitSha,contentSha256:'9'.repeat(64),scanners:{zizmor:{scanCompleted:true,rawReportRetained:false,findingCount:current.length,findings:current}}};
  const remediation=verifyDependabotCooldownRemediation(e4,plan,plan.targetDependabotBlobSha),out=applyWorkflowSupplyChainReviews(triage,e4,review,remediation);
  assert.equal(remediation.remediatedFindings.length,3);assert.equal(out.historicalReviewedFindingCount,61);assert.equal(out.reviewedFindingCount,58);assert.equal(out.remediatedHistoricalFindingCount,3);assert.equal(out.cumulativeReviewedFindingCount,68);assert.equal(out.historicallyRemediatedFindingCount,5);assert.equal(out.summary.dispositionCounts.APPLICABLE,58);assert.equal(out.summary.dispositionCounts.NOT_APPLICABLE,3);assert.equal(out.summary.dispositionCounts.UNRESOLVED,142);assert.equal(out.summary.releaseBlocked,true);
});
