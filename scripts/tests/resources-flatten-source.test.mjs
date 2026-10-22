import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { syncBuiltinESMExports } from 'node:module';
import childProcess from 'node:child_process';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import test from 'node:test';
const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const SDK = 'integrations/host-sdk/pom.xml';
const OLD_ROOT = 'docs/operations/assembly-enforcer-lang-evidence/candidate-root-pom.xml';
const OLD_SDK = 'docs/operations/resources-flatten-evidence/accepted-main905-host-sdk-pom.xml';
const HELPER = 'scripts/security/jar-plugin-source-continuation.mjs';
const MAIN = 'ddb286b44b1976dd19ad74b06f3ceef86e19d0fbee87dd27dc87985ffb1f7932';
const OLD = 'e755e3a2abd6d2df15b7e5739b10cd81f9a59476d8c21e6dc231961145b2d701';
const sha = b => createHash('sha256').update(b).digest('hex');
const read = p => fs.readFileSync(join(ROOT, p));
const original = Object.fromEntries(['pom.xml', SDK, OLD_ROOT, OLD_SDK].map(p => [p, read(p)]));
const refusal = /byte digest mismatch|safe relative source path/;
async function fixture(t, full = false) {
  const directory = fs.mkdtempSync(join(tmpdir(), 'resources-flatten-source-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  if (full) {
    for (const name of ['scripts', 'docs', 'config', 'packages', 'server-modules', 'integrations', 'apps', 'examples', '.github', 'pnpm-lock.yaml', 'package.json', 'pnpm-workspace.yaml']) {
      if (fs.existsSync(join(ROOT, name))) fs.cpSync(join(ROOT, name), join(directory, name), { recursive: true });
    }
  }
  for (const name of [HELPER, 'pom.xml', SDK, OLD_ROOT, OLD_SDK,
    'docs/operations/jar-plugin-evidence/candidate-root-pom.xml',
    'docs/operations/assembly-plexus-evidence/candidate-root-pom.xml']) {
    fs.mkdirSync(dirname(join(directory, name)), { recursive: true });
    fs.copyFileSync(join(ROOT, name), join(directory, name));
  }
  const api = await import(pathToFileURL(join(directory, HELPER)));
  assert.deepEqual(api.readMain905Source('pom.xml'), original[OLD_ROOT]);
  assert.deepEqual(api.readMain905Source(SDK), original[OLD_SDK]);
  return { directory, api, write: (p, bytes) => fs.writeFileSync(join(directory, p), bytes) };
}
function pure(action) {
  const changes=[];
  for (const [module, names] of [[childProcess, ['spawn','spawnSync','exec','execSync','execFile','execFileSync','fork']],
    [http,['request','get']],[https,['request','get']],[net,['connect','createConnection']]]) {
    for (const name of names) { changes.push([module,name,module[name]]); module[name]=()=>{throw new Error('unexpected execution or network');}; }
  }
  const fetch=globalThis.fetch; globalThis.fetch=()=>{throw new Error('unexpected fetch');}; syncBuiltinESMExports();
  try { return action(); } finally { for(const [module,name,value]of changes) module[name]=value; globalThis.fetch=fetch; syncBuiltinESMExports(); }
}

test('Resources/Flatten POM normalization freshly reads exactly four files and executes nothing', async t => {
  const f=await fixture(t);const saved=fs.readFileSync;const calls=[];
  fs.readFileSync=(path,...args)=>{calls.push(String(path));return saved(path,...args);};syncBuiltinESMExports();
  try {
    pure(()=>assert.deepEqual(f.api.readMain905Source('pom.xml'),original[OLD_ROOT]));
    assert.deepEqual(calls.sort(),['pom.xml',SDK,OLD_ROOT,OLD_SDK].map(p=>join(f.directory,p)).sort());
  } finally { fs.readFileSync=saved;syncBuiltinESMExports(); }
});
for(const path of ['pom.xml',SDK,OLD_ROOT,OLD_SDK])test(`Resources/Flatten rereads ${path} after success`,async t=>{
  const f=await fixture(t);f.write(path,Buffer.concat([original[path],Buffer.from('\n')]));
  for(const target of ['pom.xml',SDK])assert.throws(()=>f.api.readMain905Source(target),refusal);
  f.write(path,original[path]);assert.equal(sha(f.api.readMain905Source(SDK)),OLD);
});
test('Resources/Flatten rejects both mixed pairs before historical expected-byte shortcuts',async t=>{
  const f=await fixture(t);
  for(const [root,sdk]of [[original[OLD_ROOT],original[SDK]],[original['pom.xml'],original[OLD_SDK]]]){
    f.write('pom.xml',root);f.write(SDK,sdk);
    assert.throws(()=>f.api.readPreservedJarSource('pom.xml',sha(root)),refusal);
    assert.throws(()=>f.api.preservedHygieneJava21Source('pom.xml',sha(root),root),refusal);
    assert.throws(()=>f.api.readMain905Source(SDK),refusal);
  }
});
test('Resources/Flatten rejects stale caller bytes even when fresh pair is otherwise accepted',async t=>{
  const f=await fixture(t);
  assert.throws(()=>f.api.preservedHygieneJava21Source('pom.xml',MAIN,original[OLD_ROOT]),/stale supplied bytes/);
  assert.throws(()=>f.api.preservedHygieneJava21Source(SDK,OLD,original[OLD_SDK]),/stale supplied bytes/);
  assert.deepEqual(f.api.preservedHygieneJava21Source(SDK,OLD,original[SDK]),original[OLD_SDK]);
  f.write('pom.xml',original[OLD_ROOT]);f.write(SDK,original[OLD_SDK]);
  assert.throws(()=>f.api.preservedHygieneJava21Source('pom.xml',MAIN,original['pom.xml']),/stale supplied bytes/);
  assert.throws(()=>f.api.preservedHygieneJava21Source(SDK,OLD,original[SDK]),/stale supplied bytes/);
  assert.deepEqual(f.api.preservedHygieneJava21Source(SDK,OLD,original[OLD_SDK]),original[OLD_SDK]);
  assert.deepEqual(f.api.preservedHygieneJava21Source('pom.xml',MAIN,original[OLD_ROOT]),original[OLD_ROOT]);
});
test('Resources/Flatten rejects joint current/original edits and changed source witness data',async t=>{
  const f=await fixture(t);
  for(const [path,archive]of [['pom.xml',OLD_ROOT],[SDK,OLD_SDK]]){
    f.write(path,Buffer.concat([original[path],Buffer.from('\n')]));f.write(archive,Buffer.concat([original[archive],Buffer.from('\n')]));
    assert.throws(()=>f.api.readMain905Source('pom.xml'),refusal);
    f.write(path,original[path]);f.write(archive,original[archive]);
  }
});
test('Resources/Flatten candidate rejects missing SDK, symlinks, unsafe paths and oversize originals',async t=>{
  const f=await fixture(t);const sdk=join(f.directory,SDK);
  fs.unlinkSync(sdk);assert.throws(()=>f.api.readMain905Source('pom.xml'),refusal);
  fs.symlinkSync(join(f.directory,OLD_SDK),sdk);assert.throws(()=>f.api.readMain905Source('pom.xml'),refusal);
  fs.unlinkSync(sdk);f.write(SDK,original[SDK]);
  const real=join(f.directory,'integrations-real');fs.renameSync(join(f.directory,'integrations'),real);
  fs.symlinkSync(real,join(f.directory,'integrations'));assert.throws(()=>f.api.readMain905Source('pom.xml'),refusal);
  fs.unlinkSync(join(f.directory,'integrations'));fs.renameSync(real,join(f.directory,'integrations'));
  f.write(OLD_SDK,Buffer.alloc(2*1024*1024+1));assert.throws(()=>f.api.readMain905Source(SDK),refusal);
  for(const path of ['../pom.xml','/pom.xml','./pom.xml','a//pom.xml','a\\pom.xml'])assert.throws(()=>f.api.readMain905Source(path),refusal);
});
test('Resources/Flatten preserves historical root-only inputs without introducing archive requirements',async t=>{
  const f=await fixture(t);f.write('pom.xml',original[OLD_ROOT]);fs.unlinkSync(join(f.directory,SDK));fs.unlinkSync(join(f.directory,OLD_SDK));
  assert.deepEqual(f.api.readMain905Source('pom.xml'),original[OLD_ROOT]);
  assert.deepEqual(f.api.preservedHygieneJava21Source('pom.xml',MAIN,original[OLD_ROOT]),original[OLD_ROOT]);
});
test('all nine existing historical source witnesses accept the real candidate through the shared boundary',async()=>{
  for(const [name,reader]of [['compiler-plugin','readCompilerPluginSourceWitness'],['checkstyle-plugin','readCheckstylePluginSourceWitness'],
    ['checkstyle-guava','readCheckstyleGuavaSourceWitness'],['boot-http','readBootHttpSourceWitness'],['boot-utilities','readBootUtilitiesSourceWitness'],
    ['jacoco-antrun','readJacocoAntrunSourceWitness'],['assembly-plexus','readAssemblyPlexusSourceWitness'],['jar-plugin','readJarPluginSourceWitness'],
    ['assembly-enforcer-lang','readAssemblyEnforcerLangSourceWitness']]){
    const api=await import(`../security/${name}-graph-transition.mjs`);assert.equal(pure(()=>api[reader]()).releaseBlocked,true,name);
  }
});
test('existing Lang witness rejects late SDK, mixed-pair, other-POM and self-rehashed witness mutations',async t=>{
  const f=await fixture(t,true);const api=await import(pathToFileURL(join(f.directory,'scripts/security/assembly-enforcer-lang-graph-transition.mjs')));
  const check=()=>api.readAssemblyEnforcerLangSourceWitness();const witness=pure(check);
  assert.equal(witness.sourcePomFiles.length,28);
  const others=witness.sourcePomFiles.filter(row=>row.path!=='pom.xml'&&row.path!==SDK).map(row=>row.path);
  assert.equal(others.length,26);
  for(const path of [SDK,...others]){
    const raw=fs.readFileSync(join(f.directory,path));f.write(path,Buffer.concat([raw,Buffer.from('\n')]));assert.throws(check,/byte digest mismatch/);
    f.write(path,raw);pure(check);
  }
  f.write('pom.xml',original[OLD_ROOT]);assert.throws(check,/byte digest mismatch/);f.write('pom.xml',original['pom.xml']);pure(check);
  const manifestPath='docs/operations/assembly-enforcer-lang-evidence/source-witness.json';
  const raw=fs.readFileSync(join(f.directory,manifestPath));const data=JSON.parse(raw);
  const changed=Buffer.concat([original[SDK],Buffer.from('\n')]);f.write(SDK,changed);
  data.sourcePomFiles.find(row=>row.path===SDK).candidateSha256=sha(changed);
  const {contentSha256,...payload}=data;data.contentSha256=sha(JSON.stringify(payload));f.write(manifestPath,JSON.stringify(data));
  assert.throws(check,/byte digest mismatch/);
});

test('Resources/Flatten seam preserves existing non-POM hygiene projections',async t=>{
  const f=await fixture(t);
  for(const [path,expected,size]of [
    ['scripts/security/hygiene-java21-workflow-transition.mjs','820cffc7c6202de35c767c445a60361c00e632e8c7163a5284b0e7490e605f0e',3254],
    ['.github/workflows/approval-platform-validation.yml','5e0d736ccc9b5b43156397534eea2e1fc612a9fd9ee653d13e31aed3090c9ea5',19151]]){
    const current=read(path);const prior=pure(()=>f.api.preservedHygieneJava21Source(path,expected,current));
    assert.equal(sha(prior),expected);assert.equal(prior.length,size);
    assert.deepEqual(f.api.preservedHygieneJava21Source(path,expected,prior),prior);
    assert.deepEqual(f.api.preservedHygieneJava21Source('unrelated',expected,current),current);
  }
});

// One isolated Python interpreter checks the existing retained-source adapter.
// Its audit hook denies subprocesses and network; all mutations stay in fixtures.
const retainedChecks = String.raw`"""Pure RF retained-source checks; argv: checkout [adapter] [temporary-parent]."""
from pathlib import Path
from runpy import run_path
from tempfile import TemporaryDirectory
import copy, hashlib, json, sys


def audit(event, args):
    if event in {'subprocess.Popen', 'os.system', 'os.posix_spawn', 'os.posix_spawnp', 'os.fork'} or event.startswith('socket.'):
        raise AssertionError('process/network forbidden: ' + event)


sys.addaudithook(audit)
r = Path(sys.argv[1]).resolve(strict=True)
p = Path(sys.argv[2]).resolve(strict=True) if len(sys.argv) > 2 else r / 'scripts/ci/jar_plugin_retained_source.py'
work_parent = Path(sys.argv[3]).resolve(strict=True) if len(sys.argv) > 3 else None
a = run_path(str(p))
sha = lambda data: hashlib.sha256(data).hexdigest()
# Independent acceptance anchors. Do not derive expected old bytes by running
# the implementation's reversal or accept edited manifest identities.
anchors = {
    'RF_POM': '7aaef759cbdebaafde2383186cbb90861c23f51bd4fe3219614ed0fa8c22a9ab',
    'RF_SDK_POM': '2d933d9c89c7a0ee99b4870091a880ac086b295501914297c9bb6a21dd751078',
    'LANG_POM': 'ddb286b44b1976dd19ad74b06f3ceef86e19d0fbee87dd27dc87985ffb1f7932',
    'SDK_POM': 'e755e3a2abd6d2df15b7e5739b10cd81f9a59476d8c21e6dc231961145b2d701',
    'CURRENT_POM': '92694a65be5d42493864470184469b465df8107bfd98388b8b0db15292c54c7f',
    'PRIOR_POM': '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629',
    'WITNESS_SHA': '56e5701e73c25262887edcf73663cf9f7eff870a34891927107dfeceec174971',
}
assert all(a[key] == value for key, value in anchors.items())
assert a['SDK_PATH'] == 'integrations/host-sdk/pom.xml'
assert a['LANG_ARCHIVE'] == 'docs/operations/assembly-enforcer-lang-evidence/candidate-root-pom.xml'
assert a['SDK_ARCHIVE'] == 'docs/operations/resources-flatten-evidence/accepted-main905-host-sdk-pom.xml'
sdk = (r / a['SDK_ARCHIVE']).read_bytes()
assert sha(sdk) == anchors['SDK_POM']
witness_bytes = (r / a['WITNESS']).read_bytes()
assert sha(witness_bytes) == anchors['WITNESS_SHA']
pom_rows = json.loads(witness_bytes)['sourcePomFiles']
other_poms = [row['path'] for row in pom_rows if row['path'] not in ('pom.xml', a['SDK_PATH'])]
assert len(pom_rows) == 28 and len(other_poms) == len(set(other_poms)) == 26
roots = {a['RF_POM']: (r / 'pom.xml').read_bytes(),
         a['LANG_POM']: (r / a['LANG_ARCHIVE']).read_bytes(),
         a['CURRENT_POM']: (r / a['JAR_ARCHIVE']).read_bytes(),
         a['PRIOR_POM']: (r / a['ARCHIVE']).read_bytes()}
assert all(sha(raw) == h for h, raw in roots.items())
passed = []


def rejects(label, fn):
    try:
        fn()
    except ValueError:
        passed.append(label)
    else:
        raise AssertionError('accepted ' + label)


def changed(path, raw, fn, label):
    prior = path.read_bytes()
    path.write_bytes(raw)
    try:
        rejects(label, fn)
    finally:
        path.write_bytes(prior)


with TemporaryDirectory(prefix='rf-adapter-check-', dir=work_parent) as tmp:
    base = Path(tmp)

    def source(label, identity):
        root = base / label
        root.mkdir()
        # Use the API only to construct its required fixture closure. All
        # assertions below use fixed identities, counts and independent archives.
        inv = a['expected_inventory'](r, identity)
        for name, expected in inv.items():
            data = roots[identity] if name == 'pom.xml' else sdk if name == a['SDK_ARCHIVE'] or (
                name == a['SDK_PATH'] and identity != a['RF_POM']) else (r / name).read_bytes()
            assert sha(data) == expected, (name, sha(data), expected)
            dest = root / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
        return root

    src = source('rf', a['RF_POM'])
    g = a['checked_source_pair'].__globals__
    checked = g['checked']
    reads = []

    def traced(root, name, expected=None):
        reads.append(name)
        return checked(root, name, expected)

    g['checked'] = traced
    try:
        assert a['checked_source_pair'](src) == roots[a['RF_POM']]
    finally:
        g['checked'] = checked
    assert reads == ['pom.xml', a['SDK_PATH'], a['LANG_ARCHIVE'], a['SDK_ARCHIVE']], reads
    passed.append('RF seam exactly four fresh reads')
    assert len(a['source_inventory'](src)) == 53
    receipt = a['materialize_accepted_source'](src, base / 'rf-retained')
    ret = Path(receipt['retainedSourceRoot'])
    assert receipt['sourceFileCount'] == 53 and receipt['sourcePomCount'] == 28
    assert sorted(n for n in receipt['suppliedSourceHashes'] if receipt['suppliedSourceHashes'][n]
                  != receipt['retainedSourceHashes'][n]) == sorted(['pom.xml', a['SDK_PATH']])
    assert receipt['suppliedSdkPomSha256'] == a['RF_SDK_POM'] and receipt['retainedSdkPomSha256'] == a['SDK_POM']
    assert (ret / 'pom.xml').read_bytes() == roots[a['PRIOR_POM']] and (ret / a['SDK_PATH']).read_bytes() == sdk
    assert len(receipt['sourcePomReversals']) == 2 and len(receipt['reversalSteps']) == 3
    for _ in range(2):
        a['verify_projection'](receipt)
    passed.append('RF 53-file one-pass projection plus repeat verification')
    for identity, count in ((a['LANG_POM'], 51), (a['CURRENT_POM'], 50), (a['PRIOR_POM'], 50)):
        srcold = source('old-' + str(count) + '-' + identity[:4], identity)
        old_inventory = a['source_inventory'](srcold)
        assert len(old_inventory) == count
        assert old_inventory['pom.xml'] == identity and old_inventory[a['SDK_PATH']] == anchors['SDK_POM']
        assert all(sha((srcold / name).read_bytes()) == expected for name, expected in old_inventory.items())
        q = a['materialize_accepted_source'](srcold, base / ('old-' + identity[:4]))
        assert q['sourceFileCount'] == count and q['sourcePomCount'] == 28
        assert q['suppliedRootPomSha256'] == identity and q['retainedRootPomSha256'] == anchors['PRIOR_POM']
        assert q['schemaVersion'] == ('ASSEMBLY_ENFORCER_LANG_RETAINED_ASSEMBLY_SOURCE_VIEW_V1'
                                     if identity == anchors['LANG_POM'] else 'JAR_PLUGIN_RETAINED_ASSEMBLY_SOURCE_VIEW_V1')
        assert q['reversalApplied'] is (identity != anchors['PRIOR_POM'])
        assert 'sourcePomReversals' not in q and 'suppliedSdkPomSha256' not in q
        assert (Path(q['retainedSourceRoot']) / 'pom.xml').read_bytes() == roots[anchors['PRIOR_POM']]
        assert (Path(q['retainedSourceRoot']) / a['SDK_PATH']).read_bytes() == sdk
        assert ('reversalSteps' in q) is (identity == anchors['LANG_POM'])
        a['verify_projection'](q)
        passed.append('unchanged old source/receipt ' + str(count) + ' ' + identity[:4])
        changed(srcold / a['SDK_PATH'], (r / a['SDK_PATH']).read_bytes(),
                lambda: a['source_inventory'](srcold), 'old root + new SDK ' + identity[:4])
    changed(src / 'pom.xml', roots[a['LANG_POM']], lambda: a['source_inventory'](src), 'old Lang root + new SDK')
    changed(src / a['SDK_PATH'], sdk, lambda: a['source_inventory'](src), 'new root + old SDK')
    for root in (src, ret):
        for name in ('pom.xml', a['SDK_PATH'], a['LANG_ARCHIVE'], a['SDK_ARCHIVE']):
            changed(root / name, (root / name).read_bytes() + b'\n', lambda: a['verify_projection'](receipt), 'late ' + root.name + ' ' + name)
            a['verify_projection'](receipt)
    for name in a['RF_ADDITIONS']:
        raw = (src / name).read_bytes()
        for i, addition in enumerate(a['RF_ADDITIONS'][name]):
            for label, altered in [('missing', raw.replace(addition, b'', 1)),
                                   ('duplicate', raw.replace(addition, addition * 2, 1)),
                                   ('wrong-version', raw.replace(addition, addition.replace(b'4.0.3', b'4.0.4'), 1))]:
                changed(src / name, altered, lambda: a['source_inventory'](src), label + ' ' + name + ' ' + str(i))
    file = src / a['SDK_PATH']
    raw = file.read_bytes()
    file.unlink()
    try:
        rejects('missing candidate SDK', lambda: a['source_inventory'](src))
    finally:
        file.write_bytes(raw)
    file.unlink()
    file.symlink_to(ret / a['SDK_PATH'])
    try:
        rejects('SDK symlink', lambda: a['source_inventory'](src))
    finally:
        file.unlink()
        file.write_bytes(raw)
    directory = file.parent
    other = directory.with_name('host-sdk-real')
    directory.rename(other)
    directory.symlink_to(other, target_is_directory=True)
    try:
        rejects('SDK parent symlink', lambda: a['source_inventory'](src))
    finally:
        directory.unlink()
        other.rename(directory)
    changed(file, b'x' * (a['MAX_FILE_BYTES'] + 1), lambda: a['source_inventory'](src), 'oversize SDK')
    changed(src / 'apps/server/pom.xml', (src / 'apps/server/pom.xml').read_bytes() + b'\n',
            lambda: a['verify_projection'](receipt), 'other reactor POM late change')
    witness = src / a['WITNESS']
    original = witness.read_bytes()
    doc = json.loads(original)
    for row in doc['sourcePomFiles']:
        if row['path'] == a['SDK_PATH']:
            row['candidateSha256'] = sha(sdk + b'\n')
    (src / a['SDK_ARCHIVE']).write_bytes(sdk + b'\n')
    witness.write_text(json.dumps(doc))
    try:
        rejects('consistent old SDK original + witness rewrite', lambda: a['verify_projection'](receipt))
    finally:
        (src / a['SDK_ARCHIVE']).write_bytes(sdk)
        witness.write_bytes(original)
    for key in ('suppliedSdkPomSha256', 'retainedSdkPomSha256', 'retainedSdkArchiveSha256'):
        forged = copy.deepcopy(receipt)
        forged[key] = '0' * 64
        rejects('receipt ' + key, lambda: a['verify_projection'](forged))
    forged = copy.deepcopy(receipt)
    forged['sourcePomReversals'][1]['archiveSha256'] = '0' * 64
    rejects('dual reversal receipt', lambda: a['verify_projection'](forged))
    forged = copy.deepcopy(receipt)
    forged['retainedSourceHashes'][a['SDK_PATH']] = a['RF_SDK_POM']
    rejects('self-edited retained map', lambda: a['verify_projection'](forged))
    extra = ret / 'extra'
    extra.write_bytes(b'x')
    try:
        rejects('extra retained member', lambda: a['verify_projection'](receipt))
    finally:
        extra.unlink()
    conf = src / 'integrations/.mvn'
    conf.mkdir()
    try:
        rejects('.mvn injection', lambda: a['verify_projection'](receipt))
    finally:
        conf.rmdir()
    # Every source/retained endpoint remains live after successful materialization.
    for root in (src, ret):
        for name in ('pom.xml', a['SDK_PATH'], a['LANG_ARCHIVE'], a['SDK_ARCHIVE']):
            endpoint = root / name
            original_endpoint = endpoint.read_bytes()
            endpoint.unlink()
            try:
                rejects('missing ' + root.name + ' ' + name, lambda: a['verify_projection'](receipt))
            finally:
                endpoint.write_bytes(original_endpoint)
            endpoint.unlink()
            target = ret / name if root == src else src / name
            endpoint.symlink_to(target)
            try:
                rejects('symlink ' + root.name + ' ' + name, lambda: a['verify_projection'](receipt))
            finally:
                endpoint.unlink()
                endpoint.write_bytes(original_endpoint)
            changed(endpoint, b'x' * (2 * 1024 * 1024 + 1), lambda: a['verify_projection'](receipt),
                    'oversize ' + root.name + ' ' + name)
            a['verify_projection'](receipt)
    # No other POM is hidden behind the two-POM source normalization.
    for name in other_poms:
        changed(src / name, (src / name).read_bytes() + b'\n', lambda: a['verify_projection'](receipt),
                'other-26-POM ' + name)
    for name, archive in [('pom.xml', a['LANG_ARCHIVE']), (a['SDK_PATH'], a['SDK_ARCHIVE'])]:
        endpoints = [src / name, src / archive, ret / name, ret / archive]
        originals = [endpoint.read_bytes() for endpoint in endpoints]
        forged = copy.deepcopy(receipt)
        for endpoint, before in zip(endpoints, originals):
            endpoint.write_bytes(before + b'\n')
            key = 'suppliedSourceHashes' if endpoint.is_relative_to(src) else 'retainedSourceHashes'
            relative = endpoint.relative_to(src if key == 'suppliedSourceHashes' else ret).as_posix()
            forged[key][relative] = sha(before + b'\n')
        try:
            rejects('matched source retained originals and maps ' + name, lambda: a['verify_projection'](forged))
        finally:
            for endpoint, before in zip(endpoints, originals):
                endpoint.write_bytes(before)
    for key, value in [('scope', 'accepted current compatibility'), ('sourceFileCount', 51),
                       ('sourcePomCount', 27), ('reversalApplied', False),
                       ('schemaVersion', 'ASSEMBLY_ENFORCER_LANG_RETAINED_ASSEMBLY_SOURCE_VIEW_V1'),
                       ('adapterSha256', '0' * 64), ('reversalSteps', []), ('sourcePomReversals', [])]:
        forged = copy.deepcopy(receipt)
        forged[key] = value
        rejects('forged receipt ' + key, lambda: a['verify_projection'](forged))
    for key in ('suppliedSourceHashes', 'retainedSourceHashes'):
        for action in ('missing', 'wrong'):
            forged = copy.deepcopy(receipt)
            if action == 'missing':
                del forged[key][a['SDK_PATH']]
            else:
                forged[key][a['SDK_PATH']] = '0' * 64
            rejects('forged ' + key + ' ' + action, lambda: a['verify_projection'](forged))
    retained_conf = ret / 'integrations/.mvn'
    retained_conf.mkdir()
    try:
        rejects('retained .mvn injection', lambda: a['verify_projection'](receipt))
    finally:
        retained_conf.rmdir()
    rejects('unsafe relative path', lambda: a['checked'](src, '../pom.xml'))
    a['verify_projection'](receipt)
print(json.dumps({'status': 'PASSED_PURE_DATA_AND_FILESYSTEM_CHECKS', 'checks': passed, 'count': len(passed),
                  'adapterSha256': sha(p.read_bytes()), 'javaMavenNodeScannerExecuted': False, 'repositoryWritten': False, 'helper': str(p)}, indent=2))
`;
test('Resources/Flatten retained adapter rejects complete pure-data and filesystem negative controls', t => {
  const result = childProcess.spawnSync('/usr/bin/python3', ['-I', '-S', '-B', '-c', retainedChecks, ROOT],
    { encoding: 'utf8', timeout: 120000, maxBuffer: 2 * 1024 * 1024 });
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  const report = JSON.parse(result.stdout);
  assert.equal(report.status, 'PASSED_PURE_DATA_AND_FILESYSTEM_CHECKS');
  assert.equal(report.count, 106);
  assert.equal(report.javaMavenNodeScannerExecuted, false);
  assert.equal(report.repositoryWritten, false);
  t.diagnostic(JSON.stringify(report));
});
