#!/usr/bin/env python3
"""Fresh bounded compatibility source. Plan only unless explicitly enabled.

Actual model capture and disposable inherited Checkstyle check/report goals are
separate from scanners, publishing and repository lifecycle execution.
"""
import argparse
from html.parser import HTMLParser
import json
import os
from pathlib import Path
from runpy import run_path
import re
import shutil
import signal
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

# -I -S disables ambient import and site hooks; add only the reviewed sibling.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from checkstyle_live_support import (HERE, ROOT, NS, CASES, GOAL, REPORT_GOAL, MODEL_GOAL, PREPARE_GOAL, CENTRAL_SETTINGS, REFERENCE_HASH,
    Commands, cached_artifacts, cached_report_skin, digest, execution_mode, inside, isolated_env, load_contract,
    logging_import, maven_command, natural_origins, parent_xml, preparation_command, preparation_xml, prepare_repository,
    realm_artifacts, realm_imports, require, source_inputs, transition_spec)

BOOT = run_path(str(ROOT / 'scripts/ci/boot_http_contract.py'))
UTILITIES = run_path(str(ROOT / 'scripts/ci/boot_utilities_contract.py'))
PAIR = run_path(str(ROOT / 'scripts/ci/jacoco_antrun_contract.py'))
JAR_SOURCE_ADAPTER = ROOT / 'scripts/ci/jar_plugin_retained_source.py'
JAR_RETAINED = run_path(str(JAR_SOURCE_ADAPTER))

GOOD = 'package fixture;\npublic class Good { String value = "café"; }\n'
TEST_GOOD = 'package fixture;\npublic class TestGood { int value = 1; }\n'
FAILURES = {
    'missing-newline': ('package fixture;\npublic class Bad {}', 'NewlineAtEndOfFile'),
    'tabs': ('package fixture;\npublic class Bad {\n\tint value;\n}\n', 'FileTabCharacter'),
    'wildcard-import': ('package fixture;\nimport java.util.*;\npublic class Bad { List<String> value; }\n', 'AvoidStarImport'),
    'unused-import': ('package fixture;\nimport java.util.List;\npublic class Bad {}\n', 'UnusedImports'),
    'illegal-import': ('package fixture;\nimport sun.misc.Unsafe;\npublic class Bad { Unsafe value; }\n', 'IllegalImport'),
}
SETTINGS = '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n'
FIXTURE_NAMES = ('run_checkstyle_live_compatibility.py', 'checkstyle_live_support.py',
                 'CheckstyleRealmObserver.java', 'CheckstyleLangProbe.java', 'CheckstyleGuavaProbe.java')
CONTRACT_INPUTS = ('scripts/ci/checkstyle_plugin_contract.py', 'scripts/ci/verify-release-plugin-baseline.py',
                   'scripts/ci/compiler_plugin_contract.py', 'scripts/ci/clean_plugin_contract.py',
                   'docs/operations/compiler-plugin-evidence/source-witness.json')


class ReportRows(HTMLParser):
    def __init__(self):
        super().__init__(); self.rows, self.row, self.cell = [], None, None

    def handle_starttag(self, tag, attrs):
        if tag == 'tr':
            self.row = []
        elif tag in ('td', 'th') and self.row is not None:
            self.cell = []

    def handle_data(self, data):
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if tag in ('td', 'th') and self.cell is not None:
            self.row.append(' '.join(''.join(self.cell).split())); self.cell = None
        elif tag == 'tr' and self.row is not None:
            self.rows.append(self.row); self.row = None


class Run:
    def __init__(self, source, sandbox, repository, jdk, maven, offline, stage, baseline, transition='lang'):
        self.source, self.sandbox, self.repository = source, sandbox, repository
        self.jdk, self.maven, self.offline, self.stage, self.baseline = jdk, maven, offline, stage, baseline
        self.transition, self.spec = transition, transition_spec(transition)
        self.jar_source_projection = None
        self.jar_adapter_hash = digest(JAR_SOURCE_ADAPTER)
        if JAR_RETAINED['is_jar_source'](source):
            retained = inside(sandbox / 'accepted-assembly-source', sandbox)
            self.jar_source_projection = JAR_RETAINED['materialize_accepted_source'](source, retained)
            source = retained; self.source = source
        self.pair_source_projection = None
        if PAIR['is_pair_source'](source):
            retained = inside(sandbox / 'accepted-pair-predecessor', sandbox)
            self.pair_source_projection = PAIR['materialize_retained_source'](source, retained)
            source = retained; self.source = source
        self.utilities_source_projection = None
        if UTILITIES['is_utilities_source'](source):
            retained = inside(sandbox / 'accepted-utilities-predecessor', sandbox)
            self.utilities_source_projection = UTILITIES['materialize_retained_source'](source, retained)
            source = retained; self.source = source
        self.boot_source_projection = None
        if BOOT['is_boot_source'](source):
            retained = inside(sandbox / 'accepted-boot-predecessor', sandbox)
            self.boot_source_projection = BOOT['materialize_retained_source'](source, retained)
            source = retained; self.source = source
        self.fixed, self.observer_hash = {}, None
        self.supplied_source, self.supplied_hashes = source, {}
        contract_inputs = CONTRACT_INPUTS
        root_pom = source / 'pom.xml'
        require(root_pom.is_file() and root_pom.stat().st_size < 1024 * 1024, 'bounded source root POM required')
        has_guava = ET.parse(root_pom).getroot().find('m:properties/m:maven.checkstyle.guava.version', NS) is not None
        if transition == 'guava' or has_guava:
            contract_inputs += ('scripts/ci/checkstyle_guava_contract.py',)
        if transition == 'lang' and has_guava:
            guava_contract = load_contract(source, 'guava')
            _, _, input_hashes = source_inputs(source, guava_contract)
            self.supplied_hashes = input_hashes | {p: digest(source / p) for p in contract_inputs}
            historical = inside(sandbox / 'accepted-lang-source', sandbox); historical.mkdir()
            for relative in self.supplied_hashes:
                data = (source / relative).read_bytes()
                if relative == 'pom.xml':
                    data = guava_contract['reverse_source'](data)
                target = inside(historical / relative, sandbox); target.parent.mkdir(parents=True, exist_ok=True)
                self.put_fixed(target, data)
            source = historical; self.source = source
        self.contract_hashes = {p: digest(source / p) for p in contract_inputs}
        self.contract = load_contract(source, transition)
        root, plugin, self.source_hashes = source_inputs(source, self.contract)
        if baseline:
            root = self.contract['before_checkstyle'](root)
            plugin = [p for p in root.findall('m:build/m:plugins/m:plugin', NS) if self.contract['targeted'](p)][0]
        self.identity = sandbox.stat().st_dev, sandbox.stat().st_ino
        self.harness_hashes = {name: digest(HERE / name) for name in FIXTURE_NAMES}
        for name in ('home', 'tmp', 'classes', 'parent', 'config/checkstyle'):
            inside(sandbox / name, sandbox).mkdir(parents=True)
        self.put_fixed(sandbox / 'empty-settings.xml', SETTINGS)
        self.put_fixed(sandbox / 'config/checkstyle/checkstyle.xml', (source / 'config/checkstyle/checkstyle.xml').read_text())
        self.put_fixed(sandbox / 'parent/pom.xml', parent_xml(root, plugin, baseline, transition))
        self.commands = Commands(sandbox, isolated_env(jdk, sandbox), 660 if stage == 'models' else 720)
        self.report = {'status': 'RUNNING', 'stage': stage, 'variant': 'baseline' if baseline else 'candidate',
            'offline': offline, 'sandbox': str(sandbox), 'transition': transition, 'sourceHashes': self.source_hashes,
            'suppliedSourceHashes': self.supplied_hashes, 'historicalLangMaterialized': bool(self.supplied_hashes),
            'historicalJarSourceProjection': self.jar_source_projection,
            'historicalJarSourceAdapterSha256': self.jar_adapter_hash,
            'pairSourceProjection': self.pair_source_projection,
            'utilitiesSourceProjection': self.utilities_source_projection,
            'historicalUtilitiesMaterialized': bool(self.utilities_source_projection),
            'bootSourceProjection': self.boot_source_projection,
            'historicalBootMaterialized': bool(self.boot_source_projection),
            'harnessSourceHashes': self.harness_hashes, 'contractSourceHashes': self.contract_hashes,
            'commands': self.commands.results, 'cases': {}, 'coverageGaps': [],
            'scannerEvidence': False, 'canonicalCiEvidence': False, 'vulnerableApiReachabilityClaim': False}

    def put_fixed(self, file, text):
        data = text if isinstance(text, bytes) else text.encode('utf8')
        inside(file, self.sandbox).write_bytes(data); self.fixed[file] = data

    def containment(self):
        require(digest(JAR_SOURCE_ADAPTER) == self.jar_adapter_hash, 'JAR historical source adapter drift')
        if self.jar_source_projection:
            JAR_RETAINED['verify_projection'](self.jar_source_projection)
        if self.pair_source_projection:
            PAIR['verify_projection'](self.pair_source_projection)
        if self.utilities_source_projection:
            UTILITIES['verify_projection'](self.utilities_source_projection)
        if self.boot_source_projection:
            BOOT['verify_projection'](self.boot_source_projection)
        require(not self.sandbox.is_symlink() and self.identity == (self.sandbox.stat().st_dev, self.sandbox.stat().st_ino), 'sandbox identity drift')
        require(all(digest(self.source / p) == h for p, h in self.source_hashes.items()), 'source drift')
        require(all(digest(self.supplied_source / p) == h for p, h in self.supplied_hashes.items()), 'supplied source drift')
        require(all(digest(self.source / p) == h for p, h in self.contract_hashes.items()), 'contract drift')
        require(all(digest(HERE / p) == h for p, h in self.harness_hashes.items()), 'harness drift')
        require(all(not p.is_symlink() and p.read_bytes() == t for p, t in self.fixed.items()), 'fixed fixture/settings/rules drift')
        if self.observer_hash:
            require(digest(self.sandbox / 'observer.jar') == self.observer_hash, 'observer bytes drift')
        count = 0
        for base, dirs, files in os.walk(self.sandbox, followlinks=False):
            require(len(Path(base).relative_to(self.sandbox).parts) <= 16, 'sandbox depth exceeded')
            for name in dirs + files:
                count += 1
                require(count <= 4096 and name != '.mvn', 'sandbox entry/bootstrap bound')
                inside(Path(base) / name, self.sandbox)

    def run(self, label, command, seconds=30, extra_env=None, expected=0):
        self.containment()
        result = self.commands.run(label, command, seconds, extra_env)
        require(result[0] == expected, 'unexpected exit status: ' + label)
        self.containment()
        return result

    def toolchain(self):
        configuration = self.maven.parent.parent / 'conf/logging/simplelogger.properties'
        require(configuration.is_file() and re.search(r'^org\.slf4j\.simpleLogger\.logFile=System\.out\s*$', configuration.read_text(), re.M), 'Maven stdout logging required')
        env = {'MAVEN_BASEDIR': str(self.sandbox), 'MAVEN_OPTS': '-Xmx384m -Duser.home=' + str(self.sandbox / 'home')}
        _, out, err = self.run('maven-version', [str(self.maven), '--version'], extra_env=env)
        require('Apache Maven 3.9.16' in out + err and 'Java version: 21.' in out + err, 'actual Maven 3.9.16 / JDK21 required')
        _, out, err = self.run('javac-version', [str(self.jdk / 'bin/javac'), '-version'])
        require(re.search(r'^javac 21\.', out + err, re.M), 'actual javac21 required')
        self.report['toolchain'] = {'maven': '3.9.16', 'javaMajor': 21,
            'launcherSha256': digest(self.maven), 'javaSha256': digest(self.jdk / 'bin/java'),
            'javacSha256': digest(self.jdk / 'bin/javac'), 'loggingSha256': digest(configuration)}

    def models(self):
        baseline_root = inside(self.sandbox / 'accepted-baseline', self.sandbox); baseline_root.mkdir()
        baseline_hashes = {}
        for relative in self.source_hashes:
            data = (self.source / relative).read_bytes()
            if relative == 'pom.xml':
                data = self.contract['reverse_source'](data)
            file = inside(baseline_root / relative, self.sandbox); file.parent.mkdir(parents=True, exist_ok=True)
            self.put_fixed(file, data); baseline_hashes[relative] = digest(file)
        baseline = inside(self.sandbox / 'baseline-effective-pom.xml', self.sandbox)
        candidate = inside(self.sandbox / 'effective-pom.xml', self.sandbox)
        for label, root, output in [('baseline-effective-models', baseline_root, baseline),
                                     ('candidate-effective-models', self.source, candidate)]:
            self.run(label, maven_command(self.maven, self.sandbox, self.repository, self.offline,
                'models', root / 'pom.xml', model_output=output), 300, {'MAVEN_BASEDIR': str(self.sandbox),
                'MAVEN_OPTS': '-Xmx384m -Duser.home=' + str(self.sandbox / 'home')})
            require(output.is_file() and output.stat().st_size <= 16 * 1024 * 1024, 'bounded effective model required')
        count = self.contract['verify_effective'](self.source, candidate, baseline=baseline, baseline_root=baseline_root)
        self.report.update(status='PASSED', modelCount=count, effectiveModelSha256=digest(candidate),
            baselineEffectiveModelSha256=digest(baseline), baselineSourceHashes=baseline_hashes,
            scope='two actual model captures; baseline source is exact accepted root reversal plus unchanged POMs/rules')

    def prepare_artifacts(self):
        preparation = {'mode': 'offline-existing-cache' if self.offline else 'ci-central-dependency-resolution',
                       'acceptanceGoal': False, 'status': 'RUNNING', 'resolutionCommandsRun': 0}
        self.report['cachePreparation'] = preparation
        # Existing mismatches fail before resolution. Only absent files may be
        # provisioned; a warm valid cache needs no network preparation.
        present = cached_artifacts(self.repository, True, allow_missing=not self.offline, transition=self.transition)
        present |= cached_artifacts(self.repository, False, allow_missing=not self.offline, transition=self.transition)
        skin = cached_report_skin(self.repository, allow_missing=not self.offline)
        preparation.update(initialOwnerArtifactCount=len(present), initialReportResourceCount=len(skin))
        if not self.offline and (len(present) != self.spec['artifactUnionCount'] or len(skin) != 1):
            self.put_fixed(self.sandbox / 'central-settings.xml', CENTRAL_SETTINGS)
            preparation.update(goal=PREPARE_GOAL, projectSha256={}, settingsSha256=digest(self.sandbox / 'central-settings.xml'))
            for baseline, label in [(True, 'baseline'), (False, 'extras')]:
                project = self.sandbox / ('cache-preparation-' + label + '.xml')
                self.put_fixed(project, preparation_xml(baseline, self.transition))
                preparation['projectSha256'][label] = digest(project)
                self.run('prepare-artifacts-' + label, preparation_command(self.maven, self.sandbox,
                    self.repository, self.offline, baseline), 120, {'MAVEN_BASEDIR': str(self.sandbox),
                    'MAVEN_OPTS': '-Xmx384m -XX:MaxMetaspaceSize=192m -Duser.home=' + str(self.sandbox / 'home')})
                preparation['resolutionCommandsRun'] += 1
        # Preparation is never an acceptance goal. Both variants must already
        # have exact bytes before any actual model, Checkstyle goal or replay.
        union = cached_artifacts(self.repository, True, transition=self.transition) | cached_artifacts(self.repository, False, transition=self.transition)
        require(len(union) == self.spec['artifactUnionCount'], 'exact verified cached artifact union required before acceptance')
        preparation.update(status='VERIFIED', artifacts=union, reportResources=cached_report_skin(self.repository))

    def fixture(self, name, invalid=False):
        child = inside(self.sandbox / name, self.sandbox); child.mkdir()
        extra = ('<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>'
            '<artifactId>maven-checkstyle-plugin</artifactId><configuration>'
            '<configLocation>${project.basedir}/invalid-checkstyle.xml</configLocation>'
            '</configuration></plugin></plugins></build>') if invalid else ''
        self.put_fixed(child / 'pom.xml', '<project xmlns="' + NS['m'] + '"><modelVersion>4.0.0</modelVersion>'
            '<parent><groupId>local.checkstyle.compatibility</groupId><artifactId>checkstyle-parent</artifactId>'
            '<version>1</version><relativePath>../parent/pom.xml</relativePath></parent><artifactId>'
            + re.sub('[^a-z0-9-]', '-', name) + '</artifactId>' + extra + '</project>')
        if invalid:
            self.put_fixed(child / 'invalid-checkstyle.xml', '<?xml version="1.0"?>\n'
                '<!DOCTYPE module PUBLIC "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN" '
                '"https://checkstyle.org/dtds/configuration_1_3.dtd">\n'
                '<module name="Checker"><module name="NoSuchFixtureCheck"/></module>\n')
        return child

    def put_source(self, child, relative, text):
        file = inside(child / relative, self.sandbox); file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text); return file

    def check(self, label, child, processed, rule=None, invalid=False, report_goal=False, selected=None):
        require(label in CASES, 'known case required')
        selected = processed if selected is None else selected
        for file in selected:
            require(inside(file, self.sandbox).is_file() and file.stat().st_size <= 65536, 'bounded fixture source required')
        hashes = {str(file.relative_to(child)): digest(file) for file in selected}
        artifacts = cached_artifacts(self.repository, self.baseline, transition=self.transition)
        options = ('-Xmx384m -XX:MaxMetaspaceSize=192m -Dorg.slf4j.simpleLogger.logFile=System.out -Duser.home='
            + str(self.sandbox / 'home') + ' -javaagent:' + str(self.sandbox / 'observer.jar') + ' -Xlog:class+load=info:stderr')
        expected = 1 if invalid or (rule and not report_goal) else 0
        status, stdout, stderr = self.run(label, maven_command(self.maven, self.sandbox, self.repository, self.offline,
            'report' if report_goal else 'fixtures', child / 'pom.xml'), 120,
            {'MAVEN_BASEDIR': str(self.sandbox), 'MAVEN_OPTS': options}, expected)
        require(all(digest(child / p) == h for p, h in hashes.items()), 'source changed during goal')
        marker = '--- checkstyle:3.6.0:checkstyle (default-cli)' if report_goal else '--- checkstyle:3.6.0:check (checkstyle)'
        require(marker in stdout, 'versionless goal failed source inheritance')
        approved = realm_artifacts(stdout, artifacts)
        origins = natural_origins(stdout, stderr, approved, early_failure=invalid, report=report_goal,
            guava_discovery=self.transition == 'guava' and invalid)
        record = {'status': 'PASSED', 'exitStatus': status, 'expectedFailure': bool(expected), 'fixtureSourceHashes': hashes,
            'realmArtifacts': approved, 'naturalOrigins': origins, 'actualForeignImports': realm_imports(stdout),
            'loggingImport': logging_import(origins, self.maven), 'stdoutSha256': digest(self.sandbox / (label + '.stdout.log')),
            'classLoadLogSha256': digest(self.sandbox / (label + '.stderr.log'))}
        if invalid:
            require('NoSuchFixtureCheck' in stdout and ('Unable to' in stdout or 'cannot initialize' in stdout), 'specific configuration failure absent')
            record['invalidConfigurationDetected'] = True
            if self.transition == 'guava':
                record['guavaDiscovery'] = {'status': 'EXPECTED_MISSING_MODULE_FAILURE',
                    'successfulCustomModuleNaturalGoalClaim': False, 'actualSelectedGuavaOriginsRequired': True}
        else:
            output = inside(child / 'target/checkstyle-result.xml', self.sandbox)
            require(output.is_file() and output.stat().st_size <= 1024 * 1024, 'bounded actual XML required')
            xml = ET.parse(output).getroot()
            require(xml.tag == 'checkstyle' and xml.attrib.get('version') == '9.3', 'actual engine version drift')
            entries = xml.findall('file')
            observed = {str(Path(f.attrib['name']).resolve()): f for f in entries}
            require(len(observed) == len(entries) and set(observed) == {str(f.resolve()) for f in processed}, 'actual processed file coverage differs')
            errors = [(name, error) for name, file in observed.items() for error in file.findall('error')]
            if rule:
                require(len(errors) == 1 and errors[0][1].attrib.get('severity') == 'error'
                    and errors[0][1].attrib.get('source', '').endswith('.' + rule + 'Check'), 'exact violation missing/extra errors')
                require(rule in stdout and Path(errors[0][0]).name in stdout, 'console rule/file diagnostic absent')
            else:
                require(not errors and (report_goal or 'You have 0 Checkstyle violations.' in stdout), 'passing diagnostics absent')
            record.update(errorCount=len(errors), expectedRule=rule, reportSha256=digest(output),
                processedSourceFiles=sorted(observed), expectedSelectedSourceFiles=sorted(str(f.resolve()) for f in selected))
            if report_goal:
                html = inside(child / 'target/reports/checkstyle.html', self.sandbox)
                require(html.is_file() and html.stat().st_size <= 2 * 1024 * 1024, 'bounded actual HTML required')
                content = html.read_text(); parsed = ReportRows(); parsed.feed(content)
                headers = [i for i, row in enumerate(parsed.rows) if len(row) == 4 and row[0] == 'Files' and row[-1] == 'Errors']
                require(len(headers) == 1 and headers[0] + 1 < len(parsed.rows), 'HTML summary absent/ambiguous')
                summary = parsed.rows[headers[0] + 1]
                require(len(summary) == 4 and summary[-1] == str(len(errors)), 'HTML/XML error summary differs')
                require(not rule or (rule in content and Path(errors[0][0]).name in content), 'HTML violation absent')
                record.update(htmlSummary=summary, htmlSha256=digest(html), reportGoalContract='default failsOnError=false permits violation exit 0')
        self.report['cases'][label] = record

    def fixtures(self):
        self.run('compile-observer', [str(self.jdk / 'bin/javac'), '-J-Xmx96m', '-proc:none', '--release', '21',
            '-d', str(self.sandbox / 'classes'), str(HERE / 'CheckstyleRealmObserver.java')])
        with zipfile.ZipFile(self.sandbox / 'observer.jar', 'x') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: CheckstyleRealmObserver\n\n')
            for file in sorted((self.sandbox / 'classes').glob('CheckstyleRealmObserver*.class')):
                jar.write(file, file.name)
        self.observer_hash = digest(self.sandbox / 'observer.jar'); self.report['observerJarSha256'] = self.observer_hash
        child = self.fixture('passing café path')
        main = self.put_source(child, 'src/main/java/fixture/Good.java', GOOD)
        test = self.put_source(child, 'src/test/java/fixture/TestGood.java', TEST_GOOD)
        self.check('passing', child, [main, test])
        cache = inside(child / 'target/checkstyle-cachefile', self.sandbox)
        require(cache.is_file() and cache.stat().st_size < 1024 * 1024, 'actual cache absent')
        initial = digest(cache)
        # Engine9.3 skips cached inputs before XML fileStarted/fileFinished.
        self.check('unchanged-cache', child, [], selected=[main, test])
        timestamp = main.stat().st_mtime_ns
        main.write_text(GOOD.rstrip('\n')); os.utime(main, ns=(timestamp, timestamp))
        self.check('same-timestamp-cache', child, [], selected=[main, test])
        os.utime(main, ns=(timestamp + 2_000_000_000, timestamp + 2_000_000_000))
        self.check('changed-main', child, [main], 'NewlineAtEndOfFile', selected=[main, test])
        main.write_text(GOOD); os.utime(main, ns=(timestamp + 4_000_000_000, timestamp + 4_000_000_000))
        self.check('restored-main', child, [main], selected=[main, test])
        main.unlink(); self.check('deleted-main', child, [], selected=[test])
        self.report['cacheBehavior'] = {'initialCacheSha256': initial, 'finalCacheSha256': digest(cache),
            'sameTimestampContentChangeWasCached': True, 'changedTimestampRecheckedAndFailed': True,
            'restoredSourceRecheckedAndPassed': True, 'deletedFileNotReported': True,
            'scope': 'one bounded fixture; cache semantics, not security applicability'}
        for name, (text, rule) in FAILURES.items():
            child = self.fixture(name); bad = self.put_source(child, 'src/main/java/fixture/Bad.java', text)
            self.check(name, child, [bad], rule)
        child = self.fixture('test-source-violation')
        main = self.put_source(child, 'src/main/java/fixture/Good.java', GOOD)
        test = self.put_source(child, 'src/test/java/fixture/Bad.java', FAILURES['tabs'][0])
        self.check('test-source-violation', child, [main, test], 'FileTabCharacter')
        child = self.fixture('invalid-configuration', invalid=True)
        self.put_source(child, 'src/main/java/fixture/Good.java', GOOD)
        self.check('invalid-configuration', child, [], invalid=True)
        for name, text, rule in [('report-passing', GOOD, None), ('report-violation', FAILURES['tabs'][0], 'FileTabCharacter')]:
            child = self.fixture(name)
            main = self.put_source(child, 'src/main/java/fixture/' + ('Bad.java' if rule else 'Good.java'), text)
            test = self.put_source(child, 'src/test/java/fixture/TestGood.java', TEST_GOOD)
            self.check(name, child, [main, test], rule, report_goal=True)
        self.api_probe()
        require(set(self.report['cases']) == set(CASES), 'all fifteen real cases required')
        self.report['status'] = 'PASSED'

    def api_probe(self):
        observed_case = 'invalid-configuration' if self.transition == 'guava' else 'passing'
        passing = self.report['cases'][observed_case]; approved = passing['realmArtifacts']
        by_path = {a['path']: (c, a) for c, a in approved.items()}
        ordered = [by_path[p] for p in passing['naturalOrigins']['actualPrivateRealmUrls']]
        logging = passing['loggingImport']; manifest = self.sandbox / 'observed-artifacts.tsv'
        text = ''.join('private\t' + c + '\t' + a['sha256'] + '\t' + a['path'] + '\n' for c, a in ordered)
        text += 'maven-import\t' + logging['coordinate'] + '\t' + logging['sha256'] + '\t' + logging['path'] + '\n'
        self.put_fixed(manifest, text)
        self.run('compile-api-probe', [str(self.jdk / 'bin/javac'), '-J-Xmx96m', '-proc:none', '--release', '21',
            '-d', str(self.sandbox / 'classes'), str(HERE / (self.spec['probe'] + '.java'))])
        version = self.spec['versions'][0 if self.baseline else 1]
        command = [str(self.jdk / 'bin/java'), '-Xmx192m', '-XX:MaxMetaspaceSize=128m', '-cp', str(self.sandbox / 'classes'), self.spec['probe']]
        _, out, _ = self.run('run-api-probe', command + [str(manifest), version], 60)
        receipts = [line for line in out.splitlines() if line.startswith('CHECKSTYLE_API_COMPLETE ')]
        require(len(receipts) == 1, 'one completed replay required')
        receipt = json.loads(receipts[0].removeprefix('CHECKSTYLE_API_COMPLETE '))
        require(receipt['status'] == 'PASSED' and receipt[self.spec['memberField']] == self.spec['memberCount']
            and receipt['referenceSetSha256'] == self.spec['referenceHash'], 'complete exact descriptor replay required')
        self.report['apiProbe'] = dict(receipt, goalReachabilityClaim=False, observedGoalCase=observed_case,
            observedGoalStdoutSha256=passing['stdoutSha256'], observedClassLoadSha256=passing['classLoadLogSha256'],
            observedArtifactsSha256=digest(manifest), probeStdoutSha256=digest(self.sandbox / 'run-api-probe.stdout.log'), negativeControls={})
        lines = text.splitlines(keepends=True)
        target_rows = [line for line in lines if line.startswith('private\t' + self.spec['prefix'])]
        require(len(target_rows) == 1, 'one selected transition artifact row required')
        target_row = target_rows[0]; opposite = cached_artifacts(self.repository, not self.baseline, transition=self.transition)
        other_coordinate = self.spec['prefix'] + self.spec['versions'][1 if self.baseline else 0]
        other = opposite[other_coordinate]; bad_hash = target_row.split('\t'); bad_hash[2] = '0' * 64
        controls = {
            'missing-' + self.transition: (text.replace(target_row, ''), '54 observed private artifacts'),
            'wrong-' + self.transition: (text.replace(target_row, 'private\t' + other_coordinate + '\t' + other['sha256'] + '\t' + other['path'] + '\n'), 'only selected ' + self.transition.capitalize() + ' variant'),
            'changed-pin': (text.replace(target_row, '\t'.join(bad_hash)), 'actual observed bytes ' + self.spec['prefix'].split(':')[1] + '-'),
        }
        for name, (invalid, cause) in controls.items():
            file = self.sandbox / (name + '.tsv'); self.put_fixed(file, invalid)
            _, _, err = self.run('probe-reject-' + name, command + [str(file), version], 30, expected=1)
            require('AssertionError: ' + cause in err, 'negative replay failed for wrong cause: ' + name)
            self.report['apiProbe']['negativeControls'][name] = {'status': 'REJECTED', 'expectedCause': cause,
                'stderrSha256': digest(self.sandbox / ('probe-reject-' + name + '.stderr.log'))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true'); parser.add_argument('--stage', choices=('models', 'fixtures'), default='fixtures')
    parser.add_argument('--variant', choices=('baseline', 'candidate'), default='candidate')
    parser.add_argument('--transition', choices=('lang', 'guava'), default='lang')
    parser.add_argument('--source', type=Path, default=ROOT); parser.add_argument('--repository', type=Path)
    parser.add_argument('--evidence-directory', type=Path)
    args = parser.parse_args(); spec = transition_spec(args.transition)
    if not args.execute:
        print(json.dumps({'execution': 'NOT_RUN', 'transition': args.transition, 'goals': [MODEL_GOAL, GOAL, REPORT_GOAL], 'cases': list(CASES),
            'variants': ['baseline', 'candidate'], 'explicitOptInRequiredEvenInCi': True, 'localAlwaysOffline': True,
            'ciPreparation': {'goal': PREPARE_GOAL, 'artifactCount': spec['artifactUnionCount'], 'reportResourceCount': 1,
                'maximumCommands': 2, 'acceptanceGoal': False, 'commandTimeoutSeconds': 120},
            'modelCount': 26, args.transition + 'MemberReferenceExpectation': spec['memberCount'],
            'referenceSetSha256': spec['referenceHash'],
            'aggregateTimeoutSeconds': {'models': 660, 'fixtures': 720},
            'commandTimeoutSeconds': {'models': 300, 'checkstyle': 120, 'javac': 30, 'probe': 60, 'negativeProbe': 30},
            'maxCommandOutputBytes': 16 * 1024 * 1024, 'maxAggregateOutputBytes': 96 * 1024 * 1024})); return 0
    offline = execution_mode(os.environ, args.repository)
    require(os.name == 'posix' and (args.stage != 'models' or args.variant == 'candidate'), 'POSIX and candidate model stage required')
    source = args.source.resolve(); repository = (args.repository or Path.home() / '.m2/repository').resolve()
    prepare_repository(repository, source, offline)
    jdk_value = os.environ.get('JAVA_HOME', '')
    require(jdk_value and Path(jdk_value).is_absolute(), 'explicit absolute JAVA_HOME required')
    jdk = Path(jdk_value).resolve(); executable = shutil.which('mvn')
    require(executable and all((jdk / 'bin' / n).is_file() for n in ('java', 'javac')), 'verified Maven and complete JDK required')
    base = args.evidence_directory.resolve() if args.evidence_directory else Path('/tmp')
    require(base.is_dir() and not base.is_relative_to(source) and not base.is_relative_to(repository), 'external evidence directory required')
    sandbox = Path(tempfile.mkdtemp(prefix='checkstyle-live-', dir=base)).resolve()
    require(not any(c.isspace() or ord(c) < 32 for c in str(sandbox)), 'sandbox JVM option separators forbidden')
    def interrupted(signum, frame):
        raise RuntimeError('interrupted signal ' + str(signum))
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, interrupted)
    run = None
    try:
        run = Run(source, sandbox, repository, jdk, Path(executable).resolve(), offline, args.stage, args.variant == 'baseline', args.transition)
        run.toolchain(); run.prepare_artifacts()
        run.models() if args.stage == 'models' else run.fixtures()
        run.containment()
    except BaseException as exc:
        receipt = run.report if run else {'sandbox': str(sandbox), 'scannerEvidence': False, 'canonicalCiEvidence': False}
        receipt.update(status='FAILED', errorType=type(exc).__name__, error=str(exc))
        inside(sandbox / 'result.json', sandbox).write_text(json.dumps(receipt, indent=2) + '\n')
        print('CHECKSTYLE_LIVE_RESULT ' + json.dumps(receipt), flush=True); return 1
    run.report['evidenceRetained'] = True
    inside(sandbox / 'result.json', sandbox).write_text(json.dumps(run.report, indent=2) + '\n')
    print('CHECKSTYLE_LIVE_RESULT ' + json.dumps(run.report), flush=True); return 0


if __name__ == '__main__':
    sys.exit(main())
