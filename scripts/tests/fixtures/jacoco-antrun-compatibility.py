#!/usr/bin/env python3
"""Opt-in offline JaCoCo/AntRun fixture. Import and plan never launch tools."""
import argparse
import copy
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
from urllib.parse import unquote, urlparse
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
from checkstyle_live_support import Commands, digest, inside, isolated_env, require, NS, P, CENTRAL_SETTINGS
from run_boot_live_compatibility import runtime_files, python_origins, hashes as sealed_hashes, cache_snapshot

PINS = HERE / 'jacoco-antrun-artifact-pins.json'
PINS_SHA256 = 'fe67305c20ae7db28837d15c6dda244c3d7cfd12232a6a18ff18694300cde228'
MEMBERS = HERE / 'jacoco-antrun-members.tsv'
PROBE = HERE / 'JacocoAntRunPlexusProbe.java'
OBSERVER = HERE / 'JacocoAntRunRealmObserver.java'
CONTRACT = ROOT / 'scripts/ci/jacoco_antrun_contract.py'
SOURCE_ADAPTER = ROOT / 'scripts/ci/jar_plugin_retained_source.py'
JAR_RETAINED = run_path(str(SOURCE_ADAPTER))
JACOCO = 'org.jacoco:jacoco-maven-plugin:0.8.15:'
ANTRUN = 'org.apache.maven.plugins:maven-antrun-plugin:3.1.0:run'
GOALS = frozenset([JACOCO + goal for goal in ('prepare-agent', 'report', 'merge')] + [ANTRUN])
IGNORE = ('.cvsignore', '.gitignore', '.gitattributes', '.hgignore')
SHARED_SLF4J = 'org.slf4j:slf4j-api:jar:1.7.36'
SLF4J_PACKAGES = ('org.slf4j.*', 'org.slf4j.event.*', 'org.slf4j.helpers.*', 'org.slf4j.spi.*')
# These exact Maven distribution files contain no Maven pom.properties. Their
# auxiliary identities come from the distribution LICENSE and/or JAR manifest;
# they are not evidence of additional resolved plugin dependencies.
MAVEN_AUXILIARY_IDENTITIES = {
    'aopalliance-1.0.jar': ('aopalliance', '0addec670fedcd3f113c5c8091d783280d23f75e3acb841b61a9cdb079376a08', 'Maven distribution LICENSE'),
    'asm-9.9.1.jar': ('asm', '6f3828a215c920059a5efa2fb55c233d6c54ec5cadca99ce1b1bdd10077c7ddd', 'Maven distribution LICENSE and Implementation-Version'),
    'guice-5.1.0-classes.jar': ('guice', '142ad4475e19524d2fe3ac995b3f7cbc962fc726f2edb9dbdccc61feab9b2bf9', 'Distributed auxiliary classes JAR; Bundle-Name and Bundle-Version'),
    'javax.inject-1.jar': ('javax.inject', '91c77044a50c481636c32d916fd89c9118a72195390452c81065080f957de7ff', 'Maven distribution LICENSE'),
    'jspecify-1.0.0.jar': ('jspecify', '1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab', 'Maven distribution LICENSE and Implementation-Version'),
}
TEXT = 'café & <fixture> 非ASCII'
PLAN = {'execution': 'NOT_RUN', 'explicitOptIn': 'JACOCO_ANTRUN_COMPATIBILITY=true',
        'offline': True, 'sockets': False, 'aggregateTimeoutSeconds': 360, 'outerTimeoutSeconds': 420,
        'commandTimeoutSeconds': {'javac': 30, 'apiReplay': 30, 'maven': 45},
        'variants': {'jacoco': ['3.0.24', '3.6.1'], 'antrun': ['3.4.1', '3.6.1']},
        'testKind': 'real forked Java assertion JVM with JaCoCo agent; not Surefire or JUnit',
        'goals': sorted(GOALS), 'memberCount': 34,
        'cases': ['agent-ant-test-report', 'merge-defaults', 'merge-explicit-excludes',
                  'merge-invalid-exec', 'ant-failure-reader', 'api-replay', 'missing-member'],
        'scannerEvidence': False, 'productionAntRunActivationClaim': False}
RESOLVE = 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins'
PORTABLE_PLAN = {'execution': 'NOT_RUN', 'localAlwaysOffline': True, 'ciCentralPreparationOnly': True,
                 'prepareCommandsMaximum': 2, 'artifactUnionCount': 18, 'preparationTimeoutSeconds': 240,
                 'outerTimeoutSeconds': 720, 'requiredOfflineStage': PLAN, 'acceptanceGoal': False}

TARGET = '''package fixture;
public final class CoverageTarget {
  public static String classify(int value) { if (value > 0) return "positive"; return "nonpositive"; }
}
'''
ASSERTIONS = '''package fixture;
public final class CoverageAssertions {
  public static void main(String[] args) {
    if (!"kept".equals(System.getProperty("fixture.original"))) throw new AssertionError("original argLine lost");
    long agents = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(value -> value.startsWith("-javaagent:") && value.contains("org.jacoco.agent-0.8.15-runtime.jar=")).count();
    if (agents != 1) throw new AssertionError("real JaCoCo agent argument missing or duplicated");
    if (!"positive".equals(CoverageTarget.classify(7))) throw new AssertionError("positive branch");
    if (!"nonpositive".equals(CoverageTarget.classify(-2))) throw new AssertionError("negative branch");
    if (!"nonpositive".equals(CoverageTarget.classify(0))) throw new AssertionError("zero boundary");
    System.out.println("JAC_ANTRUN_ASSERTIONS 3 PASSED");
  }
}
'''


def source_files():
    return [Path(__file__).resolve(), PINS, MEMBERS, PROBE, OBSERVER, CONTRACT,
            HERE / 'checkstyle_live_support.py', HERE / 'run_boot_live_compatibility.py',
            HERE.parent / 'jacoco-antrun-compatibility-harness.test.mjs',
            HERE.parent / 'jacoco-antrun-plugin-live-compatibility.test.mjs']


def read_pins(path=PINS):
    require(path.is_file() and path.stat().st_size < 16384 and digest(path) == PINS_SHA256, 'exact reviewed artifact pins required')
    value = json.loads(path.read_text())
    require(set(value) == {'schemaVersion', 'owners', 'artifactHashes'} and value['schemaVersion'] == 1,
            'fixed artifact pin schema required')
    require(set(value['owners']) == {'jacoco', 'antrun'} and len(value['artifactHashes']) == 18,
            'exact 18-artifact pin union required')
    union = set()
    for owner, version, count in [('jacoco', '3.0.24', 13), ('antrun', '3.4.1', 4)]:
        variants = value['owners'][owner]
        require(set(variants) == {'baseline', 'candidate'}, 'fixed variants required')
        before, after = variants['baseline'], variants['candidate']
        require(len(before) == count and len(after) == count and len(set(before)) == count
                and len(set(after)) == count, 'exact unique owner realm required')
        old = 'org.codehaus.plexus:plexus-utils:jar:' + version
        new = 'org.codehaus.plexus:plexus-utils:jar:3.6.1'
        require(old in before and [new if item == old else item for item in before] == after,
                'only selected private Plexus replacement permitted')
        require(before[0] == ('org.jacoco:jacoco-maven-plugin:jar:0.8.15' if owner == 'jacoco'
                            else 'org.apache.maven.plugins:maven-antrun-plugin:jar:3.1.0'), 'fixed plugin version required')
        union.update(before); union.update(after)
    require(union == set(value['artifactHashes']) and all(re.fullmatch('[a-f0-9]{64}', h)
            for h in value['artifactHashes'].values()), 'exact artifact hashes required')
    return value


def artifact_path(repository, coordinate):
    parts = coordinate.split(':')
    require(len(parts) in (4, 5) and parts[2] == 'jar'
            and all(re.fullmatch('[A-Za-z0-9_.-]+', p) for p in parts), 'safe jar coordinate required')
    group, artifact, _, *tail = parts
    version = tail[-1]; classifier = '-' + tail[0] if len(tail) == 2 else ''
    return repository.joinpath(*group.split('.'), artifact, version, artifact + '-' + version + classifier + '.jar')


def tree_hashes(root, maximum=15000):
    require(root.is_dir() and root.resolve() == root, 'real input directory required')
    files = list(root.rglob('*'))
    require(len(files) <= maximum and not any(p.is_symlink() for p in files), 'bounded non-symlink inputs required')
    return {str(p): digest(p) for p in sorted(files) if p.is_file()}


def source_hashes(root):
    contract = run_path(str(CONTRACT))
    source = (JAR_RETAINED['source_inventory'](root) if JAR_RETAINED['is_jar_source'](root)
              else contract['verify_source'](root))
    require(isinstance(source, dict) and source, 'source-contract hash mapping required')
    hashes = {str(Path(k) if Path(k).is_absolute() else root / k): v for k, v in source.items()}
    hashes.update({str(p): digest(p) for p in [*source_files(), SOURCE_ADAPTER]})
    return hashes


def prepare_source_projection(config):
    source, output = Path(config['source']), Path(config['output'])
    if not JAR_RETAINED['is_jar_source'](source):
        run_path(str(CONTRACT))['verify_source'](source)
        return None
    projection = JAR_RETAINED['materialize_accepted_source'](
        source, output.with_name(output.name + '-accepted-assembly-source'))
    source_for_contract(config, projection)
    return projection


def source_for_contract(config, projection):
    source, output = Path(config['source']), Path(config['output'])
    require(JAR_RETAINED['is_jar_source'](source) == (projection is not None),
            'explicit JAR historical source projection required')
    if projection is not None:
        require(projection['suppliedSourceRoot'] == str(source)
                and projection['retainedSourceRoot'] == str(output.with_name(output.name + '-accepted-assembly-source')),
                'sealed JAR historical source roots changed')
        JAR_RETAINED['verify_projection'](projection)
        source = Path(projection['retainedSourceRoot'])
    run_path(str(CONTRACT))['verify_source'](source)
    return source


def runtime_hashes(maven, jdk):
    require(maven.name == 'mvn' and maven.parent.name == 'bin' and maven.resolve() == maven,
            'canonical Maven executable required')
    require((maven.parent.parent / 'lib/maven-core-3.9.16.jar').is_file(), 'pinned Maven distribution required')
    require('JAVA_VERSION="21.' in (jdk / 'release').read_text(), 'pinned JDK major required')
    shared_maven_api(maven)
    _, imported = python_origins()
    return sealed_hashes(runtime_files(jdk, maven, imported))


def shared_maven_api(maven):
    library = maven.parent.parent / 'lib'
    core = library / 'maven-core-3.9.16.jar'
    with zipfile.ZipFile(core) as archive:
        raw = archive.read('META-INF/maven/extension.xml')
    document = ET.fromstring(raw)
    packages = [e.text for e in document.findall('exportedPackages/exportedPackage')]
    exports = [e.text for e in document.findall('exportedArtifacts/exportedArtifact')]
    require(all(packages.count(name) == 1 for name in SLF4J_PACKAGES), 'exact shared SLF4J exports required')
    pins = read_pins()
    for owner, variants in pins['owners'].items():
        for coordinates in variants.values():
            filtered = [c for c in coordinates if ':'.join(c.split(':')[:2]) in exports]
            require(filtered == ([SHARED_SLF4J] if owner == 'jacoco' else []), 'unexpected Maven API artifact filter')
    slf4j = library / 'slf4j-api-1.7.36.jar'
    require(slf4j.is_file() and digest(slf4j) == pins['artifactHashes'][SHARED_SLF4J], 'pinned shared SLF4J JAR required')
    return {'coordinate': SHARED_SLF4J, 'path': str(slf4j), 'sha256': digest(slf4j),
            'coreExtensionSha256': hashlib.sha256(raw).hexdigest(), 'exportedPackages': list(SLF4J_PACKAGES),
            'requiredClassLoadOrigins': {name: str(slf4j) for name in ('org.slf4j.Logger', 'org.slf4j.LoggerFactory')},
            'scope': 'Maven shared API import; excluded from the private JaCoCo realm'}


def prepare(config, historical_jar_projection=None):
    paths = {key: Path(config[key]).absolute() for key in ('source', 'maven', 'jdk', 'repository', 'output')}
    require(all(path.resolve() == path for path in paths.values()), 'canonical input paths required')
    require(not paths['output'].exists(), 'fresh output required')
    require(not any(character.isspace() or ord(character) < 32 for path in paths.values() for character in str(path)),
            'JVM-option-safe canonical paths required')
    require(all(not paths['output'].is_relative_to(paths[k]) and not paths[k].is_relative_to(paths['output'])
                for k in ('source', 'jdk', 'repository')), 'output/input overlap')
    require(paths['maven'].name == 'mvn' and paths['maven'].parent.name == 'bin', 'Maven executable path required')
    maven_root = paths['maven'].parent.parent
    require((maven_root / 'lib/maven-core-3.9.16.jar').is_file(), 'pinned Maven 3.9.16 required')
    require('JAVA_VERSION="21.' in (paths['jdk'] / 'release').read_text(), 'JDK 21 required')
    require(all(not (p / '.mvn').exists() for p in [paths['source'], *paths['source'].parents,
                paths['output'].parent, *paths['output'].parents]), 'unexpected Maven bootstrap configuration')
    pins = read_pins()
    for coordinate, expected in pins['artifactHashes'].items():
        path = artifact_path(paths['repository'], coordinate)
        require(path.is_file() and not path.is_symlink() and digest(path) == expected,
                'missing or wrong pinned artifact: ' + coordinate)
    runtime = runtime_hashes(paths['maven'], paths['jdk'])
    supplied_hashes = source_hashes(paths['source'])
    projection = (historical_jar_projection if historical_jar_projection is not None
                  else prepare_source_projection(config))
    selected_source = source_for_contract(config, projection)
    return {'schemaVersion': 1, 'plan': PLAN, 'config': {k: str(v) for k, v in paths.items()},
            'sourceHashes': supplied_hashes, 'runtimeHashes': runtime,
            'historicalJarSourceProjection': projection, 'actualFixtureSourceRoot': str(selected_source),
            'cacheHashes': tree_hashes(paths['repository']), 'pinFileSha256': digest(PINS),
            'pythonOrigins': python_origins()[0]}


def check_seal(seal):
    require(seal.get('schemaVersion') == 1 and seal.get('plan') == PLAN, 'reviewed fixture seal required')
    config = seal['config']; require(set(config) == {'source', 'maven', 'jdk', 'repository', 'output'}, 'fixed configuration required')
    require(all(Path(value).is_absolute() and Path(value).resolve() == Path(value) for value in config.values()), 'canonical sealed paths required')
    require(not any(character.isspace() or ord(character) < 32 for value in config.values() for character in value), 'JVM-option-safe sealed paths required')
    output = Path(config['output'])
    require(all(not output.is_relative_to(Path(config[k])) and not Path(config[k]).is_relative_to(output)
                for k in ('source', 'jdk', 'repository')), 'sealed output/input overlap')
    require(source_hashes(Path(config['source'])) == seal['sourceHashes'], 'source inputs changed')
    require(str(source_for_contract(config, seal['historicalJarSourceProjection'])) == seal['actualFixtureSourceRoot'],
            'fixture historical source identity changed')
    require(tree_hashes(Path(config['repository'])) == seal['cacheHashes'], 'cache inputs changed')
    require(runtime_hashes(Path(config['maven']), Path(config['jdk'])) == seal['runtimeHashes'], 'runtime inputs changed')
    require(digest(PINS) == seal['pinFileSha256'], 'artifact pin input changed')
    pins = read_pins()
    for coordinate, expected in pins['artifactHashes'].items():
        require(seal['cacheHashes'].get(str(artifact_path(Path(config['repository']), coordinate))) == expected,
                'pinned cache identity missing')


def child(parent, name, text=None):
    result = ET.SubElement(parent, P + name)
    if text is not None: result.text = text
    return result


def fixture_pom(plugins, directory, mode='main'):
    require(mode in ('main', 'merge-defaults', 'merge-explicit-excludes', 'merge-invalid-exec', 'ant-failure-reader'),
            'fixed fixture mode required')
    root = ET.Element(P + 'project'); child(root, 'modelVersion', '4.0.0')
    for key, value in [('groupId', 'fixture'), ('artifactId', 'jacoco-antrun'), ('version', '1')]: child(root, key, value)
    properties = child(root, 'properties'); child(properties, 'argLine', '-Dfixture.original=kept')
    build = child(root, 'build'); child(build, 'directory', str(directory / 'target'))
    child(build, 'outputDirectory', str(directory / 'target/classes'))
    listing = child(build, 'plugins'); selected = {}
    for key in ('jacoco', 'antrun'):
        source = plugins[key]; plugin = child(listing, 'plugin')
        for name in ('groupId', 'artifactId', 'version', 'dependencies'):
            item = source.find('m:' + name, NS)
            if item is not None: plugin.append(copy.deepcopy(item))
        selected[key] = child(plugin, 'configuration')
    jc = selected['jacoco']; child(jc, 'destFile', str(directory / 'target' / ('jacoco.exec' if mode == 'main' else mode + '.exec')))
    # This property deliberately has no initial POM value. Maven leaves the Ant
    # expression unresolved, and Ant expands the value copied after prepare-agent.
    child(jc, 'propertyName', 'fixture.jacoco.argLine')
    child(jc, 'append', 'false'); child(jc, 'output', 'file'); child(jc, 'dumpOnExit', 'true')
    child(jc, 'dataFile', str(directory / 'target' / (mode + '.exec' if mode.startswith('merge-') else 'jacoco.exec')))
    child(jc, 'outputDirectory', str(directory / 'target/site/jacoco'))
    child(child(jc, 'includes'), 'include', 'fixture/CoverageTarget*')
    child(child(jc, 'excludes'), 'exclude', 'fixture/CoverageAssertions*')
    if mode.startswith('merge-'):
        fileset = child(child(jc, 'fileSets'), 'fileSet'); child(fileset, 'directory', str(directory / 'merge-input'))
        child(fileset, 'useDefaultExcludes', 'true'); child(fileset, 'followSymlinks', 'false')
        child(child(fileset, 'includes'), 'include', '**')
        if mode == 'merge-explicit-excludes':
            excludes = child(fileset, 'excludes')
            for name in IGNORE: child(excludes, 'exclude', '**/' + name)
    child(child(jc, 'formats'), 'format', 'XML')
    target = child(selected['antrun'], 'target'); target.set('name', 'fixture')
    if mode == 'ant-failure-reader':
        child(target, 'fail').set('message', 'JAC_ANTRUN_EXPECTED_XML_FAILURE ' + TEXT)
    else:
        child(target, 'mkdir').set('dir', str(directory / 'target/ant'))
        echo = child(target, 'echo', TEXT); echo.set('file', str(directory / 'target/ant/item-1.0.txt')); echo.set('encoding', 'UTF-8')
        copy_task = child(target, 'copy'); copy_task.set('todir', str(directory / 'target/ant/mapped'))
        fileset = child(copy_task, 'fileset'); fileset.set('dir', str(directory / 'target/ant')); fileset.set('includes', 'item-1.0.txt')
        mapper = child(copy_task, 'mapper'); mapper.set('classname', 'org.apache.maven.ant.tasks.support.VersionMapper')
        mapper.set('from', '1.0'); mapper.set('to', 'flatten')
        attach = child(target, 'attachartifact'); attach.set('file', str(directory / 'target/ant/mapped/item.txt')); attach.set('classifier', 'fixture')
        java = child(target, 'java'); java.set('classname', 'fixture.CoverageAssertions')
        java.set('fork', 'true'); java.set('failonerror', 'true'); java.set('timeout', '15000')
        arg = child(java, 'jvmarg'); arg.set('line', '${fixture.jacoco.argLine}')
        child(java, 'jvmarg').set('line', '${argLine}')
        for value in ('-Xmx128m', '-XX:-UsePerfData', '-Duser.home=' + str(directory.parent / 'home'),
                      '-Djava.io.tmpdir=' + str(directory.parent / 'tmp')):
            child(java, 'jvmarg').set('value', value)
        path = child(child(java, 'classpath'), 'pathelement'); path.set('location', str(directory / 'target/classes'))
    ET.register_namespace('', NS['m'])
    return ET.tostring(root, encoding='utf-8', xml_declaration=True)


def maven_command(config, sandbox, project, goals):
    require(goals and all(goal in GOALS for goal in goals), 'only fixed direct goals permitted')
    inside(project, sandbox)
    settings = sandbox / 'empty-settings.xml'
    toolchains = sandbox / 'empty-toolchains.xml'
    return [config['maven'], '-o', '-B', '-ntp', '-N', '-X', '--strict-checksums', '-s', str(settings), '-gs', str(settings),
            '-t', str(toolchains), '-gt', str(toolchains),
            '-Dmaven.repo.local=' + config['repository'], '-Dstyle.color=never',
            '-Dmaven.multiModuleProjectDirectory=' + str(sandbox), '-f', str(project), *goals]


def natural_members(owner, resolved):
    require(owner in ('jacoco', 'antrun'), 'fixed natural realm owner required')
    require(resolved.count(SHARED_SLF4J) == (1 if owner == 'jacoco' else 0), 'exact resolved shared API inventory required')
    return [coordinate for coordinate in resolved if coordinate != SHARED_SLF4J]


def realm(log, owner, resolved):
    expected = natural_members(owner, resolved)
    key = JACOCO[:-1] if owner == 'jacoco' else ANTRUN.rsplit(':', 1)[0]
    marker = '[DEBUG] Populating class realm plugin>' + key
    lines = log.splitlines(); starts = [i for i, line in enumerate(lines) if line.strip() == marker]
    require(len(starts) == 1, 'one natural selected realm required: ' + owner)
    coordinates = []
    for line in lines[starts[0] + 1:]:
        found = re.fullmatch(r'\[DEBUG\]\s+Included: (\S+)\s*', line)
        if not found: break
        coordinates.append(found[1])
    require(len(coordinates) == len(set(coordinates)) and set(coordinates) == set(expected), 'unexpected natural realm membership')
    return coordinates


def natural_origins(stdout, stderr, config, variant, required):
    pins = read_pins()
    jvm = set()
    for name, uri in re.findall(r'^\[[^\n]*\]\[class,load\] (\S+) source: (\S+)\s*$', stderr, re.M):
        parsed = urlparse(uri)
        if parsed.scheme == 'file' and not parsed.netloc:
            jvm.add((name, str(Path(unquote(parsed.path)).resolve())))
    observations = []
    for line in stderr.splitlines():
        if not line.startswith('JAC_ANTRUN_ORIGIN\t'): continue
        parts = line.split('\t'); require(len(parts) == 5, 'malformed passive origin')
        _, name, loader, realm_name, uri = parts; parsed = urlparse(uri)
        require(parsed.scheme == 'file' and not parsed.netloc, 'nonlocal class origin')
        path = str(Path(unquote(parsed.path)).resolve())
        require((name.replace('/', '.'), path) in jvm, 'JVM class-load corroboration missing')
        observations.append({'class': name, 'loader': loader, 'realm': realm_name, 'path': path})
    for owner, names in required.items():
        approved = {str(artifact_path(Path(config['repository']), coordinate))
                    for coordinate in natural_members(owner, pins['owners'][owner][variant])}
        mojo = 'org/jacoco/maven/' if owner == 'jacoco' else 'org/apache/maven/plugins/antrun/'
        key = JACOCO[:-1] if owner == 'jacoco' else ANTRUN.rsplit(':', 1)[0]
        require(stdout.count('[DEBUG] Importing foreign packages into class realm plugin>' + key + '\n'
                             + '[DEBUG]   Imported:  < maven.api\n') == 1, 'exact Maven API import required')
        if owner == 'jacoco':
            for name in SLF4J_PACKAGES:
                require(stdout.splitlines().count('[DEBUG]   Imported: ' + name + ' < plexus.core') == 1,
                        'shared SLF4J package import absent or duplicated')
            shared = Path(config['maven']).parent.parent / 'lib/slf4j-api-1.7.36.jar'
            require(all((name, str(shared)) in jvm for name in ('org.slf4j.Logger', 'org.slf4j.LoggerFactory')),
                    'shared SLF4J runtime origin missing')
        actual = [r for r in observations if 'plugin>' + key in r['realm']]
        loaders = {r['loader'] for r in actual}
        require(len(loaders) == 1, 'one natural loader identity required')
        require(names <= {r['class'] for r in actual}, 'required natural plugin classes absent: ' + owner)
        for item in actual:
            require(item['path'] in approved, 'natural private class origin outside selected owner artifacts')
        require(any(r['class'].startswith(mojo) for r in actual), 'plugin Mojo not naturally observed')
        urls = []
        for line in stderr.splitlines():
            if not line.startswith('JAC_ANTRUN_REALM_URL\t'): continue
            parts = line.split('\t'); require(len(parts) == 3, 'malformed passive realm URL')
            if parts[1] not in loaders: continue
            parsed = urlparse(parts[2]); require(parsed.scheme == 'file' and not parsed.netloc, 'nonlocal realm URL')
            urls.append(str(Path(unquote(parsed.path)).resolve()))
        require(len(urls) == len(set(urls)) and set(urls) == approved, 'passive realm URL membership differs')
    return observations


def report_result(path):
    require(path.is_file() and 0 < path.stat().st_size < 1024 * 1024, 'bounded real report required')
    report = ET.fromstring(path.read_bytes()); classes = report.findall('.//class')
    require([c.get('name') for c in classes] == ['fixture/CoverageTarget'], 'exact report class required')
    counters = {c.get('type'): {k: int(c.get(k)) for k in ('covered', 'missed')} for c in report.findall('counter')}
    require(counters.get('BRANCH') == {'covered': 2, 'missed': 0}
            and counters.get('CLASS') == {'covered': 1, 'missed': 0}
            and counters.get('INSTRUCTION', {}).get('covered', 0) > 0, 'real covered branches required')
    return {'sha256': digest(path), 'counters': counters, 'classNames': [c.get('name') for c in classes]}


def merge_inputs(log, directory, report_data):
    prefix = '[INFO] Loading execution data file '
    lines = log.splitlines()
    boundaries = [(i, line) for i, line in enumerate(lines) if line.startswith('[INFO] --- ')]
    require([line for _, line in boundaries] == [
        '[INFO] --- jacoco:0.8.15:merge (default-cli) @ jacoco-antrun ---',
        '[INFO] --- jacoco:0.8.15:report (default-cli) @ jacoco-antrun ---'],
        'exact ordered merge and report goal boundaries required')
    merge_start, report_start = [i for i, _ in boundaries]
    result = []; report_loads = []
    for i, line in enumerate(lines):
        if not line.startswith(prefix): continue
        require(i > merge_start, 'execution-data load outside admitted goal sections')
        value = line[len(prefix):]
        if i < report_start:
            result.append(str(Path(value).relative_to(directory)))
        else:
            report_loads.append(value)
    require(report_loads == [str(report_data)], 'exact merged report execution-data load required')
    require(len(result) == len(set(result)), 'duplicate merge input log')
    return sorted(result)


def execute(seal):
    require(os.environ.get('JACOCO_ANTRUN_COMPATIBILITY') == 'true', 'explicit reviewed compatibility opt-in required')
    check_seal(seal)
    config = seal['config']; output = Path(config['output']); jdk = Path(config['jdk'])
    source = source_for_contract(config, seal['historicalJarSourceProjection'])
    require(not output.exists(), 'fresh output required')
    output.mkdir(parents=False)
    for name in ('home', 'tmp', 'observer', 'api-classes'): (output / name).mkdir()
    (output / 'empty-settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n')
    (output / 'empty-toolchains.xml').write_text('<toolchains/>\n')
    fixed = {str(output / name): digest(output / name) for name in ('empty-settings.xml', 'empty-toolchains.xml')}
    environment = isolated_env(jdk, output); environment.pop('HOME', None)
    environment['MAVEN_BASEDIR'] = str(output)
    commands = Commands(output, environment, PLAN['aggregateTimeoutSeconds'])
    result = {'status': 'RUNNING', 'plan': PLAN, 'commands': commands.results, 'variants': {},
              'historicalJarSourceProjection': seal['historicalJarSourceProjection'], 'actualFixtureSourceRoot': str(source),
              'apiReplay': {}, 'scannerEvidence': False, 'productionAntRunActivationClaim': False,
              'sharedMavenApi': shared_maven_api(Path(config['maven'])),
              'inputSeals': {k: seal[k] for k in ('sourceHashes', 'runtimeHashes', 'cacheHashes')}}
    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT)}
    def deadline(signum, frame): raise TimeoutError('fixture aggregate deadline or termination')
    def run(label, command, seconds=45, expected=0, extra_env=None):
        check_seal(seal)
        require(all(Path(path).is_file() and not Path(path).is_symlink() and digest(Path(path)) == h
                    for path, h in fixed.items()), 'generated input changed')
        status, stdout, stderr = commands.run(label, command, seconds, extra_env)
        check_seal(seal)
        require(all(Path(path).is_file() and not Path(path).is_symlink() and digest(Path(path)) == h
                    for path, h in fixed.items()), 'generated input changed')
        require(status == expected, 'unexpected fixture command status: ' + label)
        return stdout, stderr
    for sig in previous: signal.signal(sig, deadline)
    signal.alarm(PLAN['aggregateTimeoutSeconds'])
    try:
        run('compile-observer', [str(jdk / 'bin/javac'), '-J-Xmx128m', '-proc:none', '--release', '21',
            '-d', str(output / 'observer'), str(OBSERVER)], 30)
        with zipfile.ZipFile(output / 'observer.jar', 'x') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: JacocoAntRunRealmObserver\n\n')
            for file in sorted((output / 'observer').glob('*.class')): archive.write(file, file.name)
        observer_hash = digest(output / 'observer.jar')
        fixed[str(output / 'observer.jar')] = observer_hash
        opts = '-Xmx256m -XX:-UsePerfData -Duser.home=' + str(output / 'home') + ' -Djava.io.tmpdir=' + str(output / 'tmp')
        opts += ' -javaagent:' + str(output / 'observer.jar') + ' -Xlog:class+load=info:stderr'
        pins = read_pins(); contract = run_path(str(CONTRACT))
        for variant in ('baseline', 'candidate'):
            directory = output / variant; (directory / 'target/classes').mkdir(parents=True); (directory / 'src').mkdir()
            (directory / 'src/CoverageTarget.java').write_text(TARGET); (directory / 'src/CoverageAssertions.java').write_text(ASSERTIONS)
            fixed.update(tree_hashes(directory / 'src', 5))
            run(variant + '-compile-test', [str(jdk / 'bin/javac'), '-J-Xmx128m', '-proc:none', '--release', '21', '-g',
                '-d', str(directory / 'target/classes'), str(directory / 'src/CoverageTarget.java'),
                str(directory / 'src/CoverageAssertions.java')], 30)
            classes = tree_hashes(directory / 'target/classes', 10)
            fixed.update(classes)
            plugins = contract['fixture_plugins'](source, baseline=variant == 'baseline')
            project = directory / 'pom.xml'; project.write_bytes(fixture_pom(plugins, directory))
            fixed[str(project)] = digest(project)
            stdout, stderr = run(variant + '-agent-ant-test-report', maven_command(config, output, project,
                [JACOCO + 'prepare-agent', ANTRUN, JACOCO + 'report']), extra_env={'MAVEN_OPTS': opts})
            require(stdout.count('JAC_ANTRUN_ASSERTIONS 3 PASSED') == 1, 'actual assertion JVM completion missing')
            require(tree_hashes(directory / 'target/classes', 10) == classes, 'test bytecode changed')
            exec_file = directory / 'target/jacoco.exec'
            require(exec_file.is_file() and 0 < exec_file.stat().st_size <= 65536, 'bounded real execution data required')
            require((directory / 'target/ant/mapped/item.txt').read_text() == TEXT, 'Ant XML text/version mapper output mismatch')
            ant_xml = ET.parse(directory / 'target/antrun/build-fixture.xml').getroot()
            require(ant_xml.tag == 'project' and ant_xml.find('target/echo').text == TEXT, 'Ant generated XML semantics mismatch')
            realms = {owner: realm(stdout, owner, pins['owners'][owner][variant]) for owner in ('jacoco', 'antrun')}
            required = {'jacoco': {'org/jacoco/maven/AgentMojo', 'org/jacoco/maven/ReportMojo', 'org/jacoco/maven/FileFilter',
                                  'org/codehaus/plexus/util/FileUtils', 'org/codehaus/plexus/util/DirectoryScanner'},
                        'antrun': {'org/apache/maven/plugins/antrun/AntRunMojo',
                                   'org/apache/maven/plugins/antrun/AntrunXmlPlexusConfigurationWriter',
                                   'org/apache/maven/ant/tasks/AttachArtifactTask', 'org/apache/maven/ant/tasks/support/VersionMapper',
                                   'org/codehaus/plexus/util/xml/pull/MXSerializer'}}
            result['variants'][variant] = {'realms': realms,
                'resolvedOwnerInventory': {owner: pins['owners'][owner][variant] for owner in ('jacoco', 'antrun')},
                'naturalOrigins': natural_origins(stdout, stderr, config, variant, required),
                'report': report_result(directory / 'target/site/jacoco/jacoco.xml'), 'executionDataSha256': digest(exec_file),
                'assertionJvmTests': 3, 'antOutputSha256': digest(directory / 'target/ant/mapped/item.txt'), 'merge': {}}
            merge_dir = directory / 'merge-input'; merge_dir.mkdir()
            for name in ['normal.exec', *IGNORE, '.git/hidden.exec', '.hg/hidden.exec']:
                path = merge_dir / name; path.parent.mkdir(exist_ok=True); path.write_bytes(exec_file.read_bytes())
                fixed[str(path)] = digest(path)
            for mode in ('merge-defaults', 'merge-explicit-excludes'):
                project.write_bytes(fixture_pom(plugins, directory, mode))
                fixed[str(project)] = digest(project)
                stdout, stderr = run(variant + '-' + mode, maven_command(config, output, project, [JACOCO + 'merge', JACOCO + 'report']), extra_env={'MAVEN_OPTS': opts})
                expected_names = sorted(['normal.exec', *IGNORE]) if variant == 'candidate' and mode == 'merge-defaults' else ['normal.exec']
                output_exec = directory / 'target' / (mode + '.exec')
                require(merge_inputs(stdout, merge_dir, output_exec) == expected_names, 'four-pattern merge selection mismatch')
                require(output_exec.is_file() and 0 < output_exec.stat().st_size <= 65536, 'merged exec data missing')
                realm(stdout, 'jacoco', pins['owners']['jacoco'][variant])
                origins = natural_origins(stdout, stderr, config, variant, {'jacoco': {'org/jacoco/maven/MergeMojo',
                    'org/apache/maven/shared/model/fileset/util/FileSetManager', 'org/codehaus/plexus/util/DirectoryScanner'}})
                merged_report = report_result(directory / 'target/site/jacoco/jacoco.xml')
                require(merged_report['counters'] == result['variants'][variant]['report']['counters'], 'merged coverage differs')
                result['variants'][variant]['merge'][mode] = {'inputNames': expected_names, 'sha256': digest(output_exec),
                    'naturalOrigins': origins, 'report': merged_report}
            (merge_dir / 'invalid.exec').write_bytes(b'INVALID-EXEC-DATA')
            fixed[str(merge_dir / 'invalid.exec')] = digest(merge_dir / 'invalid.exec')
            project.write_bytes(fixture_pom(plugins, directory, 'merge-invalid-exec'))
            fixed[str(project)] = digest(project)
            stdout, stderr = run(variant + '-merge-invalid-exec', maven_command(config, output, project, [JACOCO + 'merge']), expected=1, extra_env={'MAVEN_OPTS': opts})
            require('Unable to read' in stdout and 'invalid.exec' in stdout, 'invalid execution-data failure absent')
            project.write_bytes(fixture_pom(plugins, directory, 'ant-failure-reader'))
            fixed[str(project)] = digest(project)
            stdout, stderr = run(variant + '-ant-failure-reader', maven_command(config, output, project, [ANTRUN]), expected=1, extra_env={'MAVEN_OPTS': opts})
            require('JAC_ANTRUN_EXPECTED_XML_FAILURE' in stdout and 'around Ant part' in stdout, 'Ant XML failure context absent')
            origins = natural_origins(stdout, stderr, config, variant, {'antrun': {'org/apache/maven/plugins/antrun/AntRunMojo',
                'org/codehaus/plexus/util/ReaderFactory', 'org/codehaus/plexus/util/xml/XmlStreamReader'}})
            result['variants'][variant]['negativeControls'] = {'invalidExec': 'PASSED', 'antFailureReader': 'PASSED', 'naturalOrigins': origins}
            require(digest(output / 'observer.jar') == observer_hash, 'observer bytecode changed')
        require(result['variants']['baseline']['report']['counters'] == result['variants']['candidate']['report']['counters'], 'coverage changed')
        result['apiReplay'] = api_replay(config, output, run, fixed)
        check_seal(seal); result['status'] = 'PASSED'; result['inputSealsUnchanged'] = True
        result['sharedMavenApi']['classLoadOriginsVerified'] = True
    except BaseException as failure:
        result.update(status='FAILED', errorType=type(failure).__name__, error=str(failure))
        raise
    finally:
        signal.alarm(0)
        for sig in previous: signal.signal(sig, signal.SIG_IGN)
        try:
            (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
            print('JAC_ANTRUN_RESULT ' + json.dumps(result), flush=True)
        finally:
            for sig, handler in previous.items(): signal.signal(sig, handler)


def maven_support_identity(path):
    with zipfile.ZipFile(path) as archive:
        identities = []
        for name in archive.namelist():
            if not name.startswith('META-INF/maven/') or not name.endswith('/pom.properties'): continue
            text = archive.read(name).decode('utf-8')
            properties = dict(line.split('=', 1) for line in text.splitlines() if '=' in line and not line.startswith('#'))
            artifact, version = properties.get('artifactId'), properties.get('version')
            if artifact and version and path.name.startswith(artifact + '-' + version): identities.append(artifact)
    if path.name in MAVEN_AUXILIARY_IDENTITIES:
        artifact, expected, evidence = MAVEN_AUXILIARY_IDENTITIES[path.name]
        require(not identities and digest(path) == expected,
                'exact distributed auxiliary JAR bytes required: ' + path.name)
        return artifact, evidence
    require(len(identities) == 1, 'one explicit Maven support JAR identity required: ' + path.name)
    return identities[0], 'embedded Maven pom.properties'


def maven_support_artifacts(maven, selected):
    support = {}; identity_basis = {}
    for path in sorted((maven.parent.parent / 'lib').glob('*.jar')):
        artifact, evidence = maven_support_identity(path)
        if artifact in ('plexus-utils', 'plexus-xml') or artifact in selected: continue
        require(artifact not in support, 'duplicate Maven support identity')
        support[artifact] = {'path': str(path), 'sha256': digest(path)}
        identity_basis[artifact] = evidence
    return support, identity_basis


def api_replay(config, output, run, fixed):
    pins = read_pins(); repository = Path(config['repository']); jdk = Path(config['jdk'])
    common = {c.split(':')[1]: {'path': str(artifact_path(repository, c)), 'sha256': h}
              for c, h in pins['artifactHashes'].items() if ':plexus-utils:' not in c}
    # Support provides Maven's model/API classes only to this explicitly merged API replay.
    # It does not stand in for a plugin realm and cannot contribute another Plexus copy.
    support, identity_basis = maven_support_artifacts(Path(config['maven']), common)
    common.update(support)
    require({'maven-core', 'maven-model', 'maven-artifact', 'maven-plugin-api'} <= set(common), 'Maven support incomplete')
    def artifacts(version):
        coordinate = 'org.codehaus.plexus:plexus-utils:jar:' + version
        return {'plexus-utils': {'path': str(artifact_path(repository, coordinate)), 'sha256': pins['artifactHashes'][coordinate]}, **common}
    baseline = artifacts('3.0.24'); classes = output / 'api-classes'
    run('api-compile-baseline', [str(jdk / 'bin/javac'), '-J-Xmx128m', '-proc:none', '--release', '21',
        '-cp', os.pathsep.join(item['path'] for item in baseline.values()), '-d', str(classes), str(PROBE)], 30)
    compiled = tree_hashes(classes, 20); result = {'status': 'RUNNING', 'compiledAgainst': '3.0.24',
        'scope': 'Merged owner artifact API replay; not a natural Maven realm', 'variants': {},
        'supportArtifacts': common, 'supportIdentityBasis': identity_basis}
    fixed.update(compiled)
    for variant, version in [('jacoco-baseline', '3.0.24'), ('antrun-baseline', '3.4.1'), ('candidate', '3.6.1')]:
        selected = artifacts(version); manifest = output / (variant + '-identities.tsv')
        manifest.write_text(''.join(name + '\t' + item['path'] + '\t' + item['sha256'] + '\n' for name, item in selected.items()))
        fixed[str(manifest)] = digest(manifest)
        directory = output / ('api-' + variant); directory.mkdir()
        command = [str(jdk / 'bin/java'), '-Xmx128m', '-XX:-UsePerfData', '-Xlog:class+load=info:stderr',
            '-Duser.home=' + str(output / 'home'), '-Djava.io.tmpdir=' + str(output / 'tmp'),
            '-cp', os.pathsep.join([str(classes), *(item['path'] for item in selected.values())]),
            'JacocoAntRunPlexusProbe', str(manifest), str(MEMBERS), variant, str(directory)]
        stdout, stderr = run('api-' + variant, command, 30)
        result['variants'][variant] = api_result(stdout, stderr, variant, selected)
        require(tree_hashes(classes, 20) == compiled, 'baseline-compiled API probe changed')
        if variant == 'candidate':
            stdout, stderr = run('api-missing-member', command + ['missing-member'], 20, expected=1)
            require('NoSuchMethodException' in stderr and 'missingFixtureMethod' in stderr
                    and 'JAC_ANTRUN_COMPLETE' not in stdout, 'missing-member negative did not fail closed')
            result['missingMemberNegativeControl'] = 'PASSED'
    result['status'] = 'PASSED'
    return result


def api_result(stdout, stderr, variant, artifacts):
    require(variant in ('jacoco-baseline', 'antrun-baseline', 'candidate'), 'fixed API variant required')
    rows = MEMBERS.read_text().splitlines()
    selected = [row for row in rows if variant == 'candidate' or row.split('\t')[0] in ('both', variant.split('-')[0])]
    members = [line.split('\t', 1)[1] for line in stdout.splitlines() if line.startswith('JAC_ANTRUN_MEMBER\t')]
    require(members == selected, 'exact owner member replay missing')
    expected_linkage = 'JAC_ANTRUN_LINKAGE\t' + variant + '\t' + str(len(selected)) + '\t' + digest(MEMBERS)
    require(stdout.splitlines().count(expected_linkage) == 1, 'API linkage receipt missing')
    completions = [line.split('\t') for line in stdout.splitlines() if line.startswith('JAC_ANTRUN_COMPLETE\t')]
    require(len(completions) == 1 and len(completions[0]) == 4 and completions[0][1] == variant
            and completions[0][2].isdigit() and int(completions[0][2]) >= 100
            and completions[0][3] == 'api-replay-only', 'bounded API completion missing')
    expected_scanner = 'JAC_ANTRUN_SCANNER\t' + variant + '\tdefaultIgnoreFilesIncluded=' + ('4' if variant == 'candidate' else '0') + '\texplicitIgnoreFilesIncluded=0\tscmFilesIncluded=0'
    require(stdout.splitlines().count(expected_scanner) == 1, 'intentional four-name scanner delta missing')
    filters = [line for line in stdout.splitlines() if line.startswith('JAC_ANTRUN_FILE_FILTER\t')]
    require(len(filters) == 1 and filters[0].startswith('JAC_ANTRUN_FILE_FILTER\t' + variant + '\tignoreFilesIncluded=4\tfiles='), 'unchanged FileFilter receipt missing')
    for marker in ('JAC_ANTRUN_DIRECT_API\tcopyFile\tcopyDirectoryStructure\tcloseInput\tcloseOutput',
                   'JAC_ANTRUN_ANT_CONSUMERS\tVersionMapper\tAttachArtifactTask\tattachmentType=txt\tattachments=1'):
        require(stdout.splitlines().count(marker) == 1, 'actual bounded consumer completion missing')
    origins = []
    for line in stdout.splitlines():
        if not line.startswith('JAC_ANTRUN_API_ORIGIN\t'): continue
        parts = line.split('\t'); require(len(parts) == 4, 'malformed API origin')
        _, name, path, h = parts
        require(any(item == {'path': path, 'sha256': h} for item in artifacts.values()), 'unapproved API origin')
        if name.startswith('org.codehaus.plexus.util.'):
            require(artifacts['plexus-utils'] == {'path': path, 'sha256': h}, 'private Plexus API origin differs')
        require(name + ' source: file:' + path in stderr, 'API JVM origin corroboration missing')
        origins.append({'class': name, 'path': path, 'sha256': h})
    require(len(origins) >= 12 and len({item['class'] for item in origins}) == len(origins), 'complete distinct API origins required')
    required_classes = {'org.jacoco.maven.FileFilter', 'org.apache.maven.shared.model.fileset.util.FileSetManager',
                        'org.apache.maven.ant.tasks.AttachArtifactTask', 'org.apache.maven.ant.tasks.support.VersionMapper',
                        'org.codehaus.plexus.util.Expand'}
    require(required_classes <= {item['class'] for item in origins}, 'actual API consumer origins absent')
    extraction = [line for line in stdout.splitlines() if line.startswith('JAC_ANTRUN_EXPAND\t')]
    expected = ['JAC_ANTRUN_EXPAND\t' + variant + '\t' + kind + '\t'
                + ('rejected-no-sibling' if variant == 'candidate' else 'baseline-owned-sibling') + '\tdirect-api-only'
                for kind in ('relative', 'absolute')]
    require(extraction == expected, 'exact bounded extraction containment results missing')
    return {'status': 'PASSED', 'members': members, 'memberSetSha256': digest(MEMBERS),
            'assertions': int(completions[0][2]), 'origins': origins,
            'defaultIgnoreFilesIncluded': 4 if variant == 'candidate' else 0, 'fileFilterIgnoreFilesIncluded': 4,
            'extractionContainment': extraction, 'naturalGoalReachabilityClaim': False}


def pinned_cache(repository, allow_missing=False):
    records = {}
    for coordinate, expected in read_pins()['artifactHashes'].items():
        path = artifact_path(repository, coordinate)
        if not path.exists() and allow_missing: continue
        require(path.is_file() and not path.is_symlink() and digest(path) == expected,
                'missing or changed pinned cached artifact: ' + coordinate)
        records[coordinate] = {'path': str(path), 'sha256': expected}
    return records


def preparation_command(config, sandbox, variant, ci):
    require(ci is True and variant in ('baseline', 'candidate'), 'Central preparation is hosted-CI-only')
    settings, toolchains = sandbox / 'central-settings.xml', sandbox / 'empty-toolchains.xml'
    return [config['maven'], '-B', '-ntp', '-N', '--strict-checksums', '-s', str(settings), '-gs', str(settings),
            '-t', str(toolchains), '-gt', str(toolchains), '-Dmaven.repo.local=' + config['repository'],
            '-Dmaven.multiModuleProjectDirectory=' + str(sandbox), '-Dstyle.color=never',
            '-f', str(sandbox / (variant + '-pom.xml')), RESOLVE,
            '-DincludeArtifactIds=jacoco-maven-plugin,maven-antrun-plugin', '-DexcludeTransitive=false',
            '-DappendOutput=false', '-DoutputEncoding=UTF-8', '-DoutputFile=' + str(sandbox / (variant + '-owners.txt'))]


def portable(args):
    require(os.environ.get('JACOCO_ANTRUN_COMPATIBILITY') == 'true', 'portable execution requires explicit compatibility opt-in')
    ci = os.environ.get('GITHUB_ACTIONS') == 'true'
    require(ci or args.repository, 'local portable execution requires an explicit existing cache')
    repository = Path(args.repository or (Path.home() / '.m2/repository')).resolve()
    require(not repository.is_relative_to(ROOT) and not ROOT.is_relative_to(repository), 'cache/source overlap')
    if ci: repository.mkdir(parents=True, exist_ok=True)
    require(repository.is_dir(), 'existing cache required')
    jdk_value = os.environ.get('JAVA_HOME', ''); executable = shutil.which('mvn')
    require(jdk_value and Path(jdk_value).is_absolute() and executable, 'explicit JDK and pinned Maven runtime required')
    base = Path(args.evidence_directory or '/tmp').resolve()
    require(base.is_dir() and not base.is_relative_to(ROOT) and not base.is_relative_to(repository), 'external evidence directory required')
    sandbox = Path(tempfile.mkdtemp(prefix='jacoco-antrun-', dir=base))
    config = {'source': str(ROOT), 'maven': str(Path(executable).resolve()), 'jdk': str(Path(jdk_value).resolve()),
              'repository': str(repository), 'output': str(sandbox / 'offline-goals')}
    require(not any(c.isspace() or ord(c) < 32 for value in config.values() for c in value), 'JVM-option-safe portable paths required')
    projection = prepare_source_projection(config)
    historical_source = source_for_contract(config, projection)
    for name in ('home', 'tmp'): (sandbox / name).mkdir()
    (sandbox / 'central-settings.xml').write_text(CENTRAL_SETTINGS)
    (sandbox / 'empty-toolchains.xml').write_text('<toolchains/>\n')
    contract = run_path(str(CONTRACT))
    for variant in ('baseline', 'candidate'):
        plugins = contract['fixture_plugins'](historical_source, baseline=variant == 'baseline')
        document = ET.fromstring(fixture_pom(plugins, sandbox / variant))
        child(document, 'packaging', 'pom')
        (sandbox / (variant + '-pom.xml')).write_bytes(ET.tostring(document, encoding='utf-8', xml_declaration=True))
    sources = source_hashes(ROOT); runtime = runtime_hashes(Path(config['maven']), Path(config['jdk']))
    cache_before = cache_snapshot(repository)
    fixed = {str(sandbox / n): digest(sandbox / n) for n in ('central-settings.xml', 'empty-toolchains.xml', 'baseline-pom.xml', 'candidate-pom.xml')}
    # A changed pre-existing pinned JAR is an error, never a request to repair it by downloading.
    pinned_cache(repository, allow_missing=ci)
    environment = isolated_env(Path(config['jdk']), sandbox); environment.pop('HOME', None)
    environment.update(MAVEN_BASEDIR=str(sandbox), MAVEN_OPTS='-Xmx256m -XX:-UsePerfData -Duser.home='
                       + str(sandbox / 'home') + ' -Djava.io.tmpdir=' + str(sandbox / 'tmp'))
    commands = Commands(sandbox, environment, 240)
    def unchanged():
        source_for_contract(config, projection)
        require(source_hashes(ROOT) == sources and runtime_hashes(Path(config['maven']), Path(config['jdk'])) == runtime,
                'portable source/runtime changed')
        require(all(digest(Path(file)) == h for file, h in fixed.items()), 'preparation inputs changed')
        current = cache_snapshot(repository)
        require(all(current.get(file) == h for file, h in cache_before.items()), 'existing cache artifact changed')
    receipt = {'status': 'RUNNING', 'sandbox': str(sandbox), 'plan': PORTABLE_PLAN, 'commands': commands.results,
               'historicalJarSourceProjection': projection, 'actualFixtureSourceRoot': str(historical_source),
               'cachePreparation': {'mode': 'ci-central-plugin-resolution' if ci else 'offline-existing-cache',
                                    'acceptanceGoal': False, 'resolutionCommandsRun': 0}}
    old = {sig: signal.getsignal(sig) for sig in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT)}
    def deadline(signum, frame): raise TimeoutError('portable deadline or interruption')
    for sig in old: signal.signal(sig, deadline)
    signal.alarm(660)
    try:
        if ci:
            for variant in ('baseline', 'candidate'):
                unchanged()
                status, stdout, stderr = commands.run('prepare-' + variant, preparation_command(config, sandbox, variant, ci), 120)
                require(status == 0, 'Central plugin preparation failed'); unchanged()
                receipt['cachePreparation']['resolutionCommandsRun'] += 1
        artifacts = pinned_cache(repository); unchanged()
        receipt['cachePreparation'].update(status='VERIFIED', artifacts=artifacts,
                                          existingArtifactHashesUnchanged=True, cacheAfter=cache_snapshot(repository))
        seal = prepare(config, projection)
        (sandbox / 'execution-seal.json').write_text(json.dumps(seal, indent=2) + '\n')
        receipt['executionSealSha256'] = digest(sandbox / 'execution-seal.json')
        execute(seal)
        receipt['offlineResult'] = json.loads((sandbox / 'offline-goals/result.json').read_text())
        require(receipt['offlineResult']['status'] == 'PASSED', 'offline compatibility incomplete')
        receipt['status'] = 'PASSED'
    except BaseException as failure:
        receipt.update(status='FAILED', errorType=type(failure).__name__, error=str(failure)); raise
    finally:
        signal.alarm(0)
        for sig in old: signal.signal(sig, signal.SIG_IGN)
        try:
            (sandbox / 'portable-result.json').write_text(json.dumps(receipt, indent=2) + '\n')
            public_receipt = {key: value for key, value in receipt.items() if key != 'offlineResult'}
            if 'offlineResult' in receipt:
                public_receipt['offlineResultFileSha256'] = digest(sandbox / 'offline-goals/result.json')
            print('JAC_ANTRUN_PORTABLE_RESULT ' + json.dumps(public_receipt), flush=True)
        finally:
            for sig, handler in old.items(): signal.signal(sig, handler)


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--prepare-seal', type=Path); parser.add_argument('--execute-seal', type=Path)
    parser.add_argument('--portable', action='store_true'); parser.add_argument('--evidence-directory')
    for key in ('source', 'maven', 'jdk', 'repository', 'output'): parser.add_argument('--' + key)
    args = parser.parse_args()
    require(sum(bool(x) for x in (args.prepare_seal, args.execute_seal, args.portable)) <= 1, 'select one operation')
    if args.portable:
        require(not any(getattr(args, key) for key in ('source', 'maven', 'jdk', 'output')), 'portable runtime comes from pinned toolchain environment')
        portable(args)
    elif args.prepare_seal:
        require(all(getattr(args, key) for key in ('source', 'maven', 'jdk', 'repository', 'output')), 'complete preparation configuration required')
        seal = prepare({key: getattr(args, key) for key in ('source', 'maven', 'jdk', 'repository', 'output')})
        with args.prepare_seal.open('x') as stream: json.dump(seal, stream, indent=2); stream.write('\n')
        print(json.dumps({'execution': 'NOT_RUN', 'seal': str(args.prepare_seal), 'sha256': digest(args.prepare_seal)}))
    elif args.execute_seal:
        require(not any(getattr(args, key) for key in ('source', 'maven', 'jdk', 'repository', 'output')), 'sealed execution forbids overrides')
        execute(json.loads(args.execute_seal.read_text()))
    else: print(json.dumps(PLAN))


if __name__ == '__main__': main()
