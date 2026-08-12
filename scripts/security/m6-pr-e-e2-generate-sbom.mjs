#!/usr/bin/env node
import{createHash}from'node:crypto';import{existsSync,mkdtempSync,readFileSync,readdirSync,rmSync}from'node:fs';import os from'node:os';import path from'node:path';import{spawnSync}from'node:child_process';import{fileURLToPath}from'node:url';
const PLUGIN='org.apache.maven.plugins:maven-dependency-plugin:3.11.0',EXPECTED_REACTOR_PROJECTS = 26,SHA=/^[0-9a-f]{40}$/;const PNPM_PACKAGE_METADATA={typescript:{version:'5.9.3',license:'Apache-2.0',source:'https://github.com/microsoft/TypeScript',sourceRef:'v5.9.3',sourceManifestBlobSha:'f7f35370bc8ad447e488e01cd10acbc606549cd8'}};
const J=f=>JSON.parse(readFileSync(f,'utf8')),H=x=>createHash('sha256').update(x).digest('hex'),S=v=>Array.isArray(v)?v.map(S):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,S(v[k])])):v,C=v=>JSON.stringify(S(v)),B=b=>createHash('sha1').update(`blob ${b.length}\0`).update(b).digest('hex');
function head(r){let p=process.env.GITHUB_EVENT_PATH;if(p&&existsSync(p)){let s=J(p)?.pull_request?.head?.sha;if(SHA.test(s||''))return s}let s=process.env.M6_PR_E_E2_HEAD_SHA;if(SHA.test(s||''))return s;let g=spawnSync('git',['rev-parse','HEAD'],{cwd:r,encoding:'utf8'});if(!g.status&&SHA.test(g.stdout.trim()))return g.stdout.trim();throw Error('E2 exact head unavailable')}
function pnpm(r){let P=['package.json','packages/approval-sdk/package.json','packages/contracts/package.json','packages/form-schema/package.json','packages/process-dsl/package.json','examples/connector-smoke/package.json'];if(P.length!==6)throw Error('Expected 6 pnpm workspace projects');let pk=P.map(p=>{let d=J(path.join(r,p)),a=[];for(let[k,f]of[['production','dependencies'],['development','devDependencies'],['optional','optionalDependencies'],['peer','peerDependencies']])for(let[n,v]of Object.entries(d[f]||{}))a.push({name:n,version:String(v),scope:k});return{path:p,name:d.name,version:d.version??null,dependencies:a}}),N=new Set(pk.map(x=>x.name)),e=[];for(let p of pk)for(let d of p.dependencies)if(!N.has(d.name)&&!d.version.startsWith('workspace:'))e.push({...d,requestedBy:p.path});if(!existsSync(path.join(r,'pnpm-lock.yaml'))&&e.some(x=>!/^\d+\.\d+\.\d+$/.test(x.version)))throw Error('non-exact pnpm without lockfile');e=e.map(x=>{let m=PNPM_PACKAGE_METADATA[x.name];if(!m||m.version!==x.version)throw Error(`pnpm metadata unavailable ${x.name}`);return{...x,...m}});return{packageManager:J(path.join(r,'package.json')).packageManager,workspaceProjectCount:pk.length,packages:pk,external:e,lockfile:existsSync(path.join(r,'pnpm-lock.yaml'))?{state:'COMMITTED',sha256:H(readFileSync(path.join(r,'pnpm-lock.yaml')))}:{state:'ROOT_PNPM_LOCKFILE_ABSENT',integrity:'PACKAGE_TARBALL_INTEGRITY_NOT_REPOSITORY_PINNED'},generatedUpstreamPolicy:'EXCLUDED_FROM_FIRST_PARTY_GRAPH_RETAIN_BOOTSTRAP_BOUNDARIES_SEPARATELY'}}
const LEGACY_ACTION_LIMITATIONS=['ROOT_PNPM_LOCKFILE_ABSENT','PACKAGE_TARBALL_INTEGRITY_NOT_REPOSITORY_PINNED','GITHUB_ACTION_MAJOR_TAGS_REMAIN_MUTABLE_UNTIL_FUTURE_PINNING_REVIEW','E2_DOES_NOT_PROVE_VULNERABILITY_APPLICABILITY_OR_REACHABILITY','PRB_16_REMAINS_OPEN','PRB_17_REMAINS_OPEN'].sort();
const R2B_ACTION_LIMITATIONS=['ROOT_PNPM_LOCKFILE_ABSENT','PACKAGE_TARBALL_INTEGRITY_NOT_REPOSITORY_PINNED','GITHUB_ACTIONS_PINNED_TO_REVIEWED_IMMUTABLE_COMMITS_R2B','E2_DOES_NOT_PROVE_VULNERABILITY_APPLICABILITY_OR_REACHABILITY','PRB_16_REMAINS_OPEN','PRB_17_REMAINS_OPEN'].sort();
function workflowUses(raw){return[...raw.toString().matchAll(/^\s*-?\s*uses:\s*([^\s#]+)(?:\s+#\s*([^\s#]+))?\s*$/gm)].map(m=>({declared:m[1],versionComment:m[2]??null}))}
function baselineActionEvidence(b,workflows){return{workflowCount:workflows.length,automaticWorkflowCount:workflows.filter(x=>x.automatic).length,workflows,maintenancePullRequests:b.maintenancePullRequests,interpretation:b.interpretation}}
function acceptedGraphEvidence(b,workflows){const p={schemaVersion:'M6_PR_E_E2_ACCEPTED_GITHUB_ACTIONS_GRAPH_V1',sourceHead:b.sourceHead,githubActions:baselineActionEvidence(b,workflows),limitations:LEGACY_ACTION_LIMITATIONS};return S({...p,contentSha256:H(C(p))})}
export function resolveGitHubActionsEvidence(r){
  const b=J(path.join(r,'docs/m6/m6-pr-e-e2-action-resolution-baseline.json'));
  if(b.schemaVersion!=='M6_PR_E_E2_ACTION_RESOLUTION_BASELINE_V1'||b.repository!=='akaryc1b/approval-platform')throw Error('E2 Action baseline identity mismatch');
  const baselinePaths=b.workflowFiles.map(x=>x.path).sort(),workflowDir=path.join(r,'.github/workflows'),currentPaths=readdirSync(workflowDir).filter(x=>/\.ya?ml$/.test(x)).map(x=>`.github/workflows/${x}`).sort();
  if(C(baselinePaths)!==C(currentPaths))throw Error('workflow inventory drift');
  const planPath=path.join(r,'docs/m6/m6-pr-e-e3-r2b-workflow-supply-chain-remediation.json'),plan=existsSync(planPath)?J(planPath):null;
  let targetBlobs=null;
  if(plan){
    if(plan.schemaVersion!=='M6_PR_E_E3_R2B_WORKFLOW_SUPPLY_CHAIN_REMEDIATION_PLAN_V1'||plan.repository!==b.repository)throw Error('R2B workflow plan identity mismatch');
    const sourceBlobs=plan.workflowInventory?.sourceBlobs,target=plan.workflowInventory?.targetBlobs;
    if(!sourceBlobs||!target||plan.workflowInventory.expectedFileCount!==b.workflowFiles.length||C(Object.keys(sourceBlobs).sort())!==C(baselinePaths)||C(Object.keys(target).sort())!==C(baselinePaths))throw Error('R2B workflow inventory mismatch');
    for(const item of b.workflowFiles)if(sourceBlobs[item.path]!==item.blobSha)throw Error(`R2B source workflow blob mismatch ${item.path}`);
    targetBlobs=target;
  }
  const states=[],currentWorkflows=[],acceptedWorkflows=[];
  for(const item of b.workflowFiles){
    const raw=readFileSync(path.join(r,item.path)),blobSha=B(raw),state=blobSha===item.blobSha?'SOURCE':targetBlobs&&blobSha===targetBlobs[item.path]?'TARGET':null;
    if(!state)throw Error(`workflow blob drift ${item.path}`);
    states.push(state);
    const currentActions=[],acceptedActions=[];
    for(const use of workflowUses(raw)){
      const declared=use.declared;
      if(declared.startsWith('./')||declared.startsWith('docker://')){
        const action={declared,resolvedCommit:null,mutableRef:!SHA.test(declared.split('@').at(-1))};
        currentActions.push(action);acceptedActions.push(action);continue;
      }
      const at=declared.lastIndexOf('@'),repository=at>0?declared.slice(0,at):'',ref=at>0?declared.slice(at+1):'';
      if(state==='SOURCE'){
        const resolved=b.actionRefs[declared];
        if(!resolved||!SHA.test(resolved))throw Error(`unresolved action ${declared}`);
        const action={declared,resolvedCommit:resolved,mutableRef:true};
        currentActions.push(action);acceptedActions.push(action);continue;
      }
      const pin=plan.actionPins?.[repository],priorDeclared=pin?`${repository}@${pin.priorSymbolicRef}`:null,baselineResolved=priorDeclared?b.actionRefs[priorDeclared]:null;
      if(!pin||!SHA.test(ref)||pin.reviewedImmutableSha!==ref||pin.symbolicRefResolvedSha!==ref||baselineResolved!==ref||pin.priorSymbolicRef!=='v4'||pin.versionComment!=='v4'||use.versionComment!==pin.versionComment||pin.upstreamSymbolicRefDrift!==false||pin.repositoryIdentityVerified!==true||pin.commitExists!==true)throw Error(`unapproved immutable action ${declared}`);
      currentActions.push({declared,resolvedCommit:ref,mutableRef:false,priorDeclared,versionComment:use.versionComment});
      acceptedActions.push({declared:priorDeclared,resolvedCommit:ref,mutableRef:true});
    }
    currentWorkflows.push({path:item.path,blobSha,automatic:item.automatic,actions:currentActions});
    acceptedWorkflows.push({path:item.path,blobSha:item.blobSha,automatic:item.automatic,actions:acceptedActions});
  }
  const uniqueStates=[...new Set(states)];if(uniqueStates.length!==1)throw Error(`mixed workflow remediation state ${uniqueStates.join(',')}`);
  if(uniqueStates[0]==='SOURCE')return baselineActionEvidence(b,currentWorkflows);
  const accepted=acceptedGraphEvidence(b,acceptedWorkflows),current={workflowCount:currentWorkflows.length,automaticWorkflowCount:currentWorkflows.filter(x=>x.automatic).length,workflows:currentWorkflows,maintenancePullRequests:b.maintenancePullRequests,interpretation:{...b.interpretation,mutableMajorRefsRemainReleaseInputs:false,reviewedImmutableRefsBoundToR2BPlan:true},workflowSecurityState:'R2B_REVIEWED_IMMUTABLE_TARGET',r2bPlanCanonicalSha256:H(C(plan)),acceptedDependencyGraph:accepted};
  const external=currentWorkflows.flatMap(x=>x.actions).filter(x=>x.declared.startsWith('actions/'));
  if(external.length!==43||external.some(x=>x.mutableRef||!SHA.test(x.resolvedCommit||'')||x.versionComment!=='v4'))throw Error('R2B external Action inventory mismatch');
  return S(current);
}
function actions(r){return resolveGitHubActionsEvidence(r)}
export function acceptedE2GraphProjection(e2){
  const accepted=e2?.githubActions?.acceptedDependencyGraph;
  if(!accepted)return S({maven:e2.maven,pnpm:e2.pnpm,githubActions:e2.githubActions,limitations:e2.limitations});
  if(e2.githubActions.workflowSecurityState!=='R2B_REVIEWED_IMMUTABLE_TARGET'||accepted.schemaVersion!=='M6_PR_E_E2_ACCEPTED_GITHUB_ACTIONS_GRAPH_V1')throw Error('E2 accepted Action graph state mismatch');
  if(!/^[0-9a-f]{64}$/.test(e2.githubActions.r2bPlanCanonicalSha256||'')||e2.githubActions.workflowCount!==9||e2.githubActions.automaticWorkflowCount!==1)throw Error('E2 R2B target identity mismatch');
  const currentExternal=e2.githubActions.workflows.flatMap(x=>x.actions).filter(x=>x.declared.startsWith('actions/'));
  if(currentExternal.length!==43||currentExternal.some(x=>x.mutableRef!==false||!SHA.test(x.resolvedCommit||'')||x.versionComment!=='v4'))throw Error('E2 R2B current Action state mismatch');
  const {contentSha256,...payload}=accepted;if(!/^[0-9a-f]{64}$/.test(contentSha256||'')||H(C(payload))!==contentSha256)throw Error('E2 accepted Action graph canonical mismatch');
  if(accepted.githubActions?.workflowCount!==9||accepted.githubActions?.automaticWorkflowCount!==1)throw Error('E2 accepted Action graph inventory mismatch');
  return S({maven:e2.maven,pnpm:e2.pnpm,githubActions:accepted.githubActions,limitations:accepted.limitations});
}
function seq(t){let o=[],s=-1,d=0,q=0,e=0;for(let i=0;i<t.length;i++){let c=t[i];if(s<0){if(c==='{')s=i,d=1;continue}if(q){if(e)e=0;else if(c==='\\')e=1;else if(c==='"')q=0;continue}if(c==='"'){q=1;continue}if(c==='{'||c==='[')d++;if(c==='}'||c===']')d--;if(!d){try{o.push(JSON.parse(t.slice(s,i+1)))}catch{}s=-1}}return o}
function boms(r){let x=readFileSync(path.join(r,'pom.xml'),'utf8'),p={},pb=x.match(/<properties>([\s\S]*?)<\/properties>/)?.[1]||'';for(let m of pb.matchAll(/<([\w.-]+)>\s*([^<]+?)\s*<\/\1>/g))p[m[1]]=m[2].trim();let dm=x.match(/<dependencyManagement>([\s\S]*?)<\/dependencyManagement>/)?.[1]||'',o=[];for(let m of dm.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)){let z=m[1],v=t=>z.match(new RegExp(`<${t}>\\s*([^<]+?)\\s*</${t}>`))?.[1]?.trim();if(v('type')==='pom'&&v('scope')==='import')o.push({group:v('groupId'),name:v('artifactId'),version:v('version').replace(/\$\{([^}]+)\}/g,(_,k)=>p[k]),scope:'import'})}return o.sort((a,b)=>a.name.localeCompare(b.name))}
function lic(g,a,v){if(g==='io.github.akaryc1b.approval')return['Apache-2.0'];let repo=process.env.M6_PR_E_E2_MAVEN_REPOSITORY||path.join(os.homedir(),'.m2','repository'),f=path.join(repo,...g.split('.'),a,v,`${a}-${v}.pom`);if(!existsSync(f))return['EVIDENCE_UNAVAILABLE'];let x=readFileSync(f,'utf8'),b=x.match(/<licenses>([\s\S]*?)<\/licenses>/)?.[1]||'',l=[...b.matchAll(/<license>[\s\S]*?<name>\s*([^<]+?)\s*<\/name>[\s\S]*?<\/license>/g)].map(m=>m[1].trim()).filter(Boolean);return l.length?[...new Set(l)].sort():['EVIDENCE_UNAVAILABLE']}
function flat(n,c=new Map(),e=new Set(),p=null){let t=n.type||'jar',ref=`pkg:maven/${n.groupId}/${n.artifactId}@${n.version}?type=${t}`;c.set(ref,{bomRef:ref,group:n.groupId,name:n.artifactId,version:n.version,type:t,scope:n.scope??null,licenses:lic(n.groupId,n.artifactId,n.version),source:`maven:${n.groupId}:${n.artifactId}:${n.version}`});if(p)e.add(`${p}\0${ref}`);for(let x of n.children||[])flat(x,c,e,ref);return{ref,c,e}}
function maven(r){let t=mkdtempSync(path.join(os.tmpdir(),'e2-'));try{let f=path.join(t,'t.json'),p=path.join(t,'p.txt'),a=['-B','-ntp'];let z=spawnSync('mvn',[...a,`${PLUGIN}:tree`,'-DoutputType=json','-DappendOutput=true',`-DoutputFile=${f}`],{cwd:r,encoding:'utf8',maxBuffer:134217728,timeout:240000});if(z.status)throw Error(z.stderr||z.stdout);let R=seq(readFileSync(f,'utf8'));if(R.length!==EXPECTED_REACTOR_PROJECTS)throw Error(`expected ${EXPECTED_REACTOR_PROJECTS} roots`);z=spawnSync('mvn',[...a,`${PLUGIN}:resolve-plugins`,'-DappendOutput=true',`-DoutputFile=${p}`],{cwd:r,encoding:'utf8',maxBuffer:134217728,timeout:240000});if(z.status||!existsSync(p))throw Error(z.stderr||z.stdout);let c=new Map(),e=new Set(),rr=[];for(let n of R){let x=flat(n);rr.push(x.ref);for(let[k,v]of x.c)c.set(k,v);for(let y of x.e)e.add(y)}let q=readFileSync(p,'utf8'),pc=[...new Set([...q.matchAll(/\b([\w.-]+):([\w.-]+):([\w.-]+):([\w.-]+)(?::([\w.-]+))?\b/g)].map(x=>x[0]))].sort();return{dependencyPlugin:PLUGIN,reactorProjectCount:R.length,reactorRoots:[...new Set(rr)].sort(),importedBoms:boms(r),components:[...c.values()].sort((a,b)=>a.bomRef.localeCompare(b.bomRef)),edges:[...e].sort().map(x=>{let[from,to]=x.split('\0');return{from,to}}),resolvedPluginCoordinates:pc,pluginResolutionSha256:H(q),licensePolicy:'RESOLVED_POM_METADATA_OR_EVIDENCE_UNAVAILABLE_FIRST_PARTY_APACHE_2'}}finally{rmSync(t,{recursive:true,force:true})}}
export function generateEvidence(r,{fullMaven=false}={}){let P=pnpm(r),A=actions(r),M=fullMaven?maven(r):{dependencyPlugin:PLUGIN,reactorProjectCount:EXPECTED_REACTOR_PROJECTS,importedBoms:boms(r),state:'STATIC_CONTRACT_ONLY_FULL_RESOLUTION_REQUIRED_IN_GITHUB_ACTIONS'},limitations=A.workflowSecurityState==='R2B_REVIEWED_IMMUTABLE_TARGET'?R2B_ACTION_LIMITATIONS:LEGACY_ACTION_LIMITATIONS,p={schemaVersion:'M6_PR_E_E2_SBOM_V1',repository:'akaryc1b/approval-platform',commitSha:head(r),maven:M,pnpm:P,githubActions:A,limitations};return S({...p,contentSha256:H(C(p))})}
function main(){let a=new Set(process.argv.slice(2)),x=process.argv.find(v=>v.startsWith('--root=')),r=path.resolve(x?x.slice(7):process.cwd()),s=C(generateEvidence(r,{fullMaven:a.has('--full-maven')}));process.stdout.write(a.has('--markers')?`M6_PR_E_E2_SBOM_BEGIN\n${s}\nM6_PR_E_E2_SBOM_END\n`:`${s}\n`)}if(process.argv[1]&&fileURLToPath(import.meta.url)===path.resolve(process.argv[1]))main();
