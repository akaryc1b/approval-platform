#!/usr/bin/env python3
"""Paired actual current-Boot/retained-Guava effective models; plan by default."""
import argparse
import json
import os
from pathlib import Path
from runpy import run_path
import shutil
import signal
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from checkstyle_live_support import (ROOT, HERE, Commands, MODEL_GOAL, digest, inside,
                                    isolated_env, maven_command, prepare_repository, require)
from run_boot_http_compatibility import install_deadline, ignore_terminal_signals, restore_signals
from run_boot_live_compatibility import hashes, tree_files, python_origins, runtime_files

BOOT = run_path(str(ROOT / 'scripts/ci/boot_http_contract.py'))
UTILITIES = run_path(str(ROOT / 'scripts/ci/boot_utilities_contract.py'))
TOTAL_SECONDS = 660


class ModelRun:
    def __init__(self, source, sandbox, repository, jdk, maven, offline, profile='http'):
        require(profile in ('http', 'utilities'), 'Named Boot model profile required')
        self.profile = profile
        self.contract = BOOT if profile == 'http' else UTILITIES
        self.utilities_source_projection = None
        if profile == 'http' and UTILITIES['is_utilities_source'](source):
            self.utilities_source_projection = UTILITIES['materialize_retained_source'](source, sandbox / 'accepted-http-source')
            source = Path(self.utilities_source_projection['retainedSourceRoot'])
        self.source, self.sandbox, self.repository = source, sandbox, repository
        self.jdk, self.maven, self.offline = jdk, maven, offline
        self.source_hashes = self.contract['source_inventory'](source)
        self.projection = self.contract['materialize_retained_source'](source,
            sandbox / ('accepted-guava' if profile == 'http' else 'accepted-http'))
        self.retained = Path(self.projection['retainedSourceRoot'])
        for name in ('home', 'tmp'):
            inside(sandbox / name, sandbox).mkdir()
        self.fixed = {
            sandbox / 'empty-settings.xml': '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n',
            sandbox / 'empty-toolchains.xml': '<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0"/>\n',
        }
        for file, text in self.fixed.items():
            file.write_text(text)
        self.inputs = hashes([Path(__file__).resolve(), HERE / 'checkstyle_live_support.py',
            HERE / 'run_boot_live_compatibility.py', HERE / 'run_boot_http_compatibility.py',
            HERE / 'run_boot_goal_compatibility.py', ROOT / 'scripts/ci/boot_http_contract.py',
            ROOT / 'scripts/ci/maven-toolchain.mjs', ROOT / 'scripts/ci/boot_utilities_contract.py',
            ROOT / 'scripts/tests/boot-http-model-live-compatibility.test.mjs',
            ROOT / 'scripts/tests/boot-utilities-model-live-compatibility.test.mjs'])
        self.python_origins, imported = python_origins()
        self.runtime_hashes = hashes([*runtime_files(jdk, maven, imported), Path('/usr/bin/timeout')])
        self.cache_before = self.cache_inventory()
        self.cache_previous = self.cache_before
        self.cache_snapshots = []
        environment = isolated_env(jdk, sandbox)
        environment.update(LC_ALL='C.UTF-8', TZ='UTC')
        self.commands = Commands(sandbox, environment, TOTAL_SECONDS)
        self.report = {'schemaVersion': 'BOOT_HTTP_ACTUAL_MODELS_V1' if profile == 'http' else 'BOOT_UTILITIES_ACTUAL_MODELS_V1',
            'dependencyProfile': profile, 'status': 'RUNNING',
            'historicalUtilitiesSourceProjection': self.utilities_source_projection,
            'sandbox': str(sandbox), 'offline': offline, 'sourcePomCount': 28,
            'sourceHashes': self.source_hashes, 'bootSourceProjection': self.projection,
            'executionInputHashes': self.inputs, 'runtimeHashes': self.runtime_hashes,
            'pythonOrigins': self.python_origins, 'invokedPython': str(Path(sys.executable).resolve()),
            'cacheInputsBefore': self.write_cache_manifest('before', self.cache_before), 'cacheSnapshots': self.cache_snapshots,
            'commands': self.commands.results,
            'actualModelSourceRoots': {'baseline': str(self.retained), 'candidate': str(source)},
            'canonicalCiEvidence': False, 'scannerEvidence': False, 'transportCompatibilityClaimed': False,
            'aggregateTimeoutSeconds': TOTAL_SECONDS, 'modelCommandTimeoutSeconds': 300, 'cleanupGraceSeconds': 15,
            'scope': ('Exactly two native help:effective-pom invocations over all26 default-profile reactor projects. '
                      + ('Retained Boot HTTP and Guava sources are paired' if profile == 'http'
                         else 'Current Boot utility source and exact retained HTTP predecessor are paired')
                      + '; overlays remain source-bound only.')}

    def write_cache_manifest(self, label, data):
        file = self.sandbox / ('cache-' + label + '.json')
        require(not file.exists(), 'Fresh model cache witness required')
        file.write_text(json.dumps(data, sort_keys=True, indent=2) + '\n')
        return {'path': str(file), 'sha256': digest(file), 'jarPomCount': len(data)}

    def cache_inventory(self):
        files = tree_files(self.repository, {'.jar', '.pom'})
        require(all(file.resolve().is_relative_to(self.repository) for file in files), 'Escaped model cache input')
        return hashes(files)

    def witness_cache(self, label):
        current = self.cache_inventory()
        require(all(current.get(file) == expected for file, expected in self.cache_previous.items()),
                'An existing cached model JAR/POM changed or disappeared')
        additions = sorted(set(current) - set(self.cache_previous))
        require(not self.offline or not additions, 'Offline model cache membership changed')
        self.cache_snapshots.append({'afterCommand': label, 'manifest': self.write_cache_manifest(label, current),
                                     'added': additions, 'priorBytesUnchanged': True})
        self.cache_previous = current

    def containment(self):
        if self.utilities_source_projection:
            UTILITIES['verify_projection'](self.utilities_source_projection)
        self.contract['verify_projection'](self.projection)
        require(self.contract['source_inventory'](self.source) == self.source_hashes, 'Selected Boot model source changed')
        for inputs in (self.inputs, self.runtime_hashes):
            require(all(Path(file).is_file() and digest(Path(file)) == expected for file, expected in inputs.items()),
                    'Model execution source/runtime input changed')
        require(all(not file.is_symlink() and file.read_text() == text for file, text in self.fixed.items()), 'Model settings changed')
        count = 0
        for directory, folders, files in os.walk(self.sandbox, followlinks=False):
            require(len(Path(directory).relative_to(self.sandbox).parts) <= 16, 'Model sandbox depth exceeded')
            for name in folders + files:
                count += 1
                require(count <= 4096 and name != '.mvn', 'Model sandbox membership limit')
                file = Path(directory) / name
                inside(file, self.sandbox)
                if file.is_file():
                    require(file.stat().st_size <= 16 * 1024 * 1024, 'Model sandbox file size exceeded')

    def run(self, label, command, seconds):
        self.containment()
        extra = {'MAVEN_BASEDIR': str(self.sandbox),
                 'MAVEN_OPTS': '-Xmx384m -Duser.home=' + str(self.sandbox / 'home')
                              + ' -Djava.io.tmpdir=' + str(self.sandbox / 'tmp')}
        status, stdout, stderr = self.commands.run(label, command, seconds, extra)
        require(status == 0, 'Actual Boot model command failed: ' + label)
        self.containment()
        self.witness_cache(label)
        return stdout, stderr

    def execute(self):
        stdout, stderr = self.run('boot-model-maven-version', [str(self.maven), '--version'], 30)
        require('Apache Maven 3.9.16' in stdout + stderr and 'Java version: 21.' in stdout + stderr,
                'Actual Maven3.9.16/JDK21 required')
        self.report['toolchain'] = {'maven': '3.9.16', 'javaMajor': 21}
        for label, root in [('baseline', self.retained), ('candidate', self.source)]:
            output = self.sandbox / (label + '-effective-pom.xml')
            command = maven_command(self.maven, self.sandbox, self.repository, self.offline,
                                    'models', root / 'pom.xml', model_output=output)
            # Explicit empty user/global toolchains complement the accepted helper's empty settings.
            command[1:1] = ['--strict-checksums', '-t', str(self.sandbox / 'empty-toolchains.xml'),
                            '-gt', str(self.sandbox / 'empty-toolchains.xml')]
            self.run('boot-' + label + '-effective-models', command, 300)
            require(output.is_file() and output.stat().st_size <= 16 * 1024 * 1024, 'Bounded actual model output required')
        comparison = self.contract['verify_effective'](self.source, self.sandbox / 'candidate-effective-pom.xml',
                                             self.sandbox / 'baseline-effective-pom.xml', self.retained)
        self.containment()
        origins_after, imported_after = python_origins()
        runtime_after = hashes([*runtime_files(self.jdk, self.maven, imported_after), Path('/usr/bin/timeout')])
        require(runtime_after == self.runtime_hashes, 'Runtime input membership changed')
        self.report.update(pythonOriginsAfter=origins_after, runtimeInputsStable=True,
            cacheExistingJarPomBytesStable=True, cacheInputsAfter=self.write_cache_manifest('after', self.cache_previous),
            cacheAddedJarPomPaths=sorted(set(self.cache_previous) - set(self.cache_before)))
        self.report.update(status='PASSED', actualDefaultProfileModelEvidence=True,
            modelCount=comparison['modelCount'], managedBootModelCount=comparison['managedBootModelCount'],
            activeBootModelCount=comparison['activeBootModelCount'], modelChanges=comparison['models'],
            allOtherModelFieldsUnchanged=comparison['allOtherModelFieldsUnchanged'],
            effectiveModelSha256=digest(self.sandbox / 'candidate-effective-pom.xml'),
            baselineEffectiveModelSha256=digest(self.sandbox / 'baseline-effective-pom.xml'),
            sourceInputsStable=True, executionInputsStable=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--profile', choices=('http', 'utilities'), default='http')
    parser.add_argument('--source', type=Path, default=ROOT)
    parser.add_argument('--repository', type=Path)
    parser.add_argument('--evidence-directory', type=Path)
    args = parser.parse_args()
    if not args.execute:
        print(json.dumps({'execution': 'NOT_RUN', 'dependencyProfile': args.profile, 'goal': MODEL_GOAL, 'actualModelCommands': 2,
            'modelCountPerCommand': 26, 'aggregateTimeoutSeconds': TOTAL_SECONDS,
            'modelCommandTimeoutSeconds': 300, 'outerTimeoutSeconds': 675, 'cleanupGraceSeconds': 15, 'localAlwaysOffline': True,
            'explicitOptInRequired': True, 'lifecycle': False, 'scannerEvidence': False}))
        return 0
    opt_in = 'BOOT_HTTP_MODEL_COMPATIBILITY' if args.profile == 'http' else 'BOOT_UTILITIES_MODEL_COMPATIBILITY'
    require(os.environ.get(opt_in) == 'true', 'Explicit reviewed model opt-in required')
    ci = os.environ.get('GITHUB_ACTIONS') == 'true'
    require(ci or args.repository is not None, 'Local model execution requires explicit offline cache')
    require(os.name == 'posix', 'POSIX model process-group control required')
    source = args.source.resolve(); repository = (args.repository or Path.home() / '.m2/repository').resolve()
    prepare_repository(repository, source, not ci)
    jdk_value = os.environ.get('JAVA_HOME', '')
    require(jdk_value and Path(jdk_value).is_absolute(), 'Explicit absolute JAVA_HOME required')
    jdk = Path(jdk_value).resolve(); executable = shutil.which('mvn')
    require(executable and (jdk / 'bin/java').is_file() and (jdk / 'lib/modules').is_file(), 'Maven/JDK runtime required')
    base = args.evidence_directory.resolve() if args.evidence_directory else Path('/tmp')
    require(base.is_dir() and not base.is_relative_to(source) and not base.is_relative_to(repository), 'External evidence directory required')
    sandbox = Path(tempfile.mkdtemp(prefix='boot-http-models-', dir=base)).resolve()
    require(not any(c.isspace() or ord(c) < 32 for c in str(sandbox)), 'Sandbox option separators forbidden')
    previous_handlers = install_deadline(TOTAL_SECONDS)
    run = None
    try:
        run = ModelRun(source, sandbox, repository, jdk, Path(executable).resolve(), not ci, args.profile)
        run.execute()
        receipt = run.report
    except BaseException as exc:
        receipt = run.report if run else {'schemaVersion': 'BOOT_HTTP_ACTUAL_MODELS_V1' if args.profile == 'http' else 'BOOT_UTILITIES_ACTUAL_MODELS_V1',
                                         'dependencyProfile': args.profile, 'sandbox': str(sandbox)}
        receipt.update(status='FAILED', errorType=type(exc).__name__, error=str(exc), canonicalCiEvidence=False)
    finally:
        ignore_terminal_signals(); signal.alarm(0)
    try:
        receipt['evidenceRetained'] = True
        inside(sandbox / 'result.json', sandbox).write_text(json.dumps(receipt, indent=2) + '\n')
        marker = 'BOOT_HTTP_MODELS_RESULT ' if args.profile == 'http' else 'BOOT_UTILITIES_MODELS_RESULT '
        print(marker + json.dumps(receipt), flush=True)
        return 0 if receipt['status'] == 'PASSED' else 1
    finally:
        restore_signals(previous_handlers)


if __name__ == '__main__':
    sys.exit(main())
