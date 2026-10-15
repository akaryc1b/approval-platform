#!/usr/bin/env python3
"""Portable adapter. Import/plan never starts Maven, Java or sockets."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import sys
import sysconfig
import tempfile
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
from checkstyle_live_support import Commands, CENTRAL_SETTINGS, NS, P, cached_files, digest, isolated_env, prepare_repository, require
import run_boot_http_compatibility as http
import run_boot_goal_compatibility as goals
import run_boot_utilities_compatibility as utilities

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PINS = HERE / 'boot-http-artifact-pins.json'
UTILITY_PINS = HERE / 'boot-utilities-artifact-pins.json'
RESOLVE = 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins'
SETTINGS = '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n'
PLAN = {'execution': 'NOT_RUN', 'explicitOptIn': 'BOOT_HTTP_COMPATIBILITY=true',
        'localAlwaysOffline': True, 'ciCentralPreparationOnly': True,
        'prepareCommandsMaximum': 2, 'freshOfflineOwnerReports': 2,
        'artifactUnionCount': 42, 'orderedArtifactsPerVariant': 39,
        'requiredStages': ['HTTP/TLS/Unix and complete member linkage and unchanged Jackson', 'real help/repackage'],
        'aggregateTimeoutSeconds': 1200, 'outerTimeoutSeconds': 1230,
        'commandTimeoutSeconds': {'prepare': 180, 'owner': 120, 'driver': 270},
        'unixFailureIsFatal': True, 'scannerEvidence': False}


def profile_plan(profile='http'):
    require(profile in ('http', 'utilities'), 'fixed Boot compatibility profile required')
    if profile == 'http':
        return PLAN
    return dict(PLAN, explicitOptIn='BOOT_HTTP_COMPATIBILITY=true and BOOT_UTILITIES_COMPATIBILITY=true',
                profile='utilities', artifactUnionCount=41,
                requiredStages=[*PLAN['requiredStages'], 'actual Boot utilities consumers and 31-member linkage'])


def read_pins(path=None, profile='http'):
    require(profile in ('http', 'utilities'), 'fixed Boot compatibility profile required')
    data = json.loads((path or (UTILITY_PINS if profile == 'utilities' else PINS)).read_text())
    schema = 'BOOT_UTILITIES_PORTABLE_PINS_V1' if profile == 'utilities' else 'BOOT_HTTP_PORTABLE_PINS_V1'
    require(data['schemaVersion'] == schema and data['owner'] == http.OWNER, 'portable pins required')
    hashes = data['artifactHashes']
    require(set(data['variants']) == {'baseline', 'candidate'}, 'exact pin variants required')
    require(len(hashes) == (41 if profile == 'utilities' else 42) and all(re.fullmatch('[0-9a-f]{64}', value) for value in hashes.values()), 'exact valid artifact pins required')
    require(all(re.fullmatch(r'[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:jar:[A-Za-z0-9_.-]+', key) for key in hashes), 'plain portable JAR coordinates required')
    for variant in ('baseline', 'candidate'):
        coordinates = data['variants'][variant]
        require(len(coordinates) == len(set(coordinates)) == 39 and set(coordinates) <= hashes.keys(), '39 ordered distinct coordinates required')
    require(set(sum(data['variants'].values(), [])) == set(hashes), 'pin union differs')
    goals.compare_realms(data['variants']['baseline'], data['variants']['candidate'], profile)
    return data


def project_text(variant, profile='http'):
    project = ET.fromstring(goals.fixture_pom(variant, profile))
    # A POM project avoids resolving default compile/resource/test plugins.
    project.find('m:packaging', NS).text = 'pom'
    plugins = project.find('m:build/m:plugins', NS)
    jar = ET.SubElement(plugins, P + 'plugin')
    for name, value in [('groupId', 'org.apache.maven.plugins'), ('artifactId', 'maven-jar-plugin'), ('version', '3.5.0')]:
        ET.SubElement(jar, P + name).text = value
    return ET.tostring(project, encoding='unicode', default_namespace=NS['m']) + '\n'


def owner_coordinates(report, expected=None):
    require(report.is_file() and not report.is_symlink() and report.stat().st_size < 262144, 'fresh bounded owner report required')
    lines = report.read_text().splitlines()
    starts = [index for index, line in enumerate(lines) if line == '   ' + http.OWNER]
    require(len(starts) == 1, 'one actual resolved Boot owner required')
    coordinates = []
    for line in lines[starts[0] + 1:]:
        if not line.startswith('      '):
            break
        match = re.fullmatch(r'      ([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:jar:[A-Za-z0-9_.-]+)', line)
        require(match is not None, 'unexpected resolved Boot artifact format')
        coordinates.append(match[1])
    require(len(coordinates) == len(set(coordinates)) == 39, 'complete distinct actual owner inventory required')
    if expected is not None:
        require(coordinates == expected, 'actual ordered owner inventory differs from reviewed pins')
    return coordinates


def resolve_command(maven, sandbox, repository, variant, report, prepare, ci=False):
    require(variant in ('baseline', 'candidate') and (not prepare or ci), 'known variant and CI-only preparation required')
    require(report.parent == sandbox and not report.exists(), 'fresh contained owner report required')
    settings = sandbox / ('central-settings.xml' if prepare else 'empty-settings.xml')
    return [str(maven), *([] if prepare else ['-o']), '-B', '-ntp', '-C', '-N',
        '-s', str(settings), '-gs', str(settings), '-t', str(sandbox / 'empty-toolchains.xml'),
        '-gt', str(sandbox / 'empty-toolchains.xml'), '-Dmaven.repo.local=' + str(repository),
        '-Dmaven.multiModuleProjectDirectory=' + str(sandbox), '-Dstyle.color=never',
        '-f', str(sandbox / (variant + '-pom.xml')), RESOLVE,
        '-DincludeArtifactIds=spring-boot-maven-plugin' + (',maven-jar-plugin' if prepare else ''),
        '-DexcludeTransitive=false', '-DappendOutput=false', '-DoutputEncoding=UTF-8', '-DoutputFile=' + str(report)]


def bounded_deadline(deadline, installer, seconds):
    remaining = int(deadline - time.monotonic())
    require(remaining > 0, 'adapter aggregate deadline exhausted')
    return installer(min(seconds, remaining))


def hashes(paths):
    result = {}
    for file in sorted(set(paths)):
        require(file.is_file(), 'seal input absent: ' + str(file))
        result[str(file)] = digest(file)
    return result


def tree_files(root, suffixes=None):
    result = []
    for base, directories, files in os.walk(root, followlinks=False):
        directories[:] = [name for name in directories if name not in ('site-packages', 'dist-packages')]
        for name in files:
            file = Path(base) / name
            if suffixes is None or file.suffix in suffixes:
                require(file.is_file(), 'runtime/input link target absent')
                result.append(file)
                require(len(result) <= 12000, 'bounded runtime/input tree required')
    return result


def python_origins():
    rows, files = [], []
    for name, module in sorted(sys.modules.items()):
        if module is None:
            continue
        origin = getattr(getattr(module, '__spec__', None), 'origin', None)
        row = {'module': name, 'origin': origin}
        for attribute in ('__file__', '__cached__'):
            value = getattr(module, attribute, None)
            row[attribute] = value
            if value and Path(value).is_file():
                files.append(Path(value).resolve())
        rows.append(row)
    return rows, files


def runtime_files(jdk, maven, imported_files):
    # Local byte seals are runtime witnesses, not universal JDK/Python trust pins.
    files = [Path(sys.executable).resolve(), Path('/usr/bin/openssl'), Path('/bin/sh').resolve(),
             Path('/usr/bin/uname'), Path('/usr/bin/dirname'),
             jdk / 'bin/java', jdk / 'bin/javac', jdk / 'release']
    files += tree_files(jdk / 'lib') + tree_files(jdk / 'conf')
    files += tree_files(maven.parent.parent)
    files += tree_files(Path(sysconfig.get_path('stdlib')), {'.py', '.pyc', '.so'})
    files += imported_files
    for line in Path('/proc/self/maps').read_text().splitlines():
        fields = line.split(maxsplit=5)
        if len(fields) == 6 and fields[5].startswith('/'):
            files.append(Path(fields[5]))
    return files


def cache_snapshot(repository):
    files = tree_files(repository, {'.jar', '.pom'})
    require(all(file.resolve().is_relative_to(repository) for file in files), 'escaped cache input')
    return hashes(files)


def execute(args):
    require(os.environ.get('BOOT_HTTP_COMPATIBILITY') == 'true', 'explicit reviewed execution opt-in required')
    deadline = time.monotonic() + 1200
    previous = http.install_deadline(1200)
    try:
        return execute_bounded(args, deadline)
    finally:
        http.ignore_terminal_signals(); signal.alarm(0)
        http.restore_signals(previous)


def execute_bounded(args, deadline):
    require(sys.platform == 'linux', 'full Linux HTTP/TLS/Unix fixtures required')
    profile = getattr(args, 'profile', 'http')
    require(profile in ('http', 'utilities'), 'fixed Boot compatibility profile required')
    if profile == 'utilities':
        require(os.environ.get('BOOT_UTILITIES_COMPATIBILITY') == 'true', 'explicit utility profile opt-in required')
    ci = os.environ.get('GITHUB_ACTIONS') == 'true'
    require(ci or args.repository is not None, 'local execution requires explicit existing repository')
    repository = (args.repository or Path.home() / '.m2/repository').resolve()
    prepare_repository(repository, ROOT, not ci)
    jdk_value = os.environ.get('JAVA_HOME', '')
    require(jdk_value and Path(jdk_value).is_absolute(), 'explicit absolute JAVA_HOME required')
    jdk = Path(jdk_value).resolve()
    executable = shutil.which('mvn'); require(executable is not None, 'verified Maven required')
    maven = Path(executable).resolve()
    base = (args.evidence_directory or Path('/tmp')).resolve()
    require(base.is_dir() and not base.is_relative_to(ROOT) and not base.is_relative_to(repository), 'external evidence directory required')
    sandbox = Path(tempfile.mkdtemp(prefix='boot-live-', dir=base))
    require(not any(c.isspace() or ord(c) < 32 for c in str(sandbox)), 'JVM-option-safe sandbox path required')
    for name in ('home', 'tmp'):
        (sandbox / name).mkdir()
    fixed = {}
    def put(file, text):
        require(not file.exists(), 'fresh generated input required')
        file.write_text(text); fixed[str(file)] = digest(file)
    put(sandbox / 'central-settings.xml', CENTRAL_SETTINGS)
    put(sandbox / 'empty-settings.xml', SETTINGS)
    put(sandbox / 'empty-toolchains.xml', '<toolchains/>\n')
    for variant in ('baseline', 'candidate'):
        put(sandbox / (variant + '-pom.xml'), project_text(variant, profile))
    source_inventory = goals.source_inputs(ROOT)[2]
    source_hashes = hashes([*http.SOURCES, *utilities.sources(), HERE / 'run_boot_goal_compatibility.py', Path(__file__).resolve(), PINS,
                            UTILITY_PINS, ROOT / 'scripts/ci/boot_utilities_contract.py', ROOT / goals.utilities_contract.WITNESS,
                            ROOT / 'scripts/ci/jacoco_antrun_contract.py', goals.JAR_SOURCE_ADAPTER,
                            *(ROOT / name for name in source_inventory),
                            ROOT / 'pom.xml', ROOT / 'scripts/tests/boot-http-plugin-live-compatibility.test.mjs',
                            ROOT / 'scripts/tests/boot-http-compatibility-harness.test.mjs',
                            ROOT / 'scripts/tests/boot-utilities-plugin-live-compatibility.test.mjs',
                            ROOT / 'scripts/tests/boot-utilities-compatibility-harness.test.mjs'])
    origins, imported_files = python_origins()
    runtime_hashes = hashes(runtime_files(jdk, maven, imported_files))
    put(sandbox / 'python-origins.json', json.dumps(origins, indent=2) + '\n')
    environment = isolated_env(jdk, sandbox)
    environment.update(MAVEN_BASEDIR=str(sandbox), OPENSSL_CONF='/dev/null',
        MAVEN_OPTS='-Xmx384m -XX:MaxMetaspaceSize=192m -Duser.home=' + str(sandbox / 'home')
                   + ' -Djava.io.tmpdir=' + str(sandbox / 'tmp'))
    commands = Commands(sandbox, environment, 1200)
    receipt = {'status': 'RUNNING', 'profile': profile, 'sandbox': str(sandbox), 'commands': commands.results,
        'cachePreparation': {'mode': 'ci-central-plugin-resolution' if ci else 'offline-existing-cache',
                             'status': 'RUNNING', 'acceptanceGoal': False, 'resolutionCommandsRun': 0},
        'sourceHashes': source_hashes, 'runtimeHashes': runtime_hashes, 'pythonOrigins': origins,
        'invokedPython': str(Path(sys.executable).resolve()), 'ownerReports': {}, 'stages': {},
        'coverageGaps': [], 'scannerEvidence': False, 'canonicalCiEvidence': False}
    cache_seal = cache_snapshot(repository)
    cache_additions = ci
    receipt['cacheBeforePreparation'] = dict(cache_seal)
    def unchanged():
        nonlocal cache_seal
        require(all(not (ancestor / '.mvn').exists() for ancestor in [sandbox, *sandbox.parents]), 'ambient Maven bootstrap config')
        for values in (source_hashes, runtime_hashes, fixed):
            require(all(Path(file).is_file() and digest(Path(file)) == value for file, value in values.items()), 'sealed inputs changed')
        require(goals.source_inputs(ROOT)[2] == source_inventory, 'Boot original source continuation changed')
        current = cache_snapshot(repository)
        require(all(current.get(file) == value for file, value in cache_seal.items()), 'existing cache JAR/POM changed or disappeared')
        require(cache_additions or current == cache_seal, 'frozen cache JAR/POM membership changed')
        cache_seal = current
    def run(label, command, seconds, extra_env=None):
        unchanged()
        status, out, err = commands.run(label, command, seconds, extra_env)
        require(status == 0, 'command failed: ' + label)
        unchanged()
        return out, err
    remaining = int(deadline - time.monotonic())
    require(remaining > 0, 'adapter preflight exceeded aggregate deadline')
    previous = http.install_deadline(remaining)
    original_deadlines = (http.install_deadline, goals.install_deadline)
    def stage_deadline(seconds):
        return bounded_deadline(deadline, original_deadlines[0], seconds)
    try:
        pins = read_pins(profile=profile)
        # Fail on present-but-wrong bytes before any network preparation.
        cached_files(repository, pins['artifactHashes'], allow_missing=ci)
        version = ''.join(run('maven-version', [str(maven), '--version'], 15))
        require('Apache Maven 3.9.16' in version and 'Java version: 21.' in version, 'pinned Maven/JDK required')
        if ci:
            for variant in ('baseline', 'candidate'):
                run('prepare-' + variant, resolve_command(maven, sandbox, repository, variant,
                    sandbox / ('prepare-' + variant + '.txt'), True, ci=ci), 180)
                receipt['cachePreparation']['resolutionCommandsRun'] += 1
        cached = cached_files(repository, pins['artifactHashes'], allow_missing=False)
        receipt['cachePreparation'].update(status='VERIFIED', artifacts=cached)
        unchanged()
        cache_additions = False
        receipt['cacheBeforeAcceptance'] = dict(cache_seal)
        for variant in ('baseline', 'candidate'):
            report = sandbox / (variant + '-owner.txt')
            require(not report.exists(), 'owner report must be generated fresh')
            run('owners-' + variant, resolve_command(maven, sandbox, repository, variant, report, False), 120)
            observed = owner_coordinates(report, pins['variants'][variant])
            fixed[str(report)] = digest(report)
            manifest = {'schemaVersion': 'BOOT_UTILITIES_RESOLVED_REALM_V1' if profile == 'utilities' else 'BOOT_HTTP_RESOLVED_REALM_V1', 'owner': http.OWNER,
                'sourceOwnerReport': {'path': str(report), 'sha256': digest(report)},
                'artifacts': [{'coordinate': item, **cached[item]} for item in observed], 'importedArtifacts': []}
            put(sandbox / (variant + '-realm.json'), json.dumps(manifest, indent=2) + '\n')
            receipt['ownerReports'][variant] = {'sha256': digest(report), 'orderedCoordinates': observed,
                                               'freshOfflineCapture': True, 'path': str(report)}
        # Bind every potentially loaded cache JAR/POM after preparation. This also
        # covers Dependency/JAR-plugin tooling without inventing Boot-realm pins.
        input_hashes = fixed | cache_seal
        stages = [('transport', http), ('goals', goals)]
        if profile == 'utilities':
            stages.append(('utilities', utilities))
        for stage, driver in stages:
            seal = {'schemaVersion': 'BOOT_HTTP_EXECUTION_SEAL_V1', 'sourceHashes': source_hashes,
                'runtimeHashes': runtime_hashes, 'inputHashes': input_hashes,
                'config': {'jdk': str(jdk), 'maven': str(maven), 'repository': str(repository), 'profile': profile,
                    'output': str(sandbox / stage),
                    **{variant + 'Realm': str(sandbox / (variant + '-realm.json')) for variant in ('baseline', 'candidate')}}}
            seal_file = sandbox / (stage + '-seal.json')
            put(seal_file, json.dumps(seal, indent=2) + '\n')
            # Run in this interpreter: a second Commands wrapper would leave
            # grandchild Java process groups outside its kill/reap ownership.
            unchanged()
            http.install_deadline = goals.install_deadline = stage_deadline
            try:
                driver.execute(seal)
            finally:
                http.install_deadline, goals.install_deadline = original_deadlines
                signal.alarm(max(1, int(deadline - time.monotonic())))
            unchanged()
            result = json.loads((sandbox / stage / 'result.json').read_text())
            require(result['status'] == 'PASSED', 'driver did not pass')
            if stage == 'transport':
                require(result['linkageOnly'] is False, 'diagnostic linkage-only receipt cannot satisfy CI')
            receipt['stages'][stage] = result
        receipt['status'] = 'PASSED'
    except BaseException as error:
        receipt.update(status='FAILED', errorType=type(error).__name__, error=str(error))
        raise
    finally:
        http.ignore_terminal_signals(); signal.alarm(0)
        try:
            (sandbox / 'result.json').write_text(json.dumps(receipt, indent=2) + '\n')
            print('BOOT_LIVE_RESULT ' + json.dumps(receipt), flush=True)
        finally:
            http.restore_signals(previous)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--repository', type=Path)
    parser.add_argument('--evidence-directory', type=Path)
    parser.add_argument('--profile', choices=('http', 'utilities'), default='http')
    args = parser.parse_args()
    if args.execute:
        execute(args)
    else:
        print(json.dumps(profile_plan(args.profile), indent=2))
