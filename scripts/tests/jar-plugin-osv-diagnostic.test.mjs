import assert from 'node:assert/strict';
import test from 'node:test';
import { readJarPluginDiagnostic, verifyJarPluginDiagnostic } from './fixtures/jar-plugin-osv-diagnostic-verifier.mjs';
const retained = readJarPluginDiagnostic();
const fixture = () => structuredClone(retained);
test('JAR actual diagnostic covers all467 targets and removes exactly one record with zero added/changed records', () => {
  assert.deepEqual(verifyJarPluginDiagnostic(fixture()), {targets:467,findings:10,zeroFindingTargets:458,removed:1,added:0,changed:0,scannerCliExecuted:false,releaseBlocked:true});
});
for(const [name,mutate] of [
  ['missing zero-result target',d=>{const i=d.results.findIndex(r=>!r.advisories.length);d.results.splice(i,1);d.coverage.targets.splice(i,1);}],
  ['wrong source tree',d=>{d.binding.sourceBaseTree='0'.repeat(40);}],
  ['wrong accepted E4',d=>{d.binding.baselineE4RawSha256='0'.repeat(64);}],
  ['wrong candidate graph',d=>{d.summary.candidateGraphDigest='0'.repeat(64);}],
  ['wrong baseline target count',d=>{d.binding.baselineTargetCount=467;}],
  ['missing removed target',d=>{d.binding.removedTargets.pop();}],
  ['missing added target',d=>{d.binding.addedTargets.pop();}],
  ['invented retained attribution delta',d=>{d.binding.changedRetainedAttribution.push(d.targets[0]);}],
  ['changed target scope',d=>{d.targets[0].scopes=['test'];}],
  ['shifted positional attribution',d=>{d.results[0].queryIndex=1;}],
  ['incomplete zero-result response',d=>{d.results.find(r=>!r.advisories.length).complete=false;}],
  ['unconsumed pagination token',d=>{d.results[0].pageTokens.push('pending');}],
  ['missing page receipt',d=>{d.results[0].pageReceipts=[];}],
  ['changed query bytes',d=>{d.files['querybatch.request.json']+='\n';}],
  ['changed scanner input bytes',d=>{d.files['equivalent-scanner-input.json']+='\n';}],
  ['changed derivation',d=>{d.rows[0].scope='test';}],
  ['added finding',d=>{d.findings.push(structuredClone(d.findings[0]));}],
  ['missing retained finding',d=>{d.findings.pop();}],
  ['changed retained fixed versions',d=>{d.findings[0].fixedVersions=['999'];}],
  ['changed retained severity',d=>{d.findings[0].upstreamSeverity=[{type:'CVSS_V3',score:'changed'}];}],
  ['changed retained aliases',d=>{d.findings[0].aliases=['CVE-2099-9999'];}],
  ['changed retained references',d=>{d.findings[0].componentRefs=[];}],
  ['altered removed finding',d=>{d.comparison.removed[0].package.version='4.0.1';}],
  ['forged zero-added comparison',d=>{d.comparison.added.push(d.findings[0]);}],
  ['changed baseline record',d=>{d.baseline[0].fixedVersions=['999'];}],
  ['failed HTTP status',d=>{d.http.receipts[0].status=500;}],
  ['foreign HTTP destination',d=>{d.http.receipts[0].officialHost='example.invalid';}],
  ['incorrect batch hash',d=>{d.http.receipts.find(r=>r.method==='POST').requestBodySha256='0'.repeat(64);}],
  ['duplicate detail receipt',d=>{d.http.receipts[1]=structuredClone(d.http.receipts[2]);}],
  ['public raw transport header',d=>{d.http.receipts[0].headers=[];}],
  ['incomplete detail collection',d=>{d.execution.validatedDetailCount=8;}],
  ['wrong advisory timestamp',d=>{d.precision.pairs[0].detailModified='2000-01-01T00:00:00Z';}],
  ['false nanosecond equality',d=>{d.precision.pairs[0].subMicrosecondChangeExcluded=true;}],
  ['false normalized-source-class equivalence',d=>{d.comparison.normalizedSourceClassCompatibilityOnly=false;}],
  ['false raw metadata equality',d=>{d.comparison.rawMetadataEqualityClaimed=true;}],
  ['false direct collection identity',d=>{d.comparison.collectionKind='CANONICAL_SCANNER';}],
  ['false private raw retention',d=>{d.summary.rawApiResponsesRetainedPrivately=false;}],
  ['false scanner CLI claim',d=>{d.summary.scannerCliExecuted=true;}],
  ['false canonical CI claim',d=>{d.summary.canonicalCiEvidence=true;}],
  ['false graph admission',d=>{d.execution.graphAdmitted=true;}],
  ['cleared release block',d=>{d.summary.releaseBlocked=false;}],
  ['transferred disposition',d=>{d.binding.reviewDispositionTransferred=true;}],
  ['extra raw public file',d=>{d.files['raw-response.json']='{}';}],
])test('JAR diagnostic rejects '+name,()=>{const d=fixture();mutate(d);assert.throws(()=>verifyJarPluginDiagnostic(d));});
