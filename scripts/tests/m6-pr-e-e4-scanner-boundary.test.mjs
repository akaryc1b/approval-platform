import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { buildScannerFindingIntake } from '../security/m6-pr-e-e3-ingest-e4.mjs';
import { applyReviewedFindings } from '../security/m6-pr-e-e3-apply-reviewed-findings.mjs';
import { applyRuntimeDeploymentReviews } from '../security/m6-pr-e-e3-apply-runtime-deployment-reviews.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const contract=path.join(root,'docs/m6/M6_PR_E_E4_CODE_SECRET_DEPENDENCY_SCANNING_EVIDENCE.md');
const baselineFile=path.join(root,'docs/m6/m6-pr-e-e4-scanner-baseline.json');
const intakeContract=path.join(root,'docs/m6/M6_PR_E_E3_E4_SCANNER_FINDING_INTAKE.md');
const i2Contract=path.join(root,'docs/m6/M6_PR_E_E3_I2_SEMGREP_SOURCE_AUDIT.md');
const i2ReviewFile=path.join(root,'docs/m6/m6-pr-e-e3-i2-reviewed-findings.json');
const i3Contract=path.join(root,'docs/m6/M6_PR_E_E3_I3_OSV_RUNTIME_DEPLOYMENT_PRECONDITION_AUDIT.md');
const i3ReviewFile=path.join(root,'docs/m6/m6-pr-e-e3-i3-reviewed-findings.json');
const scanner=path.join(root,'scripts/security/m6-pr-e-e4-scan.mjs');
const text=f=>{assert.equal(existsSync(f),true,`${f} must exist`);return readFileSync(f,'utf8');};

test('E4 contract keeps scanner evidence separate from authoritative alerts and E3 disposition',()=>{
  const body=text(contract);for(const m of ['SCANNER_SUCCESS != ZERO_FINDINGS','ZERO_SCANNER_FINDINGS != AUTHORITATIVE_GITHUB_ZERO_ALERTS','SCANNER_FINDING != E3_DISPOSITION','RAW_SECRET_REPORT_MUST_NOT_BE_RETAINED','MUTABLE_REMOTE_RULESET_PROHIBITED','FINDINGS_REQUIRE_E3_APPLICABILITY_REACHABILITY_TRIAGE','PRB_16_REMAINS_OPEN','PRB_17_REMAINS_OPEN','NO_READY','NO_MERGE','AI_IS_NOT_AN_OPERATOR'])assert.ok(body.includes(m),m);
  assert.doesNotMatch(body,/M6_PR_E_E3_ACCEPTED\b|M6_PR_E_E4_ACCEPTED\b/);
});

test('E4 scanner baseline pins all tool and rules identities without suppressions',()=>{
  const b=JSON.parse(text(baselineFile));assert.equal(b.sourceHead,'28b7cca970aba1fcaab05aceb200a2987042d973');assert.equal(b.inheritedE2ContentDigestAtE2Correction,'babe199f7f7dd5dfe4e5b336ff8df9c412e936f70c54e49ab83d44e95a84835a');assert.equal(b.inheritedE2GraphDigest,'0b6868f057a72fb8c7a4c9d0529f4469381f0024f403873c6d92121e4b34ee0a');
  assert.deepEqual([b.scanners.osv.version,b.scanners.gitleaks.version,b.scanners.zizmor.version,b.scanners.semgrep.version],['2.5.0','8.30.1','1.26.1','1.172.0']);
  for(const s of Object.values(b.scanners))assert.match(s.sourceCommit,/^[0-9a-f]{40}$/);
  assert.match(b.scanners.osv.installation.goLinuxAmd64Sha256,/^[0-9a-f]{64}$/);assert.match(b.scanners.gitleaks.installation.sha256,/^[0-9a-f]{64}$/);assert.match(b.scanners.zizmor.installation.sha256,/^[0-9a-f]{64}$/);assert.match(b.scanners.semgrep.rules.commit,/^[0-9a-f]{40}$/);
  assert.deepEqual(b.suppressionPolicy.entries,[]);assert.equal(b.suppressionPolicy.broadPathExclusionsPermitted,false);assert.equal(b.suppressionPolicy.severityDowngradePermitted,false);
});

test('E4 implementation redacts raw secret/code evidence and removes token-bearing scanner environments',()=>{
  const s=text(scanner);assert.match(s,/--redact=100/);assert.match(s,/--log-opts=--all/);assert.match(s,/rawReportRetained:false/);assert.match(s,/candidateSecretMaterialRetained:false/);assert.match(s,/sourceSnippetRetained:false/);assert.match(s,/delete e\[k\]/);assert.match(s,/GH_TOKEN/);assert.match(s,/GITHUB_TOKEN/);assert.match(s,/ZIZMOR_GITHUB_TOKEN/);assert.match(s,/SEMGREP_APP_TOKEN/);
  assert.match(s,/--offline/);assert.match(s,/--strict-collection/);assert.match(s,/--metrics=off/);assert.match(s,/--disable-version-check/);assert.match(s,/semgrep-rules\.git/);assert.doesNotMatch(s,/p\/default|--config\s+auto|SEMGREP_APP_TOKEN\s*=/);
  assert.doesNotMatch(s,/x\.(?:Secret|Match)\b|extra\?\.lines|extra\?\.metavars/i);
});

test('E4 scanner findings remain evidence inputs instead of scanner execution failures',()=>{
  const s=text(scanner);assert.match(s,/allow:\[1\]/);assert.match(s,/scannerFindingTriageRequired/);assert.match(s,/E4_SCANNER_FINDINGS_REQUIRE_E3_TRIAGE/);assert.match(s,/authoritativeGitHubInventoryStillUnavailable:true/);assert.match(s,/workstreamReleaseBlocked:true/);
});


test('E3 intake contract and builder keep all scanner findings unresolved and Secret-safe',()=>{
  const body=text(intakeContract);for(const m of ['SCANNER_COVERAGE_COMPLETE != AUTHORITATIVE_INVENTORY_COMPLETE','SCANNER_FINDING != E3_DISPOSITION','RAW_SECRET_REPORT_MUST_NOT_BE_RECONSTRUCTED','ALL_SCANNER_FINDINGS_INITIAL_DISPOSITION_UNRESOLVED','M6_PR_E_E3_CLOSURE_NOT_ACCEPTED','PRB_16_REMAINS_OPEN','PRB_17_REMAINS_OPEN'])assert.ok(body.includes(m),m);
  const finding=(sourceClass,findingId,extra={})=>({sourceClass,findingId,...extra});
  const evidence={repository:'akaryc1b/approval-platform',commitSha:'3'.repeat(40),contentSha256:'a'.repeat(64),e2GraphDigest:'b'.repeat(64),allScannersCompleted:true,rawScannerReportsRetained:false,candidateSecretMaterialRetained:false,totalFindingCount:4,scanners:{
    osv:{scanCompleted:true,rawReportRetained:false,findingCount:1,findings:[finding('E4_OSV_SCANNER','OSV-1',{upstreamFindingId:'GHSA-fixture',aliases:[],package:{ecosystem:'Maven',name:'org.example:lib',version:'1.0.0'},componentRefs:['pkg:maven/org.example/lib@1.0.0?type=jar'],scopes:['compile'],upstreamSeverity:[{type:'CVSS_V3',score:'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N'}],fixedVersions:[]})]},
    gitleaks:{scanCompleted:true,rawReportRetained:false,candidateSecretMaterialRetained:false,findingCount:1,findings:[finding('E4_GITLEAKS','GL-1',{ruleId:'generic-api-key',path:'fixture.txt',startLine:1,endLine:1,commit:'4'.repeat(40),fingerprint:'fixture-fingerprint'})]},
    zizmor:{scanCompleted:true,rawReportRetained:false,findingCount:1,findings:[finding('E4_ZIZMOR','ZZ-1',{ruleId:'zizmor/unpinned-uses',upstreamSeverity:'error',path:'.github/workflows/a.yml',startLine:1})]},
    semgrep:{scanCompleted:true,rawReportRetained:false,sourceSnippetRetained:false,findingCount:1,findings:[finding('E4_SEMGREP','SG-1',{ruleId:'rules.fixture',upstreamSeverity:'WARNING',path:'/src/server-modules/x/A.java',startLine:5,cwe:[],owasp:[],category:'security'})]}
  }};
  const intake=buildScannerFindingIntake(evidence,{snapshotTime:'2026-08-11T04:00:00Z'});assert.equal(intake.findingInventory.itemCount,4);assert.equal(intake.decisions.length,4);assert.equal(intake.summary.unresolvedCount,4);assert.equal(intake.summary.highRiskUnresolvedCount,2);assert.equal(intake.summary.releaseBlocked,true);for(const f of intake.decisions){assert.equal(f.disposition,'UNRESOLVED');assert.equal(f.severityBand,'UNKNOWN');assert.ok(['packaged','loaded','invoked','externallyReachable'].every(k=>f.reachability[k].value===null));assert.doesNotMatch(JSON.stringify(f),/"(?:Secret|Match)"\s*:/i);}
});


test('E3-I2 reviews exactly three Semgrep findings without severity downgrade suppression or exception',()=>{
  const body=text(i2Contract);for(const m of ['SOURCE_MATCH != APPLICABLE','FALSE_POSITIVE_REQUIRES_POSITIVE_ABSENT_PRECONDITION_EVIDENCE','TYPESCRIPT_TYPE != RUNTIME_INPUT_VALIDATION','UNRESOLVED_PROTOTYPE_POLLUTION_REMAINS_OPEN','NO_SUPPRESSION','NO_SEVERITY_DOWNGRADE','NO_EXCEPTION','M6_PR_E_E3_CLOSURE_NOT_ACCEPTED'])assert.ok(body.includes(m),m);
  const r=JSON.parse(text(i2ReviewFile));assert.equal(r.reviewBasisHead,'234ed3d41b049ca9475e58e29e1204587a992693');assert.equal(r.reviewBasisIntakeCanonicalSha256,'9f0f5b9ecd4604492a1b250cae3b8d21bcd658efd4d9646b46aea1250436fbec');assert.equal(r.reviewedFindings.length,3);assert.deepEqual(r.reviewedFindings.map(x=>x.disposition).sort(),['NOT_APPLICABLE','NOT_APPLICABLE','UNRESOLVED']);
  const byId=Object.fromEntries(r.reviewedFindings.map(x=>[x.findingId,x]));
  assert.equal(byId['62035cd7f4e3f9b84870d80ef358bf05071fdbcb32652b6db96073e3d0f6f3bb'].sourceBlobSha,'b57586084a14101613231fc5ba4a999cbeac1b08');
  assert.equal(byId['d90e0567afb2502b6f6d8b34a30fbc4ec211b835a95e101200548b94f9425bd7'].sourceBlobSha,'8a5fd79cff6b712049c15450f21bc5a1c7338143');
  assert.equal(byId['e6b83b7719d2bef5092127dd47f2a34c17d9c92ce3b9581b4381dd6b25432445'].sourceBlobSha,'fa3c224ac31df08cb3e2f130f7e4bef1fc750659');
  if(process.env.GITHUB_ACTIONS==='true'){for(const reviewed of r.reviewedFindings){const source=path.join(root,reviewed.sourcePath);assert.equal(existsSync(source),true,`review source missing ${reviewed.sourcePath}`);const h=spawnSync('git',['hash-object',reviewed.sourcePath],{cwd:root,encoding:'utf8'});assert.equal(h.status,0,h.stderr||h.stdout);assert.equal(h.stdout.trim(),reviewed.sourceBlobSha,`review source drift ${reviewed.sourcePath}`);}}
  assert.equal(r.summary.resolvedCount,2);assert.equal(r.summary.automaticExceptions,0);assert.equal(r.summary.severityDowngrades,0);assert.equal(r.summary.suppressions,0);assert.equal(r.summary.e3ClosureAuthorized,false);
});

test('E3-I3 reviews exactly four OSV runtime/deployment findings without upgrade suppression exception or severity mutation',()=>{
  const body=text(i3Contract);for(const m of ['VERSION_MATCH != APPLICABLE','RUNTIME_DEPENDENCY != AUTOMATICALLY_APPLICABLE','DEFAULT_CONFIG != ALL_SUPPORTED_DEPLOYMENTS','NOT_APPLICABLE_REQUIRES_POSITIVE_ABSENT_PRECONDITION_EVIDENCE','APPLICABLE_REQUIRES_POSITIVE_RUNTIME_AND_EXTERNAL_INPUT_EVIDENCE','UPSTREAM_SEVERITY_IS_IMMUTABLE_INPUT','NO_SUPPRESSION','NO_SEVERITY_DOWNGRADE','NO_EXCEPTION','NO_DEPENDENCY_UPGRADE','M6_PR_E_E3_CLOSURE_NOT_ACCEPTED'])assert.ok(body.includes(m),m);
  const r=JSON.parse(text(i3ReviewFile));assert.equal(r.reviewBasisHead,'448e0fe2808a3d2f02d8ee0deb16480a4ed46494');assert.equal(r.reviewBasisE2GraphDigest,'0b6868f057a72fb8c7a4c9d0529f4469381f0024f403873c6d92121e4b34ee0a');assert.equal(r.reviewedFindings.length,4);assert.deepEqual(r.reviewedFindings.map(x=>x.disposition).sort(),['APPLICABLE','NOT_APPLICABLE','UNRESOLVED','UNRESOLVED']);
  assert.deepEqual(r.sourceBlobs,{
    'apps/server/pom.xml':'89026f9c844b27a11a65c478a3d7abb57c7c053f',
    'apps/server/src/main/resources/application.yml':'b8fdf7350881239c2c1c1091ac8e373bf285c2ef',
    'apps/server/src/test/java/io/github/akaryc1b/approval/integration/PostgreSqlContainerTest.java':'cc944cd84410f894fb9fcf363613115c2b797509'
  });
  if(process.env.GITHUB_ACTIONS==='true'){for(const [sourcePath,expected] of Object.entries(r.sourceBlobs)){const source=path.join(root,sourcePath);assert.equal(existsSync(source),true,`I3 review source missing ${sourcePath}`);const h=spawnSync('git',['hash-object',sourcePath],{cwd:root,encoding:'utf8'});assert.equal(h.status,0,h.stderr||h.stdout);assert.equal(h.stdout.trim(),expected,`I3 review source drift ${sourcePath}`);}}
  const byId=Object.fromEntries(r.reviewedFindings.map(x=>[x.findingId,x]));
  assert.equal(byId['2f4564d12f468262a89f22ffdba329df0c09144a51fc8101046dabbe846de46e'].disposition,'NOT_APPLICABLE');assert.equal(byId['2f4564d12f468262a89f22ffdba329df0c09144a51fc8101046dabbe846de46e'].exploitPreconditionsSatisfied,false);
  assert.equal(byId['0b81ad89215f3cf30a7b2988bce970dbbc1600150a8682dc052051ab8297cabc'].disposition,'APPLICABLE');assert.equal(byId['0b81ad89215f3cf30a7b2988bce970dbbc1600150a8682dc052051ab8297cabc'].exploitPreconditionsSatisfied,true);assert.ok(['packaged','loaded','invoked','externallyReachable'].every(k=>byId['0b81ad89215f3cf30a7b2988bce970dbbc1600150a8682dc052051ab8297cabc'].reachability[k].value===true));
  assert.equal(byId['37afc66853c8e331ce9bf87f2dea502fbfea6a7e87636e17299591f2df0376f9'].disposition,'UNRESOLVED');assert.equal(byId['37afc66853c8e331ce9bf87f2dea502fbfea6a7e87636e17299591f2df0376f9'].reachability.externallyReachable.value,null);
  assert.equal(byId['36365712da812532fa5a0e3ed4c7008a3bfdade1587219a7d38e489380118021'].disposition,'UNRESOLVED');
  assert.equal(r.summary.reviewedCount,4);assert.equal(r.summary.notApplicableCount,1);assert.equal(r.summary.applicableCount,1);assert.equal(r.summary.unresolvedCount,2);assert.equal(r.summary.automaticExceptions,0);assert.equal(r.summary.severityDowngrades,0);assert.equal(r.summary.suppressions,0);assert.equal(r.summary.dependencyUpgrades,0);assert.equal(r.summary.e3ClosureAuthorized,false);
});

test('E4 full scanner suite runs only on GitHub Actions and emits one canonical redacted payload',{timeout:2400000},()=>{
  if(process.env.GITHUB_ACTIONS!=='true')return;
  const scanEnv={...process.env,GOFLAGS:'-modcacherw'};assert.equal(scanEnv.GOFLAGS,'-modcacherw');const r=spawnSync(process.execPath,[scanner,`--root=${root}`],{cwd:root,encoding:'utf8',maxBuffer:256*1024*1024,env:scanEnv,timeout:2300000});assert.equal(r.status,0,r.stderr||r.stdout);
  const m=r.stdout.match(/M6_PR_E_E4_SCANNER_EVIDENCE_BEGIN\n([^\n]+)\nM6_PR_E_E4_SCANNER_EVIDENCE_END/);assert.ok(m,'canonical E4 payload required');const e=JSON.parse(m[1]);
  assert.match(e.commitSha,/^[0-9a-f]{40}$/);assert.equal(e.e2GraphDigest,'0b6868f057a72fb8c7a4c9d0529f4469381f0024f403873c6d92121e4b34ee0a');assert.match(e.e2CurrentContentSha256,/^[0-9a-f]{64}$/);assert.equal(e.allScannersCompleted,true);assert.equal(e.rawScannerReportsRetained,false);assert.equal(e.candidateSecretMaterialRetained,false);assert.equal(e.authoritativeGitHubInventoryStillUnavailable,true);assert.equal(e.workstreamReleaseBlocked,true);
  for(const k of ['osv','gitleaks','zizmor','semgrep']){assert.equal(e.scanners[k].scanCompleted,true);assert.equal(e.scanners[k].rawReportRetained,false);assert.ok(Number.isInteger(e.scanners[k].findingCount));assert.ok(Array.isArray(e.scanners[k].findings));}
  assert.equal(e.scanners.gitleaks.candidateSecretMaterialRetained,false);assert.equal(e.scanners.semgrep.sourceSnippetRetained,false);assert.match(e.scanners.semgrep.imageRepoDigest,/^semgrep\/semgrep@sha256:[0-9a-f]{64}$/);assert.match(e.contentSha256,/^[0-9a-f]{64}$/);
  const gitTime=spawnSync('git',['show','-s','--format=%cI',e.commitSha],{cwd:root,encoding:'utf8'});assert.equal(gitTime.status,0,gitTime.stderr||gitTime.stdout);const snapshotTime=gitTime.stdout.trim();assert.ok(Number.isFinite(Date.parse(snapshotTime)),'exact Head commit time required');
  const intake=buildScannerFindingIntake(e,{snapshotTime});assert.equal(intake.commitSha,e.commitSha);assert.equal(intake.sourceE4ContentSha256,e.contentSha256);assert.equal(intake.findingInventory.complete,true);assert.equal(intake.findingInventory.itemCount,e.totalFindingCount);assert.equal(intake.findingInventory.knownFindingCount,e.totalFindingCount);assert.equal(intake.decisions.length,e.totalFindingCount);assert.equal(intake.summary.unresolvedCount,e.totalFindingCount);assert.equal(intake.summary.releaseBlocked,true);assert.equal(intake.sourceReadiness.authoritativeComplete,false);assert.equal(intake.sourceReadiness.scannerCoverageComplete,true);for(const f of intake.decisions){assert.equal(f.disposition,'UNRESOLVED');assert.equal(f.severityBand,'UNKNOWN');}
  const review=JSON.parse(text(i2ReviewFile));const triage=applyReviewedFindings(intake,review);assert.equal(triage.reviewedFindingCount,3);assert.equal(triage.summary.dispositionCounts.NOT_APPLICABLE,2);assert.equal(triage.summary.dispositionCounts.UNRESOLVED,e.totalFindingCount-2);assert.equal(triage.summary.releaseBlocked,true);assert.equal(triage.decisions.find(x=>x.findingId==='e6b83b7719d2bef5092127dd47f2a34c17d9c92ce3b9581b4381dd6b25432445').disposition,'UNRESOLVED');
  const i3Review=JSON.parse(text(i3ReviewFile));const i3=applyRuntimeDeploymentReviews(triage,e,i3Review);assert.equal(i3.reviewedFindingCount,4);assert.equal(i3.cumulativeReviewedFindingCount,7);assert.equal(i3.summary.dispositionCounts.NOT_APPLICABLE,3);assert.equal(i3.summary.dispositionCounts.APPLICABLE,1);assert.equal(i3.summary.dispositionCounts.UNRESOLVED,e.totalFindingCount-4);assert.equal(i3.summary.releaseBlocked,true);assert.ok(i3.summary.reasonCodes.includes('E3_APPLICABLE_FINDINGS_REQUIRE_REMEDIATION'));assert.equal(i3.decisions.find(x=>x.findingId==='2f4564d12f468262a89f22ffdba329df0c09144a51fc8101046dabbe846de46e').disposition,'NOT_APPLICABLE');assert.equal(i3.decisions.find(x=>x.findingId==='0b81ad89215f3cf30a7b2988bce970dbbc1600150a8682dc052051ab8297cabc').disposition,'APPLICABLE');assert.ok(['packaged','loaded','invoked','externallyReachable'].every(k=>i3.decisions.find(x=>x.findingId==='0b81ad89215f3cf30a7b2988bce970dbbc1600150a8682dc052051ab8297cabc').reachability[k].value===true));assert.equal(i3.decisions.find(x=>x.findingId==='37afc66853c8e331ce9bf87f2dea502fbfea6a7e87636e17299591f2df0376f9').disposition,'UNRESOLVED');assert.equal(i3.decisions.find(x=>x.findingId==='37afc66853c8e331ce9bf87f2dea502fbfea6a7e87636e17299591f2df0376f9').reachability.externallyReachable.value,null);assert.equal(i3.decisions.find(x=>x.findingId==='36365712da812532fa5a0e3ed4c7008a3bfdade1587219a7d38e489380118021').disposition,'UNRESOLVED');
  console.log('M6_PR_E_E4_CANONICAL_SHA256='+e.contentSha256);console.log(m[0]);console.log('M6_PR_E_E3_SCANNER_INTAKE_CANONICAL_SHA256='+intake.contentSha256);console.log('M6_PR_E_E3_SCANNER_INTAKE_BEGIN');console.log(JSON.stringify(intake));console.log('M6_PR_E_E3_SCANNER_INTAKE_END');console.log('M6_PR_E_E3_I2_TRIAGE_CANONICAL_SHA256='+triage.contentSha256);console.log('M6_PR_E_E3_I2_TRIAGE_BEGIN');console.log(JSON.stringify(triage));console.log('M6_PR_E_E3_I2_TRIAGE_END');console.log('M6_PR_E_E3_I3_TRIAGE_CANONICAL_SHA256='+i3.contentSha256);console.log('M6_PR_E_E3_I3_TRIAGE_BEGIN');console.log(JSON.stringify(i3));console.log('M6_PR_E_E3_I3_TRIAGE_END');
});
