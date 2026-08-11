#!/usr/bin/env node
import { createHash } from 'node:crypto';

const ALLOWED = new Set(['APPLICABLE','NOT_APPLICABLE','UNREACHABLE','MITIGATED','ACCEPTED_WITH_EXPIRY','UNRESOLVED','EVIDENCE_UNAVAILABLE']);
const REACHABILITY = Object.freeze(['packaged','loaded','invoked','externallyReachable']);
const stable=v=>Array.isArray(v)?v.map(stable):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,stable(v[k])])):v;
const canonical=v=>JSON.stringify(stable(v));
const sha256=v=>createHash('sha256').update(v).digest('hex');

function sameArray(a,b){return canonical([...(a||[])].sort())===canonical([...(b||[])].sort());}
function requireReachability(r,key){
  if(!r || typeof r!=='object')throw new Error(`reachability required ${key}`);
  for(const k of REACHABILITY){
    if(!r[k] || !Object.hasOwn(r[k],'value') || typeof r[k].evidence!=='string' || !r[k].evidence.trim())throw new Error(`reachability ${k} evidence required ${key}`);
    if(![true,false,null].includes(r[k].value))throw new Error(`invalid reachability ${k} value ${key}`);
  }
}
function osvById(e4){
  const m=new Map();
  for(const f of e4?.scanners?.osv?.findings||[])m.set(`${f.sourceClass}:${f.findingId}`,f);
  return m;
}

export function applyRuntimeDeploymentReviews(triage,e4,review){
  if(!triage||!e4||!review)throw new Error('triage e4 and review required');
  if(triage.repository!==e4.repository||review.repository!==triage.repository)throw new Error('repository mismatch');
  if(triage.commitSha!==e4.commitSha)throw new Error('triage/E4 Head mismatch');
  if(!/^[0-9a-f]{64}$/.test(review.reviewBasisE2GraphDigest||''))throw new Error('review E2 graph digest required');
  if(e4.e2GraphDigest!==review.reviewBasisE2GraphDigest)throw new Error('E2 graph drift blocks E3-I3 review');
  if(!/^[0-9a-f]{40}$/.test(review.reviewBasisHead||''))throw new Error('review basis Head required');

  const current=new Map(triage.decisions.map(f=>[`${f.sourceClass}:${f.findingId}`,f]));
  const osv=osvById(e4), seen=new Set(), delta=new Map();
  for(const r of review.reviewedFindings||[]){
    const key=`${r.sourceClass}:${r.findingId}`;
    if(seen.has(key))throw new Error(`duplicate reviewed finding ${key}`);seen.add(key);
    const original=current.get(key), source=osv.get(key);
    if(!original||!source)throw new Error(`reviewed OSV finding missing ${key}`);
    if(original.disposition!=='UNRESOLVED')throw new Error(`E3-I3 can only review currently UNRESOLVED findings ${key}`);
    if(r.sourceClass!=='E4_OSV_SCANNER')throw new Error(`E3-I3 is OSV-only ${key}`);
    if(!ALLOWED.has(r.disposition))throw new Error(`invalid disposition ${key}`);
    if(r.severityBand!==original.severityBand)throw new Error(`severity mutation prohibited ${key}`);
    if((r.exception??null)!==null)throw new Error(`exceptions prohibited ${key}`);
    if(r.upstreamFindingId!==source.upstreamFindingId)throw new Error(`upstream finding drift ${key}`);
    if(r.componentRef!==original.componentRef || !(source.componentRefs||[]).includes(r.componentRef))throw new Error(`component drift ${key}`);
    if(!sameArray(r.aliases,source.aliases)||!sameArray(r.fixedVersions,source.fixedVersions))throw new Error(`OSV source identity drift ${key}`);
    if(r.dependencyPathComplete!==true || !Array.isArray(r.dependencyPath) || r.dependencyPath.at(-1)!==r.componentRef)throw new Error(`complete dependency path required ${key}`);
    requireReachability(r.reachability,key);
    if(r.disposition==='NOT_APPLICABLE'&&r.exploitPreconditionsSatisfied!==false)throw new Error(`NOT_APPLICABLE needs false prerequisite ${key}`);
    if(r.disposition==='APPLICABLE'){
      if(r.exploitPreconditionsSatisfied!==true)throw new Error(`APPLICABLE needs positive exploit-path evidence ${key}`);
      if(!REACHABILITY.every(k=>r.reachability[k].value===true))throw new Error(`APPLICABLE needs all reachability dimensions true ${key}`);
    }
    delta.set(key,r);
  }

  const decisions=triage.decisions.map(f=>{
    const r=delta.get(`${f.sourceClass}:${f.findingId}`);if(!r)return f;
    return stable({...f,
      disposition:r.disposition,
      dependencyPath:r.dependencyPath,
      dependencyPathComplete:true,
      dependencyPathEvidence:r.dependencyPathEvidence,
      deploymentScopeEvidence:r.deploymentScopeEvidence,
      reachability:r.reachability,
      exploitPreconditions:r.exploitPreconditions,
      mitigations:r.mitigations,
      reviewEvidence:{
        reviewLayer:'E3-I3',
        reviewBasisHead:review.reviewBasisHead,
        reviewBasisE2GraphDigest:review.reviewBasisE2GraphDigest,
        evidenceCode:r.evidenceCode,
        evidence:r.evidence,
        upstreamAuthority:r.upstreamAuthority,
        exploitPreconditionsSatisfied:r.exploitPreconditionsSatisfied,
        supportingPaths:r.supportingPaths||[]
      }
    });
  });
  const dispositionCounts={};for(const f of decisions)dispositionCounts[f.disposition]=(dispositionCounts[f.disposition]||0)+1;
  const cumulativeReviewedFindingCount=decisions.filter(f=>f.reviewEvidence).length;
  const payload=stable({
    schemaVersion:'M6_PR_E_E3_I3_TRIAGE_V1',
    repository:triage.repository,
    commitSha:triage.commitSha,
    sourceI2CanonicalSha256:triage.contentSha256,
    sourceE4CanonicalSha256:e4.contentSha256,
    reviewBasisHead:review.reviewBasisHead,
    reviewBasisE2GraphDigest:review.reviewBasisE2GraphDigest,
    reviewedFindingCount:delta.size,
    cumulativeReviewedFindingCount,
    decisions,
    summary:{
      dispositionCounts,
      unresolvedCount:dispositionCounts.UNRESOLVED||0,
      applicableCount:dispositionCounts.APPLICABLE||0,
      notApplicableCount:dispositionCounts.NOT_APPLICABLE||0,
      releaseBlocked:true,
      reasonCodes:[
        'AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE',
        'E3_SCANNER_FINDINGS_UNRESOLVED',
        ...(dispositionCounts.APPLICABLE?['E3_APPLICABLE_FINDINGS_REQUIRE_REMEDIATION']:[])
      ]
    }
  });
  return stable({...payload,contentSha256:sha256(canonical(payload))});
}
export const canonicalRuntimeDeploymentTriage=canonical;
