#!/usr/bin/env python3
"""One offline E2 capture and byte-matched owner replay; never acceptance evidence."""
import argparse
from runpy import run_path
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time

HEAD = '905cd30fde430972da58203a61136d50a28f81e8'
TREE = '8b5d522974a5547a7d990f42b6ce386dadad0941'
ROOT_POM = 'ddb286b44b1976dd19ad74b06f3ceef86e19d0fbee87dd27dc87985ffb1f7932'
WITNESS = 'docs/operations/assembly-enforcer-lang-evidence/source-witness.json'
WITNESS_SHA = '7e5b4ee22444b7f76334027fcb878e777854d05502ba24312dcb8e20be000a38'
RULES_SHA = 'da9bd87aaf229d4cfd25217910fe806785d3631ed3383748582f5d58ddf870c0'
PACKAGES = ('package.json', 'packages/approval-sdk/package.json', 'packages/contracts/package.json',
            'packages/form-schema/package.json', 'packages/process-dsl/package.json', 'examples/connector-smoke/package.json')
MANIFESTS = ('docs/m6/m6-pr-e-e2-action-resolution-baseline.json',
             'docs/m6/m6-pr-e-e3-r2b-workflow-supply-chain-remediation.json',
             'docs/m6/maven-toolchain-transition.json', 'docs/m6/hygiene-java21-workflow-transition.json')
TOTAL_SECONDS = 900
MAX_BYTES = 32 * 1024 * 1024


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def relative(value):
    require(isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_./-]+', value)
            and not value.startswith('/') and all(part not in ('', '.', '..') for part in value.split('/')),
            'Unsafe relative capture input')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--models-only', action='store_true')
    parser.add_argument('--variant', choices=('baseline', 'candidate'), required=True)
    parser.add_argument('--baseline-source', type=Path)
    parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[3])
    for name in ('output', 'maven', 'java-home', 'node', 'maven-repository'):
        parser.add_argument('--' + name, type=Path)
    parser.add_argument('--toolchain-evidence', type=Path, action='append', default=[])
    args = parser.parse_args()
    if not args.execute:
        print(json.dumps({'status': 'NOT_RUN', 'source': str(args.source), 'sourceBaseHead': HEAD,
                          'operations': ['E2 --full-maven', 'dependency:resolve-plugins owner replay'],
                          'offline': True, 'totalTimeoutSeconds': TOTAL_SECONDS,
                          'cleanupGraceSeconds': 5, 'maximumBytesPerOutput': MAX_BYTES,
                          'canonicalCiEvidence': False}, indent=2))
        return
    require(all(getattr(args, name) is not None for name in ('output', 'maven', 'java_home', 'node', 'maven_repository')),
            'Execution requires all explicit reviewed paths')
    source, output, maven, jdk, node, repository = (getattr(args, name).absolute() for name in
        ('source', 'output', 'maven', 'java_home', 'node', 'maven_repository'))
    require(all(re.fullmatch(r'[A-Za-z0-9_./+-]+', str(p)) for p in (source, output, maven, jdk, node, repository)),
            'Reviewed Maven launcher paths must not contain shell/whitespace metacharacters')
    require(source.resolve() == source and repository.resolve() == repository and output.resolve() == output and not output.exists(),
            'Source/cache must be real paths and output must be a new exclusive directory')
    require(not output.is_relative_to(source) and not output.is_relative_to(repository),
            'Capture output must be outside source and cache')
    for path in (maven, jdk / 'bin/java', node):
        require(path.is_file() and os.access(path, os.X_OK), 'Reviewed executable missing: ' + str(path))

    start = time.monotonic()
    deadline = start + TOTAL_SECONDS
    active = None
    record = {'schemaVersion': 'APPROVAL_RESOURCES_FLATTEN_CAPTURE_EXECUTION_V1', 'status': 'RUNNING',
              'canonicalCiEvidence': False, 'exactHeadEvidence': False, 'sourceDirty': args.variant == 'candidate',
              'sourceHead': HEAD, 'sourceTree': TREE, 'offline': True,
              'totalTimeoutSeconds': TOTAL_SECONDS, 'cleanupGraceSeconds': 5,
              'maxBytesPerOutput': MAX_BYTES, 'outputLimitCheckIntervalSeconds': 0.1,
              'outputLimitCanOvershootBetweenChecks': True, 'commands': [],
              'compatibilityAcceptanceClaimed': False, 'scannerDispositionClaimed': False,
              'reviewerAcceptanceClaimed': False, 'releaseBlocked': True}

    def interrupted(signum, _frame):
        raise InterruptedError('Capture interrupted by signal ' + str(signum))

    def stop_group(child):
        # A reaped leader does not establish that descendants have terminated.
        try:
            os.killpg(child.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        else:
            time.sleep(0.1)
        try:
            os.killpg(child.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        child.wait(timeout=4)

    def save():
        (output / 'capture-execution.json').write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')

    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP, signal.SIGALRM):
        signal.signal(sig, interrupted)
    signal.setitimer(signal.ITIMER_REAL, TOTAL_SECONDS)
    output.mkdir(parents=False, exist_ok=False)
    for name in ('home', 'tmp', 'source-inputs'):
        (output / name).mkdir()
    settings = output / 'empty-settings.xml'
    settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n')
    toolchains = output / 'empty-toolchains.xml'
    toolchains.write_text('<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0"/>\n')
    env = {'PATH': f'{maven.parent}:{jdk / "bin"}:{node.parent}:/usr/bin:/bin',
           'JAVA_HOME': str(jdk), 'TMPDIR': str(output / 'tmp'),
           'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'UTC', 'MAVEN_SKIP_RC': '1',
           'MAVEN_BASEDIR': str(source),
           'M6_PR_E_E2_MAVEN_REPOSITORY': str(repository),
           'MAVEN_ARGS': f'-o --strict-checksums -Dstyle.color=never -s {settings} -gs {settings}'
                         f' -t {toolchains} -gt {toolchains} -Dmaven.repo.local={repository}'
                         + ('' if args.models_only else f' -f {source / "pom.xml"}'),
           'MAVEN_OPTS': f'-Xmx1024m -Duser.home={output / "home"} -Djava.io.tmpdir={output / "tmp"}',
           'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_OPTIONAL_LOCKS': '0',
           'GIT_NO_REPLACE_OBJECTS': '1'}
    record['environment'] = env

    def run(label, command, seconds):
        nonlocal active
        remaining = min(seconds, deadline - time.monotonic())
        require(remaining > 0, 'Capture shared deadline exhausted')
        item = {'label': label, 'command': command, 'cwd': str(source), 'timeoutSeconds': remaining, 'status': 'RUNNING', 'mavenBasedir': env['MAVEN_BASEDIR']}
        record['commands'].append(item); save()
        began = time.monotonic()
        stdout_path, stderr_path = output / (label + '.stdout'), output / (label + '.stderr')
        try:
            with stdout_path.open('xb') as stdout, stderr_path.open('xb') as stderr:
                active = subprocess.Popen(command, cwd=source, env=env, stdin=subprocess.DEVNULL,
                                          stdout=stdout, stderr=stderr, start_new_session=True)
                while True:
                    paths = [stdout_path, stderr_path, output / 'plugin-owner-report.txt',
                             *[output / (v + '-' + p + '-effective-pom.xml') for v in ('baseline', 'candidate') for p in ('normal', 'demo')]]
                    paths.extend(p for p in (output / 'tmp').rglob('*') if p.is_file())
                    require(not any(p.exists() and p.stat().st_size > MAX_BYTES for p in paths), 'Capture output limit exceeded')
                    status = active.poll()
                    if status is not None:
                        break
                    require(time.monotonic() - began < remaining, 'Capture command deadline exhausted: ' + label)
                    time.sleep(0.1)
                require(status == 0, 'Capture command failed: ' + label)
            item.update(status='PASSED', exitStatus=status)
        except BaseException as error:
            item.update(status='FAILED', failure=type(error).__name__)
            raise
        finally:
            if active is not None:
                stop_group(active)
                item.setdefault('exitStatus', active.returncode)
                item['processGroupTerminationRequested'] = True
                active = None
            item['elapsedSeconds'] = time.monotonic() - began
            save()
        return stdout_path.read_text()

    def git(label, *arguments):
        return run(label, ['/usr/bin/git', '-c', 'core.fsmonitor=false', '-C', str(source), *arguments], 10).strip()

    def inventory(paths):
        result = {}
        for path in sorted(set(paths)):
            require(path.is_file() and not path.is_symlink(), 'Missing or symlinked capture input: ' + str(path))
            result[str(path)] = sha(path)
        return result

    def tree_files(directory):
        require(directory.is_dir(), 'Input directory missing: ' + str(directory))
        require(not any(p.is_symlink() for p in directory.rglob('*')), 'Symlinked input tree requires separate review')
        return [p for p in directory.rglob('*') if p.is_file()]

    def runtime_files():
        paths = tree_files(maven.parent.parent)
        paths += [node, jdk / 'release', jdk / 'bin/java', jdk / 'lib/modules']
        paths += list((jdk / 'lib').rglob('*.so')) + tree_files(jdk / 'conf')
        paths += [p.absolute() for p in args.toolchain_evidence]
        return paths

    try:
        preflight = Path(__file__).with_name('preflight.py')
        require(sha(preflight) == '35c85276e67693e920802241b55f8f1ca6ecea4ec7fda2134b6657f525b7f5f5', 'Preflight script changed')
        entrance = run_path(str(preflight))['verify']
        record['sourceEntrance'] = entrance(source, args.variant)
        if args.models_only:
            require(args.baseline_source is not None, 'Missing baseline source')
            record['baselineEntrance'] = entrance(args.baseline_source, 'baseline')
        save()
        require(git('head-before', 'rev-parse', '--verify', 'HEAD') == HEAD
                and git('tree-before', 'rev-parse', '--verify', 'HEAD^{tree}') == TREE, 'Capture accepted source base drift')
        require(bool(git('status-before', 'status', '--porcelain=v1', '--untracked-files=normal')) == (args.variant == 'candidate'), 'Unexpected source cleanliness')
        require(sha(source / WITNESS) == WITNESS_SHA, 'Immutable accepted source witness changed')
        witness = json.loads((source / WITNESS).read_text())
        pom_paths = [relative(row['path']) for row in witness['sourcePomFiles']]
        require(len(pom_paths) == len(set(pom_paths)) == 28, 'Capture source POM membership drift')
        pom_hashes = {name: sha(source / name) for name in pom_paths}
        for row in witness['sourcePomFiles']:
            expected = row['candidateSha256']
            if args.variant == 'candidate':
                expected = {'pom.xml': '7aaef759cbdebaafde2383186cbb90861c23f51bd4fe3219614ed0fa8c22a9ab',
                            'integrations/host-sdk/pom.xml': '2d933d9c89c7a0ee99b4870091a880ac086b295501914297c9bb6a21dd751078'}.get(row['path'], expected)
            require(pom_hashes[row['path']] == expected,
                    'Capture source POM drift: ' + row['path'])
        require(sha(source / 'config/checkstyle/checkstyle.xml') == RULES_SHA, 'Capture Checkstyle rules drift')
        generator_sources = dict(witness['generatorSources'])
        require(generator_sources['scripts/security/hygiene-java21-workflow-transition.mjs']
                == '820cffc7c6202de35c767c445a60361c00e632e8c7163a5284b0e7490e605f0e', 'Historical generator pin drift')
        generator_sources['scripts/security/hygiene-java21-workflow-transition.mjs'] = '76ada2498855ebf319612b92dbaf4fa4fd223d420bd8a10e030ab11051699b07'
        current_extra_inputs = {'docs/m6/hygiene-m3-log-retention-transition.json': '452c856216d8213b7705a5a83b0eaf801c146a782c1a1759fe5a7d99e75f8efc'}
        require(all(sha(source / p) == h for p, h in current_extra_inputs.items()), 'Current M3 retention input drift')
        record['currentGeneratorContinuation'] = {'sourceHead': HEAD, 'sourceTree': TREE,
            'historicalGeneratorSources': witness['generatorSources'], 'currentGeneratorSources': generator_sources,
            'currentExtraInputs': current_extra_inputs, 'reason': 'Accepted main905 M3 log retention continuation; archival witness remains unchanged'}
        require(len(generator_sources) == 4 and all(sha(source / relative(p)) == h for p, h in generator_sources.items()),
                'Capture unchanged generator source closure drift')
        baseline = json.loads((source / MANIFESTS[0]).read_text())
        workflows = sorted(relative(row['path']) for row in baseline['workflowFiles'])
        require(len(workflows) == 9 and workflows == sorted(str(p.relative_to(source)) for p in
                (source / '.github/workflows').iterdir() if p.suffix in ('.yml', '.yaml')), 'Capture workflow membership drift')
        source_inputs = set(pom_paths) | set(PACKAGES) | set(MANIFESTS) | set(workflows) | set(generator_sources)
        source_inputs |= {WITNESS, 'config/checkstyle/checkstyle.xml'} | set(current_extra_inputs)
        if args.models_only:
            source_inputs |= {'scripts/ci/clean_plugin_contract.py', 'scripts/ci/checkstyle_plugin_contract.py'}
        for manifest in MANIFESTS[2:]:
            source_inputs.update(relative(p) for p in json.loads((source / manifest).read_text())['toolchainSourceBlobs'])
        lock_present = (source / 'pnpm-lock.yaml').exists()
        if lock_present:
            source_inputs.add('pnpm-lock.yaml')
        config_roots = {source, *source.parents}
        config_roots.update((source / p).parent for p in pom_paths)
        require(all(not (p / '.mvn').exists() for p in config_roots), 'Unreviewed Maven project configuration')
        input_hashes = {p: sha(source / p) for p in sorted(source_inputs)}
        for name in source_inputs:
            target = output / 'source-inputs' / name
            target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes((source / name).read_bytes())
        collector = Path(__file__).resolve()
        (output / 'capture.py').write_bytes(collector.read_bytes())
        runtime_hashes = inventory(runtime_files())
        cache_paths = tree_files(repository); cache_hashes = inventory(cache_paths)
        (output / 'runtime-inputs.json').write_text(json.dumps(runtime_hashes, indent=2, sort_keys=True) + '\n')
        (output / 'cache-inputs.json').write_text(json.dumps(cache_hashes, indent=2, sort_keys=True) + '\n')
        record.update(sourcePomHashes=pom_hashes, captureInputs=input_hashes, generatorSources=generator_sources,
                      sourcePomSha256=pom_hashes['pom.xml'], collectorSha256=sha(collector),
                      runtimeInputManifestSha256=sha(output / 'runtime-inputs.json'),
                      cacheInputManifestSha256=sha(output / 'cache-inputs.json'), rootPnpmLockPresent=lock_present)
        save()
        if args.models_only:
            require(args.variant == 'candidate' and args.baseline_source is not None, 'Models require candidate and explicit independent baseline')
            baseline = args.baseline_source.absolute()
            require(baseline.resolve() == baseline and baseline != source, 'Baseline must be an independent real source directory')
            baseline_inputs = {p: sha(baseline / p) for p in sorted(source_inputs)}
            for row in witness['sourcePomFiles']:
                require(baseline_inputs[row['path']] == row['candidateSha256'], 'Baseline POM drift: ' + row['path'])
            require(all(baseline_inputs[p] == input_hashes[p] for p in source_inputs if p not in ('pom.xml', 'integrations/host-sdk/pom.xml')), 'Baseline non-POM input drift')
            baseline_roots = {baseline, *baseline.parents, *[(baseline / p).parent for p in pom_paths]}
            require(all(not (p / '.mvn').exists() for p in baseline_roots), 'Baseline Maven config drift')
            record['baselineCaptureInputs'] = baseline_inputs
            import xml.etree.ElementTree as ET
            record['effectiveModelHashes'] = {}; record['effectiveModelCounts'] = {}
            for profile in ('normal', 'demo'):
                for label, model_root in [('baseline', baseline), ('candidate', source)]:
                    name = label + '-' + profile
                    file = output / (name + '-effective-pom.xml')
                    env['MAVEN_BASEDIR'] = str(model_root)
                    command = [str(maven), '-B', '-ntp', '-f', str(model_root / 'pom.xml')]
                    if profile == 'demo': command += ['-Pproduct-readiness-demo']
                    run(name + '-models', command + ['org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom', '-Doutput=' + str(file)], 300)
                    document = ET.parse(file).getroot()
                    models = list(document) if document.tag.split('}')[-1] == 'projects' else [document]
                    require(len(models) == 26, 'Expected 26 effective reactor models: ' + name)
                    record['effectiveModelHashes'][name] = sha(file)
                    record['effectiveModelCounts'][name] = len(models)
            require(baseline_inputs == {p: sha(baseline / p) for p in baseline_inputs}
                    and all(not (p / '.mvn').exists() for p in baseline_roots), 'Baseline inputs changed')
            record['modelSemanticsAcceptanceClaimed'] = False
        else:
            e2 = json.loads(run('e2', [str(node), str(source / 'scripts/security/m6-pr-e-e2-generate-sbom.mjs'),
                                     '--full-maven', '--root=' + str(source)], 525))
            report = output / 'plugin-owner-report.txt'
            run('owners', [str(maven), '-B', '-ntp', 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins',
                           '-DappendOutput=true', '-DoutputFile=' + str(report)], 240)
            require(e2['repository'] == 'akaryc1b/approval-platform' and e2['commitSha'] == HEAD
                    and e2['maven']['reactorProjectCount'] == len(e2['maven']['reactorRoots']) == 26,
                    'Captured E2 identity/count mismatch')
            payload = {key: value for key, value in e2.items() if key != 'contentSha256'}
            canonical = json.dumps(payload, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
            require(hashlib.sha256(canonical).hexdigest() == e2['contentSha256'], 'Captured E2 canonical digest mismatch')
            require(sha(report) == e2['maven']['pluginResolutionSha256'], 'Owner replay bytes differ from actual E2 report')
        require(input_hashes == {p: sha(source / p) for p in input_hashes}
                and (source / 'pnpm-lock.yaml').exists() == lock_present
                and workflows == sorted(str(p.relative_to(source)) for p in
                    (source / '.github/workflows').iterdir() if p.suffix in ('.yml', '.yaml'))
                and all(not (p / '.mvn').exists() for p in config_roots), 'Capture source/config inputs changed')
        require(runtime_hashes == inventory(runtime_files()), 'Capture runtime inputs changed')
        after_cache = inventory(tree_files(repository))
        if cache_hashes != after_cache:
            delta = {p: {'before': cache_hashes.get(p), 'after': after_cache.get(p)}
                     for p in sorted(cache_hashes.keys() | after_cache.keys()) if cache_hashes.get(p) != after_cache.get(p)}
            (output / 'cache-changes.json').write_text(json.dumps(delta, indent=2, sort_keys=True) + '\n')
            record['cacheChangesSha256'] = sha(output / 'cache-changes.json')
        require(cache_hashes == after_cache, 'Capture offline cache inputs changed')
        require(sha(collector) == record['collectorSha256'], 'Capture collector changed')
        require(git('head-after', 'rev-parse', '--verify', 'HEAD') == HEAD
                and git('tree-after', 'rev-parse', '--verify', 'HEAD^{tree}') == TREE, 'Capture source base changed')
        require(bool(git('status-after', 'status', '--porcelain=v1', '--untracked-files=normal')) == (args.variant == 'candidate'), 'Source cleanliness changed')
        record.update(status='PASSED_CAPTURE_ONLY', captureInputsStable=True, runtimeInputsStable=True,
                      cacheInputsStable=True)
        if not args.models_only:
            record.update(e2Sha256=sha(output / 'e2.stdout'), ownerReportSha256=sha(report))
    except BaseException as error:
        record.update(status='FAILED_CAPTURE', captureInputsStable=False, failure=type(error).__name__, message=str(error))
        raise
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            signal.signal(sig, signal.SIG_IGN)
        if active is not None:
            stop_group(active)
        record['elapsedSeconds'] = time.monotonic() - start
        save()
        print(json.dumps({'status': record['status'], 'output': str(output), 'canonicalCiEvidence': False}))


if __name__ == '__main__':
    main()
