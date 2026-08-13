import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const contract = path.join(root, 'docs/m6/M6_PR_E_E3_VULNERABILITY_APPLICABILITY_AND_REACHABILITY.md');
const baselineFile = path.join(root, 'docs/m6/m6-pr-e-e3-finding-source-baseline.json');
const dispositions = ['APPLICABLE','NOT_APPLICABLE','UNREACHABLE','MITIGATED','ACCEPTED_WITH_EXPIRY','UNRESOLVED','EVIDENCE_UNAVAILABLE'];
const highRisk = new Set(['TENANT_ISOLATION','AUTHORIZATION','SECRET','RCE','INJECTION','DESERIALIZATION','SSRF','WORKFLOW_SUPPLY_CHAIN','EVIDENCE_INTEGRITY']);
const text = (file) => { assert.equal(existsSync(file), true); return readFileSync(file, 'utf8'); };

function validateFinding(f) {
  for (const k of ['findingId','sourceClass','upstreamSeverity','severityBand','severityBandEvidence','componentRef','scope','deploymentScopeEvidence','owner','reviewer','decisionTime','disposition']) assert.ok(String(f[k] ?? '').trim(), `${k} required`);
  assert.ok(dispositions.includes(f.disposition));
  assert.ok(['CRITICAL','HIGH','MEDIUM','LOW','UNKNOWN'].includes(f.severityBand));
  assert.ok(Array.isArray(f.dependencyPath) && f.dependencyPath.length > 0 && f.dependencyPath.at(-1) === f.componentRef);
  for (const k of ['packaged','loaded','invoked','externallyReachable']) {
    assert.ok(f.reachability?.[k] && 'value' in f.reachability[k] && String(f.reachability[k].evidence ?? '').trim(), `${k} evidence required`);
  }
  if (f.disposition === 'UNREACHABLE') {
    assert.ok(['packaged','loaded','invoked','externallyReachable'].every((k) => typeof f.reachability[k].value === 'boolean'));
    assert.ok(['packaged','loaded','invoked','externallyReachable'].some((k) => f.reachability[k].value === false));
  }
  if (f.disposition === 'MITIGATED') assert.ok(f.mitigations?.length && f.mitigations.every((m) => m.control && m.evidence));
  if (f.disposition === 'ACCEPTED_WITH_EXPIRY') {
    const e=f.exception||{}; for (const k of ['findingId','exactScope','rationale','owner','approver','createdAt','expiresAt','revalidationTrigger']) assert.ok(e[k]);
    assert.equal(e.findingId,f.findingId); assert.equal(e.autoRenew,false); assert.ok(e.compensatingControls?.length); assert.ok(Date.parse(e.expiresAt)>Date.parse(e.createdAt));
  }
  return ['CRITICAL','HIGH'].includes(f.severityBand) || (f.impacts||[]).some((x)=>highRisk.has(x));
}

const fixture = (overrides={}) => ({findingId:'FIX-1',sourceClass:'FIXTURE',upstreamSeverity:'CVSS raw',severityBand:'MEDIUM',severityBandEvidence:'source mapping',componentRef:'pkg:maven/x/y@1?type=jar',dependencyPath:['pkg:maven/root/app@1?type=jar','pkg:maven/x/y@1?type=jar'],scope:'runtime/server',deploymentScopeEvidence:'packaged server fixture',reachability:{packaged:{value:true,evidence:'package list'},loaded:{value:true,evidence:'class load'},invoked:{value:false,evidence:'bounded call graph'},externallyReachable:{value:false,evidence:'endpoint graph'}},impacts:[],mitigations:[],owner:'Java Dependency Owner',reviewer:'Application Security Reviewer',decisionTime:'2026-08-11T00:00:00Z',disposition:'UNREACHABLE',...overrides});

test('E3 contract remains fail-closed and does not claim E3 or E4 acceptance',()=>{
  const body=text(contract); for(const marker of ['NO_FINDING_INVENTORY != ZERO_FINDINGS','NO_CALLSITE_MATCH != UNREACHABLE','INCOMPLETE_FINDING_INTAKE_BLOCKS_E3_CLOSURE','M6_PR_E_E3_CLOSURE_NOT_ACCEPTED','PRB_16_REMAINS_OPEN','PRB_17_REMAINS_OPEN','NO_E4_SCANNER_IMPLEMENTATION_IN_E3','NO_READY','NO_MERGE','AI_IS_NOT_AN_OPERATOR']) assert.ok(body.includes(marker));
  assert.doesNotMatch(body,/M6_PR_E_E3_ACCEPTED\b|M6_PR_E_E4_ACCEPTED/);
});

test('E3 source baseline retains unavailable item counts as null, never zero',()=>{
  const b=JSON.parse(text(baselineFile)); assert.equal(b.sourceHead,'73013b5f3d36f76d39849c3f40fc7db057250507'); assert.equal(b.inheritedE2Digest,'babe199f7f7dd5dfe4e5b336ff8df9c412e936f70c54e49ab83d44e95a84835a'); assert.equal(b.sources.length,4); for(const s of b.sources){assert.equal(s.availability,'EVIDENCE_UNAVAILABLE');assert.equal(s.itemCount,null);assert.equal(s.complete,false);} assert.equal(b.zeroFindingsClaimPermitted,false); assert.equal(b.findingInventoryComplete,false);
});

test('E3 dispositions require positive reachability, mitigation and exception evidence',()=>{
  assert.equal(validateFinding(fixture()),false);
  assert.throws(()=>validateFinding(fixture({reachability:{}})),/evidence required/);
  assert.throws(()=>validateFinding(fixture({disposition:'MITIGATED',mitigations:[]})));
  assert.throws(()=>validateFinding(fixture({disposition:'ACCEPTED_WITH_EXPIRY',exception:{autoRenew:true}})));
});

test('E3 release gating preserves raw severity and blocks high-risk unresolved findings',()=>{
  assert.equal(validateFinding(fixture({upstreamSeverity:'raw upstream text',severityBand:'LOW',impacts:['AUTHORIZATION'],disposition:'UNRESOLVED'})),true);
  assert.equal(validateFinding(fixture({upstreamSeverity:'CVSS:3.1/...',severityBand:'HIGH',disposition:'UNRESOLVED'})),true);
});

test('E3 retains an exact-head machine baseline without manufacturing findings',()=>{
  const b=JSON.parse(text(baselineFile)); let head=process.env.M6_PR_E_E3_HEAD_SHA;
  if(process.env.GITHUB_ACTIONS==='true'){const e=JSON.parse(text(process.env.GITHUB_EVENT_PATH));head=e.pull_request.head.sha;}
  head=head||'2222222222222222222222222222222222222222'; assert.match(head,/^[0-9a-f]{40}$/);
  const p={schemaVersion:'M6_PR_E_E3_APPLICABILITY_V1',repository:b.repository,commitSha:head,sourceHead:b.sourceHead,inheritedE2Digest:b.inheritedE2Digest,findingInventory:{complete:false,itemCount:null,knownFindingCount:0},decisions:[],releaseBlocked:true,reasonCodes:['FINDING_INVENTORY_INCOMPLETE']};
  const digest=createHash('sha256').update(JSON.stringify(p)).digest('hex'); assert.match(digest,/^[0-9a-f]{64}$/);
  if(process.env.GITHUB_ACTIONS==='true'){console.log('M6_PR_E_E3_CANONICAL_SHA256='+digest);console.log('M6_PR_E_E3_APPLICABILITY_BEGIN');console.log(JSON.stringify({...p,contentSha256:digest}));console.log('M6_PR_E_E3_APPLICABILITY_END');}
});
