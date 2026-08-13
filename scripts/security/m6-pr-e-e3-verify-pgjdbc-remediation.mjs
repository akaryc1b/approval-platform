#!/usr/bin/env node
import { createHash } from 'node:crypto';

const stable=v=>Array.isArray(v)?v.map(stable):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,stable(v[k])])):v;
const canonical=v=>JSON.stringify(stable(v));
const sha256=v=>createHash('sha256').update(v).digest('hex');

export function verifyPgjdbcRemediation(e4,plan){
  if(!e4||!plan)throw new Error('E4 evidence and remediation plan required');
  if(e4.repository!==plan.repository)throw new Error('remediation repository mismatch');
  if(e4.e2GraphDigest!==plan.targetE2GraphDigest)throw new Error(`remediation E2 graph mismatch ${e4.e2GraphDigest}`);
  if(e4.scanners?.osv?.scanCompleted!==true)throw new Error('OSV scanner must complete for remediation');
  const findings=e4.scanners.osv.findings||[];
  const currentIds=new Set(findings.map(f=>`${f.sourceClass}:${f.findingId}`));
  const currentUpstream=new Set(findings.map(f=>String(f.upstreamFindingId||'')));
  const currentAliases=new Set(findings.flatMap(f=>f.aliases||[]).map(String));
  const remediated=[];
  for(const item of plan.remediatedFindings||[]){
    const key=`${item.sourceClass}:${item.findingId}`;
    if(item.requiredAbsentFromCurrentScanner!==true)throw new Error(`remediation absence contract required ${key}`);
    if(currentIds.has(key)||currentUpstream.has(item.upstreamFindingId)||(item.aliases||[]).some(a=>currentAliases.has(a)))throw new Error(`remediated finding still present ${key}`);
    remediated.push(stable({
      sourceClass:item.sourceClass,
      findingId:item.findingId,
      upstreamFindingId:item.upstreamFindingId,
      aliases:item.aliases||[],
      priorDisposition:item.priorDisposition,
      fixedSince:item.fixedSince,
      currentStatus:'REMEDIATED_BY_FIXED_COMPONENT_AND_ABSENT_FROM_CURRENT_OSV'
    }));
  }
  const payload=stable({
    schemaVersion:'M6_PR_E_E3_R1_PGJDBC_REMEDIATION_EVIDENCE_V1',
    repository:e4.repository,
    commitSha:e4.commitSha,
    sourceE4CanonicalSha256:e4.contentSha256,
    priorAcceptedHead:plan.priorAcceptedHead,
    priorE3I3CanonicalSha256:plan.priorE3I3CanonicalSha256,
    priorE2GraphDigest:plan.priorE2GraphDigest,
    currentE2GraphDigest:e4.e2GraphDigest,
    dependencyOverride:plan.dependencyOverride,
    expectedTransitiveChanges:plan.expectedTransitiveChanges||[],
    remediatedFindings:remediated,
    releaseBlocked:true,
    reasonCodes:['AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE','E3_SCANNER_FINDINGS_UNRESOLVED']
  });
  return stable({...payload,contentSha256:sha256(canonical(payload))});
}
export const canonicalPgjdbcRemediation=canonical;
