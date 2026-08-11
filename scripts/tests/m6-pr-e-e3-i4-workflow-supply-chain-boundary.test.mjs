import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { applyWorkflowSupplyChainReviews } from '../security/m6-pr-e-e3-apply-workflow-supply-chain-reviews.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const p=x=>path.join(root,x);
const text=x=>{const f=p(x);assert.equal(existsSync(f),true,`${x} must exist`);return readFileSync(f,'utf8');};
const contract='docs/m6/M6_PR_E_E3_I4_WORKFLOW_SUPPLY_CHAIN_AUDIT.md';
const reviewFile='docs/m6/m6-pr-e-e3-i4-reviewed-findings.json';
const actionBaselineFile='docs/m6/m6-pr-e-e2-action-resolution-baseline.json';

function planned(review){
  return Object.entries(review.findingGroups).flatMap(([ruleId,rows])=>rows.map(row=>({sourceClass:'E4_ZIZMOR',ruleId,findingId:row[0],path:row[1],startLine:row[2],upstreamSeverity:row[3]})));
}

test('E3-I4 freezes the exact 61-finding zizmor set without Workflow or Dependabot changes',()=>{
  const body=text(contract);for(const marker of [
    'MUTABLE_REF != IMMUTABLE_ACTION_IDENTITY','CURRENT_RESOLVED_SHA != COMMITTED_SHA_PIN','CONTENTS_READ != CREDENTIAL_NOT_PRESENT',
    'WORKFLOW_DISPATCH != SUPPLY_CHAIN_UNREACHABLE','DEPENDABOT_PULL_REQUEST_ONLY != COOLDOWN_PRESENT',
    'STATIC_MATRIX != TRUSTED_PULL_REQUEST_MODIFIABLE_STEP_OUTPUT','TEMPLATE_EXPANSION != SHELL_DATA_BOUNDARY',
    'NO_SUPPRESSION','NO_SEVERITY_DOWNGRADE','NO_EXCEPTION','NO_WORKFLOW_CHANGE_IN_E3_I4','NO_DEPENDABOT_CHANGE_IN_E3_I4',
    'M6_PR_E_E3_CLOSURE_NOT_ACCEPTED','PRB_16_REMAINS_OPEN','PRB_17_REMAINS_OPEN','NO_READY','NO_MERGE'
  ])assert.ok(body.includes(marker),marker);
  const review=JSON.parse(text(reviewFile)), baseline=JSON.parse(text(actionBaselineFile)), rows=planned(review);
  assert.equal(review.reviewBasisHead,'a88ca0267f199c98026e041aa2a43f84f5491b8e');
  assert.equal(rows.length,61);assert.equal(new Set(rows.map(x=>x.findingId)).size,61);
  const counts={};for(const x of rows)counts[x.ruleId]=(counts[x.ruleId]||0)+1;
  assert.deepEqual(counts,{'zizmor/unpinned-uses':43,'zizmor/artipacked':14,'zizmor/dependabot-cooldown':3,'zizmor/template-injection':1});
  assert.ok(rows.every(x=>review.rulePolicies[x.ruleId].disposition==='APPLICABLE'));
  assert.deepEqual(review.actionResolutions,baseline.actionRefs);
  assert.deepEqual(review.expectedPostReviewSummary,{totalFindingCount:206,notApplicableCount:3,applicableCount:61,unresolvedCount:142,cumulativeReviewedFindingCount:68,historicallyRemediatedFindingCount:2,releaseBlocked:true});
  assert.deepEqual(review.r2RemediationPlan,{immutableActionPins:43,checkoutPersistCredentialsFalse:14,dependabotCooldownEntries:3,templateInjectionEnvBoundaryFixes:1,workflowOrDependabotChangesInI4:0});
  assert.equal(review.summary.workflowChanges,0);assert.equal(review.summary.suppressions,0);assert.equal(review.summary.severityDowngrades,0);assert.equal(review.summary.automaticExceptions,0);assert.equal(review.summary.e3ClosureAuthorized,false);
  if(process.env.GITHUB_ACTIONS==='true')for(const [sourcePath,expected] of Object.entries(review.sourceBlobs)){
    const h=spawnSync('git',['hash-object',sourcePath],{cwd:root,encoding:'utf8'});assert.equal(h.status,0,h.stderr||h.stdout);assert.equal(h.stdout.trim(),expected,`I4 source drift ${sourcePath}`);
  }
});

test('E3-I4 machine overlay advances only the exact reviewed zizmor findings to APPLICABLE',()=>{
  const review=JSON.parse(text(reviewFile)), z=planned(review), commitSha='7'.repeat(40);
  const unresolved=x=>({sourceClass:x.sourceClass,findingId:x.findingId,severityBand:'UNKNOWN',disposition:'UNRESOLVED',reachability:{packaged:{value:null,evidence:'pending'},loaded:{value:null,evidence:'pending'},invoked:{value:null,evidence:'pending'},externallyReachable:{value:null,evidence:'pending'}}});
  const decisions=[...z.map(unresolved),...Array.from({length:3},(_,i)=>({sourceClass:'E4_SEMGREP',findingId:`na-${i}`,severityBand:'UNKNOWN',disposition:'NOT_APPLICABLE'})),...Array.from({length:142},(_,i)=>({sourceClass:'E4_OSV_SCANNER',findingId:`u-${i}`,severityBand:'UNKNOWN',disposition:'UNRESOLVED'}))];
  const triage={repository:review.repository,commitSha,contentSha256:'8'.repeat(64),cumulativeReviewedFindingCount:7,remediatedHistoricalFindingCount:2,decisions};
  const e4={repository:review.repository,commitSha,contentSha256:'9'.repeat(64),scanners:{zizmor:{scanCompleted:true,rawReportRetained:false,findingCount:z.length,findings:z}}};
  const out=applyWorkflowSupplyChainReviews(triage,e4,review);
  assert.equal(out.reviewedFindingCount,61);assert.equal(out.cumulativeReviewedFindingCount,68);assert.equal(out.historicallyRemediatedFindingCount,2);
  assert.equal(out.summary.dispositionCounts.APPLICABLE,61);assert.equal(out.summary.dispositionCounts.NOT_APPLICABLE,3);assert.equal(out.summary.dispositionCounts.UNRESOLVED,142);assert.equal(out.summary.releaseBlocked,true);
  assert.ok(out.decisions.filter(x=>x.sourceClass==='E4_ZIZMOR').every(x=>x.disposition==='APPLICABLE'));
  const drift={...e4,scanners:{zizmor:{...e4.scanners.zizmor,findings:e4.scanners.zizmor.findings.slice(1),findingCount:60}}};
  assert.throws(()=>applyWorkflowSupplyChainReviews(triage,drift,review),/count drift|exactly the reviewed I4 set/);
});
