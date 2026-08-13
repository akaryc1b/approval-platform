#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { acceptedE2GraphProjection, generateEvidence as generateE2Evidence } from './m6-pr-e-e2-generate-sbom.mjs';

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
function exactHead(){
  if(process.env.GITHUB_EVENT_PATH&&existsSync(process.env.GITHUB_EVENT_PATH)){const h=J(process.env.GITHUB_EVENT_PATH)?.pull_request?.head?.sha;if(SHA40.test(h||''))return h;}
  const h=process.env.M6_PR_E_E4_HEAD_SHA;if(SHA40.test(h||''))return h;
  throw new Error('E4 exact PR head unavailable');
}
function safeEnv(extra={}){const e={...process.env,...extra};for(const k of ['GH_TOKEN','GITHUB_TOKEN','ZIZMOR_GITHUB_TOKEN','SEMGREP_APP_TOKEN'])delete e[k];return e;}
function collectFiles(dir,predicate,out=[]){if(!existsSync(dir))return out;for(const n of readdirSync(dir).sort()){const f=path.join(dir,n),s=statSync(f);if(s.isDirectory())collectFiles(f,predicate,out);else if(predicate(f))out.push(f);}return out;}
function findingId(parts){return H(parts.join('\0'));}
export function e2GraphDigest(e2){return H(C(acceptedE2GraphProjection(e2)));}
function pluginPackage(coord){const p=coord.split(':');if(p.length<4)return null;return{name:`${p[0]}:${p[1]}`,version:p.at(-1),ecosystem:'Maven',sourceClass:'BUILD_PLUGIN'};}
function osvInputFromE2(e2){
  const map=new Map();const add=(name,version,ecosystem,componentRef,scope)=>{if(!name||!version)return;const k=`${ecosystem}\0${name}\0${version}`;if(!map.has(k))map.set(k,{package:{name,version,ecosystem},componentRefs:[],scopes:[]});const x=map.get(k);if(componentRef&&!x.componentRefs.includes(componentRef))x.componentRefs.push(componentRef);if(scope&&!x.scopes.includes(scope))x.scopes.push(scope);};
  for(const c of e2.maven.components||[])if(c.group!=='io.github.akaryc1b.approval')add(`${c.group}:${c.name}`,c.version,'Maven',c.bomRef,c.scope||'dependency');
  for(const b of e2.maven.importedBoms||[])add(`${b.group}:${b.name}`,b.version,'Maven',`pkg:maven/${b.group}/${b.name}@${b.version}?type=pom`,'import');
  for(const q of e2.maven.resolvedPluginCoordinates||[]){const p=pluginPackage(q);if(p)add(p.name,p.version,p.ecosystem,`maven-plugin:${q}`,'build-plugin');}
  for(const p of e2.pnpm.external||[])add(p.name,p.version,'npm',`pkg:npm/${encodeURIComponent(p.name)}@${p.version}`,p.scope||'unknown');
  const packages=[...map.values()].sort((a,b)=>`${a.package.ecosystem}:${a.package.name}:${a.package.version}`.localeCompare(`${b.package.ecosystem}:${b.package.name}:${b.package.version}`));
  return {scannerInput:{results:[{packages:packages.map(x=>({package:x.package}))}]},lookup:map,packageCount:packages.length};
}
function normalizeOsv(raw,lookup){
  const out=[];
  for(const result of raw.results||[])for(const entry of result.packages||[]){const p=entry.package||{};const key=`${p.ecosystem}\0${p.name}\0${p.version}`,m=lookup.get(key)||{componentRefs:[],scopes:[]};for(const v of entry.vulnerabilities||[]){const aliases=[...new Set(v.aliases||[])].sort();const severity=(v.severity||[]).map(x=>({type:String(x.type||''),score:String(x.score||'')}));const fixed=[...new Set((v.affected||[]).flatMap(a=>(a.ranges||[]).flatMap(r=>(r.events||[]).map(e=>e.fixed).filter(Boolean))))].sort();out.push({findingId:findingId(['OSV',v.id||'',p.ecosystem||'',p.name||'',p.version||'']),sourceClass:'E4_OSV_SCANNER',upstreamFindingId:String(v.id||''),aliases,package:{ecosystem:p.ecosystem,name:p.name,version:p.version},componentRefs:[...m.componentRefs].sort(),scopes:[...m.scopes].sort(),upstreamSeverity:severity,fixedVersions:fixed});}}
  return out.sort((a,b)=>a.findingId.localeCompare(b.findingId));
}
function normalizeGitleaks(raw){return (raw||[]).map(x=>({findingId:findingId(['GITLEAKS',x.Fingerprint||'',x.RuleID||'',x.File||'',String(x.StartLine||'')]),sourceClass:'E4_GITLEAKS',ruleId:String(x.RuleID||''),description:String(x.Description||''),path:String(x.File||''),startLine:x.StartLine??null,endLine:x.EndLine??null,commit:String(x.Commit||''),fingerprint:String(x.Fingerprint||'')})).sort((a,b)=>a.findingId.localeCompare(b.findingId));}
function normalizeZizmor(raw){const out=[];for(const r of raw.runs||[])for(const x of r.results||[]){const l=x.locations?.[0]?.physicalLocation||{},a=l.artifactLocation||{},g=l.region||{};out.push({findingId:findingId(['ZIZMOR',x.ruleId||'',a.uri||'',String(g.startLine||''),String(g.startColumn||'')]),sourceClass:'E4_ZIZMOR',ruleId:String(x.ruleId||''),upstreamSeverity:String(x.level||'warning'),path:String(a.uri||''),startLine:g.startLine??null,startColumn:g.startColumn??null,endLine:g.endLine??null,endColumn:g.endColumn??null});}return out.sort((a,b)=>a.findingId.localeCompare(b.findingId));}
function normalizeSemgrep(raw){return (raw.results||[]).map(x=>{const m=x.extra?.metadata||{};const safe=(v)=>Array.isArray(v)?v.map(String).sort():v==null?[]:[String(v)];return{findingId:findingId(['SEMGREP',x.check_id||'',x.path||'',String(x.start?.line||''),String(x.start?.col||'')]),sourceClass:'E4_SEMGREP',ruleId:String(x.check_id||''),upstreamSeverity:String(x.extra?.severity||'UNKNOWN'),path:String(x.path||''),startLine:x.start?.line??null,startColumn:x.start?.col??null,endLine:x.end?.line??null,endColumn:x.end?.col??null,cwe:safe(m.cwe),owasp:safe(m.owasp),category:m.category?String(m.category):null};}).sort((a,b)=>a.findingId.localeCompare(b.findingId));}
function copySecurityRules(repo,dest){
  const roots=['java','javascript','typescript'];let files=[];for(const r of roots)files.push(...collectFiles(path.join(repo,r),f=>/\.(ya?ml)$/.test(f)&&f.split(path.sep).includes('security')&&/^\s*rules:\s*$/m.test(readFileSync(f,'utf8'))));files=files.sort();if(!files.length)throw new Error('Semgrep security rule selection empty');
  const h=createHash('sha256');for(const f of files){const rel=path.relative(repo,f).replaceAll(path.sep,'/'),b=readFileSync(f);h.update(rel).update('\0').update(b).update('\0');const o=path.join(dest,rel);mkdirSync(path.dirname(o),{recursive:true});cpSync(f,o);}
  return{ruleFileCount:files.length,ruleContentSha256:h.digest('hex')};
}
export function scan(root=rootFromHere){
  const baseline=J(path.join(root,'docs/m6/m6-pr-e-e4-scanner-baseline.json')),head=exactHead(),tmp=mkdtempSync(path.join(os.tmpdir(),'m6-pr-e-e4-'));
  try{
    const e2=generateE2Evidence(root,{fullMaven:true});if(e2.commitSha!==head)throw new Error(`E2 head mismatch ${e2.commitSha} != ${head}`);const graphDigest=e2GraphDigest(e2);if(graphDigest!==baseline.inheritedE2GraphDigest)throw new Error(`E2 graph drift ${graphDigest}`);
    const env=safeEnv(),bin=path.join(tmp,'bin');mkdirSync(bin,{recursive:true});

    const goTar=path.join(tmp,'go.tgz'),goRoot=path.join(tmp,'go-root');mkdirSync(goRoot);run('curl',['--fail','--location','--silent','--show-error',baseline.scanners.osv.installation.goLinuxAmd64Url,'-o',goTar],{env});verifySha(goTar,baseline.scanners.osv.installation.goLinuxAmd64Sha256);run('tar',['-xzf',goTar,'-C',goRoot],{env});const go=path.join(goRoot,'go/bin/go'),goEnv={...env,GOTOOLCHAIN:'local',GOPATH:path.join(tmp,'gopath'),GOBIN:bin,GOPROXY:'https://proxy.golang.org,direct',GOSUMDB:'sum.golang.org'};run(go,['version'],{env:goEnv});run(go,['install',baseline.scanners.osv.installation.module],{env:goEnv,timeout:1200000});const osv=path.join(bin,'osv-scanner'),osvVersion=run(osv,['--version'],{env}).stdout.trim()||run(osv,['version'],{env}).stdout.trim();if(!osvVersion.includes(baseline.scanners.osv.version))throw new Error(`OSV version mismatch ${osvVersion}`);const osvBinarySha256=H(readFileSync(osv));const oi=osvInputFromE2(e2),osvInput=path.join(tmp,'osv-scanner.json'),osvRaw=path.join(tmp,'osv.json');writeFileSync(osvInput,JSON.stringify(oi.scannerInput));let rr=run(osv,['scan','--format','json','--lockfile',`osv-scanner:${osvInput}`],{cwd:root,env,allow:[1]});writeFileSync(osvRaw,rr.stdout);const osvJson=J(osvRaw),osvFindings=normalizeOsv(osvJson,oi.lookup);

    for(const f of ['.gitleaksignore','.gitleaks.toml'])if(existsSync(path.join(root,f)))throw new Error(`unreviewed Gitleaks suppression/config present: ${f}`);
    const glTar=path.join(tmp,'gitleaks.tgz');run('curl',['--fail','--location','--silent','--show-error',baseline.scanners.gitleaks.installation.url,'-o',glTar],{env});verifySha(glTar,baseline.scanners.gitleaks.installation.sha256);run('tar',['-xzf',glTar,'-C',bin],{env});const gitleaks=path.join(bin,'gitleaks'),glVersion=run(gitleaks,['version'],{env}).stdout.trim();if(!glVersion.includes(baseline.scanners.gitleaks.version))throw new Error(`Gitleaks version mismatch ${glVersion}`);const glRaw=path.join(tmp,'gitleaks.json');rr=run(gitleaks,['git','--redact=100','--no-banner','--log-opts=--all','--report-format','json','--report-path',glRaw,'.'],{cwd:root,env,allow:[1],timeout:900000});if(!existsSync(glRaw))writeFileSync(glRaw,'[]');const glFindings=normalizeGitleaks(J(glRaw));

    const wheel=path.join(tmp,path.basename(new URL(baseline.scanners.zizmor.installation.url).pathname));run('curl',['--fail','--location','--silent','--show-error',baseline.scanners.zizmor.installation.url,'-o',wheel],{env});verifySha(wheel,baseline.scanners.zizmor.installation.sha256);const venv=path.join(tmp,'zizmor-venv');run('python3',['-m','venv',venv],{env});run(path.join(venv,'bin/pip'),['install','--disable-pip-version-check','--no-deps',wheel],{env,timeout:600000});const zizmor=path.join(venv,'bin/zizmor'),zzVersion=run(zizmor,['--version'],{env}).stdout.trim();if(!zzVersion.includes(baseline.scanners.zizmor.version))throw new Error(`zizmor version mismatch ${zzVersion}`);const zzRaw=path.join(tmp,'zizmor.sarif');rr=run(zizmor,['--offline','--strict-collection','--collect=workflows,actions,dependabot','--format=sarif',root],{cwd:root,env,timeout:600000});writeFileSync(zzRaw,rr.stdout);const zzJson=J(zzRaw),zzFindings=normalizeZizmor(zzJson);

    if(existsSync(path.join(root,'.semgrepignore')))throw new Error('unreviewed .semgrepignore suppression present');
    const rulesRepo=path.join(tmp,'semgrep-rules');mkdirSync(rulesRepo);run('git',['init','-q'],{cwd:rulesRepo,env});run('git',['remote','add','origin','https://github.com/semgrep/semgrep-rules.git'],{cwd:rulesRepo,env});run('git',['fetch','--depth','1','origin',baseline.scanners.semgrep.rules.commit],{cwd:rulesRepo,env,timeout:600000});run('git',['checkout','-q','FETCH_HEAD'],{cwd:rulesRepo,env});const rulesHead=run('git',['rev-parse','HEAD'],{cwd:rulesRepo,env}).stdout.trim();if(rulesHead!==baseline.scanners.semgrep.rules.commit)throw new Error(`Semgrep rules head mismatch ${rulesHead}`);const rulesSelected=path.join(tmp,'semgrep-security-rules'),rulesMeta=copySecurityRules(rulesRepo,rulesSelected);const image=baseline.scanners.semgrep.installation.image;run('docker',['pull','--quiet',image],{env,timeout:900000});const imageId=run('docker',['image','inspect','--format={{.Id}}',image],{env}).stdout.trim(),repoDigests=JSON.parse(run('docker',['image','inspect','--format={{json .RepoDigests}}',image],{env}).stdout.trim()||'[]'),imageRepoDigest=repoDigests.find(x=>x.startsWith('semgrep/semgrep@sha256:'))||repoDigests[0]||null;if(!imageRepoDigest)throw new Error('Semgrep image RepoDigest unavailable');const sgVersion=run('docker',['run','--rm',image,'semgrep','--version'],{env,timeout:120000}).stdout.trim();if(!sgVersion.includes(baseline.scanners.semgrep.version))throw new Error(`Semgrep version mismatch ${sgVersion}`);const sgRaw=path.join(tmp,'semgrep.json');rr=run('docker',['run','--rm','-e','SEMGREP_SEND_METRICS=off','-v',`${root}:/src:ro`,'-v',`${rulesSelected}:/rules:ro`,image,'semgrep','scan','--json','--metrics=off','--disable-version-check','--strict','--config','/rules','/src'],{env,timeout:1200000,maxBuffer:512*1024*1024});writeFileSync(sgRaw,rr.stdout);const sgJson=J(sgRaw);if((sgJson.errors||[]).length)throw new Error(`Semgrep returned ${sgJson.errors.length} scan errors`);const sgFindings=normalizeSemgrep(sgJson);

    const scanners={
      osv:{scanCompleted:true,version:baseline.scanners.osv.version,sourceCommit:baseline.scanners.osv.sourceCommit,binarySha256:osvBinarySha256,inputPackageCount:oi.packageCount,findingCount:osvFindings.length,findings:osvFindings,rawReportRetained:false},
      gitleaks:{scanCompleted:true,version:baseline.scanners.gitleaks.version,sourceCommit:baseline.scanners.gitleaks.sourceCommit,assetSha256:baseline.scanners.gitleaks.installation.sha256,mode:'FULL_GIT_HISTORY',redactionPercent:100,findingCount:glFindings.length,findings:glFindings,rawReportRetained:false,candidateSecretMaterialRetained:false},
      zizmor:{scanCompleted:true,version:baseline.scanners.zizmor.version,sourceCommit:baseline.scanners.zizmor.sourceCommit,wheelSha256:baseline.scanners.zizmor.installation.sha256,offline:true,collection:baseline.scanners.zizmor.collection,findingCount:zzFindings.length,findings:zzFindings,rawReportRetained:false},
      semgrep:{scanCompleted:true,version:baseline.scanners.semgrep.version,sourceCommit:baseline.scanners.semgrep.sourceCommit,imageId,imageRepoDigest,rulesCommit:rulesHead,ruleFileCount:rulesMeta.ruleFileCount,ruleContentSha256:rulesMeta.ruleContentSha256,metrics:'OFF',findingCount:sgFindings.length,findings:sgFindings,rawReportRetained:false,sourceSnippetRetained:false}
    };
    const totalFindingCount=Object.values(scanners).reduce((n,x)=>n+x.findingCount,0);const p={schemaVersion:'M6_PR_E_E4_SCANNER_EVIDENCE_V1',repository:baseline.repository,commitSha:head,e2GraphDigest:graphDigest,e2CurrentContentSha256:e2.contentSha256,scannerBaselineSourceHead:baseline.sourceHead,scanners,totalFindingCount,scannerFindingTriageRequired:totalFindingCount>0,allScannersCompleted:true,rawScannerReportsRetained:false,candidateSecretMaterialRetained:false,authoritativeGitHubInventoryStillUnavailable:true,workstreamReleaseBlocked:true,reasonCodes:[...(totalFindingCount>0?['E4_SCANNER_FINDINGS_REQUIRE_E3_TRIAGE']:[]),'AUTHORITATIVE_GITHUB_ALERT_INVENTORY_EVIDENCE_UNAVAILABLE'].sort()};return S({...p,contentSha256:H(C(p))});
  } finally { rmSync(tmp,{recursive:true,force:true}); }
}

function main(){const r=process.argv.find(x=>x.startsWith('--root='));const root=path.resolve(r?r.slice(7):rootFromHere);const e=scan(root),s=C(e);console.log('M6_PR_E_E4_SCANNER_EVIDENCE_BEGIN');console.log(s);console.log('M6_PR_E_E4_SCANNER_EVIDENCE_END');}
if(process.argv[1]&&fileURLToPath(import.meta.url)===path.resolve(process.argv[1]))main();
