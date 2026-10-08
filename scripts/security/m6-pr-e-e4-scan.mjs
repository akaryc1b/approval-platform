#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { acceptedE2GraphProjection, generateEvidence as generateE2Evidence } from './m6-pr-e-e2-generate-sbom.mjs';

import { verifyObservabilityGraph } from './observability-dependency-graph.mjs';
import { normalizeSemgrepReport } from './semgrep-scan-coverage.mjs';
import { osvInputFromE2, buildOsvCoverage } from './osv-scan-coverage.mjs';
import { requireOsvReport, requireGitleaksReport, requireZizmorReport } from './scanner-report-structure.mjs';

const SHA40=/^[0-9a-f]{40}$/;
const H=x=>createHash('sha256').update(x).digest('hex');
const S=v=>Array.isArray(v)?v.map(S):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,S(v[k])])):v;
const C=v=>JSON.stringify(S(v));
const J=f=>JSON.parse(readFileSync(f,'utf8'));
const rootFromHere=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');

function run(command,args,{cwd,env,timeout=600000,maxBuffer=256*1024*1024,allow=[]}={}){
  const r=spawnSync(command,args,{cwd,env,encoding:'utf8',timeout,maxBuffer});
  if(r.error) throw r.error;
  if(![0,...allow].includes(r.status)) throw new Error(`${command} failed status=${r.status}: ${(r.stderr||r.stdout||'').slice(-4000)}`);
  return r;
}
function verifySha(file,expected){const got=H(readFileSync(file));if(got!==expected)throw new Error(`sha256 mismatch ${path.basename(file)} ${got}`);return got;}
export function exactHead(){
  let event=null;
  if(process.env.GITHUB_EVENT_PATH&&existsSync(process.env.GITHUB_EVENT_PATH)){
    event=J(process.env.GITHUB_EVENT_PATH);
  }
  const candidates=[
    event?.pull_request?.head?.sha,
    event?.after,
    event?.head_commit?.id,
    process.env.M6_PR_E_E4_HEAD_SHA,
    process.env.GITHUB_SHA
  ];
  const head=candidates.find(candidate=>SHA40.test(String(candidate??'')));
  if(head)return head;
  throw new Error('E4 exact workflow head unavailable');
}
export function verifyScannerCheckout(root,head,{git=args=>run('git',args,{cwd:root}).stdout.trim()}={}){
  if(!SHA40.test(head||''))throw new Error('scanner expected Head unavailable');
  const checkedOutSha=git(['rev-parse','HEAD']),checkedOutTreeSha=git(['rev-parse','HEAD^{tree}']),expectedHeadTreeSha=git(['rev-parse',`${head}^{tree}`]);
  if(!SHA40.test(checkedOutSha)||!SHA40.test(checkedOutTreeSha)||checkedOutTreeSha!==expectedHeadTreeSha)throw new Error('scanner checkout tree differs from expected Head');
  if(git(['status','--porcelain','--untracked-files=no'])!=='')throw new Error('scanner tracked worktree differs from expected Head');
  return{checkedOutSha,checkedOutTreeSha,expectedHeadSha:head,expectedHeadTreeSha,exactTreeMatches:true,trackedWorktreeClean:true};
}
export function requireScannerCheckoutUnchanged(root,head,before,options){
  const after=verifyScannerCheckout(root,head,options);
  if(C(after)!==C(before))throw new Error('scanner checkout changed during scanning');
  return after;
}
export function requireOsvConfigAbsent(directories){
  for(const directory of directories)for(const file of ['osv-scanner.toml','.osv-scanner.toml'])if(existsSync(path.join(directory,file)))throw new Error(`unreviewed OSV suppression/config present: ${file}`);
}
function safeEnv(extra={}){const e={...process.env,...extra};for(const k of ['GH_TOKEN','GITHUB_TOKEN','ZIZMOR_GITHUB_TOKEN','SEMGREP_APP_TOKEN'])delete e[k];return e;}
function collectFiles(dir,predicate,out=[]){if(!existsSync(dir))return out;for(const n of readdirSync(dir).sort()){const f=path.join(dir,n),s=statSync(f);if(s.isDirectory())collectFiles(f,predicate,out);else if(predicate(f))out.push(f);}return out;}
function findingId(parts){return H(parts.join('\0'));}
export function e2GraphDigest(e2){return H(C(acceptedE2GraphProjection(e2)));}
function normalizeOsv(raw,lookup){
  const out=[];
  for(const result of raw.results||[])for(const entry of result.packages||[]){const p=entry.package||{};const key=`${p.ecosystem}\0${p.name}\0${p.version}`,m=lookup.get(key)||{componentRefs:[],scopes:[]};for(const v of entry.vulnerabilities||[]){const aliases=[...new Set(v.aliases||[])].sort();const severity=(v.severity||[]).map(x=>({type:String(x.type||''),score:String(x.score||'')}));const fixed=[...new Set((v.affected||[]).flatMap(a=>(a.ranges||[]).flatMap(r=>(r.events||[]).map(e=>e.fixed).filter(Boolean))))].sort();out.push({findingId:findingId(['OSV',v.id||'',p.ecosystem||'',p.name||'',p.version||'']),sourceClass:'E4_OSV_SCANNER',upstreamFindingId:String(v.id||''),aliases,package:{ecosystem:p.ecosystem,name:p.name,version:p.version},componentRefs:[...m.componentRefs].sort(),scopes:[...m.scopes].sort(),upstreamSeverity:severity,fixedVersions:fixed});}}
  return out.sort((a,b)=>a.findingId.localeCompare(b.findingId));
}
function normalizeGitleaks(raw){return (raw||[]).map(x=>({findingId:findingId(['GITLEAKS',x.Fingerprint||'',x.RuleID||'',x.File||'',String(x.StartLine||'')]),sourceClass:'E4_GITLEAKS',ruleId:String(x.RuleID||''),description:String(x.Description||''),path:String(x.File||''),startLine:x.StartLine??null,endLine:x.EndLine??null,commit:String(x.Commit||''),fingerprint:String(x.Fingerprint||'')})).sort((a,b)=>a.findingId.localeCompare(b.findingId));}
function normalizeZizmor(raw){const out=[];for(const r of raw.runs||[])for(const x of r.results||[]){const l=x.locations?.[0]?.physicalLocation||{},a=l.artifactLocation||{},g=l.region||{};out.push({findingId:findingId(['ZIZMOR',x.ruleId||'',a.uri||'',String(g.startLine||''),String(g.startColumn||'')]),sourceClass:'E4_ZIZMOR',ruleId:String(x.ruleId||''),upstreamSeverity:String(x.level||'warning'),path:String(a.uri||''),startLine:g.startLine??null,startColumn:g.startColumn??null,endLine:g.endLine??null,endColumn:g.endColumn??null});}return out.sort((a,b)=>a.findingId.localeCompare(b.findingId));}

function copySecurityRules(repo,dest){
  const roots=['java','javascript','typescript'];let files=[];for(const r of roots)files.push(...collectFiles(path.join(repo,r),f=>/\.(ya?ml)$/.test(f)&&f.split(path.sep).includes('security')&&/^\s*rules:\s*$/m.test(readFileSync(f,'utf8'))));files=files.sort();if(!files.length)throw new Error('Semgrep security rule selection empty');
  const h=createHash('sha256');for(const f of files){const rel=path.relative(repo,f).replaceAll(path.sep,'/'),b=readFileSync(f);h.update(rel).update('\0').update(b).update('\0');const o=path.join(dest,rel);mkdirSync(path.dirname(o),{recursive:true});cpSync(f,o);}
  return{ruleFileCount:files.length,ruleContentSha256:h.digest('hex')};
}
export function scan(root=rootFromHere){
  const baseline=J(path.join(root,'docs/m6/m6-pr-e-e4-scanner-baseline.json')),head=exactHead(),tmp=mkdtempSync(path.join(os.tmpdir(),'m6-pr-e-e4-'));
  try{
    const checkout=verifyScannerCheckout(root,head);
    const e2=generateE2Evidence(root,{fullMaven:true});if(e2.commitSha!==head)throw new Error(`E2 head mismatch ${e2.commitSha} != ${head}`);const graphDigest=e2GraphDigest(e2);const graphTransition=verifyObservabilityGraph(e2,acceptedE2GraphProjection(e2),baseline.inheritedE2GraphDigest,head);
    const env=safeEnv(),bin=path.join(tmp,'bin');mkdirSync(bin,{recursive:true});

    requireOsvConfigAbsent([root,tmp]);

    const goTar=path.join(tmp,'go.tgz'),goRoot=path.join(tmp,'go-root');mkdirSync(goRoot);run('curl',['--fail','--location','--silent','--show-error',baseline.scanners.osv.installation.goLinuxAmd64Url,'-o',goTar],{env});verifySha(goTar,baseline.scanners.osv.installation.goLinuxAmd64Sha256);run('tar',['-xzf',goTar,'-C',goRoot],{env});const go=path.join(goRoot,'go/bin/go'),goEnv={...env,GOTOOLCHAIN:'local',GOPATH:path.join(tmp,'gopath'),GOBIN:bin,GOPROXY:'https://proxy.golang.org,direct',GOSUMDB:'sum.golang.org'};run(go,['version'],{env:goEnv});run(go,['install',baseline.scanners.osv.installation.module],{env:goEnv,timeout:1200000});const osv=path.join(bin,'osv-scanner'),osvVersion=run(osv,['--version'],{env}).stdout.trim()||run(osv,['version'],{env}).stdout.trim();if(!osvVersion.includes(baseline.scanners.osv.version))throw new Error(`OSV version mismatch ${osvVersion}`);const osvBinarySha256=H(readFileSync(osv));const oi=osvInputFromE2(e2),osvInput=path.join(tmp,'osv-scanner.json'),osvRaw=path.join(tmp,'osv.json');writeFileSync(osvInput,JSON.stringify(oi.scannerInput));let rr=run(osv,['scan','--all-packages','--format','json','--lockfile',`osv-scanner:${osvInput}`],{cwd:root,env,allow:[1]});writeFileSync(osvRaw,rr.stdout);const osvJson=requireOsvReport(J(osvRaw)),osvFindings=normalizeOsv(osvJson,oi.lookup),osvCoverage=buildOsvCoverage(osvJson,e2,{head,graphDigest,inputBytes:readFileSync(osvInput),inputPath:osvInput,stderr:rr.stderr,exitStatus:rr.status,findings:osvFindings});

    for(const f of ['.gitleaksignore','.gitleaks.toml'])if(existsSync(path.join(root,f)))throw new Error(`unreviewed Gitleaks suppression/config present: ${f}`);
    const glTar=path.join(tmp,'gitleaks.tgz');run('curl',['--fail','--location','--silent','--show-error',baseline.scanners.gitleaks.installation.url,'-o',glTar],{env});verifySha(glTar,baseline.scanners.gitleaks.installation.sha256);run('tar',['-xzf',glTar,'-C',bin],{env});const gitleaks=path.join(bin,'gitleaks'),glVersion=run(gitleaks,['version'],{env}).stdout.trim();if(!glVersion.includes(baseline.scanners.gitleaks.version))throw new Error(`Gitleaks version mismatch ${glVersion}`);const glRaw=path.join(tmp,'gitleaks.json');rr=run(gitleaks,['git','--redact=100','--no-banner','--log-opts=--all','--report-format','json','--report-path',glRaw,'.'],{cwd:root,env,allow:[1],timeout:900000});if(!existsSync(glRaw))throw new Error('Gitleaks report artifact missing');const glFindings=normalizeGitleaks(requireGitleaksReport(J(glRaw)));

    const wheel=path.join(tmp,path.basename(new URL(baseline.scanners.zizmor.installation.url).pathname));run('curl',['--fail','--location','--silent','--show-error',baseline.scanners.zizmor.installation.url,'-o',wheel],{env});verifySha(wheel,baseline.scanners.zizmor.installation.sha256);const venv=path.join(tmp,'zizmor-venv');run('python3',['-m','venv',venv],{env});run(path.join(venv,'bin/pip'),['install','--disable-pip-version-check','--no-deps',wheel],{env,timeout:600000});const zizmor=path.join(venv,'bin/zizmor'),zzVersion=run(zizmor,['--version'],{env}).stdout.trim();if(!zzVersion.includes(baseline.scanners.zizmor.version))throw new Error(`zizmor version mismatch ${zzVersion}`);const zzRaw=path.join(tmp,'zizmor.sarif');rr=run(zizmor,['--offline','--strict-collection','--collect=workflows,actions,dependabot','--format=sarif',root],{cwd:root,env,timeout:600000});writeFileSync(zzRaw,rr.stdout);const zzJson=requireZizmorReport(J(zzRaw)),zzFindings=normalizeZizmor(zzJson);

    if(existsSync(path.join(root,'.semgrepignore')))throw new Error('unreviewed .semgrepignore suppression present');
    const rulesRepo=path.join(tmp,'semgrep-rules');mkdirSync(rulesRepo);run('git',['init','-q'],{cwd:rulesRepo,env});run('git',['remote','add','origin','https://github.com/semgrep/semgrep-rules.git'],{cwd:rulesRepo,env});run('git',['fetch','--depth','1','origin',baseline.scanners.semgrep.rules.commit],{cwd:rulesRepo,env,timeout:600000});run('git',['checkout','-q','FETCH_HEAD'],{cwd:rulesRepo,env});const rulesHead=run('git',['rev-parse','HEAD'],{cwd:rulesRepo,env}).stdout.trim();if(rulesHead!==baseline.scanners.semgrep.rules.commit)throw new Error(`Semgrep rules head mismatch ${rulesHead}`);const rulesSelected=path.join(tmp,'semgrep-security-rules'),rulesMeta=copySecurityRules(rulesRepo,rulesSelected);const image=baseline.scanners.semgrep.installation.image;run('docker',['pull','--quiet',image],{env,timeout:900000});const imageId=run('docker',['image','inspect','--format={{.Id}}',image],{env}).stdout.trim(),repoDigests=JSON.parse(run('docker',['image','inspect','--format={{json .RepoDigests}}',image],{env}).stdout.trim()||'[]'),imageRepoDigest=repoDigests.find(x=>x.startsWith('semgrep/semgrep@sha256:'))||repoDigests[0]||null;if(!imageRepoDigest)throw new Error('Semgrep image RepoDigest unavailable');const sgVersion=run('docker',['run','--rm',image,'semgrep','--version'],{env,timeout:120000}).stdout.trim();if(!sgVersion.includes(baseline.scanners.semgrep.version))throw new Error(`Semgrep version mismatch ${sgVersion}`);const sgRaw=path.join(tmp,'semgrep.json');rr=run('docker',['run','--rm','-e','SEMGREP_SEND_METRICS=off','-v',`${root}:/src:ro`,'-v',`${rulesSelected}:/rules:ro`,image,'semgrep','scan','--json','--metrics=off','--disable-version-check','--strict','--config','/rules','/src'],{env,timeout:1200000,maxBuffer:512*1024*1024});writeFileSync(sgRaw,rr.stdout);const sgJson=J(sgRaw),sgReport=normalizeSemgrepReport(sgJson,baseline.scanners.semgrep.version),sgFindings=sgReport.findings;

    requireScannerCheckoutUnchanged(root,head,checkout);
    const scanners={
      osv:{scanCompleted:true,version:baseline.scanners.osv.version,sourceCommit:baseline.scanners.osv.sourceCommit,binarySha256:osvBinarySha256,inputPackageCount:oi.packageCount,coverage:osvCoverage,findingCount:osvFindings.length,findings:osvFindings,rawReportRetained:false},
      gitleaks:{scanCompleted:true,version:baseline.scanners.gitleaks.version,sourceCommit:baseline.scanners.gitleaks.sourceCommit,assetSha256:baseline.scanners.gitleaks.installation.sha256,mode:'FULL_GIT_HISTORY',redactionPercent:100,findingCount:glFindings.length,findings:glFindings,rawReportRetained:false,candidateSecretMaterialRetained:false},
      zizmor:{scanCompleted:true,version:baseline.scanners.zizmor.version,sourceCommit:baseline.scanners.zizmor.sourceCommit,wheelSha256:baseline.scanners.zizmor.installation.sha256,offline:true,collection:baseline.scanners.zizmor.collection,findingCount:zzFindings.length,findings:zzFindings,rawReportRetained:false},
      semgrep:{scanCompleted:true,version:baseline.scanners.semgrep.version,sourceCommit:baseline.scanners.semgrep.sourceCommit,imageId,imageRepoDigest,rulesCommit:rulesHead,ruleFileCount:rulesMeta.ruleFileCount,ruleContentSha256:rulesMeta.ruleContentSha256,metrics:'OFF',coverage:sgReport.coverage,findingCount:sgFindings.length,findings:sgFindings,rawReportRetained:false,sourceSnippetRetained:false}
    };
    const totalFindingCount=Object.values(scanners).reduce((n,x)=>n+x.findingCount,0);const p={schemaVersion:'M6_PR_E_E4_SCANNER_EVIDENCE_V1',repository:baseline.repository,commitSha:head,checkout,e2GraphDigest:graphDigest,e2CurrentContentSha256:e2.contentSha256,e2CurrentEvidence:e2,...(graphTransition?{e2GraphTransition:graphTransition}:{}),scannerBaselineSourceHead:baseline.sourceHead,scanners,totalFindingCount,scannerFindingTriageRequired:totalFindingCount>0,allScannersCompleted:true,rawScannerReportsRetained:false,candidateSecretMaterialRetained:false,authoritativeGitHubInventoryStillUnavailable:true,workstreamReleaseBlocked:true,reasonCodes:[...(totalFindingCount>0?['E4_SCANNER_FINDINGS_REQUIRE_E3_TRIAGE']:[]),'AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE'].sort()};return S({...p,contentSha256:H(C(p))});
  } finally { rmSync(tmp,{recursive:true,force:true}); }
}

function main(){const r=process.argv.find(x=>x.startsWith('--root='));const root=path.resolve(r?r.slice(7):rootFromHere);const e=scan(root),s=C(e);console.log('M6_PR_E_E4_SCANNER_EVIDENCE_BEGIN');console.log(s);console.log('M6_PR_E_E4_SCANNER_EVIDENCE_END');}
if(process.argv[1]&&fileURLToPath(import.meta.url)===path.resolve(process.argv[1]))main();
