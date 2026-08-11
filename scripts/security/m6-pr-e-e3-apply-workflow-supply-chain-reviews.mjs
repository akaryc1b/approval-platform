#!/usr/bin/env node
import { createHash } from 'node:crypto';

const SHA40=/^[0-9a-f]{40}$/;
const SHA64=/^[0-9a-f]{64}$/;
const REACHABILITY=Object.freeze(['packaged','loaded','invoked','externallyReachable']);
const stable=v=>Array.isArray(v)?v.map(stable):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,stable(v[k])])):v;
const canonical=v=>JSON.stringify(stable(v));
const sha256=v=>createHash('sha256').update(v).digest('hex');
const key=f=>`${f.sourceClass}:${f.findingId}`;

function requireReachability(r,label){
  if(!r||typeof r!=='object')throw new Error(`reachability required ${label}`);
  for(const k of REACHABILITY){
    if(!r[k]||r[k].value!==true||typeof r[k].evidence!=='string'||!r[k].evidence.trim())throw new Error(`I4 APPLICABLE requires ${k}=true with evidence ${label}`);
  }
}
function exactZizmor(e4){
  const z=e4?.scanners?.zizmor;
  if(!z||z.scanCompleted!==true||z.rawReportRetained!==false||!Array.isArray(z.findings)||z.findings.length!==z.findingCount)throw new Error('complete current zizmor evidence required');
  return z.findings;
}
function plannedFindings(review){
  const out=[];for(const [ruleId,rows] of Object.entries(review.findingGroups||{})){if(!Array.isArray(rows))throw new Error(`I4 finding group must be array ${ruleId}`);for(const row of rows){if(!Array.isArray(row)||row.length!==4)throw new Error(`I4 finding tuple invalid ${ruleId}`);out.push({sourceClass:'E4_ZIZMOR',ruleId,findingId:row[0],path:row[1],startLine:row[2],upstreamSeverity:row[3]});}}return out;
}
function sameFinding(a,b){
  return a.sourceClass==='E4_ZIZMOR'&&b.sourceClass==='E4_ZIZMOR'&&a.findingId===b.findingId&&a.ruleId===b.ruleId&&a.path===b.path&&(a.startLine??null)===(b.startLine??null)&&String(a.upstreamSeverity||'')===String(b.upstreamSeverity||'');
}

export function applyWorkflowSupplyChainReviews(triage,e4,review){
  if(!triage||!e4||!review)throw new Error('triage e4 and review required');
  if(triage.repository!==e4.repository||review.repository!==triage.repository)throw new Error('repository mismatch');
  if(triage.commitSha!==e4.commitSha)throw new Error('triage/E4 Head mismatch');
  if(!SHA40.test(review.reviewBasisHead||''))throw new Error('review basis Head required');
  if(!SHA64.test(review.reviewBasisE4CanonicalSha256||'')||!SHA64.test(review.reviewBasisI3CanonicalSha256||''))throw new Error('review basis canonical digests required');
  if(!SHA64.test(review.findingSetSha256||''))throw new Error('review finding-set digest required');

  const currentZizmor=exactZizmor(e4), planned=plannedFindings(review);
  if(planned.length!==61)throw new Error(`I4 must review exactly 61 zizmor findings, got ${planned.length}`);
  if(currentZizmor.length!==planned.length)throw new Error(`current zizmor count drift ${currentZizmor.length} != ${planned.length}`);
  const currentBy=new Map(currentZizmor.map(f=>[key(f),f])), plannedKeys=new Set(), planBy=new Map();
  for(const f of planned){
    const k=key({sourceClass:'E4_ZIZMOR',findingId:f.findingId});
    if(plannedKeys.has(k))throw new Error(`duplicate I4 reviewed finding ${k}`);plannedKeys.add(k);
    const normalized={sourceClass:'E4_ZIZMOR',...f};planBy.set(k,normalized);
    const source=currentBy.get(k);if(!source||!sameFinding(normalized,source))throw new Error(`current zizmor identity drift ${k}`);
  }
  if(currentBy.size!==plannedKeys.size||[...currentBy.keys()].some(k=>!plannedKeys.has(k)))throw new Error('current zizmor set is not exactly the reviewed I4 set');
  const setDigest=sha256([...plannedKeys].map(x=>x.split(':').at(-1)).sort().join('\n')+'\n');
  if(setDigest!==review.findingSetSha256)throw new Error(`I4 finding-set digest mismatch ${setDigest}`);

  const current=new Map(triage.decisions.map(f=>[key(f),f])), delta=new Map();
  for(const [k,f] of planBy){
    const original=current.get(k);if(!original)throw new Error(`I4 finding absent from triage ${k}`);
    if(original.disposition!=='UNRESOLVED')throw new Error(`I4 can only advance UNRESOLVED finding ${k}`);
    if(original.severityBand!=='UNKNOWN')throw new Error(`I4 severity mutation boundary changed ${k}`);
    const policy=review.rulePolicies?.[f.ruleId];if(!policy||policy.disposition!=='APPLICABLE')throw new Error(`I4 APPLICABLE policy required ${f.ruleId}`);
    requireReachability(policy.reachability,k);
    if(!Array.isArray(policy.exploitPreconditions)||!policy.exploitPreconditions.length)throw new Error(`I4 exploit prerequisite required ${k}`);
    if(typeof policy.evidenceCode!=='string'||!policy.evidenceCode||typeof policy.evidence!=='string'||!policy.evidence||typeof policy.r2Action!=='string'||!policy.r2Action)throw new Error(`I4 evidence/R2 action required ${k}`);
    delta.set(k,{finding:f,policy});
  }

  const decisions=triage.decisions.map(f=>{
    const d=delta.get(key(f));if(!d)return f;const policy=d.policy;
    return stable({...f,disposition:'APPLICABLE',reachability:policy.reachability,exploitPreconditions:policy.exploitPreconditions,mitigations:policy.mitigations||[],reviewEvidence:{
      reviewLayer:'E3-I4',reviewBasisHead:review.reviewBasisHead,reviewBasisE4CanonicalSha256:review.reviewBasisE4CanonicalSha256,reviewBasisI3CanonicalSha256:review.reviewBasisI3CanonicalSha256,ruleId:d.finding.ruleId,evidenceCode:policy.evidenceCode,evidence:policy.evidence,r2Action:policy.r2Action
    }});
  });
  const dispositionCounts={};for(const f of decisions)dispositionCounts[f.disposition]=(dispositionCounts[f.disposition]||0)+1;
  const cumulativeReviewedFindingCount=(triage.cumulativeReviewedFindingCount||0)+delta.size;
  const payload=stable({
    schemaVersion:'M6_PR_E_E3_I4_TRIAGE_V1',repository:triage.repository,commitSha:triage.commitSha,sourceI3CanonicalSha256:triage.contentSha256,sourceE4CanonicalSha256:e4.contentSha256,
    reviewBasisHead:review.reviewBasisHead,reviewBasisE4CanonicalSha256:review.reviewBasisE4CanonicalSha256,reviewBasisI3CanonicalSha256:review.reviewBasisI3CanonicalSha256,reviewedFindingCount:delta.size,cumulativeReviewedFindingCount,historicallyRemediatedFindingCount:triage.remediatedHistoricalFindingCount||0,decisions,
    summary:{dispositionCounts,applicableCount:dispositionCounts.APPLICABLE||0,notApplicableCount:dispositionCounts.NOT_APPLICABLE||0,unresolvedCount:dispositionCounts.UNRESOLVED||0,releaseBlocked:true,reasonCodes:['AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE','E3_SCANNER_FINDINGS_UNRESOLVED','E3_APPLICABLE_FINDINGS_REQUIRE_REMEDIATION']}
  });
  return stable({...payload,contentSha256:sha256(canonical(payload))});
}
export const canonicalWorkflowSupplyChainTriage=canonical;
