import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const contract=path.join(root,'docs/m6/M6_PR_E_E4_CODE_SECRET_DEPENDENCY_SCANNING_EVIDENCE.md');
const baselineFile=path.join(root,'docs/m6/m6-pr-e-e4-scanner-baseline.json');
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

test('E4 full scanner suite runs only on GitHub Actions and emits one canonical redacted payload',{timeout:2400000},()=>{
  if(process.env.GITHUB_ACTIONS!=='true')return;
  const r=spawnSync(process.execPath,[scanner,`--root=${root}`],{cwd:root,encoding:'utf8',maxBuffer:256*1024*1024,env:process.env,timeout:2300000});assert.equal(r.status,0,r.stderr||r.stdout);
  const m=r.stdout.match(/M6_PR_E_E4_SCANNER_EVIDENCE_BEGIN\n([^\n]+)\nM6_PR_E_E4_SCANNER_EVIDENCE_END/);assert.ok(m,'canonical E4 payload required');const e=JSON.parse(m[1]);
  assert.match(e.commitSha,/^[0-9a-f]{40}$/);assert.equal(e.e2GraphDigest,'0b6868f057a72fb8c7a4c9d0529f4469381f0024f403873c6d92121e4b34ee0a');assert.match(e.e2CurrentContentSha256,/^[0-9a-f]{64}$/);assert.equal(e.allScannersCompleted,true);assert.equal(e.rawScannerReportsRetained,false);assert.equal(e.candidateSecretMaterialRetained,false);assert.equal(e.authoritativeGitHubInventoryStillUnavailable,true);assert.equal(e.workstreamReleaseBlocked,true);
  for(const k of ['osv','gitleaks','zizmor','semgrep']){assert.equal(e.scanners[k].scanCompleted,true);assert.equal(e.scanners[k].rawReportRetained,false);assert.ok(Number.isInteger(e.scanners[k].findingCount));assert.ok(Array.isArray(e.scanners[k].findings));}
  assert.equal(e.scanners.gitleaks.candidateSecretMaterialRetained,false);assert.equal(e.scanners.semgrep.sourceSnippetRetained,false);assert.match(e.scanners.semgrep.imageRepoDigest,/^semgrep\/semgrep@sha256:[0-9a-f]{64}$/);assert.match(e.contentSha256,/^[0-9a-f]{64}$/);
  console.log('M6_PR_E_E4_CANONICAL_SHA256='+e.contentSha256);console.log(m[0]);
});
