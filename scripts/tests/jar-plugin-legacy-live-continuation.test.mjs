import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const setup = String.raw`
import copy, hashlib, runpy, shutil, sys, tempfile
from pathlib import Path
from unittest.mock import patch
def no_execution(event, args):
    if event in {'subprocess.Popen', 'os.system', 'os.posix_spawn', 'os.posix_spawnp', 'os.fork'} or event.startswith('socket.'):
        raise AssertionError('Pure continuation attempted execution/network: ' + event)
sys.addaudithook(no_execution)
ROOT = Path(sys.argv[1]).resolve()
HERE = ROOT / 'scripts/tests/fixtures'
sys.path.insert(0, str(HERE))
jar = runpy.run_path(str(ROOT / 'scripts/ci/jar_plugin_retained_source.py'))
goals = runpy.run_path(str(HERE / 'run_boot_goal_compatibility.py'))
def rejects(callback):
    try: callback()
    except ValueError: return
    raise AssertionError('Unreviewed source accepted')
def copy_source(base):
    source = base / 'source'; source.mkdir()
    for name in jar['source_inventory'](ROOT):
        target = source / name; target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / name, target)
    return source
`;
function python(source) {
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', '-c', setup + source, root], {
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8' }, encoding: 'utf8', timeout: 60000,
    maxBuffer: 2 * 1024 * 1024,
  });
  assert.equal(result.status, 0, `${result.error?.message ?? ''}\n${result.stdout}\n${result.stderr}`);
}

test('JAR legacy live Boot source preserves exact historical fixture POMs for both profiles', () => {
  python(String.raw`
rejects(lambda: goals['pair_contract'].verify_source(ROOT))
with tempfile.TemporaryDirectory(prefix='jar-legacy-boot-') as directory:
    retained = Path(directory) / 'accepted'
    projection = jar['materialize_accepted_source'](ROOT, retained)
    current_raw, current, current_inventory = goals['source_inputs'](ROOT)
    prior_raw, prior_current, prior_inventory = goals['source_inputs'](retained)
    assert current and prior_current and current_raw == prior_raw
    assert len(current_inventory) == 50 and len(prior_inventory) == 29
    assert current_inventory['pom.xml'] == jar['CURRENT_POM']
    assert prior_inventory['pom.xml'] == jar['PRIOR_POM']
    for profile in ('http', 'utilities'):
        for variant in ('baseline', 'candidate'):
            assert goals['fixture_pom'](variant, profile, ROOT) == goals['fixture_pom'](variant, profile, retained)
    jar['verify_projection'](projection)
`);
});

for (const family of ['clean', 'compiler']) test(`JAR legacy live ${family} retains both source identities and rejects late source/view mutations`, () => {
  python(String.raw`
family = '${family}'
module = runpy.run_path(str(HERE / ('run_' + family + '_live_compatibility.py')))
for current in (True, False):
    with tempfile.TemporaryDirectory(prefix='jar-legacy-' + family + '-') as directory:
        base = Path(directory); source = copy_source(base)
        if not current:
            (source / 'pom.xml').write_bytes((source / jar['ARCHIVE']).read_bytes())
        sandbox = base / 'sandbox'; sandbox.mkdir()
        repository, jdk, maven = base / 'repository', base / 'jdk', base / 'maven/bin/mvn'
        run = (module['FixtureRun'](sandbox, source, repository, maven, jdk, True, 'models') if family == 'clean'
               else module['Run'](source, sandbox, repository, jdk, maven, True))
        run.containment()
        assert run.commands.results == []
        assert len(run.projects) == 26 and len(run.source_hashes) == 28
        projection = run.report['jarSourceProjection']
        assert bool(projection) == current
        if not current: continue
        assert projection['suppliedRootPomSha256'] == jar['CURRENT_POM']
        assert projection['retainedRootPomSha256'] == jar['PRIOR_POM']
        assert projection['sourceFileCount'] == 50
        assert run.report['pairSourceProjection']['suppliedSourceRoot'] == projection['retainedSourceRoot']
        retained = Path(projection['retainedSourceRoot'])
        child_pom = next(name for name in projection['suppliedSourceHashes'] if name.endswith('/pom.xml'))
        for file in (source / 'pom.xml', source / jar['ARCHIVE'], source / child_pom, retained / 'pom.xml'):
            original = file.read_bytes(); file.write_bytes(original + b'\n')
            rejects(run.containment); file.write_bytes(original); run.containment()
        extra = retained / 'unexpected'; extra.write_bytes(b'unreviewed')
        rejects(run.containment); extra.unlink(); run.containment()
        bootstrap = source / '.mvn'; bootstrap.mkdir()
        rejects(run.containment); bootstrap.rmdir(); run.containment()
        assert run.commands.results == []
`);
});

test('JAR legacy live Boot seals every consumed input and rejects changed or missing source bytes', () => {
  python(String.raw`
with tempfile.TemporaryDirectory(prefix='jar-legacy-boot-seal-') as directory:
    source = copy_source(Path(directory))
    inventory = goals['source_inputs'](source)[2]
    consumed = {str(source / name): value for name, value in inventory.items()}
    for file in (Path(goals['__file__']), goals['JAR_SOURCE_ADAPTER']):
        consumed[str(file.resolve())] = goals['digest'](file)
    assert len(consumed) == 52
    seal = {'sourceHashes': consumed}; goals['verify_source_seal'](seal, source)
    def no_reload(*args): raise AssertionError('Unsealed adapter was reloaded')
    for file in (Path(goals['__file__']), goals['JAR_SOURCE_ADAPTER']):
        for value in (None, '0' * 64):
            unsealed = copy.deepcopy(seal)
            if value is None: del unsealed['sourceHashes'][str(file.resolve())]
            else: unsealed['sourceHashes'][str(file.resolve())] = value
            with patch.dict(goals['verify_source_seal'].__globals__, {'source_inputs': no_reload}):
                rejects(lambda: goals['verify_source_seal'](unsealed, source))
    for name in consumed:
        missing = {'sourceHashes': {key: value for key, value in consumed.items() if key != name}}
        rejects(lambda: goals['verify_source_seal'](missing, source))
    wrong = copy.deepcopy(seal); wrong['sourceHashes'][str(source / 'pom.xml')] = '0' * 64
    rejects(lambda: goals['verify_source_seal'](wrong, source))
    for name in ('pom.xml', jar['ARCHIVE'], next(name for name in inventory if name.endswith('/pom.xml'))):
        file = source / name; original = file.read_bytes(); file.write_bytes(original + b'\n')
        rejects(lambda: goals['source_inputs'](source)); rejects(lambda: goals['verify_source_seal'](seal, source))
        file.write_bytes(original)
    bootstrap = source / '.mvn'; bootstrap.mkdir()
    rejects(lambda: goals['source_inputs'](source)); bootstrap.rmdir()
    goals['verify_source_seal'](seal, source)
`);
});

test('JAR legacy live Boot rechecks original and temporary source after strict historical verification', () => {
  python(String.raw`
with tempfile.TemporaryDirectory(prefix='jar-legacy-boot-fresh-') as directory:
    source = copy_source(Path(directory))
    original_verify = goals['pair_contract'].verify_source
    changed = []
    for mutate_supplied in (False, True):
        def mutate(retained):
            result = original_verify(retained)
            file = (source / jar['ARCHIVE']) if mutate_supplied else (retained / 'unexpected')
            changed.append((file, file.read_bytes() if file.exists() else None))
            file.write_bytes(b'unreviewed mutation')
            return result
        with patch.object(goals['pair_contract'], 'verify_source', mutate):
            rejects(lambda: goals['source_inputs'](source))
        file, original = changed.pop()
        if mutate_supplied: file.write_bytes(original)
        else: assert not file.parent.exists(), 'temporary retained source was not cleaned after refusal'
        goals['source_inputs'](source)
`);
});
