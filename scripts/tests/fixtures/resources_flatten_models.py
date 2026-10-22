#!/usr/bin/env python3
"""Plan or supplied-model verification by default; five JVM commands only by opt-in."""
import argparse
import base64
import gzip
import hashlib
import importlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
from runpy import run_path
import shutil
import signal
import sys
import tempfile
import time
import zlib

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CONTRACT_PATH = ROOT / 'scripts/ci/resources_flatten_contract.py'
EVIDENCE = 'docs/operations/resources-flatten-evidence'
GOAL = 'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom'
LABELS = ('baseline-normal', 'candidate-normal', 'baseline-demo', 'candidate-demo')
MODEL_NAMES = {label: label + '-effective-pom.xml' for label in LABELS}
TOTAL_SECONDS = 900
OUTER_SECONDS = 930
RAW_LIMIT = 16 * 1024 * 1024
RAW_TOTAL_LIMIT = 64 * 1024 * 1024
GZIP_LIMIT = 1024 * 1024
GZIP_TOTAL_LIMIT = 4 * 1024 * 1024
RECEIPT_LIMIT = 8 * 1024 * 1024
RESULT_MARKER = 'RESOURCES_FLATTEN_MODELS_RESULT'
SHARED_MODULES = {
    'checkstyle_live_support': 'scripts/tests/fixtures/checkstyle_live_support.py',
    'jar_plugin_storage': 'scripts/tests/fixtures/jar_plugin_storage.py',
    'run_boot_live_compatibility': 'scripts/tests/fixtures/run_boot_live_compatibility.py',
    'run_boot_http_compatibility': 'scripts/tests/fixtures/run_boot_http_compatibility.py',
    'run_boot_goal_compatibility': 'scripts/tests/fixtures/run_boot_goal_compatibility.py',
    'run_boot_utilities_compatibility': 'scripts/tests/fixtures/run_boot_utilities_compatibility.py',
    'boot_utilities_contract': 'scripts/ci/boot_utilities_contract.py',
    'jacoco_antrun_contract': 'scripts/ci/jacoco_antrun_contract.py'}
CAPTURE_SHA = '28229842906bf622c7cd76b9e8cbfbf592e1fc025625eabb60ed0a21b319348a'
COLLECTOR_SHA = '7fe48c76536050e6666db8f70fc9f222cabd3b31dbbd9641b8bd431bd1acb72e'
ARCHIVED_MODELS = {
    'baseline-normal': '82876bc92cce778558d6f06f889a99f2bd0653ab54dd2f782c5dd13f04d88b7d',
    'candidate-normal': 'a0c904475feb6dd14f6a8818ebb6972f50ab7d04fe03d1dfb33d29a614161eba',
    'baseline-demo': '3363ab20cad945472e88b610bc52245b2477e7f270780b209669dab16deb75e8',
    'candidate-demo': 'f8ac3e5e3c42d189f175bfce4b73c7f3ab43d98e4af62156e65706fbdda308e3'}
PLAN = {'execution': 'NOT_RUN', 'actualModelCommands': 4, 'versionCommands': 1, 'totalCommands': 5,
        'modelCountPerCommand': 26, 'profiles': ['normal', 'demo'], 'commandOrder': list(LABELS),
        'goal': GOAL, 'explicitOptIn': 'RESOURCES_FLATTEN_MODEL_COMPATIBILITY=true',
        'explicitOptInRequired': True, 'localAlwaysOffline': True,
        'aggregateTimeoutSeconds': TOTAL_SECONDS, 'modelCommandTimeoutSeconds': 300,
        'versionCommandTimeoutSeconds': 30, 'outerTimeoutSeconds': OUTER_SECONDS, 'cleanupGraceSeconds': 15,
        'sharedDeadlineIncludesPreparationAndVersion': True,
        'outputTreeMaximumBytes': 128 * 1024 * 1024, 'outputTreeMaximumEntries': 4096,
        'rawModelMaximumBytes': RAW_LIMIT, 'rawModelsMaximumBytes': RAW_TOTAL_LIMIT,
        'gzipModelMaximumBytes': GZIP_LIMIT, 'gzipModelsMaximumBytes': GZIP_TOTAL_LIMIT,
        'receiptMaximumBytes': RECEIPT_LIMIT, 'retainedModels': list(MODEL_NAMES.values()),
        'lifecycle': False, 'scannerEvidence': False, 'canonicalCiEvidence': False}


def require(ok, message):
    if not ok:
        raise ValueError('Resources/Flatten models: ' + message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def raw_file(path, maximum):
    path = Path(path).absolute()
    require(path.is_file() and not path.is_symlink() and path.resolve() == path
            and path.stat().st_size <= maximum, 'bounded real input: ' + str(path))
    with path.open('rb') as stream:
        raw = stream.read(maximum + 1)
    require(len(raw) <= maximum, 'input grew beyond bound')
    return raw


def contract():
    raw = raw_file(CONTRACT_PATH, 2 * 1024 * 1024)
    namespace = {'__file__': str(CONTRACT_PATH), '__name__': 'resources_flatten_contract'}
    exec(compile(raw, str(CONTRACT_PATH), 'exec'), namespace)
    require(raw_file(CONTRACT_PATH, 2 * 1024 * 1024) == raw, 'contract changed while loading')
    namespace['loadedSourceSha256'] = sha(raw)
    return namespace


def model_paths(directory):
    directory = Path(directory).absolute()
    return {label: directory / name for label, name in MODEL_NAMES.items()}


def checked_json(path, maximum=RECEIPT_LIMIT):
    return contract()['strict_json'](raw_file(path, maximum))


def verify_archived_capture(evidence_root, pair, paths):
    """Read pinned technical artifacts only. Never open paths from an old receipt."""
    c = contract(); evidence_root = c['real_root'](evidence_root)
    require(set(paths) == set(LABELS), 'four supplied output paths required')
    capture_raw = raw_file(evidence_root / 'model-capture-execution.json', 2 * 1024 * 1024)
    require(sha(capture_raw) == CAPTURE_SHA, 'archived capture bytes changed')
    receipt = c['strict_json'](capture_raw)
    require(sha(raw_file(evidence_root / 'capture.py', 128 * 1024)) == COLLECTOR_SHA
            and receipt['collectorSha256'] == COLLECTOR_SHA, 'archived collector bytes changed')
    require(receipt['schemaVersion'] == 'APPROVAL_RESOURCES_FLATTEN_CAPTURE_EXECUTION_V1'
            and receipt['status'] == 'PASSED_CAPTURE_ONLY' and receipt['sourceHead'] == c['HEAD']
            and receipt['sourceTree'] == c['TREE'] and receipt['offline'] is True,
            'archived capture identity')
    require(receipt['effectiveModelHashes'] == ARCHIVED_MODELS
            and receipt['effectiveModelCounts'] == {label: 26 for label in LABELS}, 'archived output identity')
    for key in ('captureInputsStable', 'runtimeInputsStable', 'cacheInputsStable'):
        require(receipt[key] is True, 'archived input stability record')
    for name, key in [('runtime-inputs.json', 'runtimeInputManifestSha256'),
                      ('cache-inputs.json', 'cacheInputManifestSha256')]:
        require(sha(raw_file(evidence_root / name, 2 * 1024 * 1024)) == receipt[key],
                'archived technical manifest bytes changed: ' + name)
    for variant, key in [('baseline', 'baselineCaptureInputs'), ('candidate', 'captureInputs')]:
        require(len(receipt[key]) == 58, 'archived58 input record membership')
        for name, expected in pair['sourceHashes']['baseline'].items():
            target = pair['sourceHashes'][variant][name]
            require(receipt[key].get(name) == target, 'archived/current model source differs: ' + name)
    require(receipt['sourcePomHashes'] == {name: pair['sourceHashes']['candidate'][name]
            for name in pair['sourceHashes']['baseline'] if name == 'pom.xml' or name.endswith('/pom.xml')},
            'all28 captured POMs required')
    expected_labels = ['head-before', 'tree-before', 'status-before',
                       *[label + '-models' for label in LABELS], 'head-after', 'tree-after', 'status-after']
    require([row['label'] for row in receipt['commands']] == expected_labels, 'archived command membership/order')
    roots, outputs = {}, {}
    for label in LABELS:
        row = next(row for row in receipt['commands'] if row['label'] == label + '-models')
        variant, profile = label.split('-'); argv = row['command']
        require(row['status'] == 'PASSED' and row['exitStatus'] == 0
                and row['processGroupTerminationRequested'] is True, 'actual archived model command failed')
        require(argv[:3] == [argv[0], '-B', '-ntp'] and argv[3] == '-f', 'archived model command prefix')
        project = PurePosixPath(argv[4]); model_root = str(project.parent)
        require(project.name == 'pom.xml' and row['mavenBasedir'] == model_root, 'archived model root binding')
        require(variant not in roots or roots[variant] == model_root, 'profile model roots differ')
        roots[variant] = model_root
        suffix = (['-Pproduct-readiness-demo'] if profile == 'demo' else []) + [GOAL]
        require(argv[5:-1] == suffix and argv[-1].startswith('-Doutput='), 'exact archived model goal/profile')
        output_name = PurePosixPath(argv[-1][len('-Doutput='):]).name
        require(output_name == MODEL_NAMES[label], 'archived output name binding')
        raw = raw_file(paths[label], RAW_LIMIT)
        require(sha(raw) == ARCHIVED_MODELS[label], 'archived model bytes changed: ' + label)
        outputs[label] = {'sha256': sha(raw), 'bytes': len(raw), 'name': output_name}
    roots = c['checked_model_roots'](roots)
    require(sum(row['bytes'] for row in outputs.values()) <= RAW_TOTAL_LIMIT, 'archived aggregate model bound')
    # Re-read these evidence files when the caller repeats this check after semantics.
    return {'schemaVersion': 'RESOURCES_FLATTEN_ARCHIVED_CAPTURE_BINDING_V1',
            'captureReceiptSha256': CAPTURE_SHA, 'collectorSha256': COLLECTOR_SHA,
            'modelSourceRoots': roots, 'outputs': outputs,
            'currentSourcePair': pair, 'actualCaptureReexecuted': False,
            'scannerEvidence': False, 'canonicalCiEvidence': False}


def verify_archived(source, evidence_root=None):
    c = contract(); source = c['real_root'](source)
    evidence_root = c['real_root'](evidence_root or source / EVIDENCE)
    with tempfile.TemporaryDirectory(prefix='resources-flatten-archived-') as directory:
        materialized = c['materialize_baseline'](source, Path(directory) / 'baseline')
        baseline = Path(materialized['retainedSourceRoot']); pair = materialized['sourcePair']
        paths = model_paths(evidence_root)
        bound = verify_archived_capture(evidence_root, pair, paths)
        result = c['verify_models'](baseline, source, paths, model_source_roots=bound['modelSourceRoots'])
        require(verify_archived_capture(evidence_root, c['source_pair'](baseline, source), paths) == bound,
                'archived binding changed during semantic verification')
        return {'schemaVersion': 'RESOURCES_FLATTEN_ARCHIVED_MODELS_RECHECK_V1', 'status': 'PASSED',
                'models': result, 'captureBinding': bound, 'actualCaptureReexecuted': False,
                'scannerEvidence': False, 'canonicalCiEvidence': False}


def pack_outputs(paths, expected_hashes=None, complete=True):
    require(set(paths) <= set(LABELS) and (not complete or set(paths) == set(LABELS)), 'retention model membership')
    result, raw_total, packed_total = {}, 0, 0
    for label in LABELS:
        if label not in paths:
            continue
        raw = raw_file(paths[label], RAW_LIMIT); packed = gzip.compress(raw, compresslevel=9, mtime=0)
        require(len(packed) <= GZIP_LIMIT, 'per-model compressed bound')
        raw_total += len(raw); packed_total += len(packed)
        require(raw_total <= RAW_TOTAL_LIMIT and packed_total <= GZIP_TOTAL_LIMIT, 'aggregate model retention bound')
        if expected_hashes is not None:
            require(sha(raw) == expected_hashes[label], 'retention differs from actual capture')
        variant, profile = label.split('-')
        result[label] = {'label': label, 'variant': variant, 'profile': profile,
                         'name': MODEL_NAMES[label], 'encoding': 'gzip+base64',
                         'rawBytes': len(raw), 'sha256': sha(raw), 'compressedBytes': len(packed),
                         'compressedSha256': sha(packed), 'data': base64.b64encode(packed).decode('ascii')}
    return result


def unpack_outputs(rows, expected_hashes, complete=True):
    require(isinstance(rows, dict) and set(rows) <= set(LABELS)
            and (not complete or set(rows) == set(LABELS)), 'exact retained labels required')
    require(set(expected_hashes) == set(rows), 'capture/output label binding')
    result, raw_total, packed_total = {}, 0, 0
    for label, row in rows.items():
        variant, profile = label.split('-')
        require(set(row) == {'label', 'variant', 'profile', 'name', 'encoding', 'rawBytes', 'sha256',
                            'compressedBytes', 'compressedSha256', 'data'}
                and row['label'] == label and row['variant'] == variant and row['profile'] == profile
                and row['name'] == MODEL_NAMES[label] and row['encoding'] == 'gzip+base64', 'retention descriptor')
        require(type(row['rawBytes']) is int and 0 <= row['rawBytes'] <= RAW_LIMIT
                and type(row['compressedBytes']) is int and 0 < row['compressedBytes'] <= GZIP_LIMIT,
                'retention declared byte bounds')
        require(isinstance(row['data'], str) and len(row['data']) <= 4 * ((GZIP_LIMIT + 2) // 3),
                'bounded base64 input')
        packed = base64.b64decode(row['data'], validate=True)
        require(base64.b64encode(packed).decode('ascii') == row['data']
                and len(packed) == row['compressedBytes'] and sha(packed) == row['compressedSha256'],
                'compressed bytes binding')
        packed_total += len(packed)
        require(packed_total <= GZIP_TOTAL_LIMIT, 'aggregate compressed byte bound')
        decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
        raw = decoder.decompress(packed, min(RAW_LIMIT, RAW_TOTAL_LIMIT - raw_total) + 1)
        require(decoder.eof and not decoder.unused_data and not decoder.unconsumed_tail,
                'single complete gzip member with no trailing bytes required')
        raw_total += len(raw)
        require(len(raw) <= RAW_LIMIT and raw_total <= RAW_TOTAL_LIMIT and len(raw) == row['rawBytes']
                and sha(raw) == row['sha256'] == expected_hashes[label], 'raw model/capture binding')
        result[label] = raw
    return result


def model_argv(maven, sandbox, repository, offline, model_root, profile, output):
    """Canonical argv for validating Commands records, independent of their claims."""
    return [str(maven), '--strict-checksums', '-t', str(sandbox / 'empty-toolchains.xml'),
            '-gt', str(sandbox / 'empty-toolchains.xml'), *(['-o'] if offline else []), '-B', '-ntp',
            '-s', str(sandbox / 'empty-settings.xml'), '-gs', str(sandbox / 'empty-settings.xml'),
            '-Dmaven.repo.local=' + str(repository), '-Dstyle.color=never',
            '-Dmaven.multiModuleProjectDirectory=' + str(model_root), '-f', str(model_root / 'pom.xml'),
            *(['-Pproduct-readiness-demo'] if profile == 'demo' else []), GOAL, '-Doutput=' + str(output)]


def verify_current_capture(receipt, pair, paths):
    c = contract(); roots = c['checked_model_roots'](receipt['actualModelSourceRoots'])
    require(roots == pair['sourceRoots'] and receipt['sourcePairBefore'] == receipt['sourcePairAfter'] == pair,
            'current capture source binding')
    require(set(paths) == set(LABELS) and receipt['actualModelCommandCount'] == 4
            and receipt['totalCommandCount'] == 5, 'five commands/four models required')
    require(receipt['toolchain'] == {'maven': '3.9.16', 'javaMajor': 21}, 'actual toolchain probe required')
    config = receipt['config']; sandbox = c['real_root'](receipt['sandbox'])
    maven, repository = Path(config['maven']), Path(config['repository'])
    require([row['case'] for row in receipt['commands']] == ['resources-flatten-model-maven-version',
                *[label + '-models' for label in LABELS]], 'exact current command order')
    outputs = {}
    for index, row in enumerate(receipt['commands']):
        require(row['status'] == 'EXITED' and row['exitStatus'] == 0 and row['captureComplete'] is True
                and row['processGroupCleaned'] is True and row['cwd'] == str(sandbox), 'complete current command')
        if index == 0:
            require(row['command'] == [str(maven), '--version'], 'one exact version probe')
            continue
        label = LABELS[index - 1]; variant, profile = label.split('-')
        output = sandbox / MODEL_NAMES[label]
        require(Path(paths[label]).absolute() == output and row['mavenBasedir'] == roots[variant]
                and row['command'] == model_argv(maven, sandbox, repository, receipt['offline'],
                        Path(roots[variant]), profile, output), 'current model argv/root/output binding')
        raw = raw_file(output, RAW_LIMIT)
        require(row['modelOutput'] == {'name': MODEL_NAMES[label], 'sha256': sha(raw), 'bytes': len(raw)},
                'captured output bytes changed')
        outputs[label] = sha(raw)
    require(receipt['effectiveModelHashes'] == outputs, 'four current output hashes required')
    return {'modelSourceRoots': roots, 'outputHashes': outputs, 'actualModelCommands': 4,
            'sourcePair': pair, 'archivedModelEvidenceReused': False}


def execution_helpers():
    """Load existing helpers only in an explicitly requested execution/preparation path."""
    before = helper_source_hashes()
    sys.path.insert(0, str(HERE))
    result = {}
    for name in ('checkstyle_live_support', 'jar_plugin_storage',
                 'run_boot_live_compatibility', 'run_boot_http_compatibility'):
        module = importlib.import_module(name)
        require(Path(module.__file__).resolve() == HERE / (name + '.py'), 'unexpected shared helper origin')
        result[name] = module
    require(helper_source_hashes() == before, 'shared helper source changed during import')
    for name, relative in SHARED_MODULES.items():
        module = sys.modules.get(name)
        require(module is not None and Path(module.__file__).resolve() == ROOT / relative,
                'transitive helper origin changed: ' + name)
    result['sourceHashesBeforeImport'] = before
    result['sourceHashesAfterImport'] = dict(before)
    return result


def helper_source_hashes():
    return contract()['python_helper_state'](ROOT)


class ModelRun:
    def __init__(self, source, sandbox, repository, jdk, maven, offline, deadline, helpers):
        self.c = contract(); self.source = self.c['real_root'](source); self.sandbox = self.c['real_root'](sandbox)
        self.repository = self.c['real_root'](repository); self.jdk = self.c['real_root'](jdk)
        self.maven = Path(maven).absolute(); self.offline = offline; self.deadline = deadline
        self.support = helpers['checkstyle_live_support']; self.runtime = helpers['run_boot_live_compatibility']
        for left, right in [(self.source, self.sandbox), (self.repository, self.sandbox), (self.source, self.repository)]:
            require(not left.is_relative_to(right) and not right.is_relative_to(left), 'source/cache/output overlap')
        require(self.maven.is_file() and self.maven.resolve() == self.maven
                and (self.jdk / 'bin/java').is_file(), 'current Maven/JDK paths required')
        self.materialized = self.c['materialize_baseline'](self.source, self.sandbox / 'baseline')
        self.baseline = Path(self.materialized['retainedSourceRoot']); self.pair = self.materialized['sourcePair']
        for name in ('home', 'tmp'):
            (self.sandbox / name).mkdir()
        self.fixed = {
            self.sandbox / 'empty-settings.xml': (b'<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n'
                if offline else self.support.CENTRAL_SETTINGS.encode()),
            self.sandbox / 'empty-toolchains.xml': b'<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0"/>\n'}
        for path, data in self.fixed.items():
            with path.open('xb') as stream:
                stream.write(data)
        source_files = [Path(__file__).resolve(), CONTRACT_PATH,
                        ROOT / 'scripts/tests/resources-flatten-model-live-compatibility.test.mjs',
                        *[Path(name) for name in helpers['sourceHashesBeforeImport']],
                        *[self.source / 'scripts/ci' / name for name in
                          ('setup-maven.mjs', 'select-java21.mjs', 'maven-toolchain.mjs')]]
        for module in tuple(sys.modules.values()):
            origin = getattr(module, '__file__', None)
            if origin and Path(origin).is_file() and Path(origin).resolve().is_relative_to(ROOT):
                source_files.append(Path(origin).resolve())
        self.inputs = self.runtime.hashes(source_files)
        require(helper_source_hashes() == helpers['sourceHashesBeforeImport'] == helpers['sourceHashesAfterImport'],
                'helper bytes changed after import')
        require(self.inputs[str(CONTRACT_PATH)] == self.c['loadedSourceSha256'], 'loaded contract bytes changed')
        self.origins, imported = self.runtime.python_origins()
        self.runtime_hashes = self.runtime.hashes(self.runtime.runtime_files(self.jdk, self.maven, imported))
        self.cache_before = self.runtime.cache_snapshot(self.repository); self.cache_previous = self.cache_before
        self.cache_snapshots = []
        environment = self.support.isolated_env(self.jdk, self.sandbox)
        environment.pop('HOME', None); environment.update(LC_ALL='C.UTF-8', TZ='UTC')
        remaining = deadline - time.monotonic(); require(remaining > 0, 'shared deadline exhausted in preparation')
        self.commands = self.support.Commands(self.sandbox, environment, remaining)
        self.commands.deadline = deadline
        self.storage = helpers['jar_plugin_storage'].StorageGuard(self.sandbox,
            PLAN['outputTreeMaximumBytes'], PLAN['outputTreeMaximumEntries'],
            {name: RAW_LIMIT for name in MODEL_NAMES.values()})
        self.report = {'schemaVersion': 'RESOURCES_FLATTEN_ACTUAL_MODELS_V1', 'status': 'RUNNING', 'plan': PLAN,
            'sandbox': str(self.sandbox), 'config': {'maven': str(self.maven), 'jdk': str(self.jdk),
                'repository': str(self.repository), 'source': str(self.source)}, 'offline': offline,
            'sourcePairBefore': self.pair, 'baselineMaterialization': self.materialized,
            'actualModelSourceRoots': self.pair['sourceRoots'], 'sourcePomCount': 28,
            'profiles': ['normal', 'demo'], 'modelCountPerCommand': 26, 'nonReactorOverlaysSourceBoundOnly': 2,
            'executionInputHashes': self.inputs, 'runtimeHashes': self.runtime_hashes, 'pythonOrigins': self.origins,
            'helperImportHashesBefore': helpers['sourceHashesBeforeImport'],
            'helperImportHashesAfter': helpers['sourceHashesAfterImport'],
            'settingsPolicy': 'OFFLINE_EMPTY_SETTINGS' if offline else 'CENTRAL_MIRROR_ONLY',
            'fixedSettingsHashes': {p.name: sha(raw) for p, raw in self.fixed.items()},
            'cacheInputsBefore': self.write_cache('before', self.cache_before), 'cacheSnapshots': self.cache_snapshots,
            'commands': self.commands.results, 'effectiveModelHashes': {}, 'modelOutputs': {},
            'archivedModelEvidenceReused': False, 'scannerEvidence': False, 'canonicalCiEvidence': False,
            'compatibility': False, 'lifecycle': False}

    def write_cache(self, label, values):
        path = self.sandbox / ('cache-' + label + '.json')
        require(not path.exists(), 'new cache witness required')
        raw = (json.dumps(values, sort_keys=True, indent=2) + '\n').encode()
        require(len(raw) <= RECEIPT_LIMIT, 'bounded cache witness')
        with path.open('xb') as stream:
            stream.write(raw)
        return {'name': path.name, 'sha256': sha(raw), 'jarPomCount': len(values)}

    def containment(self):
        require(time.monotonic() < self.deadline, 'shared900-second deadline exhausted')
        require(self.c['source_pair'](self.baseline, self.source) == self.pair, 'fresh32/34 source changed')
        self.c['baseline_membership'](self.baseline, self.pair['sourceHashes']['baseline'])
        for mapping in (self.inputs, self.runtime_hashes):
            require(all(Path(p).is_file() and self.support.digest(Path(p)) == h for p, h in mapping.items()),
                    'execution/runtime input bytes changed')
        require(all(raw_file(p, 1024) == raw for p, raw in self.fixed.items()), 'model settings changed')
        for directory, folders, files in os.walk(self.sandbox, followlinks=False):
            require(len(Path(directory).relative_to(self.sandbox).parts) <= 16, 'model output depth')
            require('.mvn' not in folders + files, 'unreviewed output Maven configuration')
        self.storage.scan()
        require(self.storage.failure is None, 'sampled output storage bound failed')

    def witness_cache(self, label):
        current = self.runtime.cache_snapshot(self.repository)
        require(all(current.get(p) == h for p, h in self.cache_previous.items()), 'existing cache bytes changed')
        additions = sorted(set(current) - set(self.cache_previous))
        require(not self.offline or not additions, 'offline cache membership changed')
        self.cache_snapshots.append({'afterCommand': label, 'manifest': self.write_cache(label, current),
                                     'added': additions, 'priorBytesUnchanged': True})
        self.cache_previous = current

    def run(self, label, argv, seconds, model_root=None):
        self.containment()
        root = model_root or self.sandbox
        extra = {'MAVEN_BASEDIR': str(root), 'MAVEN_OPTS': '-Xmx1024m -Duser.home='
                 + str(self.sandbox / 'home') + ' -Djava.io.tmpdir=' + str(self.sandbox / 'tmp')}
        status, stdout, stderr = self.commands.run(label, argv, min(seconds, self.deadline - time.monotonic()), extra)
        row = self.commands.results[-1]; row.update(cwd=str(self.sandbox), mavenBasedir=str(root))
        require(status == 0, 'actual model/version command failed: ' + label)
        self.containment(); self.witness_cache(label)
        return stdout, stderr

    def execute(self):
        primary_failure = None
        try:
            self.storage.start()
            stdout, stderr = self.run('resources-flatten-model-maven-version', [str(self.maven), '--version'], 30)
            require('Apache Maven 3.9.16' in stdout + stderr and 'Java version: 21.' in stdout + stderr,
                    'one actual Maven3.9.16/Java21 version probe required')
            self.report['toolchain'] = {'maven': '3.9.16', 'javaMajor': 21}
            paths = model_paths(self.sandbox)
            for label in LABELS:
                variant, profile = label.split('-')
                root = self.baseline if variant == 'baseline' else self.source
                require(not paths[label].exists() and not paths[label].is_symlink(), 'new model output required')
                argv = self.support.maven_command(self.maven, self.sandbox, self.repository, self.offline,
                    'models', root / 'pom.xml', model_output=paths[label])
                argv[1:1] = ['--strict-checksums', '-t', str(self.sandbox / 'empty-toolchains.xml'),
                             '-gt', str(self.sandbox / 'empty-toolchains.xml')]
                if profile == 'demo':
                    argv.insert(argv.index(GOAL), '-Pproduct-readiness-demo')
                require(argv == model_argv(self.maven, self.sandbox, self.repository, self.offline,
                        root, profile, paths[label]), 'shared model command shape changed')
                self.run(label + '-models', argv, 300, root)
                raw = raw_file(paths[label], RAW_LIMIT)
                self.report['effectiveModelHashes'][label] = sha(raw)
                self.commands.results[-1]['modelOutput'] = {'name': MODEL_NAMES[label], 'sha256': sha(raw), 'bytes': len(raw)}
            self.report.update(sourcePairAfter=self.c['source_pair'](self.baseline, self.source),
                               actualModelCommandCount=4, totalCommandCount=5)
            capture = verify_current_capture(self.report, self.report['sourcePairAfter'], paths)
            comparison = self.c['verify_models'](self.baseline, self.source, paths,
                                                 model_source_roots=capture['modelSourceRoots'])
            self.containment()
            require(verify_current_capture(self.report, self.c['source_pair'](self.baseline, self.source), paths) == capture,
                    'current capture changed during semantics')
            origins_after, imported = self.runtime.python_origins()
            require(self.runtime.hashes(self.runtime.runtime_files(self.jdk, self.maven, imported)) == self.runtime_hashes,
                    'runtime file membership changed')
            self.report['modelOutputs'] = pack_outputs(paths, self.report['effectiveModelHashes'])
            require(set(unpack_outputs(self.report['modelOutputs'], self.report['effectiveModelHashes'])) == set(LABELS),
                    'four complete retained models required')
            self.containment(); self.witness_cache('final')
            self.report.update(status='PASSED', models=comparison, captureBinding=capture,
                sourceInputsStable=True, executionInputsStable=True, runtimeInputsStable=True,
                cacheExistingJarPomBytesStable=True, cacheInputsAfter=self.write_cache('after', self.cache_previous),
                cacheAddedJarPomPaths=sorted(set(self.cache_previous) - set(self.cache_before)),
                pythonOriginsAfter=origins_after, allOtherModelFieldsUnchanged=True, retainedModelCount=4)
        except BaseException as failure:
            primary_failure = failure
            self.report.update(status='FAILED', errorType=type(failure).__name__, error=str(failure))
            raise
        finally:
            cleanup_errors, cleanup_failure = [], None
            try:
                self.storage.stop()
            except BaseException as failure:
                cleanup_failure = failure
                cleanup_errors.append({'operation': 'storage.stop', 'errorType': type(failure).__name__, 'error': str(failure)})
            try:
                self.report['storage'] = self.storage.report()
            except BaseException as failure:
                cleanup_failure = cleanup_failure or failure
                cleanup_errors.append({'operation': 'storage.report', 'errorType': type(failure).__name__, 'error': str(failure)})
            if cleanup_errors:
                self.report.update(status='FAILED', cleanupError=cleanup_errors)
                if primary_failure is None:
                    raise cleanup_failure


def write_result(sandbox, receipt):
    raw = (json.dumps(receipt, sort_keys=True, separators=(',', ':')) + '\n').encode()
    require(len(raw) <= RECEIPT_LIMIT, 'complete result exceeds8MiB; no truncation accepted')
    path = sandbox / 'result.json'
    with path.open('xb') as stream:
        stream.write(raw)
    print(RESULT_MARKER + ' ' + json.dumps({'status': receipt['status'], 'path': str(path)}), flush=True)


def execute(args):
    require(os.environ.get('RESOURCES_FLATTEN_MODEL_COMPATIBILITY') == 'true', 'explicit model opt-in required')
    require(os.name == 'posix', 'POSIX process-group cleanup required')
    started = time.monotonic(); deadline = started + TOTAL_SECONDS
    c = contract(); source = c['real_root'](args.source); ci = os.environ.get('GITHUB_ACTIONS') == 'true'
    require(ci or args.repository is not None, 'local execution requires an explicit existing offline cache')
    repository = (args.repository or Path.home() / '.m2/repository').absolute()
    require(repository.resolve() == repository and not repository.is_symlink(), 'real repository path required')
    helpers = execution_helpers(); shared = helpers['checkstyle_live_support']; runtime = helpers['run_boot_http_compatibility']
    shared.prepare_repository(repository, source, not ci)
    jdk_value = os.environ.get('JAVA_HOME', ''); executable = shutil.which('mvn')
    require(jdk_value and Path(jdk_value).is_absolute() and executable, 'existing selected Maven/Java21 required')
    jdk, maven = Path(jdk_value).resolve(), Path(executable).resolve()
    base = c['real_root'](args.evidence_directory or '/tmp')
    require(not base.is_relative_to(source) and not base.is_relative_to(repository), 'external evidence parent required')
    sandbox = Path(tempfile.mkdtemp(prefix='resources-flatten-models-', dir=base)).resolve()
    require(all(not any(ch.isspace() or ord(ch) < 32 for ch in str(p)) for p in (source, repository, sandbox, jdk, maven)),
            'JVM-safe explicit paths required')
    remaining = deadline - time.monotonic(); require(remaining > 0, 'deadline exhausted before preparation')
    previous = runtime.install_deadline(max(1, math.ceil(remaining)))
    run = None
    receipt = {'schemaVersion': 'RESOURCES_FLATTEN_ACTUAL_MODELS_V1', 'plan': PLAN,
               'sandbox': str(sandbox), 'status': 'FAILED', 'commands': [], 'modelOutputs': {},
               'scannerEvidence': False, 'canonicalCiEvidence': False, 'lifecycle': False}
    try:
        run = ModelRun(source, sandbox, repository, jdk, maven, not ci, deadline, helpers)
        receipt = run.report; run.execute()
    except BaseException as failure:
        receipt.update(status='FAILED', errorType=type(failure).__name__, error=str(failure))
        available = {label: path for label, path in model_paths(sandbox).items()
                     if path.is_file() and not path.is_symlink() and path.stat().st_size <= RAW_LIMIT}
        try:
            receipt['modelOutputs'] = pack_outputs(available, complete=False)
            receipt['retainedModelCount'] = len(available); receipt['retentionComplete'] = False
        except BaseException as retention_failure:
            receipt['retentionFailure'] = str(retention_failure)
    finally:
        runtime.ignore_terminal_signals(); signal.alarm(0)
    try:
        receipt['elapsedSeconds'] = time.monotonic() - started; receipt['evidenceRetained'] = True
        write_result(sandbox, receipt)
        return 0 if receipt['status'] == 'PASSED' else 1
    finally:
        runtime.restore_signals(previous)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--plan', action='store_true')
    mode.add_argument('--execute', action='store_true')
    mode.add_argument('--verify-archived', action='store_true')
    parser.add_argument('--source', type=Path, default=ROOT)
    parser.add_argument('--repository', type=Path)
    parser.add_argument('--evidence-directory', type=Path)
    args = parser.parse_args()
    if args.execute:
        return execute(args)
    if args.verify_archived:
        print(json.dumps(verify_archived(args.source, args.evidence_directory), sort_keys=True))
        return 0
    print(json.dumps(PLAN, sort_keys=True))
    return 0


if __name__ == '__main__':
    sys.exit(main())
