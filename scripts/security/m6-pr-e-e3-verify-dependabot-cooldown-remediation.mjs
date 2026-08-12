#!/usr/bin/env node
import { createHash } from 'node:crypto';

const stable=v=>Array.isArray(v)?v.map(stable):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,stable(v[k])])):v;
const canonical=v=>JSON.stringify(stable(v));
const sha256=v=>createHash('sha256').update(v).digest('hex');

export function verifyDependabotCooldownRemediation(e4,plan,currentDependabotBlobSha){
  if(!e4||!plan)throw new Error('E4 evidence and R2A plan required');
  if(e4.repository!==plan.repository)throw new Error('R2A repository mismatch');
  if(!/^[0-9a-f]{40}$/.test(currentDependabotBlobSha||''))throw new Error('current Dependabot blob SHA required');
  if(currentDependabotBlobSha!==plan.targetDependabotBlobSha)throw new Error(`Dependabot target blob mismatch ${currentDependabotBlobSha}`);
  const z=e4.scanners?.zizmor;
  if(!z||z.scanCompleted!==true||z.rawReportRetained!==false||!Array.isArray(z.findings)||z.findings.length!==z.findingCount)throw new Error('complete current zizmor evidence required');
  if(z.findingCount>plan.expectedCurrentZizmorFindingCount)throw new Error(`R2A current zizmor count increased ${z.findingCount} > ${plan.expectedCurrentZizmorFindingCount}`);
  const counts={};for(const f of z.findings)counts[f.ruleId]=(counts[f.ruleId]||0)+1;
  for(const [ruleId,expected] of Object.entries(plan.expectedCurrentRuleCounts||{})){
    const actual=counts[ruleId]||0;
    if(actual>expected)throw new Error(`R2A zizmor rule count increased ${ruleId} ${actual} > ${expected}`);
  }
  if((counts['zizmor/dependabot-cooldown']||0)!==0)throw new Error('R2A Dependabot cooldown finding reappeared');
  const currentIds=new Set(z.findings.map(f=>`${f.sourceClass}:${f.findingId}`));
  const remediated=[];
  for(const item of plan.remediatedFindings||[]){
    const k=`${item.sourceClass}:${item.findingId}`;
    if(item.ruleId!=='zizmor/dependabot-cooldown'||item.path!=='.github/dependabot.yml'||item.priorDisposition!=='APPLICABLE')throw new Error(`invalid R2A historical identity ${k}`);
    if(currentIds.has(k))throw new Error(`R2A remediated finding still present ${k}`);
    remediated.push(stable({...item,currentStatus:'REMEDIATED_BY_DEPENDABOT_COOLDOWN_AND_ABSENT_FROM_CURRENT_ZIZMOR'}));
  }
  if(remediated.length!==3)throw new Error(`R2A must retain exactly 3 historical findings, got ${remediated.length}`);
  const payload=stable({
    schemaVersion:'M6_PR_E_E3_R2A_DEPENDABOT_COOLDOWN_REMEDIATION_EVIDENCE_V1',
    repository:e4.repository,
    commitSha:e4.commitSha,
    sourceE4CanonicalSha256:e4.contentSha256,
    priorAcceptedHead:plan.priorAcceptedHead,
    priorI4FindingSetSha256:plan.priorI4FindingSetSha256,
    sourceDependabotBlobSha:plan.sourceDependabotBlobSha,
    currentDependabotBlobSha,
    cooldownDefaultDays:plan.cooldownDefaultDays,
    remediatedFindings:remediated,
    currentZizmorFindingCount:z.findingCount,
    currentRuleCounts:stable(Object.fromEntries(Object.keys(plan.expectedCurrentRuleCounts).sort().map(k=>[k,counts[k]||0]))),
    releaseBlocked:true,
    reasonCodes:['AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE','E3_SCANNER_FINDINGS_UNRESOLVED',...(z.findingCount?['E3_APPLICABLE_FINDINGS_REQUIRE_REMEDIATION']:[])]
  });
  return stable({...payload,contentSha256:sha256(canonical(payload))});
}
export const canonicalDependabotCooldownRemediation=canonical;
