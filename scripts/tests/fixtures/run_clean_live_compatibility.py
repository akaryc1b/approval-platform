#!/usr/bin/env python3
"""Plan by default; --execute requires CI or explicit CLEAN_PLUGIN_COMPATIBILITY.

Models are captured from the real reactor with help:effective-pom. Clean only
runs its versionless fully qualified goal against fresh no-SCM sandbox POMs.
No lifecycle, repository Clean, publishing, scanner or security claim is made.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from clean_live_support import (CONTRACT, RELEASE, ROOT, HERE, NS, CLEAN_GOAL, MODEL_GOAL,
                                Commands, artifacts, digest, extracted_parent, inside,
                                isolated_env, maven_command, origins, require, source_gate, verify_cached_artifacts)
from clean_live_support import (execution_mode, write_failure_receipt, verify_logging_channels,
                                require_separate_class_channel, API_ASSERTION_LABELS)

CASES = ['defaults', 'defaults-repeat', 'separate-outputs', 'filesets', 'skip', 'exclude-defaults',
         'nested-symlinks', 'root-symlink-characterization', 'explicit-follow-characterization',
         'missing-fileset', 'regular-file-base', 'invalid-fast-mode', 'permission-true', 'permission-false']


class FixtureRun:
    def __init__(self, sandbox, source, repository, maven, jdk, offline, stage):
        self.sandbox, self.source, self.repository = sandbox, source, repository
        self.maven, self.jdk, self.offline = maven, jdk, offline
        self.projects, self.source_hashes = source_gate(source)
        self.parent_xml = extracted_parent(self.projects[0][1])
        self.poms, self.realm = {}, None
        self.identity = (sandbox.stat().st_dev, sandbox.stat().st_ino)
        self.commands = Commands(sandbox, isolated_env(jdk, sandbox), 360 if stage == 'models' else 660)
        self.report = {'stage': stage, 'status': 'RUNNING', 'offline': offline,
                       'sandbox': str(sandbox), 'sourcePomHashes': self.source_hashes,
                       'cases': {name: {'status': 'NOT_RUN'} for name in CASES} if stage == 'fixtures' else {},
                       'commands': self.commands.results, 'coverageGaps': [],
                       'scannerEvidence': False, 'vulnerableApiReachabilityClaim': False}
        self.report['harnessSourceHashes'] = {file.name: digest(file) for file in [
            HERE / 'run_clean_live_compatibility.py', HERE / 'clean_live_support.py',
            HERE / 'CleanRealmObserver.java', HERE / 'CleanSharedIoProbe.java']}
        for name in ['home', 'tmp', 'parent', 'classes']:
            inside(sandbox / name, sandbox).mkdir()
        self.write_pom(sandbox / 'parent/pom.xml', self.parent_xml)
        (sandbox / 'empty-settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n')

    def write_pom(self, file, xml):
        inside(file, self.sandbox).write_text(xml)
        self.poms[file] = xml

    def containment(self):
        require(self.identity == (self.sandbox.stat().st_dev, self.sandbox.stat().st_ino)
                and not self.sandbox.is_symlink(), 'sandbox identity changed')
        require(source_gate(self.source)[1] == self.source_hashes, 'current source POMs changed during live test')
        for file, expected in self.poms.items():
            require(not file.is_symlink() and file.read_text() == expected, 'disposable POM changed')
        require((self.sandbox / 'empty-settings.xml').read_text()
                == '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n', 'empty Maven settings changed')
        for name, expected in self.report['harnessSourceHashes'].items():
            require(digest(HERE / name) == expected, 'live harness source changed during execution')
        if 'observerJarSha256' in self.report:
            require(digest(self.sandbox / 'observer.jar') == self.report['observerJarSha256'], 'compiled passive observer bytes changed')
        count = 0
        for base, directories, files in os.walk(self.sandbox, followlinks=False):
            require(len(Path(base).relative_to(self.sandbox).parts) <= 16, 'sandbox traversal depth exceeded')
            for name in directories + files:
                count += 1
                require(count <= 4096, 'sandbox traversal/cleanup entry budget exceeded')
                path = Path(base) / name
                require(name != '.mvn', 'unexpected Maven bootstrap configuration in sandbox')
                inside(path, self.sandbox)

    def mvn(self, label, stage, project=None, flags=(), observe=False):
        self.containment()
        options = ('-Xmx384m -XX:MaxMetaspaceSize=192m -Dorg.slf4j.simpleLogger.logFile=System.out'
                   + ' -Duser.home=' + str(self.sandbox / 'home'))
        if observe:
            # The verified Maven logger uses stdout. Keep JVM records in the
            # independently bounded stderr pipe, outside its strict realm block.
            options += ' -javaagent:' + str(self.sandbox / 'observer.jar') + ' -Xlog:class+load=info:stderr'
        env = {'MAVEN_BASEDIR': str(self.sandbox), 'MAVEN_OPTS': options}
        command = maven_command(self.maven, self.sandbox, self.repository, self.offline, stage, project, flags)
        result = self.commands.run(label, command, self.sandbox, 300 if stage == 'models' else 120, env, observe)
        if observe:
            require_separate_class_channel((self.sandbox / (label + '.stdout.log')).read_text(),
                                           (self.sandbox / (label + '.stderr.log')).read_text())
        return result

    def toolchain(self):
        logging = self.maven.parent.parent / 'conf/logging/simplelogger.properties'
        require(logging.is_file(), 'selected Maven logging configuration is missing')
        verify_logging_channels(logging.read_text())
        self.report['mavenLogging'] = {'destination': 'System.out', 'configurationSha256': digest(logging),
                                      'jvmClassDestination': 'stderr', 'explicitSystemProperty': True}
        status, log, _ = self.mvn('maven-version', 'version')
        require(status == 0 and 'Apache Maven 3.9.16' in log and 'Java version: 21.' in log,
                'actual Maven 3.9.16 / Java 21 toolchain required')
        status, log, _ = self.commands.run('javac-version', [str(self.jdk / 'bin/javac'), '-version'], self.sandbox)
        require(status == 0 and re.search(r'^javac 21(?:\.|\s)', log, re.M), 'actual Java 21 compiler required')
        self.report['toolchain'] = {'maven': '3.9.16', 'javaMajor': 21, 'javacMajor': 21}

    def models(self):
        status, _, _ = self.mvn('effective-models', 'models', self.source / 'pom.xml')
        require(status == 0, 'actual 26-reactor effective model command failed')
        file = inside(self.sandbox / 'effective-pom.xml', self.sandbox)
        require(file.is_file() and file.stat().st_size <= 16 * 1024 * 1024, 'missing/oversized actual model output')
        RELEASE['verify_effective'](file, self.projects)
        CONTRACT['verify_effective'](file, self.projects)
        self.containment()
        self.report.update(status='PASSED', effectiveModelSha256=digest(file), modelCount=26,
                           retainedGates='Clean and original Release/Site/Dependency/Boot checks passed',
                           baselineModelComparison='Not repeated here; this gate checks current actual models')

    def compile_observer(self):
        status, _, _ = self.commands.run('compile-observer', [str(self.jdk / 'bin/javac'), '-J-Xmx96m',
            '--release', '21', '-d', str(self.sandbox / 'classes'), str(HERE / 'CleanRealmObserver.java')], self.sandbox)
        require(status == 0, 'passive observer compilation failed')
        with zipfile.ZipFile(self.sandbox / 'observer.jar', 'x') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: CleanRealmObserver\n\n')
            for file in sorted((self.sandbox / 'classes').glob('CleanRealmObserver*.class')):
                jar.write(file, file.name)
        self.report['observerJarSha256'] = digest(self.sandbox / 'observer.jar')

    def fixture(self, name, configuration='', outputs=''):
        require(name in CASES and name != 'defaults-repeat', 'unknown fixture')
        child = inside(self.sandbox / name, self.sandbox)
        child.mkdir()
        xml = ('<project xmlns="' + NS['m'] + '"><modelVersion>4.0.0</modelVersion>'
               '<parent><groupId>local.clean.compatibility</groupId><artifactId>clean-parent</artifactId><version>1</version>'
               '<relativePath>../parent/pom.xml</relativePath></parent><artifactId>' + name + '</artifactId><packaging>pom</packaging>'
               '<build>' + outputs + '<plugins><plugin><groupId>org.apache.maven.plugins</groupId>'
               '<artifactId>maven-clean-plugin</artifactId>'
               + ('<configuration>' + configuration + '</configuration>' if configuration else '')
               + '</plugin></plugins></build><reporting><outputDirectory>${project.basedir}/reports-out</outputDirectory></reporting></project>')
        self.write_pom(child / 'pom.xml', xml)
        return child

    def put(self, child, relative, data='fixture sentinel\n'):
        target = inside(child / relative, self.sandbox)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(data)
        return target

    def link(self, file, target):
        inside(file, self.sandbox)
        inside(target, self.sandbox)
        file.symlink_to(target, target_is_directory=target.is_dir())

    def clean(self, child, label=None, flags=(), failure=None):
        label = label or child.name
        self.report['cases'][label]['status'] = 'RUNNING'
        verify_cached_artifacts(self.repository, require_present=self.offline or self.realm is not None)
        status, log, file = self.mvn(label, 'clean', child / 'pom.xml', flags, observe=True)
        require(status == (1 if failure else 0), 'unexpected actual Clean exit status for ' + label)
        require(failure is None or failure in log, 'expected Clean error cause absent: ' + str(failure))
        require('--- clean:3.2.0:clean ' in log, 'versionless goal did not inherit Clean 3.2.0')
        coordinates, approved = artifacts(file, self.repository)
        class_file = self.sandbox / (label + '.stderr.log')
        observed = origins(log, approved, class_log=class_file.read_text(), skip=dict(flags).get('maven.clean.skip') == 'true',
                           selectors_used=child.name in {'filesets', 'exclude-defaults'},
                           early_invalid=child.name == 'invalid-fast-mode')
        record = self.report['cases'][label]
        record.update(exitStatus=status, expectedFailure=failure, realmCoordinates=coordinates,
                      actualArtifacts=approved, origins=observed, logSha256=digest(file),
                      classLoadCapture=self.commands.results[-1]['classLoadCapture'])
        if label == 'defaults':
            self.realm = record
        self.containment()
        return log

    def finish(self, name):
        self.report['cases'][name].update(status='PASSED', filesystemAssertions='PASSED')

    def fixtures(self):
        self.compile_observer()
        child = self.fixture('defaults')
        for relative in ['target/classes/a', 'target/test-classes/b', 'target/site/index.html', 'target/packaged.jar']:
            self.put(child, relative)
        keep = self.put(child, 'src/keep.txt')
        self.clean(child)
        require(not (child / 'target').exists() and keep.read_text() == 'fixture sentinel\n', 'default outputs/source boundary')
        self.finish('defaults')
        self.clean(child, 'defaults-repeat')
        self.finish('defaults-repeat')

        child = self.fixture('separate-outputs', outputs='<directory>${project.basedir}/build-out</directory>'
            '<outputDirectory>${project.basedir}/classes-out</outputDirectory>'
            '<testOutputDirectory>${project.basedir}/tests-out</testOutputDirectory>')
        for relative in ['build-out/a', 'classes-out/a', 'tests-out/a', 'reports-out/a']:
            self.put(child, relative)
        self.clean(child)
        require(all(not (child / p).exists() for p in ['build-out', 'classes-out', 'tests-out']), 'configured outputs remain')
        require((child / 'reports-out/a').exists(), 'Clean 3.2.0 separate reporting directory characterization changed')
        self.finish('separate-outputs')
        fileset = ('<filesets><fileset><directory>${project.basedir}/extra</directory><followSymlinks>false</followSymlinks>'
                   '<useDefaultExcludes>true</useDefaultExcludes><includes><include>**/*.tmp</include></includes>'
                   '<excludes><exclude>**/keep.tmp</exclude></excludes></fileset></filesets>')
        child = self.fixture('filesets', fileset)
        for relative in ['extra/remove.tmp', 'extra/nested/remove.tmp', 'extra/keep.tmp', 'extra/.git/private.tmp', 'extra/plain.txt']:
            self.put(child, relative)
        self.clean(child)
        require(not (child / 'extra/remove.tmp').exists() and not (child / 'extra/nested/remove.tmp').exists(), 'includes not removed')
        require(all((child / p).exists() for p in ['extra/keep.tmp', 'extra/.git/private.tmp', 'extra/plain.txt']), 'excluded files changed')
        self.finish('filesets')
        child = self.fixture('skip', fileset)
        self.put(child, 'target/a'); self.put(child, 'extra/remove.tmp')
        self.clean(child, flags=[('maven.clean.skip', 'true')])
        require((child / 'target/a').exists() and (child / 'extra/remove.tmp').exists(), 'skip mutated fixture')
        self.finish('skip')
        child = self.fixture('exclude-defaults', fileset)
        self.put(child, 'target/a'); self.put(child, 'extra/remove.tmp')
        self.clean(child, flags=[('maven.clean.excludeDefaultDirectories', 'true')])
        require((child / 'target/a').exists() and not (child / 'extra/remove.tmp').exists(), 'exclude defaults failed')
        self.finish('exclude-defaults')

        child = self.fixture('nested-symlinks')
        outside = self.put(self.sandbox, 'nested-outside/keep.txt')
        self.put(child, 'target/a')
        for name, target in [('dir-link', outside.parent), ('file-link', outside),
                             ('dangling', self.sandbox / 'absent-target'), ('cycle', child / 'target')]:
            self.link(child / 'target' / name, target)
        self.clean(child)
        require(not (child / 'target').exists() and outside.read_text() == 'fixture sentinel\n', 'nested symlink characterization failed')
        self.finish('nested-symlinks')
        child = self.fixture('root-symlink-characterization')
        victim = self.put(self.sandbox, 'root-symlink-disposable-target/a')
        self.link(child / 'target', victim.parent)
        self.clean(child)
        require(not victim.parent.exists() and (child / 'target').is_symlink(), 'root symlink characterization changed')
        self.finish('root-symlink-characterization')
        child = self.fixture('explicit-follow-characterization')
        victim = self.put(self.sandbox, 'explicit-follow-disposable-target/a')
        self.put(child, 'target/a'); self.link(child / 'target/dir-link', victim.parent)
        self.clean(child, flags=[('maven.clean.followSymLinks', 'true')])
        require(not victim.exists() and victim.parent.exists(), 'explicit follow characterization changed')
        self.finish('explicit-follow-characterization')
        child = self.fixture('missing-fileset', '<filesets><fileset><includes><include>*.tmp</include></includes></fileset></filesets>')
        self.clean(child, failure='Missing base directory'); self.finish('missing-fileset')
        child = self.fixture('regular-file-base', '<filesets><fileset><directory>${project.basedir}/regular-file</directory></fileset></filesets>')
        self.put(child, 'regular-file')
        self.clean(child, flags=[('maven.clean.failOnError', 'false')], failure='Invalid base directory')
        self.finish('regular-file-base')
        child = self.fixture('invalid-fast-mode')
        self.clean(child, flags=[('maven.clean.fast', 'true'), ('maven.clean.fastMode', 'invalid-fixture-value')], failure='for fastMode')
        self.finish('invalid-fast-mode')
        if os.geteuid() == 0:
            gap = 'Permission denial NOT_RUN: root bypasses ordinary POSIX permissions; compatibility is INCOMPLETE.'
            self.report['coverageGaps'].append(gap)
            for name in ['permission-true', 'permission-false']:
                self.report['cases'][name]['reason'] = gap
        else:
            for flag in ['true', 'false']:
                child = self.fixture('permission-' + flag)
                payload = self.put(child, 'target/locked/a')
                payload.parent.chmod(0o555)
                try:
                    log = self.clean(child, flags=[('maven.clean.retryOnError', 'false'), ('maven.clean.failOnError', flag)],
                                     failure='Failed to delete' if flag == 'true' else None)
                    require(payload.exists() and 'Failed to delete' in log, 'ordinary permission denial was not observed')
                    self.finish(child.name)
                finally:
                    payload.parent.chmod(0o755)
        self.api_probe()
        self.containment()
        self.report['status'] = 'INCOMPLETE' if self.report['coverageGaps'] else 'PASSED'

    def api_probe(self):
        # Reparse the actual successful default log; no synthetic or cached report can supply the classpath.
        require(self.realm is not None and self.realm['status'] == 'PASSED' and self.realm['exitStatus'] == 0,
                'successful current default Clean goal required before API probe')
        file = self.sandbox / 'defaults.log'
        require(digest(file) == self.realm['logSha256'], 'actual default goal output changed')
        coordinates, approved = artifacts(file, self.repository)
        require(coordinates == self.realm['realmCoordinates'] and approved == self.realm['actualArtifacts'], 'actual realm binding changed')
        class_file = self.sandbox / 'defaults.stderr.log'
        capture = self.realm['classLoadCapture']
        require(capture['complete'] and digest(class_file) == capture['sha256']
                and class_file.stat().st_size == capture['bytes'],
                'default goal class-load capture changed')
        require(origins(file.read_text(), approved, class_log=class_file.read_text()) == self.realm['origins'],
                'natural origin binding changed')
        self.containment()
        status, _, _ = self.commands.run('compile-api', [str(self.jdk / 'bin/javac'), '-J-Xmx96m', '--release', '21',
            '-d', str(self.sandbox / 'classes'), str(HERE / 'CleanSharedIoProbe.java')], self.sandbox)
        require(status == 0, 'selected-realm API probe compilation failed')
        self.containment()
        require(artifacts(file, self.repository) == (coordinates, approved), 'realm artifact bytes changed before API execution')
        api_class = self.sandbox / 'classes/CleanSharedIoProbe.class'
        self.report['apiClassSha256'] = digest(api_class)
        status, log, _ = self.commands.run('api-probe', [str(self.jdk / 'bin/java'), '-Xmx96m',
            '-XX:MaxMetaspaceSize=96m', '-Duser.home=' + str(self.sandbox / 'home'),
            '-cp', str(self.sandbox / 'classes'), 'CleanSharedIoProbe', '2.20.0',
            str(inside(self.sandbox / 'api-disposable', self.sandbox)), *[approved[c]['path'] for c in coordinates]], self.sandbox)
        passed_labels = re.findall(r'^PASS (.+)$', log, re.M)
        require(status == 0 and 'PROBE_COMPLETE:' in log and len(passed_labels) == 23
                and set(passed_labels) == API_ASSERTION_LABELS,
                'selected-realm API probe failed or incomplete')
        self.report['apiProbe'] = {'status': 'PASSED', 'assertions': 23, 'defaultGoalLogSha256': digest(file),
                                   'defaultClassLoadLogSha256': capture['sha256'],
                                   'assertionLabels': passed_labels,
                                   'actualArtifacts': approved, 'goalReachabilityClaim': False}
        print(log.strip(), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--stage', choices=['models', 'fixtures'], default='fixtures')
    parser.add_argument('--source', type=Path, default=ROOT)
    parser.add_argument('--repository', type=Path)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    if not args.execute:
        print(json.dumps({'execution': 'NOT_RUN', 'goals': [MODEL_GOAL, CLEAN_GOAL], 'cases': CASES,
                          'models': 26, 'apiAssertions': 23, 'localRequiresOfflinePinnedRepository': True,
                          'rootPermissionCoverage': 'NOT_RUN; INCOMPLETE result fails an enabled live test',
                          'commandTimeoutSeconds': {'models': 300, 'clean': 120, 'compilerAndApi': 30},
                          'aggregateTimeoutSeconds': {'models': 360, 'fixtures': 660}}, indent=2))
        return 0
    offline = execution_mode(os.environ, args.repository, args.offline)
    require(os.name == 'posix', 'POSIX sandbox and permission semantics required')
    def interrupted(signum, frame):
        # Commands.run catches this and terminates its entire child process group.
        raise RuntimeError('live harness interrupted by signal ' + str(signum))
    for signum in [signal.SIGTERM, signal.SIGINT]:
        signal.signal(signum, interrupted)
    source = args.source.resolve()
    repository = (args.repository or Path.home() / '.m2/repository').resolve()
    require(not repository.is_relative_to(source) and not source.is_relative_to(repository), 'repository/source overlap')
    require(not offline or repository.is_dir(), 'offline repository is missing')
    jdk_value = os.environ.get('JAVA_HOME', '')
    require(bool(jdk_value) and Path(jdk_value).is_absolute(), 'explicit JAVA_HOME is required')
    jdk = Path(jdk_value).resolve()
    maven_value = shutil.which('mvn')
    require(maven_value is not None, 'verified Maven installation must be on PATH')
    maven = Path(maven_value).resolve()
    require(all((jdk / 'bin' / name).is_file() for name in ['java', 'javac']), 'complete JDK required')
    # A fresh system-temp sandbox has no checkout ancestor or inherited .mvn files.
    sandbox = Path(tempfile.mkdtemp(prefix='clean-live-', dir='/tmp')).resolve()
    identity = (sandbox.stat().st_dev, sandbox.stat().st_ino)
    require(not sandbox.is_relative_to(source) and not source.is_relative_to(sandbox), 'sandbox/source overlap')
    require(not sandbox.is_relative_to(repository) and not repository.is_relative_to(sandbox), 'sandbox/repository overlap')
    require(not re.search(r'\s|[\x00-\x1f]', str(sandbox)), 'sandbox path cannot contain JVM option separators')
    run = None
    try:
        run = FixtureRun(sandbox, source, repository, maven, jdk, offline, args.stage)
        if args.stage == 'fixtures':
            verify_cached_artifacts(repository, require_present=offline)
        run.toolchain()
        run.models() if args.stage == 'models' else run.fixtures()
        if run.report['status'] == 'PASSED':
            run.report.update(status='FINALIZING', cleanup='PENDING')
            run.containment()
            require(shutil.rmtree.avoids_symlink_attacks, 'safe sandbox cleanup unavailable')
            # No success receipt exists until verified cleanup has completed.
            # Traversal is already bounded to 4,096 entries and depth 16.
            shutil.rmtree(sandbox)
            run.report.update(status='PASSED', cleanup='REMOVED')
    except BaseException as exc:
        if run is None:
            report = {'stage': args.stage, 'status': 'FAILED', 'sandbox': str(sandbox),
                      'errorType': type(exc).__name__, 'error': str(exc)}
        else:
            report = run.report
            report.update(status='FAILED', errorType=type(exc).__name__, error=str(exc))
            for case in report['cases'].values():
                if case['status'] == 'RUNNING':
                    case['status'] = 'FAILED'
        write_failure_receipt(sandbox, identity, report)
        print('CLEAN_LIVE_RESULT ' + json.dumps(report), flush=True)
        print('Raw failure evidence location: ' + str(sandbox)
              + '. It is temporary and may disappear at runner teardown; bounded sanitized diagnostics and hashes are in CLEAN_COMMAND receipts.', flush=True)
        return 1
    report = run.report
    if report['status'] != 'PASSED':
        write_failure_receipt(sandbox, identity, report)
    print('CLEAN_LIVE_RESULT ' + json.dumps(report), flush=True)
    if report['status'] != 'PASSED':
        print('Incomplete evidence is temporarily retained in ' + str(sandbox)
              + '; the structured receipt above survives in the CI log.', flush=True)
        return 2
    print('CLEAN_LIVE_STAGE_PASSED ' + args.stage + '; sandbox removed; retained command and evidence hashes above.', flush=True)
    return 0


if __name__ == '__main__':
    try:
        status = main()
    except Exception as exc:
        # Configuration/admission errors can precede sandbox creation. Avoid a
        # traceback containing source snippets; retain the explicit failure.
        print('CLEAN_LIVE_PREFLIGHT_FAILED ' + json.dumps({'status': 'FAILED',
              'errorType': type(exc).__name__, 'error': str(exc)}), flush=True)
        status = 1
    sys.exit(status)
