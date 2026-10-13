import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const fixtures = fileURLToPath(new URL('./fixtures/', import.meta.url));
function python(source) {
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', '-c', source, fixtures], {
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8' }, encoding: 'utf8', timeout: 30000, maxBuffer: 2 * 1024 * 1024,
  });
  assert.equal(result.status, 0, `${result.error?.message ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
}
const blocked = String.raw`
import http.server, ssl, runpy, subprocess, socket, sys
def blocked(*args, **kwargs): raise AssertionError('socket-free mock launched process/socket')
subprocess.Popen = socket.socket = blocked
`;

test('Boot utilities plan and absent opt-in are inert and bounded', () => {
  const plan = JSON.parse(python(blocked + String.raw`
import os
s = runpy.run_path(sys.argv[1] + '/run_boot_utilities_compatibility.py')
try: s['execute']({})
except ValueError as error: assert 'opt-in' in str(error)
else: raise AssertionError('utility execution without opt-in')
sys.argv = ['run_boot_utilities_compatibility.py']
runpy.run_path(s['__file__'], run_name='__main__')
`));
  assert.equal(plan.execution, 'NOT_RUN');
  assert.equal(plan.sockets, false);
  assert.equal(plan.aggregateTimeoutSeconds, 180);
  assert.deepEqual(plan.variants, { baseline: ['3.5.1', '3.16.0'], candidate: ['3.6.1', '3.18.0'] });
  assert.equal(plan.memberCount, 31);
});

test('fixed utilities pins reject missing, extra, wrong byte and arbitrary profiles', () => {
  python(blocked + String.raw`
import copy,json,tempfile
from pathlib import Path
s = runpy.run_path(sys.argv[1] + '/run_boot_live_compatibility.py')
pins = s['read_pins'](profile='utilities')
assert len(pins['artifactHashes']) == 41 and all(len(x) == 39 for x in pins['variants'].values())
assert len(s['read_pins']()['artifactHashes']) == 42
def rejects(fn):
    try: fn()
    except ValueError: return
    raise AssertionError('unreviewed utility change accepted')
with tempfile.TemporaryDirectory(prefix='boot-utility-pins-') as d:
    p=Path(d)/'pins.json'
    def read(value): p.write_text(json.dumps(value)); return s['read_pins'](p, 'utilities')
    assert read(pins) == pins
    for kind in ('missing','extra','duplicate','bad-hash','wrong-pair','wrong-http'):
        value=copy.deepcopy(pins)
        if kind=='missing': value['variants']['candidate'].pop()
        if kind=='extra': value['artifactHashes']['extra:artifact:jar:1']='0'*64
        if kind=='duplicate': value['variants']['candidate'][1]=value['variants']['candidate'][0]
        if kind=='bad-hash': value['artifactHashes'][next(iter(value['artifactHashes']))]='wrong'
        if kind in ('wrong-pair','wrong-http'):
            old='org.apache.commons:commons-lang3:jar:3.18.0' if kind=='wrong-pair' else 'org.apache.httpcomponents.client5:httpclient5:jar:5.6.3'
            new=old.rsplit(':',1)[0]+':99'
            value['variants']['candidate']=[new if x==old else x for x in value['variants']['candidate']]
            value['artifactHashes'][new]=value['artifactHashes'].pop(old)
        rejects(lambda: read(value))
rejects(lambda: s['read_pins'](profile='arbitrary'))
`);
});

test('utilities goal projection retains HTTP/Jackson and changes only reviewed utility pair', () => {
  python(blocked + String.raw`
import xml.etree.ElementTree as ET
s=runpy.run_path(sys.argv[1]+'/run_boot_goal_compatibility.py')
def dependencies(variant,profile):
    root=ET.fromstring(s['fixture_pom'](variant,profile)); ns=s['NS']
    return {d.findtext('m:artifactId',namespaces=ns):d.findtext('m:version',namespaces=ns) for d in root.findall('m:build/m:plugins/m:plugin/m:dependencies/m:dependency',ns)}
old={'jackson-core':'3.1.7','jackson-databind':'3.1.7'}
assert dependencies('baseline','http') == old
assert dependencies('candidate','http') == old|{'httpclient5':'5.6.3'}
assert dependencies('baseline','utilities') == old|{'httpclient5':'5.6.3'}
assert dependencies('candidate','utilities') == old|{'httpclient5':'5.6.3','plexus-utils':'3.6.1','commons-lang3':'3.18.0'}
p=runpy.run_path(sys.argv[1]+'/run_boot_live_compatibility.py')['read_pins'](profile='utilities')
before=p['variants']['baseline'];after=p['variants']['candidate']
r=s['compare_realms'](before,after,'utilities');assert r['membershipChangedOnlyByUtilityPair'] and r['nonUtilityOrderEqual']
for changed in (after+['extra:artifact:jar:1'], [x.replace('commons-lang3:jar:3.18.0','commons-lang3:jar:3.19.0') for x in after]):
    try:s['compare_realms'](before,changed,'utilities')
    except ValueError:pass
    else:raise AssertionError('arbitrary utility realm change accepted')
`);
});

test('utility probe receipt refuses omissions and falsely identical scanner defaults', () => {
  python(blocked + String.raw`
s=runpy.run_path(sys.argv[1]+'/run_boot_utilities_compatibility.py')
members=s['MEMBERS'].read_text().splitlines()
origins=['BOOT_UTIL_ORIGIN\tfixture.Class'+str(i)+'\t/mock/a.jar\t'+'0'*64 for i in range(20)]
stderr='\n'.join('fixture.Class'+str(i)+' source: file:/mock/a.jar' for i in range(20))
stdout='\n'.join(origins+['BOOT_UTIL_MEMBER\t'+x for x in members]+['BOOT_UTIL_LINKAGE\t31\t'+s['digest'](s['MEMBERS']), 'BOOT_UTIL_SCANNER\t3.6.1\t.gitignore=true\t.cvsignore=true','BOOT_UTIL_COMPLETE\tcandidate\t250'])
assert s['probe_result'](stdout,stderr,'candidate')['scannerDefaults'] == {'gitignoreIncluded':True,'cvsignoreIncluded':True}
for out,err in ((stdout.replace('BOOT_UTIL_COMPLETE','MISSING'),stderr),(stdout.replace('.cvsignore=true','.cvsignore=false'),stderr),(stdout.replace('BOOT_UTIL_MEMBER\t'+members[0],'missing'),stderr),(stdout,'')):
    try:s['probe_result'](out,err,'candidate')
    except ValueError:pass
    else:raise AssertionError('incomplete utility receipt accepted')
`);
});
