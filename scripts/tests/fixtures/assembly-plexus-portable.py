#!/usr/bin/env python3
"""Hosted-only bounded cache preparation followed by the identical offline fixture."""
import argparse
import json
import os
from pathlib import Path
from runpy import run_path
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from checkstyle_live_support import CENTRAL_SETTINGS, Commands, isolated_env, require, digest, NS, P
from run_boot_live_compatibility import cache_snapshot

RESOLVE = 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins'


def preparation_command(config, sandbox, variant, ci):
    require(ci is True and variant in ('baseline', 'candidate'), 'Central preparation is hosted-CI-only')
    settings, toolchains = sandbox / 'central-settings.xml', sandbox / 'empty-toolchains.xml'
    return [config['maven'], '-B', '-ntp', '-N', '--strict-checksums', '-s', str(settings), '-gs', str(settings),
            '-t', str(toolchains), '-gt', str(toolchains), '-Dmaven.repo.local=' + config['repository'],
            '-Dmaven.multiModuleProjectDirectory=' + str(sandbox), '-Dstyle.color=never',
            '-f', str(sandbox / (variant + '-pom.xml')), RESOLVE,
            '-DincludeArtifactIds=maven-assembly-plugin', '-DexcludeTransitive=false']


def run():
    require(os.environ.get('GITHUB_ACTIONS') == 'true' and os.environ.get('ASSEMBLY_PLEXUS_COMPATIBILITY') == 'true', 'explicit hosted compatibility execution required')
    fixture = run_path(str(HERE / 'assembly-plexus-compatibility.py'))
    source = fixture['ROOT']; repository = (Path.home() / '.m2/repository').resolve(); repository.mkdir(parents=True, exist_ok=True)
    executable = shutil.which('mvn'); jdk_value = os.environ.get('JAVA_HOME', '')
    require(executable and jdk_value and Path(jdk_value).is_absolute(), 'pinned Maven/JDK environment required')
    sandbox = Path(tempfile.mkdtemp(prefix='assembly-plexus-ci-'))
    config = {'source': str(source), 'maven': str(Path(executable).resolve()), 'jdk': str(Path(jdk_value).resolve()),
              'repository': str(repository), 'output': str(sandbox / 'offline-goals')}
    for name in ('home', 'tmp'): (sandbox / name).mkdir()
    (sandbox / 'central-settings.xml').write_text(CENTRAL_SETTINGS)
    (sandbox / 'empty-toolchains.xml').write_text('<toolchains/>\n')
    contract = run_path(str(fixture['CONTRACT']))
    ET.register_namespace('', NS['m'])
    for variant in ('baseline', 'candidate'):
        root = ET.Element(P + 'project')
        for name, value in [('modelVersion', '4.0.0'), ('groupId', 'fixture'), ('artifactId', 'assembly-cache'), ('version', '1'), ('packaging', 'pom')]:
            ET.SubElement(root, P + name).text = value
        plugins = ET.SubElement(ET.SubElement(root, P + 'build'), P + 'plugins')
        plugins.append(contract['fixture_plugin'](source, baseline=variant == 'baseline'))
        (sandbox / (variant + '-pom.xml')).write_bytes(ET.tostring(root, encoding='utf-8', xml_declaration=True))
    source_hashes = fixture['source_hashes'](source)
    runtime_hashes = fixture['runtime_hashes'](Path(config['maven']), Path(config['jdk']))
    cache_before = cache_snapshot(repository)
    fixed = {str(sandbox / name): digest(sandbox / name) for name in ('central-settings.xml', 'empty-toolchains.xml', 'baseline-pom.xml', 'candidate-pom.xml')}
    pins = fixture['read_pins']()
    def existing_pins():
        present = set()
        for coordinate, expected in pins['artifactHashes'].items():
            file = fixture['artifact_path'](repository, coordinate)
            if file.exists():
                require(file.is_file() and not file.is_symlink() and digest(file) == expected, 'pre-existing pinned artifact changed')
                present.add(coordinate)
        return present
    existing_pins()
    environment = isolated_env(Path(config['jdk']), sandbox)
    environment.update(MAVEN_BASEDIR=str(sandbox), MAVEN_OPTS='-Xmx256m -XX:-UsePerfData -Duser.home='
                       + str(sandbox / 'home') + ' -Djava.io.tmpdir=' + str(sandbox / 'tmp'))
    commands = Commands(sandbox, environment, 240)
    receipt = {'status': 'RUNNING', 'commands': commands.results, 'resolutionCommandsMaximum': 2,
               'centralPreparationOnly': True, 'offlineGoalCount': 12, 'acceptanceGoal': False}
    def unchanged():
        require(fixture['source_hashes'](source) == source_hashes, 'preparation source changed')
        require(all(digest(Path(p)) == h for p, h in fixed.items()), 'preparation inputs changed')
        current = cache_snapshot(repository)
        require(all(current.get(p) == h for p, h in cache_before.items()), 'pre-existing cache artifacts changed')
    try:
        for variant in ('baseline', 'candidate'):
            if set(pins['variants'][variant]) <= existing_pins(): continue
            unchanged()
            status, _, _ = commands.run('prepare-' + variant, preparation_command(config, sandbox, variant, True), 120)
            require(status == 0, 'bounded Central Assembly preparation failed'); unchanged()
        require(existing_pins() == set(pins['artifactHashes']), 'complete pinned artifact union required')
        require(fixture['runtime_hashes'](Path(config['maven']), Path(config['jdk'])) == runtime_hashes, 'preparation runtime changed')
        seal = fixture['prepare'](config); seal_file = sandbox / 'execution-seal.json'
        seal_file.write_text(json.dumps(seal, indent=2) + '\n'); receipt['executionSealSha256'] = digest(seal_file)
        fixture['execute'](seal)
        require(digest(seal_file) == receipt['executionSealSha256'], 'execution seal changed')
        receipt.update(status='PASSED', resolutionCommandsRun=len(commands.results), existingArtifactHashesUnchanged=True)
    except BaseException as failure:
        receipt.update(status='FAILED', error=str(failure)); raise
    finally:
        (sandbox / 'portable-result.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print('ASSEMBLY_PLEXUS_PORTABLE_RESULT ' + json.dumps({'status': receipt['status'], 'path': str(sandbox / 'portable-result.json')}), flush=True)


if __name__ == '__main__': run()
