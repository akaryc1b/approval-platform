import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { buildScannerFindingIntake } from '../security/m6-pr-e-e3-ingest-e4.mjs';
import { applyReviewedFindings } from '../security/m6-pr-e-e3-apply-reviewed-findings.mjs';
import { applyRuntimeDeploymentReviews } from '../security/m6-pr-e-e3-apply-runtime-deployment-reviews.mjs';
import { verifyPgjdbcRemediation } from '../security/m6-pr-e-e3-verify-pgjdbc-remediation.mjs';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const p=(x)=>path.join(root,x), text=(x)=>{const f=p(x);assert.equal(existsSync(f),true,`${x} must exist`);return readFileSync(f,'utf8');};
const contract='docs/m6/M6_PR_E_E4_CODE_SECRET_DEPENDENCY_SCANNING_EVIDENCE.md';
const baselineFile='docs/m6/m6-pr-e-e4-scanner-baseline.json';
const intakeContract='docs/m6/M6_PR_E_E3_E4_SCANNER_FINDING_INTAKE.md';
const i2Contract='docs/m6/M6_PR_E_E3_I2_SEMGREP_SOURCE_AUDIT.md',i2ReviewFile='docs/m6/m6-pr-e-e3-i2-reviewed-findings.json';
const i3Contract='docs/m6/M6_PR_E_E3_I3_OSV_RUNTIME_DEPLOYMENT_PRECONDITION_AUDIT.md',i3ReviewFile='docs/m6/m6-pr-e-e3-i3-reviewed-findings.json';
const r1Contract='docs/m6/M6_PR_E_E3_R1_PGJDBC_SECURITY_REMEDIATION.md',r1PlanFile='docs/m6/m6-pr-e-e3-r1-pgjdbc-remediation.json';
const scanner=p('scripts/security/m6-pr-e-e4-scan.mjs');
const OLD_GRAPH='0b6868f057a72fb8c7a4c9d0529f4469381f0024f403873c6d92121e4b34ee0a',NEW_GRAPH='2cc0000745441ebb70b7dd9ad6b17e5c9d6e27981ea213c7005c9bed3e09df94';
const CVE42198='0b81ad89215f3cf30a7b2988bce970dbbc1600150a8682dc052051ab8297cabc',CVE54291='37afc66853c8e331ce9bf87f2dea502fbfea6a7e87636e17299591f2df0376f9',BOOT='2f4564d12f468262a89f22ffdba329df0c09144a51fc8101046dabbe846de46e',TOMCAT='36365712da812532fa5a0e3ed4c7008a3bfdade1587219a7d38e489380118021',PROTO='e6b83b7719d2bef5092127dd47f2a34c17d9c92ce3b9581b4381dd6b25432445';

function fixture(){
  const f=(sourceClass,findingId,extra={})=>({sourceClass,findingId,...extra});
  return {repository:'akaryc1b/approval-platform',commitSha:'3'.repeat(40),contentSha256:'a'.repeat(64),e2GraphDigest:'b'.repeat(64),allScannersCompleted:true,rawScannerReportsRetained:false,candidateSecretMaterialRetained:false,totalFindingCount:4,scanners:{
    osv:{scanCompleted:true,rawReportRetained:false,findingCount:1,findings:[f('E4_OSV_SCANNER','OSV-1',{upstreamFindingId:'GHSA-fixture',aliases:[],package:{ecosystem:'Maven',name:'org.example:lib',version:'1.0.0'},componentRefs:['pkg:maven/org.example/lib@1.0.0?type=jar'],scopes:['compile'],upstreamSeverity:[],fixedVersions:[]})]},
    gitleaks:{scanCompleted:true,rawReportRetained:false,candidateSecretMaterialRetained:false,findingCount:1,findings:[f('E4_GITLEAKS','GL-1',{ruleId:'generic-api-key',path:'fixture.txt',startLine:1,endLine:1,commit:'4'.repeat(40),fingerprint:'fixture'})]},
    zizmor:{scanCompleted:true,rawReportRetained:false,findingCount:1,findings:[f('E4_ZIZMOR','ZZ-1',{ruleId:'zizmor/unpinned-uses',upstreamSeverity:'error',path:'.github/workflows/a.yml',startLine:1})]},
    semgrep:{scanCompleted:true,rawReportRetained:false,sourceSnippetRetained:false,findingCount:1,findings:[f('E4_SEMGREP','SG-1',{ruleId:'rules.fixture',upstreamSeverity:'WARNING',path:'/src/server-modules/x/A.java',startLine:5,cwe:[],owasp:[],category:'security'})]}
  }};
}

test('E4 contract keeps scanner evidence separate from authoritative alerts and E3 disposition',()=>{
  const b=text(contract);for(const m of ['SCANNER_SUCCESS != ZERO_FINDINGS','ZERO_SCANNER_FINDINGS != AUTHORITATIVE_GITHUB_ZERO_ALERTS','SCANNER_FINDING != E3_DISPOSITION','RAW_SECRET_REPORT_MUST_NOT_BE_RETAINED','MUTABLE_REMOTE_RULESET_PROHIBITED','FINDINGS_REQUIRE_E3_APPLICABILITY_REACHABILITY_TRIAGE','PRB_16_REMAINS_OPEN','PRB_17_REMAINS_OPEN','NO_READY','NO_MERGE','AI_IS_NOT_AN_OPERATOR'])assert.ok(b.includes(m),m);assert.doesNotMatch(b,/M6_PR_E_E3_ACCEPTED\b|M6_PR_E_E4_ACCEPTED\b/);
});

test('E4 scanner baseline pins tool identities, graph transition and no suppressions',()=>{
  const b=JSON.parse(text(baselineFile));assert.equal(b.sourceHead,'28b7cca970aba1fcaab05aceb200a2987042d973');assert.equal(b.previousE2GraphDigest,OLD_GRAPH);assert.equal(b.inheritedE2GraphDigest,NEW_GRAPH);assert.equal(b.graphTransition,'E3_R1_PGJDBC_42_7_9_TO_42_7_13');assert.deepEqual([b.scanners.osv.version,b.scanners.gitleaks.version,b.scanners.zizmor.version,b.scanners.semgrep.version],['2.5.0','8.30.1','1.26.1','1.172.0']);for(const s of Object.values(b.scanners))assert.match(s.sourceCommit,/^[0-9a-f]{40}$/);assert.deepEqual(b.suppressionPolicy.entries,[]);assert.equal(b.suppressionPolicy.broadPathExclusionsPermitted,false);assert.equal(b.suppressionPolicy.severityDowngradePermitted,false);
});

test('E4 implementation keeps redaction offline and findings as triage inputs',()=>{
  const s=readFileSync(scanner,'utf8');for(const x of [/--redact=100/,/--log-opts=--all/,/rawReportRetained:false/,/candidateSecretMaterialRetained:false/,/sourceSnippetRetained:false/,/--offline/,/--strict-collection/,/--metrics=off/,/--disable-version-check/,/scannerFindingTriageRequired/,/E4_SCANNER_FINDINGS_REQUIRE_E3_TRIAGE/,/authoritativeGitHubInventoryStillUnavailable:true/,/workstreamReleaseBlocked:true/])assert.match(s,x);assert.doesNotMatch(s,/x\.(?:Secret|Match)\b|extra\?\.lines|extra\?\.metavars|p\/default|--config\s+auto/i);
});

test('E3 intake keeps all scanner findings unresolved and Secret-safe',()=>{
  const b=text(intakeContract);for(const m of ['SCANNER_COVERAGE_COMPLETE != AUTHORITATIVE_INVENTORY_COMPLETE','SCANNER_FINDING != E3_DISPOSITION','RAW_SECRET_REPORT_MUST_NOT_BE_RECONSTRUCTED','ALL_SCANNER_FINDINGS_INITIAL_DISPOSITION_UNRESOLVED','M6_PR_E_E3_CLOSURE_NOT_ACCEPTED'])assert.ok(b.includes(m),m);const i=buildScannerFindingIntake(fixture(),{snapshotTime:'2026-08-11T04:00:00Z'});assert.equal(i.decisions.length,4);assert.equal(i.summary.unresolvedCount,4);assert.equal(i.summary.highRiskUnresolvedCount,2);for(const f of i.decisions){assert.equal(f.disposition,'UNRESOLVED');assert.equal(f.severityBand,'UNKNOWN');assert.ok(Object.values(f.reachability).every(v=>v.value===null));assert.doesNotMatch(JSON.stringify(f),/"(?:Secret|Match)"\s*:/i);}
});

test('E3-I2 preserves exact three reviewed Semgrep findings and source blobs',()=>{
  const b=text(i2Contract);for(const m of ['SOURCE_MATCH != APPLICABLE','FALSE_POSITIVE_REQUIRES_POSITIVE_ABSENT_PRECONDITION_EVIDENCE','TYPESCRIPT_TYPE != RUNTIME_INPUT_VALIDATION','UNRESOLVED_PROTOTYPE_POLLUTION_REMAINS_OPEN','NO_SUPPRESSION','NO_SEVERITY_DOWNGRADE','NO_EXCEPTION'])assert.ok(b.includes(m),m);const r=JSON.parse(text(i2ReviewFile));assert.equal(r.reviewBasisHead,'234ed3d41b049ca9475e58e29e1204587a992693');assert.equal(r.reviewedFindings.length,3);assert.deepEqual(r.reviewedFindings.map(x=>x.disposition).sort(),['NOT_APPLICABLE','NOT_APPLICABLE','UNRESOLVED']);const by=Object.fromEntries(r.reviewedFindings.map(x=>[x.findingId,x]));assert.equal(by[PROTO].sourceBlobSha,'fa3c224ac31df08cb3e2f130f7e4bef1fc750659');if(process.env.GITHUB_ACTIONS==='true')for(const x of r.reviewedFindings){const h=spawnSync('git',['hash-object',x.sourcePath],{cwd:root,encoding:'utf8'});assert.equal(h.status,0,h.stderr||h.stdout);assert.equal(h.stdout.trim(),x.sourceBlobSha);}
});

test('E3-I3 retains exact historical runtime/deployment review without rewriting it',()=>{
  const b=text(i3Contract);for(const m of ['VERSION_MATCH != APPLICABLE','DEFAULT_CONFIG != ALL_SUPPORTED_DEPLOYMENTS','NOT_APPLICABLE_REQUIRES_POSITIVE_ABSENT_PRECONDITION_EVIDENCE','APPLICABLE_REQUIRES_POSITIVE_RUNTIME_AND_EXTERNAL_INPUT_EVIDENCE','NO_SUPPRESSION','NO_SEVERITY_DOWNGRADE','NO_EXCEPTION','NO_DEPENDENCY_UPGRADE'])assert.ok(b.includes(m),m);const r=JSON.parse(text(i3ReviewFile));assert.equal(r.reviewBasisHead,'448e0fe2808a3d2f02d8ee0deb16480a4ed46494');assert.equal(r.reviewBasisE2GraphDigest,OLD_GRAPH);assert.equal(r.reviewedFindings.length,4);const by=Object.fromEntries(r.reviewedFindings.map(x=>[x.findingId,x]));assert.equal(by[BOOT].disposition,'NOT_APPLICABLE');assert.equal(by[CVE42198].disposition,'APPLICABLE');assert.ok(Object.values(by[CVE42198].reachability).every(v=>v.value===true));assert.equal(by[CVE54291].disposition,'UNRESOLVED');assert.equal(by[TOMCAT].disposition,'UNRESOLVED');
});

test('E3-R1 upgrades only pgjdbc patch line and binds exact remediation graph',()=>{
  const b=text(r1Contract);for(const m of ['REMEDIATION != HISTORY_REWRITE','SCANNER_FINDING_ABSENCE_REQUIRES_FIXED_GRAPH_EVIDENCE','DEPENDENCY_OVERRIDE != SPRING_BOOT_UPGRADE','NO_SUPPRESSION','NO_SEVERITY_DOWNGRADE','NO_EXCEPTION','PRB_16_REMAINS_OPEN','PRB_17_REMAINS_OPEN','NO_READY','NO_MERGE'])assert.ok(b.includes(m),m);const r=JSON.parse(text(r1PlanFile));assert.equal(r.priorAcceptedHead,'f7875de5c2de17ae9d8f923227e3a6898cd31ab0');assert.equal(r.priorE2GraphDigest,OLD_GRAPH);assert.equal(r.targetE2GraphDigest,NEW_GRAPH);assert.equal(r.dependencyOverride.toVersion,'42.7.13');assert.equal(r.remediatedFindings.length,2);assert.deepEqual(r.remediatedFindings.map(x=>x.aliases.find(a=>a.startsWith('CVE-'))).sort(),['CVE-2026-42198','CVE-2026-54291']);assert.equal(r.invariants.dependencyUpgradeCount,1);assert.ok(['springBootUpgrade','flowableUpgrade','testcontainersUpgrade','workflowChanged','scannerSuppressionAdded','severityDowngradeAdded','exceptionAdded','readyAuthorized','mergeAuthorized'].every(k=>r.invariants[k]===false));const pom=text('pom.xml'),pg='<artifactId>postgresql</artifactId>',boot='<artifactId>spring-boot-dependencies</artifactId>';assert.equal((pom.match(/<artifactId>postgresql<\/artifactId>/g)||[]).length,1);assert.ok(pom.indexOf(pg)<pom.indexOf(boot));assert.match(pom,/<groupId>org\.postgresql<\/groupId>\s*<artifactId>postgresql<\/artifactId>\s*<version>42\.7\.13<\/version>/);
});

test('R1 verifier accepts only fixed graph with both historical pgjdbc findings absent',()=>{
  const plan=JSON.parse(text(r1PlanFile)),base={repository:plan.repository,commitSha:'5'.repeat(40),contentSha256:'6'.repeat(64),e2GraphDigest:NEW_GRAPH,scanners:{osv:{scanCompleted:true,findings:[]}}};const r=verifyPgjdbcRemediation(base,plan);assert.equal(r.remediatedFindings.length,2);assert.ok(r.remediatedFindings.every(x=>x.currentStatus==='REMEDIATED_BY_FIXED_COMPONENT_AND_ABSENT_FROM_CURRENT_OSV'));assert.throws(()=>verifyPgjdbcRemediation({...base,e2GraphDigest:OLD_GRAPH},plan),/graph mismatch/);assert.throws(()=>verifyPgjdbcRemediation({...base,scanners:{osv:{scanCompleted:true,findings:[{sourceClass:'E4_OSV_SCANNER',findingId:CVE42198,upstreamFindingId:'GHSA-98qh-xjc8-98pq',aliases:['CVE-2026-42198']}]}}},plan),/still present/);
});

test('E4 full scanner emits R1 remediation and current I3 triage only on GitHub Actions',{timeout:2400000},()=>{
  if(process.env.GITHUB_ACTIONS!=='true')return;const env={...process.env,GOFLAGS:'-modcacherw'},r=spawnSync(process.execPath,[scanner,`--root=${root}`],{cwd:root,encoding:'utf8',maxBuffer:256*1024*1024,env,timeout:2300000});assert.equal(r.status,0,r.stderr||r.stdout);const m=r.stdout.match(/M6_PR_E_E4_SCANNER_EVIDENCE_BEGIN\n([^\n]+)\nM6_PR_E_E4_SCANNER_EVIDENCE_END/);assert.ok(m);const e=JSON.parse(m[1]);assert.equal(e.e2GraphDigest,NEW_GRAPH);assert.equal(e.allScannersCompleted,true);assert.equal(e.rawScannerReportsRetained,false);assert.equal(e.candidateSecretMaterialRetained,false);const gt=spawnSync('git',['show','-s','--format=%cI',e.commitSha],{cwd:root,encoding:'utf8'});assert.equal(gt.status,0);const intake=buildScannerFindingIntake(e,{snapshotTime:gt.stdout.trim()}),i2=applyReviewedFindings(intake,JSON.parse(text(i2ReviewFile))),r1=verifyPgjdbcRemediation(e,JSON.parse(text(r1PlanFile))),i3=applyRuntimeDeploymentReviews(i2,e,JSON.parse(text(i3ReviewFile)),r1);assert.equal(r1.remediatedFindings.length,2);assert.equal(i3.historicalReviewedFindingCount,4);assert.equal(i3.reviewedFindingCount,2);assert.equal(i3.remediatedHistoricalFindingCount,2);assert.equal(i3.cumulativeReviewedFindingCount,7);assert.equal(i3.summary.dispositionCounts.NOT_APPLICABLE,3);assert.equal(i3.summary.dispositionCounts.APPLICABLE||0,0);assert.equal(i3.summary.dispositionCounts.UNRESOLVED,e.totalFindingCount-3);assert.equal(i3.summary.reasonCodes.includes('E3_APPLICABLE_FINDINGS_REQUIRE_REMEDIATION'),false);assert.equal(i3.decisions.some(x=>x.findingId===CVE42198||x.findingId===CVE54291),false);assert.equal(i3.decisions.find(x=>x.findingId===BOOT).disposition,'NOT_APPLICABLE');assert.equal(i3.decisions.find(x=>x.findingId===TOMCAT).disposition,'UNRESOLVED');assert.equal(i3.decisions.find(x=>x.findingId===PROTO).disposition,'UNRESOLVED');const aliases=new Set(e.scanners.osv.findings.flatMap(x=>x.aliases||[]));assert.equal(aliases.has('CVE-2026-42198'),false);assert.equal(aliases.has('CVE-2026-54291'),false);
  console.log('M6_PR_E_E4_CANONICAL_SHA256='+e.contentSha256);console.log(m[0]);console.log('M6_PR_E_E3_SCANNER_INTAKE_CANONICAL_SHA256='+intake.contentSha256);console.log('M6_PR_E_E3_SCANNER_INTAKE_BEGIN\n'+JSON.stringify(intake)+'\nM6_PR_E_E3_SCANNER_INTAKE_END');console.log('M6_PR_E_E3_I2_TRIAGE_CANONICAL_SHA256='+i2.contentSha256);console.log('M6_PR_E_E3_I2_TRIAGE_BEGIN\n'+JSON.stringify(i2)+'\nM6_PR_E_E3_I2_TRIAGE_END');console.log('M6_PR_E_E3_R1_REMEDIATION_CANONICAL_SHA256='+r1.contentSha256);console.log('M6_PR_E_E3_R1_REMEDIATION_BEGIN\n'+JSON.stringify(r1)+'\nM6_PR_E_E3_R1_REMEDIATION_END');console.log('M6_PR_E_E3_I3_TRIAGE_CANONICAL_SHA256='+i3.contentSha256);console.log('M6_PR_E_E3_I3_TRIAGE_BEGIN\n'+JSON.stringify(i3)+'\nM6_PR_E_E3_I3_TRIAGE_END');
});
