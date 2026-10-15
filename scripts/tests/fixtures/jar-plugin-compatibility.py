#!/usr/bin/env python3
"""Paired actual JAR goals. Import and plan are inert; live work requires a reviewed seal."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
from runpy import run_path
import signal
import stat
import sys
from urllib.parse import unquote, urlparse
import xml.etree.ElementTree as ET
import zipfile
import zlib

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
from checkstyle_live_support import Commands, digest, inside, isolated_env, require, NS, P
from run_boot_live_compatibility import cache_snapshot, hashes, python_origins, runtime_files
from jar_plugin_inventory import subset_inventory
from jar_plugin_storage import StorageGuard

ASSEMBLY_HELPER = HERE / 'assembly-plexus-compatibility.py'
ASSEMBLY_ADAPTER = ROOT / 'scripts/ci/jar_plugin_retained_source.py'
linkage_rows = run_path(str(ASSEMBLY_HELPER))['linkage_rows']

VERSIONS = {'baseline': '3.5.0', 'candidate': '3.5.1'}
JAR = 'org.apache.maven.plugins:maven-jar-plugin:'
BOOT = 'org.springframework.boot:spring-boot-maven-plugin:4.0.8'
FIXED_TIME = '2024-01-02T03:04:06Z'
FIXED_EPOCH = 1704164646
FIXED_ZIP_TIME = (2024, 1, 2, 3, 4, 6)
SETTINGS = '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n'
TOOLCHAINS = '<toolchains/>\n'
MAIN = 'public class JarBootFixture { public static void main(String[] args) { System.out.println("JAR_BOOT_MAIN_OK"); } }\n'
CASE_GOALS = {
    'selection': ('jar@omitted', 'jar@explicit', 'jar@all'),
    'empty': ('jar@empty-keep', 'jar@empty-skip'),
    'incremental-first': ('jar@main',),
    'incremental-unchanged': ('jar@main',),
    'incremental-changed': ('jar@main',),
    'attachments': ('jar@main', 'jar@attached', 'test-jar@tests'),
    'duplicate': ('jar@main', 'jar@duplicate'),
    'boot': ('jar@main', 'boot:repackage'),
}
PLAN = {'execution': 'NOT_RUN', 'explicitOptIn': 'JAR_PLUGIN_COMPATIBILITY=true',
    'offline': True, 'sockets': False, 'variants': VERSIONS,
    'commandsPerVariant': len(CASE_GOALS), 'mavenCommands': 2 * len(CASE_GOALS),
    'jarGoals': 2 * sum(sum(g != 'boot:repackage' for g in goals) for goals in CASE_GOALS.values()),
    'bootGoals': 2, 'executableMainCommands': 2, 'javacCommands': 3,
    'commandTimeoutSeconds': 60, 'aggregateTimeoutSeconds': 900,
    'outerTimeoutSeconds': 930, 'caseGoals': {k: list(v) for k, v in CASE_GOALS.items()},
    'outputTreeMaximumBytes': 256 * 1024 * 1024, 'outputTreeMaximumEntries': 2048,
    'outputTreePollIntervalSeconds': 0.1, 'outputTreeBoundIsSampled': True,
    'classLoadCorroborationMaximumBytes': 4 * 1024 * 1024,
    'scannerEvidence': False, 'productionActivationClaim': False,
    'symbolicConsumers': ['AbstractJarMojo', 'JarMojo', 'TestJarMojo', 'MavenArchiver', 'BuildHelper'],
    'symbolicLinkageIsInvocationCoverage': False,
    'codecScope': 'ZIP/JAR only; no Snappy, tar.Snappy, XZ or Zstd execution claim'}
FILES = {'ordinary.txt': b'ordinary\n', 'nested/keep.txt': 'caf\u00e9 \u975eASCII\n'.encode(),
    'nested/drop.tmp': b'excluded suffix\n', 'excluded/drop.txt': b'excluded directory\n',
    '.hidden': b'hidden retained\n', '.gitignore': b'root ignore\n',
    'nested/.gitignore': b'nested ignore\n', '.git/config': b'ignored VCS directory\n',
    '.cvsignore': b'CVS ignore\n', 'nested/.cvsignore': b'nested CVS ignore\n',
    '.gitattributes': b'Git attributes retained\n', 'nested/.gitattributes': b'nested Git attributes retained\n',
    '.hgignore': b'Mercurial ignore retained\n', 'nested/.hgignore': b'nested Mercurial ignore retained\n',
    'bin/executable': b'fixture executable bytes; never executed\n',
    'META-INF/versions/9/fixture.txt': b'multi-release resource\n'}
IGNORED = {'.cvsignore', 'nested/.cvsignore', '.gitignore', 'nested/.gitignore', '.git/config'}
EXCLUDED = {'nested/drop.tmp', 'excluded/drop.txt'}
LARGE_BYTES = 11 * 1024 * 1024
COMPRESSIBLE_BYTES = 64 * 1024


def payloads():
    # Counter-hash data is reproducible without randomness or external input.
    return {**FILES, 'large/compressible.bin': b'A' * COMPRESSIBLE_BYTES,
        'large/incompressible.bin': b''.join(hashlib.sha256(i.to_bytes(4, 'big')).digest()
            for i in range(LARGE_BYTES // 32))}


def artifact_path(repository, coordinate):
    require(re.fullmatch(r'[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:jar:[A-Za-z0-9_.-]+', coordinate), 'plain JAR coordinate')
    group, artifact, _, version = coordinate.split(':')
    return repository.joinpath(*group.split('.'), artifact, version, artifact + '-' + version + '.jar')


def read_pins(path):
    data = json.loads(path.read_text())
    require(data['schemaVersion'] == 'JAR_PLUGIN_COMPATIBILITY_PINS_V1', 'reviewed JAR pins schema')
    require(set(data['variants']) == set(VERSIONS), 'two exact variants')
    for variant, version in VERSIONS.items():
        rows = data['variants'][variant]
        require(1 <= len(rows) == len(set(rows)) <= 64, 'bounded distinct JAR owner inventory')
        require(JAR + 'jar:' + version in rows, 'explicit JAR owner version')
    require(BOOT.replace(':4.0.8', ':jar:4.0.8') in data['boot'], 'accepted Boot owner required')
    require(len(data['boot']) == len(set(data['boot'])) <= 64, 'bounded distinct Boot inventory')
    require(set(data['artifactHashes']) == set(sum(data['variants'].values(), []) + data['boot']), 'complete pin union')
    require(all(re.fullmatch(r'[0-9a-f]{64}', v) for v in data['artifactHashes'].values()), 'SHA256 artifact pins')
    require(any(c.startswith('io.airlift:aircompressor:jar:') for c in data['variants']['baseline'])
        and not any(c.startswith('io.airlift:aircompressor:') for c in data['variants']['candidate']), 'owner-scoped AirCompressor removal')
    require(data['offloadingThresholdBytes'] == 10_000_000 and LARGE_BYTES > data['offloadingThresholdBytes'], 'exact source-backed maximum offloading threshold')
    return data


def source_hashes(source, pins):
    require(source == ROOT, 'driver belongs to requested source')
    _, imported = python_origins()
    files = [Path(__file__).resolve(), HERE / 'JarRealmObserver.java', HERE / 'JarArtifactCompletionSpy.java',
        HERE.parent / 'jar-plugin-compatibility-harness.test.mjs', ASSEMBLY_HELPER, ASSEMBLY_ADAPTER,
        HERE / 'jar-plugin-portable.py', HERE.parent / 'jar-plugin-live-compatibility.test.mjs',
        HERE.parent / 'jar-plugin-receipt-retention.test.mjs',
        source / 'pom.xml', pins]
    files += [p for p in imported if p.is_relative_to(source)]
    return hashes(files)


def runtime_hashes(maven, jdk):
    require(maven.name == 'mvn' and (maven.parent.parent / 'lib/maven-core-3.9.16.jar').is_file(), 'Maven3.9.16 required')
    require('JAVA_VERSION="21.' in (jdk / 'release').read_text(), 'JDK21 required')
    return hashes(runtime_files(jdk, maven, python_origins()[1]))


def prepare(config):
    paths = {k: Path(config[k]).absolute() for k in ('source', 'pins', 'maven', 'jdk', 'repository', 'output')}
    require(all(p.resolve() == p and not any(c.isspace() or ord(c) < 32 for c in str(p)) for p in paths.values()), 'canonical JVM-safe paths')
    require(not paths['output'].exists() and paths['output'].parent.is_dir(), 'fresh output under existing parent')
    require(all(not paths['output'].is_relative_to(paths[k]) and not paths[k].is_relative_to(paths['output'])
        for k in ('source', 'jdk', 'repository', 'pins')), 'input/output overlap')
    require(not any((p / '.mvn').exists() for key in ('source', 'output') for p in [paths[key], *paths[key].parents]), 'unexpected Maven bootstrap configuration')
    pins = read_pins(paths['pins'])
    for coordinate, expected in pins['artifactHashes'].items():
        file = artifact_path(paths['repository'], coordinate)
        require(file.is_file() and not file.is_symlink() and digest(file) == expected, 'pinned artifact: ' + coordinate)
    linkage = {variant: subset_inventory(coordinates, lambda c: artifact_path(paths['repository'], c), effective_entry)
        for variant, coordinates in pins['variants'].items()}
    for inventory in linkage.values(): require(len(linkage_rows(inventory)) <= 512, 'bounded symbolic subset')
    return {'schemaVersion': 1, 'plan': PLAN, 'config': {k: str(v) for k, v in paths.items()},
        'sourceHashes': source_hashes(paths['source'], paths['pins']),
        'runtimeHashes': runtime_hashes(paths['maven'], paths['jdk']),
        'cacheHashes': cache_snapshot(paths['repository']), 'pins': pins, 'linkage': linkage}


def check_seal(seal, complete=False):
    require(seal['schemaVersion'] == 1 and seal['plan'] == PLAN, 'exact reviewed execution plan')
    config = seal['config']
    require(set(config) == {'source', 'pins', 'maven', 'jdk', 'repository', 'output'}, 'exact sealed configuration')
    require(source_hashes(Path(config['source']), Path(config['pins'])) == seal['sourceHashes'], 'source inputs changed')
    require(read_pins(Path(config['pins'])) == seal['pins'], 'artifact pins changed')
    require({variant: subset_inventory(coordinates, lambda c: artifact_path(Path(config['repository']), c), effective_entry)
        for variant, coordinates in seal['pins']['variants'].items()} == seal['linkage'], 'effective symbolic subset changed')
    if complete:
        require(cache_snapshot(Path(config['repository'])) == seal['cacheHashes'], 'cache bytes changed')
        require(runtime_hashes(Path(config['maven']), Path(config['jdk'])) == seal['runtimeHashes'], 'runtime bytes changed')


def child(parent, name, text=None):
    node = ET.SubElement(parent, P + name)
    if text is not None: node.text = str(text)
    return node


def boot_plugin():
    root = ET.parse(ROOT / 'pom.xml').getroot()
    plugins = [p for p in root.findall('m:build/m:pluginManagement/m:plugins/m:plugin', NS)
        if p.findtext('m:artifactId', namespaces=NS) == 'spring-boot-maven-plugin']
    require(len(plugins) == 1, 'one accepted Boot plugin')
    plugin = copy.deepcopy(plugins[0]); properties = root.find('m:properties', NS)
    for element in plugin.iter():
        if element.text and element.text.startswith('${') and element.text.endswith('}'):
            value = properties.findtext('m:' + element.text[2:-1], namespaces=NS)
            require(value is not None, 'bound Boot version property'); element.text = value
    require(plugin.findtext('m:version', namespaces=NS) == '4.0.8', 'accepted Boot4.0.8')
    config = plugin.find('m:configuration', NS)
    if config is None: config = child(plugin, 'configuration')
    child(config, 'mainClass', 'JarBootFixture')
    return plugin


def fixture_pom(variant, case):
    require(variant in VERSIONS and case in ('selection', 'empty', 'incremental', 'attachments', 'duplicate', 'boot'), 'fixed fixture case')
    ET.register_namespace('', NS['m'])
    root = ET.Element(P + 'project')
    for key, value in [('modelVersion', '4.0.0'), ('groupId', 'local.jar.compatibility'), ('artifactId', 'fixture'), ('version', '1'), ('packaging', 'jar')]: child(root, key, value)
    child(child(root, 'properties'), 'project.build.outputTimestamp', FIXED_TIME)
    build = child(root, 'build'); child(build, 'finalName', 'fixture'); plugins = child(build, 'plugins')
    plugin = child(plugins, 'plugin')
    for key, value in [('groupId', 'org.apache.maven.plugins'), ('artifactId', 'maven-jar-plugin'), ('version', VERSIONS[variant])]: child(plugin, key, value)
    config = child(plugin, 'configuration'); child(config, 'outputTimestamp', FIXED_TIME)
    archive = child(config, 'archive'); child(archive, 'addMavenDescriptor', 'false')
    manifest = child(archive, 'manifest'); child(manifest, 'addDefaultEntries', 'false')
    child(child(archive, 'manifestEntries'), 'Fixture-Identity', 'jar-plugin')
    executions = child(plugin, 'executions')
    definitions = {
        'selection': [('omitted', {'classifier': 'omitted'}), ('explicit', {'classifier': 'explicit', 'addDefaultExcludes': 'true'}), ('all', {'classifier': 'all', 'addDefaultExcludes': 'false'})],
        'empty': [('empty-keep', {'classifier': 'keep'}), ('empty-skip', {'classifier': 'skip', 'skipIfEmpty': 'true'})],
        'incremental': [('main', {})], 'attachments': [('main', {}), ('attached', {'classifier': 'extra'}), ('tests', {})],
        'duplicate': [('main', {}), ('duplicate', {})], 'boot': [('main', {})],
    }
    for identifier, values in definitions[case]:
        execution = child(executions, 'execution'); child(execution, 'id', identifier)
        specific = child(execution, 'configuration')
        for key, value in values.items(): child(specific, key, value)
        if case == 'selection':
            child(child(specific, 'includes'), 'include', '**/*')
            exclusions = child(specific, 'excludes')
            for pattern in ('**/*.tmp', 'excluded/**'): child(exclusions, 'exclude', pattern)
    if case == 'boot': plugins.append(boot_plugin())
    return ET.tostring(root, encoding='utf-8', xml_declaration=True)


def write_case(directory, variant, case):
    directory.mkdir(); classes = directory / 'target/classes'; classes.mkdir(parents=True)
    (directory / 'pom.xml').write_bytes(fixture_pom(variant, case))
    contents = payloads() if case == 'selection' else {} if case in ('empty', 'boot') else {'ordinary.txt': FILES['ordinary.txt']}
    for name, data in contents.items():
        path = classes / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
        path.chmod(0o755 if name == 'bin/executable' else 0o644); os.utime(path, (FIXED_EPOCH, FIXED_EPOCH))
    if case == 'attachments':
        tests = directory / 'target/test-classes'; tests.mkdir(); (tests / 'test-fixture.txt').write_text('test-only\n')
    if case == 'boot': (directory / 'JarBootFixture.java').write_text(MAIN)
    return {str(p): digest(p) for p in directory.rglob('*') if p.is_file()}


def maven_command(config, output, project, variant, case):
    require(variant in VERSIONS and case in CASE_GOALS, 'fixed goal list')
    inside(project, output)
    goals = [BOOT + ':repackage' if g == 'boot:repackage' else JAR + VERSIONS[variant] + ':' + g for g in CASE_GOALS[case]]
    return [config['maven'], '-o', '-B', '-ntp', '-N', '-X', '--strict-checksums',
        '-s', str(output / 'empty-settings.xml'), '-gs', str(output / 'empty-settings.xml'),
        '-t', str(output / 'empty-toolchains.xml'), '-gt', str(output / 'empty-toolchains.xml'),
        '-Dmaven.repo.local=' + config['repository'], '-Dmaven.multiModuleProjectDirectory=' + str(output),
        '-Dmaven.ext.class.path=' + str(output / 'completion-spy.jar'), '-Dstyle.color=never', '-f', str(project), *goals]


def archive_members(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size < 32 * 1024 * 1024, 'bounded fixture JAR')
    members = {}; manifest = None
    with zipfile.ZipFile(path) as archive:
        require(len(archive.infolist()) <= 256, 'bounded archive member count')
        require(sum(info.file_size for info in archive.infolist()) <= 32 * 1024 * 1024
            and all(info.file_size <= LARGE_BYTES for info in archive.infolist()), 'bounded expanded fixture JAR')
        require(archive.testzip() is None, 'every member CRC verifies')
        for info in archive.infolist():
            name = info.filename; parts = PurePosixPath(name).parts
            require(name not in members and not name.startswith('/') and '..' not in parts and '\\' not in name, 'safe distinct archive members')
            require(info.date_time == FIXED_ZIP_TIME, 'fixed archive member timestamp')
            require(info.file_size <= LARGE_BYTES, 'bounded member size')
            data = archive.read(info)
            require((zlib.crc32(data) & 0xffffffff) == info.CRC, 'explicit decompressed member CRC')
            members[name] = {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data), 'crc32': info.CRC,
                'mode': (info.external_attr >> 16) & 0xffff, 'timestamp': list(info.date_time), 'directory': info.is_dir()}
            if name == 'META-INF/MANIFEST.MF':
                lines = data.decode('utf-8').replace('\r\n', '\n').splitlines(); unfolded = []
                for line in lines:
                    if line.startswith(' '): require(bool(unfolded), 'valid manifest continuation'); unfolded[-1] += line[1:]
                    elif line: unfolded.append(line)
                manifest = dict(line.split(': ', 1) for line in unfolded)
    require(manifest and manifest.get('Manifest-Version') == '1.0' and manifest.get('Fixture-Identity') == 'jar-plugin', 'fixture manifest')
    return {'members': members, 'manifest': manifest, 'archiveSha256': digest(path)}


def comparable(value):
    """Raw container hashes are evidence; the contract compares every member and manifest."""
    if isinstance(value, dict): return {k: comparable(v) for k, v in value.items() if k != 'archiveSha256'}
    if isinstance(value, list): return [comparable(v) for v in value]
    return value


def check_payload(path, expected, multi=False):
    observed = archive_members(path)
    files = {name: row for name, row in observed['members'].items() if not row['directory'] and name != 'META-INF/MANIFEST.MF'}
    require(set(files) == set(expected), 'exact packaged input selection')
    for name, data in expected.items():
        require(files[name]['sha256'] == hashlib.sha256(data).hexdigest() and files[name]['bytes'] == len(data), 'member byte identity: ' + name)
        require(stat.S_IMODE(files[name]['mode']) == (0o755 if name == 'bin/executable' else 0o644), 'source member mode: ' + name)
    require(observed['manifest'].get('Multi-Release') == ('true' if multi else None), 'automatic multi-release manifest')
    return observed


def actual_realm(stdout, owner):
    lines = stdout.splitlines(); marker = '[DEBUG] Populating class realm plugin>' + owner
    starts = [i for i, line in enumerate(lines) if line.strip() == marker]
    require(len(starts) == 1, 'one actual owner realm: ' + owner)
    result = []
    for line in lines[starts[0] + 1:]:
        match = re.fullmatch(r'\[DEBUG\]\s+Included: (\S+)\s*', line)
        if not match: break
        result.append(match[1])
    require(result and len(result) == len(set(result)), 'distinct actual realm members')
    return result


def expected_runtime_coordinates(coordinates, maven):
    # Match the existing Assembly fixture's explicit Maven-core artifact imports.
    with zipfile.ZipFile(maven.parent.parent / 'lib/maven-core-3.9.16.jar') as archive:
        extension = ET.fromstring(archive.read('META-INF/maven/extension.xml'))
    shared = {node.text for node in extension.findall('exportedArtifacts/exportedArtifact')}
    return [coordinate for coordinate in coordinates if ':'.join(coordinate.split(':')[:2]) not in shared]


def local_url(value):
    parsed = urlparse(value)
    require(parsed.scheme == 'file' and not parsed.netloc and not parsed.query and not parsed.fragment, 'local class origin')
    return Path(unquote(parsed.path)).resolve()


def effective_entry(archive, name):
    entry = name + '.class'
    multi = 'META-INF/MANIFEST.MF' in archive.namelist() and b'multi-release: true' in archive.read('META-INF/MANIFEST.MF').lower()
    if multi:
        for version in range(21, 8, -1):
            candidate = 'META-INF/versions/' + str(version) + '/' + entry
            if candidate in archive.namelist(): return candidate
    require(entry in archive.namelist(), 'loaded class entry exists'); return entry


def definition_origin(line, class_log, config, path_coordinates):
    phase, name, loader, value, loaded_hash = line.split('\t')
    require(phase in ('JAR_NATURAL', 'JAR_SYMBOLIC_LOAD') and re.fullmatch(r'[0-9a-f]{64}', loaded_hash), 'exact definition record')
    path = local_url(value)
    require(str(path) in path_coordinates or path.is_relative_to(Path(config['maven']).parent.parent / 'lib'), 'bound plugin or Maven-core origin')
    with zipfile.ZipFile(path) as archive:
        entry = effective_entry(archive, name); expected_hash = hashlib.sha256(archive.read(entry)).hexdigest()
    require(loaded_hash == expected_hash, 'effective loaded definition bytes')
    expected_origin = '] ' + name.replace('/', '.') + ' source: file:' + str(path)
    require(any(row.endswith(expected_origin) for row in class_log.splitlines()), 'exact JVM class-load origin corroboration')
    return phase, {'class': name, 'loader': loader, 'path': str(path), 'entry': entry, 'sha256': loaded_hash,
        'coordinate': path_coordinates.get(str(path))}


def observations(stdout, stderr, class_log, config, pins, variant, case, rows):
    require('JAR_OBSERVER_FAILURE' not in stderr and 'JAR_LINKAGE_FAILURE' not in stderr, 'observer or symbolic linkage failed')
    coordinates = actual_realm(stdout, JAR + VERSIONS[variant])
    expected = set(expected_runtime_coordinates(pins['variants'][variant], Path(config['maven'])))
    require(set(coordinates) == expected and JAR + 'jar:' + VERSIONS[variant] in coordinates, 'complete actual JAR realm equals pinned owner minus declared Maven imports')
    if variant == 'candidate': require(not any(c.startswith('io.airlift:aircompressor:') for c in coordinates), 'actual JAR realm has no AirCompressor')
    path_coordinates = {str(artifact_path(Path(config['repository']), c)): c for c in pins['artifactHashes']}
    realm_rows = [line.split('\t') for line in stderr.splitlines() if line.startswith('JAR_REALM_URL\t')]
    jar_rows = [row for row in realm_rows if row[1].startswith('org/apache/maven/plugins/jar/')]
    require(jar_rows and len({row[2] for row in jar_rows}) == 1, 'one observed native JAR realm')
    realm_paths = [str(local_url(row[3])) for row in jar_rows]
    require(realm_paths == [str(artifact_path(Path(config['repository']), c)) for c in coordinates], 'debug membership matches actual ordered native realm URLs')
    natural = []; symbolic = []
    for line in stderr.splitlines():
        if not line.startswith(('JAR_NATURAL\t', 'JAR_SYMBOLIC_LOAD\t')): continue
        phase, observed = definition_origin(line, class_log, config, path_coordinates)
        observed['jarRealm'] = observed['loader'] == jar_rows[0][2]
        (natural if phase == 'JAR_NATURAL' else symbolic).append(observed)
    required = {'org/apache/maven/plugins/jar/JarMojo'}
    if case == 'attachments': required.add('org/apache/maven/plugins/jar/TestJarMojo')
    if case != 'empty': required |= {'org/apache/maven/archiver/MavenArchiver', 'org/codehaus/plexus/archiver/jar/JarArchiver'}
    require(required <= {row['class'] for row in natural if row['jarRealm']}, 'required actual JAR consumers loaded')
    terminals = [line.split('\t')[1:] for line in stderr.splitlines() if line.startswith('JAR_TERMINAL\t')]
    goals = [g for g in CASE_GOALS[case] if g != 'boot:repackage']
    expected_events = [['MojoFailed' if case == 'duplicate' and i == len(goals) - 1 else 'MojoSucceeded', VERSIONS[variant], *g.split('@')] for i, g in enumerate(goals)]
    require(terminals == expected_events, 'exact JAR terminal outcomes')
    artifacts = [line.split('\t')[1:] for line in stderr.splitlines() if line.startswith('JAR_ARTIFACT\t')]
    expected_members = ['JAR_MEMBER\t' + row for row in rows if row.startswith('member\t')]
    require([line for line in stderr.splitlines() if line.startswith('JAR_MEMBER\t')] == expected_members, 'every exact subset member links')
    expected_consumers = ['JAR_CONSUMER\t' + row for row in rows if row.startswith('consumer\t')]
    require([line for line in stderr.splitlines() if line.startswith('JAR_CONSUMER\t')] == expected_consumers, 'every effective subset consumer origin')
    expected_complete = 'JAR_LINKAGE_COMPLETE\t' + '\t'.join(str(sum(row.startswith(kind + '\t') for row in rows)) for kind in ('consumer', 'type', 'member'))
    expected_complete += '\t' + hashlib.sha256(('\n'.join(rows) + '\n').encode()).hexdigest()
    require([line for line in stderr.splitlines() if line.startswith('JAR_LINKAGE_COMPLETE\t')] == [expected_complete]
        and stderr.count('JAR_MISSING_MEMBER\tPASSED') == 1, 'complete bounded linkage and absent-member negative')
    definitions = {(r['class'], r['loader'], r['path']) for r in natural + symbolic}
    for line in stderr.splitlines():
        if line.startswith('JAR_TYPE\t'):
            _, name, loader, value = line.split('\t')
            require((name, loader, str(local_url(value))) in definitions, 'symbolically resolved type has witnessed effective definition origin')
    result = {'actualJarRealm': coordinates, 'naturalOrigins': natural, 'symbolicLoadOrigins': symbolic,
        'symbolicConsumerCount': len(expected_consumers), 'symbolicMemberCount': len(expected_members),
        'symbolicLinkageIsInvocationCoverage': False, 'terminalEvents': terminals, 'artifactEvents': artifacts}
    if case == 'boot':
        boot = actual_realm(stdout, BOOT)
        require(set(boot) == set(expected_runtime_coordinates(pins['boot'], Path(config['maven']))), 'complete accepted Boot runtime realm')
        boot_rows = [row for row in realm_rows if row[1] == 'org/springframework/boot/maven/RepackageMojo']
        require([str(local_url(row[3])) for row in boot_rows] == [str(artifact_path(Path(config['repository']), c)) for c in boot], 'actual accepted Boot realm URLs')
        require({'org/springframework/boot/maven/RepackageMojo', 'org/springframework/boot/loader/tools/Repackager'} <= {row['class'] for row in natural}, 'actual Boot consumers loaded')
        result['actualBootRealm'] = boot
    return result


def execute(seal):
    require(os.environ.get('JAR_PLUGIN_COMPATIBILITY') == 'true', 'explicit reviewed execution opt-in')
    check_seal(seal, complete=True); config = seal['config']; pins = seal['pins']
    output = Path(config['output']); jdk = Path(config['jdk'])
    require(not output.exists(), 'fresh reviewed output'); output.mkdir()
    for name in ('home', 'tmp', 'observer'): (output / name).mkdir()
    (output / 'empty-settings.xml').write_text(SETTINGS); (output / 'empty-toolchains.xml').write_text(TOOLCHAINS)
    fixed = {str(output / name): digest(output / name) for name in ('empty-settings.xml', 'empty-toolchains.xml')}
    for variant in VERSIONS:
        file = output / (variant + '-members.tsv'); file.write_text('\n'.join(linkage_rows(seal['linkage'][variant])) + '\n')
        fixed[str(file)] = digest(file)
    environment = isolated_env(jdk, output); environment['MAVEN_BASEDIR'] = str(output)
    commands = Commands(output, environment, PLAN['aggregateTimeoutSeconds'])
    storage = StorageGuard(output, PLAN['outputTreeMaximumBytes'], PLAN['outputTreeMaximumEntries'],
        {variant + '-' + case + '.class-load.log': PLAN['classLoadCorroborationMaximumBytes']
            for variant in VERSIONS for case in CASE_GOALS})
    result = {'status': 'RUNNING', 'plan': PLAN, 'commands': commands.results, 'variants': {}, 'scannerEvidence': False}
    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGALRM, signal.SIGTERM, signal.SIGINT)}
    def deadline(signum, frame): raise TimeoutError('bounded JAR compatibility deadline or termination')
    def run(label, command, seconds=60, extra_env=None):
        check_seal(seal)
        require(not any((p / '.mvn').exists() for p in [output, *output.parents]), 'Maven bootstrap drift')
        require(all(Path(p).is_file() and digest(Path(p)) == h for p, h in fixed.items()), 'generated fixture input changed')
        storage.start()
        try:
            value = commands.run(label, command, seconds, extra_env)
        except BaseException as failure:
            if storage.failure is not None: raise ValueError(storage.failure) from failure
            raise
        finally:
            storage.stop()
        require(storage.failure is None, 'storage watcher violation: ' + str(storage.failure))
        storage.scan()
        require(all(Path(p).is_file() and digest(Path(p)) == h for p, h in fixed.items()), 'generated fixture input changed')
        return value
    for sig in previous: signal.signal(sig, deadline)
    signal.alarm(PLAN['aggregateTimeoutSeconds'])
    try:
        status, _, _ = run('compile-observers', [str(jdk / 'bin/javac'), '-J-Xmx128m', '-proc:none', '--release', '21',
            '-cp', os.pathsep.join(str(p) for p in sorted((Path(config['maven']).parent.parent / 'lib').glob('*.jar'))),
            '-d', str(output / 'observer'), str(HERE / 'JarRealmObserver.java'), str(HERE / 'JarArtifactCompletionSpy.java')], 30)
        require(status == 0, 'passive observer compilation')
        with zipfile.ZipFile(output / 'observer.jar', 'x') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nPremain-Class: JarRealmObserver\n\n')
            for file in sorted((output / 'observer').glob('JarRealmObserver*.class')): archive.write(file, file.name)
        with zipfile.ZipFile(output / 'completion-spy.jar', 'x') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n\n')
            archive.writestr('META-INF/plexus/components.xml', '<component-set><components><component><role>org.apache.maven.eventspy.EventSpy</role><role-hint>jar-compatibility</role-hint><implementation>JarArtifactCompletionSpy</implementation></component></components></component-set>')
            archive.write(output / 'observer/JarArtifactCompletionSpy.class', 'JarArtifactCompletionSpy.class')
        fixed.update({str(output / name): digest(output / name) for name in ('observer.jar', 'completion-spy.jar')})
        opts = '-Xmx256m -XX:-UsePerfData -Duser.home=' + str(output / 'home') + ' -Djava.io.tmpdir=' + str(output / 'tmp')
        for variant in VERSIONS:
            result['variants'][variant] = {}; incremental = None; snapshots = []
            for case in CASE_GOALS:
                family = 'incremental' if case.startswith('incremental-') else case
                directory = output / (variant + '-' + family)
                if case not in ('incremental-unchanged', 'incremental-changed'):
                    fixed.update(write_case(directory, variant, family))
                if case == 'incremental-changed':
                    path = directory / 'target/classes/ordinary.txt'; path.write_bytes(b'changed input\n')
                    os.utime(path, ns=(incremental['mtimeNs'] + 4_000_000_000,) * 2); fixed[str(path)] = digest(path)
                if case == 'boot':
                    status, _, _ = run(variant + '-compile-main', [str(jdk / 'bin/javac'), '-J-Xmx64m', '-proc:none', '--release', '21',
                        '-d', str(directory / 'target/classes'), str(directory / 'JarBootFixture.java')], 20)
                    require(status == 0, 'tiny main compilation'); fixed[str(directory / 'target/classes/JarBootFixture.class')] = digest(directory / 'target/classes/JarBootFixture.class')
                last_id = [goal for goal in CASE_GOALS[case] if goal != 'boot:repackage'][-1].split('@')[1]
                case_opts = opts + ' -javaagent:' + str(output / 'observer.jar') + '=' + str(output / (variant + '-members.tsv'))
                case_opts += ' -Djar.fixture.lastExecution=' + last_id
                class_log = output / (variant + '-' + case + '.class-load.log')
                require(not class_log.exists(), 'fresh separate JVM class-load log')
                case_opts += ' -Xlog:class+load=info:file=' + str(class_log) + ':uptime,level,tags:filecount=0'
                status, stdout, stderr = run(variant + '-' + case, maven_command(config, output, directory / 'pom.xml', variant, case), extra_env={'MAVEN_OPTS': case_opts})
                require(status == (1 if case == 'duplicate' else 0), 'actual goal exit status: ' + case)
                require(class_log.is_file() and not class_log.is_symlink()
                    and class_log.stat().st_size <= PLAN['classLoadCorroborationMaximumBytes'], 'bounded separate JVM class-load log')
                observed = observations(stdout, stderr, class_log.read_text(), config, pins, variant, case, linkage_rows(seal['linkage'][variant])); behavior = {}
                observed['classLoadCorroborationLog'] = {'path': str(class_log), 'sha256': digest(class_log), 'bytes': class_log.stat().st_size}
                target = directory / 'target'
                if case == 'selection':
                    for classifier in ('omitted', 'explicit', 'all'):
                        expected = {n: b for n, b in payloads().items() if n not in EXCLUDED and (classifier == 'all' or n not in IGNORED)}
                        path = target / ('fixture-' + classifier + '.jar')
                        behavior[classifier] = check_payload(path, expected, multi=True)
                        with zipfile.ZipFile(path) as archive:
                            compressed = archive.getinfo('large/incompressible.bin').compress_size
                            small_compressed = archive.getinfo('large/compressible.bin').compress_size
                        require(compressed > pins['offloadingThresholdBytes'], 'actual compressed member exceeds maximum offloading threshold')
                        require(0 < small_compressed < COMPRESSIBLE_BYTES, 'compressible member verifies small compressed path')
                        behavior[classifier]['offloading'] = {'maximumThresholdBytes': pins['offloadingThresholdBytes'],
                            'compressedMemberBytes': compressed, 'memberBytes': LARGE_BYTES,
                            'smallCompressedBytes': small_compressed, 'crcAndFullPayloadVerified': True}
                    require(comparable(behavior['omitted']) == comparable(behavior['explicit']), 'omitted and explicit default excludes agree')
                    require({'org/codehaus/plexus/archiver/zip/ConcurrentJarCreator', 'org/codehaus/plexus/archiver/zip/OffloadingOutputStream'}
                        <= {row['class'] for row in observed['naturalOrigins'] if row['jarRealm']}, 'actual offloading consumers loaded')
                elif case == 'empty':
                    behavior = check_payload(target / 'fixture-keep.jar', {})
                    require(not (target / 'fixture-skip.jar').exists(), 'empty skip emits no archive')
                elif family == 'incremental':
                    path = target / 'fixture.jar'; expected = b'changed input\n' if case.endswith('changed') and case != 'incremental-unchanged' else FILES['ordinary.txt']
                    behavior = check_payload(path, {'ordinary.txt': expected}); state = {'sha256': digest(path), 'mtimeNs': path.stat().st_mtime_ns}
                    if case == 'incremental-first': incremental = state
                    elif case == 'incremental-unchanged': require(state == incremental, 'unchanged input does not rewrite archive')
                    else: require(state['sha256'] != incremental['sha256'], 'changed input rebuilds archive')
                    snapshots.append(state)
                elif case == 'attachments':
                    behavior = {name: check_payload(target / ('fixture' + suffix + '.jar'), contents)
                        for name, suffix, contents in [('main', '', {'ordinary.txt': FILES['ordinary.txt']}), ('extra', '-extra', {'ordinary.txt': FILES['ordinary.txt']}), ('tests', '-tests', {'test-fixture.txt': b'test-only\n'})]}
                    actual = {tuple(row[1:4]) for row in observed['artifactEvents'] if row[0] == 'tests'}
                    require(actual == {('main', 'jar', '-'), ('attached', 'jar', 'extra'), ('attached', 'test-jar', 'tests')}, 'actual project main and classified/test attachments')
                elif case == 'duplicate':
                    require('You have to use a classifier to attach supplemental artifacts' in stdout, 'expected duplicate-main diagnostic')
                    behavior = {'failure': 'duplicate-main-artifact'}
                elif case == 'boot':
                    path = target / 'fixture.jar'; behavior = archive_members(path)
                    behavior['originalJar'] = check_payload(target / 'fixture.jar.original',
                        {'JarBootFixture.class': (target / 'classes/JarBootFixture.class').read_bytes()})
                    require(behavior['manifest'].get('Main-Class') == 'org.springframework.boot.loader.launch.JarLauncher'
                        and behavior['manifest'].get('Start-Class') == 'JarBootFixture', 'executable Boot manifest')
                    require('BOOT-INF/classes/JarBootFixture.class' in behavior['members'], 'repackaged main member')
                    status, out, _ = run(variant + '-archive-main', [str(jdk / 'bin/java'), '-Xmx64m', '-XX:-UsePerfData',
                        '-Duser.home=' + str(output / 'home'), '-Djava.io.tmpdir=' + str(output / 'tmp'), '-jar', str(path)], 20)
                    require(status == 0 and out.strip() == 'JAR_BOOT_MAIN_OK', 'tiny executable main marker')
                result['variants'][variant][case] = {'behavior': behavior, **observed}
            result['variants'][variant]['incrementalStates'] = snapshots
        for case in CASE_GOALS:
            require(comparable(result['variants']['baseline'][case]['behavior']) == comparable(result['variants']['candidate'][case]['behavior']), 'unreviewed paired output difference: ' + case)
        require(len(commands.results) == PLAN['mavenCommands'] + PLAN['javacCommands'] + PLAN['executableMainCommands'], 'mechanically exact command count')
        storage.stop(); storage.scan()
        require(storage.failure is None, 'storage watcher violation before final seals: ' + str(storage.failure))
        check_seal(seal, complete=True); result.update(status='PASSED', inputSealsUnchanged=True)
    except BaseException as failure:
        result.update(status='FAILED', errorType=type(failure).__name__, error=str(failure)); raise
    finally:
        signal.alarm(0)
        storage.stop(); result['outputTreeStorage'] = storage.report()
        for sig, handler in previous.items(): signal.signal(sig, handler)
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print('JAR_PLUGIN_RESULT ' + json.dumps({'status': result['status'], 'path': str(output / 'result.json'), 'error': result.get('error')}), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--prepare-seal', type=Path); parser.add_argument('--execute-seal', type=Path)
    for name in ('source', 'pins', 'maven', 'jdk', 'repository', 'output'): parser.add_argument('--' + name)
    args = parser.parse_args()
    if args.prepare_seal:
        require(not args.execute_seal and not args.prepare_seal.exists(), 'fresh preparation seal')
        seal = prepare(vars(args)); args.prepare_seal.write_text(json.dumps(seal, indent=2) + '\n')
        print(json.dumps({'status': 'PREPARED_NOT_EXECUTED', 'seal': str(args.prepare_seal)}))
    elif args.execute_seal:
        before = digest(args.execute_seal)
        try: execute(json.loads(args.execute_seal.read_text()))
        finally: require(digest(args.execute_seal) == before, 'reviewed seal file changed')
    else: print(json.dumps(PLAN))


if __name__ == '__main__': main()
