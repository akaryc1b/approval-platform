#!/usr/bin/env python3
"""Actual inherited Compiler goals and wrapper APIs in fresh bounded local projects.

No repository lifecycle, installation, deployment, scanner, SCM or browser action.
Local opt-in is always offline. Raw local evidence is retained under the supplied
evidence directory; ordinary CI uses a fresh temporary directory.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import re
from runpy import run_path
import shutil
import signal
import sys
import tempfile
from urllib.parse import unquote, urlparse
import xml.etree.ElementTree as ET
import zipfile

from clean_live_support import Commands, digest, inside, isolated_env, require, verify_logging_channels

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CONTRACT = run_path(str(ROOT / 'scripts/ci/compiler_plugin_contract.py'))
RELEASE = run_path(str(ROOT / 'scripts/ci/verify-release-plugin-baseline.py'))
NS = CONTRACT['NS']; P = CONTRACT['P']
HASHES = json.loads((HERE / 'compiler-artifact-hashes.json').read_text())
OWNER = 'org.apache.maven.plugins:maven-compiler-plugin:jar:3.14.0'
GOAL = 'org.apache.maven.plugins:maven-compiler-plugin:'


class Run:
    def __init__(self, source, sandbox, repository, jdk, maven, offline):
        self.source, self.sandbox, self.repository = source, sandbox, repository
        self.jdk, self.maven, self.offline = jdk, maven, offline
        self.projects = RELEASE['verify_source'](source)
        self.source_hashes = {str(p.relative_to(source)): digest(p) for p, _ in self.projects}
        for version in (5, 6):
            relative = f'integrations/ruoyi{version}-host-starter/overlay/ruoyi-extend/ruoyi-approval-host-starter/pom.xml'
            self.source_hashes[relative] = digest(source / relative)
        self.poms = {}; self.identity = (sandbox.stat().st_dev, sandbox.stat().st_ino)
        self.harness = {p.name: digest(p) for p in [Path(__file__), HERE / 'CompilerRealmObserver.java',
            HERE / 'CompilerSharedIoProbe.java', HERE / 'compiler-artifact-hashes.json', HERE / 'clean_live_support.py']}
        self.contract_hashes = {name: digest(source / 'scripts/ci' / name) for name in
            ['compiler_plugin_contract.py', 'clean_plugin_contract.py', 'verify-release-plugin-baseline.py']}
        for name in ['home', 'tmp', 'classes', 'parent']:
            inside(sandbox / name, sandbox).mkdir()
        self.settings = sandbox / 'empty-settings.xml'
        self.settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n')
        self.commands = Commands(sandbox, isolated_env(jdk, sandbox), 660)
        self.report = {'status': 'RUNNING', 'offline': offline, 'sandbox': str(sandbox),
            'sourcePomHashes': self.source_hashes, 'harnessSourceHashes': self.harness,
            'contractSourceHashes': self.contract_hashes,
            'cases': {}, 'commands': self.commands.results, 'scannerEvidence': False,
            'vulnerableApiReachabilityClaim': False, 'canonicalCiEvidence': False}
        self.parent()

    def write_pom(self, file, text):
        inside(file, self.sandbox).write_text(text)
        self.poms[file] = text

    def containment(self):
        require(not self.sandbox.is_symlink() and (self.sandbox.stat().st_dev, self.sandbox.stat().st_ino) == self.identity, 'sandbox identity drift')
        require(all(digest(self.source / p) == h for p, h in self.source_hashes.items()), 'source POM drift')
        require(all(digest(HERE / p) == h for p, h in self.harness.items()), 'harness drift')
        require(all(digest(self.source / 'scripts/ci' / p) == h for p, h in self.contract_hashes.items()), 'contract drift')
        if 'observerJarSha256' in self.report:
            require(digest(self.sandbox / 'observer.jar') == self.report['observerJarSha256'], 'observer artifact drift')
        require(all(not p.is_symlink() and p.read_text() == text for p, text in self.poms.items()), 'fixture POM drift')
        require(self.settings.read_text() == '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n', 'settings drift')
        count = 0
        for base, dirs, files in os.walk(self.sandbox, followlinks=False):
            require(len(Path(base).relative_to(self.sandbox).parts) <= 16, 'sandbox depth exceeded')
            for name in dirs + files:
                count += 1
                require(count <= 4096 and name != '.mvn', 'sandbox entry/bootstrap bound')
                inside(Path(base) / name, self.sandbox)

    def parent(self):
        source = self.projects[0][1]
        parent = ET.Element(P + 'project')
        for name, value in [('modelVersion', '4.0.0'), ('groupId', 'local.compiler.compatibility'),
                ('artifactId', 'compiler-parent'), ('version', '1'), ('packaging', 'pom')]:
            ET.SubElement(parent, P + name).text = value
        props = ET.SubElement(parent, P + 'properties')
        for name in ['java.version', 'maven.compiler.version', CONTRACT['PROP']]:
            props.append(copy.deepcopy(CONTRACT['one'](CONTRACT['one'](source, 'properties'), name)))
        plugins = ET.SubElement(ET.SubElement(ET.SubElement(parent, P + 'build'), P + 'pluginManagement'), P + 'plugins')
        selected = [p for p in source.findall('m:build/m:pluginManagement/m:plugins/m:plugin', NS) if CONTRACT['targeted'](p)]
        require(len(selected) == 1, 'source Compiler owner ambiguous')
        CONTRACT['verify_plugin'](selected[0]); plugins.append(copy.deepcopy(selected[0]))
        self.write_pom(self.sandbox / 'parent/pom.xml', ET.tostring(parent, encoding='unicode', default_namespace=NS['m']))

    def fixture(self, name, configuration=''):
        child = inside(self.sandbox / name, self.sandbox); child.mkdir()
        text = ('<project xmlns="' + NS['m'] + '"><modelVersion>4.0.0</modelVersion>'
            '<parent><groupId>local.compiler.compatibility</groupId><artifactId>compiler-parent</artifactId><version>1</version>'
            '<relativePath>../parent/pom.xml</relativePath></parent><artifactId>' + re.sub(r'[^a-z0-9-]', '-', name) + '</artifactId>'
            '<properties><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>'
            '<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>'
            + ('<configuration>' + configuration + '</configuration>' if configuration else '') + '</plugin></plugins></build></project>')
        self.write_pom(child / 'pom.xml', text); return child

    def put(self, child, path, text):
        file = inside(child / path, self.sandbox); file.parent.mkdir(parents=True, exist_ok=True); file.write_text(text); return file

    def run(self, label, command, seconds=30):
        self.containment(); status, log, file = self.commands.run(label, command, self.sandbox, seconds)
        require(status == 0, label + ' failed'); return log

    def artifacts(self, log):
        marker = '[DEBUG] Populating class realm plugin>org.apache.maven.plugins:maven-compiler-plugin:3.14.0'
        lines = log.splitlines(); starts = [i for i, line in enumerate(lines) if line.strip() == marker]
        require(len(starts) == 1, 'missing/duplicate actual Compiler realm')
        coords = []
        for line in lines[starts[0] + 1:]:
            match = re.fullmatch(r'\[DEBUG\]\s+Included: (\S+)\s*', line)
            if not match: break
            coords.append(match[1])
        expected = set(HASHES) - {'javax.inject:javax.inject:jar:1', 'org.slf4j:slf4j-api:jar:1.7.36'}
        require(len(coords) == len(set(coords)) and set(coords) == expected, 'actual Compiler realm membership differs: ' + str(coords))
        approved = {}
        for coordinate in coords:
            group, name, kind, version = coordinate.split(':')
            file = self.repository / group.replace('.', '/') / name / version / f'{name}-{version}.{kind}'
            require(file.is_file() and file.resolve().is_relative_to(self.repository), 'realm JAR escape/missing')
            require(digest(file) == HASHES[coordinate], 'realm JAR bytes drift: ' + coordinate)
            approved[coordinate] = {'path': str(file.resolve()), 'sha256': digest(file)}
        return approved

    def mvn(self, label, child, goal, failure=False, flags=()):
        require(goal in ('compile', 'testCompile'), 'only nondeploying Compiler goals admitted')
        self.containment()
        args = [str(self.maven), *(['-o'] if self.offline else []), '-B', '-ntp', '-s', str(self.settings), '-gs', str(self.settings),
            '-Dmaven.repo.local=' + str(self.repository), '-Dstyle.color=never', '-Dmaven.multiModuleProjectDirectory=' + str(self.sandbox),
            '-f', str(child / 'pom.xml'), '-N', '-X', GOAL + goal, *flags]
        opts = '-Xmx384m -XX:MaxMetaspaceSize=192m -Dorg.slf4j.simpleLogger.logFile=System.out -Duser.home=' + str(self.sandbox / 'home')
        opts += ' -javaagent:' + str(self.sandbox / 'observer.jar') + ' -Xlog:class+load=info:stderr'
        status, log, file = self.commands.run(label, args, self.sandbox, 120,
            {'MAVEN_BASEDIR': str(self.sandbox), 'MAVEN_OPTS': opts}, True)
        require(status == (1 if failure else 0), 'unexpected Compiler status for ' + label)
        require('--- compiler:3.14.0:' + goal in log, 'versionless goal did not inherit Compiler pin')
        approved = self.artifacts((self.sandbox / (label + '.stdout.log')).read_text())
        observations = []
        for line in log.splitlines():
            if line.startswith('COMPILER_ORIGIN\t'):
                _, name, loader, realm, location = line.split('\t')
                parsed = urlparse(location)
                require(parsed.scheme == 'file' and not parsed.netloc, 'nonlocal natural class origin')
                observations.append({'class': name, 'loader': loader, 'realm': realm, 'path': str(Path(unquote(parsed.path)).resolve())})
        mojo_name = 'org/apache/maven/plugin/compiler/' + ('CompilerMojo' if goal == 'compile' else 'TestCompilerMojo')
        mojos = [o for o in observations if o['class'] == mojo_name]
        require(len(mojos) == 1 and 'plugin>org.apache.maven.plugins:maven-compiler-plugin:3.14.0' in mojos[0]['realm'], 'natural Compiler mojo absent')
        own = [o for o in observations if o['loader'] == mojos[0]['loader']]
        required = {mojo_name, 'org/apache/maven/shared/incremental/IncrementalBuildHelper'}
        if label in ('initial-compile', 'changed-main-compile', 'added-source-compile', 'removed-source-compile'):
            required |= {'org/apache/maven/shared/utils/io/FileUtils', 'org/apache/maven/shared/utils/io/DirectoryScanner'}
        require(required <= {o['class'] for o in own}, 'required natural Compiler/incremental classes missing')
        jvm = set()
        for match in re.finditer(r'^\[[^\n]*\]\[class,load\] (\S+) source: (\S+)\s*$', log, re.M):
            parsed = urlparse(match[2])
            if parsed.scheme == 'file' and not parsed.netloc:
                jvm.add((match[1], str(Path(unquote(parsed.path)).resolve())))
        for item in own:
            require(item['path'] in [v['path'] for v in approved.values()], 'natural class originated outside approved realm')
            require((item['class'].replace('/', '.'), item['path']) in jvm, 'JVM origin corroboration missing')
        require(not re.search(r'\[class,load\]', (self.sandbox / (label + '.stdout.log')).read_text()), 'class and Maven log channels overlap')
        self.report['cases'][label] = {'status': 'PASSED', 'exitStatus': status, 'expectedFailure': failure,
            'realmArtifacts': approved, 'naturalOrigins': own, 'logSha256': digest(file),
            'commonsIoNaturallyObserved': any(o['class'].startswith('org/apache/commons/io/') for o in own),
            'absenceIsNotNonReachabilityEvidence': True}
        self.containment(); return log

    def execute(self):
        verify_logging_channels((self.maven.parent.parent / 'conf/logging/simplelogger.properties').read_text())
        version = self.run('maven-version', [str(self.maven), '--version'])
        require('Apache Maven 3.9.16' in version and 'Java version: 21.' in version, 'Maven/JDK pin drift')
        require(re.search(r'javac 21\.', self.run('javac-version', [str(self.jdk / 'bin/javac'), '-version'])), 'JDK compiler pin drift')
        model_file = self.sandbox / 'effective-pom.xml'
        # Captures every current reactor model without invoking its lifecycle.
        model_args = [str(self.maven), *(['-o'] if self.offline else []), '-B', '-ntp', '-s', str(self.settings), '-gs', str(self.settings),
            '-Dmaven.repo.local=' + str(self.repository), '-Dstyle.color=never', '-Dmaven.multiModuleProjectDirectory=' + str(self.source),
            '-f', str(self.source / 'pom.xml'), 'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom', '-Doutput=' + str(model_file)]
        status, _, _ = self.commands.run('effective-models', model_args, self.sandbox, 300,
            {'MAVEN_BASEDIR': str(self.sandbox), 'MAVEN_OPTS': '-Xmx384m -Duser.home=' + str(self.sandbox / 'home')})
        require(status == 0, 'actual Compiler model capture failed')
        RELEASE['verify_effective'](model_file, self.projects)
        run_path(str(self.source / 'scripts/ci/clean_plugin_contract.py'))['verify_effective'](model_file, self.projects)
        self.report['modelCount'] = CONTRACT['verify_effective'](model_file)
        self.report['effectiveModelSha256'] = digest(model_file)
        self.run('compile-observer', [str(self.jdk / 'bin/javac'), '-J-Xmx96m', '--release', '21', '-d', str(self.sandbox / 'classes'), str(HERE / 'CompilerRealmObserver.java')])
        with zipfile.ZipFile(self.sandbox / 'observer.jar', 'x') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: CompilerRealmObserver\n\n')
            for file in sorted((self.sandbox / 'classes').glob('CompilerRealmObserver*.class')): jar.write(file, file.name)
        self.report['observerJarSha256'] = digest(self.sandbox / 'observer.jar')
        child = self.fixture('incremental café paths')
        api = self.put(child, 'src/main/java/fixture/Api.java', 'package fixture; public record Api(String value) { public static String message() { return "first café"; } }')
        self.put(child, 'src/main/java/fixture/Main.java', 'package fixture; public class Main { public static void main(String[] args) throws Exception { if (!Api.message().equals(args[0])) throw new AssertionError(Api.message()); if (!Main.class.getMethod("main", String[].class).getParameters()[0].getName().equals("args")) throw new AssertionError("parameter names missing"); } }')
        test = self.put(child, 'src/test/java/fixture/TestMain.java', 'package fixture; public class TestMain { public static void main(String[] args) { if (!Api.message().equals(args[0])) throw new AssertionError(Api.message()); System.out.println("test-one"); } }')
        self.mvn('initial-compile', child, 'compile')
        output = child / 'target/classes/fixture/Api.class'; first_hash = digest(output); first_mtime = output.stat().st_mtime_ns
        require(output.read_bytes()[6:8] == b'\x00A', 'Java 21 class version absent')
        noop = self.mvn('unchanged-compile', child, 'compile')
        require('Nothing to compile' in noop and output.stat().st_mtime_ns == first_mtime, 'unchanged compile rebuilt output')
        self.mvn('initial-test-compile', child, 'testCompile')
        test_class = child / 'target/test-classes/fixture/TestMain.class'
        test_mtime = test_class.stat().st_mtime_ns
        require('Nothing to compile' in self.mvn('unchanged-test-compile', child, 'testCompile')
                and test_class.stat().st_mtime_ns == test_mtime, 'unchanged test compile rebuilt output')
        cp = str(child / 'target/classes') + os.pathsep + str(child / 'target/test-classes')
        self.run('run-initial-tests', [str(self.jdk / 'bin/java'), '-Xmx96m', '-cp', cp, 'fixture.TestMain', 'first café'])
        # Explicit newer fixture timestamps avoid sleeps and filesystem clock granularity.
        api.write_text(api.read_text().replace('first café', 'second café'))
        os.utime(api, ns=(output.stat().st_mtime_ns + 3_000_000_000,) * 2)
        self.mvn('changed-main-compile', child, 'compile')
        require(digest(output) != first_hash, 'changed main bytecode did not rebuild')
        self.run('run-changed-main', [str(self.jdk / 'bin/java'), '-Xmx96m', '-cp', cp, 'fixture.Main', 'second café'])
        self.mvn('changed-main-test-compile', child, 'testCompile')
        self.run('run-dependent-tests', [str(self.jdk / 'bin/java'), '-Xmx96m', '-cp', cp, 'fixture.TestMain', 'second café'])
        test_output = child / 'target/test-classes/fixture/TestMain.class'; test_hash = digest(test_output)
        test.write_text(test.read_text().replace('test-one', 'test-two'))
        os.utime(test, ns=(test_output.stat().st_mtime_ns + 3_000_000_000,) * 2)
        self.mvn('changed-test-compile', child, 'testCompile')
        require(digest(test_output) != test_hash, 'changed test bytecode did not rebuild')
        require('test-two' in self.run('run-changed-tests', [str(self.jdk / 'bin/java'), '-Xmx96m', '-cp', cp, 'fixture.TestMain', 'second café']), 'changed test behavior missing')
        require((child / 'target/maven-status/maven-compiler-plugin/compile/default-cli/inputFiles.lst').is_file(), 'incremental input cache absent')
        require((child / 'target/maven-status/maven-compiler-plugin/compile/default-cli/createdFiles.lst').is_file(), 'incremental output cache absent')
        extra = self.put(child, 'src/main/java/fixture/Extra.java', 'package fixture; public class Extra { public static class Nested {} }')
        self.mvn('added-source-compile', child, 'compile')
        require((child / 'target/classes/fixture/Extra.class').is_file()
                and (child / 'target/classes/fixture/Extra$Nested.class').is_file(), 'added source/nested output missing')
        extra.unlink()
        self.mvn('removed-source-compile', child, 'compile')
        require(not (child / 'target/classes/fixture/Extra.class').exists()
                and not (child / 'target/classes/fixture/Extra$Nested.class').exists(), 'removed source left stale tracked classes')
        broken = self.fixture('invalid-source')
        self.put(broken, 'src/main/java/Invalid.java', 'public class Invalid { this is not Java; }')
        require('COMPILATION ERROR' in self.mvn('invalid-source', broken, 'compile', failure=True), 'expected compiler diagnostic absent')
        self.put(child, 'src/test/java/fixture/BrokenTest.java', 'package fixture; public class BrokenTest { not valid; }')
        require('COMPILATION ERROR' in self.mvn('invalid-test-source', child, 'testCompile', failure=True), 'expected test compiler diagnostic absent')
        self.annotation_processing()
        self.api_probe()
        self.report.update(status='PASSED', modelSourceCount=len(self.source_hashes), coverageGaps=[])

    def annotation_processing(self):
        processor = self.sandbox / 'processor'; processor.mkdir()
        source = self.put(processor, 'FixtureProcessor.java', '''import java.io.*;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.*;
import javax.lang.model.element.*;
@SupportedAnnotationTypes("*") @SupportedSourceVersion(SourceVersion.RELEASE_21)
public class FixtureProcessor extends AbstractProcessor {
 private boolean generated;
 public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
  if (!generated && !round.processingOver()) { generated=true;
   try (Writer out=processingEnv.getFiler().createSourceFile("generated.Generated").openWriter()) {
    out.write("package generated; public class Generated { public static String message() { return \\\"generated café\\\"; } }");
   } catch(IOException e) { throw new UncheckedIOException(e); }
  } return false;
 }
}''')
        self.run('compile-processor', [str(self.jdk / 'bin/javac'), '-J-Xmx96m', '-proc:none', '--release', '21', '-d', str(processor), str(source)])
        child = self.fixture('annotation-processing', '<proc>full</proc><annotationProcessors><annotationProcessor>FixtureProcessor</annotationProcessor></annotationProcessors>'
            '<compilerArgs><arg>-processorpath</arg><arg>' + str(processor) + '</arg></compilerArgs>')
        self.put(child, 'src/main/java/fixture/GeneratedMain.java', 'package fixture; public class GeneratedMain { public static void main(String[] args) { if (!generated.Generated.message().equals("generated café")) throw new AssertionError(); } }')
        self.mvn('annotation-compile', child, 'compile')
        require((child / 'target/generated-sources/annotations/generated/Generated.java').is_file(), 'processor generated source missing')
        self.run('run-generated-main', [str(self.jdk / 'bin/java'), '-Xmx96m', '-cp', str(child / 'target/classes'), 'fixture.GeneratedMain'])
        test_child = self.fixture('annotation-test-processing', '<proc>full</proc><annotationProcessors><annotationProcessor>FixtureProcessor</annotationProcessor></annotationProcessors>'
            '<compilerArgs><arg>-processorpath</arg><arg>' + str(processor) + '</arg></compilerArgs>')
        self.put(test_child, 'src/test/java/fixture/GeneratedTest.java', 'package fixture; public class GeneratedTest { public static void main(String[] args) { if (!generated.Generated.message().equals("generated café")) throw new AssertionError(); } }')
        self.mvn('annotation-test-compile', test_child, 'testCompile')
        require((test_child / 'target/generated-test-sources/test-annotations/generated/Generated.java').is_file(), 'processor generated test source missing')
        self.run('run-generated-test', [str(self.jdk / 'bin/java'), '-Xmx96m', '-cp', str(test_child / 'target/test-classes'), 'fixture.GeneratedTest'])

    def api_probe(self):
        approved = self.report['cases']['initial-compile']['realmArtifacts']
        paths = [v['path'] for v in approved.values()]
        self.run('compile-api-probe', [str(self.jdk / 'bin/javac'), '-J-Xmx96m', '--release', '21', '-d', str(self.sandbox / 'classes'), str(HERE / 'CompilerSharedIoProbe.java')])
        log = self.run('run-api-probe', [str(self.jdk / 'bin/java'), '-Xmx96m', '-cp', str(self.sandbox / 'classes'), 'CompilerSharedIoProbe',
            str(self.sandbox / 'api-files'), str(HERE / 'compiler-artifact-hashes.json'), *paths])
        require('COMPILER_API_COMPLETE' in log, 'API probe did not complete')
        self.report['apiProbe'] = {'status': 'PASSED', 'assertions': len(re.findall(r'^PASS ', log, re.M)),
            'goalReachabilityClaim': False, 'observedRealmLogSha256': self.report['cases']['initial-compile']['logSha256'],
            'logSha256': digest(self.sandbox / 'run-api-probe.log')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true'); parser.add_argument('--source', type=Path, default=ROOT)
    parser.add_argument('--repository', type=Path); parser.add_argument('--evidence-directory', type=Path)
    args = parser.parse_args()
    if not args.execute:
        print(json.dumps({'execution': 'NOT_RUN', 'goals': [GOAL + 'compile', GOAL + 'testCompile'], 'offlineLocally': True,
            'coverage': ['main/test compilation', 'unchanged incremental', 'changed main and test', 'compile failure', 'annotation processing', 'observed-realm Shared Utils IO APIs']})); return 0
    ci = os.environ.get('GITHUB_ACTIONS') == 'true'
    require(ci or os.environ.get('COMPILER_PLUGIN_COMPATIBILITY') == 'true', 'live Compiler tests are not enabled')
    require(ci or args.repository is not None, 'local Compiler tests require explicit offline repository')
    source = args.source.resolve(); repository = (args.repository or Path.home() / '.m2/repository').resolve()
    require(not source.is_relative_to(repository) and not repository.is_relative_to(source), 'source/repository overlap')
    jdk = Path(os.environ.get('JAVA_HOME', '')).resolve(); maven_path = shutil.which('mvn')
    require(maven_path is not None and (jdk / 'bin/javac').is_file(), 'explicit JDK and Maven required')
    base = args.evidence_directory.resolve() if args.evidence_directory else Path('/tmp')
    require(base.is_dir() and not base.is_relative_to(source) and not base.is_relative_to(repository), 'evidence directory unsafe')
    sandbox = Path(tempfile.mkdtemp(prefix='compiler-live-', dir=base)).resolve()
    for signum in (signal.SIGTERM, signal.SIGINT):
        signal.signal(signum, lambda signum, frame: (_ for _ in ()).throw(RuntimeError('interrupted signal ' + str(signum))))
    run = None
    try:
        run = Run(source, sandbox, repository, jdk, Path(maven_path).resolve(), not ci)
        run.execute(); run.containment()
    except BaseException as exc:
        report = run.report if run else {'sandbox': str(sandbox), 'scannerEvidence': False, 'canonicalCiEvidence': False}
        report.update(status='FAILED', errorType=type(exc).__name__, error=str(exc))
        (sandbox / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
        print('COMPILER_LIVE_RESULT ' + json.dumps(report), flush=True); return 1
    run.report['evidenceRetained'] = True
    (sandbox / 'result.json').write_text(json.dumps(run.report, indent=2) + '\n')
    print('COMPILER_LIVE_RESULT ' + json.dumps(run.report), flush=True); return 0


if __name__ == '__main__':
    sys.exit(main())
