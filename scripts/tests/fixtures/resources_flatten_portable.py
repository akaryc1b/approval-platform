#!/usr/bin/env python3
"""Hosted, bounded Central preparation followed by one fresh sealed goal run.

Import and the default plan are inert. No historical private result is an input.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
from runpy import run_path
import shutil
import signal
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
P = '{' + NS['m'] + '}'
RESOLVE = 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins'
STATUS = 'PASSED_CURRENT_RUNTIME_PAIRED_GOALS_AND_COMPLETE_SYMBOLIC_LINKAGE'
PLAN = {'execution': 'NOT_RUN', 'hostedOnly': True,
        'explicitOptIn': 'RESOURCES_FLATTEN_COMPATIBILITY=true',
        'resolutionCommandsMaximum': 2, 'preparationCommandTimeoutSeconds': 120,
        'preparationAggregateTimeoutSeconds': 240, 'offlineGoalCount': 20, 'compileCount': 1,
        'offlineAggregateTimeoutSeconds': 1800, 'offlineOuterTimeoutSeconds': 1830,
        'outerTimeoutSeconds': 2100, 'repositoryUrl': 'https://repo.maven.apache.org/maven2',
        'artifactUnionCount': 13, 'owners': ['resources', 'flatten'],
        'scannerEvidence': False, 'productionRepeatProven': False, 'fullProductionBuildClaim': False}


def require(ok, message):
    if not ok:
        raise ValueError('Resources/Flatten portable: ' + message)


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def hosted_gate():
    require(os.environ.get('GITHUB_ACTIONS') == 'true'
            and os.environ.get('RESOURCES_FLATTEN_COMPATIBILITY') == 'true',
            'explicit hosted compatibility execution required')


def canonical(path):
    path = Path(path)
    require(path.is_absolute() and path.resolve() == path and not path.is_symlink()
            and not any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in str(path)),
            'canonical JVM-safe path required')
    return path


def checked_paths(config, sandbox):
    require(set(config) == {'baseline', 'candidate', 'maven', 'jdk', 'repository', 'output'}, 'exact configuration keys')
    paths = {k: canonical(v) for k, v in config.items()}
    sandbox = canonical(sandbox)
    require(sandbox.is_dir() and not any(sandbox.iterdir()), 'fresh empty preparation scratch required')
    require(paths['baseline'].is_dir() and paths['candidate'].is_dir()
            and paths['jdk'].is_dir() and paths['repository'].is_dir() and paths['maven'].is_file(),
            'existing source pair, selected official tools and repository required')
    require(paths['output'] == sandbox / 'offline-goals' and not paths['output'].exists(), 'fresh owned goal output required')
    inputs = [paths[k] for k in ('baseline', 'candidate', 'jdk', 'repository')] + [paths['maven'].parent.parent]
    require(all(not sandbox.is_relative_to(p) and not p.is_relative_to(sandbox) for p in inputs), 'preparation scratch/input overlap')
    require(all(not paths['repository'].is_relative_to(paths[k]) and not paths[k].is_relative_to(paths['repository'])
                for k in ('baseline', 'candidate', 'jdk')), 'repository/input overlap')
    for path in [sandbox, *inputs]:
        require(not any((p / '.mvn').exists() or (p / '.mvn').is_symlink() for p in (path, *path.parents)),
                'inherited Maven bootstrap configuration forbidden')
    return paths


def artifact_path(repository, coordinate):
    require(re.fullmatch(r'[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:jar:[A-Za-z0-9_.-]+', coordinate) is not None,
            'plain pinned JAR coordinate required')
    group, name, kind, version = coordinate.split(':')
    return repository / group.replace('.', '/') / name / version / (name + '-' + version + '.' + kind)


def existing_pins(repository, pins):
    """Reject bad cached JARs before retrieval; a JAR without its POM is incomplete."""
    require(len(pins['artifactHashes']) == 13 and set(pins['owners']) == {'resources', 'flatten'}, 'fixed thirteen-artifact owner union')
    complete = set()
    for coordinate, expected in pins['artifactHashes'].items():
        require(re.fullmatch('[0-9a-f]{64}', expected) is not None, 'content hash required')
        jar = artifact_path(repository, coordinate)
        pom = jar.with_suffix('.pom')
        for path in (jar, pom):
            if path.exists() or path.is_symlink():
                require(path.is_file() and not path.is_symlink() and path.resolve() == path
                        and path.resolve().is_relative_to(repository), 'escaped/non-regular pinned cache file')
        if pom.exists():
            require(0 < pom.stat().st_size <= 4 * 1024 * 1024, 'bounded nonempty artifact POM required')
            require(ET.parse(pom).getroot().tag == P + 'project', 'artifact POM must be XML project')
        if jar.exists():
            require(digest(jar) == expected, 'pre-existing pinned artifact changed: ' + coordinate)
            if pom.exists():
                complete.add(coordinate)
    return complete


def preparation_pom(fixtures, source, variant):
    require(variant in ('baseline', 'candidate'), 'fixed source variant')
    ET.register_namespace('', NS['m'])
    root = ET.Element(P + 'project')
    for name, value in [('modelVersion', '4.0.0'), ('groupId', 'local.resources.flatten.compatibility'),
                        ('artifactId', 'owner-cache'), ('version', '1'), ('packaging', 'pom')]:
        ET.SubElement(root, P + name).text = value
    plugins = ET.SubElement(ET.SubElement(root, P + 'build'), P + 'plugins')
    for owner, group, artifact, version in [('resources', 'org.apache.maven.plugins', 'maven-resources-plugin', '3.4.0'),
                                           ('flatten', 'org.codehaus.mojo', 'flatten-maven-plugin', '1.7.3')]:
        selected = fixtures['plugin'](Path(source), owner, variant)
        require(selected.findtext('m:groupId', namespaces=NS) == group
                and selected.findtext('m:artifactId', namespaces=NS) == artifact, 'fixed preparation owner')
        element = selected.find('m:version', NS)
        require(element is not None, 'explicit preparation owner version required')
        declared = element.text or ''
        if declared.startswith('${') and declared.endswith('}'):
            property_name = declared[2:-1]
            require(re.fullmatch('[A-Za-z0-9_.-]+', property_name) is not None, 'plain source version property')
            properties = ET.parse(Path(source) / 'pom.xml').getroot().findall('m:properties/m:' + property_name, NS)
            require(len(properties) == 1 and properties[0].text == version, 'source plugin version property drift')
        else:
            require(declared == version, 'source plugin version drift')
        element.text = version  # Freeze source-resolved 3.4.0 / 1.7.3, with no missing parent property.
        plugins.append(selected)
    return ET.tostring(root, encoding='utf-8', xml_declaration=True)


def preparation_command(config, sandbox, variant, ci):
    require(ci is True and variant in ('baseline', 'candidate'), 'Central preparation is hosted-only')
    settings, toolchains = sandbox / 'central-settings.xml', sandbox / 'empty-toolchains.xml'
    return [config['maven'], '-B', '-ntp', '-N', '--strict-checksums', '-s', str(settings), '-gs', str(settings),
            '-t', str(toolchains), '-gt', str(toolchains), '-Dmaven.repo.local=' + config['repository'],
            '-Dmaven.multiModuleProjectDirectory=' + str(sandbox), '-Dstyle.color=never',
            '-f', str(sandbox / (variant + '-pom.xml')), RESOLVE,
            '-DincludeArtifactIds=maven-resources-plugin,flatten-maven-plugin', '-DexcludeTransitive=false']


def adapter_hashes(source):
    files = [Path(__file__).resolve(), HERE / 'resources-flatten-live-support.mjs',
             HERE.parent / 'resources-flatten-live-compatibility.test.mjs',
             HERE.parent / 'resources-flatten-compatibility-harness.test.mjs',
             *[source / 'scripts/ci' / name for name in ('setup-maven.mjs', 'maven-toolchain.mjs', 'select-java21.mjs')]]
    require(all(p.is_file() and not p.is_symlink() and p.resolve() == p for p in files), 'real reviewed adapter inputs')
    return {str(p): digest(p) for p in files}


def execute_portable(fixture, config, sandbox):
    hosted_gate()
    paths = checked_paths(config, sandbox)
    sandbox = Path(sandbox)
    started = time.monotonic()
    receipt = {'schemaVersion': 'RESOURCES_FLATTEN_PORTABLE_V1', 'status': 'RUNNING', 'plan': PLAN,
               'config': config, 'commands': [], 'centralPreparationOnly': True,
               'scannerEvidence': False, 'productionRepeatProven': False, 'fullProductionBuildClaim': False,
               'runtimeProvisioningPrerequisites': ['existing setup-maven.mjs SHA-512-verified Apache Maven 3.9.16',
                                                  'existing select-java21.mjs verified preinstalled Java 21']}
    commands = storage = None
    failure = None
    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT)}
    def interrupted(signum, frame):
        raise TimeoutError('bounded hosted preparation/goal deadline or termination')
    def remaining():
        seconds = PLAN['outerTimeoutSeconds'] - (time.monotonic() - started)
        require(seconds > 0, 'whole-wrapper budget exhausted')
        return max(1, int(seconds))
    for sig in previous:
        signal.signal(sig, interrupted)
    signal.alarm(remaining())
    # The live entry applies timeout TERM at 2090s / KILL at 2100s to the
    # whole process group. The core temporarily owns SIGALRM during its goals.
    try:
        supplied = fixture['private_hashes']()
        contract = fixture['source_contract']()
        pair = contract['source_pair'](paths['baseline'], paths['candidate'])
        own = adapter_hashes(paths['candidate'])
        helpers = fixture['support'](paths['candidate'])
        require(fixture['private_hashes']() == supplied, 'harness/helper changed during import')
        common, runtime = helpers['checkstyle_live_support'], helpers['run_boot_live_compatibility']
        fixtures = run_path(str(HERE / 'resources_flatten_fixtures.py'))
        fixture['tool_pins'](config)
        pins = json.loads(Path(fixture['PINS']).read_text())
        require(set(c for owner in pins['owners'].values() for v in ('baseline', 'candidate') for c in owner[v])
                == set(pins['artifactHashes']), 'exact owner closure union')
        existing_pins(paths['repository'], pins)  # This precedes every possible retrieval.
        _, imported = runtime.python_origins()
        runtime_hashes = runtime.hashes(runtime.runtime_files(paths['jdk'], paths['maven'], imported))
        cache_before = runtime.cache_snapshot(paths['repository'])
        for name in ('home', 'tmp'):
            (sandbox / name).mkdir()
        (sandbox / 'central-settings.xml').write_text(common.CENTRAL_SETTINGS)
        (sandbox / 'empty-toolchains.xml').write_text('<toolchains/>\n')
        for variant in ('baseline', 'candidate'):
            (sandbox / (variant + '-pom.xml')).write_bytes(preparation_pom(fixtures, paths[variant], variant))
        fixed = {str(sandbox / name): digest(sandbox / name) for name in
                 ('central-settings.xml', 'empty-toolchains.xml', 'baseline-pom.xml', 'candidate-pom.xml')}
        environment = common.isolated_env(paths['jdk'], sandbox)
        environment.update(MAVEN_BASEDIR=str(sandbox), MAVEN_OPTS='-Xmx256m -XX:-UsePerfData -Duser.home='
                           + str(sandbox / 'home') + ' -Djava.io.tmpdir=' + str(sandbox / 'tmp'))
        commands = common.Commands(sandbox, environment, PLAN['preparationAggregateTimeoutSeconds'])
        storage = helpers['jar_plugin_storage'].StorageGuard(sandbox, 256 * 1024 * 1024, 3000)
        receipt.update(commands=commands.results, source=pair, harnessHashes=supplied,
                       adapterHashes=own, runtimeHashes=runtime_hashes, preexistingCacheHashes=cache_before,
                       fixedPreparationHashes=fixed, artifactHashes=pins['artifactHashes'], owners=pins['owners'])
        def unchanged():
            remaining()
            require(contract['source_pair'](paths['baseline'], paths['candidate']) == pair
                    and fixture['private_hashes']() == supplied and adapter_hashes(paths['candidate']) == own,
                    'preparation source/harness/adapter changed')
            require(all(Path(p).is_file() and digest(p) == h for p, h in fixed.items()), 'fixed preparation input changed')
            require(all(Path(p).is_file() and digest(p) == h for p, h in runtime_hashes.items()), 'preparation runtime changed')
            current = runtime.cache_snapshot(paths['repository'])
            require(all(current.get(p) == h for p, h in cache_before.items()), 'pre-existing cache artifact/POM changed')
        storage.start()
        for variant in ('baseline', 'candidate'):
            needed = {c for owner in pins['owners'].values() for c in owner[variant]}
            if needed <= existing_pins(paths['repository'], pins):
                continue
            unchanged()
            status, _, _ = commands.run('prepare-' + variant, preparation_command(config, sandbox, variant, True),
                                         PLAN['preparationCommandTimeoutSeconds'])
            require(status == 0, 'bounded Central owner preparation failed')
            unchanged()
        require(existing_pins(paths['repository'], pins) == set(pins['artifactHashes']), 'complete pinned JAR/POM union required')
        unchanged()
        receipt['cacheAfterPreparationHashes'] = runtime.cache_snapshot(paths['repository'])
        seal = fixture['prepare'](config)
        require(seal['source'] == pair and seal['harnessHashes'] == supplied, 'fresh offline seal differs from preparation')
        seal_file = sandbox / 'execution-seal.json'
        seal_file.write_text(json.dumps(seal, indent=2) + '\n')
        receipt['executionSealSha256'] = digest(seal_file)
        receipt['executionResultPath'] = str(paths['output'] / 'result.json')
        receipt['preparationCompleted'] = True
        result = fixture['execute'](seal)
        # The core owns its 1800-second aggregate; restore this wrapper's remaining budget.
        signal.signal(signal.SIGALRM, interrupted)
        signal.alarm(remaining())
        require(result['schemaVersion'] == 'RESOURCES_FLATTEN_COMPAT_V1' and result['status'] == STATUS,
                'complete fresh portable goal result required')
        require(digest(seal_file) == receipt['executionSealSha256'], 'execution seal changed')
        fixture['check_seal'](seal, True)
        unchanged()
        require(runtime.cache_snapshot(paths['repository']) == receipt['cacheAfterPreparationHashes'], 'offline cache changed')
        receipt.update(status='PASSED', existingArtifactHashesUnchanged=True, adapterSealsUnchanged=True,
                       freshSourceRuntimeCacheVerified=True, executionResultSha256=digest(paths['output'] / 'result.json'))
    except BaseException as error:
        failure = error
        receipt.update(status='FAILED', errorType=type(error).__name__, error=str(error))
    finally:
        signal.alarm(0)
        if storage is not None:
            try:
                storage.stop()
                storage.scan()
                require(storage.failure is None, 'preparation/output storage violation: ' + str(storage.failure))
                receipt['storage'] = storage.report()
            except BaseException as error:
                receipt['cleanupError'] = str(error)
                if failure is None:
                    failure = error
                    receipt.update(status='FAILED', errorType=type(error).__name__, error=str(error))
        receipt['resolutionCommandsRun'] = len(receipt['commands'])
        receipt['elapsedSeconds'] = round(time.monotonic() - started, 3)
        if receipt['elapsedSeconds'] > PLAN['outerTimeoutSeconds'] and failure is None:
            failure = TimeoutError('whole-wrapper budget exhausted during cleanup')
            receipt.update(status='FAILED', errorType=type(failure).__name__, error=str(failure))
        receipt['cleanupComplete'] = storage is not None and 'cleanupError' not in receipt
        path = sandbox / 'portable-result.json'
        path.write_text(json.dumps(receipt, indent=2) + '\n')
        print('RESOURCES_FLATTEN_PORTABLE_RESULT ' + json.dumps({'status': receipt['status'], 'path': str(path)}), flush=True)
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    if failure is not None:
        raise failure
    return receipt


def run():
    hosted_gate()
    fixture = run_path(str(HERE / 'resources_flatten_compatibility.py'))
    executable, jdk_value = shutil.which('mvn'), os.environ.get('JAVA_HOME', '')
    require(executable and jdk_value and Path(jdk_value).is_absolute(), 'existing verified Maven and selected Java 21 required')
    source = canonical(fixture['ROOT'])
    repository = canonical((Path.home() / '.m2/repository').resolve())
    repository.mkdir(parents=True, exist_ok=True)
    base = canonical(Path(tempfile.mkdtemp(prefix='resources-flatten-ci-')).resolve())
    baseline = base / 'baseline'
    fixture['source_contract']()['materialize_baseline'](source, baseline)
    sandbox = base / 'execution'
    sandbox.mkdir()
    config = {'baseline': str(baseline), 'candidate': str(source), 'maven': str(Path(executable).resolve()),
              'jdk': str(Path(jdk_value).resolve()), 'repository': str(repository), 'output': str(sandbox / 'offline-goals')}
    execute_portable(fixture, config, sandbox)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    if parser.parse_args().execute:
        run()
    else:
        print(json.dumps(PLAN, indent=2))
