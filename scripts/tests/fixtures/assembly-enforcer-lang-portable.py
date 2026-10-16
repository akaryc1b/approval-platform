#!/usr/bin/env python3
"""Hosted Central cache preparation, then the unchanged sealed 18-goal fixture."""
import argparse
import json
import os
from pathlib import Path
from runpy import run_path
import shutil
import signal
import sys
import tempfile
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from checkstyle_live_support import CENTRAL_SETTINGS, Commands, isolated_env, require, digest, NS, P
from run_boot_live_compatibility import cache_snapshot, hashes

RESOLVE = 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins'
PLAN = {'execution': 'NOT_RUN', 'hostedOnly': True,
    'explicitOptIn': 'ASSEMBLY_ENFORCER_LANG_COMPATIBILITY=true',
    'resolutionCommandsMaximum': 2, 'preparationCommandTimeoutSeconds': 120,
    'preparationAggregateTimeoutSeconds': 240, 'offlineGoalCount': 18,
    'compileCount': 1, 'offlineAggregateTimeoutSeconds': 1200, 'outerTimeoutSeconds': 1500,
    'repositoryUrl': 'https://repo.maven.apache.org/maven2',
    'scannerEvidence': False, 'productionActivationClaim': False}


def preparation_pom(fixture, source, variant):
    require(variant in ('baseline', 'candidate'), 'fixed Lang variant')
    ET.register_namespace('', NS['m'])
    root = ET.Element(P + 'project')
    for name, value in [('modelVersion', '4.0.0'), ('groupId', 'local.lang.compatibility'),
            ('artifactId', 'owner-cache'), ('version', '1'), ('packaging', 'pom')]:
        ET.SubElement(root, P + name).text = value
    plugins = ET.SubElement(ET.SubElement(root, P + 'build'), P + 'plugins')
    for owner in ('assembly', 'enforcer'):
        plugins.append(fixture['variant_plugin'](source, owner, variant))
    return ET.tostring(root, encoding='utf-8', xml_declaration=True)


def preparation_command(config, sandbox, variant, ci):
    require(ci is True and variant in ('baseline', 'candidate'), 'Central preparation is hosted-CI-only')
    settings, toolchains = sandbox / 'central-settings.xml', sandbox / 'empty-toolchains.xml'
    return [config['maven'], '-B', '-ntp', '-N', '--strict-checksums', '-s', str(settings), '-gs', str(settings),
        '-t', str(toolchains), '-gt', str(toolchains), '-Dmaven.repo.local=' + config['repository'],
        '-Dmaven.multiModuleProjectDirectory=' + str(sandbox), '-Dstyle.color=never',
        '-f', str(sandbox / (variant + '-pom.xml')), RESOLVE,
        '-DincludeArtifactIds=maven-assembly-plugin,maven-enforcer-plugin', '-DexcludeTransitive=false']


def adapter_hashes(source):
    return hashes([Path(__file__).resolve(), HERE / 'assembly-enforcer-lang-live-support.mjs',
        HERE.parent / 'assembly-enforcer-lang-live-compatibility.test.mjs',
        HERE.parent / 'assembly-enforcer-lang-portable-harness.test.mjs',
        *[source / 'scripts/ci' / name for name in ('setup-maven.mjs', 'maven-toolchain.mjs', 'select-java21.mjs')]])


def execute_portable(fixture, config, sandbox):
    """Use the same helpers and exact fixture; independently bind this adapter."""
    paths = fixture['checked_paths'](config)
    source, repository = paths['source'], paths['repository']
    for name in ('home', 'tmp'): (sandbox / name).mkdir()
    (sandbox / 'central-settings.xml').write_text(CENTRAL_SETTINGS)
    (sandbox / 'empty-toolchains.xml').write_text('<toolchains/>\n')
    for variant in ('baseline', 'candidate'):
        (sandbox / (variant + '-pom.xml')).write_bytes(preparation_pom(fixture, source, variant))
    source_hashes = fixture['source_hashes'](source)
    own_hashes = adapter_hashes(source)
    runtime_hashes = fixture['runtime_hashes'](paths['maven'], paths['jdk'])
    cache_before = cache_snapshot(repository)
    fixed = {str(sandbox / name): digest(sandbox / name) for name in
        ('central-settings.xml', 'empty-toolchains.xml', 'baseline-pom.xml', 'candidate-pom.xml')}
    pins = fixture['read_pins']()

    def existing_pins():
        complete = set()
        for coordinate, expected in pins['artifactHashes'].items():
            jar = fixture['artifact_path'](repository, coordinate)
            if jar.exists() or jar.is_symlink():
                require(jar.is_file() and not jar.is_symlink() and jar.resolve().is_relative_to(repository)
                    and digest(jar) == expected, 'pre-existing pinned artifact changed')
                pom = jar.with_suffix('.pom')
                if pom.exists() or pom.is_symlink():
                    require(pom.is_file() and not pom.is_symlink() and pom.resolve().is_relative_to(repository), 'escaped artifact POM')
                    complete.add(coordinate)
        return complete

    environment = isolated_env(paths['jdk'], sandbox)
    environment.update(MAVEN_BASEDIR=str(sandbox), MAVEN_OPTS='-Xmx256m -XX:-UsePerfData -Duser.home='
        + str(sandbox / 'home') + ' -Djava.io.tmpdir=' + str(sandbox / 'tmp'))
    commands = Commands(sandbox, environment, PLAN['preparationAggregateTimeoutSeconds'])
    receipt = {'status': 'RUNNING', 'plan': PLAN, 'commands': commands.results,
        'centralPreparationOnly': True, 'sourceHashes': source_hashes, 'adapterHashes': own_hashes,
        'runtimeHashes': runtime_hashes, 'fixedPreparationHashes': fixed,
        'artifactHashes': pins['artifactHashes'], 'owners': pins['owners'],
        'runtimeProvisioningPrerequisites': ['existing setup-maven.mjs SHA-512-verified Apache Maven 3.9.16',
            'existing select-java21.mjs verified preinstalled Java 21'],
        'scannerEvidence': False, 'productionActivationClaim': False}

    def unchanged():
        require(fixture['source_hashes'](source) == source_hashes and adapter_hashes(source) == own_hashes,
            'preparation source changed')
        require(all(digest(Path(p)) == h for p, h in fixed.items()), 'preparation inputs changed')
        current = cache_snapshot(repository)
        require(all(current.get(p) == h for p, h in cache_before.items()), 'pre-existing cache artifacts changed')

    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGTERM, signal.SIGINT)}
    def interrupted(signum, frame): raise TimeoutError('hosted fixture termination')
    for sig in previous: signal.signal(sig, interrupted)
    try:
        existing_pins()  # Reject corrupt cached bytes before any possible retrieval.
        for variant in ('baseline', 'candidate'):
            needed = {c for owner in pins['owners'].values() for c in owner[variant]}
            if needed <= existing_pins(): continue
            unchanged()
            status, _, _ = commands.run('prepare-' + variant,
                preparation_command(config, sandbox, variant, True), PLAN['preparationCommandTimeoutSeconds'])
            require(status == 0, 'bounded Central owner preparation failed'); unchanged()
        require(existing_pins() == set(pins['artifactHashes']), 'complete pinned JAR/POM union required')
        require(fixture['runtime_hashes'](paths['maven'], paths['jdk']) == runtime_hashes, 'preparation runtime changed')
        unchanged()
        seal = fixture['prepare'](config); seal_file = sandbox / 'execution-seal.json'
        seal_file.write_text(json.dumps(seal, indent=2) + '\n'); receipt['executionSealSha256'] = digest(seal_file)
        receipt['executionResultPath'] = str(paths['output'] / 'result.json')
        fixture['execute'](seal)
        require(digest(seal_file) == receipt['executionSealSha256'], 'execution seal changed')
        unchanged()
        receipt.update(status='PASSED', existingArtifactHashesUnchanged=True, adapterSealsUnchanged=True)
    except BaseException as failure:
        receipt.update(status='FAILED', errorType=type(failure).__name__, error=str(failure)); raise
    finally:
        for sig, handler in previous.items(): signal.signal(sig, handler)
        receipt['resolutionCommandsRun'] = len(commands.results)
        path = sandbox / 'portable-result.json'; path.write_text(json.dumps(receipt, indent=2) + '\n')
        print('ASSEMBLY_ENFORCER_LANG_PORTABLE_RESULT ' + json.dumps({'status': receipt['status'], 'path': str(path)}), flush=True)
    return receipt


def run():
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('ASSEMBLY_ENFORCER_LANG_COMPATIBILITY') == 'true',
        'explicit hosted compatibility execution required')
    fixture = run_path(str(HERE / 'assembly-enforcer-lang-compatibility.py'))
    executable = shutil.which('mvn'); jdk_value = os.environ.get('JAVA_HOME', '')
    require(executable and jdk_value and Path(jdk_value).is_absolute(), 'existing verified Maven/JDK environment required')
    repository = (Path.home() / '.m2/repository').resolve(); repository.mkdir(parents=True, exist_ok=True)
    sandbox = Path(tempfile.mkdtemp(prefix='assembly-enforcer-lang-ci-')).resolve()
    config = {'source': str(fixture['ROOT']), 'maven': str(Path(executable).resolve()),
        'jdk': str(Path(jdk_value).resolve()), 'repository': str(repository), 'output': str(sandbox / 'offline-goals')}
    execute_portable(fixture, config, sandbox)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--execute', action='store_true')
    if parser.parse_args().execute: run()
    else: print(json.dumps(PLAN))
