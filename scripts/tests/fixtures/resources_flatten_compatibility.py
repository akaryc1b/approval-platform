"""Bounded paired Resources/Flatten goals. Plan and prepare start no JVM."""
import sys
sys.dont_write_bytecode = True
import argparse
import base64
import gzip
import hashlib
import importlib
import json
import os
from pathlib import Path
from runpy import run_path
import signal
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CONTRACT = ROOT / 'scripts/ci/resources_flatten_contract.py'
PINS = HERE / 'resources-flatten-artifact-pins.json'
PLAN = {'execution': 'NOT_RUN', 'optIn': 'RESOURCES_FLATTEN_COMPATIBILITY=true',
        'mavenProcessCount': 20, 'compileCount': 1, 'commandTimeoutSeconds': 90,
        'compileTimeoutSeconds': 30, 'aggregateTimeoutSeconds': 1800, 'outerTimeoutSeconds': 1830,
        'offline': True, 'downloadAllowed': False, 'scannerEvidence': False,
        'classLogMaximumBytes': 6 * 1024 * 1024, 'classLogTotalMaximumBytes': 120 * 1024 * 1024,
        'outputMaximumBytes': 256 * 1024 * 1024, 'outputMaximumEntries': 3000,
        'receiptMaximumBytes': 16 * 1024 * 1024, 'protocolMaximumBytes': 1024 * 1024,
        'protocolCompressedMaximumBytes': 256 * 1024, 'protocolRowMaximum': 2000,
        'protocolTotalMaximumBytes': 8 * 1024 * 1024,
        'casesPerVariant': ['resources-success', 'resources-repeat', 'resources-failure',
                            'sdk-flatten', 'sdk-clean', 'demo-flatten', 'demo-repeat', 'demo-clean', 'flatten-failure', 'sdk-production-context'],
        'evidencePhases': ['natural real goals', 'separate bounded API replay', 'complete symbolic member resolution'],
        'scope': 'Resources 3.4.0 and Flatten 1.7.3 private Utils 4.0.2 to 4.0.3 only'}
PRIVATE_INPUTS = ['resources_flatten_compatibility.py', 'resources_flatten_fixtures.py',
                  'resources_flatten_observations.py', 'resources_flatten_inventory.py',
                  'ResourcesFlattenRealmObserver.java', 'ResourcesFlattenCompletionSpy.java', 'resources-flatten-artifact-pins.json']



def require(ok, message):
    if not ok:
        raise ValueError('Resources/Flatten compatibility: ' + message)


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def source_contract():
    return run_path(str(CONTRACT))


def support(candidate):
    contract = source_contract()
    before = contract['python_helper_state'](candidate)
    directory = candidate / 'scripts/tests/fixtures'
    sys.path.insert(0, str(directory))
    modules = {name: importlib.import_module(name) for name in
               ('checkstyle_live_support', 'run_boot_live_compatibility', 'jar_plugin_storage')}
    for name in contract['PYTHON_HELPERS']:
        if name.endswith('/assembly_plexus_inventory.py'):
            continue
        module = sys.modules.get(Path(name).stem)
        require(module is not None and Path(module.__file__).resolve() == candidate / name,
                'helper imported outside current reviewed closure')
    require(contract['python_helper_state'](candidate) == before, 'helper changed during first import')
    return modules


def private_hashes():
    files = [HERE / name for name in PRIVATE_INPUTS] + [CONTRACT,
        ROOT / 'docs/operations/resources-flatten-evidence/accepted-main905-source-manifest.json',
        ROOT / 'docs/operations/resources-flatten-evidence/approved-additions.json',
        ROOT / 'docs/operations/jar-plugin-evidence/compatibility-local-seal.json']
    require(all(p.is_file() and not p.is_symlink() and p.resolve() == p for p in files), 'real reviewed harness inputs')
    return {**{str(p): digest(p) for p in files}, **source_contract()['python_helper_state'](ROOT)}


def fresh_inventory(config):
    return run_path(str(HERE / 'resources_flatten_inventory.py'))['fresh_inventory'](
        Path(config['candidate']), Path(config['repository']), Path(config['maven']), PINS)


def tool_pins(config):
    jdk = Path(config['jdk']); maven = Path(config['maven'])
    pins = json.loads(PINS.read_text())
    require(maven.name == 'mvn' and digest(maven) == pins['mavenLauncherSha256'], 'accepted official Maven 3.9.16 launcher')
    require('JAVA_VERSION="21.' in (jdk / 'release').read_text(), 'existing selected Java 21 required')
    for row in pins['runtime']:
        file = maven.parent.parent / 'lib' / row['name']
        require(file.is_file() and digest(file) == row['sha256'], 'accepted official Maven provider bytes')


def prepare(config):
    config = {k: str(Path(v).absolute()) for k, v in config.items()}
    require(set(config) == {'baseline', 'candidate', 'maven', 'jdk', 'repository', 'output'}, 'exact configuration keys')
    paths = {k: Path(v) for k, v in config.items()}
    require(all(p.resolve() == p and not any(c.isspace() or ord(c) < 32 for c in str(p)) for p in paths.values()), 'canonical JVM-safe inputs')
    require(not paths['output'].exists() and paths['output'].parent.is_dir(), 'fresh output required')
    require(all(not paths['output'].is_relative_to(paths[k]) and not paths[k].is_relative_to(paths['output']) for k in ('baseline', 'candidate', 'jdk', 'repository')), 'output/input overlap')
    source = source_contract()['source_pair'](paths['baseline'], paths['candidate'])
    supplied = private_hashes()
    helpers = support(paths['candidate']); runtime = helpers['run_boot_live_compatibility']
    tool_pins(config)
    inventory = fresh_inventory(config)
    origins, imported = runtime.python_origins()
    result = {'schemaVersion': 'RESOURCES_FLATTEN_COMPAT_V1', 'plan': PLAN, 'config': config,
              'source': source, 'harnessHashes': supplied,
              'runtimeHashes': runtime.hashes(runtime.runtime_files(paths['jdk'], paths['maven'], imported)),
              'cacheHashes': runtime.cache_snapshot(paths['repository']), 'pythonOrigins': origins,
              'inventory': inventory, 'preparedIsExecutionEvidence': False}
    require(private_hashes() == supplied and source_contract()['source_pair'](paths['baseline'], paths['candidate']) == source,
            'input changed during preparation')
    return result


def check_seal(seal, full=False):
    require(seal['schemaVersion'] == 'RESOURCES_FLATTEN_COMPAT_V1' and seal['plan'] == PLAN, 'reviewed seal identity')
    config = seal['config']
    require(private_hashes() == seal['harnessHashes'], 'reviewed harness changed')
    current = source_contract()['source_pair'](Path(config['baseline']), Path(config['candidate']))
    require(current == seal['source'], 'fresh bounded source changed')
    pins = seal['inventory']['pins']
    for coordinate, expected in pins['artifactHashes'].items():
        group, name, kind, version = coordinate.split(':')
        path = Path(config['repository']) / group.replace('.', '/') / name / version / (name + '-' + version + '.' + kind)
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(Path(config['repository'])) and digest(path) == expected, 'actual consumed JAR changed')
    if full:
        require(fresh_inventory(config) == seal['inventory'], 'fresh semantic classfile/member inventory changed')
        runtime = support(Path(config['candidate']))['run_boot_live_compatibility']
        require(all(Path(p).is_file() and digest(p) == h for p, h in seal['runtimeHashes'].items()), 'runtime input changed')
        require(runtime.cache_snapshot(Path(config['repository'])) == seal['cacheHashes'], 'offline cache JAR/POM content changed')


def terminal(owner, goal, execution, project, outcome='MojoSucceeded'):
    return '\t'.join((owner, '3.4.0' if owner == 'resources' else '1.7.3', goal, execution, project, outcome))


def failure_attribution(log, fragments):
    lines = [next((line for line in log.splitlines() if fragment in line), '') for fragment in fragments]
    require(len(lines) == len(fragments) and all(line and len(line.encode()) <= 8192 for line in lines),
            'bounded actual failure attribution lines')
    return lines


def execute(seal):
    require(os.environ.get('RESOURCES_FLATTEN_COMPATIBILITY') == 'true', 'independently reviewed opt-in required')
    check_seal(seal, True)
    config = seal['config']; output = Path(config['output']); jdk = Path(config['jdk'])
    require(not output.exists(), 'fresh execution directory')
    output.mkdir()
    for name in ('home', 'tmp', 'observer', 'class-load'):
        (output / name).mkdir()
    (output / 'empty-settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n')
    (output / 'empty-toolchains.xml').write_text('<toolchains/>\n')
    helpers = support(Path(config['candidate'])); common = helpers['checkstyle_live_support']
    fixtures = run_path(str(HERE / 'resources_flatten_fixtures.py'))
    observations = run_path(str(HERE / 'resources_flatten_observations.py'))
    environment = common.isolated_env(jdk, output); environment['MAVEN_BASEDIR'] = str(output)
    commands = common.Commands(output, environment, PLAN['aggregateTimeoutSeconds'])
    labels = [v + '-' + case for v in ('baseline', 'candidate') for case in PLAN['casesPerVariant']]
    storage = helpers['jar_plugin_storage'].StorageGuard(output, PLAN['outputMaximumBytes'], PLAN['outputMaximumEntries'],
        {'class-load/' + label + '.log': PLAN['classLogMaximumBytes'] for label in labels})
    fixed = {str(p): digest(p) for p in output.iterdir() if p.is_file()}
    for owner in ('resources', 'flatten'):
        path = output / (owner + '-members.tsv')
        path.write_text('\n'.join(seal['inventory']['owners'][owner]['effectiveLinkageRows']) + '\n')
        fixed[str(path)] = digest(path)
    result = {'status': 'RUNNING', 'schemaVersion': 'RESOURCES_FLATTEN_COMPAT_V1',
              'config': config, 'plan': PLAN, 'commands': commands.results, 'variants': {},
              'scannerEvidence': False, 'publicAcceptance': False, 'source': seal['source'],
              'harnessHashes': seal['harnessHashes'], 'semanticInventory': seal['inventory'],
              'runtimeHashes': seal['runtimeHashes'], 'cacheHashes': seal['cacheHashes'],
              'productionRepeatProven': False, 'fullProductionBuildClaim': False,
              'limitationFixedByDependencyChange': False}
    old_handlers = {sig: signal.getsignal(sig) for sig in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT)}
    def deadline(signum, frame):
        raise TimeoutError('bounded compatibility deadline or termination')
    def run(label, command, seconds=90, extra=None):
        check_seal(seal)
        require(all(Path(p).is_file() and digest(p) == h for p, h in fixed.items()), 'fixed fixture or observer input changed')
        storage.start()
        command_failure = None
        try:
            value = commands.run(label, command, seconds, extra)
        except BaseException as failure:
            command_failure = failure
        try:
            storage.stop()
        except BaseException as cleanup_failure:
            result['commandCleanupError'] = {'case': label, 'error': str(cleanup_failure)}
            if command_failure is None:
                command_failure = cleanup_failure
        if command_failure is not None:
            raise command_failure
        require(storage.failure is None, 'storage violation: ' + str(storage.failure)); storage.scan()
        check_seal(seal)
        require(all(Path(p).is_file() and digest(p) == h for p, h in fixed.items()), 'fixed input changed during command')
        return value
    def freeze_fixture(directory):
        for p in directory.rglob('*'):
            if p.is_file():
                fixed[str(p)] = digest(p)
    def goal(variant, name, owner, work, project, goals, terminals, fixture=None, replay='none', successful=True, demo=False, properties=()):
        label = variant + '-' + name
        terminal_file = output / (label + '-terminals.tsv'); terminal_file.write_text('\n'.join(terminals) + '\n')
        fixed[str(terminal_file)] = digest(terminal_file)
        log = output / 'class-load' / (label + '.log')
        require(not log.exists(), 'fresh independent JVM class log')
        opts = '-Xmx256m -XX:-UsePerfData -Duser.home=' + str(output / 'home') + ' -Djava.io.tmpdir=' + str(output / 'tmp')
        opts += ' -javaagent:' + str(output / 'observer.jar') + '=' + str(output / (owner + '-members.tsv'))
        opts += ' -Xlog:class+load=info:file=' + str(log) + ':uptime,level,tags:filecount=0'
        opts += ' -Drf.owner=' + owner + ' -Drf.replay=' + replay + ' -Drf.work=' + str(work)
        opts += ' -Drf.terminals=' + str(terminal_file) + ' -Drf.utils.version=' + ('4.0.2' if variant == 'baseline' else '4.0.3')
        command = [config['maven'], '-o', '-B', '-ntp', '-X', '--strict-checksums',
                   '-s', str(output / 'empty-settings.xml'), '-gs', str(output / 'empty-settings.xml'),
                   '-t', str(output / 'empty-toolchains.xml'), '-gt', str(output / 'empty-toolchains.xml'),
                   '-Dmaven.repo.local=' + config['repository'], '-Dstyle.color=never',
                   '-Dmaven.ext.class.path=' + str(output / 'completion-spy.jar'),
                   '-Dmaven.multiModuleProjectDirectory=' + str(work), '-f', str(project),
                   *(['-Pproduct-readiness-demo'] if demo else ['-N']), *properties, *goals]
        status, stdout, stderr = run(label, command, extra={'MAVEN_OPTS': opts})
        require((status == 0) == successful, 'real goal exit differs: ' + label)
        require(log.is_file() and not log.is_symlink() and log.stat().st_size <= PLAN['classLogMaximumBytes'], 'bounded independent class log')
        log_hash = digest(log); class_log = log.read_text()
        receipt = observations['check'](stdout, stderr, class_log, config, seal['inventory']['pins'],
            seal['inventory']['owners'][owner], variant, owner, terminals, work, fixture, replay, successful,
            sorted({r.split('\t')[2] for r in terminals}))
        require(digest(log) == log_hash, 'class log mutated during validation')
        commands.results[-1]['classLoadCorroborationLog'] = {'path': str(log), 'sha256': log_hash, 'bytes': log.stat().st_size}
        protocol_rows = [line for line in stderr.splitlines() if line.startswith('RF_')]
        protocol_bytes = ('\n'.join(protocol_rows) + '\n').encode()
        compressed = gzip.compress(protocol_bytes, mtime=0)
        require(len(protocol_rows) <= PLAN['protocolRowMaximum'] and len(protocol_bytes) <= PLAN['protocolMaximumBytes']
                and len(compressed) <= PLAN['protocolCompressedMaximumBytes'], 'bounded complete protocol evidence')
        receipt['protocol'] = {'encoding': 'gzip+base64', 'bytes': len(protocol_bytes),
                               'sha256': hashlib.sha256(protocol_bytes).hexdigest(), 'rowCount': len(protocol_rows),
                               'data': base64.b64encode(compressed).decode()}
        receipt['exitStatus'] = status
        result['variants'][variant][name] = receipt
        return stdout + '\n' + stderr
    first_failure = None
    for sig in old_handlers:
        signal.signal(sig, deadline)
    signal.alarm(PLAN['aggregateTimeoutSeconds'])
    try:
        status, _, _ = run('compile-observer', [str(jdk / 'bin/javac'), '-J-Xmx128m', '-proc:none', '--release', '21',
            '-cp', os.pathsep.join(str(p) for p in [*sorted((Path(config['maven']).parent.parent / 'lib').glob('*.jar')),
                Path(config['maven']).parent.parent / 'boot/plexus-classworlds-2.11.0.jar']),
            '-d', str(output / 'observer'), str(HERE / 'ResourcesFlattenRealmObserver.java'), str(HERE / 'ResourcesFlattenCompletionSpy.java')], 30)
        require(status == 0, 'observer compilation')
        with zipfile.ZipFile(output / 'observer.jar', 'x') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: ResourcesFlattenRealmObserver\n\n')
            for p in sorted((output / 'observer').glob('ResourcesFlattenRealmObserver*.class')):
                archive.write(p, p.name)
        with zipfile.ZipFile(output / 'completion-spy.jar', 'x') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n\n')
            archive.writestr('META-INF/plexus/components.xml', '<component-set><components><component>'
                '<role>org.apache.maven.eventspy.EventSpy</role><role-hint>resources-flatten</role-hint>'
                '<implementation>ResourcesFlattenCompletionSpy</implementation></component></components></component-set>')
            archive.write(output / 'observer/ResourcesFlattenCompletionSpy.class', 'ResourcesFlattenCompletionSpy.class')
        for name in ('observer.jar', 'completion-spy.jar'):
            fixed[str(output / name)] = digest(output / name)
        for variant in ('baseline', 'candidate'):
            result['variants'][variant] = {}
            source = Path(config[variant])
            resource_dir = output / (variant + '-resources')
            resource = fixtures['resources_case'](resource_dir, source, variant); freeze_fixture(resource_dir)
            resource_terms = [terminal('resources', g, e, '.') for g, e in
                              [('resources', 'main-fixture'), ('testResources', 'test-fixture'), ('copy-resources', 'copy-fixture')]]
            goal(variant, 'resources-success', 'resources', resource_dir, resource['sourcePom'], resource['goals'], resource_terms, replay='api')
            result['variants'][variant]['resources-success']['outputs'] = fixtures['check_resources'](resource_dir, variant)
            changed = resource_dir / 'inputs/plain/welcome.txt'; changed.write_text('changed=${fixture.name}\n')
            fixed[str(changed)] = digest(changed)
            goal(variant, 'resources-repeat', 'resources', resource_dir, resource['sourcePom'], resource['goals'], resource_terms)
            result['variants'][variant]['resources-repeat']['outputs'] = fixtures['check_resources'](resource_dir, variant, True)
            log = goal(variant, 'resources-failure', 'resources', resource_dir, resource['sourcePom'],
                [fixtures['RESOURCE_OWNER'] + ':copy-resources@bad-encoding'],
                [terminal('resources', 'copy-resources', 'bad-encoding', '.', 'MojoFailed')], successful=False)
            require('BOGUS-FIXTURE-CHARSET' in log, 'resource failure not attributable to controlled encoding')
            result['variants'][variant]['resources-failure'].update(
                expectedFailure={'kind': 'unsupported-encoding', 'requiredFragments': ['BOGUS-FIXTURE-CHARSET']},
                actualFailureAttributionVerified=True,
                failureAttributionLines=failure_attribution(log, ['BOGUS-FIXTURE-CHARSET']))
            result['variants'][variant]['resources-failure']['retainedOutputs'] = fixtures['check_resources'](resource_dir, variant, True)
            for demo in (False, True):
                name = 'demo' if demo else 'sdk'; work = output / (variant + '-' + name)
                fixture = fixtures['flatten_case'](work, source, variant, demo); freeze_fixture(work)
                keys = ['.', 'ordinary', 'sdk'] if demo else ['sdk']
                flatten_terms = [terminal('flatten', 'flatten', 'flatten', key) for key in keys]
                goals = ['process-resources']
                if not demo:
                    goals += [fixtures['FLATTEN_OWNER'] + ':flatten@flatten']
                    flatten_terms += [terminal('flatten', 'flatten', 'flatten', 'sdk', 'MojoFailed')]
                log = goal(variant, name + '-flatten', 'flatten', work, fixture['project'], goals, flatten_terms,
                     fixture, replay='api', demo=demo, successful=demo)
                if not demo:
                    parent = fixtures['GROUP'] + ':fixture-parent:pom:' + fixtures['VERSION']
                    require('Non-resolvable parent POM' in log and parent in log
                            and "'parent.relativePath' points at wrong local POM" in log,
                            'SDK repeat failed for a different cause than the retained baseline CI-parent limit')
                    result['variants'][variant][name + '-flatten'].update(
                        expectedFailure={'kind': 'retained-ci-parent-repeat', 'parentCoordinate': parent,
                            'requiredFragments': ['Non-resolvable parent POM', parent,
                                                  "'parent.relativePath' points at wrong local POM"]},
                        actualFailureAttributionVerified=True,
                        failureAttributionLines=failure_attribution(log, ['Non-resolvable parent POM', parent,
                            "'parent.relativePath' points at wrong local POM"]))
                    result['variants'][variant][name + '-flatten']['knownBaselineLimitation'] = {
                        'parentCoordinate': parent, 'parentVersionPattern': '${revision}${sha1}${changelist}',
                        'parentRelativePath': '../pom.xml', 'firstFlattenSucceeded': True,
                        'secondFlattenFailedWithoutProjectMutation': True,
                        'productionRevisionParentRepeatProven': False}
                result['variants'][variant][name + '-flatten']['outputs'] = fixtures['check_flatten_files'](fixture)
                if demo:
                    goal(variant, 'demo-repeat', 'flatten', work, fixture['project'], goals, flatten_terms, fixture, demo=True)
                    result['variants'][variant]['demo-repeat']['outputs'] = fixtures['check_flatten_files'](fixture)
                clean_id = 'flatten.clean' if demo else 'flatten-clean'
                clean_goal = fixtures['FLATTEN_OWNER'] + ':clean@' + clean_id
                clean_terms = [terminal('flatten', 'clean', clean_id, key) for key in keys for _ in range(2)]
                goal(variant, name + '-clean', 'flatten', work, fixture['project'], [clean_goal, clean_goal], clean_terms, fixture, demo=demo)
                fixtures['check_flatten_files'](fixture, True)
            work = output / (variant + '-flatten-invalid')
            fixture = fixtures['flatten_case'](work, source, variant, invalid=True); freeze_fixture(work)
            log = goal(variant, 'flatten-failure', 'flatten', work, fixture['project'], ['process-resources'],
                 [terminal('flatten', 'flatten', 'flatten', 'sdk', 'MojoFailed')], fixture,
                 successful=False, properties=['-DflattenedPomFilename=blocked/flattened-pom.xml'])
            require('blocked' in log, 'flatten failure not attributable to controlled output obstruction')
            result['variants'][variant]['flatten-failure'].update(
                expectedFailure={'kind': 'blocked-flatten-output', 'requiredFragments': ['blocked']},
                actualFailureAttributionVerified=True,
                failureAttributionLines=failure_attribution(log, ['blocked']))
            fixtures['check_flatten_sources'](fixture)
            work = output / (variant + '-sdk-production-context')
            fixture = fixtures['production_sdk_case'](work, source, variant); freeze_fixture(work)
            exact_goals = [fixtures['FLATTEN_OWNER'] + ':flatten@flatten',
                           fixtures['FLATTEN_OWNER'] + ':clean@flatten-clean']
            exact_terms = [terminal('flatten', g, e, 'integrations/host-sdk')
                           for g, e in [('flatten', 'flatten'), ('clean', 'flatten-clean')]]
            goal(variant, 'sdk-production-context', 'flatten', work, fixture['project'], exact_goals,
                 exact_terms, fixture)
            fixtures['check_flatten_files'](fixture, True)
            result['variants'][variant]['sdk-production-context'].update(
                actualFlattenOutputSnapshotVerified=True, actualCleanRemovedFlattenedOutput=True,
                exactSourcePomBytes=True, productionRepeatProven=False, fullProductionBuildClaim=False)
        require(len(commands.results) == 21 and all(c['captureComplete'] and c['processGroupCleaned'] for c in commands.results), 'one compile and 20 bounded processes')
        class_logs = list((output / 'class-load').glob('*.log'))
        require(len(class_logs) == 20 and sum(p.stat().st_size for p in class_logs) <= PLAN['classLogTotalMaximumBytes'], 'complete bounded JVM corroboration set')
        require(sum(case['protocol']['bytes'] for cases in result['variants'].values() for case in cases.values())
                <= PLAN['protocolTotalMaximumBytes'], 'aggregate complete protocol byte bound')
        check_seal(seal, True)
        result.update(freshSourceRuntimeCacheVerified=True)
        result.update(status='PASSED_CURRENT_RUNTIME_PAIRED_GOALS_AND_COMPLETE_SYMBOLIC_LINKAGE', inputSealsUnchanged=True,
                      generatedFixedInputs=fixed, allOutputsCheckedAgainstIndependentOracles=True)
        require(len(json.dumps(result, indent=2).encode()) <= PLAN['receiptMaximumBytes'], 'bounded complete goal receipt')
    except BaseException as failure:
        first_failure = failure
        result.update(status='FAILED', errorType=type(failure).__name__, error=str(failure))
    finally:
        try:
            storage.stop()
            result['storage'] = storage.report()
        except BaseException as cleanup_failure:
            result.update(status='FAILED', cleanupErrorType=type(cleanup_failure).__name__,
                          cleanupError=str(cleanup_failure))
            if first_failure is None:
                first_failure = cleanup_failure
                result.update(errorType=type(cleanup_failure).__name__, error=str(cleanup_failure))
        signal.alarm(0)
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print('RESOURCES_FLATTEN_RESULT ' + json.dumps({'status': result['status'], 'path': str(output / 'result.json')}), flush=True)
    if first_failure is not None:
        raise first_failure
    return result


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--prepare-seal', type=Path); cli.add_argument('--execute-seal', type=Path)
    for name in ('baseline', 'candidate', 'maven', 'jdk', 'repository', 'output'):
        cli.add_argument('--' + name)
    args = cli.parse_args()
    if args.prepare_seal:
        require(not args.execute_seal and not args.prepare_seal.exists(), 'new prepare-only seal')
        config = {k: v for k, v in vars(args).items() if k not in ('prepare_seal', 'execute_seal')}
        seal = prepare(config); args.prepare_seal.write_text(json.dumps(seal, indent=2) + '\n')
        print(json.dumps({'status': 'PREPARED_NOT_EXECUTED', 'seal': str(args.prepare_seal), 'sha256': digest(args.prepare_seal)}))
    elif args.execute_seal:
        prior = digest(args.execute_seal)
        try:
            execute(json.loads(args.execute_seal.read_text()))
        finally:
            require(digest(args.execute_seal) == prior, 'reviewed seal bytes changed')
    else:
        print(json.dumps(PLAN, indent=2))
