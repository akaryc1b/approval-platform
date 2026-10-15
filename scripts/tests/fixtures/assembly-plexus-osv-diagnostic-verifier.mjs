// Read-only complete diagnostic verification. No scanner, network, subprocess or historical callback replay.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { acceptedE2GraphProjection } from '../../security/m6-pr-e-e2-generate-sbom.mjs';
import { osvInputFromE2 } from '../../security/osv-scan-coverage.mjs';
import { ASSEMBLY_PLEXUS_GRAPH, readAssemblyPlexusManifest } from '../../security/assembly-plexus-graph-transition.mjs';
export const canonical = value => JSON.stringify(stable(value));
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
export const sha = value => createHash('sha256').update(value).digest('hex');
const INVENTORY = 'fed5a0564c5087444fed185d5a67fc578705f62716e4351fc3a6feeaba6dc987';
const QUERY = '41b20936a18d27b350a80a8ff222791bdb5d44c515384f7f853ad1768057476e';
const BASELINE = '130810230d5906105943b8bea0b095da7c25c81c0599d834d85dbcbc14417796';
const HEAD = '9b03965b8c7f6015301fd3db729172cd0f2cca3b';
const root = new URL('../../../', import.meta.url), directory = new URL('docs/operations/assembly-plexus-evidence/osv-diagnostic/', root);
const names = ['README.md','baseline-normalized-osv-findings.json','comparison-to-main1857.json','diagnostic-target-coverage.json',
  'equivalent-scanner-input.json','execution.complete.json','http-provenance.json','independent-derivation-rows.json',
  'independent-targets.json','input-derivation.json','normalized-osv-findings.json','query-target-results.json',
  'querybatch.request.json','timestamp-precision-comparison.json','verified-osv-summary.json'].sort();
const key = p => [p.ecosystem, p.name, p.version].join('\0');
const targetsFrom = e2 => [...osvInputFromE2(e2).lookup.values()].sort((a,b) =>
  `${a.package.ecosystem}:${a.package.name}:${a.package.version}`.localeCompare(`${b.package.ecosystem}:${b.package.name}:${b.package.version}`))
  .map(t => ({ package:t.package, componentRefs:[...t.componentRefs].sort(), scopes:[...t.scopes].sort() }));
const fields = (v, expected) => assert.deepEqual(Object.keys(v).sort(), [...expected].sort(), 'unexpected diagnostic field');
export function readAssemblyPlexusDiagnostic() {
  const bytes = readFileSync(new URL('public-file-checksums.sha256', directory)); assert.equal(sha(bytes), INVENTORY);
  const files = {};
  for (const line of bytes.toString().trim().split('\n')) {
    const match = line.match(/^([a-f0-9]{64})  ([A-Za-z0-9_.-]+)$/); assert.ok(match);
    assert.equal(Object.hasOwn(files, match[2]), false);
    const file = readFileSync(new URL(match[2], directory)); assert.equal(sha(file), match[1]); files[match[2]] = file.toString();
  }
  assert.deepEqual(Object.keys(files).sort(), names); assert.deepEqual(readdirSync(directory).sort(), [...names,'public-file-checksums.sha256'].sort());
  const json = name => JSON.parse(files[name]), manifest = readAssemblyPlexusManifest();
  const pinned = ref => { const b = readFileSync(new URL(ref.path,root)); assert.equal(sha(b),ref.rawSha256); return JSON.parse(b); };
  assert.equal(sha(readFileSync(new URL('pom.xml',root))),manifest.capture.sourcePom.rawSha256);
  return { files, manifest, e2:pinned(manifest.capture.e2), priorE2:pinned(manifest.base.e2),
    binding:json('input-derivation.json'), findings:json('normalized-osv-findings.json'), baseline:json('baseline-normalized-osv-findings.json'),
    targets:json('independent-targets.json'), rows:json('independent-derivation-rows.json'), results:json('query-target-results.json'),
    coverage:json('diagnostic-target-coverage.json'), summary:json('verified-osv-summary.json'), comparison:json('comparison-to-main1857.json'),
    execution:json('execution.complete.json'), http:json('http-provenance.json'), precision:json('timestamp-precision-comparison.json') };
}
export function verifyAssemblyPlexusDiagnostic(d) {
  const {files,manifest,e2,priorE2,binding,findings,baseline,targets,rows,results,coverage,summary,comparison,execution,http,precision}=d;
  assert.deepEqual(Object.keys(files).sort(),names);
  for(const e of [e2,priorE2]) { const {contentSha256,...body}=e; assert.equal(sha(canonical(body)),contentSha256); assert.equal(e.commitSha,HEAD); }
  assert.deepEqual(binding.sourceE2,manifest.capture.e2); assert.deepEqual(binding.sourcePom,manifest.capture.sourcePom); assert.deepEqual(binding.baselineE2,manifest.base.e2);
  assert.equal(e2.contentSha256,manifest.capture.e2.contentSha256);assert.equal(priorE2.contentSha256,manifest.base.e2.contentSha256);
  assert.equal(sha(canonical(acceptedE2GraphProjection(priorE2))),manifest.base.graphDigest);
  assert.equal(binding.sourceBaseHead,HEAD); assert.equal(binding.baselineRunNumber,1857);
  assert.equal(sha(canonical(acceptedE2GraphProjection(e2))),ASSEMBLY_PLEXUS_GRAPH); assert.equal(binding.candidateGraphDigest,ASSEMBLY_PLEXUS_GRAPH);
  const expected=targetsFrom(e2),previous=targetsFrom(priorE2),input=osvInputFromE2(e2);
  assert.equal(expected.length,468);assert.equal(previous.length,469);assert.deepEqual(targets,expected);
  assert.equal(files['equivalent-scanner-input.json'],JSON.stringify(input.scannerInput));
  const query=JSON.stringify({queries:expected.map(({package:p})=>({package:{name:p.name,ecosystem:p.ecosystem},version:p.version}))});
  assert.equal(files['querybatch.request.json'],query);assert.equal(sha(query),QUERY);assert.equal(binding.exactQueryBytesSha256,QUERY);
  assert.equal(binding.equivalentScannerInputBytesSha256,sha(files['equivalent-scanner-input.json']));assert.equal(binding.inputTargetCanonicalSha256,sha(canonical(targets)));
  const oldTargets=new Map(previous.map(t=>[key(t.package),t])),nowTargets=new Map(targets.map(t=>[key(t.package),t]));
  const removed=previous.filter(t=>!nowTargets.has(key(t.package)));assert.deepEqual(binding.removedTargets,removed);assert.equal(removed.length,1);
  assert.deepEqual(removed[0].package,{name:'org.codehaus.plexus:plexus-utils',version:'4.0.0',ecosystem:'Maven'});
  assert.deepEqual(binding.addedTargets,[]);assert.deepEqual(binding.changedRetainedAttribution,[]);
  for(const target of targets)assert.deepEqual(target,oldTargets.get(key(target.package)),'retained attribution changed');
  const derived=[],add=(p,ref,scope,source)=>derived.push({package:p,componentRef:ref,scope,source});
  for(const c of e2.maven.components)if(c.group!=='io.github.akaryc1b.approval')add({name:c.group+':'+c.name,version:c.version,ecosystem:'Maven'},c.bomRef,c.scope||'dependency','maven.components');
  for(const b of e2.maven.importedBoms)add({name:b.group+':'+b.name,version:b.version,ecosystem:'Maven'},`pkg:maven/${b.group}/${b.name}@${b.version}?type=pom`,'import','maven.importedBoms');
  for(const c of e2.maven.resolvedPluginCoordinates){const p=c.split(':');add({name:p[0]+':'+p[1],version:p.at(-1),ecosystem:'Maven'},'maven-plugin:'+c,'build-plugin','maven.resolvedPluginCoordinates');}
  for(const n of e2.pnpm.external)add({name:n.name,version:n.version,ecosystem:'npm'},'pkg:npm/'+encodeURIComponent(n.name)+'@'+n.version,n.scope||'unknown','pnpm.external');
  assert.deepEqual(rows,derived);
  assert.equal(results.length,468);assert.equal(coverage.targets.length,468);const ids=new Set();let pairs=0,affected=0;
  for(const [i,t]of targets.entries()){
    const r=results[i],c=coverage.targets[i];assert.equal(r.queryIndex,i);assert.equal(c.queryIndex,i);
    for(const v of [r,c])assert.deepEqual({package:v.package,componentRefs:v.componentRefs,scopes:v.scopes},t);
    assert.equal(r.complete,true);assert.equal(c.allPagesRetrieved,true);assert.deepEqual(r.pageTokens,[]);
    assert.deepEqual(r.pageReceipts,['http/batch-1.receipt.json']);assert.deepEqual(c.responseReceipts,r.pageReceipts);
    const advisoryIds=r.advisories.map(v=>v.id).sort();assert.equal(new Set(advisoryIds).size,advisoryIds.length);assert.deepEqual(c.advisoryIds,advisoryIds);
    const matched=findings.filter(f=>key(f.package)===key(t.package));assert.deepEqual(matched.map(f=>f.upstreamFindingId).sort(),advisoryIds);
    for(const f of matched){assert.equal(f.sourceClass,'E4_OSV_SCANNER');assert.equal(f.findingId,sha(['OSV',f.upstreamFindingId,...key(f.package).split('\0')].join('\0')));assert.deepEqual(f.componentRefs,t.componentRefs);assert.deepEqual(f.scopes,t.scopes);ids.add(f.upstreamFindingId);}
    pairs+=advisoryIds.length;affected+=advisoryIds.length>0?1:0;
  }
  assert.equal(pairs,11);assert.equal(affected,10);assert.equal(ids.size,10);assert.equal(findings.length,11);
  assert.equal(sha(canonical(baseline)),BASELINE);assert.equal(baseline.length,12);
  const old=new Map(baseline.map(f=>[f.findingId,f]));for(const f of findings)assert.deepEqual(f,old.get(f.findingId),'all retained normalized fields');
  const removedFindings=baseline.filter(f=>!findings.some(current=>current.findingId===f.findingId));assert.equal(removedFindings.length,1);
  assert.equal(removedFindings[0].findingId,'5e63544c0ea7c20e2d9b793649021cdfc3cfbaf5253dc618b7dd04824bd1118f');
  assert.deepEqual(comparison.removed,removedFindings);assert.deepEqual(comparison.added,[]);assert.deepEqual(comparison.changed,[]);
  assert.deepEqual(comparison.unchangedFindingIds,findings.map(f=>f.findingId));assert.equal(comparison.baselineCount,12);assert.equal(comparison.candidateCount,11);
  assert.equal(comparison.baselineCanonicalSha256,BASELINE);assert.equal(comparison.candidateCanonicalSha256,sha(canonical(findings)));
  assert.deepEqual(summary.comparison,{prior:12,current:11,removed:1,added:0,changed:0,unchanged:11});
  fields(http,['schemaVersion','requestCount','rawBodiesAndHeadersIncluded','scannerCliExecuted','transportUrlsIncluded','receipts']);
  assert.equal(http.requestCount,11);assert.equal(http.receipts.length,11);assert.equal(http.rawBodiesAndHeadersIncluded,false);assert.equal(http.transportUrlsIncluded,false);
  const receiptFields=['label','method','status','startedAtUtc','completedAtUtc','requestBodySha256','responseBodyBytes','responseBodySha256','error','officialHost','endpoint','advisoryId','requestBodyBytes','sourceReceipt','sourceReceiptSha256'];
  const posts=http.receipts.filter(r=>r.method==='POST'),gets=http.receipts.filter(r=>r.method==='GET');assert.equal(posts.length,1);assert.equal(gets.length,10);
  assert.equal(posts[0].endpoint,'/v1/querybatch');assert.equal(posts[0].requestBodySha256,QUERY);assert.equal(posts[0].requestBodyBytes,Buffer.byteLength(query));
  assert.deepEqual(gets.map(r=>r.advisoryId).sort(),[...ids].sort());assert.equal(new Set(http.receipts.map(r=>r.sourceReceipt)).size,11);
  for(const r of http.receipts){fields(r,receiptFields);assert.equal(r.status,200);assert.equal(r.error,null);assert.equal(r.officialHost,'api.osv.dev');assert.equal(r.endpoint,r.method==='POST'?'/v1/querybatch':'/v1/vulns/'+r.advisoryId);assert.equal(r.sourceReceipt,'http/'+r.label+'.receipt.json');assert.match(r.responseBodySha256,/^[a-f0-9]{64}$/);assert.match(r.sourceReceiptSha256,/^[a-f0-9]{64}$/);assert.ok(r.responseBodyBytes>0&&r.responseBodyBytes<=16777216);assert.ok(Date.parse(r.startedAtUtc)>=Date.parse(execution.startedAtUtc)&&Date.parse(r.completedAtUtc)>=Date.parse(r.startedAtUtc)&&Date.parse(r.completedAtUtc)<=Date.parse(execution.completedAtUtc));}
  assert.equal(http.receipts.reduce((n,r)=>n+r.responseBodyBytes,0),execution.responseBytesRetained);
  assert.equal(execution.status,'COMPLETE_HTTP_QUERY_AND_DETAILS');assert.equal(execution.error,null);assert.equal(execution.allTargetsAndDetailsComplete,true);
  for(const k of ['inputTargetCount','completeTargetCount'])assert.equal(execution[k],468);
  assert.equal(execution.validatedDetailCount,10);assert.equal(execution.packageAdvisoryPairs,11);assert.equal(execution.zeroFindingTargetCount,458);assert.equal(execution.httpRequestCount,11);assert.equal(execution.querySha256,QUERY);
  assert.equal(execution.automaticRetries,0);assert.equal(execution.inventoryQueryRepeated,false);assert.equal(execution.successfulDetailRepeated,false);
  assert.equal(precision.pairs.length,10);assert.equal(precision.rawTimestampsUnmodified,true);assert.deepEqual(precision.pairs.map(p=>p.id).sort(),[...ids].sort());
  for(const p of precision.pairs){const a=p.queryModified.replace(/Z$/,''),b=p.detailModified.replace(/Z$/,'');assert.ok(b.startsWith(a));assert.equal(p.exactStringEqual,p.queryModified===p.detailModified);assert.equal(p.matchAtBatchPrecision,true);assert.equal(p.subMicrosecondChangeExcluded,false);for(const r of results)for(const v of r.advisories)if(v.id===p.id)assert.equal(v.modified,p.queryModified);}
  assert.equal(summary.status,'COMPLETE_ACTUAL_API_DIAGNOSTIC');assert.equal(summary.inputPackageCount,468);assert.equal(summary.completeResultPackageCount,468);assert.equal(summary.findingsCount,11);assert.equal(summary.zeroFindingPackageTargetCount,458);assert.equal(summary.affectedPackageTargetCount,10);assert.equal(summary.normalizedFindingsCanonicalSha256,sha(canonical(findings)));
  for(const v of [summary,binding,comparison,execution]){assert.equal(v.scannerCliExecuted,false);assert.equal(v.graphAdmitted,false);assert.equal(v.releaseBlocked,true);}
  for(const v of [summary,coverage,execution])assert.equal(v.canonicalCiEvidence,false);
  for(const v of [summary,binding,execution])assert.equal(v.reviewDispositionTransferred,false);
  for(const k of ['allInputPackagesQueried','allAdvisoryDetailsRetrieved','allNormalizedFieldsCompared'])assert.equal(summary[k],true);
  assert.equal(binding.queryExecutedOnCommittedCandidateClaimed,false);assert.equal(binding.canonicalE4,false);assert.equal(summary.rawAdvisoryMetadataEqualityClaimed,false);assert.equal(summary.sourceOrCredentialsTransmitted,false);
  return {targets:468,findings:11,zeroFindingTargets:458,removed:1,added:0,changed:0,scannerCliExecuted:false,releaseBlocked:true};
}
